package com.canvasframe.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Animated precipitation radar around a location: a dark base map (CARTO / OpenStreetMap) with RainViewer
 * radar frames for the past ~2 hours plus any nowcast, looped. Frames are fetched only while the view is
 * shown and are reused for 10 minutes. Attribution is drawn in the corner as the providers require.
 */
public class RadarView extends View {

    private static final int ZOOM = 7, TILE = 256, W = 768, H = 400;   // ~3×1.6 tiles ≈ 300 mi wide
    private static final long STALE_MS = 10 * 60_000L, FRAME_MS = 550, HOLD_MS = 1600;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), barBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();

    private double lat = Double.NaN, lon = Double.NaN;
    private Bitmap base;
    private final List<Bitmap> frames = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();
    private final List<Boolean> forecast = new ArrayList<>();
    private int index, pastCount;
    private long loadedAt;
    private boolean loading, running, h24;
    private String status = "Loading radar…";

    public RadarView(Context c) {
        super(c);
        text.setColor(0xFFF6F1EA);
        text.setTextSize(sp(12));
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
        small.setColor(0xB3F6F1EA);
        small.setTextSize(sp(7));
        small.setShadowLayer(dp(2), 0, 0, 0xCC000000);
        dot.setColor(0xFFE8C99A);
        ring.setColor(0xFFE8C99A);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(1.5f));
        bar.setColor(0xFFE8C99A);
        barBg.setColor(0x40FFFFFF);
        shade.setColor(0x66000000);
        // OpenStreetMap is a bright map: show it desaturated and dark so the radar colours stand out.
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(0.15f);
        android.graphics.ColorMatrix dim = new android.graphics.ColorMatrix(new float[]{
                0.26f, 0, 0, 0, 4,
                0, 0.28f, 0, 0, 6,
                0, 0, 0.32f, 0, 10,
                0, 0, 0, 1, 0});
        cm.postConcat(dim);
        mapPaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
    }

    private final Paint mapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    public void setLocation(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return;
        boolean moved = Math.abs(lat - this.lat) > 0.01 || Math.abs(lon - this.lon) > 0.01;
        this.lat = lat;
        this.lon = lon;
        if (moved) { loadedAt = 0; if (running) load(); }
    }

    public void setClock24(boolean h24) { this.h24 = h24; }

    /** Start (or resume) the loop; reloads if the frames are older than 10 minutes. */
    public void start() {
        running = true;
        if (System.currentTimeMillis() - loadedAt > STALE_MS) load();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    public void stop() {
        running = false;
        ui.removeCallbacks(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    /** Animate only while actually on screen (Radar tab open, panel visible, app in front). */
    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        syncRunning();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncRunning();
    }

    private void syncRunning() {
        if (isShown() && getWindowVisibility() == VISIBLE) { if (!running) start(); }
        else if (running) stop();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long delay = FRAME_MS;
            if (!frames.isEmpty()) {
                index = (index + 1) % frames.size();
                if (index == pastCount - 1 || index == frames.size() - 1) delay = HOLD_MS; // pause on "now" and the end
                invalidate();
            }
            ui.postDelayed(this, delay);
        }
    };

    // ------------------------------------------------------------------ loading

    private void load() {
        if (loading || Double.isNaN(lat)) return;
        loading = true;
        status = "Loading radar…";
        invalidate();
        new Thread(() -> {
            try {
                // Fractional tile position of the location, and the top-left pixel of our window.
                double n = Math.pow(2, ZOOM);
                double tx = (lon + 180) / 360 * n;
                double rad = Math.toRadians(lat);
                double ty = (1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * n;
                int left = (int) Math.round(tx * TILE - W / 2.0), top = (int) Math.round(ty * TILE - H / 2.0);

                // Base map: OpenStreetMap tiles, downloaded once per location and kept on disk (OSM tile policy).
                java.io.File cached = new java.io.File(getContext().getCacheDir(), "radar_base_" + ZOOM + "_" + left + "_" + top + ".png");
                Bitmap map = cached.isFile() ? BitmapFactory.decodeFile(cached.getPath()) : null;
                if (map == null) {
                    map = compose(left, top, (x, y) -> "https://tile.openstreetmap.org/" + ZOOM + "/" + x + "/" + y + ".png");
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(cached)) {
                        map.compress(Bitmap.CompressFormat.PNG, 100, fo);
                    } catch (Exception ignored) { }
                }

                // Show the (cached) map right away while the radar frames download.
                final Bitmap early = map;
                ui.post(() -> {
                    if (base == null) { base = early; status = "Loading radar frames…"; invalidate(); }
                });

                JSONObject maps = new JSONObject(new String(fetch("https://api.rainviewer.com/public/weather-maps.json"), "UTF-8"));
                String host = maps.getString("host");
                JSONObject radar = maps.getJSONObject("radar");
                JSONArray past = radar.optJSONArray("past"), now = radar.optJSONArray("nowcast");
                List<JSONObject> all = new ArrayList<>();
                int pc = 0;
                if (past != null) for (int i = Math.max(0, past.length() - 12); i < past.length(); i++) { all.add(past.getJSONObject(i)); pc++; }
                if (now != null) for (int i = 0; i < now.length(); i++) all.add(now.getJSONObject(i));

                // Download frames in parallel (each frame is ~6 tiles); keep them in time order.
                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
                List<java.util.concurrent.Future<Bitmap>> jobs = new ArrayList<>();
                for (JSONObject f : all) {
                    final String path = f.getString("path");
                    jobs.add(pool.submit(() -> compose(left, top,
                            (x, y) -> host + path + "/" + TILE + "/" + ZOOM + "/" + x + "/" + y + "/2/1_1.png")));
                }
                List<Bitmap> fr = new ArrayList<>();
                List<Long> ts = new ArrayList<>();
                List<Boolean> fc = new ArrayList<>();
                for (int i = 0; i < all.size(); i++) {
                    try {
                        fr.add(jobs.get(i).get(30, java.util.concurrent.TimeUnit.SECONDS));
                        ts.add(all.get(i).getLong("time") * 1000L);
                        fc.add(i >= pc);
                    } catch (Exception ignored) { }   // a missing frame just drops out of the loop
                }
                pool.shutdownNow();
                int pastN = 0;
                for (Boolean isForecast : fc) if (!isForecast) pastN++;
                final int pastF = pastN;
                final Bitmap mapF = map;
                ui.post(() -> {
                    if (base != null && base != mapF) base.recycle();
                    for (Bitmap b : frames) b.recycle();
                    base = mapF;
                    frames.clear(); frames.addAll(fr);
                    times.clear(); times.addAll(ts);
                    forecast.clear(); forecast.addAll(fc);
                    pastCount = pastF;
                    index = Math.max(0, pastF - 1);
                    loadedAt = System.currentTimeMillis();
                    loading = false;
                    status = frames.isEmpty() ? "No radar frames available" : null;
                    invalidate();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    loading = false;
                    status = "Radar unavailable (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")";
                    invalidate();
                });
            }
        }, "radar-load").start();
    }

    private interface TileUrl { String url(int x, int y); }

    /** Stitch the 256-px tiles covering our W×H window (top-left pixel left/top) into one bitmap. */
    private Bitmap compose(int left, int top, TileUrl src) throws Exception {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        int max = 1 << ZOOM;
        for (int ty = Math.floorDiv(top, TILE); ty * TILE < top + H; ty++) {
            for (int tx = Math.floorDiv(left, TILE); tx * TILE < left + W; tx++) {
                if (ty < 0 || ty >= max) continue;
                int wx = ((tx % max) + max) % max;
                byte[] png = fetch(src.url(wx, ty));
                Bitmap t = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (t == null) continue;
                cv.drawBitmap(t, tx * TILE - left, ty * TILE - top, null);
                t.recycle();
            }
        }
        return out;
    }

    private static byte[] fetch(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(12000);
        h.setRequestProperty("User-Agent", "CanvasFrame/3.3 (personal Android TV home screen; low-volume, cached)");
        int code = h.getResponseCode();
        if (code >= 400) { h.disconnect(); throw new Exception("HTTP " + code); }
        try (InputStream is = h.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        } finally {
            h.disconnect();
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, Math.round(w * (float) H / W));   // keep the map's aspect ratio
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        RectF dst = new RectF(0, 0, w, h);
        c.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(dst, dp(12), dp(12), android.graphics.Path.Direction.CW);
        c.clipPath(clip);
        if (base != null) c.drawBitmap(base, null, dst, mapPaint); else c.drawColor(0xFF15191E);
        if (!frames.isEmpty()) {
            img.setAlpha(220);
            c.drawBitmap(frames.get(Math.min(index, frames.size() - 1)), null, dst, img);
            img.setAlpha(255);
        }
        // Location marker at the centre.
        float cx = w / 2f, cy = h / 2f;
        c.drawCircle(cx, cy, dp(3.5f), dot);
        c.drawCircle(cx, cy, dp(8), ring);

        // Time label + progress along the bottom.
        if (!frames.isEmpty()) {
            int i = Math.min(index, times.size() - 1);
            long t = times.get(i);
            String label = forecast.get(i)
                    ? "+" + Math.max(0, Math.round((t - times.get(Math.max(0, pastCount - 1))) / 60_000.0)) + " min forecast"
                    : new SimpleDateFormat(h24 ? "H:mm" : "h:mm a", Locale.US).format(new Date(t))
                      + (i == pastCount - 1 ? "  · latest" : "");
            c.drawRect(0, h - dp(26), w, h, shade);
            c.drawText(label, dp(10), h - dp(10), text);
            float bw = w * 0.32f, bx = w - bw - dp(10), by = h - dp(15);
            c.drawRoundRect(new RectF(bx, by, bx + bw, by + dp(4)), dp(2), dp(2), barBg);
            c.drawRoundRect(new RectF(bx, by, bx + bw * (i + 1) / frames.size(), by + dp(4)), dp(2), dp(2), bar);
        }
        String credit = "Radar © RainViewer · Map © OpenStreetMap contributors";
        c.drawText(credit, w - small.measureText(credit) - dp(8), dp(11), small);
        if (status != null) {
            float tw = text.measureText(status);
            c.drawText(status, (w - tw) / 2f, h / 2f - dp(16), text);
        }
        c.restore();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }
}
