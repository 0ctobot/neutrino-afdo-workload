package org.neutrino.afdo.workload;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.OutputStream;
import java.io.IOException;
import java.util.zip.CRC32;

/**
 * ipc (a diagnostic, weight 0): Binder transactions to IpcService in a second
 * process: payloads of 64 B to 64 KiB, checked by the CRC the other side
 * returns, and every 16th transaction a pipe's file descriptor passed along
 * and read on the other side (the byte count it read is checked too).
 */
final class Ipc extends Case {
    private IBinder remote;
    private ServiceConnection conn;
    private long calls, bytes, fds, bad, fdBad;

    Ipc(Run a, Params p) { super(a, p); }

    @Override
    void prepare() {
        conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder b) { remote = b; ready(); }
            @Override public void onServiceDisconnected(ComponentName n) { if (!cancelled && runEndNs == 0) fail("ipc peer died"); }
        };
        if (!activity.bindService(new Intent(activity, IpcService.class), conn, Context.BIND_AUTO_CREATE)) fail("bind failed");
    }

    @Override
    void run() {
        spawn("ipc", () -> {
            int[] sizes = {64, 512, 4096, 16384, 65536};
            try {
                while (!cancelled && !pastDeadline()) {
                    byte[] b = new byte[sizes[random.nextInt(sizes.length)]];
                    random.nextBytes(b);
                    Parcel d = Parcel.obtain(), r = Parcel.obtain();
                    ParcelFileDescriptor[] pipe = null;
                    long fdWant = 0;
                    try {
                        d.writeByteArray(b);
                        if (calls % 16 == 0) {
                            pipe = ParcelFileDescriptor.createPipe();
                            d.writeInt(1);
                            d.writeFileDescriptor(pipe[0].getFileDescriptor());
                            // AutoCloseOutputStream closes the write end, so the other side sees EOF
                            // (a FileOutputStream on a FileDescriptor would leave it open).
                            fdWant = Math.min(b.length, 4096);
                            try (OutputStream o = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { o.write(b, 0, (int) fdWant); }
                            pipe[1] = null;
                            fds++;
                        } else {
                            d.writeInt(0);
                        }
                        remote.transact(IpcService.ECHO, d, r, 0);
                        CRC32 crc = new CRC32();
                        crc.update(b);
                        if (r.readLong() != crc.getValue()) bad++;
                        if (r.readLong() != fdWant) fdBad++;
                        calls++;
                        bytes += b.length;
                    } finally {
                        d.recycle(); r.recycle();
                        if (pipe != null) for (ParcelFileDescriptor f : pipe) if (f != null) try { f.close(); } catch (IOException ignored) {}
                    }
                }
                main.post(this::done);
            } catch (RemoteException | IOException | RuntimeException e) {
                if (!cancelled) fail("ipc: " + e);
            }
        });
    }

    @Override
    void validate() {
        put(completed, "transactions", calls);
        put(completed, "bytes", bytes);
        put(completed, "fds_passed", fds);
        put(evidence, "crc_mismatches", bad);
        put(evidence, "fd_count_mismatches", fdBad);
        need(calls >= 100 * activeSeconds(), "too few transactions: " + calls);
        need(bad == 0, bad + " reply CRC mismatch(es)");
        need(fdBad == 0, fdBad + " descriptor read(s) returned the wrong byte count");
    }

    @Override
    void cleanup() {
        if (conn != null) { try { activity.unbindService(conn); } catch (IllegalArgumentException ignored) {} }
    }
}
