package org.neutrino.afdo.workload;

import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * video-local / video-stream: a fixed clip decoded by the hardware decoder to
 * a SurfaceView in real time, looped for the whole interval.  video-local
 * plays HEVC 1080p60 from the APK; video-stream plays AV1 1080p30 fetched
 * from the LAN peer in 512 KiB ranges, at most a few chunks ahead of
 * playback, so the transfer keeps going while it plays (the first chunk,
 * which holds the moov box of the faststart clip, stays pinned; the rest is a
 * rolling window, so every loop streams the media data again).  The feeder
 * and the decoder are stopped before DONE.  Evidence: the decoder
 * (hardware), frames actually rendered (OnFrameRenderedListener) and the
 * media time played across loops; for the stream, bytes, requests, bytes
 * fetched again, and the share of 5 s windows with a transfer.
 */
final class Video extends Case {
    private static final int CHUNK = 512 * 1024, AHEAD = 6, BEHIND = 8, PINNED = 1;
    private static final long WINDOW_NS = 5_000_000_000L;
    private final boolean stream;
    private final String asset;
    private SurfaceView sv;
    private Surface surface;
    private MediaExtractor ex;
    private MediaCodec codec;
    private String decoder = "", mime = "";
    private boolean hardware;
    private long durationUs, frameUs = 33_333;
    private Peer peer;
    private Source source;
    private Thread feeder;
    private boolean codecStarted;
    private final AtomicInteger rendered = new AtomicInteger();
    private final AtomicLong playedUs = new AtomicLong();
    private volatile long lastRenderedPts = -1;
    private int loops;

    Video(Run a, Params p, boolean stream) {
        super(a, p);
        this.stream = stream;
        this.asset = stream ? "media/av1-1080p30.mp4" : "media/hevc-1080p60.mp4";
    }

