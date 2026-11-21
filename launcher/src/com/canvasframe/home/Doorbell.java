package com.canvasframe.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.net.ssl.HttpsURLConnection;

/**
 * UniFi Protect doorbell on the TV (same as webos/server/lib/doorbell.js).
 *
 * Setup is shared by the TVs as the site item set:door {console, fp (its certificate, recorded on first contact), apiKey
 * (Protect integration API), hook (webhook token), alerts [{id, name, camera, stream, quiet}] (user-defined: name = the card's
 * title, camera = Protect camera id for snapshots and its name, stream = RTSP token for live video, quiet = repeats often,
 * so it never replaces an alert that's up and waits 30 s), plus the older live / pkg stream tokens (fallbacks; with no
 * alerts saved the four defaults ring / person / package / motion apply). It only travels TV-to-TV, browsers see what's
 * set. Per TV (prefs): door_show, door_over (also over inputs/apps), door_hide (alert ids not shown here).
 *
 * Protect's Alarm Manager calls http(s)://<any TV>/api/doorbell/hook/<hook>?kind=<alert id> (POST or GET, no
 * X-CanvasFrame header needed). That TV shows the alert and sends a site command "doorbell" so the others do (~1-2 s).
 * On screen: a panel with live video (Android's player, plain RTSP on port 7447), falling back to snapshots from the
 * integration API once a second; centred over CanvasFrame / CanvasArt, or small in a corner over an input or app. 45 s.
 */
final class Doorbell {
    private Doorbell() { }

    static final String SHOW = "door_show", OVER = "door_over";
    static final String SECS = "door_secs", SECS_OVER = "door_secs_over";
    static final String CORNER = "door_corner", SIZE = "door_size";   // the corner card over inputs/apps: br|bl|tr|tl, s|m|l   // how long it stays: over CanvasFrame / over an input or app
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Deque<JSONObject> recent = new ArrayDeque<>();   // last webhook calls (for setting up / debugging)
    private static String lastEvent = "";
    private static long lastAt;
    private static View panel;
    private static volatile boolean snapping;
    private static String cameraId;
    private static volatile String cameraName;

    static final String KINDS = "door_kinds";   // before 3.39: which of the four fixed alerts this TV showed
    static final String HIDE = "door_hide";     // alert ids this TV doesn't show (comma list); new alerts show everywhere
    static final String ALL_KINDS = "ring,person,package,motion";
    private static final String RECENT = "door_recent";   // kept in prefs so an update or restart doesn't lose them

    /** The alerts: the saved list, or the four defaults (ids match the older ?kind= webhook addresses). */
    static JSONArray alerts(JSONObject door) {
        JSONArray a = door == null ? null : door.optJSONArray("alerts");
        if (a != null && a.length() > 0) return a;
        a = new JSONArray();
        try {
            a.put(new JSONObject().put("id", "ring").put("name", "Doorbell Ringing"));
            a.put(new JSONObject().put("id", "person").put("name", "Person at the Door"));
            a.put(new JSONObject().put("id", "package").put("name", "Package Delivered"));
            a.put(new JSONObject().put("id", "motion").put("name", "Motion at the Door").put("quiet", true));
        } catch (JSONException ignored) { }
        return a;
    }

    /** The alert with this id; an unknown id still shows, under a plain title. */
    static JSONObject alert(JSONObject door, String id) {
        JSONArray a = alerts(door);
        for (int i = 0; i < a.length(); i++) if (id.equals(a.optJSONObject(i).optString("id"))) return a.optJSONObject(i);
        try { return new JSONObject().put("id", id).put("name", "UniFi Protect alert"); } catch (JSONException e) { return new JSONObject(); }
    }

    /** RTSP token for an alert's live video: its own stream; the four original alerts fall back to the older package / main
     *  stream (an alert of the user's own never borrows another camera's video). */
    static String stream(JSONObject door, JSONObject a) {
        String s = a.optString("stream"), id = a.optString("id");
        if (!s.isEmpty()) return s;
        if (a.optBoolean("noStream")) return "";
        if (!("," + ALL_KINDS + ",").contains("," + id + ",")) return "";
        if ("package".equals(id) && !door.optString("pkg").isEmpty()) return door.optString("pkg");
        return door.optString("live");
    }

