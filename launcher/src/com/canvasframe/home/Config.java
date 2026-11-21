package com.canvasframe.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Home-screen settings shared by the launcher and the dashboard (same process, same SharedPreferences),
 * plus the Open-Meteo weather fetch. Changing a setting from the dashboard updates the TV immediately.
 */
public final class Config {
    private Config() { }

    public static final String PREFS = "canvasframe";
    public static final String HIDDEN = "hidden_apps";
    public static final String WEATHER_ON = "weather_on";
    public static final String WEATHER_LAT = "weather_lat";
    public static final String WEATHER_LON = "weather_lon";
    public static final String WEATHER_PLACE = "weather_place";
    public static final String UNITS = "units";               // "F" or "C"
    public static final String CLOCK = "clock";               // "12" or "24"
    public static final String SHOW_DASH = "show_dash";
    public static final String HIDE_NO_SIGNAL = "hide_no_signal";
    public static final String PHOTO_MIN = "photo_min";
    public static final String AUTO_HIDE = "auto_hide_sec";   // 0 = never
    public static final String CONTROLS_ALWAYS = "controls_always"; // false: clock/weather and buttons slide off when idle
    public static final String DEVICE_NAME = "device_name"; // shown on dashboards and other TVs' Sync lists
    public static final String THEME = "theme";               // "frame" (default) or "glass"
    public static final String PHOTOS_VER = "photos_ver";     // bumped when uploads change
    public static final String PHOTO_SHOW = "photo_show";     // file name to show right now
    // CanvasArt screen saver (ArtDream)
    public static final String ART_MIN = "art_min";           // minutes per photo
    public static final String ART_MAT = "art_mat";           // "none", "white" or "black"
    public static final String ART_DIM = "art_dim";           // % darker at night (half at dawn/dusk), 0 = off
    public static final String UI_SOUNDS = "ui_sounds";       // remote sounds on the home screen (also needs the TV's System sounds); per TV
    public static final String LIGHT_DIM = "light_dim";       // CanvasArt dims by the room light (RoomLight) instead of the clock; per TV
    public static final String LIGHT_SRC = "light_src";       // "auto", "self" or another TV's device id
    public static final String LIGHT_OFFSET = "light_offset"; // -50..+50: + = treat the room as brighter = dim less
    public static final String MESH_PHOTOS = "mesh_photos";   // receive photos from the other TVs in the mesh
    public static final String ART_FADE = "art_fade_min";     // at night, fade on to the minimum after this many minutes, 0 = never
    public static final String SD_PHOTO_DIR = "/sdcard/Pictures/CanvasFrame";

    /** Uploaded backgrounds live in the app's private storage (no storage permission needed). */
    public static java.io.File photosDir(Context c) {
        java.io.File d = new java.io.File(c.getFilesDir(), "photos");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------------ photo time-of-day tags

    public static final String PHOTO_TAGS = "photo_tags";     // JSON {fileName: ["dawn","night"]}
    public static final String[] PERIODS = {"dawn", "day", "dusk", "night"};

    public static JSONObject allTags(Context c) {
        try { return new JSONObject(prefs(c).getString(PHOTO_TAGS, "{}")); } catch (JSONException e) { return new JSONObject(); }
    }

    /** Tags for one photo; empty means "any time". */
    public static Set<String> tagsFor(Context c, String name) {
        Set<String> out = new HashSet<>();
        JSONArray a = allTags(c).optJSONArray(name);
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    public static final String[] SEASONS = {"spring", "summer", "fall", "winter"};
    public static final String[] HOLIDAYS = {"newyear", "valentines", "stpatricks", "easter", "memorial",
            "july4", "halloween", "thanksgiving", "christmas"};

    private static boolean in(String[] group, String t) {
        for (String g : group) if (g.equals(t)) return true;
        return false;
    }

    /** Northern-hemisphere meteorological seasons. */
    public static String currentSeason() {
        int m = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH); // 0 = Jan
        if (m >= 2 && m <= 4) return "spring";
        if (m >= 5 && m <= 7) return "summer";
        if (m >= 8 && m <= 10) return "fall";
        return "winter";
    }

    /** Holidays whose display window includes today (US dates). */
    public static Set<String> currentHolidays() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        int y = now.get(java.util.Calendar.YEAR);
        int doy = now.get(java.util.Calendar.DAY_OF_YEAR);
        Set<String> out = new HashSet<>();
        int m = now.get(java.util.Calendar.MONTH) + 1, d = now.get(java.util.Calendar.DAY_OF_MONTH);
        if ((m == 12 && d == 31) || (m == 1 && d <= 2)) out.add("newyear");
        if (m == 2 && d >= 10 && d <= 14) out.add("valentines");
        if (m == 3 && d >= 14 && d <= 17) out.add("stpatricks");
        if (m == 7 && d <= 4) out.add("july4");
        if (m == 10 && d >= 15) out.add("halloween");
        if (m == 12 && d <= 26) out.add("christmas");
        if (within(doy, dayOfYear(easter(y)), -7, 1)) out.add("easter");
        if (within(doy, dayOfYear(nthWeekday(y, java.util.Calendar.MAY, java.util.Calendar.MONDAY, -1)), -3, 0)) out.add("memorial");
        if (within(doy, dayOfYear(nthWeekday(y, java.util.Calendar.NOVEMBER, java.util.Calendar.THURSDAY, 4)), -7, 1)) out.add("thanksgiving");
        return out;
    }

