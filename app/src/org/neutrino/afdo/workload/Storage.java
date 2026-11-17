package org.neutrino.afdo.workload;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * storage: app-private files on /data.  The measured interval is split into
 * fixed time windows, in a fixed order: sequential write with fsync, one
 * sequential read-back of what was written (served from the page cache: it
 * is a read-copy of this attempt's own data, not a measure of the storage
 * device; dropping the cache would need root or fadvise, which isn't used),
 * random 4 KiB reads and writes with fdatasync, mmap writes with explicit
 * force(), a metadata storm (create, stat, rename, list, unlink) and SQLite
 * in WAL mode.  Each subcase does whole units of work within its own window;
 * a subcase that finishes early leaves the rest of its window idle, so no
 * subcase grows into another's.
 *
 * Writes are bounded before they happen: preparation and every subcase have
 * their own budget (BUDGET, 2016 MiB in all, under the 2 GiB the attempt may
 * write), charged before each write; random writes are paced over their
 * window.  A write is counted at its actual length.  Free space is checked
 * during the case: below MIN_FREE it stops with SAFETY.  Everything written
 * is checksummed and verified after the trace stops.  Logical bytes are
 * counted by the app; the bytes that reached storage (/proc/self/io, write
 * amplification included) are recorded separately.
 */
final class Storage extends Case {
    private static final long MB = 1 << 20;
    private static final long NEED_FREE = 8L << 30;      // refuse to start below this
    private static final long MIN_FREE = 6L << 30;       // stop with SAFETY below this during the case
    private static final long WRITE_LIMIT = 2L << 30;    // at most this much written per attempt
    private static final long SEQ_FILE = 64 * MB, RAND_FILE = 256 * MB, MMAP_FILE = 64 * MB;
    private static final String[] SUB = {"seq_write", "seq_read", "random", "mmap", "metadata", "sqlite"};
    private static final double[] SHARE = {0.20, 0.10, 0.20, 0.15, 0.15, 0.20};
    private static final long PREP_BUDGET = RAND_FILE;
    private static final long[] BUDGET = {640 * MB, 0, 192 * MB, 640 * MB, 128 * MB, 160 * MB};
    private static final long SQL_TXN_BYTES = 16 << 10;  // charged per SQLite transaction (200 rows and an update)

    private File scratch;
    private final ByteBuffer[] blocks = new ByteBuffer[8];   // 1 MiB each, seeded
    private final WriteBudget prep = new WriteBudget("preparation", PREP_BUDGET);
    private final WriteBudget[] budget = new WriteBudget[SUB.length];
    private long minFree = Long.MAX_VALUE;
    private final Map<String, Long> seqCrc = new HashMap<>();
    private final Map<Long, Integer> randWrites = new HashMap<>();
    private int mmapPasses;
    private long sqlRows, randReads;
    private final long[] units = new long[SUB.length];
    private final long[] bytes = new long[SUB.length];
    private long fsyncs, metaOps;
    private Map<String, Long> ioBefore, ioAfter;

    Storage(Run a, Params p) {
        super(a, p);
        for (int i = 0; i < SUB.length; i++) budget[i] = new WriteBudget(SUB[i], BUDGET[i]);
    }

    @Override
    void prepare() {
        worker.post(() -> {
            try {
                scratch = new File(dir, "scratch");
                deleteTree(scratch);
                if (!scratch.mkdirs()) throw new IOException("mkdir " + scratch);
                long free = new StatFs(scratch.getPath()).getAvailableBytes();
                put(requested, "free_bytes", free);
                if (free < NEED_FREE) { safety("only " + (free >> 20) + " MiB free"); return; }
                for (int i = 0; i < blocks.length; i++) {
                    byte[] b = new byte[(int) MB];
                    random.nextBytes(b);
                    blocks[i] = ByteBuffer.wrap(b);
                }
                // The random-I/O file is written before the trace: unwritten
                // extents would read back as zeros without touching the device.
                try (FileChannel c = FileChannel.open(new File(scratch, "rand.bin").toPath(),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    for (long off = 0; off < RAND_FILE; off += MB) {
                        if (!prep.take(MB)) throw new IOException("preparation over its budget");
                        writeFully(c, block(off / MB).duplicate(), off);
                    }
                    c.force(true);
                }
                long total = PREP_BUDGET;
                for (int i = 0; i < SUB.length; i++) {
                    put(requested, SUB[i] + "_share", SHARE[i]);
                    put(requested, SUB[i] + "_budget_bytes", BUDGET[i]);
                    total += BUDGET[i];
                }
                if (total > WRITE_LIMIT) throw new IllegalStateException("budgets exceed the write limit");
                put(requested, "preparation_budget_bytes", PREP_BUDGET);
                put(requested, "write_limit_bytes", WRITE_LIMIT);
                put(requested, "min_free_bytes", MIN_FREE);
                main.post(this::ready);
            } catch (IOException | RuntimeException e) {
                fail("prepare: " + e);
            }
        });
    }

    private ByteBuffer block(long i) { return blocks[Math.floorMod(i * 7 + 3, blocks.length)]; }

    @Override
    void run() {
        worker.post(() -> {
            try {
                ioBefore = procIo();
                long t = runStartNs;
                for (int i = 0; i < SUB.length && !cancelled; i++) {
                    t += (long) (p.seconds * 1e9 * SHARE[i]);
                    long end = t;
                    long t0 = SystemClock.elapsedRealtimeNanos();
                    switch (i) {
                        case 0: seqWrite(end); break;
                        case 1: seqRead(end); break;
                        case 2: randomIo(t0, end); break;
                        case 3: mmap(end); break;
                        case 4: metadata(end); break;
                        case 5: sqlite(end); break;
                        default: break;
                    }
                    put(completed, SUB[i] + "_busy_s", (SystemClock.elapsedRealtimeNanos() - t0) / 1e9);
                    while (!cancelled && SystemClock.elapsedRealtimeNanos() < end) SystemClock.sleep(100);
                }
                ioAfter = procIo();
                main.post(this::done);
            } catch (IOException | RuntimeException e) {
                fail("run: " + e);
            }
        });
    }

    private boolean before(long end) { return !cancelled && SystemClock.elapsedRealtimeNanos() < end; }

    /** Free space, checked once per unit of work: below MIN_FREE the case stops with SAFETY. */
    private boolean room() {
        long free = new StatFs(scratch.getPath()).getAvailableBytes();
        minFree = Math.min(minFree, free);
        if (free >= MIN_FREE) return true;
        safety("free space " + (free >> 20) + " MiB below " + (MIN_FREE >> 20) + " MiB");
        return false;
    }

    /** Writes all of b at pos (a channel may write less than asked); returns the bytes written. */
    private static long writeFully(FileChannel c, ByteBuffer b, long pos) throws IOException {
        long n = 0;
        while (b.hasRemaining()) n += c.write(b, pos + n);
        return n;
    }

    private static long writeFully(FileChannel c, ByteBuffer b) throws IOException {
        long n = 0;
        while (b.hasRemaining()) n += c.write(b);
        return n;
    }

    private void seqWrite(long end) throws IOException {
        int n = 0;
        while (before(end) && room() && budget[0].used() + SEQ_FILE <= BUDGET[0]) {
            File f = new File(scratch, "seq" + n + ".bin");
            CRC32 crc = new CRC32();
            try (FileChannel c = FileChannel.open(f.toPath(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (long off = 0; off < SEQ_FILE && before(end); off += MB) {
                    if (!budget[0].take(MB)) break;
                    ByteBuffer b = block(off / MB + n).duplicate();
                    crc.update(b.duplicate());
                    bytes[0] += writeFully(c, b);
                }
                c.force(true); fsyncs++;
            }
            seqCrc.put(f.getName(), crc.getValue());
            units[0]++; n++;
        }
    }

    // One pass over what seq_write wrote (mostly from the page cache: an app
    // can't evict it); repeating it would only measure memory copies.
    private void seqRead(long end) throws IOException {
        ByteBuffer buf = ByteBuffer.allocateDirect((int) MB);
        for (String name : seqCrc.keySet()) {
            if (!before(end)) return;
            try (FileChannel c = FileChannel.open(new File(scratch, name).toPath(), StandardOpenOption.READ)) {
                buf.clear();
                int r;
                while (before(end) && (r = c.read(buf)) > 0) { bytes[1] += r; buf.clear(); }
            }
            units[1]++;
        }
    }

    // 70% reads, 30% writes while the paced write budget allows them (reads otherwise).
    private void randomIo(long start, long end) throws IOException {
        try (FileChannel c = FileChannel.open(new File(scratch, "rand.bin").toPath(),
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocateDirect(4096);
            long blocksInFile = RAND_FILE / 4096;
            int sinceSync = 0;
            while (before(end) && (units[2] % 4096 != 0 || room())) {
                long blk = Math.floorMod(random.nextLong(), blocksInFile);
                buf.clear();
                double f = (SystemClock.elapsedRealtimeNanos() - start) / (double) (end - start);
                if (random.nextInt(10) >= 7 && budget[2].paced(4096, f)) {
                    int pattern = random.nextInt();
                    for (int i = 0; i < 4096; i += 4) buf.putInt(i, pattern ^ i);
                    bytes[2] += writeFully(c, buf, blk * 4096);
                    randWrites.put(blk, pattern);
                    if (++sinceSync == 64) { c.force(false); fsyncs++; sinceSync = 0; }
                } else {
                    c.read(buf, blk * 4096);
                    randReads++;
                }
                units[2]++;
            }
            c.force(false); fsyncs++;
        }
    }

    // Each pass (charged in full before it starts) stamps every page; a pass
    // the window cut short is finished, so verification sees one complete pass.
    private void mmap(long end) throws IOException {
        File f = new File(scratch, "mmap.bin");
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(MMAP_FILE);
            MappedByteBuffer m = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, MMAP_FILE);
            int off = (int) MMAP_FILE;
            while (before(end) && room() && budget[3].take(MMAP_FILE)) {
                int pass = ++mmapPasses;
                for (off = 0; off < MMAP_FILE && before(end); off += 4096) m.putLong(off, ((long) pass << 32) | (off >>> 12));
                m.force(); fsyncs++;
                bytes[3] += MMAP_FILE; units[3]++;
            }
            if (mmapPasses > 0 && off < MMAP_FILE && !cancelled) {
                for (; off < MMAP_FILE; off += 4096) m.putLong(off, ((long) mmapPasses << 32) | (off >>> 12));
                m.force(); fsyncs++;
            }
        }
    }

    private static final long META_ROUND = 8L * (36 << 10);   // file contents of one round: 64 files of 1-8 KiB

    private void metadata(long end) throws IOException {
        int round = 0;
        while (before(end) && room() && budget[4].take(META_ROUND)) {
            File d = new File(scratch, "meta" + round++);
            if (!d.mkdir()) throw new IOException("mkdir " + d);
            for (int i = 0; i < 64; i++) {
                File f = new File(d, "f" + i);
                try (FileOutputStream o = new FileOutputStream(f)) {
                    ByteBuffer b = block(i);
                    int len = 1024 + (i % 8) * 1024;
                    o.write(b.array(), 0, len);
                    bytes[4] += len;
                    if (i % 16 == 0) { o.getFD().sync(); fsyncs++; }
                }
                metaOps++;
            }
            String[] names = d.list();
            metaOps++;
            if (names != null) {
                for (String n : names) {
                    File f = new File(d, n);
                    if (f.length() < 0 || f.lastModified() < 0) throw new IOException("stat " + f);
                    if (!f.renameTo(new File(d, n + ".r"))) throw new IOException("rename " + f);
                    metaOps += 2;
                }
            }
            deleteTree(d);
            metaOps += 65;
            units[4]++;
        }
    }

    // Each transaction is charged before it runs, and the database (with its
    // WAL) must stay within the budget too.
    private void sqlite(long end) {
        File db = new File(scratch, "w.db"), wal = new File(scratch, "w.db-wal");
        try (SQLiteDatabase s = SQLiteDatabase.openOrCreateDatabase(db, null)) {
            s.enableWriteAheadLogging();
            s.execSQL("CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY, k INTEGER, v TEXT)");
            s.execSQL("CREATE INDEX IF NOT EXISTS t_k ON t (k)");
            ContentValues cv = new ContentValues();
            while (before(end) && (units[5] % 64 != 0 || room()) && db.length() + wal.length() + SQL_TXN_BYTES <= BUDGET[5]
                    && budget[5].take(SQL_TXN_BYTES)) {
                s.beginTransaction();
                try {
                    for (int i = 0; i < 200; i++) {
                        cv.clear();
                        cv.put("k", random.nextInt(1 << 20));
                        cv.put("v", Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong()));
                        s.insertOrThrow("t", null, cv);
                    }
                    s.setTransactionSuccessful();
                } finally {
                    s.endTransaction();
                }
                sqlRows += 200;
                int lo = random.nextInt(1 << 20);
                try (Cursor c = s.rawQuery("SELECT count(*), max(id) FROM t WHERE k BETWEEN ? AND ?",
                        new String[]{String.valueOf(lo), String.valueOf(lo + 4096)})) {
                    c.moveToFirst();
                }
                s.execSQL("UPDATE t SET v = v || 'u' WHERE k BETWEEN ? AND ?", new Object[]{lo, lo + 1024});
                units[5]++;
            }
            bytes[5] = db.length() + wal.length();
        }
    }

    @Override
    void validate() throws IOException {
        // Sequential files: every completed file reads back with its checksum.
        int bad = 0;
        ByteBuffer buf = ByteBuffer.allocate((int) MB);
        for (Map.Entry<String, Long> e : seqCrc.entrySet()) {
            File f = new File(scratch, e.getKey());
            CRC32 crc = new CRC32();
            try (FileChannel c = FileChannel.open(f.toPath(), StandardOpenOption.READ)) {
                buf.clear();
                while (c.read(buf) > 0) { buf.flip(); crc.update(buf); buf.clear(); }
            }
            if (crc.getValue() != e.getValue()) bad++;
        }
        need(bad == 0, bad + " sequential file(s) failed their checksum");
        // Random writes: the last pattern written to each block.
        int rbad = 0;
        try (FileChannel c = FileChannel.open(new File(scratch, "rand.bin").toPath(), StandardOpenOption.READ)) {
            ByteBuffer b = ByteBuffer.allocate(4096);
            for (Map.Entry<Long, Integer> e : randWrites.entrySet()) {
                b.clear();
                c.read(b, e.getKey() * 4096);
                if (b.getInt(64) != (e.getValue() ^ 64)) rbad++;
            }
        }
        need(rbad == 0, rbad + " random block(s) don't hold their last write");
        // mmap: every page carries the last complete pass.
        int mbad = 0;
        if (mmapPasses > 0) {
            try (FileChannel c = FileChannel.open(new File(scratch, "mmap.bin").toPath(), StandardOpenOption.READ)) {
                ByteBuffer b = ByteBuffer.allocate(8);
                for (long off = 0; off < MMAP_FILE; off += 4096) {
                    b.clear();
                    c.read(b, off);
                    if (b.getLong(0) != (((long) mmapPasses << 32) | (off >>> 12))) mbad++;
                }
            }
        }
        need(mbad == 0, mbad + " mmap page(s) don't hold the last pass");
        // SQLite: integrity and row count.
        try (SQLiteDatabase s = SQLiteDatabase.openDatabase(new File(scratch, "w.db").getPath(), null,
                SQLiteDatabase.OPEN_READONLY);
             Cursor c = s.rawQuery("PRAGMA integrity_check", null);
             Cursor n = s.rawQuery("SELECT count(*) FROM t", null)) {
            c.moveToFirst(); n.moveToFirst();
            need("ok".equals(c.getString(0)), "sqlite integrity: " + c.getString(0));
            need(n.getLong(0) == sqlRows, "sqlite rows " + n.getLong(0) + " != " + sqlRows);
        } catch (RuntimeException e) {
            if (units[5] > 0) problems.add("sqlite: " + e);
        }
        for (int i = 0; i < SUB.length; i++) {
            put(completed, SUB[i] + "_units", units[i]);
            put(completed, SUB[i] + "_bytes", bytes[i]);
            need(units[i] > 0, SUB[i] + " completed no unit of work");
        }
        long written = 0;
        for (int i = 0; i < SUB.length; i++) {
            put(completed, SUB[i] + "_bytes_charged", budget[i].used());
            written += budget[i].used();
            need(budget[i].used() <= BUDGET[i], SUB[i] + " wrote past its budget");
        }
        put(completed, "fsyncs", fsyncs);
        put(completed, "metadata_ops", metaOps);
        put(completed, "sqlite_rows", sqlRows);
        put(completed, "random_reads", randReads);
        put(completed, "random_blocks_written", randWrites.size());
        put(completed, "preparation_bytes_written", prep.used());
        put(completed, "logical_bytes_written", written);
        put(evidence, "min_free_bytes", minFree);
        need(prep.used() + written <= WRITE_LIMIT, "wrote more than the limit");
        if (ioBefore != null && ioAfter != null) {
            for (String k : new String[]{"read_bytes", "write_bytes", "rchar", "wchar", "syscr", "syscw"})
                if (ioBefore.containsKey(k) && ioAfter.containsKey(k))
                    put(evidence, "proc_io_" + k, ioAfter.get(k) - ioBefore.get(k));
        }
    }

    @Override
    void cleanup() {
        File s = scratch;
        if (s != null) worker.post(() -> deleteTree(s));
    }

    private static Map<String, Long> procIo() {
        Map<String, Long> m = new HashMap<>();
        try {
            for (String l : Files.readAllLines(new File("/proc/self/io").toPath())) {
                String[] f = l.split(":\\s*");
                if (f.length == 2) m.put(f[0], Long.parseLong(f[1].trim()));
            }
        } catch (IOException | NumberFormatException ignored) {
            // recorded as missing
        }
        return m;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
