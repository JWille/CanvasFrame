package com.canvasframe.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The shared "site": what every CanvasFrame TV in the home knows about all of them. Same behaviour as webos/server/lib/site.js.
 *
 * One small JSON document, replicated on every TV, editable from any TV's dashboard (no master needed). A map of items,
 * each {d: data, t: time ms, by: device id, x: true if deleted}; merging keeps, per item, the newer (t, by).
 *   dev:<id>    identity of a TV (name, brand, platform, dashboard addresses). Written only by that TV.
 *   env:<id>    that TV's live state (screen, backlight, light level...). Written only by that TV, about every 30 s.
 *   place:<id>  where a TV is: room and coordinator priority. Written by any dashboard.
 *   room:<rid>  a room: name and the rooms next to it. Written by any dashboard.
 *   act:<id>    when someone last used that TV (remote, screen on, screen saver dismissed). Written only by that TV, pushed
 *               at once; TVs in the same room (strongly) and next-door rooms (gently) react to it.
 *   pics:<id>   the photo file names that TV has (for showing the same photo together). Written only by that TV.
 *   opt:saver   shared screen saver options: {sync: "off"|"all"|"room", min}. Written by any dashboard.
 *   auth:<aid>  a dashboard authenticator (TOTP secret). Only ever sent TV-to-TV over the API-key links: browsers never get
 *               the secret, and only items from a peer using an API key are accepted. Deleting one removes it everywhere.
 *   photo:<pid> a photo in the mesh ({name}; pid = sha1(name)[0:16]). Deleted (x, keeping {name}) -> every TV deletes it.
 *               A TV missing one gets it pushed by exactly one TV that has it and reaches it (lowest device id).
 *   tag:<pid>   a photo's tags. set:home: shared home-screen settings. set:cal: calendars (private links: TV-to-TV only).
 *               A TV's existing values enter with t = 1, so anything really changed in the mesh wins over them.
 *   cmd:<cid>   a short-lived command for some TVs (e.g. "identify"). Each TV runs it once; it expires after 2 minutes.
 *
 * Replication goes over the Sync peers (API key + pinned certificate): every 15 s and right after a local change, each
 * TV fetches every reachable peer's document, merges it and sends back its merged one. A TV that can't call out (one-way
 * network) is kept current by the TVs that can reach it.
 */
final class Site {
    private Site() { }

    interface Hooks {
        /** The mesh deleted this photo: delete it here (file, preview, tags). */
        void deletePhoto(String name);
        /** Take the mesh's home-screen settings / calendars. */
        void applySettings(JSONObject values);
        void applyCalendars(JSONArray calendars);
        /** This TV's live state (screen, backlight...), or null. Runs on the site thread. */
        JSONObject env() throws Exception;
        /** Runs a command meant for this TV. */
        void command(JSONObject cmd, JSONObject view) throws Exception;
    }

    private static final long CMD_TTL = 2 * 60_000L, TOMBSTONE_TTL = 7 * 86_400_000L, FRESH = 3 * 60_000L;
    private static final Pattern KEY = Pattern.compile("^(dev|env|act|pics|sig|scan|sensor|place|room|opt|auth|sso|photo|tag|set|cmd):[A-Za-z0-9_-]{1,40}$");
    private static final Pattern EDITABLE = Pattern.compile("^(place|room|opt|sensor):[A-Za-z0-9_-]{1,40}$");

    private static JSONObject items;                 // guarded by Site.class
    private static final Set<String> done = new HashSet<>();
    private static final Map<String, JSONObject> peerState = new HashMap<>();
    private static Hooks hooks;
    private static Context app;

    static synchronized String id(Context c) {
        SharedPreferences p = Config.prefs(c);
        String id = p.getString("device_id", "");
        if (!id.matches("[a-z0-9]{8,32}")) {
            byte[] b = new byte[8];
            new java.security.SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            id = sb.toString();
            p.edit().putString("device_id", id).commit();
        }
        return id;
    }

    private static File file(Context c) { return new File(c.getFilesDir(), "site.json"); }

    private static synchronized JSONObject items(Context c) {
        if (items == null) {
            try {
                byte[] b = java.nio.file.Files.readAllBytes(file(c).toPath());
                items = new JSONObject(new String(b, StandardCharsets.UTF_8)).optJSONObject("items");
            } catch (Exception e) { items = null; }
            if (items == null) items = new JSONObject();
        }
        return items;
    }

