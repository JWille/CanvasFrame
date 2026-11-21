package com.canvasframe.home;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Calendar panel opened from the clock: a month grid (today highlighted, dots on days with events) beside an
 * "Upcoming" list. The month title is the focus point: → next month, ← previous month (← on the current
 * month leaves the panel, back to the clock).
 */
public class CalendarPanel extends LinearLayout {

    private static final int TEXT = 0xFFF6F1EA, MUTED = 0x9EF6F1EA, ACCENT = 0xFFE8C99A, ON_ACCENT = 0xFF2B2218;
    private static final int LINE = 0x33FFFFFF;

    private final TextView title;
    private final LinearLayout grid, agenda;
    private JSONArray events;
    private int monthOffset = 0;
    private boolean h24;

    public CalendarPanel(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setPadding(dp(18), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x99121820, 0x99182A36, 0x99101820});
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), LINE);
        setBackground(bg);
        setElevation(dp(8));

        // ---- Left: month grid
        LinearLayout left = new LinearLayout(c);
        left.setOrientation(VERTICAL);
        addView(left, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        title = text(14, TEXT, "sans-serif");
        title.setFocusable(true);
        title.setFocusableInTouchMode(true);
        title.setPadding(dp(10), dp(4), dp(10), dp(4));
        title.setOnFocusChangeListener((v, has) -> styleTitle());
        title.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { monthOffset++; render(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && monthOffset > 0) { monthOffset--; render(); return true; }
            return false;   // ← on the current month: let focus go back to the clock
        });
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(title);
        TextView hint = text(9, MUTED, "sans-serif");
        hint.setText("  ◀ ▶ change month");
        head.addView(hint);
        left.addView(head);

        grid = new LinearLayout(c);
        grid.setOrientation(VERTICAL);
        LayoutParams glp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(8);
        left.addView(grid, glp);

        // ---- Right: upcoming list
        agenda = new LinearLayout(c);
        agenda.setOrientation(VERTICAL);
        agenda.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable abg = new GradientDrawable();
        abg.setColor(0x26FFFFFF);
        abg.setCornerRadius(dp(16));
        abg.setStroke(dp(1), LINE);
        agenda.setBackground(abg);
        LayoutParams alp = new LayoutParams(dp(210), ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(14);
        addView(agenda, alp);

        render();
    }

    public View focusTarget() { return title; }

    public void setClock24(boolean h24) { this.h24 = h24; }

    public void setEvents(JSONArray events) {
        this.events = events;
        render();
    }

    /** Back to the current month whenever the panel is reopened. */
    public void resetMonth() {
        if (monthOffset != 0) { monthOffset = 0; render(); }
    }

    // ------------------------------------------------------------------ render

    private void render() {
        Calendar today = Calendar.getInstance();
        Calendar month = Calendar.getInstance();
        month.set(Calendar.DAY_OF_MONTH, 1);
        month.add(Calendar.MONTH, monthOffset);
        title.setText(new SimpleDateFormat("MMMM yyyy", Locale.US).format(month.getTime()));
        styleTitle();

        // Event dots per day of this month (first event's calendar colour).
        Map<Integer, Integer> dots = new HashMap<>();
        if (events != null) {
            for (int i = 0; i < events.length(); i++) {
                JSONObject ev = events.optJSONObject(i);
                if (ev == null) continue;
                Calendar s = Calendar.getInstance();
                s.setTimeInMillis(ev.optLong("s"));
                long end = Math.max(ev.optLong("e"), ev.optLong("s") + 1) - 1;
                while (s.getTimeInMillis() <= end) {
                    if (s.get(Calendar.YEAR) == month.get(Calendar.YEAR) && s.get(Calendar.MONTH) == month.get(Calendar.MONTH)) {
                        int d = s.get(Calendar.DAY_OF_MONTH);
                        if (!dots.containsKey(d)) dots.put(d, parseColor(ev.optString("c")));
                    }
                    s.add(Calendar.DAY_OF_MONTH, 1);
                    s.set(Calendar.HOUR_OF_DAY, 0);
                    s.set(Calendar.MINUTE, 0);
                }
            }
        }

        grid.removeAllViews();
        LinearLayout names = row();
        for (String n : new String[]{"S", "M", "T", "W", "T", "F", "S"}) names.addView(cell(n, MUTED, 9, false, 0, false));
        grid.addView(names);
        int firstDow = month.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
        int days = month.getActualMaximum(Calendar.DAY_OF_MONTH);
        int day = 1 - firstDow;
        for (int w = 0; w < 6 && day <= days; w++) {
            LinearLayout r = row();
            for (int i = 0; i < 7; i++, day++) {
                if (day < 1 || day > days) { r.addView(cell("", TEXT, 11, false, 0, false)); continue; }
                boolean isToday = monthOffset == 0 && day == today.get(Calendar.DAY_OF_MONTH);
                Integer dot = dots.get(day);
                r.addView(cell(String.valueOf(day), TEXT, 11, isToday, dot == null ? 0 : dot, dot != null));
            }
            grid.addView(r);
        }

        renderAgenda();
    }

    private void renderAgenda() {
        agenda.removeAllViews();
        TextView head = text(10, MUTED, "sans-serif");
        head.setText("UPCOMING");
        head.setLetterSpacing(0.14f);
        agenda.addView(head);
        long now = System.currentTimeMillis();
        int shown = 0;
        String lastDay = null;
        if (events != null) {
            for (int i = 0; i < events.length() && shown < 5; i++) {
                JSONObject ev = events.optJSONObject(i);
                if (ev == null) continue;
                boolean allDay = ev.optBoolean("a");
                long s = ev.optLong("s"), e = ev.optLong("e");
                if (allDay ? s + 86_400_000L <= now : Math.max(e, s) <= now) continue;
                String dayLabel = dayLabel(Math.max(s, startOfToday()));
                if (!dayLabel.equals(lastDay)) {
                    TextView d = text(9, ACCENT, "sans-serif-medium");
                    d.setText(dayLabel);
                    d.setPadding(0, dp(7), 0, dp(1));
                    agenda.addView(d);
                    lastDay = dayLabel;
                }
                LinearLayout line = new LinearLayout(getContext());
                line.setGravity(Gravity.CENTER_VERTICAL);
                View dot = new View(getContext());
                GradientDrawable dd = new GradientDrawable();
                dd.setShape(GradientDrawable.OVAL);
                dd.setColor(parseColor(ev.optString("c")));
                dot.setBackground(dd);
                line.addView(dot, new LayoutParams(dp(6), dp(6)));
                TextView t = text(10, TEXT, "sans-serif");
                String when = allDay ? "All day" : new SimpleDateFormat(h24 ? "H:mm" : "h:mm a", Locale.US).format(new Date(s));
                t.setText(when + "  " + ev.optString("t"));
                t.setPadding(dp(6), 0, 0, 0);
                line.addView(t, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                agenda.addView(line);
                shown++;
            }
        }
        if (shown == 0) {
            TextView none = text(10, MUTED, "sans-serif");
            none.setText(events == null ? "Add a calendar in the dashboard\n(Home screen → Calendars)" : "Nothing coming up");
            none.setSingleLine(false);
            none.setPadding(0, dp(8), 0, 0);
            agenda.addView(none);
        }
    }

    private static long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static String dayLabel(long t) {
        long today = startOfToday();
        if (t < today + 86_400_000L) return "Today";
        if (t < today + 2 * 86_400_000L) return "Tomorrow";
        return new SimpleDateFormat("EEE, MMM d", Locale.US).format(new Date(t));
    }

    // ------------------------------------------------------------------ pieces

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(HORIZONTAL);
        return r;
    }

    private View cell(String s, int color, int sp, boolean today, int dotColor, boolean hasDot) {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView t = text(sp, today ? ON_ACCENT : color, today ? "sans-serif-medium" : "sans-serif");
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        if (today) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(ACCENT);
            t.setBackground(g);
        }
        c.addView(t, new LayoutParams(dp(20), dp(20)));
        View dot = new View(getContext());
        if (hasDot) {
            GradientDrawable dd = new GradientDrawable();
            dd.setShape(GradientDrawable.OVAL);
            dd.setColor(dotColor);
            dot.setBackground(dd);
        }
        LayoutParams dl = new LayoutParams(dp(4), dp(4));
        dl.topMargin = dp(1);
        c.addView(dot, dl);
        c.setLayoutParams(new LayoutParams(0, dp(28), 1f));
        return c;
    }

    private void styleTitle() {
        boolean f = title.isFocused();
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(f ? ACCENT : 0x26FFFFFF);
        title.setBackground(g);
        title.setTextColor(f ? ON_ACCENT : TEXT);
    }

    private TextView text(int sp, int color, String family) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setSingleLine(true);
        return t;
    }

    private static int parseColor(String s) {
        try { return Color.parseColor(s); } catch (Exception e) { return ACCENT; }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
