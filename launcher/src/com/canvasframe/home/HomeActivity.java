package com.canvasframe.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CanvasFrame: a quiet home screen for a Hisense CanvasTV used as a monitor.
 * Clock over a photo, a row of inputs (plus Art Mode), and a row of apps. No feeds, no ads.
 */
public class HomeActivity extends Activity {

    private static final String ART_PKG = "com.vt.arttv.artmode";
    private static final String ART_ACTIVITY = "com.vt.arttv.artmode.ArtModeActivity";
    private static final long PHOTO_INTERVAL_MS = 10 * 60 * 1000;
    private static final String PREFS = "canvasframe";
    private static final String KEY_HIDDEN = "hidden_apps";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ImageView background;
    private TextView dashHint;
    private TextClock clock, date;
    private LinearLayout weatherCard;
    private ForecastPanel forecast;
    private TextView wIcon, wTemp, wCond, wRange, wPlace, wUpdated;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (p, key) ->
            runOnUiThread(() -> {
                if (Config.THEME.equals(key)) { recreate(); return; } // rebuild the screen in the new theme
                if (Config.PHOTOS_VER.equals(key)) {
                    // Uploads changed: reload, and if the list was empty start showing photos now.
                    boolean wasEmpty = HomeActivity.this.photos.isEmpty();
                    loadPhotos();
                    if (wasEmpty || HomeActivity.this.photos.isEmpty() || HomeActivity.this.currentPhoto == null) showNextPhoto();
                    return;
                }
                if (Config.PHOTO_TAGS.equals(key)) {
                    // Retagged: if what's showing no longer fits this part of the day, move on.
                    File cur = HomeActivity.this.currentPhoto;
                    if (cur == null || !fitsPeriod(cur, Config.currentPeriod(HomeActivity.this))) showNextPhoto();
                    return;
                }
                if (Config.PHOTO_SHOW.equals(key)) {
                    String v = p.getString(Config.PHOTO_SHOW, "");
                    int bar = v.indexOf('|');
                    File f = new File(Config.photosDir(this), bar > 0 ? v.substring(0, bar) : v);
                    if (f.exists()) {
                        HomeActivity.this.currentPhoto = f;
                        HomeActivity.this.manualPick = true;
                        showPhoto(f);
                        HomeActivity.this.handler.removeCallbacks(HomeActivity.this.photoTicker);
                        HomeActivity.this.handler.postDelayed(HomeActivity.this.photoTicker,
                                p.getInt(Config.PHOTO_MIN, 10) * 60_000L);
                    }
                    return;
                }
                if (Config.CONTROLS_ALWAYS.equals(key)) {
                    if (controlsAlways()) slideControls(true, true);
                    else if (HomeActivity.this.parked) slideControls(false, true);
                    return;
                }
                applyConfig();
                if (Config.HIDDEN.equals(key) || Config.HIDE_NO_SIGNAL.equals(key)) populate();
            });
    private LinearLayout inputRow;
    private LinearLayout appRow;
    private View firstInputTile;
    private List<File> photos = new ArrayList<>();
    private int photoIndex = 0;

    private final Runnable photoTicker = new PhotoTicker();

    private class PhotoTicker implements Runnable {
        @Override public void run() {
            // Same photo as the other TVs (Rooms & TVs -> Screen savers together): the shared photo, changed at the boundary.
            Object[] plan = Site.syncPlan(HomeActivity.this);
            if (plan != null) {
                long ms = (Long) plan[0], now = System.currentTimeMillis(), slot = now / ms;
                @SuppressWarnings("unchecked") List<String> names = (List<String>) plan[1];
                File f = new File(Config.photosDir(HomeActivity.this), names.get(Site.samePhoto(slot, names.size())));
                if (f.isFile() && !f.equals(currentPhoto)) { currentPhoto = f; manualPick = false; showPhoto(f); }
                handler.postDelayed(this, (slot + 1) * ms - now + 300);
                return;
            }
            showNextPhoto();
            handler.postDelayed(this, Config.prefs(HomeActivity.this).getInt(Config.PHOTO_MIN, 10) * 60_000L);
        }
    }

    /** While same-photo sync is on, check every 15 s whether the shared photo changed (e.g. sync just turned on). */
    private final Runnable syncWatch = new Runnable() {
        @Override public void run() {
            Object[] plan = Site.syncPlan(HomeActivity.this);
            if (plan != null) {
                long ms = (Long) plan[0], slot = System.currentTimeMillis() / ms;
                @SuppressWarnings("unchecked") List<String> names = (List<String>) plan[1];
                File f = new File(Config.photosDir(HomeActivity.this), names.get(Site.samePhoto(slot, names.size())));
                if (!f.equals(currentPhoto) && !manualPick) { handler.removeCallbacks(photoTicker); handler.post(photoTicker); }
            }
            handler.postDelayed(this, 15_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        glassTheme = "glass".equals(Config.prefs(this).getString(Config.THEME, "frame"));
        if (glassTheme) { APP_W = 84; APP_H = 70; }
        setContentView(buildUi());
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            // The forecast panel stays open only while focus is inside it; it's opened from the weather card's › button.
            showForecast(newF != null && isInside(newF, forecast));
            showPanel(calPanel, newF != null && isInside(newF, calPanel));
            // Rows: landing on a row's button slides the row in; leaving the row slides it back out.
            // Exception: a "held" button (on open, or after idle) stays selected but closed until OK/←.
            boolean held = holdToggle && (newF == inputsDrawer.toggle || newF == appsDrawer.toggle);
            if (!held) holdToggle = false;
            for (Drawer d : new Drawer[]{inputsDrawer, appsDrawer}) {
                if (newF == d.toggle) { if (!held) handler.post(() -> d.expand(true, true)); }
                else if (d.expanded && (newF == null || !isInside(newF, d.content))) d.collapse(true);
            }
        });
        startForegroundService(new Intent(this, DashboardService.class));
        Config.prefs(this).registerOnSharedPreferenceChangeListener(prefListener);
    }