    private static boolean within(int doy, int center, int before, int after) {
        return doy >= center + before && doy <= center + after;
    }

    private static int dayOfYear(java.util.Calendar c) { return c.get(java.util.Calendar.DAY_OF_YEAR); }

    /** Easter Sunday (anonymous Gregorian algorithm). */
    private static java.util.Calendar easter(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = ((h + l - 7 * m + 114) % 31) + 1;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.clear();
        cal.set(y, month - 1, day);
        return cal;
    }

    /** n-th weekday of a month (n = -1 for the last one). */
    private static java.util.Calendar nthWeekday(int y, int month, int weekday, int n) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.clear();
        if (n > 0) {
            cal.set(y, month, 1);
            while (cal.get(java.util.Calendar.DAY_OF_WEEK) != weekday) cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
            cal.add(java.util.Calendar.DAY_OF_MONTH, 7 * (n - 1));
        } else {
            cal.set(y, month, 1);
            cal.add(java.util.Calendar.MONTH, 1);
            cal.add(java.util.Calendar.DAY_OF_MONTH, -1);
            while (cal.get(java.util.Calendar.DAY_OF_WEEK) != weekday) cal.add(java.util.Calendar.DAY_OF_MONTH, -1);
        }
        return cal;
    }

    /**
     * Whether a photo with these tags may show right now. Within a group any tag matches; every group
     * the photo is tagged in must match (untagged groups don't restrict).
     */
    public static boolean fitsNow(Context c, Set<String> tags) {
        if (tags.isEmpty()) return true;
        String period = currentPeriod(c), season = currentSeason();
        Set<String> hol = currentHolidays();
        boolean hasP = false, okP = false, hasS = false, okS = false, hasH = false, okH = false;
        for (String t : tags) {
            if (in(PERIODS, t)) { hasP = true; okP |= t.equals(period); }
            else if (in(SEASONS, t)) { hasS = true; okS |= t.equals(season); }
            else if (in(HOLIDAYS, t)) { hasH = true; okH |= hol.contains(t); }
        }
        return (!hasP || okP) && (!hasS || okS) && (!hasH || okH);
    }

    /** True if the photo is tagged for a holiday that's on right now (those get priority in the rotation). */
    public static boolean isHolidayPick(Set<String> tags) {
        Set<String> hol = currentHolidays();
        for (String t : tags) if (hol.contains(t)) return true;
        return false;
    }

    /** Photos uploaded from the dashboard plus any in /sdcard/Pictures/CanvasFrame. */
    public static List<java.io.File> allPhotos(Context c) {
        List<java.io.File> out = new ArrayList<>();
        for (java.io.File dir : new java.io.File[]{photosDir(c), new java.io.File(SD_PHOTO_DIR)}) {
            java.io.File[] files = dir.listFiles((d, n) -> isImageName(n));
            if (files != null) out.addAll(java.util.Arrays.asList(files));
        }
        return out;
    }

    /**
     * The photos that may show right now (time of day, season and holiday tags). During a holiday,
     * photos tagged for it take priority. Empty if nothing fits.
     */
    public static List<java.io.File> fittingPhotos(Context c, java.util.Collection<java.io.File> photos) {
        List<java.io.File> pool = new ArrayList<>(), holiday = new ArrayList<>();
        for (java.io.File f : photos) {
            Set<String> tags = tagsFor(c, f.getName());
            if (!fitsNow(c, tags)) continue;
            pool.add(f);
            if (isHolidayPick(tags)) holiday.add(f);
        }
        return holiday.isEmpty() ? pool : holiday;
    }

    public static void setTags(Context c, String name, java.util.Collection<String> tags) {
        JSONObject all = allTags(c);
        JSONArray clean = new JSONArray();
        for (String[] group : new String[][]{PERIODS, SEASONS, HOLIDAYS}) {
            for (String p : group) if (tags != null && tags.contains(p)) clean.put(p);
        }
        try {
            if (clean.length() == 0) all.remove(name); else all.put(name, clean);
        } catch (JSONException ignored) { }
        prefs(c).edit().putString(PHOTO_TAGS, all.toString()).apply();
    }

    /**
     * Current part of the day, from today's sunrise/sunset in the weather data when available:
     * dawn = sunrise -45m … +60m, dusk = sunset -60m … +45m, day between, night otherwise.
     * Falls back to fixed hours (dawn 5–8, day 8–17, dusk 17–20, night 20–5).
     */
    public static String currentPeriod(Context c) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        int mins = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        int rise = 6 * 60 + 30, set = 18 * 60 + 30;
        boolean fromWeather = false;
        try {
            JSONObject d = new JSONObject(prefs(c).getString(WEATHER_CACHE, "{}")).getJSONObject("daily");
            rise = minutesOfDay(d.getJSONArray("sunrise").getString(0));
            set = minutesOfDay(d.getJSONArray("sunset").getString(0));
            fromWeather = true;
        } catch (Exception ignored) { }
        if (!fromWeather) {
            if (mins >= 5 * 60 && mins < 8 * 60) return "dawn";
            if (mins >= 8 * 60 && mins < 17 * 60) return "day";
            if (mins >= 17 * 60 && mins < 20 * 60) return "dusk";
            return "night";
        }
        if (mins >= rise - 45 && mins < rise + 60) return "dawn";
        if (mins >= rise + 60 && mins < set - 60) return "day";
        if (mins >= set - 60 && mins < set + 45) return "dusk";
        return "night";
    }

    private static int minutesOfDay(String iso) {   // "2026-11-12T07:03"
        String t = iso.substring(iso.indexOf('T') + 1);
        return Integer.parseInt(t.substring(0, 2)) * 60 + Integer.parseInt(t.substring(3, 5));
    }

    public static boolean isImageName(String n) {
        String l = n.toLowerCase(Locale.US);
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp")
                || l.endsWith(".heic") || l.endsWith(".heif");
    }
    public static final String WEATHER_CACHE = "weather_cache"; // JSON from Open-Meteo, plus "fetched"

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static JSONObject toJson(Context c) throws JSONException {
        SharedPreferences p = prefs(c);
        JSONObject o = new JSONObject();
        o.put(WEATHER_ON, p.getBoolean(WEATHER_ON, true));
        o.put(WEATHER_PLACE, p.getString(WEATHER_PLACE, ""));
        o.put(WEATHER_LAT, p.getString(WEATHER_LAT, ""));
        o.put(WEATHER_LON, p.getString(WEATHER_LON, ""));
        o.put(UNITS, p.getString(UNITS, "F"));
        o.put(CLOCK, p.getString(CLOCK, "12"));
        o.put(SHOW_DASH, p.getBoolean(SHOW_DASH, true));
        o.put(HIDE_NO_SIGNAL, p.getBoolean(HIDE_NO_SIGNAL, false));
        o.put(PHOTO_MIN, p.getInt(PHOTO_MIN, 10));
        o.put(AUTO_HIDE, p.getInt(AUTO_HIDE, 15));
        o.put(CONTROLS_ALWAYS, p.getBoolean(CONTROLS_ALWAYS, false));
        o.put(THEME, p.getString(THEME, "frame"));
        o.put(DEVICE_NAME, p.getString(DEVICE_NAME, ""));
        o.put(ART_MIN, p.getInt(ART_MIN, 10));
        o.put(ART_MAT, p.getString(ART_MAT, "none"));
        o.put(ART_DIM, p.getInt(ART_DIM, 40));
        o.put(ART_FADE, p.getInt(ART_FADE, 30));
        o.put(MESH_PHOTOS, p.getBoolean(MESH_PHOTOS, true));
        o.put(LIGHT_DIM, p.getBoolean(LIGHT_DIM, false));
        o.put(LIGHT_SRC, p.getString(LIGHT_SRC, "auto"));
        o.put(LIGHT_OFFSET, p.getInt(LIGHT_OFFSET, 0));
        o.put(UI_SOUNDS, p.getBoolean(UI_SOUNDS, true));
        o.put(HIDDEN, new JSONArray(p.getStringSet(HIDDEN, new HashSet<>())));
        return o;
    }

    /** Apply a partial update from the dashboard. Returns true if weather needs refetching. */
    public static boolean update(Context c, JSONObject in) throws JSONException {
        SharedPreferences.Editor e = prefs(c).edit();
        boolean refetch = false;
        if (in.has(WEATHER_ON)) { e.putBoolean(WEATHER_ON, in.getBoolean(WEATHER_ON)); refetch = true; }
        if (in.has(WEATHER_LAT) && in.has(WEATHER_LON)) {
            double lat = in.getDouble(WEATHER_LAT), lon = in.getDouble(WEATHER_LON);
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new IllegalArgumentException("bad coordinates");
            e.putString(WEATHER_LAT, String.valueOf(lat));
            e.putString(WEATHER_LON, String.valueOf(lon));
            e.putString(WEATHER_PLACE, in.optString(WEATHER_PLACE, "").trim());
            refetch = true;
        }
        if (in.optBoolean("clear_location")) {
            e.remove(WEATHER_LAT).remove(WEATHER_LON).remove(WEATHER_PLACE).remove(WEATHER_CACHE);
        }
        if (in.has(UNITS)) { e.putString(UNITS, "C".equals(in.getString(UNITS)) ? "C" : "F"); refetch = true; }
        if (in.has(CLOCK)) e.putString(CLOCK, "24".equals(in.getString(CLOCK)) ? "24" : "12");
        if (in.has(SHOW_DASH)) e.putBoolean(SHOW_DASH, in.getBoolean(SHOW_DASH));
        if (in.has(HIDE_NO_SIGNAL)) e.putBoolean(HIDE_NO_SIGNAL, in.getBoolean(HIDE_NO_SIGNAL));
        if (in.has(PHOTO_MIN)) e.putInt(PHOTO_MIN, Math.max(1, Math.min(240, in.getInt(PHOTO_MIN))));
        if (in.has(AUTO_HIDE)) e.putInt(AUTO_HIDE, Math.max(0, Math.min(600, in.getInt(AUTO_HIDE))));
        if (in.has(CONTROLS_ALWAYS)) e.putBoolean(CONTROLS_ALWAYS, in.getBoolean(CONTROLS_ALWAYS));
        if (in.has(THEME)) e.putString(THEME, "glass".equals(in.getString(THEME)) ? "glass" : "frame");
        if (in.has(DEVICE_NAME)) { String n = in.optString(DEVICE_NAME, "").trim(); e.putString(DEVICE_NAME, n.length() > 40 ? n.substring(0, 40) : n); }
        if (in.has(ART_MIN)) e.putInt(ART_MIN, Math.max(1, Math.min(240, in.getInt(ART_MIN))));
        if (in.has(ART_MAT)) { String m = in.getString(ART_MAT); e.putString(ART_MAT, "white".equals(m) || "black".equals(m) ? m : "none"); }
        if (in.has(ART_DIM)) e.putInt(ART_DIM, Math.max(0, Math.min(90, in.getInt(ART_DIM))));
        if (in.has(UI_SOUNDS)) e.putBoolean(UI_SOUNDS, in.getBoolean(UI_SOUNDS));
        if (in.has(Radar.PREF)) e.putBoolean(Radar.PREF, in.getBoolean(Radar.PREF));
        if (in.has(MESH_PHOTOS)) e.putBoolean(MESH_PHOTOS, in.getBoolean(MESH_PHOTOS));
        if (in.has(LIGHT_DIM)) e.putBoolean(LIGHT_DIM, in.getBoolean(LIGHT_DIM));
        if (in.has(LIGHT_SRC)) e.putString(LIGHT_SRC, in.optString(LIGHT_SRC, "auto").replaceAll("[^A-Za-z0-9_-]", "").isEmpty() ? "auto"
                : in.optString(LIGHT_SRC).replaceAll("[^A-Za-z0-9_-]", ""));
        if (in.has(LIGHT_OFFSET)) e.putInt(LIGHT_OFFSET, Math.max(-50, Math.min(50, in.optInt(LIGHT_OFFSET, 0))));
        if (in.has(ART_FADE)) e.putInt(ART_FADE, Math.max(0, Math.min(480, in.getInt(ART_FADE))));
        if (in.has("unhide")) {
            Set<String> h = new HashSet<>(prefs(c).getStringSet(HIDDEN, new HashSet<>()));
            h.remove(in.getString("unhide"));
            e.putStringSet(HIDDEN, h);
        }
        e.apply();
        return refetch;
    }

    // ------------------------------------------------------------------ calendars

    public static final String CALENDARS = "calendars";     // JSON [{id,name,url,color,on}]
    public static final String CAL_CACHE = "cal_cache";     // JSON {fetched, events:[{s,e,a,t,l,c}]}
    public static final String CAL_STATUS = "cal_status";   // JSON {id:{ok,count,error,fetched}}

    public static JSONArray calendars(Context c) {
        try { return new JSONArray(prefs(c).getString(CALENDARS, "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    /** Add / remove / toggle a calendar from the dashboard. Returns true if a refetch is needed. */
    public static boolean updateCalendars(Context c, JSONObject in) throws JSONException {
        JSONArray cals = calendars(c);
        String action = in.optString("action");
        if (action.equals("add")) {
            String url = in.getString("url").trim();
            if (url.startsWith("webcal://")) url = "https://" + url.substring(9);
            if (!url.startsWith("https://") && !url.startsWith("http://")) throw new IllegalArgumentException("Calendar link must start with https:// or webcal://");
            String name = in.optString("name", "").trim();
            if (name.isEmpty()) name = "Calendar " + (cals.length() + 1);
            String color = in.optString("color", "#E8C99A");
            if (!color.matches("#[0-9A-Fa-f]{6}")) color = "#E8C99A";
            cals.put(new JSONObject().put("id", Long.toString(System.currentTimeMillis(), 36))
                    .put("name", name).put("url", url).put("color", color).put("on", true));
        } else if (action.equals("remove") || action.equals("toggle")) {
            JSONArray next = new JSONArray();
            for (int i = 0; i < cals.length(); i++) {
                JSONObject cal = cals.getJSONObject(i);
                if (cal.getString("id").equals(in.optString("id"))) {
                    if (action.equals("remove")) continue;
                    cal.put("on", in.optBoolean("on", !cal.optBoolean("on", true)));
                }
                next.put(cal);
            }
            cals = next;
        } else if (action.equals("replace")) {
            // Sync from another TV: take its calendar list as a whole.
            JSONArray in2 = in.optJSONArray("calendars"), next = new JSONArray();
            if (in2 != null) for (int i = 0; i < in2.length(); i++) {
                JSONObject cal = in2.optJSONObject(i);
                if (cal == null) continue;
                String url = cal.optString("url", "");
                if (!url.startsWith("https://") && !url.startsWith("http://")) continue;
                String color = cal.optString("color", "#E8C99A");
                if (!color.matches("#[0-9A-Fa-f]{6}")) color = "#E8C99A";
                String name = cal.optString("name", "Calendar");
                next.put(new JSONObject().put("id", Long.toString(System.currentTimeMillis(), 36) + i)
                        .put("name", name.length() > 60 ? name.substring(0, 60) : name).put("url", url)
                        .put("color", color).put("on", cal.optBoolean("on", true)));
            }
            cals = next;
        } else if (!action.equals("refresh")) {
            throw new IllegalArgumentException("unknown action");
        }
        prefs(c).edit().putString(CALENDARS, cals.toString()).apply();
        return true;
    }

    /** Download every enabled calendar and cache occurrences from yesterday through ~9 weeks ahead. */
    public static void fetchCalendars(Context c) throws JSONException {
        JSONArray cals = calendars(c);
        long now = System.currentTimeMillis();
        long from = now - 86_400_000L, to = now + 63L * 86_400_000L;
        List<Ics.Ev> all = new ArrayList<>();
        JSONObject status = new JSONObject();
        for (int i = 0; i < cals.length(); i++) {
            JSONObject cal = cals.getJSONObject(i);
            if (!cal.optBoolean("on", true)) continue;
            JSONObject st = new JSONObject().put("fetched", now);
            try {
                List<Ics.Ev> evs = Ics.parse(get(cal.getString("url")), i, from, to);
                all.addAll(evs);
                st.put("ok", true).put("count", evs.size());
            } catch (Exception e) {
                st.put("ok", false).put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            }
            status.put(cal.getString("id"), st);
        }
        java.util.Collections.sort(all, (a, b) -> Long.compare(a.start, b.start));
        JSONArray evs = new JSONArray();
        for (Ics.Ev e : all) {
            JSONObject cal = cals.getJSONObject(e.cal);
            evs.put(new JSONObject().put("s", e.start).put("e", e.end).put("a", e.allDay)
                    .put("t", e.title).put("l", e.location).put("c", cal.optString("color", "#E8C99A"))
                    .put("n", cal.optString("name")));
        }
        prefs(c).edit().putString(CAL_CACHE, new JSONObject().put("fetched", now).put("events", evs).toString())
                .putString(CAL_STATUS, status.toString()).apply();
    }

    public static JSONArray cachedEvents(Context c) {
        try { return new JSONObject(prefs(c).getString(CAL_CACHE, "{}")).optJSONArray("events"); }
        catch (JSONException e) { return null; }
    }

    // ------------------------------------------------------------------ weather

    /** Fetch current weather from Open-Meteo and cache it in prefs. Safe to call off the main thread. */
    public static void fetchWeather(Context c) throws IOException, JSONException {
        SharedPreferences p = prefs(c);
        String lat = p.getString(WEATHER_LAT, ""), lon = p.getString(WEATHER_LON, "");
        if (!p.getBoolean(WEATHER_ON, true) || lat.isEmpty() || lon.isEmpty()) return;
        boolean f = !"C".equals(p.getString(UNITS, "F"));
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m"
                + "&hourly=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,daylight_duration"
                + "&forecast_days=7&timezone=auto"
                + (f ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : "");
        JSONObject w = new JSONObject(get(url));
        w.put("fetched", System.currentTimeMillis());
        w.put("unit", f ? "F" : "C");
        p.edit().putString(WEATHER_CACHE, w.toString()).apply();
    }

    public static JSONArray geocode(String q) throws IOException, JSONException {
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=6&language=en&format=json&name="
                + URLEncoder.encode(q, "UTF-8");
        JSONObject r = new JSONObject(get(url));
        JSONArray out = new JSONArray();
        JSONArray res = r.optJSONArray("results");
        if (res == null) return out;
        for (int i = 0; i < res.length(); i++) {
            JSONObject g = res.getJSONObject(i);
            StringBuilder name = new StringBuilder(g.optString("name"));
            String admin = g.optString("admin1", ""), cc = g.optString("country_code", "");
            if (!admin.isEmpty() && !admin.equals(g.optString("name"))) name.append(", ").append(admin);
            if (!cc.isEmpty()) name.append(", ").append(cc);
            out.put(new JSONObject().put("name", name.toString())
                    .put("lat", g.getDouble("latitude")).put("lon", g.getDouble("longitude")));
        }
        return out;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(20000); // calendar feeds can be large
        h.setInstanceFollowRedirects(true);
        h.setRequestProperty("User-Agent", "CanvasFrame");
        int code = h.getResponseCode();
        if (code >= 400) { h.disconnect(); throw new IOException("HTTP " + code + " from " + new URL(url).getHost()); }
        try (InputStream is = h.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    /** WMO weather code → [emoji, description]. */
    public static String[] describe(int code, boolean day) {
        switch (code) {
            case 0: return new String[]{day ? "☀️" : "🌙", "Clear"};
            case 1: return new String[]{day ? "🌤️" : "🌙", "Mostly clear"};
            case 2: return new String[]{day ? "⛅" : "☁️", "Partly cloudy"};
            case 3: return new String[]{"☁️", "Overcast"};
            case 45: case 48: return new String[]{"🌫️", "Fog"};
            case 51: case 53: case 55: return new String[]{"🌦️", "Drizzle"};
            case 56: case 57: return new String[]{"🌧️", "Freezing drizzle"};
            case 61: return new String[]{"🌧️", "Light rain"};
            case 63: return new String[]{"🌧️", "Rain"};
            case 65: return new String[]{"🌧️", "Heavy rain"};
            case 66: case 67: return new String[]{"🌧️", "Freezing rain"};
            case 71: return new String[]{"🌨️", "Light snow"};
            case 73: return new String[]{"🌨️", "Snow"};
            case 75: return new String[]{"❄️", "Heavy snow"};
            case 77: return new String[]{"🌨️", "Snow grains"};
            case 80: case 81: return new String[]{"🌦️", "Showers"};
            case 82: return new String[]{"⛈️", "Violent showers"};
            case 85: case 86: return new String[]{"🌨️", "Snow showers"};
            case 95: return new String[]{"⛈️", "Thunderstorm"};
            case 96: case 99: return new String[]{"⛈️", "Storm with hail"};
            default: return new String[]{"🌡️", "—"};
        }
    }

    static String fmtTemp(double t) { return String.format(Locale.US, "%d°", Math.round(t)); }
}