    private static synchronized void save(Context c) {
        try {
            File tmp = new File(c.getFilesDir(), "site.json.tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(new JSONObject().put("items", items(c)).toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file(c));
        } catch (IOException | JSONException ignored) { }
    }

    private static boolean newer(JSONObject a, JSONObject b) {
        if (b == null) return true;
        long ta = a.optLong("t"), tb = b.optLong("t");
        return ta > tb || (ta == tb && a.optString("by").compareTo(b.optString("by")) > 0);
    }

    private static synchronized void put(Context c, String key, JSONObject d, boolean del) throws JSONException {
        boolean keep = del && key.startsWith("photo:") && d != null;   // a deleted photo keeps its name
        JSONObject it = new JSONObject().put("d", (del && !keep) || d == null ? JSONObject.NULL : d).put("t", System.currentTimeMillis()).put("by", id(c));
        if (del) it.put("x", true);
        JSONObject cur = items(c).optJSONObject(key);
        if (cur != null && it.getLong("t") <= cur.optLong("t")) it.put("t", cur.optLong("t") + 1);   // a local edit always wins
        items(c).put(key, it);
        save(c);
    }

    /** Merge another TV's items in; owner-only items (dev:, env:) are never taken about this TV. */
    static boolean merge(Context c, JSONObject in, boolean trusted) throws JSONException {
        boolean[] auth = {false, false};
        boolean changed = mergeItems(c, in, trusted, auth);
        if (auth[0]) reconcileAuth(c);
        if (auth[1]) reconcileMesh(c);
        return changed;
    }

    /** trusted: from another TV using an API key; only then are "auth:" items (secrets) accepted. */
    private static synchronized boolean mergeItems(Context c, JSONObject in, boolean trusted, boolean[] auth) throws JSONException {
        if (in == null) return false;
        boolean changed = false;
        String me = id(c);
        for (Iterator<String> ks = in.keys(); ks.hasNext(); ) {
            String k = ks.next();
            JSONObject it = in.optJSONObject(k);
            if (it == null || !it.has("t") || !KEY.matcher(k).matches()) continue;
            if ((k.startsWith("auth:") || k.startsWith("sso:") || k.equals("set:cal") || k.equals("set:door")) && !trusted) continue;
            if ((k.startsWith("dev:") || k.startsWith("env:") || k.startsWith("act:") || k.startsWith("pics:") || k.startsWith("sig:") || k.startsWith("scan:"))
                    && k.substring(k.indexOf(':') + 1).equals(me)) continue;
            if (newer(it, items(c).optJSONObject(k))) {
                JSONObject id0 = it.optJSONObject("d");
                if ((k.startsWith("auth:") || k.startsWith("sso:")) && !it.optBoolean("x") && (id0 == null || id0.optString("secret").isEmpty())) continue;
                if (k.equals("set:cal") && !it.optBoolean("x") && (id0 == null || id0.optJSONArray("calendars") == null)) continue;
                if (k.equals("set:door") && !it.optBoolean("x") && (id0 == null || !id0.has("hook"))) continue;   // a stripped copy
                if (k.startsWith("auth:")) auth[0] = true;
                if (k.startsWith("photo:") || k.startsWith("tag:") || k.startsWith("set:")) auth[1] = true;
                JSONObject copy = new JSONObject().put("d", it.optBoolean("x") && !k.startsWith("photo:") ? JSONObject.NULL : it.opt("d"))
                        .put("t", it.optLong("t")).put("by", it.optString("by"));
                if (it.optBoolean("x")) copy.put("x", true);
                items(c).put(k, copy);
                changed = true;
            }
        }
        if (changed) save(c);
        return changed;
    }

    private static synchronized void prune(Context c) {
        long now = System.currentTimeMillis();
        List<String> drop = new ArrayList<>();
        for (Iterator<String> ks = items(c).keys(); ks.hasNext(); ) {
            String k = ks.next();
            JSONObject it = items(c).optJSONObject(k);
            if (it == null) continue;
            if ((k.startsWith("cmd:") && now - it.optLong("t") > CMD_TTL * 3) || (it.optBoolean("x") && now - it.optLong("t") > TOMBSTONE_TTL)) drop.add(k);
        }
        for (String k : drop) items(c).remove(k);
        if (!drop.isEmpty()) save(c);
    }

    /** The document for the dashboard and peers, plus which TV coordinates right now (lowest priority among TVs seen lately). */
    static JSONObject view(Context c) throws JSONException { return view(c, false); }

    /** withSecrets: only for another TV calling with an API key. Browsers get authenticators without their secret. */
    static synchronized JSONObject view(Context c, boolean withSecrets) throws JSONException {
        JSONObject v = viewAll(c);
        if (withSecrets) return v;
        JSONObject src = v.getJSONObject("items"), items = new JSONObject();
        for (Iterator<String> ks = src.keys(); ks.hasNext(); ) {
            String k = ks.next();
            JSONObject it = src.getJSONObject(k);
            if (k.startsWith("auth:")) {
                JSONObject d = it.optJSONObject("d"), copy = new JSONObject(it.toString());
                copy.put("d", d == null ? JSONObject.NULL : new JSONObject().put("name", d.optString("name")).put("added", d.optLong("added")));
                it = copy;
            }
            if (k.equals("set:door") && it.optJSONObject("d") != null) {   // doorbell: API key, stream tokens, webhook token are TV-to-TV only
                JSONObject d = it.optJSONObject("d");
                JSONObject bd = new JSONObject().put("console", d.optString("console"))
                        .put("apiKey", !d.optString("apiKey").isEmpty()).put("live", !d.optString("live").isEmpty()).put("pkg", !d.optString("pkg").isEmpty());
                JSONArray al = d.optJSONArray("alerts");   // the user's alerts, without their stream tokens
                if (al != null) {
                    JSONArray safe = new JSONArray();
                    for (int i = 0; i < al.length(); i++) {
                        JSONObject a = al.optJSONObject(i);
                        if (a != null) safe.put(new JSONObject().put("id", a.optString("id")).put("name", a.optString("name"))
                                .put("camera", a.optString("camera")).put("quiet", a.optBoolean("quiet")).put("stream", !a.optString("stream").isEmpty()));
                    }
                    bd.put("alerts", safe);
                }
                it = new JSONObject(it.toString()).put("d", bd);
            }
            if (k.startsWith("sso:")) {   // the mesh's sign-in pass secret: TV-to-TV only
                JSONObject d = it.optJSONObject("d");
                it = new JSONObject(it.toString()).put("d", d == null ? JSONObject.NULL : new JSONObject().put("created", d.optLong("created")));
            }
            if (k.equals("set:cal") && it.optJSONObject("d") != null) {
                JSONArray cl = it.optJSONObject("d").optJSONArray("calendars");
                it = new JSONObject(it.toString()).put("d", new JSONObject().put("count", cl == null ? 0 : cl.length()));
            }
            items.put(k, it);
        }
        return v.put("items", items);
    }

    private static synchronized JSONObject viewAll(Context c) throws JSONException {
        long now = System.currentTimeMillis();
        JSONObject all = items(c);
        String me = id(c), coord = null;
        int best = Integer.MAX_VALUE;
        List<String> devs = new ArrayList<>();
        for (Iterator<String> ks = all.keys(); ks.hasNext(); ) {
            String k = ks.next();
            JSONObject it = all.optJSONObject(k);
            if (k.startsWith("dev:") && it != null && !it.optBoolean("x")) devs.add(k.substring(4));
        }
        java.util.Collections.sort(devs);
        for (String d : devs) {
            JSONObject env = all.optJSONObject("env:" + d);
            JSONObject ed = env == null ? null : env.optJSONObject("d");
            boolean fresh = d.equals(me) || (ed != null && now - ed.optLong("at", env.optLong("t")) < FRESH);
            if (!fresh) continue;
            JSONObject pl = all.optJSONObject("place:" + d);
            JSONObject pd = pl == null ? null : pl.optJSONObject("d");
            // Priority 1-9 as set; unset = 50 (automatic, after any set ones); 99 = never coordinate.
            int prio = pd != null && pd.has("priority") && !pd.isNull("priority") ? pd.optInt("priority", 50) : 50;
            if (prio < 99 && prio < best) { best = prio; coord = d; }
        }
        JSONObject v = new JSONObject().put("self", me).put("coordinator", coord == null ? JSONObject.NULL : coord).put("items", all).put("now", now);
        Object[] plan = syncPlan(c);
        if (plan != null) {
            @SuppressWarnings("unchecked") List<String> names = (List<String>) plan[1];
            v.put("plan", new JSONObject().put("ms", plan[0]).put("names", new JSONArray(names)).put("fit", plan[2]).put("total", plan[3]));
        }
        return v;
    }

    /** The TVs this one reached in the last 2 minutes (published in env, so dashboards know who can copy to whom). */
    private static synchronized JSONArray links() {
        JSONArray out = new JSONArray();
        long now = System.currentTimeMillis();
        for (JSONObject st : peerState.values())
            if (st.optBoolean("ok") && !st.optString("dev").isEmpty() && now - st.optLong("at") < 120_000) out.put(st.optString("dev"));
        return out;
    }

    /** The Sync peer record id that reaches TV devId, or null. */
    static synchronized String peerFor(String devId) {
        for (Map.Entry<String, JSONObject> e : peerState.entrySet())
            if (devId.equals(e.getValue().optString("dev")) && e.getValue().optBoolean("ok")) return e.getKey();
        return null;
    }

    static synchronized JSONArray peerStates(Context c) throws JSONException {
        JSONArray ps = Sync.peers(c), out = new JSONArray();
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.getJSONObject(i), st = peerState.get(p.optString("id"));
            JSONObject o = new JSONObject().put("id", p.optString("id")).put("name", p.optString("name")).put("url", p.optString("url"));
            if (st != null) for (Iterator<String> ks = st.keys(); ks.hasNext(); ) { String k = ks.next(); o.put(k, st.get(k)); }
            out.put(o);
        }
        return out;
    }

