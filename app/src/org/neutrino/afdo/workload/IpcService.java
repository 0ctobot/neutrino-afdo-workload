package org.neutrino.afdo.workload;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.CRC32;

/**
 * The other end of the ipc case, in its own process (":peer"): a raw Binder
 * that returns the CRC32 of the payload it gets, and reads a file descriptor
 * the caller passes when there is one.
 */
public class IpcService extends Service {
    static final int ECHO = IBinder.FIRST_CALL_TRANSACTION;

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != ECHO) return false;
            byte[] b = data.createByteArray();
            CRC32 crc = new CRC32();
            if (b != null) crc.update(b);
            long fdBytes = 0;
            if (data.readInt() == 1) {
                ParcelFileDescriptor pfd = data.readFileDescriptor();
                try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fdBytes += n;
                } catch (IOException e) {
                    fdBytes = -1;
                } finally {
                    try { pfd.close(); } catch (IOException ignored) {}
                }
            }
            reply.writeLong(crc.getValue());
            reply.writeLong(fdBytes);
            return true;
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return binder; }
}
