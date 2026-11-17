package org.neutrino.afdo.workload;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One attempt of one case: the PREPARE -> READY -> RUN -> DONE -> VALIDATE
 * protocol suite.sh drives.  The state file (files/cases/RUN/KEY/state) holds
 * one word: PREPARING, READY, RUNNING, DONE, VALIDATED, FAILED or SAFETY.
 * result.json is written before VALIDATED, FAILED or SAFETY.  Only the work
 * between RUN and DONE is measured: preparation happens before the runner
 * arms the trace, and the work is stopped (stop(), its threads joined) before
 * DONE is published, so validation and cleanup run after the trace stops.
 *
 * The end of an attempt is reserved once and published once: SAFETY wins
 * over FAILED, which wins over VALIDATED, until the terminal state is
 * written; after that nothing changes it, and late ready()/done()/fail()
 * calls are ignored.  A safety stop also writes the file "safety" at once,
 * before any cleanup, so the runner can stop the run without waiting.
 * Resources are released only after every thread that uses them has
 * stopped; when one does not stop in time, nothing is released and the
 * runner's force-stop ends the process.
 */
abstract class Case {
    static final int PROTOCOL = 1;
    static final String TAG = "AfdoWorkload";
    private static final long STOP_MS = 8000;
    private static final int NONE = 0, VALIDATED = 1, FAILED = 2, SAFETY = 3;
    private static final Map<String, Case> LIVE = new HashMap<>();

    final Run activity;
    final Params p;
    final File dir;
    final Random random;
    final Handler main;
    final HandlerThread workerThread;
    final Handler worker;
    final JSONObject requested = new JSONObject();
    final JSONObject completed = new JSONObject();
    final JSONObject evidence = new JSONObject();
    final List<String> problems = Collections.synchronizedList(new ArrayList<>());
    volatile long runStartNs, runEndNs;
    /** Set when the work must stop (the measured interval ended, or the attempt is ending). */
    volatile boolean cancelled;
    // Guarded by this: the protocol state, the end reserved for the attempt
    // and whether it was published.
    private String state = "";
    private int ending = NONE;
    private boolean validating, published;
    private final List<Thread> threads = new ArrayList<>();
    private int stopped;              // main thread: 0 not yet, 1 stopped, 2 did not stop

    Case(Run activity, Params p) {
        this.activity = activity;
        this.p = p;
        this.dir = new File(activity.getFilesDir(), "cases/" + p.run + "/" + p.key());
        this.random = new Random(p.seed ^ p.key().hashCode());
        this.main = new Handler(activity.getMainLooper());
        this.workerThread = new HandlerThread("case-" + p.scenario);
        workerThread.start();
        this.worker = new Handler(workerThread.getLooper());
    }

    // ---- what a scenario implements --------------------------------------------------

    /** Set up everything the measured interval needs, then call ready(). Main thread. */
    abstract void prepare() throws Exception;

    /** Start the measured work; call done() when it ends (normally at deadline()). Main thread. */
    abstract void run() throws Exception;

    /**
     * Stop the measured work (loops, animations, rendering, streams) and
     * return true once nothing of it runs any more.  Main thread, once, with
     * cancelled already set: before DONE, or before cleanup when the attempt
     * fails.  Threads started with spawn() are joined after it and the worker
     * handler is drained, so it only has to stop what those don't cover.
     */
    boolean stop() { return true; }

    /** After the trace stopped: check the evidence and add problems. Worker thread. */
    abstract void validate() throws Exception;

    /** Release everything (also after a failure); only called once the work stopped. Main thread. */
    void cleanup() {}

    // ---- protocol ------------------------------------------------------------------------

    static synchronized Case live(String key) { return LIVE.get(key); }