    @Override
    void prepare() {
        sv = new SurfaceView(activity);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                surface = h.getSurface();
                worker.post(Video.this::open);
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { if (!cancelled && runEndNs == 0) fail("surface destroyed"); }
        });
        activity.setContentView(sv);
        put(requested, "clip", asset);
    }

    private void open() {
        try {
            ex = new MediaExtractor();
            if (stream) {
                peer = new Peer(this);
                String why = peer.acquire();
                if (why != null) { fail(why); return; }
                source = new Source("/media/" + asset.substring(asset.indexOf('/') + 1));
                ex.setDataSource(source);
            } else {
                try (AssetFileDescriptor fd = activity.getAssets().openFd(asset)) {
                    ex.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                }
            }
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) { track = i; mime = m; break; }
            }
            if (track < 0) { fail("no video track in " + asset); return; }
            ex.selectTrack(track);
            MediaFormat f = ex.getTrackFormat(track);
            durationUs = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
            if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) frameUs = 1_000_000L / Math.max(1, f.getInteger(MediaFormat.KEY_FRAME_RATE));
            for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (ci.isEncoder() || !ci.isHardwareAccelerated() || ci.getName().endsWith(".secure")) continue;
                for (String t : ci.getSupportedTypes()) {
                    if (t.equalsIgnoreCase(mime) && ci.getCapabilitiesForType(t).isFormatSupported(f)) { decoder = ci.getName(); break; }
                }
                if (!decoder.isEmpty()) break;
            }
            if (decoder.isEmpty()) { fail("no hardware decoder for " + mime); return; }
            hardware = true;
            codec = MediaCodec.createByCodecName(decoder);
            codec.setOnFrameRenderedListener((c, ptsUs, nanoTime) -> {
                if (runStartNs == 0 || runEndNs != 0) return;
                rendered.incrementAndGet();
                long last = lastRenderedPts;
                if (last >= 0 && ptsUs > last) playedUs.addAndGet(ptsUs - last);
                lastRenderedPts = ptsUs;
            }, worker);
            codec.configure(f, surface, null, 0);
            put(requested, "decoder", decoder);
            put(requested, "mime", mime);
            put(requested, "width", f.getInteger(MediaFormat.KEY_WIDTH));
            put(requested, "height", f.getInteger(MediaFormat.KEY_HEIGHT));
            main.post(this::ready);
        } catch (IOException | RuntimeException | InterruptedException e) {
            fail("open: " + e);
        }
    }

    @Override
    void run() {
        codec.start();
        codecStarted = true;
        feeder = spawn("video-feed", this::play);
    }

    /** Stop the feeder (a blocked range read is disconnected), then the decoder. */
    @Override
    boolean stop() {
        if (source != null) source.cancel();
        if (peer != null) peer.cancel();
        if (!await(feeder, 5000)) return false;
        if (codecStarted) {
            codecStarted = false;
            try { codec.stop(); } catch (RuntimeException e) { problems.add("codec stop: " + e); }
        }
        return true;
    }

    /** Real-time playback loop: queue samples, release outputs at their presentation time. */
    private void play() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long baseNs = System.nanoTime(), offsetUs = 0, lastPts = 0;
        try {
            while (!cancelled && !pastDeadline()) {
                int in = codec.dequeueInputBuffer(5000);
                if (in >= 0) {
                    ByteBuffer b = codec.getInputBuffer(in);
                    int n = ex.readSampleData(b, 0);
                    if (n < 0) {                         // end of the clip: loop, timestamps keep rising
                        offsetUs = lastPts + frameUs;
                        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        loops++;
                        n = ex.readSampleData(b, 0);
                    }
                    if (n < 0) { if (!cancelled) fail("cannot read the clip"); return; }
                    lastPts = ex.getSampleTime() + offsetUs;
                    codec.queueInputBuffer(in, 0, n, lastPts, 0);
                    ex.advance();
                }
                int out = codec.dequeueOutputBuffer(info, 5000);
                if (out >= 0) {
                    if (info.size > 0) {
                        long due = baseNs + info.presentationTimeUs * 1000L;
                        long wait = due - System.nanoTime();
                        if (wait > 2_000_000) Thread.sleep(Math.min(wait / 1_000_000, 500));
                        codec.releaseOutputBuffer(out, Math.max(due, System.nanoTime()));
                    } else {
                        codec.releaseOutputBuffer(out, false);
                    }
                }
            }
            main.post(this::done);
        } catch (InterruptedException | RuntimeException e) {
            if (!cancelled) fail("playback: " + e);
        }
    }

    @Override
    void validate() {
        double s = activeSeconds();
        double fps = mime.equals("video/av01") ? 30 : 60;
        put(completed, "frames_rendered", rendered.get());
        put(completed, "media_seconds_played", playedUs.get() / 1e6);
        put(completed, "loops", loops);
        put(evidence, "decoder", decoder);
        put(evidence, "hardware", hardware);
        put(evidence, "clip_seconds", durationUs / 1e6);
        put(evidence, "render_fps", s > 0 ? rendered.get() / s : 0);
        need(hardware, "not a hardware decoder");
        need(playedUs.get() / 1e6 >= 0.9 * s, "played " + playedUs.get() / 1e6 + " s of media in " + s + " s");
        need(rendered.get() >= 0.8 * fps * s, "rendered " + rendered.get() + " frames in " + s + " s");
        if (stream) {
            int w = (int) Math.max(1, Math.round(s * 1e9 / WINDOW_NS)), active = 0;
            for (int i = 0; i < Math.min(w, source.windows.length); i++) if (source.windows[i] > 0) active++;
            put(completed, "bytes_received", source.bytes.get());
            put(completed, "bytes_during_run", source.duringRun.get());
            put(completed, "requests", source.requests.get());
            put(evidence, "bytes_refetched", source.refetched.get());
            synchronized (source) {
                put(evidence, "read_back_jumps", source.backJumps);
                put(evidence, "read_max_back_chunks", source.maxBack);
            }
            put(evidence, "transfer_windows", w);
            put(evidence, "transfer_windows_active", active);
            put(evidence, "fetch_errors", source.errors.get());
            peer.report();
            need(source.bytes.get() > 0, "nothing received");
            need(source.errors.get() == 0, source.errors.get() + " fetch error(s)");
            need(source.duringRun.get() > 0, "no transfer during playback");
            if (s >= 15) need(active >= 0.8 * w, "transfers in only " + active + " of " + w + " 5 s windows");
        }
    }

    @Override
    void cleanup() {
        try { if (codec != null) codec.release(); } catch (RuntimeException ignored) {}
        if (ex != null) ex.release();
        if (source != null) source.close();
        if (peer != null) peer.release();
    }

    /** Ranged HTTP reads from the peer, with a bounded read-ahead in 512 KiB chunks. */
    private final class Source extends MediaDataSource {
        final String path;
        final AtomicLong bytes = new AtomicLong(), duringRun = new AtomicLong(), refetched = new AtomicLong();
        final AtomicInteger requests = new AtomicInteger(), errors = new AtomicInteger();
        final long[] windows = new long[256];                  // bytes per 5 s window of the interval
        private final Map<Long, byte[]> chunks = new HashMap<>();   // guarded by this
        private final java.util.Set<Long> fetched = new java.util.HashSet<>();
        private volatile HttpURLConnection inflight;
        private long lastChunk = -1, backJumps, maxBack;   // the extractor's read pattern (guarded by this)
        private volatile boolean closed;
        private long size = -1;

        Source(String path) throws IOException {
            this.path = path;
            HttpURLConnection h = peer.open(path);
            h.setRequestMethod("HEAD");
            requests.incrementAndGet();
            if (h.getResponseCode() != 200) throw new IOException("HEAD " + path + ": " + h.getResponseCode());
            size = h.getContentLengthLong();
            h.disconnect();
            peer.done(h);
        }

        @Override public long getSize() { return size; }

        @Override
        public synchronized int readAt(long pos, byte[] buf, int off, int len) throws IOException {
            if (pos >= size) return -1;
            long ci = pos / CHUNK;
            if (lastChunk >= 0 && ci < lastChunk) { backJumps++; maxBack = Math.max(maxBack, lastChunk - ci); }
            lastChunk = ci;
            byte[] c = chunk(ci);
            int at = (int) (pos - ci * CHUNK), n = Math.min(len, c.length - at);
            System.arraycopy(c, at, buf, off, n);
            // keep a bounded window around the read position (and the pinned head)
            chunks.keySet().removeIf(k -> k >= PINNED && (k < ci - BEHIND || k > ci + AHEAD));
            for (long k = ci + 1; k <= ci + 2 && k * CHUNK < size; k++) chunk(k);
            return n;
        }

        private byte[] chunk(long ci) throws IOException {
            byte[] c = chunks.get(ci);
            if (c != null) return c;
            if (closed) throw new IOException("closed");
            long from = ci * CHUNK, to = Math.min(size, from + CHUNK) - 1;
            HttpURLConnection h = peer.open(path);
            h.setRequestProperty("Range", "bytes=" + from + "-" + to);
            requests.incrementAndGet();
            inflight = h;
            try {
                if (h.getResponseCode() != 206) { errors.incrementAndGet(); throw new IOException("range: " + h.getResponseCode()); }
                c = new byte[(int) (to - from + 1)];
                try (InputStream in = h.getInputStream()) {
                    int got = 0, r;
                    while (got < c.length && (r = in.read(c, got, c.length - got)) > 0) got += r;
                    if (got != c.length) { errors.incrementAndGet(); throw new IOException("short range"); }
                }
            } finally {
                inflight = null;
                h.disconnect();
                peer.done(h);
            }
            bytes.addAndGet(c.length);
            if (!fetched.add(ci)) refetched.addAndGet(c.length);
            if (runStartNs != 0 && runEndNs == 0) {
                duringRun.addAndGet(c.length);
                int w = (int) (elapsedNs() / WINDOW_NS);
                if (w >= 0 && w < windows.length) windows[w] += c.length;
            }
            chunks.put(ci, c);
            return c;
        }

        /** From any thread: no more reads, and a blocked one is disconnected. */
        void cancel() {
            closed = true;
            HttpURLConnection h = inflight;
            if (h != null) h.disconnect();
        }

        @Override public void close() {
            cancel();
            synchronized (this) { chunks.clear(); }
        }
    }
}
