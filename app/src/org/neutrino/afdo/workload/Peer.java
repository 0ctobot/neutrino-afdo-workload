package org.neutrino.afdo.workload;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The LAN peer (peer.py on the host), reached only over Wi-Fi: every socket
 * is bound to the Wi-Fi network, only the endpoint the case was started with
 * is ever contacted, redirects are refused, and every request carries the
 * run's token and the attempt's key (the peer keeps counters per attempt).
 * Open connections are tracked, so cancel() can disconnect a blocked one.
 * A lost or changed Wi-Fi network is recorded as a problem.
 */
final class Peer {
    final String host;
    final int port;
    final String token;
    private final Case c;
    private final ConnectivityManager cm;
    private volatile Network wifi;
    private volatile int lost, changed;
    private volatile String vpn = "";
    private ConnectivityManager.NetworkCallback callback;
    private final java.util.Set<HttpURLConnection> open = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<DatagramSocket> sockets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean cancelled;

    Peer(Case c) {
        this.c = c;
        if (c.p.peer == null || c.p.token == null) throw new IllegalArgumentException("no peer or token");
        int i = c.p.peer.lastIndexOf(':');
        host = c.p.peer.substring(0, i);
        port = Integer.parseInt(c.p.peer.substring(i + 1));
        token = c.p.token;
        cm = c.activity.getSystemService(ConnectivityManager.class);
    }

    /** Wait up to 10 s for the Wi-Fi network; null on success, else the reason. */
    String acquire() throws InterruptedException {
        CountDownLatch got = new CountDownLatch(1);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                if (wifi != null && !wifi.equals(n)) changed++;
                wifi = n;
                got.countDown();
            }
            @Override public void onLost(Network n) { if (n.equals(wifi)) lost++; }
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) {}
        };
        NetworkRequest r = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        cm.registerNetworkCallback(r, callback);
        if (!got.await(10, TimeUnit.SECONDS)) return "no Wi-Fi network";
        Network active = cm.getActiveNetwork();
        NetworkCapabilities ac = active == null ? null : cm.getNetworkCapabilities(active);
        if (ac != null && ac.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = "default network is a VPN";
        return null;
    }

    void release() {
        if (callback != null) {
            try { cm.unregisterNetworkCallback(callback); } catch (RuntimeException ignored) {}
            callback = null;
        }
    }

    /** An HTTP connection to the peer, on Wi-Fi, without redirects; give it back with done(). */
    HttpURLConnection open(String path) throws IOException {
        if (!path.startsWith("/")) throw new IllegalArgumentException(path);
        if (cancelled) throw new IOException("cancelled");
        URL u = new URL("http", host, port, path);
        HttpURLConnection h = (HttpURLConnection) wifi.openConnection(u);
        h.setInstanceFollowRedirects(false);
        h.setConnectTimeout(5000);
        h.setReadTimeout(10000);
        h.setRequestProperty("X-Afdo-Token", token);
        h.setRequestProperty("X-Afdo-Run", c.p.run);
        h.setRequestProperty("X-Afdo-Key", c.p.key());
        open.add(h);
        if (cancelled) { h.disconnect(); open.remove(h); throw new IOException("cancelled"); }
        return h;
    }

    /** The caller is finished with h (a kept-alive socket stays in the pool). */
    void done(HttpURLConnection h) { open.remove(h); }

    /** A UDP socket on Wi-Fi, connected to the peer; closed by cancel() as well. */
    DatagramSocket udp() throws IOException {
        DatagramSocket s = new DatagramSocket();
        sockets.add(s);
        wifi.bindSocket(s);
        s.connect(new InetSocketAddress(host, port));
        s.setSoTimeout(300);
        return s;
    }

    /** From any thread: refuse new connections and disconnect the open ones. */
    void cancel() {
        cancelled = true;
        for (HttpURLConnection h : open) h.disconnect();
        open.clear();
        for (DatagramSocket s : sockets) s.close();
        sockets.clear();
    }

    /** After the work stopped: connections are allowed again (validation asks for the peer's counters). */
    void resume() { cancelled = false; }

    /** Problems seen on the network during the case. */
    void report() {
        c.put(c.evidence, "wifi_lost", lost);
        c.put(c.evidence, "wifi_changed", changed);
        if (!vpn.isEmpty()) c.put(c.evidence, "vpn", vpn);
        c.need(lost == 0, "the Wi-Fi network was lost during the case");
        c.need(changed == 0, "the Wi-Fi network changed during the case");
    }

}