    @Override
    protected void onDestroy() {
        Config.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // CanvasArt dimmed the backlight and the app was killed before it put it back. The Hisense keeps a backlight
        // per input, so this is only done here, where the apps' setting (the one that was dimmed) is the active one.
        if (Backlight.pending(this)) Backlight.restore(this);
        handler.removeCallbacks(syncWatch);
        handler.postDelayed(syncWatch, 3000);
        populate();
        applyUiSounds();
        loadPhotos();
        dashHint.setText("Dashboard  " + DashboardService.dashboardUrl());
        applyConfig();
        handler.removeCallbacks(photoTicker);
        // Opened by the dashboard's "Capture preview": show that exact photo instead of a random one.
        String requested = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SHOW_PHOTO);
        File req = requested == null ? null : new File(Config.photosDir(this), requested);
        if (requested != null) getIntent().removeExtra(EXTRA_SHOW_PHOTO);
        String notice = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_NOTICE);
        if (notice != null) { getIntent().removeExtra(EXTRA_NOTICE); Toast.makeText(this, notice, Toast.LENGTH_LONG).show(); }
        long interval = Config.prefs(this).getInt(Config.PHOTO_MIN, 10) * 60_000L;
        restorePhotoState();
        if (req != null && req.isFile()) {
            currentPhoto = req;
            manualPick = true;
            showPhoto(req);
            handler.postDelayed(photoTicker, interval);
        } else if (currentPhoto != null && currentPhoto.isFile()
                && (manualPick || fitsPeriod(currentPhoto, Config.currentPeriod(this)))) {
            // Coming back to the home screen: keep what's showing (including a "Show now" pick)
            // and let the rotation timer continue from when that photo appeared.
            long left = interval - (System.currentTimeMillis() - lastPhotoChange);
            handler.postDelayed(photoTicker, Math.max(1000L, left));
        } else {
            handler.post(photoTicker);
        }
        handler.removeCallbacks(periodWatch);
        handler.postDelayed(periodWatch, 60_000);
        // On open: both rows hidden and nothing selected (same as idle). The first press picks a spot.
        inputsDrawer.collapse(false);
        appsDrawer.collapse(false);
        holdToggle = false;
        lastFocus = null;
        parked = true;
        park.setFocusable(true);
        park.setFocusableInTouchMode(true);
        park.requestFocus();
        slideControls(controlsAlways(), false);
    }

    // ---------------------------------------------------------------- sliding rows

    /**
     * A row of tiles that collapses to a single button ("INPUTS" / "APPS") at the right edge.
     * Selecting the button slides the row in from the right and focuses its first tile;
     * moving out of the row slides it back off the right edge and the button returns.
     */
    private final class Drawer {
        final View label, content, toggle;
        final LinearLayout row;
        boolean expanded = true;

        Drawer(View label, View content, LinearLayout row, View toggle) {
            this.label = label;
            this.content = content;
            this.row = row;
            this.toggle = toggle;
        }

        View firstTile() { return row.getChildCount() > 0 ? row.getChildAt(0) : null; }

        void expand(boolean animate, boolean focus) {
            if (!expanded) {
                expanded = true;
                label.setVisibility(glassTheme ? View.GONE : View.VISIBLE);
                content.setVisibility(View.VISIBLE);
                content.animate().cancel();
                if (animate) {
                    content.setTranslationX(getResources().getDisplayMetrics().widthPixels);
                    content.setAlpha(0f);
                    content.animate().translationX(0).alpha(1f).setDuration(280)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
                } else {
                    content.setTranslationX(0);
                    content.setAlpha(1f);
                }
            }
            View first = firstTile();
            if (focus && first != null) first.requestFocus();
            toggle.setVisibility(View.GONE);
        }

        void collapse(boolean animate) {
            if (!expanded && toggle.getVisibility() == View.VISIBLE) return;
            expanded = false;
            label.setVisibility(View.GONE);
            content.animate().cancel();
            if (animate) {
                content.animate().translationX(getResources().getDisplayMetrics().widthPixels).alpha(0f)
                        .setDuration(220).setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f))
                        .withEndAction(() -> {
                            if (expanded) return;
                            content.setVisibility(View.GONE);
                            showToggle();
                        }).start();
            } else {
                content.setVisibility(View.GONE);
                showToggle();
            }
        }

        private void showToggle() {
            toggle.setAlpha(0f);
            toggle.setVisibility(View.VISIBLE);
            toggle.setBackground(glassFor(toggle, false, false));
            toggle.animate().alpha(idleBase()).setDuration(160).start();
        }
    }

    private Drawer inputsDrawer, appsDrawer;

    // ---------------------------------------------------------------- idle auto-hide

    /** Invisible focus target used while idle, so nothing on screen looks selected. */
    private View park;
    private View lastFocus;
    private boolean parked, swallowUp;
    /** True while a row button is selected but should stay closed (on open / after idle). */
    private boolean holdToggle;
    private final Runnable idleHide = this::goIdle;

    private void scheduleIdle() {
        handler.removeCallbacks(idleHide);
        int sec = Config.prefs(this).getInt(Config.AUTO_HIDE, 15);
        lastKeyAt = System.currentTimeMillis();
        if (sec > 0) handler.postDelayed(idleHide, sec * 1000L);
    }

    private long lastKeyAt;
    private static final long RADAR_HOLD_MS = 2 * 60_000L;

    /**
     * No remote input for a while (or Back). Stage 1: remember the selection and let any open row/panel
     * recoil. Stage 2, one more auto-hide period later (or right away if nothing was open): slide the
     * clock/weather column off the left edge and the INPUTS/APPS buttons off the right.
     */
    private void goIdle() {
        if (!hasWindowFocus()) return;
        if (parked) {
            if (!controlsAlways() && controlsShown) {
                lastFocus = opener(lastFocus); // coming back shows the controls, not the old menu
                slideControls(false, true);
            }
            return;
        }
        // Radar is something you watch: while it's open, wait at least 2 minutes without input.
        long since = System.currentTimeMillis() - lastKeyAt;
        if (forecast.isRadarShowing() && since < RADAR_HOLD_MS) {
            handler.removeCallbacks(idleHide);
            handler.postDelayed(idleHide, RADAR_HOLD_MS - since);
            return;
        }
        boolean menuOpen = inputsDrawer.expanded || appsDrawer.expanded
                || forecast.getVisibility() == View.VISIBLE || calPanel.getVisibility() == View.VISIBLE;
        lastFocus = getCurrentFocus();
        parked = true;
        park.setFocusable(true);
        park.setFocusableInTouchMode(true);
        park.requestFocus(); // focus leaves every row/panel, so the focus listener slides them away
        if (controlsAlways()) return;
        if (!menuOpen) { goIdle(); return; }
        int sec = Config.prefs(this).getInt(Config.AUTO_HIDE, 15);
        handler.removeCallbacks(idleHide);
        if (sec > 0) handler.postDelayed(idleHide, sec * 1000L);
    }

    /** The button or card a menu was opened from (a row tile -> its INPUTS/APPS button, a panel -> its card). */
    private View opener(View v) {
        if (v == null) return null;
        if (isInside(v, inputsDrawer.content)) return inputsDrawer.toggle;
        if (isInside(v, appsDrawer.content)) return appsDrawer.toggle;
        if (isInside(v, forecast)) return weatherCard;
        if (isInside(v, calPanel)) return clockCard;
        return v;
    }

    // ---------------------------------------------------------------- controls slide off when idle

    /** The clock/date/weather column on the left. */
    private LinearLayout leftColumn;
    /** False while the left column is off the left edge and the INPUTS/APPS buttons off the right. */
    private boolean controlsShown = true;
    private float scrimLevel;
    private android.animation.AnimatorSet controlsAnim;

    private boolean controlsAlways() { return Config.prefs(this).getBoolean(Config.CONTROLS_ALWAYS, false); }

    private void slideControls(boolean show, boolean animate) {
        if (animate && show == controlsShown) return;
        controlsShown = show;
        if (controlsAnim != null) controlsAnim.cancel();
        int contentRight = Math.max(clockCard.getRight(), weatherCard.getRight());
        float left = show ? 0 : -(contentRight > 0 ? leftColumn.getLeft() + contentRight + dp(24)
                : getResources().getDisplayMetrics().widthPixels);
        float right = show ? 0 : dp(INPUT_W + 28);
        View[] buttons = {inputsDrawer.toggle, appsDrawer.toggle};
        if (animate) {
            android.animation.AnimatorSet set = new android.animation.AnimatorSet();
            set.playTogether(
                    android.animation.ObjectAnimator.ofFloat(leftColumn, View.TRANSLATION_X, left),
                    android.animation.ObjectAnimator.ofFloat(leftColumn, View.ALPHA, show ? 1f : 0f),
                    android.animation.ObjectAnimator.ofFloat(buttons[0], View.TRANSLATION_X, right),
                    android.animation.ObjectAnimator.ofFloat(buttons[1], View.TRANSLATION_X, right));
            set.setDuration(show ? 320 : 260);
            set.setInterpolator(show ? new android.view.animation.DecelerateInterpolator(1.6f)
                    : new android.view.animation.AccelerateInterpolator(1.4f));
            set.start();
            controlsAnim = set;
        } else {
            leftColumn.setTranslationX(left);
            leftColumn.setAlpha(show ? 1f : 0f);
            for (View b : buttons) b.setTranslationX(right);
        }
        // The shading behind the column only makes sense while the column is there.
        readScrim.animate().alpha(show ? scrimLevel : 0f).setDuration(animate ? 400 : 0).start();
    }

    /** First arrow/OK after idling: restore the drawer, card or forecast tab that was selected. */
    private void wakeFromIdle(int code) {
        slideControls(true, true);
        parked = false;
        swallowUp = true;
        View target = lastFocus;
        if (target == null || !target.isAttachedToWindow()) target = null;
        if (target != null && isInside(target, inputsDrawer.content)) {
            inputsDrawer.expand(true, false);
        } else if (target != null && isInside(target, appsDrawer.content)) {
            appsDrawer.expand(true, false);
        } else if (target != null && isInside(target, forecast)) {
            openForecast();
        } else if (target != null && isInside(target, calPanel)) {
            openCalendar();
        }
        if (target == null) {
            // Nothing selected yet (fresh open): ↑ opens Inputs, ↓ opens Apps, ← weather card,
            // anything else selects the APPS button without opening it.
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                holdToggle = false;
                (code == KeyEvent.KEYCODE_DPAD_UP ? inputsDrawer : appsDrawer).expand(true, true);
                park.setFocusable(false);
                park.setFocusableInTouchMode(false);
                scheduleIdle();
                return;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && weatherCard.getVisibility() == View.VISIBLE) target = weatherCard;
            else target = appsDrawer.toggle;
        }
        if (target == inputsDrawer.toggle || target == appsDrawer.toggle) holdToggle = true;
        target.requestFocus();
        park.setFocusable(false);
        park.setFocusableInTouchMode(false);
        scheduleIdle();
    }

    /** The collapsed row's button: styled like an unselected input tile, with a spaced-out caps label. */
    private View drawerToggle(String text, int w, int h, boolean dock) {
        LinearLayout t = baseTile(v -> { }, w, h, true, dock);
        t.setGravity(Gravity.CENTER);
        TextView tv = new TextView(this);
        tv.setText(glassTheme ? text : text.toUpperCase());
        tv.setLetterSpacing(glassTheme ? 0.04f : 0.18f);
        tv.setTextColor(TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, glassTheme ? 11 : 9);
        tv.setTag("title");
        t.addView(tv);
        if (glassTheme) {
            t.setTag("pill");
            t.setBackground(glassFor(t, false, true));
        }
        t.setId(View.generateViewId());
        return t;
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(photoTicker);
        handler.removeCallbacks(idleHide);
        handler.removeCallbacks(periodWatch);
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back hides whatever is open right away (same as the idle timeout).
        goIdle();
    }

    static final String EXTRA_SHOW_PHOTO = "show_photo";
    static final String EXTRA_NOTICE = "notice";   // message to show once open (e.g. "Synced from …")

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); // singleTask: let onResume see a "show this photo" request
    }

    private static boolean isInside(View v, View parent) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == parent) return true;
        }
        return false;
    }

    private void showForecast(boolean show) {
        boolean visible = forecast.getVisibility() == View.VISIBLE;
        if (show == visible) return;
        if (show) {
            forecast.setAlpha(0f);
            forecast.setTranslationX(dp(-12));
            forecast.setVisibility(View.VISIBLE);
            forecast.animate().alpha(1f).translationX(0).setDuration(180).start();
        } else {
            forecast.animate().alpha(0f).setDuration(120)
                    .withEndAction(() -> forecast.setVisibility(View.GONE)).start();
        }
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1E1B18, 0xFF3A3129, 0xFF5E4E3D}));

        background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(background, match());

        // Scrims top and bottom so text and tiles stay readable over any photo; the middle stays clear.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x00000000, 0x00000000, 0x00000000, 0x99000000}));
        root.addView(scrim, match());

        // Extra shading behind the clock/weather column, faded in only over bright photos.
        readScrim = new View(this);
        readScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xB3000000, 0x73000000, 0x26000000, 0x00000000, 0x00000000}));
        readScrim.setAlpha(0f);
        root.addView(readScrim, match());

        // ---- Top bar: clock on the left, inputs on the right.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(dp(36), dp(22), dp(20), 0);
        top.setClipChildren(false);
        top.setClipToPadding(false);
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout column = new LinearLayout(this);
        leftColumn = column;
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(0, 0, 0, dp(8));
        top.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout inputsCol = new LinearLayout(this);
        inputsCol.setOrientation(LinearLayout.VERTICAL);
        inputsCol.setGravity(Gravity.END);
        inputsCol.setClipChildren(false);
        inputsCol.setClipToPadding(false);
        TextView inputsLabel = sectionLabel("Inputs");
        if (glassTheme) inputsLabel.setVisibility(View.GONE);
        inputsCol.addView(inputsLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout inputsLine = new LinearLayout(this);
        inputsLine.setOrientation(LinearLayout.HORIZONTAL);
        inputsLine.setGravity(Gravity.CENTER_VERTICAL);
        inputsLine.setClipChildren(false);
        inputsLine.setClipToPadding(false);
        inputRow = row();
        View inputsScroll = scroller(inputRow);
        inputsLine.addView(inputsScroll);
        View inputsToggle = drawerToggle("Inputs", INPUT_W, INPUT_H, false);
        inputsLine.addView(inputsToggle);
        inputsCol.addView(inputsLine);
        inputsDrawer = new Drawer(inputsLabel, inputsScroll, inputRow, inputsToggle);
        top.addView(inputsCol, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Clock block is selectable like the weather card: → / OK opens the calendar panel.
        clockCard = new LinearLayout(this);
        clockCard.setOrientation(LinearLayout.HORIZONTAL);
        clockCard.setGravity(Gravity.CENTER_VERTICAL);
        clockCard.setPadding(dp(10), dp(2), dp(8), dp(6));
        clockCard.setBackground(weatherBg(false));
        clockCard.setFocusable(true);
        clockCard.setFocusableInTouchMode(true);
        clockCard.setClickable(true);
        clockCard.setOnClickListener(v -> openCalendar());
        clockCard.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && e.getAction() == KeyEvent.ACTION_DOWN) { openCalendar(); return true; }
            return false;
        });
        LinearLayout clockContent = new LinearLayout(this);
        clockContent.setOrientation(LinearLayout.VERTICAL);
        clockCard.addView(clockContent);
        clockExpand = expandDot();
        clockCard.addView(clockExpand);
        clockCard.setOnFocusChangeListener((v, has) -> {
            v.setBackground(weatherBg(has));
            clockExpand.animate().alpha(has ? 1f : 0f).translationX(has ? 0 : dp(-4)).setDuration(150).start();
        });

        clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, glassTheme ? 64 : 56);
        if (glassTheme) clock.setLetterSpacing(-0.02f);
        clock.setIncludeFontPadding(false);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setShadowLayer(dp(8), 0, dp(2), 0x80000000);
        shadowTexts.add(clock);
        clockContent.addView(clock);

        date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(0xDDFFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        date.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        date.setShadowLayer(dp(6), 0, dp(1), 0x80000000);
        shadowTexts.add(date);
        clockContent.addView(date);

        nextEvent = shadowed(text(11, TEXT, "sans-serif-light"));
        nextEvent.setPadding(dp(2), dp(4), 0, 0);
        nextEvent.setVisibility(View.GONE);
        clockContent.addView(nextEvent);

        dashHint = new TextView(this);
        dashHint.setTextColor(MUTED);
        dashHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        dashHint.setLetterSpacing(0.04f);
        dashHint.setPadding(dp(2), dp(6), 0, 0);
        shadowed(dashHint);
        clockContent.addView(dashHint);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(-10); // card padding offset: idle text stays where it was
        clp.topMargin = dp(-2);
        column.addView(clockCard, clp);

        weatherCard = buildWeatherCard();
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(10);
        wlp.leftMargin = dp(-10); // card padding offset: idle text lines up with the date above
        weatherCard.setClipToOutline(false);
        column.addView(weatherCard, wlp);

        // ---- Bottom dock: Art Mode, Settings, then apps.
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setPadding(dp(30), 0, 0, dp(14));
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        TextView appsLabel = sectionLabel("Apps");
        if (glassTheme) appsLabel.setVisibility(View.GONE);
        dock.addView(appsLabel);
        appRow = row();
        View appsScroll = scroller(appRow);
        dock.addView(appsScroll);
        root.addView(dock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        int toggleH = glassTheme ? 40 : APP_H;
        View appsToggle = drawerToggle("Apps", INPUT_W, toggleH, true);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(dp(INPUT_W), dp(toggleH), Gravity.BOTTOM | Gravity.END);
        alp.rightMargin = dp(20);
        alp.bottomMargin = dp(18);
        root.addView(appsToggle, alp);
        appsDrawer = new Drawer(appsLabel, appsScroll, appRow, appsToggle);

        // A selected-but-closed button opens with OK, or ← (the row slides in from the right).
        for (Drawer d : new Drawer[]{inputsDrawer, appsDrawer}) {
            d.toggle.setOnClickListener(v -> { holdToggle = false; d.expand(true, true); });
            d.toggle.setOnKeyListener((v, code, e) -> {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.getAction() == KeyEvent.ACTION_DOWN) {
                    holdToggle = false;
                    d.expand(true, true);
                    return true;
                }
                return false;
            });
        }

        park = new View(this);
        park.setFocusable(false);
        root.addView(park, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.START));

        // ---- Forecast panel in the open middle area, hidden until the weather card is selected.
        forecast = new ForecastPanel(this);
        forecast.setVisibility(View.GONE);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(560),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        flp.leftMargin = dp(216);
        flp.topMargin = dp(112);
        root.addView(forecast, flp);

        // ---- Calendar panel, same spot, opened from the clock.
        calPanel = new CalendarPanel(this);
        calPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams cpl = new FrameLayout.LayoutParams(dp(560),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        cpl.leftMargin = dp(216);
        cpl.topMargin = dp(40);
        root.addView(calPanel, cpl);

        // Remote navigation: right from the card goes into the panel's tabs, left from the tabs back to the card.
        weatherCard.setId(View.generateViewId());
        forecast.hourlyTab().setId(View.generateViewId());
        forecast.weeklyTab().setId(View.generateViewId());
        forecast.hourlyTab().setNextFocusLeftId(weatherCard.getId());
        // Vertical chain: INPUTS ↕ clock ↕ weather ↕ APPS.
        clockCard.setId(View.generateViewId());
        calPanel.focusTarget().setId(View.generateViewId());
        calPanel.focusTarget().setNextFocusLeftId(clockCard.getId());
        clockCard.setNextFocusUpId(inputsDrawer.toggle.getId());
        clockCard.setNextFocusDownId(weatherCard.getId());
        weatherCard.setNextFocusUpId(clockCard.getId());
        weatherCard.setNextFocusDownId(appsDrawer.toggle.getId());
        // Right column: INPUTS ↕ APPS directly; ← crosses to the left column.
        appsDrawer.toggle.setNextFocusUpId(inputsDrawer.toggle.getId());
        inputsDrawer.toggle.setNextFocusDownId(appsDrawer.toggle.getId());
        inputsDrawer.toggle.setNextFocusLeftId(clockCard.getId());
        forecast.radarTab().setId(View.generateViewId());
        forecast.hourlyTab().setNextFocusRightId(forecast.weeklyTab().getId());
        forecast.weeklyTab().setNextFocusLeftId(forecast.hourlyTab().getId());
        forecast.weeklyTab().setNextFocusRightId(forecast.radarTab().getId());
        forecast.radarTab().setNextFocusLeftId(forecast.weeklyTab().getId());

        return root;
    }

    // ---------------------------------------------------------------- weather & settings

    private TextView wExpand;

    // ---------------------------------------------------------------- clock / calendar

    private LinearLayout clockCard;
    private TextView clockExpand, nextEvent;
    private CalendarPanel calPanel;

    /** Gold › shown on a selected card (weather, clock) to say → opens more. */
    private TextView expandDot() {
        TextView x = new TextView(this);
        x.setText("›");
        x.setTextColor(ON_ACCENT);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        x.setGravity(Gravity.CENTER);
        x.setIncludeFontPadding(false);
        x.setPadding(0, 0, 0, dp(2));
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ACCENT);
        x.setBackground(dot);
        x.setAlpha(0f);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(dp(20), dp(20));
        el.leftMargin = dp(10);
        x.setLayoutParams(el);
        return x;
    }

    private void openCalendar() {
        calPanel.resetMonth();
        calPanel.animate().cancel();
        calPanel.setAlpha(0f);
        calPanel.setTranslationX(dp(-12));
        calPanel.setVisibility(View.VISIBLE);
        calPanel.animate().alpha(1f).translationX(0).setDuration(180).start();
        calPanel.focusTarget().requestFocus();
    }

    private void showPanel(View panel, boolean show) {
        boolean visible = panel.getVisibility() == View.VISIBLE;
        if (show == visible) return;
        if (show) {
            panel.setAlpha(0f);
            panel.setVisibility(View.VISIBLE);
            panel.animate().alpha(1f).setDuration(180).start();
        } else {
            panel.animate().alpha(0f).setDuration(120).withEndAction(() -> panel.setVisibility(View.GONE)).start();
        }
    }

    /** Feed calendar events to the panel and the "next event" line under the date (next 7 days). */
    private void renderCalendar() {
        org.json.JSONArray evs = Config.cachedEvents(this);
        boolean any = Config.calendars(this).length() > 0;
        boolean h24 = "24".equals(Config.prefs(this).getString(Config.CLOCK, "12"));
        calPanel.setClock24(h24);
        calPanel.setEvents(any ? (evs == null ? new org.json.JSONArray() : evs) : null);
        String line = null;
        long now = System.currentTimeMillis(), horizon = now + 7L * 86_400_000L;
        if (any && evs != null) {
            for (int i = 0; i < evs.length(); i++) {
                org.json.JSONObject ev = evs.optJSONObject(i);
                if (ev == null) continue;
                long s = ev.optLong("s"), e = ev.optLong("e");
                boolean allDay = ev.optBoolean("a");
                if (allDay ? s + 86_400_000L <= now : Math.max(e, s) <= now) continue;
                if (s > horizon) break;
                String when = allDay ? CalendarPanel.dayLabel(Math.max(s, now))
                        : CalendarPanel.dayLabel(s) + " " + new java.text.SimpleDateFormat(h24 ? "H:mm" : "h:mm a", Locale.US).format(new java.util.Date(s));
                if (s <= now && !allDay) when = "Now";
                line = "📅 " + when + " · " + ev.optString("t");
                break;
            }
        }
        nextEvent.setText(line == null ? "" : line);
        nextEvent.setVisibility(line == null ? View.GONE : View.VISIBLE);
    }

    /**
     * Weather reads like the clock and date: plain shadowed text, no frame. When selected, the glass frame
     * appears with a gold › button; Right or OK opens the forecast panel and moves into its tabs.
     */
    private LinearLayout buildWeatherCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        // Same padding framed or not, so nothing shifts when the frame appears.
        c.setPadding(dp(12), dp(8), dp(8), dp(9));
        c.setBackground(weatherBg(false));
        c.setFocusable(true);
        c.setFocusableInTouchMode(true);
        c.setClickable(true);
        c.setOnClickListener(v -> openForecast());
        c.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && e.getAction() == KeyEvent.ACTION_DOWN) {
                openForecast();
                return true;
            }
            return false;
        });
        c.setOnFocusChangeListener((v, has) -> {
            v.setBackground(weatherBg(has));
            wExpand.animate().alpha(has ? 1f : 0f).translationX(has ? 0 : dp(-4)).setDuration(150).start();
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        c.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Compact: icon and temperature side by side, then details. Shadows match the clock and date.
        wIcon = shadowed(text(20, TEXT, "sans-serif"));
        wTemp = shadowed(text(28, TEXT, "sans-serif-thin"));
        wTemp.setIncludeFontPadding(false);
        wTemp.setPadding(dp(6), 0, 0, 0);
        wCond = shadowed(text(10, TEXT, "sans-serif-light"));
        wRange = shadowed(text(7, 0xDDFFFFFF, "sans-serif-light"));
        View rule = new View(this);
        rule.setBackgroundColor(0x33FFFFFF);
        wPlace = shadowed(text(8, TEXT, "sans-serif-light"));
        wUpdated = shadowed(text(7, 0xB3FFFFFF, "sans-serif-light"));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(wIcon);
        head.addView(wTemp);
        content.addView(head);
        content.addView(wCond);
        content.addView(wRange);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        rl.setMargins(0, dp(6), 0, dp(5));
        content.addView(rule, rl);
        content.addView(wPlace);
        content.addView(wUpdated);

        // Expand button: only visible while the card is selected.
        wExpand = new TextView(this);
        wExpand.setText("›");
        wExpand.setTextColor(ON_ACCENT);
        wExpand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        wExpand.setGravity(Gravity.CENTER);
        wExpand.setIncludeFontPadding(false);
        wExpand.setPadding(0, 0, 0, dp(2));
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ACCENT);
        wExpand.setBackground(dot);
        wExpand.setAlpha(0f);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(dp(20), dp(20));
        el.leftMargin = dp(6);
        c.addView(wExpand, el);
        return c;
    }

    private TextView shadowed(TextView t) {
        t.setShadowLayer(dp(6), 0, dp(1), 0x80000000);
        shadowTexts.add(t);
        return t;
    }

    private void openForecast() {
        forecast.animate().cancel();
        forecast.setAlpha(0f);
        forecast.setTranslationX(dp(-12));
        forecast.setVisibility(View.VISIBLE);
        forecast.animate().alpha(1f).translationX(0).setDuration(180).start();
        forecast.firstFocus().requestFocus();
    }

    /** Frameless when idle (reads like the clock); glass frame with a gold outline when selected. */
    private GradientDrawable weatherBg(boolean focused) {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                focused ? new int[]{0x731C1F24, 0x73203A44, 0x73182A33} : new int[]{0, 0, 0});
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(2), focused ? ACCENT : 0x00000000);
        return bg;
    }

    private TextView text(int sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setSingleLine(true);
        return t;
    }

    /** Re-read settings shared with the dashboard and update the screen. */
    private void applyConfig() {
        SharedPreferences p = Config.prefs(this);
        boolean h24 = "24".equals(p.getString(Config.CLOCK, "12"));
        clock.setFormat12Hour(h24 ? "H:mm" : "h:mm");
        clock.setFormat24Hour(h24 ? "H:mm" : "h:mm");
        dashHint.setVisibility(p.getBoolean(Config.SHOW_DASH, true) ? View.VISIBLE : View.GONE);
        renderWeather(p);
        renderCalendar();
    }

    private void renderWeather(SharedPreferences p) {
        if (!p.getBoolean(Config.WEATHER_ON, true)) { weatherCard.setVisibility(View.GONE); return; }
        weatherCard.setVisibility(View.VISIBLE);
        String place = p.getString(Config.WEATHER_PLACE, "");
        String cache = p.getString(Config.WEATHER_CACHE, null);
        forecast.setClock24("24".equals(p.getString(Config.CLOCK, "12")));
        try {
            forecast.setWeather(place.isEmpty() || cache == null ? null : new org.json.JSONObject(cache));
        } catch (org.json.JSONException e) {
            forecast.setWeather(null);
        }
        if (place.isEmpty() || cache == null) {
            wIcon.setText("🌤️");
            wTemp.setText("--°");
            wCond.setText(place.isEmpty() ? "Set a location" : "Loading…");
            wRange.setText(place.isEmpty() ? "Dashboard › Home screen" : "");
            wPlace.setText(place.isEmpty() ? "" : place);
            wUpdated.setText("");
            return;
        }
        try {
            org.json.JSONObject w = new org.json.JSONObject(cache);
            org.json.JSONObject cur = w.getJSONObject("current");
            org.json.JSONObject daily = w.getJSONObject("daily");
            String[] d = Config.describe(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1);
            wIcon.setText(d[0]);
            wTemp.setText(Config.fmtTemp(cur.getDouble("temperature_2m")) + w.optString("unit", "F"));
            wCond.setText(d[1]);
            wRange.setText("H " + Config.fmtTemp(daily.getJSONArray("temperature_2m_max").getDouble(0))
                    + "  ·  L " + Config.fmtTemp(daily.getJSONArray("temperature_2m_min").getDouble(0))
                    + "  ·  Feels " + Config.fmtTemp(cur.getDouble("apparent_temperature")));
            wPlace.setText(place);
            java.text.DateFormat tf = android.text.format.DateFormat.getTimeFormat(this);
            wUpdated.setText("Updated " + tf.format(new java.util.Date(w.optLong("fetched"))));
        } catch (Exception e) {
            wCond.setText("Weather unavailable");
        }
    }

    private void populate() {
        inputRow.removeAllViews();
        appRow.removeAllViews();
        firstInputTile = null;

        // Inputs: every HDMI input, connected ones first.
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput() && info.getType() == TvInputInfo.TYPE_HDMI) inputs.add(info);
            }
            final TvInputManager t = tim;
            Collections.sort(inputs, (a, b) -> {
                int sa = t.getInputState(a.getId()) == TvInputManager.INPUT_STATE_CONNECTED ? 0 : 1;
                int sb = t.getInputState(b.getId()) == TvInputManager.INPUT_STATE_CONNECTED ? 0 : 1;
                if (sa != sb) return sa - sb;
                return a.getId().compareTo(b.getId());
            });
        }
        for (TvInputInfo info : inputs) {
            CharSequence custom = info.loadCustomLabel(this);
            String label = custom != null && custom.length() > 0
                    ? custom.toString().trim() : info.loadLabel(this).toString();
            boolean connected = tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED;
            if (!connected && Config.prefs(this).getBoolean(Config.HIDE_NO_SIGNAL, false)) continue;
            final Uri uri = TvContract.buildChannelUriForPassthroughInput(info.getId());
            View tile = textTile(capitalize(label), connected ? "Connected" : "No signal",
                    INPUT_W, INPUT_H, v -> launch(new Intent(Intent.ACTION_VIEW, uri)));
            if (!connected) setIdleAlpha(tile, NO_SIGNAL_ALPHA);
            tile.setNextFocusDownId(appsDrawer.toggle.getId());   // right column: down to Apps
            inputRow.addView(tile);
            if (firstInputTile == null) firstInputTile = tile;
        }
        if (firstInputTile != null) {
            firstInputTile.setId(View.generateViewId());
            firstInputTile.setNextFocusLeftId(clockCard.getId()); // ← crosses to the left column (top)
        }

        // Art Mode
        Intent art = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(ART_PKG, ART_ACTIVITY));
        if (getPackageManager().resolveActivity(art, 0) != null) {
            View artTile = glassTheme ? symbolTile("🖼️", "Art Mode", v -> launch(art))
                    : textTile("Art Mode", "Canvas", APP_W, APP_H, v -> launch(art));
            artTile.setId(View.generateViewId());
            artTile.setNextFocusUpId(inputsDrawer.toggle.getId());
            artTile.setNextFocusLeftId(weatherCard.getId()); // ← crosses to the left column (bottom)
            appRow.addView(artTile);
        }

        // Settings (long-press to restore hidden apps)
        View.OnClickListener openSettings = v -> launch(new Intent(Settings.ACTION_SETTINGS));
        View settings = glassTheme ? symbolTile("⚙️", "Settings", openSettings)
                : textTile("Settings", "System", APP_W, APP_H, openSettings);
        settings.setOnLongClickListener(v -> {
            prefs().edit().remove(KEY_HIDDEN).apply();
            toast("Hidden apps restored");
            populate();
            return true;
        });
        settings.setNextFocusUpId(inputsDrawer.toggle.getId());
        appRow.addView(settings);

        // Apps: everything with a TV launcher entry, minus ourselves and hidden ones.
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(q, 0);
        Set<String> hidden = hiddenApps();
        Collections.sort(apps, (a, b) -> a.loadLabel(pm).toString()
                .compareToIgnoreCase(b.loadLabel(pm).toString()));
        for (ResolveInfo ri : apps) {
            final String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || pkg.equals(ART_PKG) || hidden.contains(pkg)) continue;
            final Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            String label = ri.loadLabel(pm).toString();
            // Glass theme: app icon + name on dark glass; Frame theme: the app's wide TV banner.
            View tile = banner != null && !glassTheme
                    ? bannerTile(banner, label, v -> launch(launch))
                    : iconTile(ri.loadIcon(pm), label, v -> launch(launch));
            tile.setOnLongClickListener(v -> {
                Set<String> h = hiddenApps();
                h.add(pkg);
                prefs().edit().putStringSet(KEY_HIDDEN, h).apply();
                toast(label + " hidden (long-press Settings to restore)");
                populate();
                return true;
            });
            tile.setNextFocusUpId(inputsDrawer.toggle.getId());   // right column: up to Inputs
            appRow.addView(tile);
        }
    }

    // ---------------------------------------------------------------- tiles

    // Sizes in dp (screen is 960x540dp). Colours mirror the dashboard CSS.
    private static final int INPUT_W = 104, INPUT_H = 46;
    private int APP_W = 96, APP_H = 54;          // the Glass dock theme uses squarer icon tiles
    /** "Glass dock" theme: frosted trays, dark glass icon tiles, white glow focus, pill buttons. */
    private boolean glassTheme;
    private static final int RADIUS = 10;
    private static final int GLASS = 0x2EFFFFFF, GLASS_LINE = 0x33FFFFFF;
    private static final int ACCENT = 0xFFE8C99A, ON_ACCENT = 0xFF2B2218;
    private static final int TEXT = 0xFFF6F1EA, MUTED = 0x9EF6F1EA;

    private View textTile(String title, String subtitle, int w, int h, View.OnClickListener click) {
        LinearLayout t = baseTile(click, w, h, true, h == APP_H);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(glassTheme ? Gravity.CENTER : Gravity.CENTER_VERTICAL | Gravity.START);
        t.setPadding(dp(10), dp(4), dp(8), dp(4));
        TextView a = new TextView(this);
        a.setText(title);
        a.setTextColor(TEXT);
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        a.setSingleLine(true);
        a.setTag("title");
        TextView b = new TextView(this);
        b.setText(subtitle);
        b.setTextColor(MUTED);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        b.setSingleLine(true);
        b.setTag("sub");
        t.addView(a);
        t.addView(b);
        return t;
    }

    private View bannerTile(Drawable banner, String label, View.OnClickListener click) {
        LinearLayout t = baseTile(click, APP_W, APP_H, false, true);
        t.setContentDescription(label);
        t.setPadding(dp(2), dp(2), dp(2), dp(2));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(banner);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setClipToOutline(true);
        iv.setBackground(rounded(0x00000000, RADIUS - 2));
        t.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return t;
    }

    private View iconTile(Drawable icon, String label, View.OnClickListener click) {
        LinearLayout t = baseTile(click, APP_W, APP_H, true, true);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        int s = glassTheme ? 30 : 22;
        t.addView(iv, new LinearLayout.LayoutParams(dp(s), dp(s)));
        t.addView(tileLabel(label));
        return t;
    }

    /** Glass theme tile with a symbol instead of an app icon (Art Mode, Settings). */
    private View symbolTile(String symbol, String label, View.OnClickListener click) {
        LinearLayout t = baseTile(click, APP_W, APP_H, true, true);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        TextView sym = new TextView(this);
        sym.setText(symbol);
        sym.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        sym.setGravity(Gravity.CENTER);
        sym.setIncludeFontPadding(false);
        t.addView(sym);
        t.addView(tileLabel(label));
        return t;
    }

    private TextView tileLabel(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, glassTheme ? 9 : 8);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(4), dp(glassTheme ? 5 : 3), dp(4), 0);
        tv.setTag("title");
        if (glassTheme) tv.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        return tv;
    }

    /**
     * Glass card like the dashboard. Every tile (inputs and dock) sits see-through until selected,
     * then goes fully opaque, turns gold and grows 18%. Dock tiles grow upward, input tiles downward,
     * so neither is clipped by the screen edge.
     */
    private static final float IDLE_ALPHA = 0.55f, NO_SIGNAL_ALPHA = 0.35f, FOCUS_SCALE = 1.18f;
    private final java.util.WeakHashMap<View, Float> idleAlpha = new java.util.WeakHashMap<>();

    private void setIdleAlpha(View v, float a) {
        idleAlpha.put(v, a);
        if (!v.isFocused()) v.setAlpha(a);
    }

    private LinearLayout baseTile(View.OnClickListener click, int w, int h, boolean textual, boolean dock) {
        LinearLayout t = new LinearLayout(this);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        t.setBackground(glass(false, textual));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(w), dp(h));
        lp.setMargins(0, dp(4), dp(11), dp(4));
        t.setLayoutParams(lp);
        t.setAlpha(idleBase());
        t.setPivotY(dock ? dp(h) : 0);
        final float scale = glassTheme ? 1.12f : FOCUS_SCALE;
        t.setOnFocusChangeListener((v, has) -> {
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(dock ? v.getHeight() : 0);
            Float idle = idleAlpha.get(v);
            v.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f)
                    .alpha(has ? 1f : (idle != null ? (bright ? Math.min(1f, idle + 0.3f) : idle) : idleBase()))
                    .translationZ(has ? dp(6) : 0).setDuration(150).start();
            v.setBackground(glassFor(v, has, textual));
            if (textual && !glassTheme && v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (!(c instanceof TextView)) continue;
                    boolean sub = "sub".equals(c.getTag());
                    ((TextView) c).setTextColor(has ? (sub ? 0xB32B2218 : ON_ACCENT) : (sub ? MUTED : TEXT));
                }
            }
        });
        return t;
    }

    /** Tile background; pill-shaped for row buttons in the Glass theme (tagged "pill"). */
    private GradientDrawable glassFor(View v, boolean focused, boolean textual) {
        GradientDrawable g = glass(focused, textual);
        if (glassTheme && "pill".equals(v.getTag())) g.setCornerRadius(dp(99));
        return g;
    }

    /**
     * Glass dock theme tile: dark glass with a soft top highlight and hairline border;
     * focused = lighter glass with a bright white edge (no gold).
     */
    private GradientDrawable glassDockTile(boolean focused) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, focused
                ? new int[]{0xE6686872, 0xE6484850, 0xE63A3A42}
                : new int[]{0xB3484850, 0xB32E2E34, 0xB3242428});
        g.setCornerRadius(dp(14));
        g.setStroke(dp(focused ? 2 : 1), focused ? 0xF2FFFFFF : 0x4DFFFFFF);
        return g;
    }

    private GradientDrawable glass(boolean focused, boolean textual) {
        if (glassTheme) return glassDockTile(focused);
        // Over bright photos, idle tiles use dark smoked glass instead of white frosted glass.
        int fill = focused && textual ? ACCENT : (bright ? 0x99141210 : GLASS);
        GradientDrawable g = rounded(fill, RADIUS);
        g.setStroke(dp(focused ? 2 : 1), focused ? ACCENT : (bright ? 0x40FFFFFF : GLASS_LINE));
        return g;
    }

    // ---------------------------------------------------------------- photos

    /** Photos uploaded from the dashboard plus any in /sdcard/Pictures/CanvasFrame. */
    private void loadPhotos() {
        photos.clear();
        photos.addAll(Config.allPhotos(this));
        Collections.shuffle(photos);
    }

    private File currentPhoto;
    private String lastPeriod;
    /** When the background last changed, and whether it was picked by hand ("Show now"). */
    private long lastPhotoChange;
    private boolean manualPick;

    /**
     * Random photo that fits right now (time of day, season and holiday tags; untagged groups don't restrict).
     * During a holiday, photos tagged for it take priority. Falls back to every photo if none match,
     * and avoids repeating the current one.
     */
    private void showNextPhoto() {
        if (photos.isEmpty()) { currentPhoto = null; background.setImageDrawable(null); applyReadability(0f, false); return; }
        lastPeriod = Config.currentPeriod(this);
        lastSeasonKey = seasonKey();
        List<File> pool = Config.fittingPhotos(this, photos);
        if (pool.isEmpty()) {
            // Nothing fits right now (e.g. only winter photos in summer): show the plain background
            // rather than break the tag rules.
            showPlainBackground();
            return;
        }
        if (pool.size() > 1) pool.remove(currentPhoto);
        currentPhoto = pool.get(new java.util.Random().nextInt(pool.size()));
        manualPick = false;
        showPhoto(currentPhoto);
    }

    /** Whether a photo's tags (time of day, season, holiday) fit right now. The period argument is kept for callers. */
    /** Fade out to the plain gradient background (no photo fits the current tags). */
    private void showPlainBackground() {
        showing = null;
        currentPhoto = null;
        manualPick = false;
        lastPhotoChange = System.currentTimeMillis();
        photoState().edit().remove("cur").putLong("time", lastPhotoChange).putBoolean("manual", false).apply();
        if (background.getDrawable() != null) {
            background.animate().alpha(0f).setDuration(1200)
                    .withEndAction(() -> { if (currentPhoto == null) background.setImageDrawable(null); }).start();
        }
        applyReadability(0f, false);
    }

    private boolean fitsPeriod(File f, String period) {
        return Config.fitsNow(this, Config.tagsFor(this, f.getName()));
    }

    private String lastSeasonKey;

    /** Season plus active holidays, so a change at midnight is noticed. */
    private static String seasonKey() {
        return Config.currentSeason() + new java.util.TreeSet<>(Config.currentHolidays());
    }

    /**
     * Checks once a minute. When the part of the day changes and the current photo no longer fits, or the
     * season/holiday changes (e.g. a holiday starts), switch to a fitting photo right away.
     */
    private final Runnable periodWatch = new PeriodWatch();

    private class PeriodWatch implements Runnable {
        @Override public void run() {
            String p = Config.currentPeriod(HomeActivity.this);
            String sk = seasonKey();
            boolean periodChanged = lastPeriod != null && !p.equals(lastPeriod);
            boolean seasonChanged = lastSeasonKey != null && !sk.equals(lastSeasonKey);
            lastSeasonKey = sk;
            if ((periodChanged || seasonChanged) && !photos.isEmpty()
                    && (currentPhoto == null || seasonChanged || !fitsPeriod(currentPhoto, p))) {
                showNextPhoto();
                handler.removeCallbacks(photoTicker);
                handler.postDelayed(photoTicker, Config.prefs(HomeActivity.this).getInt(Config.PHOTO_MIN, 10) * 60_000L);
            }
            lastPeriod = p;
            renderCalendar(); // keep "next event" and today's highlight current
            handler.postDelayed(this, 60_000);
        }
    }

    // The TV is low on memory and may destroy this activity while another input is on screen, so the
    // current background is saved and restored instead of picking a new random one on every return.
    private SharedPreferences photoState() { return getSharedPreferences("canvasframe_state", MODE_PRIVATE); }

    private void savePhotoState() {
        if (currentPhoto == null) return;
        photoState().edit().putString("cur", currentPhoto.getPath())
                .putLong("time", lastPhotoChange).putBoolean("manual", manualPick).apply();
    }

    /** Re-adopt the saved background, and apply a "Show now" that arrived while we were gone. */
    private void restorePhotoState() {
        SharedPreferences st = photoState();
        long savedTime = st.getLong("time", 0);
        String show = Config.prefs(this).getString(Config.PHOTO_SHOW, "");
        int bar = show.indexOf('|');
        long showTime = 0;
        try { if (bar > 0) showTime = Long.parseLong(show.substring(bar + 1)); } catch (NumberFormatException ignored) { }
        File want = null;
        long when = savedTime;
        boolean manual = st.getBoolean("manual", false);
        if (showTime > savedTime) {                       // a "Show now" newer than what we last showed
            want = new File(Config.photosDir(this), show.substring(0, bar));
            when = showTime;
            manual = true;
        } else if (currentPhoto == null) {               // fresh instance: re-adopt the saved photo
            String p = st.getString("cur", null);
            if (p != null) want = new File(p);
        }
        if (want == null || !want.isFile()) return;
        // A saved (not hand-picked) photo that no longer fits the tags isn't re-shown; rotation picks again.
        if (!manual && !Config.fitsNow(this, Config.tagsFor(this, want.getName()))) return;
        boolean changed = !want.equals(currentPhoto) || background.getDrawable() == null;
        currentPhoto = want;
        manualPick = manual;
        if (changed) showPhoto(want);
        lastPhotoChange = when;                            // keep the rotation timer's original start
        savePhotoState();
    }

    /** The background photo right now (for the dashboards' "what each TV shows"), or null. */
    static volatile String showing;

    private void showPhoto(File f) {
        showing = f.getName();
        lastPhotoChange = System.currentTimeMillis();
        savePhotoState();
        new Thread(() -> {
            Bitmap bmp = decodeScaled(f, getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            if (bmp == null) return;
            float[] r = readability(bmp);
            runOnUiThread(() -> {
                background.setAlpha(0f);
                background.setImageDrawable(new BitmapDrawable(getResources(), bmp));
                background.animate().alpha(1f).setDuration(1200).start();
                applyReadability(r[0], r[1] > 0.5f);
            });
        }).start();
    }

    // ---------------------------------------------------------------- readability on bright photos

    private boolean bright;
    private View readScrim;
    private final List<TextView> shadowTexts = new ArrayList<>();

    /**
     * Returns {textLevel 0..1, cornersBright 0/1}.
     * textLevel: how hard the clock/weather column is to read over this photo. Uses the brightest 10% of
     * pixels there (bright patches, not the average) and how busy/detailed the area is (mean edge strength),
     * since fine bright texture behind thin white text hurts as much as plain brightness.
     * cornersBright: whether the INPUTS / APPS corners are light enough to need dark glass buttons.
     */
    private float[] readability(Bitmap bmp) {
        final int W = 128, H = 72;
        Bitmap s = Bitmap.createScaledBitmap(bmp, W, H, true);
        float[] lum = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int c = s.getPixel(x, y);
                lum[y * W + x] = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
            }
        }
        // Text column: left 25%, from just below the top to ~45% down (clock, date, weather).
        int x1 = 32, y0 = 2, y1 = 33, n = 0;
        float[] vals = new float[x1 * (y1 - y0)];
        double edges = 0;
        int en = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < x1; x++) {
                float v = lum[y * W + x];
                vals[n++] = v;
                if (x > 0 && y > y0) {
                    edges += Math.abs(v - lum[y * W + x - 1]) + Math.abs(v - lum[(y - 1) * W + x]);
                    en++;
                }
            }
        }
        java.util.Arrays.sort(vals, 0, n);
        float p90 = vals[(int) (n * 0.9f)];
        float edge = (float) (edges / Math.max(1, en));
        String p = Config.currentPeriod(this);
        float bias = ("day".equals(p) || "dawn".equals(p)) ? 0.05f : 0f;
        float fromBright = (p90 + bias - 0.25f) / 0.35f;
        float fromBusy = (edge - 0.02f) / 0.03f;
        float textLevel = Math.max(0f, Math.min(1f, Math.max(fromBright, fromBusy)));

        float corners = Math.max(regionLum(s, 100, 0, W, 14), regionLum(s, 100, 60, W, H));
        if (s != bmp) s.recycle();
        return new float[]{textLevel, corners > 0.45f - bias ? 1f : 0f};
    }

    private static float regionLum(Bitmap b, int x0, int y0, int x1, int y1) {
        double sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int c = b.getPixel(x, y);
                sum += (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0;
                n++;
            }
        }
        return n == 0 ? 0 : (float) (sum / n);
    }

    private float idleBase() { return glassTheme ? 0.94f : (bright ? 0.88f : IDLE_ALPHA); }

    /**
     * Shading behind the clock/weather column scales with textLevel (0 = none … 1 = full);
     * heavier text shadows above 25%; dark glass on tiles and buttons when their corners are bright.
     */
    private void applyReadability(float textLevel, boolean cornersBright) {
        scrimLevel = textLevel;
        readScrim.animate().alpha(controlsShown ? textLevel : 0f).setDuration(500).start();
        boolean strong = textLevel > 0.25f;
        for (TextView t : shadowTexts) {
            if (strong) t.setShadowLayer(dp(10), 0, dp(2), 0xD9000000);
            else t.setShadowLayer(dp(6), 0, dp(1), 0x80000000);
        }
        setBright(cornersBright);
    }

    /** Dark smoked glass on tiles/buttons (bright corners) or the normal white frosted glass. */
    private void setBright(boolean b) {
        if (b == bright) return;
        bright = b;
        List<View> tiles = new ArrayList<>();
        for (LinearLayout r : new LinearLayout[]{inputRow, appRow}) {
            for (int i = 0; i < r.getChildCount(); i++) tiles.add(r.getChildAt(i));
        }
        tiles.add(inputsDrawer.toggle);
        tiles.add(appsDrawer.toggle);
        for (View v : tiles) {
            if (v.isFocused()) continue;
            v.setBackground(glassFor(v, false, false));
            if (v.getVisibility() == View.VISIBLE) {
                Float idle = idleAlpha.get(v);
                v.animate().alpha(idle != null ? (b ? Math.min(1f, idle + 0.3f) : idle) : idleBase()).setDuration(300).start();
            }
        }
    }

    static Bitmap decodeScaled(File f, int reqW, int reqH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    // ---------------------------------------------------------------- helpers

    private void launch(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast("Can't open that");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Remote use on the home screen counts as activity for the shared site (Site throttles it to every 20 s).
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            final android.content.Context app = getApplicationContext();
            new Thread(() -> Site.activity(app, "remote")).start();
        }
        int code = e.getKeyCode();
        boolean nav = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        // While idle-hidden, the first arrow/OK press only brings back what was selected.
        if (nav && parked) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) wakeFromIdle(code);
            return true;
        }
        if (nav && swallowUp && e.getAction() == KeyEvent.ACTION_UP) {
            swallowUp = false;
            return true;
        }
        scheduleIdle();
        // Remote sounds off (dashboard switch): Android plays Google TV's navigation sound whenever it moves the focus, so
        // let the views handle the key first and, if none does, move the focus here, which is silent. (Clicks are muted per
        // view in applyUiSounds.) With the switch on, Android's own sounds play as usual (they follow System sounds).
        if (!Config.prefs(this).getBoolean(Config.UI_SOUNDS, true) && e.getAction() == KeyEvent.ACTION_DOWN) {
            int dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT
                    : code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : code == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN : 0;
            View f = getCurrentFocus();
            if (dir != 0 && f != null) {
                if (getWindow().getDecorView().dispatchKeyEvent(e)) return true;
                View n = f.focusSearch(dir);
                if (n != null) n.requestFocus(dir);
                return true;
            }
        }
        // Menu key on a focused app tile = hide it (same as long-press).
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
            View f = getCurrentFocus();
            if (f != null && f.isLongClickable()) { f.performLongClick(); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    /** Mutes (or unmutes) the click sound of every view, following the Remote sounds switch. */
    private void applyUiSounds() {
        boolean on = Config.prefs(this).getBoolean(Config.UI_SOUNDS, true);
        java.util.ArrayDeque<View> todo = new java.util.ArrayDeque<>();
        todo.add(getWindow().getDecorView());
        while (!todo.isEmpty()) {
            View v = todo.poll();
            v.setSoundEffectsEnabled(on);
            if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) todo.add(((ViewGroup) v).getChildAt(i));
        }
    }

    private TextView sectionLabel(String s) {
        TextView tv = new TextView(this);
        tv.setText(s.toUpperCase());
        tv.setLetterSpacing(0.16f);
        tv.setTextColor(MUTED);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        tv.setPadding(dp(6), dp(4), 0, dp(1));
        return shadowed(tv);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(6), dp(4), dp(24), dp(4));
        r.setClipChildren(false);
        r.setClipToPadding(false);
        if (glassTheme) {
            // Frosted glass tray around the row: light translucent fill, brighter top, soft white rim.
            GradientDrawable tray = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x59FFFFFF, 0x2EFFFFFF, 0x40FFFFFF});
            tray.setCornerRadius(dp(24));
            tray.setStroke(dp(1), 0x80FFFFFF);
            r.setBackground(tray);
            r.setPadding(dp(12), dp(10), dp(1), dp(10));
        }
        return r;
    }

    private HorizontalScrollView scroller(View child) {
        HorizontalScrollView s = new HorizontalScrollView(this);
        s.setHorizontalScrollBarEnabled(false);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        s.addView(child);
        return s;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private Set<String> hiddenApps() {
        return new HashSet<>(prefs().getStringSet(KEY_HIDDEN, new HashSet<>()));
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
