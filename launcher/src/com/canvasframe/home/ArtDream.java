package com.canvasframe.home;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * "CanvasArt": an Android screen saver (dream) that shows the CanvasFrame background photos the way
 * art mode shows art: one picture at a time, slow cross-fades, an optional mat, and dimmer at night. A small panel with
 * the time and date, alternating with the weather, drifts slowly across the screen (like the LG screen savers).
 *
 * The TV has no light sensor apps can read, so dimming follows the time of day instead (sunrise/sunset
 * from the weather data, see Config.currentPeriod): full brightness by day, half the chosen dimming at
 * dawn and dusk, all of it at night. Chosen as the screen saver from the dashboard (/api/screensaver).
 */
public class ArtDream extends DreamService {
    private static final long FADE_MS = 2500;
    private static final long CHECK_MS = 60_000;   // time-of-day / dimming check

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private FrameLayout root;
    private final FrameLayout[] slots = new FrameLayout[2];
    private int front = 0;
    private View dim;
    private TextView empty;
    private File current;
    private String lastPeriod;
    private volatile boolean running;
    /** True once Backlight confirmed it can dim the real backlight (Hisense); otherwise the picture is darkened. */
    private volatile boolean useBacklight;
    private long startedAt;
    /** The running CanvasArt, so activity from nearby TVs (Site) can re-check the dimming at once. */
    private static volatile ArtDream active;
    /** The photo on screen right now (for the dashboards' "what each TV shows"), or null. */
    static volatile String showing;

    /** Until when the dimming steps aside (the doorbell panel is up). */
    static volatile long brightUntil;

    static void brightFor(long ms) {
        brightUntil = System.currentTimeMillis() + ms;
        nudge();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(ArtDream::nudge, ms + 500);
    }

    static void nudge() {
        ArtDream d = active;
        if (d != null) d.handler.post(() -> { if (d.running) d.applyDim(true); });
    }
    private static final long FADE_SPAN_MS = 5 * 60_000;   // the late fade to the minimum takes this long

