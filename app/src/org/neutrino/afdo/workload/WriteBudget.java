package org.neutrino.afdo.workload;

/**
 * A write budget, checked before every write: take(n) charges n bytes or
 * refuses when they don't fit.  paced(n, fraction) also refuses while the
 * bytes charged would run ahead of the elapsed fraction of a time window
 * (plus a small burst), so the budget spreads over the window instead of
 * being spent at its start.  Plain Java: tested on the host.
 */
final class WriteBudget {
    static final long BURST = 256 << 10;
    final String name;
    final long limit;
    private long used;

    WriteBudget(String name, long limit) {
        this.name = name;
        this.limit = limit;
    }

    synchronized boolean take(long n) {
        if (n < 0 || used + n > limit) return false;
        used += n;
        return true;
    }

    synchronized boolean paced(long n, double fraction) {
        double f = Math.max(0, Math.min(1, fraction));
        if (n < 0 || used + n > Math.min(limit, (long) (limit * f) + BURST)) return false;
        used += n;
        return true;
    }

    synchronized long used() { return used; }
}
