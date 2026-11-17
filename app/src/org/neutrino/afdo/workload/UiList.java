package org.neutrino.afdo.workload;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ui-list: a long list of images (decoded from the APK's assets off the main
 * thread, through a small cache so decoding keeps going) and text, driven by
 * in-process touch gestures (Gestures: drags and flings dispatched to the list
 * itself, never injected into the system).
 * The list is effectively endless in both directions, so the scrolling never
 * hits an end.  Evidence: gestures, items scrolled each way, decodes during
 * the measured interval (those of preparation are recorded apart), frames and
 * the longest gap between frames.
 */
final class UiList extends Case {
    private static final int MIDDLE = 1 << 20;
    private ListView list;
    private String[] images;
    private String[] texts;
    private ExecutorService decoder;
    private LruCache<String, Bitmap> cache;
    private Frames frames;
    private final AtomicInteger decodes = new AtomicInteger(), prepDecodes = new AtomicInteger();
    private final AtomicLong decodedBytes = new AtomicLong();
    private int gestures, lastFirst, down, up, scrollState;
    private float density;

    UiList(Run a, Params p) { super(a, p); }

    @Override
    void prepare() throws IOException {
        images = activity.getAssets().list("images");
        if (images == null || images.length == 0) { fail("no images in the APK"); return; }
        Arrays.sort(images);
        String[] words = ("kernel scheduler page cache binder surface frame render buffer queue device memory "
                + "reclaim fault vsync display codec network socket storage journal").split(" ");
        texts = new String[257];
        for (int i = 0; i < texts.length; i++) {
            StringBuilder b = new StringBuilder();
            int n = 8 + random.nextInt(40);
            for (int w = 0; w < n; w++) b.append(words[random.nextInt(words.length)]).append(' ');
            texts[i] = b.toString();
        }
        density = activity.getResources().getDisplayMetrics().density;
        cache = new LruCache<String, Bitmap>(24 << 20) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getAllocationByteCount(); }
        };
        decoder = Executors.newFixedThreadPool(2);
        list = new ListView(activity);
        list.setAdapter(new Adapter());
        list.setSelection(MIDDLE);
        activity.setContentView(list);
        frames = new Frames(this, activity.getWindow());
        list.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                list.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                lastFirst = list.getFirstVisiblePosition();
                ready();
            }
        });
        put(requested, "images", images.length);
    }

    @Override
    void run() {
        frames.start(worker);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { scrollState = s; }
            @Override public void onScroll(AbsListView v, int first, int visible, int total) {
                if (first > lastFirst) down += first - lastFirst; else up += lastFirst - first;
                lastFirst = first;
            }
        });
        nextGesture();
    }

    // One gesture (Gestures.swipe), then wait for the list to settle.
    private void nextGesture() {
        if (cancelled) return;
        if (pastDeadline()) { done(); return; }
        Gestures.swipe(list, random, random.nextInt(10) < 7, random.nextInt(10) < 6, () -> {
            gestures++;
            settle(android.os.SystemClock.uptimeMillis());
        });
    }

    private void settle(long since) {
        main.postDelayed(() -> {
            if (cancelled) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE && now - since < 2500) settle(since);
            else main.postDelayed(this::nextGesture, 100 + random.nextInt(400));
        }, 50);
    }

    /** No more gestures (cancelled is set), frame reports or decodes. */
    @Override
    boolean stop() {
        if (frames != null) frames.stop();
        if (decoder == null) return true;
        decoder.shutdownNow();
        try {
            return decoder.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    void validate() {
        double s = activeSeconds();
        int n = frames.count();
        put(completed, "gestures", gestures);
        put(completed, "items_down", down);
        put(completed, "items_up", up);
        put(completed, "decodes", decodes.get());
        put(evidence, "decoded_bytes", decodedBytes.get());
        put(evidence, "preparation_decodes", prepDecodes.get());
        put(evidence, "refresh_hz", activity.getDisplay().getRefreshRate());
        frames.report("");
        // Liveness floors (not throughput): the work kept going for the whole interval.
        need(gestures >= Math.max(1, (int) (s / 3)), "too few gestures: " + gestures + " in " + s + " s");
        need(n >= 5 * s, "too few frames: " + n + " in " + s + " s");
        need(decodes.get() > 0, "no image decodes during the measured interval");
        frames.needNoStall(3000);
        if (s >= 10) need(down > 0 && up > 0, "did not scroll both ways (down " + down + ", up " + up + ")");
    }

    @Override
    void cleanup() {
        if (cache != null) cache.evictAll();
        if (list != null) list.setAdapter(null);
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return 2 * MIDDLE; }
        @Override public Object getItem(int i) { return i; }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.VERTICAL);
                int pad = (int) (12 * density);
                row.setPadding(pad, pad, pad, pad);
                ImageView img = new ImageView(activity);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (200 * density)));
                TextView t = new TextView(activity);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                row.addView(t);
            }
            ImageView img = (ImageView) row.getChildAt(0);
            ((TextView) row.getChildAt(1)).setText(texts[pos % texts.length]);
            String name = images[pos % images.length];
            int sample = 1 + pos % 3;
            String k = name + "@" + sample;
            img.setTag(k);
            Bitmap b = cache.get(k);
            if (b != null) {
                img.setImageBitmap(b);
            } else {
                img.setImageDrawable(null);
                img.setBackgroundColor(Color.rgb(40 + pos % 60, 60, 80));
                decoder.execute(() -> decode(name, sample, k, img));
            }
            return row;
        }
    }

    private void decode(String name, int sample, String k, ImageView img) {
        if (cancelled) return;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = activity.getAssets().open("images/" + name)) {
            b = BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            return;
        }
        if (b == null) return;
        if (runStartNs != 0 && runEndNs == 0) {
            decodes.incrementAndGet();
            decodedBytes.addAndGet(b.getAllocationByteCount());
        } else {
            prepDecodes.incrementAndGet();
        }
        main.post(() -> {
            if (cancelled) return;
            cache.put(k, b);
            if (k.equals(img.getTag())) img.setImageBitmap(b);
        });
    }
}