    /** A change from this TV's dashboard: {set: {key: data}}, {del: [keys]}, {identify: target}. */
    static void edit(Context c, JSONObject body) throws JSONException {
        JSONObject set = body.optJSONObject("set");
        if (set != null) for (Iterator<String> ks = set.keys(); ks.hasNext(); ) {
            String k = ks.next();
            if (!EDITABLE.matcher(k).matches()) throw new IllegalArgumentException("That item can only be changed by its own TV");
            put(c, k, set.optJSONObject(k), false);
        }
        JSONArray del = body.optJSONArray("del");
        if (del != null) for (int i = 0; i < del.length(); i++) {
            String k = del.getString(i);
            if (!EDITABLE.matcher(k).matches()) throw new IllegalArgumentException("That item can only be changed by its own TV");
            put(c, k, null, true);
        }
        // "Find sensors" from a dashboard: every TV that can scan listens for a while; all TVs sync faster meanwhile.
        if (body.has("blescan")) {
            long until = System.currentTimeMillis() + Math.max(10, Math.min(120, body.optInt("blescan", 45))) * 1000L;
            String cid = Long.toString(System.currentTimeMillis(), 36) + Integer.toHexString(new java.util.Random().nextInt(0xffff));
            put(c, "cmd:" + cid, new JSONObject().put("action", "blescan").put("until", until).put("target", "all").put("from", id(c)), false);
            fast(until);
        }
        // Activity reported from outside (later Home Assistant motion; for now the API): this TV's room counts as in use.
        String act = body.optString("activity", "");
        if (!act.isEmpty()) {
            synchronized (Site.class) { lastAct = 0; }
            activity(c, act.length() > 40 ? act.substring(0, 40) : act);
        }
        // Fix an inconsistency: TV `from` copies the photos TV `to` is missing (run by `from`, over its own link).
        JSONObject copy = body.optJSONObject("copy");
        if (copy != null && copy.optString("from").matches("[a-z0-9]{8,32}") && copy.optString("to").matches("[a-z0-9]{8,32}")) {
            String cid = Long.toString(System.currentTimeMillis(), 36) + Integer.toHexString(new java.util.Random().nextInt(0xffff));
            put(c, "cmd:" + cid, new JSONObject().put("action", "copyphotos").put("to", copy.optString("to"))
                    .put("target", "dev:" + copy.optString("from")).put("from", id(c)), false);
        }
        // Doorbell (from the webhook on one TV): every TV shows it.
        JSONObject door = body.optJSONObject("doorbell");
        if (door != null) {
            String cid = Long.toString(System.currentTimeMillis(), 36) + Integer.toHexString(new java.util.Random().nextInt(0xffff));
            put(c, "cmd:" + cid, new JSONObject().put("action", "doorbell").put("kind", door.optString("kind", "ring"))
                    .put("event", door.optString("event")).put("target", "all").put("from", id(c)), false);
        }
        // "Sign out on all TVs": every TV ends the sessions of this sign-in chain.
        String chain = body.optString("signout", "");
        if (chain.matches("[a-f0-9]{8,64}")) {
            String cid = Long.toString(System.currentTimeMillis(), 36) + Integer.toHexString(new java.util.Random().nextInt(0xffff));
            put(c, "cmd:" + cid, new JSONObject().put("action", "signout").put("chain", chain).put("target", "all").put("from", id(c)), false);
        }
        String target = body.optString("identify", "");
        if (!target.isEmpty()) {
            String cid = Long.toString(System.currentTimeMillis(), 36) + Integer.toHexString(new java.util.Random().nextInt(0xffff));
            put(c, "cmd:" + cid, new JSONObject().put("action", "identify").put("target", target).put("from", id(c)), false);
        }
    }

