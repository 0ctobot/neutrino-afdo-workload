package android.os;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Host stub: a queue of runnables; the main looper is drained by the test, others by their thread. */
public class Looper {
    public static final Looper MAIN = new Looper();
    final LinkedBlockingQueue<Runnable> q = new LinkedBlockingQueue<>();
    volatile boolean quit;

    /** Run the main queue until it stays empty for ms. */
    public static void drainMain(long ms) throws InterruptedException {
        Runnable r;
        while ((r = MAIN.q.poll(ms, TimeUnit.MILLISECONDS)) != null) r.run();
    }

    void loop() {
        while (!quit) {
            try {
                Runnable r = q.poll(20, TimeUnit.MILLISECONDS);
                if (r != null) r.run();
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
