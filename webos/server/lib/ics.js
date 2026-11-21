'use strict';
/*
 * Small iCalendar (ICS) reader, a port of launcher/.../Ics.java: VEVENTs with DTSTART/DTEND/DURATION,
 * SUMMARY, LOCATION, all-day dates, TZID / UTC / floating times, and RRULE expansion (DAILY, WEEKLY with
 * BYDAY, MONTHLY incl. "2TU"/"-1FR", YEARLY) with INTERVAL, COUNT, UNTIL, EXDATE and RECURRENCE-ID
 * overrides. Cancelled events are skipped. Recurrences step in the event's own time zone (DST-correct).
 * Pure Node: test on the PC with `node webos/server/test/ics-test.js`.
 */

const DAY = 86400000;
const WD = { SU: 0, MO: 1, TU: 2, WE: 3, TH: 4, FR: 5, SA: 6 };
const WIN_TZ = {
  'Eastern Standard Time': 'America/New_York', 'Central Standard Time': 'America/Chicago',
  'Mountain Standard Time': 'America/Denver', 'US Mountain Standard Time': 'America/Phoenix',
  'Pacific Standard Time': 'America/Los_Angeles', 'Alaskan Standard Time': 'America/Anchorage',
  'Hawaiian Standard Time': 'Pacific/Honolulu', 'GMT Standard Time': 'Europe/London', 'UTC': 'UTC',
};

// ---------------------------------------------------------------- wall-clock time in a zone

const dtfCache = {};
function zoneFormatter(tz) {
  if (!dtfCache[tz]) {
    dtfCache[tz] = new Intl.DateTimeFormat('en-US', {
      timeZone: tz, hour12: false, year: 'numeric', month: 'numeric', day: 'numeric',
      hour: 'numeric', minute: 'numeric', second: 'numeric',
    });
  }
  return dtfCache[tz];
}

/** Offset (ms) of zone tz from UTC at instant t; null tz = this machine's zone. */
function offsetAt(tz, t) {
  if (!tz) return -new Date(t).getTimezoneOffset() * 60000;
  if (tz === 'UTC') return 0;
  const p = {};
  for (const part of zoneFormatter(tz).formatToParts(new Date(t))) p[part.type] = part.value;
  const h = +p.hour === 24 ? 0 : +p.hour;
  const asUtc = Date.UTC(+p.year, +p.month - 1, +p.day, h, +p.minute, +p.second);
  return asUtc - Math.floor(t / 1000) * 1000;
}

/** Epoch ms for wall-clock fields w = {y, m (0-11), d, h, mi, s} in zone tz. */
function toMs(w, tz) {
  const guess = Date.UTC(w.y, w.m, w.d, w.h, w.mi, w.s);
  let t = guess - offsetAt(tz, guess);
  t = guess - offsetAt(tz, t);
  return t;
}

function norm(w) {   // normalise overflowing fields via UTC arithmetic
  const d = new Date(Date.UTC(w.y, w.m, w.d, w.h, w.mi, w.s));
  return { y: d.getUTCFullYear(), m: d.getUTCMonth(), d: d.getUTCDate(), h: d.getUTCHours(), mi: d.getUTCMinutes(), s: d.getUTCSeconds() };
}
const addDays = (w, n) => norm(Object.assign({}, w, { d: w.d + n }));
const dow = w => new Date(Date.UTC(w.y, w.m, w.d)).getUTCDay();
const daysIn = (y, m) => new Date(Date.UTC(y, m + 1, 0)).getUTCDate();

// ---------------------------------------------------------------- parsing

function valueColon(line) {
  let q = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '"') q = !q;
    else if (ch === ':' && !q) return i;
  }
  return -1;
}
const unquote = s => (s.length >= 2 && s[0] === '"' && s[s.length - 1] === '"' ? s.slice(1, -1) : s);

function zoneFor(id) {
  const m = WIN_TZ[id] || id;
  try { zoneFormatter(m); return m; } catch (e) { return null; }   // unknown zone: local time
}

const isDateOnly = p => (p.params.VALUE || '').toUpperCase() === 'DATE' || p.value.trim().length === 8;

