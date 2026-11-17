package org.neutrino.afdo.workload;

import android.annotation.SuppressLint;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;

/**
 * ui-web: a WebView (Chromium, with its separate renderer process) on a long
 * local page from the APK (text, images, tables, CSS animation), scrolled by
 * in-process flings and drags.  The scrolling direction turns around every
 * PHASE (a quarter of the interval, at least 10 s) and near either end of the
 * page, so the page moves both ways for the whole interval.  Nothing but the
 * APK's own assets can load: every other request and navigation is refused.
 * Evidence: page loaded, gestures, the distance travelled each way, the
 * share of 5 s windows in which the page moved, scroll positions (a position
 * that can't be read is counted, never guessed), host-window frames and the
 * longest gap between them; the WebView provider and version are recorded.
 */
final class UiWeb extends Case {
    private static final String PAGE = "file:///android_asset/web/page.html";
    private WebView web;
    private Frames frames;
    private static final long WINDOW_NS = 5_000_000_000L;
    private boolean forward = true, loaded;
    private int gestures, reversals, blocked, positionErrors, windows, movedWindows, phase;
    private double minPos = 1, maxPos = 0, lastPos = -1, travelDown, travelUp;
    private long window = -1;
    private double windowMin, windowMax;

    UiWeb(Run a, Params p) { super(a, p); }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    void prepare() {
        web = new WebView(activity);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);        // only for reading the scroll position
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                blocked++;
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("file:///android_asset/")) return null;
                blocked++;
                return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", null,
                        new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (loaded) return;
                loaded = true;
                main.postDelayed(UiWeb.this::ready, 500);
            }
        });
        activity.setContentView(web);
        frames = new Frames(this, activity.getWindow());
        web.loadUrl(PAGE);
        android.content.pm.PackageInfo wv = WebView.getCurrentWebViewPackage();
        put(requested, "webview", wv == null ? "" : wv.packageName + " " + wv.versionName);
    }

    @Override
    void run() {
        frames.start(worker);
        next();
    }

    private void next() {
        if (cancelled) return;
        if (pastDeadline()) { done(); return; }
        web.evaluateJavascript("(window.scrollY/Math.max(1,document.documentElement.scrollHeight-window.innerHeight))",
                v -> {
                    if (cancelled) return;
                    long phaseNs = Math.max(10_000_000_000L, p.seconds * 250_000_000L);
                    int ph = (int) (elapsedNs() / phaseNs);
                    if (ph != phase) { phase = ph; forward = !forward; reversals++; }
                    double pos;
                    try { pos = Double.parseDouble(v); } catch (NumberFormatException e) { pos = Double.NaN; }
                    if (Double.isNaN(pos) || pos < 0 || pos > 1.0001) {
                        positionErrors++;
                    } else {
                        position(pos);
                        if (forward && pos > 0.92 || !forward && pos < 0.08) { forward = !forward; reversals++; }
                    }
                    boolean dir = random.nextInt(10) < 8 == forward;
                    Gestures.swipe(web, random, dir, random.nextInt(10) < 7, () -> {
                        gestures++;
                        main.postDelayed(this::next, 500 + random.nextInt(600));
                    });
                });
    }

    // A scroll position read during the interval: travel each way and movement per window.
    private void position(double pos) {
        minPos = Math.min(minPos, pos);
        maxPos = Math.max(maxPos, pos);
        if (lastPos >= 0) {
            if (pos > lastPos) travelDown += pos - lastPos; else travelUp += lastPos - pos;
        }
        lastPos = pos;
        long w = elapsedNs() / WINDOW_NS;
        if (w != window) {
            closeWindow();
            window = w;
            windowMin = windowMax = pos;
        } else {
            windowMin = Math.min(windowMin, pos);
            windowMax = Math.max(windowMax, pos);
        }
    }

    private void closeWindow() {
        if (window < 0) return;
        windows++;
        if (windowMax - windowMin > 0.005) movedWindows++;
    }

    @Override
    boolean stop() {
        if (frames != null) frames.stop();
        if (web != null) web.onPause();
        return true;
    }

    @Override
    void validate() {
        double s = activeSeconds();
        closeWindow();
        window = -1;
        put(completed, "gestures", gestures);
        put(completed, "reversals", reversals);
        put(completed, "travel_down", travelDown);
        put(completed, "travel_up", travelUp);
        put(evidence, "scroll_min", minPos);
        put(evidence, "scroll_max", maxPos);
        put(evidence, "windows", windows);
        put(evidence, "windows_moved", movedWindows);
        put(evidence, "position_errors", positionErrors);
        put(evidence, "blocked_requests", blocked);
        frames.report("host_");
        need(loaded, "page did not load");
        need(gestures >= Math.max(1, (int) (s / 3)), "too few gestures: " + gestures + " in " + s + " s");
        need(maxPos - minPos > 0.05, "the page barely moved");
        need(positionErrors <= gestures / 10, positionErrors + " scroll position(s) could not be read");
        if (s >= 15) need(movedWindows >= 0.8 * windows, "the page moved in only " + movedWindows + " of " + windows + " 5 s windows");
        if (s >= 30) need(travelDown > 0.02 && travelUp > 0.02, "did not scroll both ways (down " + travelDown + ", up " + travelUp + ")");
        need(frames.count() >= 5 * s, "too few frames: " + frames.count() + " in " + s + " s");
        frames.needNoStall(3000);
    }

    @Override
    void cleanup() {
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
    }
}
