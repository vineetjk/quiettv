package com.quiettv.setup;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Finds TVs with network debugging on: devices on the phone's Wi-Fi (its /24) that accept a
 * connection on port 5555. It only opens and closes a connection; it doesn't talk to them.
 */
final class Finder {
    static final int PORT = 5555;

    private Finder() {}

    /** The phone's own IPv4 address on the current network, or null. */
    static Inet4Address phoneAddress(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm == null ? null : cm.getActiveNetwork();
        LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) return (Inet4Address) la.getAddress();
        }
        return null;
    }

    /** Checks the 254 neighbours of the phone's address, many at once. Takes a few seconds. */
    static List<String> scan(Context c) throws InterruptedException {
        Inet4Address me = phoneAddress(c);
        final List<String> found = Collections.synchronizedList(new ArrayList<String>());
        if (me == null) return found;
        byte[] b = me.getAddress();
        final String prefix = (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".";
        final int self = b[3] & 0xff;
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (int i = 1; i < 255; i++) {
            if (i == self) continue;
            final String host = prefix + i;
            pool.execute(() -> {
                if (open(host, 600)) found.add(host);
            });
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        List<String> sorted = new ArrayList<>(found);
        Collections.sort(sorted, (x, y) -> Integer.compare(last(x), last(y)));
        return sorted;
    }

    static boolean open(String host, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getByName(host), PORT), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int last(String ip) {
        return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1));
    }
}
