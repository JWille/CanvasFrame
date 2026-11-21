package com.canvasframe.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Room light for CanvasArt's "Dim by room light" (setting light_dim). The source (light_src):
 *  - "auto": a TV in this TV's room that shares a light-sensor reading (an LG with AI Brightness on: env.roomLight in the
 *    shared site), the freshest one; otherwise this TV's own reading;
 *  - "self": this TV's own reading (Hisense: Hsp light, 0 dark .. ~20 flashlight on the sensor, read with the site state
 *    every ~30 s; uncalibrated, so on a log scale against OWN_REF);
 *  - a device id: that TV's shared reading.
 * bright() = 0 (dark) .. 1 (bright) after light_offset (-50..+50, + = brighter = dim less), or null when the chosen
 * source has no fresh reading (then CanvasArt dims by the clock as before).
 */
final class RoomLight {
    private RoomLight() { }

    static final int OWN_REF = 20;
    private static final long OWN_STALE = 120_000;
    static volatile int ownLight = -1;      // last Hsp light value (DashboardService.siteEnv)
    static volatile long ownAt;

    static void own(int v) { ownLight = v; ownAt = System.currentTimeMillis(); }

    private static Double ownPct() {
        if (ownLight < 0 || System.currentTimeMillis() - ownAt > OWN_STALE) return null;
        return Math.max(0, Math.min(1, Math.log(1 + Math.max(0, ownLight)) / Math.log(1 + OWN_REF)));
    }

    /** {raw 0..1, source name, source id} or null. */
    static Object[] reading(Context c) {
        String src = Config.prefs(c).getString(Config.LIGHT_SRC, "auto");
        if (!"self".equals(src)) {
            try {
                JSONArray list = Site.lightSources(c);
                JSONObject best = null;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject s = list.getJSONObject(i);
                    if (!s.optBoolean("fresh")) continue;
                    if ("auto".equals(src) ? s.optBoolean("sameRoom") && (best == null || s.optLong("at") > best.optLong("at"))
                            : src.equals(s.optString("id"))) best = s;
                }
                if (best != null) return new Object[]{best.optInt("pct") / 100.0, best.optString("name"), best.optString("id")};
            } catch (JSONException ignored) { }
            if (!"auto".equals(src)) return null;   // a chosen TV without a fresh reading: back to the clock
        }
        Double own = ownPct();
        return own == null ? null : new Object[]{own, "this TV", "self"};
    }

    static Double bright(Context c) {
        Object[] r = reading(c);
        if (r == null) return null;
        int off = Math.max(-50, Math.min(50, Config.prefs(c).getInt(Config.LIGHT_OFFSET, 0)));
        return Math.max(0, Math.min(1, (Double) r[0] + off / 100.0));
    }

    /** Use the room light right now? (switched on, and the source has a fresh reading) */
    static Double active(Context c) {
        return Config.prefs(c).getBoolean(Config.LIGHT_DIM, false) ? bright(c) : null;
    }

    /** For the dashboard: GET /api/roomlight (same shape as the webOS one, plus the sources to choose from). */
    static JSONObject status(Context c) throws JSONException {
        SharedPreferences p = Config.prefs(c);
        Object[] r = reading(c);
        Double b = bright(c);
        int dim = p.getInt(Config.ART_DIM, 40);
        String period = Config.currentPeriod(c);
        boolean on = p.getBoolean(Config.LIGHT_DIM, false);
        double dimNow = on && b != null ? dim / 100.0 * (1 - b)
                : "night".equals(period) ? dim / 100.0 : "day".equals(period) ? 0 : dim / 200.0;
        JSONArray sources = new JSONArray().put(new JSONObject().put("id", "auto").put("name", "Automatic: a TV in this room, else this TV"));
        Double own = ownPct();
        sources.put(new JSONObject().put("id", "self").put("name", "This TV's own sensor" + (own == null ? " (no reading)" : ""))
                .put("pct", own == null ? JSONObject.NULL : Math.round(own * 100)));
        JSONArray list = Site.lightSources(c);
        for (int i = 0; i < list.length(); i++) {
            JSONObject s = list.getJSONObject(i);
            sources.put(new JSONObject().put("id", s.optString("id"))
                    .put("name", s.optString("name") + (s.optBoolean("sameRoom") ? " (same room)" : "") + (s.optBoolean("fresh") ? "" : " (no reading now)"))
                    .put("pct", s.optBoolean("fresh") ? s.optInt("pct") : JSONObject.NULL));
        }
        return new JSONObject().put("available", true).put("platform", "android").put("on", on)
                .put("offset", p.getInt(Config.LIGHT_OFFSET, 0)).put("src", p.getString(Config.LIGHT_SRC, "auto"))
                .put("sources", sources)
                .put("raw", r == null ? JSONObject.NULL : r[0]).put("bright", b == null ? JSONObject.NULL : b)
                .put("from", r == null ? JSONObject.NULL : r[1]).put("ownLight", ownLight < 0 ? JSONObject.NULL : ownLight)
                .put("lum", JSONObject.NULL).put("dimNow", dimNow).put("history", new JSONArray());
    }
}