/** {w, tz} or null. All-day and floating times use this machine's zone (tz null). */
function parseDate(p) {
  const v = p.value.trim();
  if (!/^\d{8}/.test(v)) return null;
  const y = +v.slice(0, 4), m = +v.slice(4, 6) - 1, d = +v.slice(6, 8);
  if (v.length === 8) return { w: { y, m, d, h: 0, mi: 0, s: 0 }, tz: null };
  if (v[8] !== 'T') return null;
  const w = { y, m, d, h: +v.slice(9, 11), mi: +v.slice(11, 13), s: v.length >= 15 ? +v.slice(13, 15) || 0 : 0 };
  const tz = v.endsWith('Z') ? 'UTC' : p.params.TZID ? zoneFor(p.params.TZID) : null;
  return { w, tz };
}
const ms = pd => toMs(pd.w, pd.tz);

function parseDuration(v) {   // PT1H30M, P1D, P1W
  let total = 0, num = 0;
  for (const ch of v) {
    if (ch >= '0' && ch <= '9') { num = num * 10 + (+ch); continue; }
    if (ch === 'W') total += num * 7 * DAY;
    else if (ch === 'D') total += num * DAY;
    else if (ch === 'H') total += num * 3600000;
    else if (ch === 'M') total += num * 60000;
    else if (ch === 'S') total += num * 1000;
    num = 0;
  }
  return v.startsWith('-') ? -total : total;
}

function text(p) {
  if (!p) return '';
  return p.value.replace(/\\[nN]/g, ' ').replace(/\\,/g, ',').replace(/\\;/g, ';').replace(/\\\\/g, '\\').trim();
}

/** Occurrences overlapping [from, to) as [{start, end, allDay, title, location, cal}], sorted. */
function parse(src, cal, from, to) {
  const unfolded = src.replace(/\r?\n[ \t]/g, '');
  const events = [];
  let cur = null;
  for (const line of unfolded.split(/\r?\n/)) {
    if (line === 'BEGIN:VEVENT') { cur = { props: {}, exdates: [] }; continue; }
    if (line === 'END:VEVENT') { if (cur) events.push(cur); cur = null; continue; }
    if (!cur) continue;
    const colon = valueColon(line);
    if (colon < 0) continue;
    const head = line.slice(0, colon).split(';');
    const p = { params: {}, value: line.slice(colon + 1) };
    for (let i = 1; i < head.length; i++) {
      const eq = head[i].indexOf('=');
      if (eq > 0) p.params[head[i].slice(0, eq).toUpperCase()] = unquote(head[i].slice(eq + 1));
    }
    const name = head[0].toUpperCase();
    if (name === 'EXDATE') cur.exdates.push(p); else cur.props[name] = p;
  }

  const overridden = {};
  for (const e of events) {
    const rid = e.props['RECURRENCE-ID'], uid = e.props.UID;
    if (!rid || !uid) continue;
    const pd = parseDate(rid);
    if (!pd) continue;
    (overridden[uid.value] = overridden[uid.value] || new Set()).add(ms(pd));
  }

  const out = [];
  for (const e of events) {
    try {
      const st = e.props.STATUS;
      if (st && st.value.toUpperCase() === 'CANCELLED') continue;
      expand(e, cal, from, to, overridden, out);
    } catch (err) { /* skip a bad event */ }
  }
  out.sort((a, b) => a.start - b.start);
  return out;
}

