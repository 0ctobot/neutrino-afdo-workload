package android.os;

public class HandlerThread extends Thread {
    final Looper l = new Looper();
    @SuppressWarnings("this-escape")
    public HandlerThread(String name) { super(name); setDaemon(true); }
    @Override public void run() { l.loop(); }
    public Looper getLooper() { return l; }
    public boolean quitSafely() { l.quit = true; return true; }
}
