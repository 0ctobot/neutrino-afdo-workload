package org.neutrino.afdo.workload;

import android.content.Intent;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Host tests of the case protocol (Case.java against stubs: the main looper
 * is drained by the test, the worker is a real thread) and of WriteBudget.
 */
public class CaseTest {
    static int failures, n;
    static File root;

    static void check(boolean ok, String what) {
        n++;
        if (!ok) failures++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static final class T extends Case {
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        boolean stopResult = true, autoReady = true;
        Runnable onRun, onValidate;

        T(Intent i) { super(new Run(root), new Params(i)); }

        String file(String name) {
            try { return new String(Files.readAllBytes(new File(dir, name).toPath())).trim(); } catch (IOException e) { return null; }
        }

        @Override void prepare() { log.add("prepare"); if (autoReady) ready(); }
        @Override void run() { log.add("run"); if (onRun != null) onRun.run(); }
        @Override boolean stop() { log.add("stop " + file("state")); return stopResult; }
        @Override void validate() { log.add("validate"); if (onValidate != null) onValidate.run(); }
        @Override void cleanup() { log.add("cleanup safety-marker=" + new File(dir, "safety").exists()); }
    }

    static int seq;

    static T make() { return make(Case.PROTOCOL); }

    static T make(int protocol) {
        Intent i = new Intent().put("run", "r1").put("scenario", "test").put("case", "c" + (++seq))
                .put("rep", 1).put("attempt", 1).put("seconds", 10).put("protocol", protocol).put("seed", 7L);
        return new T(i);
    }

    static void drain() throws InterruptedException { Looper.drainMain(300); }

    static String status(T c) {
        String r = c.file("result.json");
        if (r == null) return null;
        int i = r.indexOf("\"status\":\"");
        return i < 0 ? "?" : r.substring(i + 10, r.indexOf('"', i + 10));
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("case-test").toFile();

        T c = make();
        c.start(); drain();
        check("READY".equals(c.file("state")), "prepare reaches READY");
        c.onRun(); c.safety("low memory"); c.fail("a late failure"); drain();
        check("SAFETY".equals(c.file("state")) && "safety".equals(status(c)), "SAFETY then FAILED before publishing ends SAFETY");
        check(c.log.contains("cleanup safety-marker=true"), "the safety marker is written before cleanup");

        c = make();
        c.start(); c.onRun(); c.fail("first"); c.safety("then unsafe"); drain();
        check("SAFETY".equals(c.file("state")), "FAILED then SAFETY before publishing ends SAFETY");

        c = make();
        c.start(); c.onRun(); c.fail("failed"); drain();
        c.safety("after publishing"); c.ready(); c.done(); drain();
        check("FAILED".equals(c.file("state")) && "fail".equals(status(c)) && c.file("safety") == null,
                "a published FAILED is final: later safety/ready/done change nothing");

        c = make();
        c.start(); c.onRun(); c.done(); drain();
        check(c.log.contains("stop RUNNING") && "DONE".equals(c.file("state")), "done() stops the work before DONE is published");
        check(!c.log.contains("cleanup safety-marker=false"), "no cleanup before validation");
        c.onValidate(); drain(); Thread.sleep(100); drain();
        check("VALIDATED".equals(c.file("state")) && "ok".equals(status(c)), "VALIDATE ends VALIDATED/ok");
        check(c.log.indexOf("validate") < c.log.indexOf("cleanup safety-marker=false"), "cleanup runs after validation");

        c = make();
        c.stopResult = false;
        c.start(); c.onRun(); c.done(); drain();
        check("FAILED".equals(c.file("state")), "work that does not stop fails the attempt instead of DONE");
        check(c.log.stream().noneMatch(s -> s.startsWith("cleanup")), "nothing is released when the work did not stop");

        c = make();
        T v = c;
        c.onValidate = () -> v.fail("failure during validation");
        c.start(); c.onRun(); c.done(); drain(); c.onValidate(); drain(); Thread.sleep(100); drain();
        check("FAILED".equals(c.file("state")), "a failure during validation ends FAILED, not VALIDATED");

        c = make();
        T w = c;
        c.onValidate = () -> w.safety("unsafe during validation");
        c.start(); c.onRun(); c.done(); drain(); c.onValidate(); drain(); Thread.sleep(100); drain();
        check("SAFETY".equals(c.file("state")), "a safety stop during validation ends SAFETY");

        c = make();
        T x = c;
        long[] ended = new long[1];
        c.onRun = () -> x.spawn("busy", () -> {
            long until = System.nanoTime() + 300_000_000L;
            while (System.nanoTime() < until) { /* ignores interrupts */ }
            ended[0] = System.nanoTime();
        });
        c.start(); c.onRun();
        long t0 = System.nanoTime();
        c.done();
        long t1 = System.nanoTime();
        drain();
        check(ended[0] != 0 && ended[0] <= t1 && "DONE".equals(c.file("state")), "done() joins spawned threads before DONE");
        check(t1 - t0 < 2_000_000_000L, "the join is not longer than the thread");

        c = make();
        c.start(); c.onRun(); c.onRun(); drain();
        check("FAILED".equals(c.file("state")), "RUN twice fails the attempt");

        c = make(Case.PROTOCOL + 1);
        c.start(); drain();
        check("FAILED".equals(c.file("state")) && "fail".equals(status(c)), "a protocol mismatch fails with a result");

        c = make();
        c.autoReady = false;
        c.start(); c.fail("prepare failed"); drain(); c.ready(); drain();
        check("FAILED".equals(c.file("state")), "a late ready() after FAILED is ignored");

        WriteBudget b = new WriteBudget("t", 10);
        check(b.take(6) && !b.take(5) && b.take(4) && !b.take(1) && b.used() == 10, "WriteBudget.take refuses what doesn't fit");
        WriteBudget pb = new WriteBudget("p", 4L << 20);
        int ok0 = 0;
        while (pb.paced(4096, 0)) ok0++;
        check(ok0 * 4096L == WriteBudget.BURST, "WriteBudget.paced allows only the burst at the start of the window");
        int ok5 = 0;
        while (pb.paced(4096, 0.5)) ok5++;
        check(pb.used() == (2L << 20) + WriteBudget.BURST, "WriteBudget.paced follows the elapsed fraction");
        while (pb.paced(4096, 1.0)) ok5++;
        check(pb.used() == 4L << 20 && !pb.paced(1, 2.0), "WriteBudget.paced never passes the limit");

        System.out.println(failures == 0 ? "all " + n + " passed" : failures + " of " + n + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