    void start() {
        synchronized (Case.class) { LIVE.put(p.key(), this); }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            Log.e(TAG, "cannot create " + dir);
            return;
        }
        if (!advance("", "PREPARING")) return;
        if (p.error != null) { fail(p.error); return; }
        try {
            put(requested, "seconds", p.seconds);
            prepare();
        } catch (Exception e) {
            fail("prepare: " + e);
        }
    }

    void ready() { advance("PREPARING", "READY"); }

    void onRun() {
        String s = currentState();
        if (!"READY".equals(s)) { fail("RUN in state " + s); return; }
        runStartNs = SystemClock.elapsedRealtimeNanos();
        if (!advance("READY", "RUNNING")) return;
        try {
            run();
        } catch (Exception e) {
            fail("run: " + e);
        }
    }

    /** The end of the measured interval: stops the work, then publishes DONE. Main thread. */
    void done() {
        if (!"RUNNING".equals(currentState()) || isEnding()) return;
        runEndNs = SystemClock.elapsedRealtimeNanos();
        if (!stopWork()) { fail("the work did not stop within " + STOP_MS / 1000 + " s"); return; }
        advance("RUNNING", "DONE");
    }

    void onValidate() {
        boolean ok;
        synchronized (this) {
            if (ending != NONE) return;
            ok = "DONE".equals(state);
            validating = ok;
        }
        if (!ok) { fail("VALIDATE in state " + currentState()); return; }
        worker.post(() -> {
            try {
                validate();
            } catch (Exception e) {
                problems.add("validate: " + e);
            }
            synchronized (Case.this) {
                validating = false;
                if (ending == NONE) ending = VALIDATED;
            }
            main.post(this::publish);
        });
    }

    void onAbort() { fail("aborted by the runner"); }

    /** A failure of the case: record it and stop (the runner may retry). Any thread. */
    void fail(String why) {
        Log.w(TAG, p.key() + ": " + why);
        end(FAILED, why);
    }

    /** A safety limit of the case itself (memory, storage): the runner aborts the run. Any thread. */
    void safety(String why) {
        Log.w(TAG, p.key() + ": safety: " + why);
        end(SAFETY, "safety: " + why);
    }

    private void end(int kind, String why) {
        boolean post;
        synchronized (this) {
            if (published) return;                     // terminal states are final
            problems.add(why);
            if (kind <= ending) return;
            post = ending == NONE && !validating;      // a validation in progress publishes when it ends
            ending = kind;
        }
        cancelled = true;
        if (kind == SAFETY) write("safety", why + "\n");
        if (post) main.post(this::publish);
    }

    /** Stop the work, clean up and write the result and the terminal state. Main thread. */
    private void publish() {
        synchronized (this) { if (published) return; }
        if (stopWork()) {
            try { cleanup(); } catch (RuntimeException e) { problems.add("cleanup: " + e); }
        } else {
            problems.add("the work did not stop within " + STOP_MS / 1000 + " s: nothing released (the runner force-stops the app)");
        }
        int kind;
        synchronized (this) {
            kind = ending;                             // a safety stop during cleanup still wins
            published = true;
        }
        String status = kind == SAFETY ? "safety" : kind == FAILED ? "fail" : problems.isEmpty() ? "ok" : "fail";
        result(status);
        synchronized (this) {
            state = kind == SAFETY ? "SAFETY" : kind == FAILED ? "FAILED" : "VALIDATED";
            Log.i(TAG, p.key() + " " + state);
            write("state", state + "\n");
        }
        finish();
    }

    private boolean stopWork() {
        if (stopped != 0) return stopped == 1;
        cancelled = true;
        boolean ok;
        try {
            ok = stop();
        } catch (RuntimeException e) {
            problems.add("stop: " + e);
            ok = false;
        }
        long until = SystemClock.uptimeMillis() + STOP_MS;
        ok &= joinThreads(until) & drainWorker(until);
        stopped = ok ? 1 : 2;
        return ok;
    }

    private synchronized boolean advance(String from, String to) {
        if (ending != NONE || !state.equals(from)) return false;
        state = to;
        Log.i(TAG, p.key() + " " + to);
        write("state", to + "\n");
        return true;
    }

    private synchronized String currentState() { return state; }
    private synchronized boolean isEnding() { return ending != NONE; }

    private void finish() {
        synchronized (Case.class) { LIVE.remove(p.key()); }
        workerThread.quitSafely();
        activity.caseFinished(this);
    }

    // ---- threads ------------------------------------------------------------------------

    /** A thread of the measured work: joined before DONE and before cleanup. */
    Thread spawn(String name, Runnable body) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (RuntimeException e) {
                fail(name + ": " + e);
            }
        }, name);
        synchronized (threads) { threads.add(t); }
        t.start();
        return t;
    }

    private boolean joinThreads(long untilMs) {
        List<Thread> ts;
        synchronized (threads) { ts = new ArrayList<>(threads); }
        boolean ok = true;
        for (Thread t : ts) {
            if (t == Thread.currentThread()) continue;
            t.interrupt();
            ok &= await(t, untilMs - SystemClock.uptimeMillis());
        }
        return ok;
    }

    /** Wait up to ms for a thread to end. */
    static boolean await(Thread t, long ms) {
        if (t == null) return true;
        try {
            if (ms > 0) t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !t.isAlive();
    }

    /** Wait until everything already posted to the worker handler has run. */
    private boolean drainWorker(long untilMs) {
        return onWorker(() -> {}, untilMs - SystemClock.uptimeMillis());
    }

    /** Run r on the worker thread and wait up to ms for it (directly when already there). */
    boolean onWorker(Runnable r, long ms) {
        if (Thread.currentThread() == workerThread) { r.run(); return true; }
        CountDownLatch l = new CountDownLatch(1);
        if (!worker.post(() -> { try { r.run(); } finally { l.countDown(); } })) return true;   // the worker has quit
        try {
            return l.await(Math.max(0, ms), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    long elapsedNs() { return SystemClock.elapsedRealtimeNanos() - runStartNs; }
    long deadlineNs() { return runStartNs + p.seconds * 1_000_000_000L; }
    boolean pastDeadline() { return SystemClock.elapsedRealtimeNanos() >= deadlineNs(); }
    double activeSeconds() { return (runEndNs - runStartNs) / 1e9; }

    void need(boolean ok, String what) { if (!ok) problems.add(what); }

    /** Thread-safe: cases record from their worker threads. */
    void put(JSONObject o, String k, Object v) {
        synchronized (o) {
            try { o.put(k, v); } catch (JSONException e) { throw new IllegalStateException(e); }
        }
    }

    /** A copy of o's entries as they are now (cases may still be adding to it from another thread). */
    private static JSONObject copy(JSONObject o) {
        JSONObject c = new JSONObject();
        synchronized (o) {
            try {
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    c.put(k, o.opt(k));
                }
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
        return c;
    }

    private void result(String status) {
        JSONObject r = new JSONObject();
        put(r, "protocol", PROTOCOL);
        put(r, "status", status);
        put(r, "run", p.run);
        put(r, "key", p.key());
        put(r, "scenario", p.scenario);
        put(r, "case", p.caseId);
        put(r, "rep", p.rep);
        put(r, "attempt", p.attempt);
        put(r, "seed", p.seed);
        put(r, "run_start_ns", runStartNs);
        put(r, "run_end_ns", runEndNs);
        put(r, "active_s", runEndNs > runStartNs ? activeSeconds() : 0);
        put(r, "requested", copy(requested));
        put(r, "completed", copy(completed));
        put(r, "evidence", copy(evidence));
        synchronized (problems) { put(r, "problems", new JSONArray(new ArrayList<>(problems))); }
        JSONObject d = new JSONObject();
        put(d, "model", Build.MODEL);
        put(d, "device", Build.DEVICE);
        put(d, "sdk", Build.VERSION.SDK_INT);
        put(d, "fingerprint", Build.FINGERPRINT);
        android.content.pm.PackageInfo wv = WebView.getCurrentWebViewPackage();
        put(d, "webview", wv == null ? "" : wv.packageName + " " + wv.versionName);
        put(d, "app_version", BuildInfo.versionName(activity));
        put(r, "device", d);
        try {
            write("result.json", r.toString(1) + "\n");
        } catch (JSONException e) {
            write("result.json", r + "\n");
        }
    }

    /** Atomic write of a small file into the case directory. */
    void write(String name, String text) {
        File tmp = new File(dir, name + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        } catch (IOException e) {
            Log.e(TAG, "write " + name + ": " + e);
            return;
        }
        if (!tmp.renameTo(new File(dir, name))) Log.e(TAG, "rename " + name);
    }

    /** The parameters of one attempt, validated: nothing unbounded reaches a scenario. */
    static final class Params {
        final String run, scenario, caseId, peer, token;
        final int rep, attempt, seconds;
        final long seed;
        final String error;

        Params(android.content.Intent i) {
            run = id(i.getStringExtra("run"), "run");
            scenario = id(i.getStringExtra("scenario"), "scenario");
            caseId = id(i.getStringExtra("case"), "case");
            rep = bounded(i.getIntExtra("rep", -1), 1, 99, "rep");
            attempt = bounded(i.getIntExtra("attempt", -1), 1, 9, "attempt");
            seed = i.getLongExtra("seed", 0);
            // Past the identifiers, a bad parameter fails the case (with a result)
            // instead of leaving the runner to time out.
            String err = null;
            int s = i.getIntExtra("seconds", -1);
            if (s < 1 || s > 900) err = "seconds out of range: " + s;
            seconds = Math.max(1, Math.min(900, s));
            int protocol = i.getIntExtra("protocol", -1);
            if (protocol != PROTOCOL) err = "protocol " + protocol + ", this app speaks " + PROTOCOL;
            String pe = i.getStringExtra("peer");
            if (pe != null && !pe.matches("(\\d{1,3}\\.){3}\\d{1,3}:\\d{1,5}")) { err = "peer must be an IPv4 address:port"; pe = null; }
            peer = pe;
            String t = i.getStringExtra("token");
            if (t != null && !t.matches("[A-Za-z0-9]{8,64}")) { err = "bad token"; t = null; }
            token = t;
            error = err;
        }

        String key() { return scenario + "." + caseId + "." + rep + "." + attempt; }

        private static String id(String s, String what) {
            if (s == null || !s.matches("[A-Za-z0-9_-]{1,64}"))
                throw new IllegalArgumentException("bad " + what + ": " + s);
            return s;
        }

        private static int bounded(int v, int lo, int hi, String what) {
            if (v < lo || v > hi) throw new IllegalArgumentException(what + " out of range: " + v);
            return v;
        }
    }
}
