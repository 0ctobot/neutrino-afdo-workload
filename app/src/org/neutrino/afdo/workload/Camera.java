package org.neutrino.afdo.workload;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * camera: the back camera through Camera2, in two phases.  First (70%) a
 * preview to a SurfaceView plus a YUV stream an app reads (each frame's luma
 * is sampled, then the image closed at once), with a JPEG still every four
 * seconds written to private storage.  Then (30%) video-only recording of the
 * preview size through MediaRecorder.  The stream combination is checked
 * (isSessionConfigurationSupported): case "main" needs preview+YUV+JPEG and
 * fails without it; case "preview-jpeg", for a device qualified without the
 * YUV stream, runs preview+JPEG.  The camera objects are used on the worker
 * thread only, and every callback first checks that they weren't released,
 * so nothing touches a released object; they are released before DONE.
 * Evidence: YUV images and their timestamps, stills written, and the
 * recorded file parsed after the trace stops (a video track, its duration
 * and frame count).
 */
final class Camera extends Case {
    // Worker thread only.
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader yuv, jpeg;
    private MediaRecorder recorder;
    private boolean released, recording, recorderRunning;
    private long lastTs = -1, yuvSum;
    private int nonMonotonic, captureFailures;
    private long recordStartNs, recordEndNs;
    private String combo = "";

    private SurfaceView sv;
    private volatile Surface preview;
    private Size size, jpegSize;
    private File video;
    private final AtomicInteger yuvImages = new AtomicInteger(), stills = new AtomicInteger();
    private volatile boolean counting;
    private String cameraId = "";

    Camera(Run a, Params p) { super(a, p); }

