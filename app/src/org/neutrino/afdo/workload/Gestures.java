package org.neutrino.afdo.workload;

import android.os.SystemClock;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/**
 * In-process touch gestures: a DOWN, a MOVE every frame along an eased path
 * and an UP, dispatched to one of the app's own views (which then drags or
 * flings by the velocity it measured).  Nothing is injected into the system,
 * so no gesture can ever reach another app.
 */
final class Gestures {
    private Gestures() {}

    /** Swipe vertically; forward = content moves up (scrolls down). Calls done() after the UP. */
    static void swipe(View v, Random r, boolean forward, boolean fling, Runnable done) {
        int h = v.getHeight(), w = v.getWidth();
        float x = w * (0.3f + 0.4f * r.nextFloat());
        float span = h * (0.35f + 0.4f * r.nextFloat());
        float y0 = forward ? h * 0.8f : h * 0.2f, y1 = forward ? y0 - span : y0 + span;
        long dur = (fling ? 90 : 300) + r.nextInt(fling ? 120 : 400);
        long t0 = SystemClock.uptimeMillis();
        dispatch(v, t0, t0, MotionEvent.ACTION_DOWN, x, y0);
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override public void doFrame(long frameTimeNanos) {
                if (!v.isAttachedToWindow()) return;
                long now = SystemClock.uptimeMillis();
                float f = Math.min(1f, (now - t0) / (float) dur);
                float e = f * f * (3 - 2 * f);
                if (f < 1f) {
                    dispatch(v, t0, now, MotionEvent.ACTION_MOVE, x, y0 + (y1 - y0) * e);
                    Choreographer.getInstance().postFrameCallback(this);
                } else {
                    dispatch(v, t0, now, MotionEvent.ACTION_UP, x, y1);
                    done.run();
                }
            }
        });
    }

    private static void dispatch(View v, long downTime, long time, int action, float x, float y) {
        MotionEvent ev = MotionEvent.obtain(downTime, time, action, x, y, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        v.dispatchTouchEvent(ev);
        ev.recycle();
    }
}
