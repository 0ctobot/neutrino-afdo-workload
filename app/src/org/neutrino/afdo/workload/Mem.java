package org.neutrino.afdo.workload;

import android.os.SharedMemory;
import android.os.SystemClock;
import android.system.ErrnoException;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * mem-fixture: allocation and churn of app memory, bounded and live-checked.
 * Mostly private anonymous memory (libafdomem: mmap, written page by page,
 * by thirds nearly empty, text-like and random, so zram sees mixed
 * compressibility), plus a smaller shmem part (SharedMemory, written from
 * Java), plus Java-heap churn.  The fixture grows in 64 MiB regions while
 * MemAvailable stays above a floor and the total under a cap, then churns
 * them in a seeded mix of the kernel work app allocators cause: releasing
 * part of a region (madvise MADV_DONTNEED) and touching it again (faults,
 * zeroed pages), unmapping a region and mapping a new one, rewriting a
 * region (refaulting whatever was swapped out) and re-reading one.  Safety,
 * checked before every region operation: below the soft floor it stops
 * growing and frees regions; below the hard floor it stops with SAFETY.
 * The memory thread is joined before DONE and before anything is unmapped
 * (a native touch or verify never runs on a released region).  This is
 * allocation and churn: whether a run reaches reclaim is an outcome.  Mandatory evidence is the work itself (regions, pages
 * written and verified, passes, stamps correct after the trace stops);
 * swap, refault and reclaim counters are outcomes, recorded but not
 * required.  Whether a run reaches reclaim is for qualification to show.
 */
final class Mem extends Case {
    private static final long REGION = 64L << 20;
    private long cap, softFloor, hardFloor, memTotal;
    private final List<long[]> anon = new ArrayList<>();          // {addr, bytes, kind, pass}
    private final List<SharedMemory> shm = new ArrayList<>();
    private final List<Integer> shmPass = new ArrayList<>();
    private long pagesWritten, pagesVerified, peak, minAvail = Long.MAX_VALUE, heapChurn, released;
    private int passes, freedForHeadroom, grows, remaps;
    private Map<String, Long> vmBefore, vmAfter;

    Mem(Run a, Params p) { super(a, p); }

    static native long nativeAlloc(long bytes);
    static native void nativeFree(long addr, long bytes);
    static native long nativeTouch(long addr, long bytes, int kind, int pass, long seed);
    static native long nativeVerify(long addr, long bytes, int pass);
    static native long nativePageSize();
    static native int nativeRelease(long addr, long off, long len);

    @Override
    void prepare() throws IOException {
        System.loadLibrary("afdomem");
        Map<String, Long> m = meminfo();
        memTotal = m.getOrDefault("MemTotal", 0L);
        if (memTotal == 0) { fail("cannot read /proc/meminfo"); return; }
        cap = Math.min(3L << 30, memTotal / 4);
        softFloor = Math.max(1L << 30, memTotal / 12);
        hardFloor = Math.max(512L << 20, memTotal / 32);
        put(requested, "cap_bytes", cap);
        put(requested, "soft_floor_bytes", softFloor);
        put(requested, "hard_floor_bytes", hardFloor);
        put(requested, "region_bytes", REGION);
        put(requested, "page_size", nativePageSize());
        put(requested, "shmem_share", 0.2);
        ready();
    }

    @Override
    void run() {
        vmBefore = vmstat();
        spawn("mem", this::work);
    }

    private void work() {
        try {
            int seq = 0;
            while (!cancelled && !pastDeadline()) {
                long avail = meminfo().getOrDefault("MemAvailable", 0L);
                minAvail = Math.min(minAvail, avail);
                if (avail < hardFloor) {
                    freeHalf();
                    long again = meminfo().getOrDefault("MemAvailable", 0L);
                    if (again < hardFloor) { safety("MemAvailable " + (again >> 20) + " MiB below the hard floor"); return; }
                    continue;
                }
                if (avail < softFloor && !anon.isEmpty()) { freeOne(); freedForHeadroom++; continue; }
                long total = total();
                if (total + REGION <= cap && avail - REGION > softFloor) {
                    grow(seq++);
                } else if (!anon.isEmpty()) {
                    churn();
                } else {
                    SystemClock.sleep(50);
                }
                // Java heap churn: short-lived arrays the GC has to collect.
                byte[] junk = new byte[(1 + random.nextInt(8)) << 20];
                junk[random.nextInt(junk.length)] = 1;
                heapChurn += junk.length;
            }
            main.post(this::done);
        } catch (IOException | ErrnoException | RuntimeException e) {
            fail("mem: " + e);
        }
    }

    private long total() {
        long t = 0;
        for (long[] r : anon) t += r[1];
        for (SharedMemory s : shm) t += s.getSize();
        return t;
    }

    private void grow(int seq) throws ErrnoException {
        if (seq % 5 == 4) {                                // a fifth as shmem
            SharedMemory s = SharedMemory.create("afdo-shm-" + seq, (int) REGION);
            ByteBuffer b = s.mapReadWrite();
            writeShm(b, 1);
            SharedMemory.unmap(b);
            shm.add(s);
            shmPass.add(1);
        } else {
            long addr = nativeAlloc(REGION);
            if (addr == 0) { problems.add("mmap failed at " + (total() >> 20) + " MiB"); return; }
            int kind = seq % 3;
            pagesWritten += nativeTouch(addr, REGION, kind, 1, p.seed + seq);
            anon.add(new long[]{addr, REGION, kind, 1});
        }
        grows++;
        peak = Math.max(peak, total());
    }

