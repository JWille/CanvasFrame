package com.canvasframe.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Ad hoc "Sync to other TVs": copies this TV's home-screen settings, calendars and (optionally) photos with their
 * tags to other CanvasFrame TVs (Android or webOS), straight TV-to-TV over their dashboard API. Each target is
 * reached with an API key created on that TV, so it works when the target has sign-in on. Same behaviour as
 * webos/server/lib/sync.js.
 */
final class Sync {
    private Sync() { }

    private static final String PEERS = "peers";   // in Config.prefs; holds the keys, never sent to browsers
    private static final String[] SETTINGS = {Config.WEATHER_ON, Config.WEATHER_PLACE, Config.WEATHER_LAT, Config.WEATHER_LON,
            Config.UNITS, Config.CLOCK, Config.SHOW_DASH, Config.AUTO_HIDE, Config.CONTROLS_ALWAYS, Config.PHOTO_MIN};

    /** The peer records with their keys: for Site replication only, never for browsers. */
    static JSONArray peers(Context c) {
        try { return new JSONArray(Config.prefs(c).getString(PEERS, "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    static void save(Context c, JSONArray a) { Config.prefs(c).edit().putString(PEERS, a.toString()).apply(); }

    /** Peers without their keys, for the dashboard. */
    static JSONArray list(Context c) throws JSONException {
        JSONArray in = peers(c), out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject p = in.getJSONObject(i);
            out.put(new JSONObject().put("id", p.optString("id")).put("name", p.optString("name")).put("url", p.optString("url"))
                    .put("brand", p.optString("brand")).put("lastSync", p.optLong("lastSync")).put("pin", p.optString("pin")));
        }
        return out;
    }

    /** The list, after checking each TV can be reached from here (and picking up name changes). */
    static JSONArray check(Context c) throws JSONException {
        JSONArray all = peers(c), out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.getJSONObject(i);
            JSONObject o = new JSONObject().put("id", p.optString("id")).put("lastSync", p.optLong("lastSync"));
            try {
                secure(p);
                JSONObject st = call(p, "GET", "/api/status", null, null, null, null, 4000);
                p.put("name", st.optString("name", st.optString("model", p.optString("name")))).put("brand", st.optString("brand", p.optString("brand")));
                o.put("reachable", true);
            } catch (Unreachable e) {
                o.put("reachable", false).put("why", explain(p));
            } catch (IOException e) {
                o.put("reachable", false).put("why", e.getMessage()).put("badKey", e instanceof BadKey);
            }
            out.put(o.put("name", p.optString("name")).put("brand", p.optString("brand")).put("url", p.optString("url"))
                    .put("pin", p.optString("pin")));
        }
        save(c, all);
        return out;
    }

    static String normUrl(String u) {
        u = u == null ? "" : u.trim().replaceAll("/+$", "");
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        if (!u.replaceFirst("^https?://", "").matches(".*:\\d+$")) u += ":8080";
        return u;
    }

    /** Plain explanation when another TV can't be reached from this one (usually a router/firewall rule). */
    static String explain(JSONObject peer) {
        return "This TV (" + DashboardService.lanIp() + ") can't connect to " + peer.optString("url").replaceFirst("^https?://", "")
                + ". Your router or firewall probably doesn't allow connections from this TV's network to that one. Sync from the"
                + " other TV instead (it can often reach this one), or allow the connection on your router.";
    }

    private static class Unreachable extends IOException { Unreachable() { super("cannot reach it"); } }

    /** The other TV presented a different certificate from the one recorded when it was added. */
    private static final String PIN_CHANGED = "That TV's security certificate has changed since it was added, so its key wasn't sent."
            + " If that TV was reset or reinstalled, remove it here and add it again with a new key; otherwise something on the"
            + " network may be intercepting the connection.";

    /**
     * Before any key is sent: move a TV added as http:// to its HTTPS address (found via its public /api/tls, no key sent),
     * and on first contact record the certificate it presents. Afterwards keys only go to that exact certificate.
     * A TV running an older CanvasFrame without HTTPS stays on http://. Returns true if the peer record changed.
     */
    static boolean secure(JSONObject peer) throws IOException, JSONException {
        boolean changed = false;
        String url = peer.optString("url");
        if (url.startsWith("http://")) {
            JSONObject t = fetch(url + "/api/tls", null);
            int port = t == null ? 0 : t.optInt("port");
            if (port <= 0 || !t.optBoolean("ready", true)) return false;
            url = "https://" + url.substring(7).replaceFirst(":\\d+$", "") + ":" + port;
            peer.put("url", url);
            changed = true;
        }
        if (url.startsWith("https://") && peer.optString("pin").isEmpty()) {
            String[] seen = new String[1];
            try {
                fetch(url + "/api/tls", Tls.clientFactory(null, seen));
            } catch (java.security.GeneralSecurityException e) { throw new IOException(e.getMessage()); }
            if (seen[0] == null) throw new IOException("That TV didn't present a certificate");
            peer.put("pin", seen[0]);
            changed = true;
        }
        return changed;
    }

    /** GET without a key: the JSON body, or null if the TV answers with an error (e.g. older versions without /api/tls). */
    private static JSONObject fetch(String url, javax.net.ssl.SSLSocketFactory tls) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        if (h instanceof javax.net.ssl.HttpsURLConnection && tls != null) {
            ((javax.net.ssl.HttpsURLConnection) h).setSSLSocketFactory(tls);
            ((javax.net.ssl.HttpsURLConnection) h).setHostnameVerifier((host, session) -> true);   // identity = the pinned certificate
        }
        h.setConnectTimeout(6000);
        h.setReadTimeout(8000);
        h.setInstanceFollowRedirects(false);
        try {
            int code = h.getResponseCode();
            if (code != 200) return null;
            try (InputStream is = h.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int r;
                while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
                try { return new JSONObject(bo.toString("UTF-8")); } catch (JSONException e) { return null; }
            }
        } catch (java.net.ConnectException | java.net.SocketTimeoutException | java.net.NoRouteToHostException e) {
            throw new Unreachable();
        } finally {
            h.disconnect();
        }
    }
    private static class BadKey extends IOException { BadKey(String m) { super(m); } }

    private static JSONObject call(JSONObject peer, String method, String path, byte[] body, String type, String filename, String tags)
            throws IOException {
        return call(peer, method, path, body, type, filename, tags, 60000);
    }

    static JSONObject call(JSONObject peer, String method, String path, byte[] body, String type, String filename, String tags,
                                   int readTimeout) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(peer.optString("url") + path).openConnection();
        if (h instanceof javax.net.ssl.HttpsURLConnection) {
            String pin = peer.optString("pin");
            if (pin.isEmpty()) throw new IOException("No certificate recorded for that TV yet");
            try {
                ((javax.net.ssl.HttpsURLConnection) h).setSSLSocketFactory(Tls.clientFactory(pin, null));
            } catch (java.security.GeneralSecurityException e) { throw new IOException(e.getMessage()); }
            ((javax.net.ssl.HttpsURLConnection) h).setHostnameVerifier((host, session) -> true);   // identity = the pinned certificate
        }
        h.setInstanceFollowRedirects(false);
        h.setConnectTimeout(Math.min(8000, readTimeout));
        h.setReadTimeout(readTimeout);
        h.setRequestMethod(method);
        h.setRequestProperty("X-CanvasFrame", "1");
        h.setRequestProperty("X-CanvasFrame-Key", peer.optString("key"));
        if (filename != null) h.setRequestProperty("X-Filename", URLEncoder.encode(filename, "UTF-8").replace("+", "%20"));
        if (tags != null) h.setRequestProperty("X-Tags", tags);
        try {
            if (body != null) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", type);
                h.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = h.getOutputStream()) { os.write(body); }
            }
            int code = h.getResponseCode();
            InputStream is = code >= 400 ? h.getErrorStream() : h.getInputStream();
            String text = "";
            if (is != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
                is.close();
                text = bo.toString("UTF-8");
            }
            JSONObject j;
            try { j = new JSONObject(text.isEmpty() ? "{}" : text); } catch (JSONException e) { j = new JSONObject(); }
            if (code == 401) throw new BadKey("That TV doesn't recognise the saved key (it was revoked or replaced). Create a new key on that TV, then remove and re-add the TV here.");
            if (code >= 400) throw new IOException(j.optString("error", "HTTP " + code));
            return j;
        } catch (javax.net.ssl.SSLHandshakeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof Tls.PinMismatch) throw new IOException(PIN_CHANGED);
            throw e;
        } catch (java.net.ConnectException | java.net.SocketTimeoutException | java.net.NoRouteToHostException e) {
            throw new Unreachable();
        } finally {
            h.disconnect();
        }
    }

    static JSONObject json(JSONObject peer, String method, String path, JSONObject body) throws IOException {
        return call(peer, method, path, body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), "application/json", null, null);
    }

    static void add(Context c, String url, String key) throws IOException, JSONException {
        JSONObject peer = new JSONObject().put("url", normUrl(url)).put("key", key == null ? "" : key.trim());
        if (peer.optString("key").isEmpty()) throw new IllegalArgumentException("Paste the API key created on that TV");
        JSONArray all = peers(c);
        for (int i = 0; i < all.length(); i++)
            if (all.getJSONObject(i).optString("url").equals(peer.getString("url"))) throw new IllegalArgumentException("That TV is already in the list");
        JSONObject st;
        try {
            secure(peer);
            for (int i = 0; i < all.length(); i++)
                if (all.getJSONObject(i).optString("url").equals(peer.getString("url"))) throw new IllegalArgumentException("That TV is already in the list");
            st = call(peer, "GET", "/api/status", null, null, null, null, 8000);
            call(peer, "GET", "/api/settings", null, null, null, null, 8000);   // proves the key works
        } catch (Unreachable e) { throw new IOException(explain(peer)); }
        peer.put("name", st.optString("name", st.optString("model", peer.getString("url")))).put("brand", st.optString("brand"))
                .put("id", Long.toString(System.currentTimeMillis(), 36));
        all.put(peer);
        save(c, all);
    }

    static void remove(Context c, String id) throws JSONException {
        JSONArray all = peers(c), keep = new JSONArray();
        for (int i = 0; i < all.length(); i++) if (!all.getJSONObject(i).optString("id").equals(id)) keep.put(all.getJSONObject(i));
        save(c, keep);
    }

    private static String sortedTags(Iterable<?> tags) {
        List<String> l = new ArrayList<>();
        for (Object t : tags) l.add(String.valueOf(t));
        Collections.sort(l);
        return android.text.TextUtils.join(",", l);
    }

    /** Copy to each selected peer; per-target results. */
    private static void notify(JSONObject peer, String message) {
        try {
            call(peer, "POST", "/api/home", new JSONObject().put("message", message).toString().getBytes(StandardCharsets.UTF_8),
                    "application/json", null, null, 8000);
        } catch (IOException | JSONException ignored) { /* feedback only; older versions ignore the message */ }
    }

    static JSONArray run(Context c, JSONArray ids, JSONObject what) throws JSONException {
        JSONArray all = peers(c), results = new JSONArray();
        JSONObject s = Config.toJson(c);
        String me = DashboardService.deviceName(c);
        // On-TV feedback (on unless turned off): open the target's home screen first, with a message before and after.
        boolean show = what.optBoolean("show", true);
        for (int i = 0; i < all.length(); i++) {
            JSONObject peer = all.getJSONObject(i);
            boolean chosen = false;
            for (int k = 0; k < ids.length(); k++) chosen |= ids.optString(k).equals(peer.optString("id"));
            if (!chosen) continue;
            JSONObject r = new JSONObject().put("name", peer.optString("name")).put("ok", true);
            JSONArray done = new JSONArray();
            boolean reached = true;
            try {
                secure(peer);   // move to HTTPS / record the certificate before any key is sent
            } catch (Unreachable e) {
                results.put(r.put("ok", false).put("error", explain(peer)).put("done", done));
                continue;
            } catch (IOException e) {
                results.put(r.put("ok", false).put("error", e.getMessage()).put("done", done));
                continue;
            }
            if (show) { notify(peer, "Syncing from " + me + "…"); try { Thread.sleep(2500); } catch (InterruptedException ignored) { } }
            try {
                if (what.optBoolean("settings")) {
                    JSONObject out = new JSONObject();
                    for (String k : SETTINGS) out.put(k, s.opt(k));
                    if (what.optBoolean("theme")) out.put(Config.THEME, s.optString(Config.THEME));
                    if (s.optString(Config.WEATHER_LAT).isEmpty()) { out.remove(Config.WEATHER_LAT); out.remove(Config.WEATHER_LON); out.remove(Config.WEATHER_PLACE); }
                    json(peer, "POST", "/api/settings", out);
                    done.put(what.optBoolean("theme") ? "settings + theme" : "settings");
                }
                if (what.optBoolean("calendars")) {
                    JSONArray cals = Config.calendars(c), send = new JSONArray();
                    for (int k = 0; k < cals.length(); k++) {
                        JSONObject cal = cals.getJSONObject(k);
                        send.put(new JSONObject().put("name", cal.optString("name")).put("url", cal.optString("url"))
                                .put("color", cal.optString("color")).put("on", cal.optBoolean("on", true)));
                    }
                    json(peer, "POST", "/api/calendars", new JSONObject().put("action", "replace").put("calendars", send));
                    done.put(send.length() + (send.length() == 1 ? " calendar" : " calendars"));
                }
                if (what.optBoolean("photos")) {
                    JSONObject theirs = new JSONObject();
                    JSONArray tp = json(peer, "GET", "/api/photos", null).optJSONArray("photos");
                    if (tp != null) for (int k = 0; k < tp.length(); k++) theirs.put(tp.getJSONObject(k).optString("name"), tp.getJSONObject(k));
                    int added = 0, retagged = 0;
                    File[] files = Config.photosDir(c).listFiles((d, n) -> Config.isImageName(n));
                    if (files != null) for (File f : files) {
                        Set<String> tags = Config.tagsFor(c, f.getName());
                        JSONObject t = theirs.optJSONObject(f.getName());
                        if (t == null) {
                            byte[] data = new byte[(int) f.length()];
                            try (FileInputStream in = new FileInputStream(f)) { int off = 0, n; while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n; }
                            call(peer, "POST", "/api/upload", data, "application/octet-stream", f.getName(), sortedTags(tags));
                            added++;
                        } else {
                            List<Object> theirTags = new ArrayList<>();
                            JSONArray ta = t.optJSONArray("tags");
                            if (ta != null) for (int k = 0; k < ta.length(); k++) theirTags.add(ta.optString(k));
                            if (!sortedTags(theirTags).equals(sortedTags(tags))) {
                                json(peer, "POST", "/api/photos/tags", new JSONObject().put("name", f.getName()).put("tags", new JSONArray(tags)));
                                retagged++;
                            }
                        }
                    }
                    done.put(added + (added == 1 ? " photo added" : " photos added") + (retagged > 0 ? ", " + retagged + " retagged" : ""));
                }
                peer.put("lastSync", System.currentTimeMillis());
            } catch (Unreachable e) {
                r.put("ok", false).put("error", explain(peer));
                reached = false;
            } catch (IOException | RuntimeException e) {
                r.put("ok", false).put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            }
            if (show && reached) {
                List<String> parts = new ArrayList<>();
                for (int k = 0; k < done.length(); k++) parts.add(done.optString(k));
                notify(peer, r.optBoolean("ok") ? "Synced from " + me + ": " + android.text.TextUtils.join(", ", parts) : "Sync from " + me + " failed");
            }
            results.put(r.put("done", done));
        }
        save(c, all);
        return results;
    }
}
