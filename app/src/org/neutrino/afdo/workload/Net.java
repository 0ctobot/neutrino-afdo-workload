package org.neutrino.afdo.workload;

import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * net-rpc / net-bulk: traffic to the LAN peer over Wi-Fi only (Peer).
 * net-rpc paces short HTTP request/responses (new and keep-alive
 * connections, 256 B to 64 KiB, every body checked against the peer's CRC)
 * and UDP request/responses with IDs, one retry and loss and duplicate
 * counts.  net-bulk runs four paced TCP streams, each downloading and
 * uploading whole transfers; every download's CRC is compared with what the
 * peer recorded and every upload's with the peer's reply, and every UDP
 * echo byte for byte.  The peer's counters are this attempt's own (the
 * attempt key is on every request).  Nothing is gated on throughput: the
 * floors are liveness floors well under the pacing.
 */
final class Net extends Case {
    private static final int STREAMS = 4;
    private static final long STREAM_RATE = 2L << 20;      // bytes/s per bulk stream (pacing)
    private static final int DOWN = 8 << 20, UP = 4 << 20;
    private final boolean bulk;
    private Peer peer;
    private final AtomicInteger id = new AtomicInteger();
    // rpc
    private final AtomicInteger httpOk = new AtomicInteger(), httpErr = new AtomicInteger(), crcBad = new AtomicInteger();
    private final AtomicInteger fresh = new AtomicInteger(), udpSent = new AtomicInteger(), udpOk = new AtomicInteger();
    private final AtomicInteger udpRetried = new AtomicInteger(), udpLost = new AtomicInteger(), udpDup = new AtomicInteger();
    private final AtomicInteger udpBad = new AtomicInteger(), udpLate = new AtomicInteger();
    // bulk
    private final Map<Integer, Long> downCrc = new ConcurrentHashMap<>();
    private final AtomicInteger upOk = new AtomicInteger(), upBad = new AtomicInteger(), downPartial = new AtomicInteger();
    private final AtomicLong bytesDown = new AtomicLong(), bytesUp = new AtomicLong();
    private final AtomicInteger errors = new AtomicInteger();
    private JSONObject peerStats;

    Net(Run a, Params p, boolean bulk) { super(a, p); this.bulk = bulk; }

    @Override
    void prepare() {
        worker.post(() -> {
            try {
                peer = new Peer(this);
                String why = peer.acquire();
                if (why != null) { fail(why); return; }
                HttpURLConnection h = peer.open("/rpc?id=0&size=16");
                try {
                    if (h.getResponseCode() != 200) { fail("peer answered " + h.getResponseCode()); return; }
                    h.getInputStream().close();
                } finally {
                    peer.done(h);
                }
                put(requested, bulk ? "streams" : "mode", bulk ? STREAMS : "http+udp");
                if (bulk) put(requested, "pacing_bytes_per_s", STREAMS * STREAM_RATE);
                main.post(this::ready);
            } catch (IOException | RuntimeException | InterruptedException e) {
                fail("peer: " + e);
            }
        });
    }

    @Override
    void run() {
        int n = bulk ? STREAMS : 2;
        AtomicInteger left = new AtomicInteger(n);
        for (int i = 0; i < n; i++) {
            final int k = i;
            spawn("net-" + i, () -> {
                try {
                    if (bulk) bulkStream(k); else if (k == 0) rpcHttp(); else rpcUdp();
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                    problems.add("stream " + k + ": " + e);
                }
                if (left.decrementAndGet() == 0) main.post(this::done);
            });
        }
    }

    /** A blocked read or connect is disconnected; Case joins the threads after this. */
    @Override
    boolean stop() {
        if (peer != null) peer.cancel();
        return true;
    }

    private void rpcHttp() {
        Random r = new Random(p.seed ^ 0x5151);
        int[] sizes = {256, 1024, 4096, 16384, 65536};
        while (!cancelled && !pastDeadline()) {
            int n = id.incrementAndGet(), size = sizes[r.nextInt(sizes.length)] - r.nextInt(128);
            boolean close = r.nextInt(10) < 3;
            HttpURLConnection h = null;
            try {
                h = peer.open("/rpc?id=" + n + "&size=" + size);
                if (close) { h.setRequestProperty("Connection", "close"); fresh.incrementAndGet(); }
                if (h.getResponseCode() != 200) { httpErr.incrementAndGet(); continue; }
                byte[] b = readAll(h.getInputStream(), size + 1);
                CRC32 crc = new CRC32();
                crc.update(b);
                if (b.length != size || !String.format("%08x", crc.getValue()).equals(h.getHeaderField("X-Afdo-Crc"))) crcBad.incrementAndGet();
                else httpOk.incrementAndGet();
                if (close) h.disconnect();
            } catch (IOException e) {
                if (!cancelled) httpErr.incrementAndGet();
            } finally {
                if (h != null) peer.done(h);
            }
            SystemClock.sleep(5 + r.nextInt(15));
        }
    }

    private void rpcUdp() {
        Random r = new Random(p.seed ^ 0xa5a5);
        Set<Integer> seen = new HashSet<>();
        byte[] in = new byte[2048];
        try (DatagramSocket s = peer.udp()) {
            while (!cancelled && !pastDeadline()) {
                int n = 1_000_000 + id.incrementAndGet();
                byte[] pay = new byte[64 + r.nextInt(1100)];
                r.nextBytes(pay);
                byte[] head = (n + ":" + peer.token + ":").getBytes(StandardCharsets.US_ASCII);
                byte[] msg = new byte[head.length + pay.length];
                System.arraycopy(head, 0, msg, 0, head.length);
                System.arraycopy(pay, 0, msg, head.length, pay.length);
                boolean got = false;
                for (int attempt = 0; attempt < 2 && !got; attempt++) {
                    if (attempt == 1) udpRetried.incrementAndGet();
                    s.send(new DatagramPacket(msg, msg.length));
                    udpSent.incrementAndGet();
                    long until = SystemClock.elapsedRealtime() + 300;
                    while (!got && SystemClock.elapsedRealtime() < until) {
                        DatagramPacket dp = new DatagramPacket(in, in.length);
                        try { s.receive(dp); } catch (SocketTimeoutException e) { break; }
                        String txt = new String(dp.getData(), 0, Math.min(dp.getLength(), 16), StandardCharsets.US_ASCII);
                        int colon = txt.indexOf(':');
                        int rid = colon > 0 ? Integer.parseInt(txt.substring(0, colon)) : -1;
                        if (rid != n) {                  // an earlier request's late or repeated echo
                            if (rid > 1_000_000 && rid < n) { if (seen.add(rid)) udpLate.incrementAndGet(); else udpDup.incrementAndGet(); }
                            else udpBad.incrementAndGet();
                            continue;
                        }
                        if (dp.getLength() != colon + 1 + pay.length
                                || !java.util.Arrays.equals(dp.getData(), colon + 1, dp.getLength(), pay, 0, pay.length)) {
                            udpBad.incrementAndGet();
                            continue;
                        }
                        seen.add(rid);
                        got = true;
                    }
                }
                if (got) udpOk.incrementAndGet(); else udpLost.incrementAndGet();
                SystemClock.sleep(5 + r.nextInt(15));
            }
        } catch (IOException | NumberFormatException e) {
            if (!cancelled) {
                errors.incrementAndGet();
                problems.add("udp: " + e);
            }
        }
    }

    private void bulkStream(int k) {
        Random r = new Random(p.seed ^ (k * 7919L));
        byte[] buf = new byte[256 << 10];
        while (!cancelled && !pastDeadline()) {
            int n = id.incrementAndGet();
            HttpURLConnection h = null;
            try {
                if (n % 3 != 0) {                       // download
                    h = peer.open("/bulk?id=" + n + "&bytes=" + DOWN);
                    if (h.getResponseCode() != 200) { errors.incrementAndGet(); continue; }
                    CRC32 crc = new CRC32();
                    long got = 0, t0 = SystemClock.elapsedRealtimeNanos();
                    try (InputStream in = h.getInputStream()) {
                        int m;
                        while (got < DOWN && (m = in.read(buf)) > 0) {
                            crc.update(buf, 0, m);
                            got += m;
                            bytesDown.addAndGet(m);
                            pace(t0, got);
                            if (cancelled || pastDeadline()) break;
                        }
                    }
                    if (got == DOWN) downCrc.put(n, crc.getValue()); else { downPartial.incrementAndGet(); h.disconnect(); }
                } else {                                // upload
                    h = peer.open("/upload?id=" + n);
                    h.setDoOutput(true);
                    h.setFixedLengthStreamingMode(UP);
                    CRC32 crc = new CRC32();
                    long sent = 0, t0 = SystemClock.elapsedRealtimeNanos();
                    try (OutputStream o = h.getOutputStream()) {
                        while (sent < UP) {
                            int m = (int) Math.min(buf.length, UP - sent);
                            r.nextBytes(buf);
                            crc.update(buf, 0, m);
                            o.write(buf, 0, m);
                            sent += m;
                            bytesUp.addAndGet(m);
                            pace(t0, sent);
                        }
                    }
                    String reply = new String(readAll(h.getInputStream(), 64), StandardCharsets.US_ASCII).trim();
                    if (reply.equals(String.format("%08x %d", crc.getValue(), UP))) upOk.incrementAndGet(); else upBad.incrementAndGet();
                }
            } catch (IOException e) {
                if (!pastDeadline() && !cancelled) errors.incrementAndGet();
            } finally {
                if (h != null) peer.done(h);
            }
        }
    }

    private void pace(long t0, long bytes) {
        long due = t0 + bytes * 1_000_000_000L / STREAM_RATE - SystemClock.elapsedRealtimeNanos();
        if (due > 1_000_000) SystemClock.sleep(due / 1_000_000);
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = i.read(b)) > 0 && o.size() <= max) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    @Override
    void validate() throws IOException, JSONException {
        double s = activeSeconds();
        peer.resume();
        HttpURLConnection h = peer.open("/stats?run=" + p.run + "&key=" + p.key());
        try {
            peerStats = new JSONObject(new String(readAll(h.getInputStream(), 1 << 22), StandardCharsets.UTF_8));
        } finally {
            peer.done(h);
        }
        put(evidence, "peer_requests", peerStats.optLong("requests"));
        put(evidence, "peer_rejected", peerStats.optLong("rejected"));
        peer.report();
        if (!bulk) {
            put(completed, "http_ok", httpOk.get());
            put(completed, "http_new_connections", fresh.get());
            put(completed, "udp_ok", udpOk.get());
            put(evidence, "http_errors", httpErr.get());
            put(evidence, "crc_mismatches", crcBad.get());
            put(evidence, "udp_sent", udpSent.get());
            put(evidence, "udp_retried", udpRetried.get());
            put(evidence, "udp_lost", udpLost.get());
            put(evidence, "udp_duplicates", udpDup.get());
            put(evidence, "udp_bad_echoes", udpBad.get());
            put(evidence, "udp_late_echoes", udpLate.get());
            need(httpOk.get() >= 5 * s, "too few HTTP requests: " + httpOk.get() + " in " + s + " s");
            need(crcBad.get() == 0, crcBad.get() + " response(s) failed their CRC");
            need(httpErr.get() <= httpOk.get() / 100, httpErr.get() + " HTTP error(s)");
            need(udpOk.get() >= 5 * s, "too few UDP round trips: " + udpOk.get());
            need(udpLost.get() <= Math.max(5, udpOk.get() / 10), udpLost.get() + " UDP request(s) lost after a retry");
            need(udpBad.get() == 0, udpBad.get() + " UDP echo(es) did not match what was sent");
            need(peerStats.optLong("requests") >= httpOk.get(), "the peer saw fewer requests than were answered");
        } else {
            JSONObject crcs = peerStats.optJSONObject("bulk_crc");
            int bad = 0;
            for (Map.Entry<Integer, Long> e : downCrc.entrySet()) {
                String want = crcs == null ? null : crcs.optString(String.valueOf(e.getKey()), null);
                if (want == null || !want.equals(String.format("%08x", e.getValue()))) bad++;
            }
            put(completed, "downloads", downCrc.size());
            put(completed, "uploads", upOk.get());
            put(completed, "bytes_down", bytesDown.get());
            put(completed, "bytes_up", bytesUp.get());
            put(evidence, "download_crc_mismatches", bad);
            put(evidence, "upload_crc_mismatches", upBad.get());
            put(evidence, "partial_downloads", downPartial.get());
            put(evidence, "errors", errors.get());
            put(evidence, "mbit_per_s", s > 0 ? (bytesDown.get() + bytesUp.get()) * 8 / s / 1e6 : 0);
            double paced = STREAMS * STREAM_RATE * s;
            need(bytesDown.get() + bytesUp.get() >= 0.25 * paced, "moved " + (bytesDown.get() + bytesUp.get()) + " bytes, paced for " + (long) paced);
            need(!downCrc.isEmpty() && upOk.get() > 0, "no complete download or upload");
            need(bad == 0 && upBad.get() == 0, "transfers failed their CRC (" + bad + " down, " + upBad.get() + " up)");
            need(errors.get() == 0, errors.get() + " transfer error(s)");
        }
    }

    @Override
    void cleanup() {
        if (peer != null) { peer.cancel(); peer.release(); }
    }
}
