package com.canvasframe.home;

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
 * Minimal ADB client that talks to the TV's own adbd over localhost:5555.
 * This gives the dashboard shell-level access (input keyevent, pm, settings, dumpsys)
 * without root. The first connection shows "Allow USB debugging?" on the TV once.
 */
public class AdbClient {

    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    public enum State { DISCONNECTED, CONNECTING, WAITING_FOR_APPROVAL, CONNECTED, ERROR }

    private final Context ctx;
    private final String host;
    private final int port;
    private KeyPair keys;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int nextLocalId = 1;
    private volatile State state = State.DISCONNECTED;
    private volatile String lastError = "";

    public AdbClient(Context ctx, String host, int port) {
        this.ctx = ctx.getApplicationContext();
        this.host = host;
        this.port = port;
    }

    public State state() { return state; }
    public String lastError() { return lastError; }

    /** Run a shell command and return its combined output. Reconnects as needed. */
    public synchronized String shell(String cmd) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensureConnected();
                return runShell(cmd);
            } catch (IOException e) {
                closeQuietly();
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                if (state != State.WAITING_FOR_APPROVAL) state = State.ERROR;
                if (attempt == 1 || state == State.WAITING_FOR_APPROVAL) throw e;
            }
        }
        throw new IOException("unreachable");
    }

    /** Run a command and return its raw output bytes (e.g. "screencap -p"). */
    public synchronized byte[] shellBytes(String cmd) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensureConnected();
                return runShellBytes(cmd);
            } catch (IOException e) {
                closeQuietly();
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                if (state != State.WAITING_FOR_APPROVAL) state = State.ERROR;
                if (attempt == 1 || state == State.WAITING_FOR_APPROVAL) throw e;
            }
        }
        throw new IOException("unreachable");
    }

    // ------------------------------------------------------------------ connection

    private void ensureConnected() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed() && state == State.CONNECTED) return;
        closeQuietly();
        loadKeys();
        state = State.CONNECTING;
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 3000);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        send(A_CNXN, VERSION, MAX_DATA, "host::canvasframe\0".getBytes(StandardCharsets.UTF_8));

        boolean sentSignature = false, sentKey = false;
        socket.setSoTimeout(10000);
        while (true) {
            Msg m;
            try {
                m = read();
            } catch (java.net.SocketTimeoutException e) {
                if (sentKey) {
                    state = State.WAITING_FOR_APPROVAL;
                    throw new IOException("Waiting for 'Allow USB debugging' approval on the TV");
                }
                throw e;
            }
            if (m.cmd == A_CNXN) {
                state = State.CONNECTED;
                lastError = "";
                socket.setSoTimeout(30000);
                return;
            }
            if (m.cmd == A_AUTH && m.arg0 == AUTH_TOKEN) {
                if (!sentSignature) {
                    send(A_AUTH, AUTH_SIGNATURE, 0, sign(m.data));
                    sentSignature = true;
                } else if (!sentKey) {
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, adbPublicKey());
                    sentKey = true;
                    state = State.WAITING_FOR_APPROVAL;
                    socket.setSoTimeout(60000); // give the user time to press Allow
                } else {
                    throw new IOException("ADB authorization rejected");
                }
            }
        }
    }

    private String runShell(String cmd) throws IOException {
        return new String(runShellBytes(cmd), StandardCharsets.UTF_8);
    }

    private byte[] runShellBytes(String cmd) throws IOException {
        int localId = nextLocalId++;
        send(A_OPEN, localId, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int remoteId = 0;
        while (true) {
            Msg m = read();
            if (m.arg1 != localId && m.cmd != A_CNXN) continue; // not for this stream
            if (m.cmd == A_OKAY) {
                remoteId = m.arg0;
            } else if (m.cmd == A_WRTE) {
                remoteId = m.arg0;
                buf.write(m.data);
                send(A_OKAY, localId, remoteId, null);
            } else if (m.cmd == A_CLSE) {
                if (remoteId != 0) send(A_CLSE, localId, remoteId, null);
                break;
            }
        }
        return buf.toByteArray();
    }

    public synchronized void close() { closeQuietly(); state = State.DISCONNECTED; }

    private void closeQuietly() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        socket = null;
    }

    // ------------------------------------------------------------------ wire format

    private static class Msg { int cmd, arg0, arg1; byte[] data; }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        if (data != null) for (byte b : data) sum += (b & 0xff);
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] hdr = new byte[24];
        in.readFully(hdr);
        ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        m.data = new byte[len];
        if (len > 0) in.readFully(m.data);
        return m;
    }

    // ------------------------------------------------------------------ keys

    private void loadKeys() throws IOException {
        if (keys != null) return;
        File priv = new File(ctx.getFilesDir(), "adbkey");
        File pub = new File(ctx.getFilesDir(), "adbkey.pub");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (priv.exists() && pub.exists()) {
                keys = new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
            } else {
                KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                g.initialize(2048);
                keys = g.generateKeyPair();
                writeAll(priv, keys.getPrivate().getEncoded());
                writeAll(pub, keys.getPublic().getEncoded());
            }
        } catch (Exception e) {
            throw new IOException("key setup failed: " + e);
        }
    }

    private byte[] sign(byte[] token) throws IOException {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.ENCRYPT_MODE, (PrivateKey) keys.getPrivate());
            c.update(SHA1_PREFIX);
            return c.doFinal(token);
        } catch (Exception e) {
            throw new IOException("sign failed: " + e);
        }
    }

    /** Android's custom RSA public key blob, base64-encoded, plus a name. */
    private byte[] adbPublicKey() {
        RSAPublicKey pk = (RSAPublicKey) keys.getPublic();
        BigInteger n = pk.getModulus();
        int words = 2048 / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);

        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, 256));
        b.put(littleEndian(rr, 256));
        b.putInt(pk.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " canvasframe@tv\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] littleEndian(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) { os.write(data); }
    }
}
