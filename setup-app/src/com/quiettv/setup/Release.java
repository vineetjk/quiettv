package com.quiettv.setup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** Downloads the latest QuietTV home screen from GitHub Releases and checks it against its SHA-256. */
final class Release {
    static final String REPO = "vineetjk/quiettv";
    static final String BASE = "https://github.com/" + REPO + "/releases/latest/download/";

    private Release() {}

    static byte[] latestQuietTv() throws IOException {
        byte[] apk = get(BASE + "quiettv.apk", 20 << 20);
        String sum = new String(get(BASE + "quiettv.apk.sha256", 4096), "US-ASCII").trim().split("\\s+")[0];
        if (!sum.equalsIgnoreCase(sha256(apk))) throw new IOException("The download doesn't match its checksum.");
        return apk;
    }

    private static byte[] get(String url, int limit) throws IOException {
        for (int hops = 0; hops < 5; hops++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                if (!url.startsWith("https://")) throw new IOException("refusing a non-https redirect");
                c.disconnect();
                continue;
            }
            if (code != 200) throw new IOException("GitHub answered " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > limit) throw new IOException("download too large");
                }
                return out.toByteArray();
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
