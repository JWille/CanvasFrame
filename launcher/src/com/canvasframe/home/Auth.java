package com.canvasframe.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Optional dashboard sign-in with TOTP authenticator apps (RFC 6238: SHA-1, 6 digits, 30 s).
 * Off while no authenticator is enrolled. Several authenticators can be enrolled (one per person/device);
 * a code from any of them signs a browser in, and the browser keeps a session cookie for {@link #SESSION_MS}.
 * Stored in the app's private prefs; clear everything with
 * {@code adb shell am broadcast -f 32 -n com.canvasframe.home/.AuthResetReceiver}.
 */
final class Auth {
    private Auth() { }

    static final String COOKIE = "cf_session";
    static final long SESSION_MS = 30L * 24 * 3600 * 1000;
    private static final String PREFS = "canvasframe_auth";
    private static final String K_AUTHS = "authenticators";
    private static final String K_SESSIONS = "sessions";
    private static final String K_KEYS = "api_keys";
    private static final String ISSUER = "CanvasFrame";

    // Brute-force limits: per address, then for everyone.
    private static final int IP_MAX_FAILS = 5;
    private static final long IP_LOCK_MS = 5 * 60_000;
    private static final int ALL_MAX_FAILS = 20;
    private static final long ALL_WINDOW_MS = 10 * 60_000;

    private static final SecureRandom RNG = new SecureRandom();
    private static final Object LOCK = new Object();
    private static final Map<String, JSONObject> pending = new HashMap<>();   // enrolment id -> {name, secret, expires}
    private static final Map<String, long[]> ipFails = new HashMap<>();       // ip -> {count, lockedUntil}
    private static long allWindowStart;
    private static int allFails;

    static class AuthException extends Exception {
        AuthException(String m) { super(m); }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray auths(Context c) {
        try { return new JSONArray(prefs(c).getString(K_AUTHS, "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    private static JSONObject sessions(Context c) {
        try { return new JSONObject(prefs(c).getString(K_SESSIONS, "{}")); } catch (JSONException e) { return new JSONObject(); }
    }

    private static void save(Context c, JSONArray auths, JSONObject sessions) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (auths != null) e.putString(K_AUTHS, auths.toString());
        if (sessions != null) e.putString(K_SESSIONS, sessions.toString());
        e.commit();
    }

    static boolean enabled(Context c) { return auths(c).length() > 0; }

    /** Requests allowed without a session when sign-in is on: the page itself and the sign-in calls. */
    static boolean isPublic(String method, String path) {
        return method.equals("GET") && (path.equals("/") || path.equals("/index.html") || path.equals("/api/auth") || path.equals("/api/tls"))
                || method.equals("POST") && (path.equals("/api/auth/login") || path.equals("/api/auth/pass-login"));
    }

    /** The session record for this cookie token, or null. Refreshes "last seen" at most every 10 minutes. */
    static JSONObject session(Context c, String token) {
        if (token == null || token.isEmpty()) return null;
        synchronized (LOCK) {
            JSONObject all = sessions(c);
            String key = sha256(token);
            JSONObject s = all.optJSONObject(key);
            if (s == null) return null;
            long now = System.currentTimeMillis();
            if (now - s.optLong("seen") > SESSION_MS || authById(auths(c), s.optString("auth")) == null) {
                all.remove(key);
                save(c, null, all);
                return null;
            }
            if (now - s.optLong("seen") > 10 * 60_000) {
                try { s.put("seen", now); all.put(key, s); } catch (JSONException ignored) { }
                save(c, null, all);
            }
            return s;
        }
    }

    // ------------------------------------------------------------- sign in / out

    /** Checks a code against every authenticator; returns a new session token. */
    static String login(Context c, String ip, String code, String ua) throws AuthException {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            long[] f = ipFails.get(ip);
            if (f != null && f[1] > now) throw new AuthException("Too many wrong codes. Try again in "
                    + ((f[1] - now) / 60_000 + 1) + " min.");
            if (now - allWindowStart > ALL_WINDOW_MS) { allWindowStart = now; allFails = 0; }
            if (allFails >= ALL_MAX_FAILS) throw new AuthException("Too many wrong codes. Try again in a few minutes.");

            JSONArray auths = auths(c);
            JSONObject hit = null;
            for (int i = 0; i < auths.length() && hit == null; i++) {
                JSONObject a = auths.optJSONObject(i);
                if (a != null && accept(a, code, now)) hit = a;
            }
            if (hit == null) {
                allFails++;
                if (f == null) { f = new long[2]; ipFails.put(ip, f); }
                if (++f[0] >= IP_MAX_FAILS) { f[0] = 0; f[1] = now + IP_LOCK_MS; }
                throw new AuthException("That code didn't match. Check the TV's clock is right, and use a fresh code.");
            }
            ipFails.remove(ip);
            save(c, auths, null);
            return newSession(c, hit.optString("id"), ua, null, null);
        }
    }

    static void logout(Context c, String token) {
        if (token == null) return;
        synchronized (LOCK) {
            JSONObject all = sessions(c);
            all.remove(sha256(token));
            save(c, null, all);
        }
    }

    /** Signs out one session (by its short id), or every session except {@code keepToken} when id is "others". */
    static void revoke(Context c, String id, String keepToken) {
        synchronized (LOCK) {
            JSONObject all = sessions(c);
            String keep = keepToken == null ? "" : sha256(keepToken);
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                String k = it.next();
                if ("others".equals(id) ? !k.equals(keep) : k.startsWith(id)) it.remove();
            }
            save(c, null, all);
        }
    }

    /** chain: shared by the sign-ins one code sign-in leads to on the other TVs (passes carry it), for "Sign out on all TVs";
     *  a new one for a code sign-in. via: the TV that vouched (pass sign-ins). */
    private static String newSession(Context c, String authId, String ua, String chain, String via) {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        String token = hex(b);
        JSONObject all = sessions(c);
        long now = System.currentTimeMillis();
        try {
            JSONObject s = new JSONObject().put("auth", authId).put("created", now).put("seen", now)
                    .put("ua", ua == null ? "" : ua.length() > 200 ? ua.substring(0, 200) : ua)
                    .put("chain", chain == null || chain.isEmpty() ? hex(randomBytes(12)) : chain);
            if (via != null && !via.isEmpty()) s.put("via", via);
            all.put(sha256(token), s);
        } catch (JSONException ignored) { }
        save(c, null, all);
        return token;
    }

    // ------------------------------------------------------------- sign-in passes between TVs
    // A signed-in TV (or the PC app with an API key) vouches for you on another TV of the mesh: a one-time pass, valid ~2 minutes,
    // signed with the mesh secret (site item sso:key, which only travels TV-to-TV over the API-key links, like the authenticators).
    // pass = base64url(JSON {v, to, from, exp, n, auth, chain, who}) + "." + base64url(HMAC-SHA256(secret, first part)).
    // Same format in webos/server/lib/auth.js.

    private static final Map<String, Long> usedPasses = new HashMap<>();   // nonce -> expiry

    static String makePass(String secret, String from, String to, String authId, String chain, String who) throws JSONException {
        JSONObject p = new JSONObject().put("v", 1).put("to", to).put("from", from).put("exp", System.currentTimeMillis() + 120_000)
                .put("n", hex(randomBytes(12))).put("auth", authId).put("chain", chain).put("who", who);
        String body = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(p.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return body + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(secret, body));
    }

    private static byte[] hmac(String secret, String msg) {
        try {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            m.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return m.doFinal(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Checks a pass for this TV (me) and returns a new session token. Failures count like wrong codes. */
    static String passLogin(Context c, String ip, String pass, String secret, String me, String ua) throws AuthException {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            long[] f = ipFails.get(ip);
            if (f != null && f[1] > now) throw new AuthException("Too many failed sign-ins. Try again in " + ((f[1] - now) / 60_000 + 1) + " min.");
            JSONObject p = null;
            String why = "That sign-in link isn't valid (it may be old). Sign in with a code instead.";
            try {
                int dot = pass == null ? -1 : pass.indexOf('.');
                if (dot > 0 && secret != null && !secret.isEmpty()) {
                    String body = pass.substring(0, dot);
                    byte[] sig = java.util.Base64.getUrlDecoder().decode(pass.substring(dot + 1));
                    if (java.security.MessageDigest.isEqual(sig, hmac(secret, body)))
                        p = new JSONObject(new String(java.util.Base64.getUrlDecoder().decode(body), java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) { p = null; }
            if (p != null) {
                long exp = p.optLong("exp");
                for (Iterator<Map.Entry<String, Long>> it = usedPasses.entrySet().iterator(); it.hasNext(); ) if (it.next().getValue() < now - 120_000) it.remove();
                if (!me.equals(p.optString("to"))) { why = "That sign-in link was made for another TV."; p = null; }
                else if (now > exp + 60_000 || exp - now > 300_000) { why = "That sign-in link has expired. Sign in with a code instead."; p = null; }
                else if (usedPasses.containsKey(p.optString("n"))) { why = "That sign-in link was already used."; p = null; }
            }
            if (p == null) {
                if (f == null) { f = new long[2]; ipFails.put(ip, f); }
                if (++f[0] >= IP_MAX_FAILS) { f[0] = 0; f[1] = now + IP_LOCK_MS; }
                throw new AuthException(why);
            }
            usedPasses.put(p.optString("n"), p.optLong("exp"));
            JSONArray auths = auths(c);
            JSONObject a = authById(auths, p.optString("auth"));
            if (a == null && auths.length() > 0) a = auths.optJSONObject(0);   // not synced here yet: any (they're the same people)
            if (a == null) throw new AuthException("Sign-in isn't set up on this TV.");
            ipFails.remove(ip);
            return newSession(c, a.optString("id"), ua, p.optString("chain"), p.optString("who"));
        }
    }

    /** The authenticator id and chain of a session, for making a pass ({auth, chain}); a key caller gets the first authenticator. */
    static String[] passIdentity(Context c, String token) {
        synchronized (LOCK) {
            JSONObject s = token == null ? null : sessions(c).optJSONObject(sha256(token));
            JSONArray auths = auths(c);
            String auth = s != null ? s.optString("auth") : auths.length() > 0 ? auths.optJSONObject(0).optString("id") : "";
            String chain = s != null ? s.optString("chain") : "";
            if (s != null && chain.isEmpty()) {   // a session from before chains: give it one now
                chain = hex(randomBytes(12));
                try { s.put("chain", chain); JSONObject all = sessions(c); all.put(sha256(token), s); save(c, null, all); } catch (JSONException ignored) { }
            }
            return new String[]{auth, chain.isEmpty() ? hex(randomBytes(12)) : chain};
        }
    }

    /** "Sign out on all TVs": ends every session of this chain on this TV. */
    static int revokeChain(Context c, String chain) {
        if (chain == null || chain.isEmpty()) return 0;
        synchronized (LOCK) {
            JSONObject all = sessions(c);
            int n = 0;
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                JSONObject s = all.optJSONObject(it.next());
                if (s != null && chain.equals(s.optString("chain"))) { it.remove(); n++; }
            }
            if (n > 0) save(c, null, all);
            return n;
        }
    }

    // ------------------------------------------------------------- authenticators

    /** Starts adding an authenticator: returns the secret, otpauth link and QR code; confirm with a code. */
    static JSONObject enrollStart(String name) throws JSONException {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) name = "Authenticator";
        if (name.length() > 32) name = name.substring(0, 32);
        byte[] key = new byte[20];
        RNG.nextBytes(key);
        String secret = base32(key);
        String id = hex(randomBytes(6));
        String uri;
        try {
            uri = "otpauth://totp/" + ISSUER + ":" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                    + "?secret=" + secret + "&issuer=" + ISSUER;   // SHA1 / 6 digits / 30 s are the defaults
        } catch (java.io.UnsupportedEncodingException e) { throw new RuntimeException(e); }
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            for (Iterator<JSONObject> it = pending.values().iterator(); it.hasNext(); )
                if (it.next().optLong("expires") < now) it.remove();
            pending.put(id, new JSONObject().put("name", name).put("secret", secret).put("expires", now + 15 * 60_000));
        }
        return new JSONObject().put("id", id).put("name", name).put("secret", secret).put("uri", uri).put("qr", Qr.svg(uri));
    }

    /**
     * Finishes adding an authenticator once the user types a code it shows. Returns a session token when the
     * caller has no session yet (i.e. this is the first authenticator, which turns sign-in on), else null.
     */
    static String enrollConfirm(Context c, String id, String code, boolean hasSession, String ua)
            throws AuthException, JSONException {
        synchronized (LOCK) {
            JSONObject p = pending.get(id);
            if (p == null || p.optLong("expires") < System.currentTimeMillis())
                throw new AuthException("This setup expired. Start again.");
            JSONObject a = new JSONObject().put("id", id).put("name", p.getString("name"))
                    .put("secret", p.getString("secret")).put("added", System.currentTimeMillis()).put("lastStep", 0);
            if (!accept(a, code, System.currentTimeMillis()))
                throw new AuthException("That code didn't match. Check the TV's clock is right, and use a fresh code.");
            pending.remove(id);
            JSONArray auths = auths(c);
            auths.put(a);
            save(c, auths, null);
            return hasSession ? null : newSession(c, id, ua, null, null);
        }
    }

    static void remove(Context c, String id) {
        synchronized (LOCK) {
            JSONArray auths = auths(c), keep = new JSONArray();
            for (int i = 0; i < auths.length(); i++) {
                JSONObject a = auths.optJSONObject(i);
                if (a != null && !a.optString("id").equals(id)) keep.put(a);
            }
            JSONObject all = sessions(c);
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                JSONObject s = all.optJSONObject(it.next());
                if (s == null || s.optString("auth").equals(id) || keep.length() == 0) it.remove();
            }
            save(c, keep, all);
        }
    }

    // ------------------------------------------------------------- shared with the other TVs (Site "auth:" items)

    /** The authenticators with their secrets, for Site (only ever sent TV-to-TV over the API-key links). */
    static JSONArray syncList(Context c) throws JSONException {
        JSONArray out = new JSONArray(), all = auths(c);
        for (int i = 0; i < all.length(); i++) {
            JSONObject a = all.optJSONObject(i);
            if (a != null) out.put(new JSONObject().put("id", a.optString("id")).put("name", a.optString("name"))
                    .put("secret", a.optString("secret")).put("added", a.optLong("added")));
        }
        return out;
    }

    /** Adds an authenticator another TV shared; false if it's already here or malformed. */
    static boolean addSynced(Context c, String id, String name, String secret, long added) throws JSONException {
        if (id == null || !id.matches("[a-f0-9]{6,40}") || secret == null || !secret.matches("[A-Z2-7]{16,}")) return false;
        synchronized (LOCK) {
            JSONArray all = auths(c);
            if (authById(all, id) != null) return false;
            String n = name == null || name.isEmpty() ? "Authenticator" : name.length() > 32 ? name.substring(0, 32) : name;
            all.put(new JSONObject().put("id", id).put("name", n).put("secret", secret)
                    .put("added", added > 0 ? added : System.currentTimeMillis()).put("lastStep", 0));
            save(c, all, null);
            return true;
        }
    }

    // ------------------------------------------------------------- API keys
    // For other CanvasFrame TVs ("Sync to other TVs") and, later, Home Assistant. A valid key counts as signed in.
    // Only a hash is stored; the key is shown once when created.

    private static JSONArray keys(Context c) {
        try { return new JSONArray(prefs(c).getString(K_KEYS, "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    static JSONObject createKey(Context c, String name) throws JSONException {
        synchronized (LOCK) {
            String key = "cfk_" + hex(randomBytes(24));
            name = name == null || name.trim().isEmpty() ? "API key" : name.trim();
            if (name.length() > 40) name = name.substring(0, 40);
            JSONObject k = new JSONObject().put("id", hex(randomBytes(4))).put("name", name).put("hash", sha256(key))
                    .put("created", System.currentTimeMillis()).put("lastUsed", 0);
            JSONArray all = keys(c);
            all.put(k);
            prefs(c).edit().putString(K_KEYS, all.toString()).commit();
            return new JSONObject().put("id", k.getString("id")).put("name", name).put("key", key);
        }
    }

    static boolean keyValid(Context c, String key) {
        if (key == null || !key.startsWith("cfk_")) return false;
        synchronized (LOCK) {
            String h = sha256(key);
            JSONArray all = keys(c);
            for (int i = 0; i < all.length(); i++) {
                JSONObject k = all.optJSONObject(i);
                if (k == null || !h.equals(k.optString("hash"))) continue;
                if (System.currentTimeMillis() - k.optLong("lastUsed") > 10 * 60_000) {
                    try { k.put("lastUsed", System.currentTimeMillis()); } catch (JSONException ignored) { }
                    prefs(c).edit().putString(K_KEYS, all.toString()).apply();
                }
                return true;
            }
            return false;
        }
    }

    static void revokeKey(Context c, String id) {
        synchronized (LOCK) {
            JSONArray all = keys(c), keep = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject k = all.optJSONObject(i);
                if (k != null && !k.optString("id").equals(id)) keep.put(k);
            }
            prefs(c).edit().putString(K_KEYS, keep.toString()).commit();
        }
    }

    static void reset(Context c) {
        synchronized (LOCK) {
            prefs(c).edit().clear().commit();
            pending.clear();
            ipFails.clear();
        }
    }

    /** Status for the dashboard. Lists are only included for signed-in callers (or when sign-in is off). */
    static JSONObject status(Context c, String token) throws JSONException {
        JSONArray auths = auths(c);
        JSONObject me = session(c, token);
        boolean on = auths.length() > 0, authed = !on || me != null;
        JSONObject r = new JSONObject().put("enabled", on).put("authed", authed);
        if (!authed) return r;
        JSONArray list = new JSONArray();
        for (int i = 0; i < auths.length(); i++) {
            JSONObject a = auths.getJSONObject(i);
            list.put(new JSONObject().put("id", a.optString("id")).put("name", a.optString("name"))
                    .put("added", a.optLong("added")).put("lastUsed", a.optLong("lastStep") * 30_000));
        }
        r.put("authenticators", list);
        String mine = token == null ? "" : sha256(token);
        JSONArray ss = new JSONArray();
        JSONObject all = sessions(c);
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject s = all.optJSONObject(k);
            if (s == null) continue;
            JSONObject a = authById(auths, s.optString("auth"));
            ss.put(new JSONObject().put("id", k.substring(0, 12)).put("current", k.equals(mine))
                    .put("auth", a == null ? "?" : a.optString("name")).put("ua", s.optString("ua")).put("via", s.optString("via"))
                    .put("created", s.optLong("created")).put("seen", s.optLong("seen")));
        }
        r.put("sessions", ss);
        JSONArray ks = keys(c), kl = new JSONArray();
        for (int i = 0; i < ks.length(); i++) {
            JSONObject k = ks.getJSONObject(i);
            kl.put(new JSONObject().put("id", k.optString("id")).put("name", k.optString("name"))
                    .put("created", k.optLong("created")).put("lastUsed", k.optLong("lastUsed")));
        }
        r.put("keys", kl);
        if (me != null) {
            JSONObject a = authById(auths, me.optString("auth"));
            r.put("me", a == null ? "" : a.optString("name"));
        }
        return r;
    }

    // ------------------------------------------------------------- TOTP

    /** Accepts the code for the current 30 s step or one either side; never the same or an older step twice. */
    private static boolean accept(JSONObject a, String code, long now) {
        if (code == null) return false;
        code = code.replaceAll("\\s", "");
        if (!code.matches("\\d{6}")) return false;
        byte[] key = base32Decode(a.optString("secret"));
        long step = now / 30_000, last = a.optLong("lastStep");
        int want = Integer.parseInt(code);
        for (long s = step - 1; s <= step + 1; s++) {
            if (s > last && totp(key, s) == want) {
                try { a.put("lastStep", s); } catch (JSONException ignored) { }
                return true;
            }
        }
        return false;
    }

    static int totp(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return bin % 1_000_000;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------- helpers

    private static JSONObject authById(JSONArray auths, String id) {
        for (int i = 0; i < auths.length(); i++) {
            JSONObject a = auths.optJSONObject(i);
            if (a != null && a.optString("id").equals(id)) return a;
        }
        return null;
    }

    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    static String base32(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte x : b) {
            buf = (buf << 8) | (x & 0xff);
            bits += 8;
            while (bits >= 5) { sb.append(B32.charAt((buf >>> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) sb.append(B32.charAt((buf << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        s = s.toUpperCase().replaceAll("[^A-Z2-7]", "");
        byte[] out = new byte[s.length() * 5 / 8];
        int buf = 0, bits = 0, n = 0;
        for (int i = 0; i < s.length(); i++) {
            buf = (buf << 5) | B32.indexOf(s.charAt(i));
            bits += 5;
            if (bits >= 8) { out[n++] = (byte) (buf >>> (bits - 8)); bits -= 8; }
        }
        return out;
    }

    private static byte[] randomBytes(int n) { byte[] b = new byte[n]; RNG.nextBytes(b); return b; }

    /** First 16 hex chars of SHA-1 (the mesh's photo ids; same as lib/site.js). */
    static String sha256Sha1(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return hex(d).substring(0, 16);
        } catch (Exception e) { return ""; }
    }

    static String sha256(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }
}
