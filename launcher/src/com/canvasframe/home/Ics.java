package com.canvasframe.home;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Small iCalendar (ICS) reader: VEVENTs with DTSTART/DTEND/DURATION, SUMMARY, LOCATION, all-day dates,
 * TZID / UTC / floating times, and RRULE expansion (DAILY, WEEKLY with BYDAY, MONTHLY incl. "2TU"/"-1FR",
 * YEARLY) with INTERVAL, COUNT, UNTIL, EXDATE and RECURRENCE-ID overrides. Cancelled events are skipped.
 */
final class Ics {
    private Ics() { }

    static final class Ev {
        long start, end;
        boolean allDay;
        String title = "", location = "";
        int cal;
    }

    private static final class Prop {
        final Map<String, String> params = new HashMap<>();
        String value = "";
    }

    private static final class VEvent {
        final Map<String, Prop> props = new HashMap<>();
        final List<Prop> exdates = new ArrayList<>();
    }

    /** Parse ICS text and return occurrences overlapping [from, to), tagged with calendar index cal. */
    static List<Ev> parse(String text, int cal, long from, long to) {
        String unfolded = text.replace("\r\n ", "").replace("\r\n\t", "").replace("\n ", "").replace("\n\t", "");
        List<VEvent> events = new ArrayList<>();
        VEvent cur = null;
        for (String line : unfolded.split("\r?\n")) {
            if (line.equals("BEGIN:VEVENT")) { cur = new VEvent(); continue; }
            if (line.equals("END:VEVENT")) { if (cur != null) events.add(cur); cur = null; continue; }
            if (cur == null) continue;
            int colon = valueColon(line);
            if (colon < 0) continue;
            String[] head = line.substring(0, colon).split(";");
            Prop p = new Prop();
            p.value = line.substring(colon + 1);
            for (int i = 1; i < head.length; i++) {
                int eq = head[i].indexOf('=');
                if (eq > 0) p.params.put(head[i].substring(0, eq).toUpperCase(Locale.US), unquote(head[i].substring(eq + 1)));
            }
            String name = head[0].toUpperCase(Locale.US);
            if (name.equals("EXDATE")) cur.exdates.add(p); else cur.props.put(name, p);
        }

        // Moved/edited single occurrences (RECURRENCE-ID) replace that date in their series.
        Map<String, Set<Long>> overridden = new HashMap<>();
        for (VEvent e : events) {
            Prop rid = e.props.get("RECURRENCE-ID");
            Prop uid = e.props.get("UID");
            if (rid == null || uid == null) continue;
            Calendar c = parseDate(rid);
            if (c == null) continue;
            Set<Long> s = overridden.get(uid.value);
            if (s == null) overridden.put(uid.value, s = new HashSet<>());
            s.add(c.getTimeInMillis());
        }

        List<Ev> out = new ArrayList<>();
        for (VEvent e : events) {
            try {
                Prop status = e.props.get("STATUS");
                if (status != null && status.value.equalsIgnoreCase("CANCELLED")) continue;
                expand(e, cal, from, to, overridden, out);
            } catch (Exception ignored) { }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    private static void expand(VEvent e, int cal, long from, long to, Map<String, Set<Long>> overridden, List<Ev> out) {
        Prop ds = e.props.get("DTSTART");
        if (ds == null) return;
        Calendar start = parseDate(ds);
        if (start == null) return;
        boolean allDay = isDateOnly(ds);
        long dur;
        Prop de = e.props.get("DTEND"), du = e.props.get("DURATION");
        if (de != null && parseDate(de) != null) dur = parseDate(de).getTimeInMillis() - start.getTimeInMillis();
        else if (du != null) dur = parseDuration(du.value);
        else dur = allDay ? 86_400_000L : 0;
        if (dur < 0) dur = 0;

        String title = text(e.props.get("SUMMARY")), loc = text(e.props.get("LOCATION"));
        Set<Long> skip = new HashSet<>();
        for (Prop x : e.exdates) {
            for (String v : x.value.split(",")) {
                Prop one = new Prop();
                one.params.putAll(x.params);
                one.value = v.trim();
                Calendar c = parseDate(one);
                if (c != null) skip.add(c.getTimeInMillis());
            }
        }
        Prop uid = e.props.get("UID");
        boolean isOverride = e.props.containsKey("RECURRENCE-ID");
        if (uid != null && !isOverride && overridden.containsKey(uid.value)) skip.addAll(overridden.get(uid.value));

        Prop rr = e.props.get("RRULE");
        if (rr == null || isOverride) {
            add(out, start.getTimeInMillis(), dur, allDay, title, loc, cal, from, to);
            return;
        }

        Map<String, String> rule = new HashMap<>();
        for (String part : rr.value.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) rule.put(part.substring(0, eq).toUpperCase(Locale.US), part.substring(eq + 1).toUpperCase(Locale.US));
        }
        String freq = rule.getOrDefault("FREQ", "");
        int interval = Math.max(1, parseInt(rule.get("INTERVAL"), 1));
        int count = parseInt(rule.get("COUNT"), Integer.MAX_VALUE);
        long until = Long.MAX_VALUE;
        if (rule.containsKey("UNTIL")) {
            Prop u = new Prop();
            u.value = rule.get("UNTIL");
            if (ds.params.containsKey("TZID") && !u.value.endsWith("Z")) u.params.put("TZID", ds.params.get("TZID"));
            Calendar uc = parseDate(u);
            if (uc != null) until = uc.getTimeInMillis() + (isDateOnly(u) ? 86_399_999L : 0);
        }
        String byday = rule.get("BYDAY");

        int emitted = 0, guard = 0;
        Calendar c = (Calendar) start.clone();
        switch (freq) {
            case "DAILY":
            case "YEARLY":
            case "MONTHLY":
                if (freq.equals("MONTHLY") && byday != null && byday.matches("[-+]?\\d+[A-Z]{2}")) {
                    int n = Integer.parseInt(byday.substring(0, byday.length() - 2));
                    int wd = weekday(byday.substring(byday.length() - 2));
                    Calendar month = (Calendar) start.clone();
                    month.set(Calendar.DAY_OF_MONTH, 1);
                    while (guard++ < 2000) {
                        Calendar occ = nthWeekday(month, wd, n, start);
                        if (occ != null && occ.getTimeInMillis() >= start.getTimeInMillis()) {
                            long t = occ.getTimeInMillis();
                            if (t > until || emitted >= count || t >= to) break;
                            emitted++;
                            if (!skip.contains(t)) add(out, t, dur, allDay, title, loc, cal, from, to);
                        }
                        month.add(Calendar.MONTH, interval);
                    }
                    break;
                }
                int dom = start.get(Calendar.DAY_OF_MONTH);
                while (guard++ < 5000) {
                    long t = c.getTimeInMillis();
                    if (t > until || emitted >= count || t >= to) break;
                    boolean valid = !freq.equals("MONTHLY") || c.get(Calendar.DAY_OF_MONTH) == dom;
                    if (valid) {
                        emitted++;
                        if (!skip.contains(t)) add(out, t, dur, allDay, title, loc, cal, from, to);
                    }
                    if (freq.equals("DAILY")) c.add(Calendar.DAY_OF_MONTH, interval);
                    else if (freq.equals("YEARLY")) c.add(Calendar.YEAR, interval);
                    else {   // MONTHLY on a fixed day: skip months too short for that day
                        c.set(Calendar.DAY_OF_MONTH, 1);
                        c.add(Calendar.MONTH, interval);
                        if (dom <= c.getActualMaximum(Calendar.DAY_OF_MONTH)) c.set(Calendar.DAY_OF_MONTH, dom);
                    }
                }
                break;
            case "WEEKLY": {
                List<Integer> days = new ArrayList<>();
                if (byday != null) for (String d : byday.split(",")) {
                    int w = weekday(d.replaceAll("[-+\\d]", ""));
                    if (w > 0) days.add(w);
                }
                if (days.isEmpty()) days.add(start.get(Calendar.DAY_OF_WEEK));
                Collections.sort(days);
                Calendar week = (Calendar) start.clone();
                week.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
                if (week.after(start)) week.add(Calendar.DAY_OF_MONTH, -7);
                outer:
                while (guard++ < 2000) {
                    for (int d : days) {
                        Calendar occ = (Calendar) week.clone();
                        occ.add(Calendar.DAY_OF_MONTH, d - Calendar.SUNDAY);
                        long t = occ.getTimeInMillis();
                        if (t < start.getTimeInMillis()) continue;
                        if (t > until || emitted >= count || t >= to) break outer;
                        emitted++;
                        if (!skip.contains(t)) add(out, t, dur, allDay, title, loc, cal, from, to);
                    }
                    week.add(Calendar.DAY_OF_MONTH, 7 * interval);
                }
                break;
            }
            default:
                add(out, start.getTimeInMillis(), dur, allDay, title, loc, cal, from, to);
        }
    }

    private static void add(List<Ev> out, long t, long dur, boolean allDay, String title, String loc, int cal, long from, long to) {
        if (t + Math.max(dur, 1) <= from || t >= to) return;
        Ev ev = new Ev();
        ev.start = t;
        ev.end = t + dur;
        ev.allDay = allDay;
        ev.title = title;
        ev.location = loc;
        ev.cal = cal;
        out.add(ev);
    }

    /** n-th weekday of the month (n &lt; 0 counts from the end), keeping the start's time of day. */
    private static Calendar nthWeekday(Calendar month, int weekday, int n, Calendar timeOf) {
        Calendar c = (Calendar) month.clone();
        c.set(Calendar.HOUR_OF_DAY, timeOf.get(Calendar.HOUR_OF_DAY));
        c.set(Calendar.MINUTE, timeOf.get(Calendar.MINUTE));
        c.set(Calendar.SECOND, timeOf.get(Calendar.SECOND));
        c.set(Calendar.MILLISECOND, 0);
        int max = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        if (n > 0) {
            c.set(Calendar.DAY_OF_MONTH, 1);
            while (c.get(Calendar.DAY_OF_WEEK) != weekday) c.add(Calendar.DAY_OF_MONTH, 1);
            c.add(Calendar.DAY_OF_MONTH, 7 * (n - 1));
            return c.get(Calendar.MONTH) == month.get(Calendar.MONTH) ? c : null;
        } else {
            c.set(Calendar.DAY_OF_MONTH, max);
            while (c.get(Calendar.DAY_OF_WEEK) != weekday) c.add(Calendar.DAY_OF_MONTH, -1);
            c.add(Calendar.DAY_OF_MONTH, 7 * (n + 1));
            return c.get(Calendar.MONTH) == month.get(Calendar.MONTH) ? c : null;
        }
    }

    private static int weekday(String s) {
        switch (s) {
            case "SU": return Calendar.SUNDAY;
            case "MO": return Calendar.MONDAY;
            case "TU": return Calendar.TUESDAY;
            case "WE": return Calendar.WEDNESDAY;
            case "TH": return Calendar.THURSDAY;
            case "FR": return Calendar.FRIDAY;
            case "SA": return Calendar.SATURDAY;
            default: return -1;
        }
    }

    private static boolean isDateOnly(Prop p) {
        return "DATE".equalsIgnoreCase(p.params.get("VALUE")) || p.value.trim().length() == 8;
    }

    /** DATE (all-day, local midnight), UTC "…Z", TZID-qualified, or floating local time. */
    static Calendar parseDate(Prop p) {
        String v = p.value.trim();
        try {
            if (v.length() == 8) {
                Calendar c = Calendar.getInstance();
                c.clear();
                c.set(Integer.parseInt(v.substring(0, 4)), Integer.parseInt(v.substring(4, 6)) - 1, Integer.parseInt(v.substring(6, 8)));
                return c;
            }
            boolean utc = v.endsWith("Z");
            TimeZone tz = utc ? TimeZone.getTimeZone("UTC")
                    : p.params.containsKey("TZID") ? zone(p.params.get("TZID")) : TimeZone.getDefault();
            Calendar c = Calendar.getInstance(tz);
            c.clear();
            c.set(Integer.parseInt(v.substring(0, 4)), Integer.parseInt(v.substring(4, 6)) - 1, Integer.parseInt(v.substring(6, 8)),
                    Integer.parseInt(v.substring(9, 11)), Integer.parseInt(v.substring(11, 13)),
                    v.length() >= 15 ? Integer.parseInt(v.substring(13, 15)) : 0);
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    /** IANA ids, plus the Windows names Outlook/Exchange use. */
    private static TimeZone zone(String id) {
        String m;
        switch (id) {
            case "Eastern Standard Time": m = "America/New_York"; break;
            case "Central Standard Time": m = "America/Chicago"; break;
            case "Mountain Standard Time": m = "America/Denver"; break;
            case "US Mountain Standard Time": m = "America/Phoenix"; break;
            case "Pacific Standard Time": m = "America/Los_Angeles"; break;
            case "Alaskan Standard Time": m = "America/Anchorage"; break;
            case "Hawaiian Standard Time": m = "Pacific/Honolulu"; break;
            case "UTC": case "GMT Standard Time": m = id.equals("UTC") ? "UTC" : "Europe/London"; break;
            default: m = id;
        }
        TimeZone tz = TimeZone.getTimeZone(m);
        return (tz.getID().equals("GMT") && !m.equals("GMT")) ? TimeZone.getDefault() : tz;
    }

    private static long parseDuration(String v) {   // e.g. PT1H30M, P1D, P1W
        long ms = 0, num = 0;
        boolean neg = v.startsWith("-");
        for (char ch : v.toCharArray()) {
            if (Character.isDigit(ch)) { num = num * 10 + (ch - '0'); continue; }
            switch (ch) {
                case 'W': ms += num * 7 * 86_400_000L; break;
                case 'D': ms += num * 86_400_000L; break;
                case 'H': ms += num * 3_600_000L; break;
                case 'M': ms += num * 60_000L; break;
                case 'S': ms += num * 1000L; break;
                default: break;
            }
            num = 0;
        }
        return neg ? -ms : ms;
    }

    private static String text(Prop p) {
        if (p == null) return "";
        return p.value.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim();
    }

    private static int valueColon(String line) {
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') q = !q;
            else if (ch == ':' && !q) return i;
        }
        return -1;
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private static int parseInt(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
