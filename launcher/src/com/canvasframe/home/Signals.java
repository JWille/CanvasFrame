package com.canvasframe.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Signals: what this TV knows about its surroundings, in one shape, shared over the mesh as the site item sig:<id> {list}
 * (same as webos/server/lib/signals.js). Integrations add sources; outside sources post them with POST /api/signals
 * {signals:[...]} (signed in or with an API key). Consumers (Rooms view, timeline, climate charts) only read signals.
 *
 * A signal: {key, src, name, room?, presence? someone|nobody, since?, t? (deg C), h? (%), light? (%), co2?, bat?, rssi?, at}.
 */
final class Signals {
    private Signals() { }

    interface Source { JSONArray get(Context c) throws Exception; }

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_.:/-]{1,60}$");
    private static final String[] NUMS = {"t", "h", "light", "co2", "bat", "rssi", "since"};
    private static final long POSTED_FOR = 15 * 60_000L;
    private static final Map<String, Source> sources = new LinkedHashMap<>();
    private static final Map<String, JSONObject> posted = new LinkedHashMap<>();

    static synchronized void register(String src, Source s) { sources.put(src, s); }

    static JSONObject clean(JSONObject s, String src) {
        if (s == null || !KEY.matcher(s.optString("key")).matches()) return null;
        try {
            String name = s.optString("name", s.optString("key"));
            JSONObject o = new JSONObject().put("key", s.optString("key"))
                    .put("src", s.optString("src", src).length() > 24 ? s.optString("src", src).substring(0, 24) : s.optString("src", src))
                    .put("name", name.length() > 40 ? name.substring(0, 40) : name)
                    .put("at", s.optLong("at", System.currentTimeMillis()));
            if (s.optString("room").matches("[A-Za-z0-9_-]{1,40}")) o.put("room", s.optString("room"));
            String where = s.optString("where");   // a place that isn't one of the rooms (Garage, Kitchen freezer...)
            if (!where.isEmpty()) o.put("where", where.length() > 30 ? where.substring(0, 30) : where);
            String p = s.optString("presence");
            if (p.equals("someone") || p.equals("nobody")) o.put("presence", p);
            if (!s.optString("kind").isEmpty()) o.put("kind", s.optString("kind").length() > 24 ? s.optString("kind").substring(0, 24) : s.optString("kind"));
            for (String k : NUMS) {
                if (!s.has(k) || s.isNull(k)) continue;
                double v = s.optDouble(k, Double.NaN);
                if (!Double.isNaN(v) && !Double.isInfinite(v)) o.put(k, Math.round(v * 10) / 10.0);
            }
            return o;
        } catch (JSONException e) { return null; }
    }

    static synchronized JSONArray collect(Context c) {
        JSONArray out = new JSONArray();
        for (Map.Entry<String, Source> e : sources.entrySet()) {
            try {
                JSONArray list = e.getValue().get(c);
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject s = clean(list.optJSONObject(i), e.getKey());
                    if (s != null) out.put(s);
                }
            } catch (Exception ex) { android.util.Log.e("CanvasFrame", "signals: " + e.getKey() + ": " + ex); }
        }
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, JSONObject>> it = posted.entrySet().iterator(); it.hasNext(); ) {
            JSONObject s = it.next().getValue();
            if (now - s.optLong("at") > POSTED_FOR) it.remove(); else out.put(s);
        }
        return out;
    }

    static void publish(Context c, boolean push) { Site.signals(c, collect(c), push); }

    /** POST /api/signals from outside. */
    static JSONObject post(Context c, JSONObject body, String from) throws JSONException {
        JSONArray list = body.optJSONArray("signals");
        int n = 0;
        boolean presence = false;
        synchronized (Signals.class) {
            for (int i = 0; list != null && i < list.length() && i < 50; i++) {
                JSONObject in = list.optJSONObject(i);
                if (in == null) continue;
                in = new JSONObject(in.toString()).put("src", "api").put("at", System.currentTimeMillis());
                JSONObject s = clean(in, "api");
                if (s == null) continue;
                s.put("key", "api/" + s.optString("key"));
                JSONObject before = posted.get(s.optString("key"));
                if (s.has("presence") && (before == null || !s.optString("presence").equals(before.optString("presence")))) presence = true;
                posted.put(s.optString("key"), s);
                n++;
            }
        }
        if (n > 0) {
            android.util.Log.e("CanvasFrame", "signals: " + n + " from " + from);
            publish(c, presence);
        }
        return new JSONObject().put("ok", true).put("taken", n);
    }

    static synchronized JSONArray posted() { JSONArray a = new JSONArray(); for (JSONObject s : posted.values()) a.put(s); return a; }
}
