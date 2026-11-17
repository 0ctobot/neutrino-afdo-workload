package org.neutrino.afdo.workload;

import android.os.Handler;
import android.view.FrameMetrics;
import android.view.Window;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The window's frame metrics while a case runs, with the reports the system
 * dropped and the longest gap between frames (vsync timestamps, from start()
 * to stop()): aggregate counts alone don't rule out long stalls.
 */
final class Frames {
    private final Case c;
    private final Window window;
    private final AtomicInteger frames = new AtomicInteger(), dropped = new AtomicInteger();
    private final long[] ns = new long[1 << 16];
    private Window.OnFrameMetricsAvailableListener listener;
    private volatile long startMono, stopMono, lastVsync, maxGapNs;

    Frames(Case c, Window window) { this.c = c; this.window = window; }

    void start(Handler h) {
        startMono = System.nanoTime();
        lastVsync = startMono;
        listener = (w, fm, droppedSince) -> {
            if (c.runStartNs == 0 || c.runEndNs != 0) return;
            int n = frames.getAndIncrement();
            if (n < ns.length) ns[n] = fm.getMetric(FrameMetrics.TOTAL_DURATION);
            dropped.addAndGet(droppedSince);
            long v = fm.getMetric(FrameMetrics.VSYNC_TIMESTAMP);
            if (v > lastVsync) { maxGapNs = Math.max(maxGapNs, v - lastVsync); lastVsync = v; }
        };
        window.addOnFrameMetricsAvailableListener(listener, h);
    }

    void stop() {
        if (listener == null) return;
        try { window.removeOnFrameMetricsAvailableListener(listener); } catch (RuntimeException ignored) {}
        listener = null;
        stopMono = System.nanoTime();
    }

    /** The longest stretch without a frame, including from start() to the first and from the last to stop(). */
    long maxGapMs() {
        long end = stopMono != 0 ? stopMono : System.nanoTime();
        return Math.max(maxGapNs, end - lastVsync) / 1_000_000;
    }

    void needNoStall(long ms) {
        c.need(maxGapMs() < ms, "no frame for " + maxGapMs() + " ms");
    }

    int count() { return frames.get(); }

    /** frames, lost frame reports, fps and frame-time percentiles into the case's evidence. */
    void report(String prefix) {
        int n = frames.get();
        double s = c.activeSeconds();
        c.put(c.evidence, prefix + "frames", n);
        c.put(c.evidence, prefix + "frame_reports_dropped", dropped.get());
        c.put(c.evidence, prefix + "fps", s > 0 ? n / s : 0);
        c.put(c.evidence, prefix + "max_frame_gap_ms", maxGapMs());
        long[] d = Arrays.copyOf(ns, Math.min(n, ns.length));
        Arrays.sort(d);
        if (d.length > 0) {
            c.put(c.evidence, prefix + "frame_ms_p50", d[d.length / 2] / 1e6);
            c.put(c.evidence, prefix + "frame_ms_p95", d[(int) (d.length * 0.95)] / 1e6);
        }
    }
}