function expand(e, cal, from, to, overridden, out) {
  const ds = e.props.DTSTART;
  if (!ds) return;
  const start = parseDate(ds);
  if (!start) return;
  const startMs = ms(start);
  const allDay = isDateOnly(ds);
  const de = e.props.DTEND, du = e.props.DURATION;
  let dur;
  if (de && parseDate(de)) dur = ms(parseDate(de)) - startMs;
  else if (du) dur = parseDuration(du.value);
  else dur = allDay ? DAY : 0;
  if (dur < 0) dur = 0;

  const title = text(e.props.SUMMARY), loc = text(e.props.LOCATION);
  const skip = new Set();
  for (const x of e.exdates) {
    for (const v of x.value.split(',')) {
      const pd = parseDate({ params: x.params, value: v.trim() });
      if (pd) skip.add(ms(pd));
    }
  }
  const uid = e.props.UID;
  const isOverride = !!e.props['RECURRENCE-ID'];
  if (uid && !isOverride && overridden[uid.value]) overridden[uid.value].forEach(t => skip.add(t));

  const add = t => {
    if (t + Math.max(dur, 1) <= from || t >= to) return;
    out.push({ start: t, end: t + dur, allDay, title, location: loc, cal });
  };
  const rr = e.props.RRULE;
  if (!rr || isOverride) { add(startMs); return; }

  const rule = {};
  for (const part of rr.value.split(';')) {
    const eq = part.indexOf('=');
    if (eq > 0) rule[part.slice(0, eq).toUpperCase()] = part.slice(eq + 1).toUpperCase();
  }
  const freq = rule.FREQ || '';
  const interval = Math.max(1, parseInt(rule.INTERVAL, 10) || 1);
  const count = rule.COUNT ? parseInt(rule.COUNT, 10) : Infinity;
  let until = Infinity;
  if (rule.UNTIL) {
    const u = { params: {}, value: rule.UNTIL };
    if (ds.params.TZID && !u.value.endsWith('Z')) u.params.TZID = ds.params.TZID;
    const uc = parseDate(u);
    if (uc) until = ms(uc) + (isDateOnly(u) ? DAY - 1 : 0);
  }
  const byday = rule.BYDAY;
  const tz = start.tz;
  let emitted = 0, guard = 0;

  if (freq === 'MONTHLY' && byday && /^[-+]?\d+[A-Z]{2}$/.test(byday)) {
    const n = parseInt(byday.slice(0, -2), 10), wd = WD[byday.slice(-2)];
    let y = start.w.y, m = start.w.m;
    while (guard++ < 2000) {
      const occ = nthWeekday(y, m, wd, n, start.w);
      if (occ) {
        const t = toMs(occ, tz);
        if (t >= startMs) {
          if (t > until || emitted >= count || t >= to) break;
          emitted++;
          if (!skip.has(t)) add(t);
        }
      }
      m += interval;
      y += Math.floor(m / 12); m %= 12;
    }
    return;
  }
  if (freq === 'DAILY' || freq === 'YEARLY' || freq === 'MONTHLY') {
    const dom = start.w.d;
    let c = Object.assign({}, start.w);
    while (guard++ < 5000) {
      const t = toMs(c, tz);
      if (t > until || emitted >= count || t >= to) break;
      if (freq !== 'MONTHLY' || c.d === dom) {
        emitted++;
        if (!skip.has(t)) add(t);
      }
      if (freq === 'DAILY') c = addDays(c, interval);
      else if (freq === 'YEARLY') {
        c = Object.assign({}, c, { y: c.y + interval });
        if (c.d > daysIn(c.y, c.m)) c.d = daysIn(c.y, c.m);
      } else {   // MONTHLY on a fixed day: skip months too short for that day
        let m = c.m + interval, y = c.y + Math.floor((c.m + interval) / 12);
        m %= 12;
        c = Object.assign({}, c, { y, m, d: dom <= daysIn(y, m) ? dom : 1 });
      }
    }
    return;
  }
  if (freq === 'WEEKLY') {
    const days = [];
    if (byday) for (const d of byday.split(',')) { const w = WD[d.replace(/[-+\d]/g, '')]; if (w !== undefined) days.push(w); }
    if (!days.length) days.push(dow(start.w));
    days.sort((a, b) => a - b);
    let week = addDays(start.w, -dow(start.w));   // Sunday of the start week, same time of day
    outer:
    while (guard++ < 2000) {
      for (const d of days) {
        const t = toMs(addDays(week, d), tz);
        if (t < startMs) continue;
        if (t > until || emitted >= count || t >= to) break outer;
        emitted++;
        if (!skip.has(t)) add(t);
      }
      week = addDays(week, 7 * interval);
    }
    return;
  }
  add(startMs);
}

/** n-th weekday of month m (n < 0 counts from the end), keeping the time of day; null if absent. */
function nthWeekday(y, m, wd, n, timeOf) {
  const base = { y, m, d: 1, h: timeOf.h, mi: timeOf.mi, s: timeOf.s };
  if (n > 0) {
    const first = (wd - dow(base) + 7) % 7 + 1;
    const d = first + 7 * (n - 1);
    return d <= daysIn(y, m) ? Object.assign(base, { d }) : null;
  }
  const last = daysIn(y, m);
  const lastDow = dow(Object.assign({}, base, { d: last }));
  const d = last - ((lastDow - wd + 7) % 7) + 7 * (n + 1);
  return d >= 1 ? Object.assign(base, { d }) : null;
}

module.exports = { parse, toMs, offsetAt };
