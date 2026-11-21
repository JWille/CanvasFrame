package com.canvasframe.home;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Forecast panel shown in the middle of the home screen while the weather card (or this panel) has focus.
 * Hourly / Weekly tabs, a row of forecast pills, a "Tomorrow" strip, and sunrise / sunset / day length.
 */
public class ForecastPanel extends LinearLayout {

    private static final int TEXT = 0xFFF6F1EA, MUTED = 0x9EF6F1EA, ACCENT = 0xFFE8C99A, ON_ACCENT = 0xFF2B2218;
    private static final int PILL = 0x1FFFFFFF, PILL_LINE = 0x33FFFFFF;

    private final TextView hourlyTab, weeklyTab, radarTab;
    private final LinearLayout pills, tomorrowBox, sunCol;
    private final RadarView radar;
    private boolean radarMode = false;
    private final TextView tomorrowLabel, tomorrowCond, tomorrowTemp, tomorrowIcon;
    private final TextView sunrise, sunriseAmPm, sunset, sunsetAmPm, dayLength;
    private JSONObject weather;
    private boolean weekly = false;
    private boolean h24 = false;

    public ForecastPanel(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setPadding(dp(18), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x99121820, 0x99182A36, 0x99101820});
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), PILL_LINE);
        setBackground(bg);
        setElevation(dp(8));
        setClipChildren(false);
        setClipToPadding(false);

        // ---- Left: tabs, pills, tomorrow
        LinearLayout left = new LinearLayout(c);
        left.setOrientation(VERTICAL);
        left.setClipChildren(false);
        left.setClipToPadding(false);
        addView(left, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout tabs = new LinearLayout(c);
        tabs.setOrientation(HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        hourlyTab = tab("Hourly");
        weeklyTab = tab("Weekly");
        radarTab = tab("Radar");
        hourlyTab.setOnClickListener(v -> setMode(0));
        weeklyTab.setOnClickListener(v -> setMode(1));
        radarTab.setOnClickListener(v -> setMode(2));
        // Moving between tabs switches the view too, so the remote needs no extra OK press.
        hourlyTab.setOnFocusChangeListener((v, has) -> { styleTabs(); if (has) setMode(0); });
        weeklyTab.setOnFocusChangeListener((v, has) -> { styleTabs(); if (has) setMode(1); });
        radarTab.setOnFocusChangeListener((v, has) -> { styleTabs(); if (has) setMode(2); });
        tabs.addView(hourlyTab);
        tabs.addView(slash());
        tabs.addView(weeklyTab);
        tabs.addView(slash());
        tabs.addView(radarTab);
        left.addView(tabs);

        radar = new RadarView(c);
        radar.setVisibility(GONE);
        LayoutParams rlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        left.addView(radar, rlp);

        pills = new LinearLayout(c);
        pills.setOrientation(HORIZONTAL);
        LayoutParams plp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(10);
        left.addView(pills, plp);

        LinearLayout tomorrow = tomorrowBox = new LinearLayout(c);
        tomorrow.setOrientation(HORIZONTAL);
        tomorrow.setGravity(Gravity.CENTER_VERTICAL);
        tomorrow.setPadding(dp(14), dp(8), dp(14), dp(8));
        tomorrow.setBackground(rounded(PILL, 14, PILL_LINE));
        LinearLayout tl = new LinearLayout(c);
        tl.setOrientation(VERTICAL);
        tomorrowLabel = text(11, TEXT, "sans-serif");
        tomorrowLabel.setText("Tomorrow");
        tomorrowCond = text(9, MUTED, "sans-serif");
        tl.addView(tomorrowLabel);
        tl.addView(tomorrowCond);
        tomorrow.addView(tl);
        tomorrowTemp = text(26, TEXT, "sans-serif-light");
        tomorrowTemp.setPadding(dp(14), 0, 0, 0);
        tomorrow.addView(tomorrowTemp, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tomorrowIcon = text(28, TEXT, "sans-serif");
        tomorrow.addView(tomorrowIcon);
        LayoutParams tlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(12);
        left.addView(tomorrow, tlp);

        // ---- Right: sun column
        LinearLayout sun = sunCol = new LinearLayout(c);
        sun.setOrientation(VERTICAL);
        sun.setPadding(dp(14), dp(10), dp(14), dp(10));
        sun.setGravity(Gravity.CENTER_VERTICAL);
        sun.setBackground(rounded(0x26FFFFFF, 16, PILL_LINE));
        sun.addView(label("Sunrise"));
        TextView[] sr = timeRow(sun);
        sunrise = sr[0];
        sunriseAmPm = sr[1];
        sun.addView(label("Sunset"));
        TextView[] ss = timeRow(sun);
        sunset = ss[0];
        sunsetAmPm = ss[1];
        sun.addView(label("Length of day"));
        dayLength = text(17, TEXT, "sans-serif");
        sun.addView(dayLength);
        LayoutParams slp = new LayoutParams(dp(132), ViewGroup.LayoutParams.MATCH_PARENT);
        slp.leftMargin = dp(14);
        addView(sun, slp);

        styleTabs();
    }

    public View firstFocus() { return radarMode ? radarTab : weekly ? weeklyTab : hourlyTab; }
    public View hourlyTab() { return hourlyTab; }
    public View weeklyTab() { return weeklyTab; }
    public View radarTab() { return radarTab; }
    public boolean isRadarShowing() { return radarMode && isShown(); }

    public void setClock24(boolean h24) { this.h24 = h24; radar.setClock24(h24); }

    public void setWeather(JSONObject w) {
        this.weather = w;
        if (w != null) radar.setLocation(w.optDouble("latitude", Double.NaN), w.optDouble("longitude", Double.NaN));
        render();
    }

    /** 0 = Hourly, 1 = Weekly, 2 = Radar (the map replaces the pills, tomorrow strip and sun column). */
    private void setMode(int m) {
        boolean r = m == 2, wk = m == 1;
        if (r == radarMode && (r || wk == weekly)) return;
        radarMode = r;
        if (!r) weekly = wk;
        pills.setVisibility(r ? GONE : VISIBLE);
        tomorrowBox.setVisibility(r ? GONE : VISIBLE);
        sunCol.setVisibility(r ? GONE : VISIBLE);
        radar.setVisibility(r ? VISIBLE : GONE);
        styleTabs();
        if (!r) render();
    }

    private TextView slash() {
        TextView s = text(13, MUTED, "sans-serif-light");
        s.setText("/");
        s.setPadding(dp(2), 0, dp(2), 0);
        return s;
    }

    // ------------------------------------------------------------------ render

    private void render() {
        pills.removeAllViews();
        if (weather == null || weather.optJSONObject("hourly") == null) {
            TextView t = text(11, MUTED, "sans-serif");
            t.setText(weather == null ? "Set a location in the dashboard" : "Loading forecast…");
            pills.addView(t);
            return;
        }
        try {
            JSONObject daily = weather.getJSONObject("daily");
            if (weekly) renderWeek(daily); else renderHours(weather.getJSONObject("hourly"));

            // Tomorrow
            JSONArray codes = daily.getJSONArray("weather_code");
            if (codes.length() > 1) {
                String[] d = Config.describe(codes.getInt(1), true);
                tomorrowCond.setText(d[1]);
                tomorrowTemp.setText(Config.fmtTemp(daily.getJSONArray("temperature_2m_max").getDouble(1))
                        + "  " + Config.fmtTemp(daily.getJSONArray("temperature_2m_min").getDouble(1)));
                tomorrowIcon.setText(d[0]);
            }

            // Sun
            setTime(sunrise, sunriseAmPm, daily.getJSONArray("sunrise").getString(0));
            setTime(sunset, sunsetAmPm, daily.getJSONArray("sunset").getString(0));
            long secs = Math.round(daily.getJSONArray("daylight_duration").getDouble(0));
            dayLength.setText((secs / 3600) + "h " + (secs % 3600 / 60) + "m");
        } catch (Exception e) {
            TextView t = text(11, MUTED, "sans-serif");
            t.setText("Forecast unavailable");
            pills.addView(t);
        }
    }

    private void renderHours(JSONObject hourly) throws Exception {
        JSONArray times = hourly.getJSONArray("time");
        JSONArray temps = hourly.getJSONArray("temperature_2m");
        JSONArray codes = hourly.getJSONArray("weather_code");
        JSONArray day = hourly.getJSONArray("is_day");
        // Start at the current hour (times are local to the forecast location).
        String now = weather.getJSONObject("current").getString("time").substring(0, 13);
        int start = 0;
        for (int i = 0; i < times.length(); i++) {
            if (times.getString(i).substring(0, 13).compareTo(now) >= 0) { start = i; break; }
        }
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        SimpleDateFormat out = new SimpleDateFormat(h24 ? "HH:00" : "ha", Locale.US);
        for (int i = start; i < Math.min(start + 8, times.length()); i++) {
            String label = i == start ? "Now" : out.format(in.parse(times.getString(i)));
            String[] d = Config.describe(codes.getInt(i), day.getInt(i) == 1);
            pills.addView(pill(label, d[0], Config.fmtTemp(temps.getDouble(i)), null));
        }
    }

    private void renderWeek(JSONObject daily) throws Exception {
        JSONArray times = daily.getJSONArray("time");
        JSONArray hi = daily.getJSONArray("temperature_2m_max");
        JSONArray lo = daily.getJSONArray("temperature_2m_min");
        JSONArray codes = daily.getJSONArray("weather_code");
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.US);
        for (int i = 0; i < Math.min(7, times.length()); i++) {
            String label = i == 0 ? "Today" : out.format(in.parse(times.getString(i)));
            String[] d = Config.describe(codes.getInt(i), true);
            pills.addView(pill(label, d[0], Config.fmtTemp(hi.getDouble(i)), Config.fmtTemp(lo.getDouble(i))));
        }
    }

    private void setTime(TextView big, TextView ampm, String iso) throws ParseException {
        Date d = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(iso);
        if (h24) {
            big.setText(new SimpleDateFormat("H:mm", Locale.US).format(d));
            ampm.setText("");
        } else {
            big.setText(new SimpleDateFormat("h:mm", Locale.US).format(d));
            ampm.setText(new SimpleDateFormat("a", Locale.US).format(d));
        }
    }

    // ------------------------------------------------------------------ pieces

    private View pill(String top, String icon, String temp, String low) {
        LinearLayout p = new LinearLayout(getContext());
        p.setOrientation(VERTICAL);
        p.setGravity(Gravity.CENTER_HORIZONTAL);
        p.setPadding(0, dp(7), 0, dp(7));
        p.setBackground(rounded(PILL, 14, PILL_LINE));
        TextView a = text(9, TEXT, "sans-serif");
        a.setText(top);
        TextView b = text(18, TEXT, "sans-serif");
        b.setText(icon);
        b.setPadding(0, dp(4), 0, dp(4));
        TextView c = text(11, TEXT, "sans-serif");
        c.setText(temp);
        a.setGravity(Gravity.CENTER);
        b.setGravity(Gravity.CENTER);
        c.setGravity(Gravity.CENTER);
        p.addView(a);
        p.addView(b);
        p.addView(c);
        if (low != null) {
            TextView l = text(9, MUTED, "sans-serif");
            l.setText(low);
            l.setGravity(Gravity.CENTER);
            p.addView(l);
        }
        LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, dp(6), 0);
        p.setLayoutParams(lp);
        return p;
    }

    private TextView tab(String s) {
        TextView t = text(14, TEXT, "sans-serif");
        t.setText(s);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setClickable(true);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        return t;
    }

    private void styleTabs() {
        for (TextView t : new TextView[]{hourlyTab, weeklyTab, radarTab}) {
            boolean active = radarMode ? t == radarTab : t != radarTab && (t == weeklyTab) == weekly;
            boolean focused = t.isFocused();
            t.setBackground(focused ? rounded(ACCENT, 10, ACCENT)
                    : active ? rounded(0x26FFFFFF, 10, 0x00000000) : null);
            t.setTextColor(focused ? ON_ACCENT : active ? TEXT : MUTED);
        }
    }

    private TextView label(String s) {
        TextView t = text(10, MUTED, "sans-serif");
        t.setText(s);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private TextView[] timeRow(LinearLayout parent) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.BOTTOM);
        TextView big = text(20, TEXT, "sans-serif");
        TextView small = text(10, MUTED, "sans-serif");
        small.setPadding(dp(5), 0, 0, dp(3));
        r.addView(big);
        r.addView(small);
        parent.addView(r);
        return new TextView[]{big, small};
    }

    private TextView text(int sp, int color, String family) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setSingleLine(true);
        return t;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if ((stroke >>> 24) != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
