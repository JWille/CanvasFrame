package com.canvasframe.home;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * HTTPS for the dashboard and TV-to-TV sync. Each TV makes its own key (EC P-256) and self-signed certificate once and
 * keeps them, so its fingerprint stays the same. Browsers show a one-time warning for it; other CanvasFrame TVs pin the
 * fingerprint they saw when the TV was added (see Sync), so a key is only ever sent to that exact certificate.
 * Plain Java (no Android classes), so it can be tested on the PC.
 */
final class Tls {
    private Tls() { }

    static final int PORT = 9443;   // 8443 is taken on Google TVs (Cast/Widevine)
    private static final String KEY_FILE = "tls.key", CERT_FILE = "tls.crt";
    private static final char[] PASS = "canvasframe".toCharArray();   // in-memory keystore only

    /** Server socket factory for this TV's certificate (made on first use in dir). */
    static SSLServerSocketFactory serverFactory(File dir, String commonName, String ip) throws GeneralSecurityException, IOException {
        ensure(dir, commonName, ip);
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(
                new PKCS8EncodedKeySpec(Files.readAllBytes(new File(dir, KEY_FILE).toPath())));
        Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(
                new java.io.ByteArrayInputStream(certDer(dir)));
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("cf", key, PASS, new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx.getServerSocketFactory();
    }

    static byte[] certDer(File dir) throws IOException { return Files.readAllBytes(new File(dir, CERT_FILE).toPath()); }

    /** "AB:CD:…" SHA-256 of the certificate, the form browsers and openssl show. */
    static String fingerprint(byte[] der) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) { if (sb.length() > 0) sb.append(':'); sb.append(String.format("%02X", b & 0xff)); }
            return sb.toString();
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    /**
     * Client socket factory that only talks to the certificate with fingerprint pin. With pin null it accepts any
     * certificate (first contact) and reports what it saw in seen[0].
     */
    static SSLSocketFactory clientFactory(final String pin, final String[] seen) throws GeneralSecurityException {
        X509TrustManager tm = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("not used");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("no certificate");
                String fp = fingerprint(chain[0].getEncoded());
                if (seen != null && seen.length > 0) seen[0] = fp;
                if (pin != null && !pin.equalsIgnoreCase(fp)) throw new PinMismatch(fp);
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
        return ctx.getSocketFactory();
    }

    /** The other TV presented a different certificate from the one recorded when it was added. */
    static final class PinMismatch extends CertificateException {
        final String seen;
        PinMismatch(String seen) { super("certificate changed"); this.seen = seen; }
    }

    // ------------------------------------------------------------------ certificate generation

    private static synchronized void ensure(File dir, String commonName, String ip) throws GeneralSecurityException, IOException {
        File keyF = new File(dir, KEY_FILE), certF = new File(dir, CERT_FILE);
        if (keyF.isFile() && certF.isFile()) return;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        KeyPair kp = g.generateKeyPair();
        byte[] cert = selfSigned(kp, commonName, ip);
        write(new File(dir, KEY_FILE + ".tmp"), kp.getPrivate().getEncoded(), keyF);
        write(new File(dir, CERT_FILE + ".tmp"), cert, certF);
    }

    private static void write(File tmp, byte[] data, File dest) throws IOException {
        try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(data); }
        if (!tmp.renameTo(dest)) throw new IOException("can't write " + dest);
    }

    private static final byte[] ECDSA_SHA256 = oid(1, 2, 840, 10045, 4, 3, 2);

    /** A v3 X.509 certificate, self-signed with SHA256withECDSA, valid for 20 years. */
    static byte[] selfSigned(KeyPair kp, String commonName, String ip) throws GeneralSecurityException {
        byte[] name = seq(set(seq(cat(oid(2, 5, 4, 3), tlv(0x0C, commonName.getBytes(StandardCharsets.UTF_8))))));
        byte[] alg = seq(ECDSA_SHA256);
        long now = System.currentTimeMillis();
        byte[] validity = seq(cat(time(new Date(now - 86_400_000L)), time(new Date(now + 20L * 365 * 86_400_000L))));
        byte[] serialBytes = new byte[16];
        new SecureRandom().nextBytes(serialBytes);
        serialBytes[0] &= 0x7f;
        byte[] serial = tlv(0x02, new BigInteger(1, serialBytes).toByteArray());
        // Extensions: basicConstraints CA:TRUE (critical) so pinning clients can treat it as its own anchor;
        // subjectAltName with the TV's address so browsers only complain about who signed it.
        byte[] exts = seq(cat(oid(2, 5, 29, 19), tlv(0x01, new byte[]{(byte) 0xff}), tlv(0x04, seq(tlv(0x01, new byte[]{(byte) 0xff})))));
        byte[] ipBytes = ipBytes(ip);
        if (ipBytes != null) exts = cat(exts, seq(cat(oid(2, 5, 29, 17), tlv(0x04, seq(tlv(0x87, ipBytes))))));
        byte[] tbs = seq(cat(
                tlv(0xA0, tlv(0x02, new byte[]{2})),   // version v3
                serial, alg, name, validity, name,
                kp.getPublic().getEncoded(),            // SubjectPublicKeyInfo, already DER
                tlv(0xA3, seq(exts))));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        return seq(cat(tbs, alg, tlv(0x03, bits)));
    }

    private static byte[] ipBytes(String ip) {
        if (ip == null || !ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return null;
        try { return InetAddress.getByName(ip).getAddress(); } catch (IOException e) { return null; }
    }

    private static byte[] time(Date d) {
        SimpleDateFormat f = new SimpleDateFormat(d.getTime() < 2524608000000L ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(d.getTime() < 2524608000000L ? 0x17 : 0x18, f.format(d).getBytes(StandardCharsets.US_ASCII));   // UTCTime before 2050
    }

    private static byte[] oid(int... arcs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int v = arcs[i];
            byte[] tmp = new byte[5];
            int n = 0;
            do { tmp[n++] = (byte) (v & 0x7f); v >>>= 7; } while (v != 0);
            for (int k = n - 1; k >= 0; k--) o.write(tmp[k] | (k > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }

    private static byte[] seq(byte[] content) { return tlv(0x30, content); }
    private static byte[] set(byte[] content) { return tlv(0x31, content); }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int len = content.length;
        if (len < 0x80) o.write(len);
        else if (len < 0x100) { o.write(0x81); o.write(len); }
        else if (len < 0x10000) { o.write(0x82); o.write(len >> 8); o.write(len & 0xff); }
        else { o.write(0x83); o.write(len >> 16); o.write((len >> 8) & 0xff); o.write(len & 0xff); }
        o.write(content, 0, len);
        return o.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }
}