    // ---------------------------------------------------------------- this TV's own items

    private static long lastEnv;

    private static void refreshSelf(Context c, JSONObject identity) throws JSONException {
        String me = id(c);
        JSONObject cur;
        synchronized (Site.class) { cur = items(c).optJSONObject("dev:" + me); }
        if (cur == null || !String.valueOf(cur.opt("d")).equals(identity.toString())) put(c, "dev:" + me, identity, false);
        // The uploaded photos' names, for showing the same photo together.
        List<String> names = new ArrayList<>();
        java.io.File[] fs = Config.photosDir(c).listFiles((d, n) -> Config.isImageName(n));
        if (fs != null) for (java.io.File f : fs) names.add(f.getName());
        java.util.Collections.sort(names);
        JSONObject pics;
        synchronized (Site.class) { pics = items(c).optJSONObject("pics:" + me); }
        JSONObject pd = pics == null ? null : pics.optJSONObject("d");
        if (pd == null || !String.valueOf(pd.optJSONArray("names")).equals(new JSONArray(names).toString()))
            put(c, "pics:" + me, new JSONObject().put("names", new JSONArray(names)), false);
        if (System.currentTimeMillis() - lastEnv < 25_000) return;   // state every ~30 s; replication runs every 15 s
        lastEnv = System.currentTimeMillis();
        try {
            JSONObject e = hooks.env();
            if (e != null) put(c, "env:" + me, e.put("at", System.currentTimeMillis()).put("links", links()), false);
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- commands

    static void commands(Context c) {
        if (hooks == null) return;
        long now = System.currentTimeMillis();
        List<JSONObject> run = new ArrayList<>();
        JSONObject view;
        try {
            synchronized (Site.class) {
                String me = id(c);
                JSONObject pl = items(c).optJSONObject("place:" + me);
                String room = pl != null && pl.optJSONObject("d") != null ? pl.optJSONObject("d").optString("room") : "";
                for (Iterator<String> ks = items(c).keys(); ks.hasNext(); ) {
                    String k = ks.next();
                    if (!k.startsWith("cmd:") || done.contains(k)) continue;
                    done.add(k);
                    JSONObject it = items(c).optJSONObject(k);
                    JSONObject d = it == null ? null : it.optJSONObject("d");
                    if (d == null || it.optBoolean("x") || now - it.optLong("t") > CMD_TTL) continue;
                    String tgt = d.optString("target", "all");
                    if (tgt.equals("all") || tgt.equals("dev:" + me) || (!room.isEmpty() && tgt.equals("room:" + room))) run.add(d);
                }
                view = view(c);
            }
            for (JSONObject d : run) {
                try { hooks.command(d, view); } catch (Exception ignored) { }
            }
        } catch (JSONException ignored) { }
    }

    // ---------------------------------------------------------------- replication

    private static final Object roundLock = new Object();

    /** One replication round with every peer, then run commands. */
    static void round(Context c) {
        synchronized (roundLock) {
            JSONArray ps = Sync.peers(c);
            boolean peersChanged = false;
            for (int i = 0; i < ps.length(); i++) {
                JSONObject peer = ps.optJSONObject(i);
                if (peer == null) continue;
                JSONObject st = new JSONObject();
                try {
                    if (Sync.secure(peer)) peersChanged = true;
                    JSONObject theirs = Sync.call(peer, "GET", "/api/site", null, null, null, null, 8000);
                    if (!theirs.optString("self").isEmpty()) st.put("dev", theirs.optString("self"));   // which TV this link reaches
                    if (merge(c, theirs.optJSONObject("items"), true)) ArtDream.nudge();
                    JSONObject mine;
                    synchronized (Site.class) { mine = new JSONObject(items(c).toString()); }
                    Sync.call(peer, "POST", "/api/site", new JSONObject().put("merge", mine).toString().getBytes(StandardCharsets.UTF_8),
                            "application/json", null, null, 8000);
                    st.put("ok", true).put("at", System.currentTimeMillis()).put("error", "");
                    synchronized (Site.class) { peerState.put(peer.optString("id"), st); }
                    try { pushPhotos(c, peer, st.optString("dev")); } catch (Exception ignored) { }
                } catch (Exception e) {
                    String m = e.getMessage() == null ? e.toString() : e.getMessage();
                    try {
                        st.put("ok", false).put("at", System.currentTimeMillis())
                                .put("error", m.matches("(?i).*(404|not found).*") ? "older CanvasFrame (no shared setup)" : m);
                    } catch (JSONException ignored) { }
                }
                synchronized (Site.class) { peerState.put(peer.optString("id"), st); }
            }
            if (peersChanged) Sync.save(c, ps);
            commands(c);
        }
    }

    // ---------------------------------------------------------------- the mesh: photos, tags, home-screen settings, calendars

    static final String[] MESH_SETTINGS = {Config.WEATHER_ON, Config.WEATHER_PLACE, Config.WEATHER_LAT, Config.WEATHER_LON,
            Config.UNITS, Config.CLOCK, Config.SHOW_DASH, Config.HIDE_NO_SIGNAL, Config.AUTO_HIDE, Config.CONTROLS_ALWAYS,
            Config.PHOTO_MIN, Config.THEME};

    static String pid(String name) { return Auth.sha256Sha1(name); }

    private static List<String> localPhotos(Context c) {
        List<String> out = new ArrayList<>();
        java.io.File[] fs = Config.photosDir(c).listFiles((d, n) -> Config.isImageName(n));
        if (fs != null) for (java.io.File f : fs) out.add(f.getName());
        java.util.Collections.sort(out);
        return out;
    }

    static JSONObject meshSettings(Context c) throws JSONException {
        JSONObject all = Config.toJson(c), o = new JSONObject();
        for (String k : MESH_SETTINGS) o.put(k, all.opt(k));
        return o;
    }

    private static JSONArray calNorm(JSONArray in) throws JSONException {
        JSONArray out = new JSONArray();
        if (in != null) for (int i = 0; i < in.length(); i++) {
            JSONObject x = in.getJSONObject(i);
            out.put(new JSONObject().put("name", x.optString("name")).put("url", x.optString("url")).put("color", x.optString("color"))
                    .put("on", x.optBoolean("on", true)));
        }
        return out;
    }

    private static String calSig(JSONArray l) throws JSONException {
        StringBuilder sb = new StringBuilder();
        JSONArray n = calNorm(l);
        for (int i = 0; i < n.length(); i++) {
            JSONObject x = n.getJSONObject(i);
            sb.append(x.optString("name")).append('|').append(x.optString("url")).append('|').append(x.optString("color")).append('|').append(x.optBoolean("on")).append('\n');
        }
        return sb.toString();
    }

    /** Settings from another platform may differ in key order and number/string form: compare value by value. */
    private static boolean sameValues(JSONObject a, JSONObject b) {
        for (Iterator<String> ks = a.keys(); ks.hasNext(); ) {
            String k = ks.next();
            if (!String.valueOf(a.opt(k)).equals(String.valueOf(b.opt(k)))) return false;
        }
        return true;
    }

    private static synchronized void putOld(Context c, String key, JSONObject d) throws JSONException {
        if (items(c).has(key)) return;
        items(c).put(key, new JSONObject().put("d", d).put("t", 1).put("by", id(c)));   // oldest possible: the mesh's values win
        save(c);
    }

    private static Set<String> tagSet(JSONArray a) {
        Set<String> s = new HashSet<>();
        if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
        return s;
    }

    /** Make this TV match the mesh (delete photos deleted elsewhere, take tags/settings/calendars) and publish what's new here. */
    static void reconcileMesh(Context c) {
        Hooks h = hooks;
        if (h == null) return;
        try {
            List<String> local = localPhotos(c);
            List<String> del = new ArrayList<>();
            synchronized (Site.class) {
                for (Iterator<String> ks = items(c).keys(); ks.hasNext(); ) {
                    String k = ks.next();
                    JSONObject it = items(c).optJSONObject(k), d = it == null ? null : it.optJSONObject("d");
                    if (k.startsWith("photo:") && it.optBoolean("x") && d != null && local.contains(d.optString("name"))) del.add(d.optString("name"));
                }
            }
            for (String n : del) h.deletePhoto(n);
            local = localPhotos(c);
            for (String n : local) {
                JSONObject cur;
                synchronized (Site.class) { cur = items(c).optJSONObject("photo:" + pid(n)); }
                if (cur == null || cur.optBoolean("x")) put(c, "photo:" + pid(n), new JSONObject().put("name", n), false);
                JSONObject t;
                synchronized (Site.class) { t = data(items(c), "tag:" + pid(n)); }
                Set<String> mine = Config.tagsFor(c, n);
                if (t != null && n.equals(t.optString("name"))) {
                    if (!tagSet(t.optJSONArray("tags")).equals(mine)) Config.setTags(c, n, tagSet(t.optJSONArray("tags")));
                } else if (t == null && !mine.isEmpty()) {
                    boolean had;
                    synchronized (Site.class) { had = items(c).has("tag:" + pid(n)); }
                    if (!had) putOld(c, "tag:" + pid(n), new JSONObject().put("name", n).put("tags", new JSONArray(mine)));
                }
            }
            JSONObject hs, cal;
            synchronized (Site.class) { hs = data(items(c), "set:home"); cal = data(items(c), "set:cal"); }
            if (hs != null && hs.optJSONObject("values") != null) { if (!sameValues(hs.getJSONObject("values"), meshSettings(c))) h.applySettings(hs.getJSONObject("values")); }
            else putOld(c, "set:home", new JSONObject().put("values", meshSettings(c)));
            if (cal != null && cal.optJSONArray("calendars") != null) { if (!calSig(cal.getJSONArray("calendars")).equals(calSig(Config.calendars(c)))) h.applyCalendars(cal.getJSONArray("calendars")); }
            else putOld(c, "set:cal", new JSONObject().put("calendars", calNorm(Config.calendars(c))));
        } catch (Exception ignored) { }
    }

    private static final Map<String, Map<String, Long>> sent = new HashMap<>();   // peer dev -> {name: time}

    /** Push photos the peer lacks, when this TV is the one responsible for them (lowest id among holders reaching it). */
    private static void pushPhotos(Context c, JSONObject peer, String peerDev) throws Exception {
        if (peerDev == null || peerDev.isEmpty() || hooks == null) return;
        String me = id(c);
        List<String> todo = new ArrayList<>();
        synchronized (Site.class) {
            JSONObject all = items(c), pdev = data(all, "dev:" + peerDev), penv = data(all, "env:" + peerDev), ppics = data(all, "pics:" + peerDev);
            if (pdev == null || !pdev.optBoolean("photosIn", true) || ppics == null) return;
            if (penv != null && penv.has("free") && penv.optLong("free") < 250L * 1048576) return;
            Set<String> theirs = tagSet(ppics.optJSONArray("names"));
            Map<String, Long> recent = sent.get(peerDev);
            if (recent == null) { recent = new HashMap<>(); sent.put(peerDev, recent); }
            for (String n : localPhotos(c)) {
                Long when = recent.get(n);
                if (theirs.contains(n) || (when != null && System.currentTimeMillis() - when < 600_000)) continue;
                JSONObject it = all.optJSONObject("photo:" + pid(n));
                if (it == null || it.optBoolean("x")) continue;
                String first = me;   // lowest id among the TVs that have it and reach the peer (this TV does)
                for (Iterator<String> ks = all.keys(); ks.hasNext(); ) {
                    String k = ks.next();
                    if (!k.startsWith("pics:")) continue;
                    String d = k.substring(5);
                    JSONObject pics = data(all, k), env = data(all, "env:" + d);
                    if (pics == null || !tagSet(pics.optJSONArray("names")).contains(n)) continue;
                    JSONArray links = env == null ? null : env.optJSONArray("links");
                    boolean reaches = d.equals(me);
                    if (links != null) for (int i = 0; i < links.length(); i++) if (peerDev.equals(links.optString(i))) reaches = true;
                    if (reaches && d.compareTo(first) < 0) first = d;
                }
                if (!first.equals(me)) continue;
                todo.add(n);
                if (todo.size() >= 4) break;
            }
        }
        for (String n : todo) {
            java.io.File f = new java.io.File(Config.photosDir(c), n);
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            List<String> tl = new ArrayList<>(Config.tagsFor(c, n));
            Sync.call(peer, "POST", "/api/upload", bytes, "application/octet-stream", n, android.text.TextUtils.join(",", tl), 60_000);
            synchronized (Site.class) { sent.get(peerDev).put(n, System.currentTimeMillis()); }
        }
    }

    /** Mesh edits made on this TV: share them now. */
    static void photoAdded(Context c, String name) {
        try {
            put(c, "photo:" + pid(name), new JSONObject().put("name", name), false);
            Set<String> t = Config.tagsFor(c, name);
            if (!t.isEmpty()) put(c, "tag:" + pid(name), new JSONObject().put("name", name).put("tags", new JSONArray(t)), false);
        } catch (JSONException ignored) { }
        now(c);
    }

    static void photoDeleted(Context c, String name) {
        try {
            put(c, "photo:" + pid(name), new JSONObject().put("name", name), true);
            put(c, "tag:" + pid(name), null, true);
        } catch (JSONException ignored) { }
        now(c);
    }

    static void tagsChanged(Context c, String name) {
        try { put(c, "tag:" + pid(name), new JSONObject().put("name", name).put("tags", new JSONArray(Config.tagsFor(c, name))), false); }
        catch (JSONException ignored) { }
        now(c);
    }

    static void settingsChanged(Context c) {
        try { put(c, "set:home", new JSONObject().put("values", meshSettings(c)), false); } catch (JSONException ignored) { }
        now(c);
    }

    static void calendarsChanged(Context c) {
        try { put(c, "set:cal", new JSONObject().put("calendars", calNorm(Config.calendars(c))), false); } catch (JSONException ignored) { }
        now(c);
    }

    // ---------------------------------------------------------------- shared authenticators

    /** Make this TV's authenticators match the site: add shared ones, drop deleted ones, publish local ones. */
    static void reconcileAuth(Context c) {
        try {
            JSONArray local = Auth.syncList(c);
            Map<String, JSONObject> mine = new HashMap<>();
            for (int i = 0; i < local.length(); i++) mine.put(local.getJSONObject(i).getString("id"), local.getJSONObject(i));
            List<String[]> add = new ArrayList<>();
            List<String> remove = new ArrayList<>();
            List<JSONObject> publish = new ArrayList<>();
            synchronized (Site.class) {
                for (Iterator<String> ks = items(c).keys(); ks.hasNext(); ) {
                    String k = ks.next();
                    if (!k.startsWith("auth:")) continue;
                    String aid = k.substring(5);
                    JSONObject it = items(c).getJSONObject(k), d = it.optJSONObject("d");
                    if (it.optBoolean("x")) { if (mine.containsKey(aid)) remove.add(aid); }
                    else if (!mine.containsKey(aid) && d != null && !d.optString("secret").isEmpty())
                        add.add(new String[]{aid, d.optString("name"), d.optString("secret"), String.valueOf(d.optLong("added"))});
                }
                for (Map.Entry<String, JSONObject> e : mine.entrySet()) if (!items(c).has("auth:" + e.getKey())) publish.add(e.getValue());
            }
            for (String aid : remove) Auth.remove(c, aid);
            for (String[] a : add) Auth.addSynced(c, a[0], a[1], a[2], Long.parseLong(a[3]));
            for (JSONObject a : publish)
                put(c, "auth:" + a.getString("id"), new JSONObject().put("name", a.optString("name")).put("secret", a.optString("secret"))
                        .put("added", a.optLong("added")), false);
        } catch (JSONException ignored) { }
    }

    /** An authenticator was added on this TV: share it now. */
    static void authChanged(Context c) { reconcileAuth(c); now(c); }

    /** An authenticator was removed on this TV: remove it on every TV. */
    static void authRemoved(Context c, String aid) {
        if (aid == null || !aid.matches("[a-f0-9]{6,40}")) return;
        try { put(c, "auth:" + aid, null, true); } catch (JSONException ignored) { }
        now(c);
    }

    /** The shared doorbell setup (set:door, with its secrets) or null. */
    static JSONObject door(Context c) {
        synchronized (Site.class) {
            JSONObject it = items(c).optJSONObject("set:door");
            return it == null || it.optBoolean("x") ? null : it.optJSONObject("d");
        }
    }

    static void setDoor(Context c, JSONObject d) throws JSONException {
        if (d == null) put(c, "set:door", null, true); else put(c, "set:door", d, false);
        now(c);
    }

    /** The mesh secret for sign-in passes (made here if no TV has one yet; sign-in must be on). null while sign-in is off. */
    static String ssoSecret(Context c) {
        if (!Auth.enabled(c)) return null;
        synchronized (Site.class) {
            JSONObject it = items(c).optJSONObject("sso:key");
            JSONObject d = it == null || it.optBoolean("x") ? null : it.optJSONObject("d");
            if (d != null && !d.optString("secret").isEmpty()) return d.optString("secret");
        }
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        try { put(c, "sso:key", new JSONObject().put("secret", sb.toString()).put("created", System.currentTimeMillis()), false); }
        catch (JSONException e) { return null; }
        now(c);
        return sb.toString();
    }

    /** Sign-in was reset on this TV (lock-out recovery): remove every shared authenticator everywhere, or the others would hand them back. */
    static void authReset(Context c) {
        List<String> keys = new ArrayList<>();
        synchronized (Site.class) {
            for (Iterator<String> ks = items(c).keys(); ks.hasNext(); ) {
                String k = ks.next();
                JSONObject it = items(c).optJSONObject(k);
                if ((k.startsWith("auth:") || k.startsWith("sso:")) && it != null && !it.optBoolean("x")) keys.add(k);
            }
        }
        for (String k : keys) try { put(c, k, null, true); } catch (JSONException ignored) { }
        now(c);
    }

    private static long lastAct;

    /** Someone used this TV: record it and tell the others now (at most every 20 s). */
    static void activity(Context c, String kind) {
        long now = System.currentTimeMillis();
        synchronized (Site.class) {
            if (now - lastAct < 20_000) return;
            lastAct = now;
        }
        try { put(c, "act:" + id(c), new JSONObject().put("at", now).put("kind", kind), false); } catch (JSONException ignored) { }
        now(c);
    }

    /**
     * Other TVs sharing a light-sensor reading (env.roomLight {lum, pct}: an LG whose sensor runs), for "Dim by room light":
     * [{id, name, pct, at, fresh (state < 3 min old), sameRoom}].
     */
    static synchronized JSONArray lightSources(Context c) throws JSONException {
        JSONArray out = new JSONArray();
        JSONObject all = items(c);
        String me = id(c);
        String mine = data(all, "place:" + me) == null ? "" : data(all, "place:" + me).optString("room");
        long now = System.currentTimeMillis();
        for (Iterator<String> ks = all.keys(); ks.hasNext(); ) {
            String k = ks.next();
            if (!k.startsWith("env:") || k.substring(4).equals(me)) continue;
            String id = k.substring(4);
            JSONObject e = data(all, k), dev = data(all, "dev:" + id), pl = data(all, "place:" + id);
            if (e == null || dev == null || !e.has("roomLight")) continue;
            JSONObject rl = e.optJSONObject("roomLight");
            String room = pl == null ? "" : pl.optString("room");
            long at = e.optLong("at");
            out.put(new JSONObject().put("id", id).put("name", dev.optString("name", "TV"))
                    .put("pct", rl == null ? 0 : rl.optInt("pct")).put("at", at)
                    .put("fresh", rl != null && now - at < 180_000).put("sameRoom", !mine.isEmpty() && mine.equals(room)));
        }
        return out;
    }

    /**
     * Latest activity near this TV: {same, next} = newest act time in this TV's room and in rooms next to it (0 if none).
     * Other TVs only; this TV's own use ends its own screen saver anyway.
     */
    static synchronized long[] nearby(Context c) {
        long[] out = {0, 0};
        JSONObject all = items(c);
        String me = id(c);
        String mine = data(all, "place:" + me) == null ? "" : data(all, "place:" + me).optString("room");
        if (mine.isEmpty()) return out;
        JSONObject room = data(all, "room:" + mine);
        JSONArray next = room == null ? null : room.optJSONArray("next");
        for (Iterator<String> ks = all.keys(); ks.hasNext(); ) {
            String k = ks.next();
            if (!k.startsWith("act:") || k.substring(4).equals(me)) continue;
            JSONObject a = data(all, k), pl = data(all, "place:" + k.substring(4));
            String r = pl == null ? "" : pl.optString("room");
            if (a == null || r.isEmpty()) continue;
            long at = a.optLong("at");
            if (r.equals(mine)) out[0] = Math.max(out[0], at);
            else if (next != null) for (int i = 0; i < next.length(); i++) if (r.equals(next.optString(i))) out[1] = Math.max(out[1], at);
        }
        return out;
    }

    /**
     * Showing the same photo together: null when off, else {ms, names} = the change interval and the sorted photo names every
     * TV in the group has (all TVs, or with "room" those in this TV's room); screen savers and home-screen backgrounds both
     * use it, so a TV that can't run CanvasFrame's screen saver (webOS 10) still matches on its home screen. Every TV
     * computes the same from the shared document and picks with samePhoto(), so no TV coordinates. Same as lib/site.js.
     */
    static synchronized Object[] syncPlan(Context c) {
        JSONObject all = items(c);
        String me = id(c);
        JSONObject opt = data(all, "opt:saver"), myDev = data(all, "dev:" + me);
        String mode = opt == null ? "off" : opt.optString("sync", "off");
        if (mode.equals("off") || myDev == null || data(all, "pics:" + me) == null) return null;
        String room = data(all, "place:" + me) == null ? "" : data(all, "place:" + me).optString("room");
        if (mode.equals("room") && room.isEmpty()) return null;
        List<String> pool = null;
        for (Iterator<String> ks = all.keys(); ks.hasNext(); ) {
            String k = ks.next();
            if (!k.startsWith("dev:")) continue;
            String d = k.substring(4);
            JSONObject dev = data(all, k), pics = data(all, "pics:" + d);
            if (dev == null || pics == null) continue;
            if (mode.equals("room")) {
                JSONObject pl = data(all, "place:" + d);
                if (pl == null || !room.equals(pl.optString("room"))) continue;
            }
            JSONArray n = pics.optJSONArray("names");
            Set<String> set = new HashSet<>();
            if (n != null) for (int i = 0; i < n.length(); i++) set.add(n.optString(i));
            if (pool == null) pool = new ArrayList<>(set); else pool.retainAll(set);
        }
        if (pool == null || pool.size() < 2) return null;
        java.util.Collections.sort(pool);
        // Narrow to photos whose (shared) tags fit now, holiday ones first; all of them if fewer than two fit.
        List<String> fit = new ArrayList<>(), hol = new ArrayList<>();
        for (String n : pool) {
            JSONObject t = data(all, "tag:" + pid(n));
            Set<String> tags = new HashSet<>();
            if (t != null && n.equals(t.optString("name"))) { JSONArray ta = t.optJSONArray("tags"); if (ta != null) for (int i = 0; i < ta.length(); i++) tags.add(ta.optString(i)); }
            else tags = Config.tagsFor(c, n);
            if (!Config.fitsNow(c, tags)) continue;
            fit.add(n);
            if (Config.isHolidayPick(tags)) hol.add(n);
        }
        if (hol.size() >= 2) fit = hol;
        int min = Math.max(1, Math.min(240, opt.optInt("min", 10)));
        return new Object[]{min * 60_000L, fit.size() >= 2 ? fit : pool, fit.size(), pool.size()};
    }

    /** The photo for time slot `slot` out of n (same function in lib/site.js and the webOS screen saver QML). */
    private static long hash32(long x) {
        int h = (int) x;
        h = (h ^ (h >>> 16)) * 0x45d9f3b;
        h = (h ^ (h >>> 16)) * 0x45d9f3b;
        return Integer.toUnsignedLong(h ^ (h >>> 16));
    }

    static int samePhoto(long slot, int n) {
        int i = (int) (hash32(slot) % n);
        if (n > 1 && i == (int) (hash32(slot - 1) % n)) i = (i + 1) % n;   // never the same photo twice in a row
        return i;
    }

    private static JSONObject data(JSONObject all, String k) {
        JSONObject it = all.optJSONObject(k);
        return it == null || it.optBoolean("x") ? null : it.optJSONObject("d");
    }

    private static final java.util.concurrent.ExecutorService NOW = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** After a local edit: replicate right away (in the background). */
    static void now(Context c) { NOW.execute(() -> round(c)); }

    static volatile long fastUntil;

    /** Sync every few seconds until `until` (a sensor search: other TVs' scan results arrive quickly). */
    static void fast(long until) { if (until > fastUntil) fastUntil = until; }

    /** Called every 3 s: a round while a sensor search runs. */
    static void fastTick(Context c) { if (System.currentTimeMillis() < fastUntil) round(c); }

    /** This TV's signals (Signals.java): sig:<id> {list:[...]}, only written when they changed; push = tell the others now. */
    static void signals(Context c, JSONArray list, boolean push) {
        try {
            JSONObject d = new JSONObject().put("list", list);
            JSONObject cur;
            synchronized (Site.class) { cur = items(c).optJSONObject("sig:" + id(c)); }
            if (cur != null && String.valueOf(cur.opt("d")).equals(d.toString())) return;
            put(c, "sig:" + id(c), d, false);
            if (push) now(c);
        } catch (JSONException ignored) { }
    }

    /** Every 15 s from DashboardService. */
    static void tick(Context c, JSONObject identity, Hooks h) {
        hooks = h;
        app = c.getApplicationContext();
        try {
            prune(c);
            reconcileAuth(c);
            reconcileMesh(c);
            refreshSelf(c, identity);
        } catch (Exception ignored) { }
        round(c);
    }
}