    /** Alert ids hidden on this TV (moved over from the older "shown" list once). */
    static String hidden(Context c) {
        SharedPreferences p = Config.prefs(c);
        String h = p.getString(HIDE, null);
        if (h != null) return h;
        String shown = "," + p.getString(KINDS, ALL_KINDS) + ",";
        StringBuilder sb = new StringBuilder();
        for (String k : ALL_KINDS.split(",")) if (!shown.contains("," + k + ",")) sb.append(sb.length() > 0 ? "," : "").append(k);
        p.edit().putString(HIDE, sb.toString()).apply();
        return sb.toString();
    }

    static boolean wants(Context c, String kind) { return !("," + hidden(c) + ",").contains("," + kind + ","); }

    static JSONObject recent(Context c) throws JSONException {
        JSONArray a = new JSONArray();
        synchronized (recent) {
            if (recent.isEmpty()) {
                try {
                    JSONArray saved = new JSONArray(Config.prefs(c).getString(RECENT, "[]"));
                    for (int i = 0; i < saved.length(); i++) recent.addLast(saved.getJSONObject(i));
                } catch (JSONException ignored) { }
            }
            for (JSONObject o : recent) a.put(o);
        }
        return new JSONObject().put("events", a);
    }

    /** A call to the webhook (already checked against the token): the alert id from ?kind=, or guessed from the body. */
    static String kindOf(JSONObject door, String query, String body) {
        String q = query == null ? "" : query, b = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)kind=([A-Za-z0-9_-]{1,24})").matcher(q);
        if (m.find()) return m.group(1);
        JSONArray a = alerts(door);
        for (String k : new String[]{"package", "ring", "person", "motion"}) {   // older guesses, if those alerts exist
            if (b.contains(k) || ("person".equals(k) && b.contains("human")))
                for (int i = 0; i < a.length(); i++) if (k.equals(a.optJSONObject(i).optString("id"))) return k;
        }
        return a.length() > 0 ? a.optJSONObject(0).optString("id") : "ring";
    }

    static void remember(Context c, String kind, String from, String body) {
        try {
            recent(c);   // loads the saved ones first
            JSONObject o = new JSONObject().put("at", System.currentTimeMillis()).put("kind", kind).put("from", from)
                    .put("body", body == null ? "" : body.length() > 1500 ? body.substring(0, 1500) : body);
            JSONArray a = new JSONArray();
            synchronized (recent) {
                recent.addFirst(o);
                while (recent.size() > 10) recent.removeLast();
                for (JSONObject x : recent) a.put(x);
            }
            Config.prefs(c).edit().putString(RECENT, a.toString()).apply();
        } catch (JSONException ignored) { }
        android.util.Log.e("CanvasFrame", "doorbell webhook: " + kind + " from " + from);
    }

    /** Shows the alert on this TV if it's switched on here. event: an id so the same alert isn't shown twice. */
    static void show(Context ctx, String kind, String event, JSONObject door, boolean overSomething) {
        SharedPreferences p = Config.prefs(ctx);
        if (!p.getBoolean(SHOW, false) || door == null || !wants(ctx, kind)) return;
        JSONObject a = alert(door, kind);
        long now = System.currentTimeMillis();
        synchronized (Doorbell.class) {
            if (event.equals(lastEvent) || now - lastAt < 8000 && "".equals(event)) return;
            // Quiet alerts (motion) fire again and again: they never replace an alert that's up, nor repeat within 30 s.
            if (a.optBoolean("quiet") && !event.startsWith("test") && (panel != null || now - lastAt < 30000)) return;
            lastEvent = event; lastAt = now;
        }
        if (overSomething && !p.getBoolean(OVER, false)) return;
        final Context c = ctx.getApplicationContext();
        long ms = 1000L * Math.max(5, overSomething ? p.getInt(SECS_OVER, 15) : p.getInt(SECS, 45));
        if (!overSomething) ArtDream.brightFor(ms);   // CanvasArt's dimming steps aside while the picture is up
        String corner = p.getString(CORNER, "br"), size = p.getString(SIZE, "m");
        MAIN.post(() -> open(c, a, door, !overSomething, ms, corner, size));
    }

    private static TextView subtitle;
    private static View livePill;
    private static JSONObject alertShown = new JSONObject();

    /** Width of the corner card (share of the screen) for door_size. */
    static float cornerWidth(String size) {
        switch (size == null ? "" : size) {
            case "s": return 0.16f;
            case "l": return 0.27f;
            default: return 0.21f;
        }
    }

    private static void open(Context c, JSONObject alert, JSONObject door, boolean centred, long showMs, String corner, String size) {
        close(c);
        try {
            WindowManager wm = c.getSystemService(WindowManager.class);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            float dp = dm.density;
            // Everything (bell, fonts, padding, corners) scales with the card's width, so the corner card is the same
            // card shrunk, not big text squeezed into a small box (seen 2026-11-20: "Doorbell Ri…").
            float share = centred ? 0.40f : cornerWidth(size);
            float k = share / 0.40f;
            int w = Math.round(dm.widthPixels * share);
            int pad = Math.round(16 * k * dp);

            // The card: dark glass, a soft light rim, rounded (like the mock-up the user chose, 2026-11-20).
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(pad, pad, pad, pad);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xD12C3039, 0xD1191C22});
            bg.setCornerRadius(24 * k * dp);
            bg.setStroke(Math.max(1, Math.round(1.5f * Math.max(k, 0.6f) * dp)), 0x66C8CCD6);
            card.setBackground(bg);

            // Header: gold bell | title + camera | time + LIVE
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            int bell = Math.round(46 * k * dp);
            head.addView(new BellView(c), new LinearLayout.LayoutParams(bell, bell));

            LinearLayout names = new LinearLayout(c);
            names.setOrientation(LinearLayout.VERTICAL);
            names.setPadding(Math.round(14 * k * dp), 0, Math.round(8 * k * dp), 0);
            TextView t1 = new TextView(c);
            t1.setText(alert.optString("name", "UniFi Protect alert"));
            t1.setTextColor(0xFFF4F5F7);
            t1.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 21 * k * dp);
            t1.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            t1.setSingleLine(true);
            names.addView(t1);
            TextView t2 = new TextView(c);
            t2.setText(cameraLabel(alert));
            t2.setTextColor(0xFF9CA0AA);
            t2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15 * k * dp);
            t2.setSingleLine(true);
            names.addView(t2);
            head.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout right = new LinearLayout(c);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.END);
            TextView time = new TextView(c);
            time.setText(new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(new java.util.Date()));
            time.setTextColor(0xFFC9CCD3);
            time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15 * k * dp);
            time.setGravity(Gravity.END);
            right.addView(time);
            LinearLayout pill = new LinearLayout(c);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(Math.round(12 * k * dp), Math.round(4 * k * dp), Math.round(12 * k * dp), Math.round(4 * k * dp));
            GradientDrawable pb = new GradientDrawable();
            pb.setColor(0xFF4A2A30);
            pb.setCornerRadius(99 * dp);
            pill.setBackground(pb);
            View dot = new View(c);
            GradientDrawable db = new GradientDrawable();
            db.setShape(GradientDrawable.OVAL);
            db.setColor(0xFFE0707A);
            dot.setBackground(db);
            int ds = Math.round(8 * k * dp);
            pill.addView(dot, new LinearLayout.LayoutParams(ds, ds));
            TextView lv = new TextView(c);
            lv.setText("LIVE");
            lv.setTextColor(0xFFF1C9CD);
            lv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13 * k * dp);
            lv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            lv.setLetterSpacing(0.08f);
            lv.setPadding(Math.round(7 * k * dp), 0, 0, 0);
            pill.addView(lv);
            pill.setVisibility(View.INVISIBLE);   // until the picture is actually updating
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            plp.topMargin = Math.round(6 * k * dp);
            plp.gravity = Gravity.END;
            right.addView(pill, plp);
            head.addView(right);
            card.addView(head);

            // The picture: rounded, black until the first image.
            int mw = w - 2 * pad, mh = mw * 3 / 4;   // the streams are 4:3
            FrameLayout media = new FrameLayout(c);
            GradientDrawable mb = new GradientDrawable();
            mb.setColor(0xFF000000);
            mb.setCornerRadius(14 * k * dp);
            media.setBackground(mb);
            media.setClipToOutline(true);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(mw, mh);
            mlp.topMargin = Math.round(14 * k * dp);
            card.addView(media, mlp);
            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            media.addView(img, new FrameLayout.LayoutParams(mw, mh));
            subtitle = t2; livePill = pill; alertShown = alert;

            // The snapshot first (straight away, refreshed about once a second), then live video over it once its first
            // frame is drawn. The video goes into a TextureView (drawn into this window): a VideoView uses a hardware video
            // layer, which stays black over an HDMI input (seen 2026-11-20).
            snapshots(c, door, alert.optString("camera"), img, card);
            String token = stream(door, alert);
            if (!token.isEmpty()) {
                android.view.TextureView tv = new android.view.TextureView(c);
                tv.setAlpha(0f);
                media.addView(tv, new FrameLayout.LayoutParams(mw, mh, Gravity.CENTER));
                final String url = "rtsp://" + door.optString("console") + ":7447/" + token;
                tv.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
                    android.media.MediaPlayer mp;
                    @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                        try {
                            mp = new android.media.MediaPlayer();
                            mp.setSurface(new android.view.Surface(st));
                            mp.setDataSource(c, Uri.parse(url));
                            mp.setVolume(0f, 0f);
                            mp.setOnPreparedListener(android.media.MediaPlayer::start);
                            mp.setOnInfoListener((p, what, extra) -> {
                                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                    tv.animate().alpha(1f).setDuration(400).start();
                                    showLive();
                                    snapping = false;   // live video now: no more snapshots
                                }
                                return false;
                            });
                            mp.setOnErrorListener((p, what, extra) -> {
                                android.util.Log.e("CanvasFrame", "doorbell video failed (" + what + "/" + extra + "), snapshots only");
                                return true;
                            });
                            mp.prepareAsync();
                        } catch (Exception e) { android.util.Log.e("CanvasFrame", "doorbell video: " + e); }
                    }
                    @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) { }
                    @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                        if (mp != null) { try { mp.release(); } catch (Exception ignored) { } mp = null; }
                        return true;
                    }
                    @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) { }
                });
            }

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            String cn = corner == null ? "br" : corner;
            lp.gravity = centred ? Gravity.CENTER
                    : (cn.startsWith("t") ? Gravity.TOP : Gravity.BOTTOM) | (cn.endsWith("l") ? Gravity.START : Gravity.END);
            if (!centred) { lp.x = Math.round(24 * dp); lp.y = Math.round(24 * dp); }
            wm.addView(card, lp);
            panel = card;
            final View mine = card;
            MAIN.postDelayed(() -> { if (panel == mine) close(c); }, showMs);
        } catch (Exception e) {
            android.util.Log.e("CanvasFrame", "doorbell panel: " + e);
        }
    }

    private static void showLive() { if (livePill != null) livePill.setVisibility(View.VISIBLE); }

    private static void refreshName() { if (subtitle != null) subtitle.setText(cameraLabel(alertShown)); }

    /** The panel's second line: the alert's camera name from Protect (once known), or the doorbell camera's, or a fallback. */
    static String cameraLabel(JSONObject a) {
        String id = a.optString("camera");
        String n = !id.isEmpty() && camNames.containsKey(id) ? camNames.get(id)
                : cameraName != null && !cameraName.isEmpty() ? cameraName : "Front Door Camera";
        return "package".equals(a.optString("id")) && id.isEmpty() ? n + " · package camera" : n;
    }

    static void close(Context c) {
        livePill = null; subtitle = null;
        snapping = false;
        if (panel == null) return;
        try { c.getSystemService(WindowManager.class).removeView(panel); } catch (Exception ignored) { }
        panel = null;
    }

    /** Snapshots from Protect's integration API, about once a second, while the panel is up. */
    private static void snapshots(Context c, JSONObject door, String camId, ImageView img, View owner) {
        if (door.optString("apiKey").isEmpty()) return;
        snapping = true;
        final View mine = owner;
        new Thread(() -> {
            while (snapping && (panel == mine || panel == null)) {
                try {
                    byte[] jpg = snapshot(door, camId);
                    Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                    if (b != null) MAIN.post(() -> { img.setImageBitmap(b); showLive(); refreshName(); });
                } catch (Exception e) {
                    android.util.Log.e("CanvasFrame", "doorbell snapshot: " + e.getMessage());
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                }
                try { Thread.sleep(900); } catch (InterruptedException ignored) { }
            }
        }, "doorbell-snap").start();
    }

    /** GET from the Protect console's integration API with the API key, pinned to the console's certificate. */
    static byte[] protect(JSONObject door, String path) throws Exception {
        HttpsURLConnection con = (HttpsURLConnection) new URL("https://" + door.optString("console") + path).openConnection();
        con.setSSLSocketFactory(Tls.clientFactory(door.optString("fp"), null));
        con.setHostnameVerifier((h, s) -> true);   // the pinned certificate is what's checked
        con.setRequestProperty("X-API-KEY", door.optString("apiKey"));
        con.setConnectTimeout(4000);
        con.setReadTimeout(6000);
        int code = con.getResponseCode();
        if (code != 200) throw new java.io.IOException("Protect answered " + code);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static final java.util.Map<String, String> camNames = new java.util.concurrent.ConcurrentHashMap<>();

    /** Protect's cameras [{id, name, model}] (also remembers their names for the card). */
    static JSONArray cameras(JSONObject door) throws Exception {
        JSONArray cams = new JSONArray(new String(protect(door, "/proxy/protect/integration/v1/cameras"), "UTF-8"));
        JSONArray out = new JSONArray();
        for (int i = 0; i < cams.length(); i++) {
            JSONObject cam = cams.getJSONObject(i);
            camNames.put(cam.optString("id"), cam.optString("name"));
            out.put(new JSONObject().put("id", cam.optString("id")).put("name", cam.optString("name"))
                    .put("model", cam.optString("modelKey").isEmpty() ? cam.optString("type") : cam.optString("modelKey")));
        }
        return out;
    }

    /** The default camera id (the first camera whose name or model says doorbell, else the first). */
    static synchronized String camera(JSONObject door) throws Exception {
        if (cameraId != null) return cameraId;
        JSONArray cams = new JSONArray(new String(protect(door, "/proxy/protect/integration/v1/cameras"), "UTF-8"));
        String first = null;
        for (int i = 0; i < cams.length(); i++) {
            JSONObject cam = cams.getJSONObject(i);
            camNames.put(cam.optString("id"), cam.optString("name"));
            if (first == null) { first = cam.optString("id"); cameraName = cam.optString("name"); }
            if ((cam.optString("name") + " " + cam.optString("modelKey") + " " + cam.optString("type")).toLowerCase(java.util.Locale.ROOT).contains("doorbell")) {
                cameraName = cam.optString("name");
                return cameraId = cam.optString("id");
            }
        }
        return cameraId = first;
    }

    /** A snapshot from the alert's camera (camId), or the default camera when it has none. */
    static byte[] snapshot(JSONObject door, String camId) throws Exception {
        String id = camId == null || camId.isEmpty() ? camera(door) : camId;
        return protect(door, "/proxy/protect/integration/v1/cameras/" + id + "/snapshot");
    }

    static void forgetCamera() { synchronized (Doorbell.class) { cameraId = null; } }
}

