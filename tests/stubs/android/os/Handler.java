package android.os;

public class Handler {
    final Looper l;
    public Handler(Looper l) { this.l = l; }
    public boolean post(Runnable r) { if (l.quit) return false; l.q.add(r); return true; }
    public Looper getLooper() { return l; }
}
