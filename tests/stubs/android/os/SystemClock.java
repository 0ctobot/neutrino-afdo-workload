package android.os;

public class SystemClock {
    public static long elapsedRealtimeNanos() { return System.nanoTime(); }
    public static long elapsedRealtime() { return System.nanoTime() / 1_000_000; }
    public static long uptimeMillis() { return System.nanoTime() / 1_000_000; }
    public static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