    // One churn operation on a region chosen by the seed.
    private void churn() throws ErrnoException {
        int i = random.nextInt(anon.size() + shm.size());
        if (i < anon.size()) {
            long[] r = anon.get(i);
            int op = random.nextInt(100);
            if (op < 40) {                         // release part, then touch the whole region again
                long ps = nativePageSize(), pages = r[1] / ps;
                long off = random.nextInt((int) (pages / 2)) * ps;
                nativeRelease(r[0], off, (pages / 2) * ps);
                released += pages / 2;
                r[3]++;
                pagesWritten += nativeTouch(r[0], r[1], (int) r[2], (int) r[3], p.seed + i);
            } else if (op < 60) {                  // unmap, map a new one
                nativeFree(r[0], r[1]);
                long addr = nativeAlloc(r[1]);
                if (addr == 0) { anon.remove(i); problems.add("mmap failed while remapping"); return; }
                r[0] = addr; r[3] = 1;
                pagesWritten += nativeTouch(r[0], r[1], (int) r[2], 1, p.seed + i);
                remaps++;
            } else if (op < 85) {                  // rewrite (swap-in of anything swapped out)
                r[3]++;
                pagesWritten += nativeTouch(r[0], r[1], (int) r[2], (int) r[3], p.seed + i);
            } else {                               // re-read
                long bad = nativeVerify(r[0], r[1], (int) r[3]);
                if (bad != 0) problems.add(bad + " page(s) of region " + i + " lost their stamp");
                pagesVerified += r[1] / nativePageSize();
            }
        } else {
            int k = i - anon.size();
            ByteBuffer b = shm.get(k).mapReadWrite();
            int pass = shmPass.get(k) + 1;
            writeShm(b, pass);
            SharedMemory.unmap(b);
            shmPass.set(k, pass);
        }
        passes++;
    }

    private void writeShm(ByteBuffer b, int pass) {
        for (int off = 0; off + 8 <= b.capacity(); off += 4096) b.putLong(off, ((long) pass << 32) | (off >>> 12));
        pagesWritten += b.capacity() / 4096;
    }

    private void freeOne() {
        if (!anon.isEmpty()) { long[] r = anon.remove(anon.size() - 1); nativeFree(r[0], r[1]); }
    }

    private void freeHalf() {
        int n = (anon.size() + 1) / 2;
        for (int i = 0; i < n; i++) freeOne();
        freedForHeadroom += n;
    }

    @Override
    void validate() throws ErrnoException {
        vmAfter = vmstat();
        long bad = 0;
        for (long[] r : anon) bad += nativeVerify(r[0], r[1], (int) r[3]);
        long shmBad = 0;
        for (int k = 0; k < shm.size(); k++) {
            ByteBuffer b = shm.get(k).mapReadOnly();
            int pass = shmPass.get(k);
            for (int off = 0; off + 8 <= b.capacity(); off += 4096) if (b.getLong(off) != (((long) pass << 32) | (off >>> 12))) shmBad++;
            SharedMemory.unmap(b);
        }
        put(completed, "grows", grows);
        put(completed, "passes", passes);
        put(completed, "pages_written", pagesWritten);
        put(completed, "pages_verified_during_run", pagesVerified);
        put(completed, "peak_bytes", peak);
        put(completed, "anon_regions", anon.size());
        put(completed, "shmem_regions", shm.size());
        put(completed, "heap_churn_bytes", heapChurn);
        put(completed, "pages_released", released);
        put(completed, "remaps", remaps);
        put(evidence, "min_mem_available_bytes", minAvail);
        put(evidence, "regions_freed_for_headroom", freedForHeadroom);
        put(evidence, "stamp_mismatches", bad + shmBad);
        if (vmBefore != null && vmAfter != null)
            for (String k : new String[]{"pswpin", "pswpout", "pgmajfault", "workingset_refault_anon", "pgsteal_kswapd", "pgsteal_direct"})
                if (vmBefore.containsKey(k) && vmAfter.containsKey(k)) put(evidence, "vmstat_" + k, vmAfter.get(k) - vmBefore.get(k));
        need(bad + shmBad == 0, (bad + shmBad) + " page(s) failed their stamp");
        need(grows > 0, "never allocated");
        need(passes > 0 || peak >= cap, "no churn passes");
    }

    /** Only called once the memory thread has ended (Case joins it). */
    @Override
    void cleanup() {
        for (long[] r : anon) nativeFree(r[0], r[1]);
        anon.clear();
        for (SharedMemory s : shm) s.close();
        shm.clear();
    }

    private static Map<String, Long> meminfo() throws IOException {
        Map<String, Long> m = new HashMap<>();
        for (String l : Files.readAllLines(new File("/proc/meminfo").toPath())) {
            String[] f = l.split("\\s+");
            if (f.length >= 2) m.put(f[0].replace(":", ""), Long.parseLong(f[1]) * 1024);
        }
        return m;
    }

    private static Map<String, Long> vmstat() {
        Map<String, Long> m = new HashMap<>();
        try {
            for (String l : Files.readAllLines(new File("/proc/vmstat").toPath())) {
                String[] f = l.split(" ");
                if (f.length == 2) m.put(f[0], Long.parseLong(f[1]));
            }
        } catch (IOException | NumberFormatException | SecurityException ignored) {
            // not readable for apps on some builds: the suite records it as root
        }
        return m;
    }
}