/** The gold bell in the doorbell panel's header: a round gold badge with a white bell (drawn, so it looks the same on every TV). */
final class BellView extends View {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path bell = new android.graphics.Path();

    BellView(Context c) { super(c); }

    @Override protected void onDraw(android.graphics.Canvas cv) {
        float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;
        paint.setShader(new android.graphics.RadialGradient(w * 0.38f, h * 0.32f, r * 1.3f, 0xFFC9AE7C, 0xFF6E5A38, android.graphics.Shader.TileMode.CLAMP));
        cv.drawCircle(w / 2f, h / 2f, r, paint);
        paint.setShader(null);
        paint.setColor(0xFFFFFFFF);
        float u = r * 0.98f / 12f;   // the bell on a 24-unit grid, about half the badge
        float ox = w / 2f - 12 * u, oy = h / 2f - 12 * u;
        bell.reset();
        bell.moveTo(ox + 12 * u, oy + 3.4f * u);
        bell.cubicTo(ox + 8.4f * u, oy + 3.4f * u, ox + 6.6f * u, oy + 6.2f * u, ox + 6.6f * u, oy + 10f * u);
        bell.lineTo(ox + 6.6f * u, oy + 15.4f * u);
        bell.lineTo(ox + 4.6f * u, oy + 17.6f * u);
        bell.lineTo(ox + 19.4f * u, oy + 17.6f * u);
        bell.lineTo(ox + 17.4f * u, oy + 15.4f * u);
        bell.lineTo(ox + 17.4f * u, oy + 10f * u);
        bell.cubicTo(ox + 17.4f * u, oy + 6.2f * u, ox + 15.6f * u, oy + 3.4f * u, ox + 12 * u, oy + 3.4f * u);
        bell.close();
        cv.drawPath(bell, paint);
        cv.drawCircle(ox + 12 * u, oy + 19.6f * u, 1.9f * u, paint);   // clapper
        cv.drawCircle(ox + 12 * u, oy + 2.9f * u, 1.1f * u, paint);    // top
    }
}