    @Override
    void prepare() throws Exception {
        CameraManager cm = activity.getSystemService(CameraManager.class);
        for (String id : cm.getCameraIdList()) {
            Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) { cameraId = id; break; }
        }
        if (cameraId.isEmpty()) { fail("no back camera"); return; }
        StreamConfigurationMap map = cm.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) { fail("no stream configuration map"); return; }
        size = pick(map.getOutputSizes(SurfaceHolder.class), 1920, 1080);
        jpegSize = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()));
        video = new File(dir, "video.mp4");
        sv = new SurfaceView(activity);
        sv.getHolder().setFixedSize(size.getWidth(), size.getHeight());
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { preview = h.getSurface(); worker.post(() -> open(cm)); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { if (!cancelled && runEndNs == 0) fail("preview surface destroyed"); }
        });
        activity.setContentView(sv);
        put(requested, "camera", cameraId);
        put(requested, "preview", size.toString());
        put(requested, "jpeg", jpegSize.toString());
    }

    private static Size pick(Size[] sizes, int maxW, int maxH) {
        List<Size> ok = new ArrayList<>();
        for (Size s : sizes) if (s.getWidth() <= maxW && s.getHeight() <= maxH) ok.add(s);
        if (ok.isEmpty()) ok.addAll(Arrays.asList(sizes));
        return Collections.max(ok, Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()));
    }

    @SuppressWarnings("MissingPermission")
    private void open(CameraManager cm) {
        if (released) return;
        try {
            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    if (released) { d.close(); return; }
                    device = d;
                    startPreview();
                }
                @Override public void onDisconnected(CameraDevice d) { lost(d, "camera disconnected"); }
                @Override public void onError(CameraDevice d, int e) { lost(d, "camera error " + e); }
            }, worker);
        } catch (Exception e) {
            fail("open camera: " + e);
        }
    }

    private void lost(CameraDevice d, String why) {
        d.close();
        if (d == device) device = null;
        if (!cancelled) fail(why);
    }

    private void startPreview() {
        try {
            boolean withYuv = !"preview-jpeg".equals(p.caseId);
            jpeg = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
            jpeg.setOnImageAvailableListener(this::onJpeg, worker);
            List<Surface> outs;
            if (withYuv) {
                yuv = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 4);
                yuv.setOnImageAvailableListener(this::onYuv, worker);
                outs = Arrays.asList(preview, yuv.getSurface(), jpeg.getSurface());
                combo = "preview+yuv+jpeg";
            } else {
                outs = Arrays.asList(preview, jpeg.getSurface());
                combo = "preview+jpeg";
            }
            SessionConfiguration sc = session(outs);
            Boolean supported = null;
            try { supported = device.isSessionConfigurationSupported(sc); } catch (UnsupportedOperationException e) { /* can't be asked: try it */ }
            if (Boolean.FALSE.equals(supported)) {
                fail(combo + " is not supported" + (withYuv ? " (a device without it needs the camera case preview-jpeg)" : ""));
                return;
            }
            put(requested, "streams", combo);
            put(requested, "streams_checked", supported != null);
            device.createCaptureSession(sc);
        } catch (Exception e) {
            fail("preview: " + e);
        }
    }

    private void onYuv(ImageReader r) {
        if (released) return;
        Image im = r.acquireLatestImage();
        if (im == null) return;
        if (counting) {
            ByteBuffer y = im.getPlanes()[0].getBuffer();
            long s = 0;
            for (int i = 0; i < y.limit(); i += 4099) s += y.get(i) & 0xff;
            yuvSum += s;
            long ts = im.getTimestamp();
            if (ts <= lastTs) nonMonotonic++;
            lastTs = ts;
            yuvImages.incrementAndGet();
        }
        im.close();
    }

    private void onJpeg(ImageReader r) {
        if (released) return;
        Image im = r.acquireNextImage();
        if (im == null) return;
        ByteBuffer b = im.getPlanes()[0].getBuffer();
        byte[] data = new byte[b.remaining()];
        b.get(data);
        im.close();
        File f = new File(dir, "still" + stills.get() + ".jpg");
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(data);
            stills.incrementAndGet();
        } catch (IOException e) {
            problems.add("still: " + e);
        }
        if (!f.delete()) problems.add("delete " + f);
    }

    private SessionConfiguration session(List<Surface> outs) {
        List<OutputConfiguration> oc = new ArrayList<>();
        for (Surface s : outs) oc.add(new OutputConfiguration(s));
        Executor ex = worker::post;
        return new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, oc, ex, new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession s) {
                if (released) { s.close(); return; }
                session = s;
                try {
                    CaptureRequest.Builder b = device.createCaptureRequest(recording ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
                    for (Surface t : outs) if (jpeg == null || t != jpeg.getSurface()) b.addTarget(t);
                    s.setRepeatingRequest(b.build(), counter, worker);
                    if (recording) {
                        recorder.start();
                        recorderRunning = true;
                        recordStartNs = android.os.SystemClock.elapsedRealtimeNanos();
                    } else if (runStartNs == 0) {
                        main.post(Camera.this::ready);
                    }
                } catch (Exception e) {
                    fail("repeating request: " + e);
                }
            }
            @Override public void onConfigureFailed(CameraCaptureSession s) { if (!released) fail("session configuration failed (" + combo + ")"); }
        });
    }

    private final CameraCaptureSession.CaptureCallback counter = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult t) {}
        @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, android.hardware.camera2.CaptureFailure f) {
            if (counting) captureFailures++;
        }
    };

    @Override
    void run() {
        counting = true;
        long phase1 = p.seconds * 700L;
        still();
        main.postDelayed(this::record, phase1);
    }

    private void still() {
        worker.postDelayed(() -> {
            if (released || cancelled || recording || session == null) return;
            try {
                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(preview);
                b.addTarget(jpeg.getSurface());
                session.capture(b.build(), counter, worker);
            } catch (Exception e) {
                problems.add("still capture: " + e);
            }
            still();
        }, 4000);
    }

    private void record() {
        worker.post(() -> {
            if (released || cancelled) return;
            try {
                recording = true;
                session.stopRepeating();
                session.close();
                session = null;
                recorder = new MediaRecorder(activity);
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC);
                recorder.setVideoSize(size.getWidth(), size.getHeight());
                recorder.setVideoFrameRate(30);
                recorder.setVideoEncodingBitRate(10_000_000);
                recorder.setOutputFile(video);
                recorder.prepare();
                device.createCaptureSession(session(Arrays.asList(preview, recorder.getSurface())));
            } catch (Exception e) {
                fail("record: " + e);
                return;
            }
            main.postDelayed(this::stopRecording, Math.max(1000, deadlineNs() / 1_000_000 - android.os.SystemClock.elapsedRealtime()));
        });
    }

    private void stopRecording() {
        worker.post(() -> {
            if (released) return;
            try {
                if (session != null) session.stopRepeating();
                if (recorderRunning) { recorderRunning = false; recorder.stop(); }
                recordEndNs = android.os.SystemClock.elapsedRealtimeNanos();
            } catch (Exception e) {
                problems.add("stop recording: " + e);
            }
            counting = false;
            main.post(this::done);
        });
    }

    /** The camera, session, recorder and readers are released on the worker, before DONE. */
    @Override
    boolean stop() {
        counting = false;
        return onWorker(this::release, 5000);
    }

    private void release() {
        if (released) return;
        released = true;
        try { if (session != null) session.close(); } catch (RuntimeException ignored) {}
        session = null;
        if (recorder != null) {
            if (recorderRunning) { recorderRunning = false; try { recorder.stop(); } catch (RuntimeException ignored) {} }
            recorder.release();
            recorder = null;
        }
        if (device != null) { device.close(); device = null; }
        if (yuv != null) { yuv.close(); yuv = null; }
        if (jpeg != null) { jpeg.close(); jpeg = null; }
    }

    @Override
    void validate() throws IOException {
        double s = activeSeconds(), phase1 = 0.7 * s, rec = (recordEndNs - recordStartNs) / 1e9;
        put(completed, "yuv_images", yuvImages.get());
        put(completed, "stills", stills.get());
        put(completed, "record_seconds", rec);
        put(evidence, "yuv_fps", phase1 > 0 ? yuvImages.get() / phase1 : 0);
        put(evidence, "yuv_luma_sum", yuvSum);
        put(evidence, "timestamps_out_of_order", nonMonotonic);
        put(evidence, "capture_failures", captureFailures);
        if (combo.contains("yuv")) need(yuvImages.get() >= 5 * phase1, "too few YUV images: " + yuvImages.get());
        need(stills.get() >= Math.max(1, (int) (phase1 / 4) - 1), "too few stills: " + stills.get());
        need(nonMonotonic == 0, nonMonotonic + " image timestamp(s) out of order");
        // The recording: a video track with frames, about as long as it ran.
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(video.getPath());
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++)
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("video/")) track = i;
            need(track >= 0, "no video track in the recording");
            if (track >= 0) {
                ex.selectTrack(track);
                MediaFormat f = ex.getTrackFormat(track);
                long dur = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
                int frames = 0;
                while (ex.getSampleTime() >= 0) { frames++; ex.advance(); }
                put(completed, "recorded_frames", frames);
                put(evidence, "recorded_seconds", dur / 1e6);
                put(evidence, "recorded_bytes", video.length());
                need(dur / 1e6 >= 0.6 * rec, "recording is " + dur / 1e6 + " s long, recorded for " + rec + " s");
                need(frames >= 10 * rec, "recording has " + frames + " frames for " + rec + " s");
            }
        } finally {
            ex.release();
        }
    }

    /** The recording (complete or not) is deleted: only the result is kept. */
    @Override
    void cleanup() {
        if (video != null && video.exists() && !video.delete()) problems.add("delete " + video);
    }
}
