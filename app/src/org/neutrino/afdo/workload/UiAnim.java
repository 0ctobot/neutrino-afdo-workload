package org.neutrino.afdo.workload;

import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * ui-anim: the kind of UI work apps do between inputs: property animations
 * (translation, rotation, alpha, scale) on many views with elevation shadows
 * over each other (overdraw), plus one view resized every frame (relayout).
 * The animations run between RUN and DONE only.  Evidence: animation ticks,
 * frames, and the longest gap between frames.
 */
final class UiAnim extends Case {
    private FrameLayout root;
    private View resizing;
    private Frames frames;
    private final List<ValueAnimator> animators = new ArrayList<>();
    private int ticks, layouts;

    UiAnim(Run a, Params p) { super(a, p); }

    @Override
    void prepare() {
        root = new FrameLayout(activity);
        root.setBackgroundColor(Color.rgb(24, 26, 30));
        activity.setContentView(root);
        float d = activity.getResources().getDisplayMetrics().density;
        for (int i = 0; i < 48; i++) {
            View v = new View(activity);
            v.setBackgroundColor(Color.HSVToColor(200, new float[]{i * 7.5f, 0.6f, 0.9f}));
            v.setElevation((2 + i % 12) * d);
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline o) {
                    o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 16 * d);
                }
            });
            v.setClipToOutline(true);
            int size = (int) ((60 + random.nextInt(140)) * d);
            root.addView(v, new FrameLayout.LayoutParams(size, size));
        }
        resizing = new View(activity);
        resizing.setBackgroundColor(Color.argb(160, 250, 250, 250));
        root.addView(resizing, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (80 * d)));
        frames = new Frames(this, activity.getWindow());
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                ready();
            }
        });
    }

    @Override
    void run() {
        frames.start(worker);
        int w = root.getWidth(), h = root.getHeight();
        for (int i = 0; i < root.getChildCount() - 1; i++) {
            View v = root.getChildAt(i);
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(1200 + random.nextInt(2400));
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.setInterpolator(new LinearInterpolator());
            float x0 = random.nextFloat() * w, y0 = random.nextFloat() * h;
            float x1 = random.nextFloat() * w, y1 = random.nextFloat() * h;
            a.addUpdateListener(an -> {
                float f = (float) an.getAnimatedValue();
                v.setTranslationX(x0 + (x1 - x0) * f - v.getWidth() / 2f);
                v.setTranslationY(y0 + (y1 - y0) * f - v.getHeight() / 2f);
                v.setRotation(360 * f);
                v.setAlpha(0.4f + 0.6f * f);
                v.setScaleX(0.7f + 0.6f * f);
                v.setScaleY(0.7f + 0.6f * f);
            });
            animators.add(a);
            a.start();
        }
        ValueAnimator relayout = ValueAnimator.ofInt(h / 10, h / 2);
        relayout.setDuration(900);
        relayout.setRepeatCount(ValueAnimator.INFINITE);
        relayout.setRepeatMode(ValueAnimator.REVERSE);
        relayout.addUpdateListener(an -> {
            ViewGroup.LayoutParams lp = resizing.getLayoutParams();
            lp.height = (int) an.getAnimatedValue();
            resizing.setLayoutParams(lp);
            layouts++;
            ticks++;
        });
        animators.add(relayout);
        relayout.start();
        main.postDelayed(this::done, p.seconds * 1000L);
    }

    @Override
    boolean stop() {
        for (ValueAnimator a : animators) a.cancel();
        if (frames != null) frames.stop();
        return true;
    }

    @Override
    void validate() {
        double s = activeSeconds();
        put(completed, "animation_ticks", ticks);
        put(completed, "relayouts", layouts);
        put(completed, "animated_views", animators.size());
        frames.report("");
        need(ticks >= 10 * s, "too few animation ticks: " + ticks + " in " + s + " s");
        need(frames.count() >= 10 * s, "too few frames: " + frames.count() + " in " + s + " s");
        frames.needNoStall(3000);
    }
}
