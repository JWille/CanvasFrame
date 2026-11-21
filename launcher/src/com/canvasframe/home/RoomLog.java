package com.canvasframe.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Room history for the dashboards (Rooms card: presence timeline + climate charts), built from the shared site, so this
 * TV's dashboard shows it even though the Hisense has no climate sensor: every 30 s it reads all TVs' act:<id> and sig:<id>;
 * presence changes become events, climate readings a point per sensor every 5 minutes; kept 48 h in files/roomlog.json.
 * Same logic as webos/server/lib/roomlog.js. GET /api/rooms.
 */
final class RoomLog {
    private RoomLog() { }

    private static final long KEEP = 48 * 3600_000L, STEP = 5 * 60_000L, FRESH = 10 * 60_000L;
    private static JSONObject st;
    private static final Map<String, JSONObject> cur = new HashMap<>();              // key -> freshest reading (+ dev)
    private static final Map<String, Map<String, long[]>> heard = new HashMap<>();   // key -> dev -> {rssi, at}
    private static long lastSave;

    private static File file(Context c) { return new File(c.getFilesDir(), "roomlog.json"); }

    private static void load(Context c) throws JSONException {
        if (st != null) return;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(file(c).toPath());
            st = new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) { st = new JSONObject(); }
        if (!st.has("ev")) st.put("ev", new JSONArray());
        if (!st.has("clim")) st.put("clim", new JSONObject());
        if (!st.has("last")) st.put("last", new JSONObject().put("acts", new JSONObject()).put("pres", new JSONObject()));
    }

    static synchronized void tick(Context c) {
        try {
            load(c);
            JSONObject items = Site.view(c).getJSONObject("items");
            long now = System.currentTimeMillis();
            JSONArray ev = st.getJSONArray("ev");
            JSONObject acts = st.getJSONObject("last").getJSONObject("acts"), pres = st.getJSONObject("last").getJSONObject("pres");
            for (Iterator<String> ks = items.keys(); ks.hasNext(); ) {
                String k = ks.next();
                JSONObject it = items.optJSONObject(k);
                if (it == null || it.optBoolean("x") || it.optJSONObject("d") == null) continue;
                JSONObject d = it.getJSONObject("d");
                String dev = k.substring(k.indexOf(':') + 1);
                if (k.startsWith("act:")) {
                    long at = d.optLong("at");
                    if (at > acts.optLong(dev)) {
                        if (now - at < KEEP) ev.put(new JSONArray().put(at).put("act/" + dev).put("a").put(d.optString("kind")).put(dev));
                        acts.put(dev, at);
                    }
                } else if (k.startsWith("sig:")) {
                    JSONArray list = d.optJSONArray("list");
                    for (int i = 0; list != null && i < list.length(); i++) {
                        JSONObject s = list.getJSONObject(i);
                        String key = s.optString("key");
                        if (s.has("presence")) {
                            String pk = dev + "|" + key;
                            if (!s.optString("presence").equals(pres.optString(pk))) {
                                ev.put(new JSONArray().put(s.optLong("since", s.optLong("at", now))).put(key).put("p").put(s.optString("presence")).put(dev));
                                pres.put(pk, s.optString("presence"));
                            }
                        }
                        if (s.has("t") || s.has("h") || s.has("light") || s.has("co2")) {
                            if (s.has("rssi")) {
                                Map<String, long[]> h = heard.get(key);
                                if (h == null) heard.put(key, h = new HashMap<>());
                                h.put(dev, new long[]{s.optLong("rssi"), s.optLong("at", now)});
                            }
                            JSONObject prev = cur.get(key);
                            if (prev == null || s.optLong("at") > prev.optLong("at"))
                                cur.put(key, new JSONObject(s.toString()).put("dev", dev).put("at", s.optLong("at", now)));
                        }
                    }
                }
            }
            long bucket = now / STEP * STEP;
            JSONObject clim = st.getJSONObject("clim");
            for (Map.Entry<String, JSONObject> e : cur.entrySet()) {
                JSONObject r = e.getValue();
                if (now - r.optLong("at") > FRESH) continue;
                JSONObject s = clim.optJSONObject(e.getKey());
                if (s == null) clim.put(e.getKey(), s = new JSONObject().put("hist", new JSONArray()));
                s.put("name", r.optString("name")).put("kind", r.optString("kind")).put("src", r.optString("src"));
                JSONArray hist = s.getJSONArray("hist");
                JSONArray last = hist.length() > 0 ? hist.getJSONArray(hist.length() - 1) : null;
                if (last == null || last.optLong(0) < bucket)
                    hist.put(new JSONArray().put(bucket).put(num(r, "t")).put(num(r, "h")).put(num(r, "light")).put(num(r, "co2")));
            }
            // Prune: 48 h, sorted, at most 4000 events.
            List<JSONArray> evs = new ArrayList<>();
            for (int i = 0; i < ev.length(); i++) if (now - ev.getJSONArray(i).optLong(0) < KEEP) evs.add(ev.getJSONArray(i));
            evs.sort((a, b) -> Long.compare(a.optLong(0), b.optLong(0)));
            JSONArray kept = new JSONArray();
            for (int i = Math.max(0, evs.size() - 4000); i < evs.size(); i++) kept.put(evs.get(i));
            st.put("ev", kept);
            for (Iterator<String> ks = clim.keys(); ks.hasNext(); ) {
                String key = ks.next();
                JSONArray hist = clim.getJSONObject(key).getJSONArray("hist"), keep = new JSONArray();
                for (int i = 0; i < hist.length(); i++) if (now - hist.getJSONArray(i).optLong(0) < KEEP) keep.put(hist.getJSONArray(i));
                if (keep.length() == 0) ks.remove(); else clim.getJSONObject(key).put("hist", keep);
            }
            if (now - lastSave > 5 * 60_000L) {
                lastSave = now;
                java.nio.file.Files.write(file(c).toPath(), st.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) { android.util.Log.e("CanvasFrame", "roomlog: " + e); }
    }

    private static Object num(JSONObject r, String k) { return r.has(k) && !r.isNull(k) ? r.opt(k) : JSONObject.NULL; }

    private static String bestDev(String key) {
        Map<String, long[]> h = heard.get(key);
        long now = System.currentTimeMillis();
        String best = null;
        long bestR = Long.MIN_VALUE;
        if (h != null) for (Map.Entry<String, long[]> e : h.entrySet())
            if (now - e.getValue()[1] < FRESH && e.getValue()[0] > bestR) { best = e.getKey(); bestR = e.getValue()[0]; }
        JSONObject r = cur.get(key);
        return best != null ? best : r == null ? null : r.optString("dev", null);
    }

    static synchronized JSONObject get(Context c) throws JSONException {
        load(c);
        long now = System.currentTimeMillis();
        JSONArray climate = new JSONArray();
        JSONObject clim = st.getJSONObject("clim");
        // Bluetooth sensors count only while added (sensor:<id>); a removed one's history goes.
        JSONObject items = Site.view(c).getJSONObject("items");
        for (Iterator<String> ks = clim.keys(); ks.hasNext(); ) {
            String key = ks.next();
            if (!key.startsWith("switchbot/")) continue;
            JSONObject it = items.optJSONObject("sensor:" + key.substring(10));
            if (it == null || it.optBoolean("x")) { ks.remove(); cur.remove(key); }
        }
        for (Iterator<String> ks = clim.keys(); ks.hasNext(); ) {
            String key = ks.next();
            JSONObject s = clim.getJSONObject(key), r = cur.get(key);
            JSONObject o = new JSONObject().put("key", key).put("name", s.optString("name", key)).put("kind", s.optString("kind"))
                    .put("src", s.optString("src")).put("dev", bestDev(key) == null ? JSONObject.NULL : bestDev(key))
                    .put("hist", s.getJSONArray("hist"));
            if (r != null) {
                for (String k : new String[]{"t", "h", "light", "co2", "bat", "room", "where"}) if (r.has(k)) o.put(k, r.get(k));
                o.put("at", r.optLong("at")).put("fresh", now - r.optLong("at") < FRESH);
            } else o.put("fresh", false);
            climate.put(o);
        }
        return new JSONObject().put("now", now).put("keep", KEEP).put("ev", st.getJSONArray("ev")).put("climate", climate)
                .put("units", "C".equals(Config.prefs(c).getString(Config.UNITS, "F")) ? "C" : "F");
    }
}
