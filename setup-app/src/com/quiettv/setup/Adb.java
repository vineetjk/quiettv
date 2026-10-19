package com.quiettv.setup;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Talks to a TV's debugging port (adbd, legacy protocol on TCP 5555), the same way the `adb` tool on a
 * computer does. The phone has its own key, which the user approves once on the TV ("Allow debugging?").
 * Protocol: AOSP packages/modules/adb protocol.txt. Key format: libcrypto_utils android_pubkey.cpp.
 * Only used while the user is going through QuietTV Setup; nothing runs in the background.
 */
final class Adb implements AutoCloseable {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final byte[] SHA1_DIGEST_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14};

    /** The TV didn't allow this phone (the user pressed Deny, or didn't answer in time). */
    static final class NotAllowed extends IOException {
        NotAllowed(String m) { super(m); }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;
    private int maxData = MAX_DATA;

    private Adb(Socket s) throws IOException {
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    /**
     * Connects to host:port and authenticates. If the TV doesn't know this phone yet, it shows
     * "Allow debugging?" and this waits up to askTimeoutMs for the user to press Allow.
     */
    static Adb connect(Context c, String host, int port, int askTimeoutMs) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 4000);
        s.setSoTimeout(8000);
        Adb a = new Adb(s);
        try {
            a.handshake(c, askTimeoutMs);
            return a;
        } catch (Exception e) {
            a.close();
            throw e;
        }
    }

    private void handshake(Context c, int askTimeoutMs) throws Exception {
        KeyPair keys = keys(c);
        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
        boolean signed = false;
        while (true) {
            Packet p = read();
            if (p.cmd == CNXN) {
                maxData = Math.max(1024, Math.min(MAX_DATA, p.arg1));
                return;
            }
            if (p.cmd != AUTH || p.arg0 != AUTH_TOKEN) throw new IOException("unexpected reply from the TV");
            if (!signed) {
                send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), p.data));
                signed = true;
                continue;
            }
            // The TV doesn't know this phone yet: offer the key, which makes it ask "Allow debugging?".
            send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKey((RSAPublicKey) keys.getPublic()));
            socket.setSoTimeout(askTimeoutMs);
            try {
                Packet r = read();
                if (r.cmd == CNXN) {
                    maxData = Math.max(1024, Math.min(MAX_DATA, r.arg1));
                    return;
                }
                throw new NotAllowed("The TV didn't allow the connection.");
            } catch (SocketTimeoutException e) {
                throw new NotAllowed("Nobody pressed Allow on the TV in time.");
            } finally {
                socket.setSoTimeout(8000);
            }
        }
    }

    /** Runs a shell command on the TV and returns what it printed. */
    synchronized String shell(String command) throws IOException {
        return run("shell:" + command, null, 30_000).replace("\r", "");
    }

    /**
     * Installs an APK by streaming it to `cmd package install` (what `adb install` does on new
     * Androids). Returns the installer's answer, e.g. "Success" or "Failure [INSTALL_FAILED_...]".
     */
    synchronized String install(byte[] apk, boolean replace) throws IOException {
        String cmd = "exec:cmd package install " + (replace ? "-r " : "") + "-S " + apk.length;
        return run(cmd, apk, 120_000).trim();
    }

    private String run(String service, byte[] input, int timeoutMs) throws IOException {
        int local = nextId++;
        send(OPEN, local, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
        socket.setSoTimeout(timeoutMs);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int remote = 0, sent = 0;
        boolean waitingForOkay = false;
        try {
            while (true) {
                Packet p = read();
                if (p.arg1 != local) continue;
                if (p.cmd == OKAY) {
                    remote = p.arg0;
                    waitingForOkay = false;
                } else if (p.cmd == WRTE) {
                    result.write(p.data);
                    send(OKAY, local, p.arg0, null);
                } else if (p.cmd == CLSE) {
                    if (remote != 0) send(CLSE, local, remote, null);
                    break;
                }
                // Stream the input one packet at a time; adbd acknowledges each with OKAY.
                if (input != null && remote != 0 && !waitingForOkay && sent < input.length) {
                    int n = Math.min(maxData, input.length - sent);
                    byte[] chunk = new byte[n];
                    System.arraycopy(input, sent, chunk, 0, n);
                    send(WRTE, local, remote, chunk);
                    sent += n;
                    waitingForOkay = true;
                }
            }
        } finally {
            socket.setSoTimeout(8000);
        }
        return result.toString("UTF-8");
    }

    @Override public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ wire format

    private static final class Packet {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        if (data == null) data = new byte[0];
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private Packet read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.cmd = b.getInt();
        p.arg0 = b.getInt();
        p.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt(); // checksum: adbd doesn't check it either
        if (b.getInt() != ~p.cmd || len < 0 || len > 1 << 20) throw new IOException("bad packet from the TV");
        p.data = new byte[len];
        in.readFully(p.data);
        return p;
    }

    // ------------------------------------------------------------------ keys

    /** This phone's adb key, made once and kept in app-private storage that isn't backed up. */
    private static synchronized KeyPair keys(Context c) throws Exception {
        File dir = c.getNoBackupFilesDir();
        File priv = new File(dir, "adbkey"), pub = new File(dir, "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048); // adbd only accepts 2048-bit keys
        KeyPair kp = g.generateKeyPair();
        writeAll(priv, kp.getPrivate().getEncoded());
        writeAll(pub, kp.getPublic().getEncoded());
        return kp;
    }

    /** adbd checks RSA_verify(NID_sha1, token): PKCS#1 v1.5 over a SHA-1 DigestInfo whose "hash" is the raw token. */
    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        byte[] block = new byte[256];
        block[0] = 0x00;
        block[1] = 0x01;
        int fill = 256 - 3 - SHA1_DIGEST_INFO.length - token.length;
        for (int i = 0; i < fill; i++) block[2 + i] = (byte) 0xff;
        block[2 + fill] = 0x00;
        System.arraycopy(SHA1_DIGEST_INFO, 0, block, 3 + fill, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, block, 3 + fill + SHA1_DIGEST_INFO.length, token.length);
        Cipher rsa = Cipher.getInstance("RSA/ECB/NoPadding");
        rsa.init(Cipher.ENCRYPT_MODE, key);
        return rsa.doFinal(block);
    }

    /** The key in Android's adb format: RSAPublicKey struct, base64, then " name\0". */
    private static byte[] publicKey(RSAPublicKey pk) {
        BigInteger n = pk.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(n.mod(r32).modInverse(r32).negate().intValue());
        for (int i = 0; i < 64; i++) {
            b.putInt(n.intValue());
            n = n.shiftRight(32);
        }
        for (int i = 0; i < 64; i++) {
            b.putInt(rr.intValue());
            rr = rr.shiftRight(32);
        }
        b.putInt(pk.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " QuietTV-Setup@phone\0";
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(data);
        }
    }
}