    // ---- Floating info panel (same as the LG screen savers): time + date, alternating with the weather (30 s / 15 s,
    // 3 s fades), drifting slowly around the screen so it's obvious the TV is in screen-saver mode.
    private static final long CLOCK_MS = 30_000, WEATHER_MS = 15_000, INFO_FADE_MS = 3000;
    private FrameLayout info;
    private View clockCol, wxCol;
    private TextView tTime, tDate, tWxTemp, tWxCond, tWxMore;
    private boolean showingWeather;
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            handler.postDelayed(this, 5000);
        }
    };
    private final Runnable phase = new Runnable() {
        @Override public void run() {
            if (!showingWeather && fillWeather()) {
                showingWeather = true;
                clockCol.animate().alpha(0f).setDuration(INFO_FADE_MS).start();
                wxCol.animate().alpha(1f).setDuration(INFO_FADE_MS).start();
                handler.postDelayed(this, WEATHER_MS);
            } else {
                showingWeather = false;
                updateClock();
                clockCol.animate().alpha(1f).setDuration(INFO_FADE_MS).start();
                wxCol.animate().alpha(0f).setDuration(INFO_FADE_MS).start();
                handler.postDelayed(this, CLOCK_MS);
            }
        }
    };

    private final Runnable next = this::showNext;
    private final Runnable check = new Runnable() {
        @Override public void run() {
            applyDim(true);
            // The part of the day changed and the picture no longer fits its tags: change it now.
            String p = Config.currentPeriod(ArtDream.this);
            if (!p.equals(lastPeriod) && current != null
                    && !Config.fitsNow(ArtDream.this, Config.tagsFor(ArtDream.this, current.getName()))) {
                showNext();
            }
            handler.postDelayed(this, CHECK_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key or touch ends it
        setFullscreen(true);
        setScreenBright(true);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        for (int i = 0; i < 2; i++) {
            slots[i] = new FrameLayout(this);
            slots[i].setAlpha(0f);
            root.addView(slots[i], new FrameLayout.LayoutParams(-1, -1));
        }
        empty = new TextView(this);
        empty.setTextColor(0xFF8A8A8A);
        empty.setTextSize(18);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        root.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        info = buildInfo();
        root.addView(info, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        dim = new View(this);
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        startedAt = System.currentTimeMillis();
        active = this;
        applyDim(false);
        Backlight.begin(this, ok -> handler.post(() -> {
            if (!running) return;
            useBacklight = ok;
            applyDim(true);
        }));
        showNext();
        handler.postDelayed(check, CHECK_MS);
        // The info panel starts in the middle, then drifts; clock first, weather after 30 s.
        updateClock();
        handler.post(clockTick);
        handler.postDelayed(phase, CLOCK_MS);
        info.post(() -> {
            if (!running) return;
            info.setX((root.getWidth() - info.getWidth()) / 2f);
            info.setY((root.getHeight() - info.getHeight()) / 2f);
            handler.postDelayed(this::drift, 500);
        });
    }

    @Override
    public void onDreamingStopped() {
        running = false;
        if (active == this) { active = null; showing = null; }
        handler.removeCallbacksAndMessages(null);
        if (info != null) info.animate().cancel();
        Backlight.restore(this);   // the user's own backlight, before anything else is on screen
        super.onDreamingStopped();
    }

    private SharedPreferences prefs() { return Config.prefs(this); }

    /** Picks a photo that fits right now (any photo if none fits), never the same one twice in a row. */
    private void showNext() {
        handler.removeCallbacks(next);
        lastPeriod = Config.currentPeriod(this);
        // Same photo as the other TVs (Site.syncPlan): photo samePhoto(slot) of the shared list, changing on slot boundaries.
        Object[] plan = Site.syncPlan(this);
        if (plan != null) {
            long ms = (Long) plan[0];
            @SuppressWarnings("unchecked") List<String> names = (List<String>) plan[1];
            long now = System.currentTimeMillis(), slot = now / ms;
            File f = new File(Config.photosDir(this), names.get(Site.samePhoto(slot, names.size())));
            long wait = (slot + 1) * ms - now + 150;   // just after the next boundary
            if (f.isFile()) { show(f, wait); return; }
        }
        List<File> all = Config.allPhotos(this);
        if (all.isEmpty()) {
            empty.setText("CanvasArt\n\nAdd photos on the CanvasFrame dashboard:\nHome screen → Background photos");
            empty.setVisibility(View.VISIBLE);
            // Nudge the text now and then so nothing stays in one place.
            empty.setTranslationY((random.nextFloat() - 0.5f) * 200f);
            handler.postDelayed(next, CHECK_MS);
            return;
        }
        empty.setVisibility(View.GONE);
        List<File> pool = Config.fittingPhotos(this, all);
        if (pool.isEmpty()) pool = new ArrayList<>(all);   // a screen saver should always show something
        if (pool.size() > 1) pool.remove(current);
        show(pool.get(random.nextInt(pool.size())), prefs().getInt(Config.ART_MIN, 10) * 60_000L);
    }

    /** Cross-fades to f, then schedules the next change after `wait` ms. */
    private void show(File f, long wait) {
        empty.setVisibility(View.GONE);
        current = f;
        showing = f.getName();
        String mat = prefs().getString(Config.ART_MAT, "none");
        DisplayMetrics dm = getResources().getDisplayMetrics();
        new Thread(() -> {
            Bitmap bmp = HomeActivity.decodeScaled(f, dm.widthPixels, dm.heightPixels);
            handler.post(() -> {
                if (!running) return;
                if (bmp != null) crossFade(bmp, mat, dm);
                handler.postDelayed(next, wait);
            });
        }).start();
    }

    private void crossFade(Bitmap bmp, String mat, DisplayMetrics dm) {
        int back = 1 - front;
        FrameLayout slot = slots[back];
        slot.removeAllViews();
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new BitmapDrawable(getResources(), bmp));
        if ("none".equals(mat)) {
            slot.setBackgroundColor(Color.BLACK);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            slot.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // A mat: the picture fits inside a margin of mat colour, edged with a thin bevel line.
            boolean white = "white".equals(mat);
            slot.setBackgroundColor(white ? 0xFFECE8E1 : 0xFF141414);
            int margin = Math.round(Math.min(dm.widthPixels, dm.heightPixels) * 0.11f);
            int line = Math.max(2, Math.round(dm.density * 1.5f));
            float scale = Math.min((dm.widthPixels - 2f * margin) / bmp.getWidth(),
                    (dm.heightPixels - 2f * margin) / bmp.getHeight());
            int w = Math.round(bmp.getWidth() * scale), h = Math.round(bmp.getHeight() * scale);
            GradientDrawable edge = new GradientDrawable();
            edge.setColor(white ? 0xFFB9B2A6 : 0xFF000000);
            iv.setBackground(edge);
            iv.setPadding(line, line, line, line);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setElevation(dm.density * 6);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w + 2 * line, h + 2 * line, Gravity.CENTER);
            slot.addView(iv, lp);
        }
        slot.bringToFront();
        info.bringToFront();
        dim.bringToFront();
        View old = slots[front];
        slot.animate().alpha(1f).setDuration(FADE_MS)
                .withEndAction(() -> { old.setAlpha(0f); if (old instanceof FrameLayout) ((FrameLayout) old).removeAllViews(); })
                .start();
        front = back;
    }

    private TextView infoText(float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setShadowLayer(6f, 0f, 2f, 0x66000000);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    private FrameLayout buildInfo() {
        float d = getResources().getDisplayMetrics().density;
        FrameLayout box = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x52000000);   // black at 32 %, like the LG panel
        bg.setCornerRadius(16 * d);
        box.setBackground(bg);
        box.setPadding(Math.round(22 * d), Math.round(12 * d), Math.round(22 * d), Math.round(14 * d));

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        tTime = infoText(46, 0xFFF2F2F2, "sans-serif-thin");
        tDate = infoText(14, 0xFFD0D0D0, "sans-serif-light");
        c.addView(tTime);
        c.addView(tDate);
        clockCol = c;

        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.VERTICAL);
        w.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        tWxTemp = infoText(46, 0xFFF2F2F2, "sans-serif-thin");
        tWxCond = infoText(15, 0xFFE0E0E0, "sans-serif-light");
        tWxCond.setPadding(Math.round(12 * d), 0, 0, 0);
        top.addView(tWxTemp);
        top.addView(tWxCond);
        w.addView(top);
        tWxMore = infoText(11, 0xFFC8C8C8, "sans-serif-light");
        w.addView(tWxMore);
        w.setAlpha(0f);
        wxCol = w;

        box.addView(c, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        box.addView(w, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return box;
    }

    /** Glide to a random spot at a gentle, constant speed (about 0.6 % of the width per second), then pick the next. */
    private void drift() {
        if (!running || root.getWidth() == 0) return;
        float m = root.getHeight() * 0.05f;
        float tx = m + random.nextFloat() * Math.max(0f, root.getWidth() - info.getWidth() - 2 * m);
        float ty = m + random.nextFloat() * Math.max(0f, root.getHeight() - info.getHeight() - 2 * m);
        double dist = Math.hypot(tx - info.getX(), ty - info.getY());
        long ms = Math.max(8000L, Math.round(dist / (root.getWidth() * 0.006) * 1000));
        info.animate().x(tx).y(ty).setDuration(ms)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(this::drift).start();
    }

    private void updateClock() {
        boolean h24 = "24".equals(prefs().getString(Config.CLOCK, "12"));
        java.util.Date now = new java.util.Date();
        tTime.setText(new java.text.SimpleDateFormat(h24 ? "H:mm" : "h:mm", java.util.Locale.US).format(now));
        tDate.setText(new java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.US).format(now));
    }

    /** Fills the weather texts from the cached forecast; false when there's no weather to show. */
    private boolean fillWeather() {
        SharedPreferences p = prefs();
        String cache = p.getString(Config.WEATHER_CACHE, null), place = p.getString(Config.WEATHER_PLACE, "");
        if (!p.getBoolean(Config.WEATHER_ON, true) || cache == null || place.isEmpty()) return false;
        try {
            org.json.JSONObject w = new org.json.JSONObject(cache);
            org.json.JSONObject cur = w.getJSONObject("current"), daily = w.getJSONObject("daily");
            String[] d = Config.describe(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1);
            String temp = Config.fmtTemp(cur.getDouble("temperature_2m"));
            String feels = Config.fmtTemp(cur.getDouble("apparent_temperature"));
            tWxTemp.setText(temp);
            tWxCond.setText(d[0] + "  " + d[1]);
            StringBuilder more = new StringBuilder("High " + Config.fmtTemp(daily.getJSONArray("temperature_2m_max").getDouble(0))
                    + "  Low " + Config.fmtTemp(daily.getJSONArray("temperature_2m_min").getDouble(0)));
            if (!feels.equals(temp)) more.append("   ·   Feels like ").append(feels);
            more.append("   ·   ").append(place.split(",")[0]);
            tWxMore.setText(more);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Dims for the time of day: none by day, half at dawn/dusk, the full setting at night. Lowers the real backlight
     * where the TV allows it (saves power), otherwise darkens the picture with an overlay.
     */
    private void applyDim(boolean animate) {
        float level = prefs().getInt(Config.ART_DIM, 40) / 100f;
        // By room light (RoomLight: a TV in this room with a light sensor, or this TV's own reading): the darker the room,
        // the dimmer, and a dark room counts as night for the late fade. Without a fresh reading, by the clock.
        Double rb = RoomLight.active(this);
        String p = rb != null ? (rb < 0.15 ? "night" : "day") : Config.currentPeriod(this);
        float a = rb != null ? (float) (level * (1 - rb)) : "night".equals(p) ? level : "day".equals(p) ? 0f : level / 2f;
        // Late at night: after the screen saver has run a while, slide the rest of the way down to the minimum.
        int fadeMin = prefs().getInt(Config.ART_FADE, 30);
        // Someone using a TV in this room counts as touching this one (the fade starts over); in a room next door it counts
        // as if it happened half a fade-delay ago (fading is delayed, a deep fade lifts partway).
        long[] near = Site.nearby(this);
        long since = Math.max(startedAt, Math.max(near[0], near[1] - fadeMin * 30_000L));
        long late = System.currentTimeMillis() - since - fadeMin * 60_000L;
        if ("night".equals(p) && level > 0 && fadeMin > 0 && late > 0) {
            float f = Math.min(1f, late / (float) FADE_SPAN_MS);
            a = a + ((useBacklight ? 1f : 0.95f) - a) * f;   // the overlay stops just short of black
        }
        if (System.currentTimeMillis() < brightUntil) a = 0f;   // the doorbell: full brightness for a moment
        if (useBacklight) {
            Backlight.dimTo(this, a);
            a = 0f;
        }
        if (animate) dim.animate().alpha(a).setDuration(useBacklight ? 3000 : 30_000).start();
        else dim.setAlpha(a);
    }
}
