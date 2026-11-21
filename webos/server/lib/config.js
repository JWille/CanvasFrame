'use strict';
/*
 * Home-screen settings shared by the home screen and the dashboard, plus Open-Meteo weather, photo tags
 * (time of day / season / US holidays) and iCal calendars. Port of launcher/.../Config.java; same keys and
 * defaults, so the dashboard works unchanged on both platforms. Stored as JSON files in the data dir.
 */
const fs = require('fs');
const path = require('path');
const https = require('https');
const http = require('http');
const ics = require('./ics');

const PERIODS = ['dawn', 'day', 'dusk', 'night'];
const SEASONS = ['spring', 'summer', 'fall', 'winter'];
const HOLIDAYS = ['newyear', 'valentines', 'stpatricks', 'easter', 'memorial', 'july4', 'halloween', 'thanksgiving', 'christmas'];
const DAY = 86400000;

const DEFAULTS = {
  weather_on: true, weather_place: '', weather_lat: '', weather_lon: '', units: 'F', clock: '12',
  show_dash: true, hide_no_signal: false, photo_min: 10, auto_hide_sec: 15, controls_always: false, theme: 'frame',
  hidden_apps: [], photo_tags: {}, calendars: [], open_on_wake: true, app_names: {}, devices: [], peers: [], device_name: '',
  art_dim: 40,   // screen saver: % lower backlight at night (half at dawn/dusk), 0 = off
  art_fade_min: 30,
  mesh_photos: true,
  light_dim: false,   // dim by the room light (LG's light sensor; turns on LG's AI Brightness) instead of the clock; per TV
  light_offset: 0,
  light_src: 'auto',  // 'auto' (own sensor, else a TV in this room), 'self', or another TV's device id    // -50..+50: shift the room-light reading (+ = treat the room as brighter = dim less)
  ui_sounds: true,   // remote sounds on the CanvasFrame home screen (also needs the TV's own sound effects on); per TV   // receive photos from the other TVs in the mesh   // at night, fade on to the minimum after the screen saver has run this long, 0 = never
};

function create(dataDir, onChange) {
  const file = name => path.join(dataDir, name);
  const read = (name, def) => { try { return JSON.parse(fs.readFileSync(file(name), 'utf8')); } catch (e) { return def; } };
  const write = (name, obj) => { const t = file(name) + '.tmp'; fs.writeFileSync(t, JSON.stringify(obj)); fs.renameSync(t, file(name)); };

  let settings = Object.assign({}, DEFAULTS, read('settings.json', {}));
  let weather = read('weather.json', null);
  let calCache = read('calendar.json', { fetched: 0, events: [], status: {} });
  let version = Date.now();
  const bump = () => { version++; if (onChange) onChange(); };
  const save = () => { write('settings.json', settings); bump(); };

  const c = {
    PERIODS, SEASONS, HOLIDAYS,
    get: () => settings,
    version: () => version,
    bump,
    weather: () => weather,
    events: () => calCache.events || [],

    /** Settings for the dashboard (same shape as the Android app's /api/settings). */
    toJson(labelFor) {
      const o = {};
      for (const k of Object.keys(DEFAULTS)) if (k !== 'photo_tags' && k !== 'calendars' && k !== 'app_names' && k !== 'devices' && k !== 'peers') o[k] = settings[k];
      o.hidden_apps = settings.hidden_apps.map(id => ({ pkg: id, label: labelFor ? labelFor(id) : id }));
      if (weather) o.weather = weather;
      o.calendars = settings.calendars.map(cal => {
        const host = cal.url.replace(/^https?:\/\//, '').split('/')[0];
        return { id: cal.id, name: cal.name, color: cal.color, on: cal.on !== false,
          link: host + '/…' + cal.url.slice(-8), status: (calCache.status || {})[cal.id] || null };
      });
      const now = Date.now();
      o.upcoming = c.events().filter(ev => ev.e > now || (ev.a && ev.s + DAY > now)).slice(0, 8);
      return o;
    },

    /** Partial update from the dashboard. Returns true when the weather should be refetched. */
    update(inp) {
      let refetch = false;
      if ('weather_on' in inp) { settings.weather_on = !!inp.weather_on; refetch = true; }
      if ('weather_lat' in inp && 'weather_lon' in inp) {
        const lat = +inp.weather_lat, lon = +inp.weather_lon;
        if (!isFinite(lat) || !isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new Error('bad coordinates');
        settings.weather_lat = String(lat); settings.weather_lon = String(lon);
        settings.weather_place = String(inp.weather_place || '').trim();
        refetch = true;
      }
      if (inp.clear_location) { settings.weather_lat = settings.weather_lon = settings.weather_place = ''; weather = null; write('weather.json', null); }
      if ('units' in inp) { settings.units = inp.units === 'C' ? 'C' : 'F'; refetch = true; }
      if ('clock' in inp) settings.clock = inp.clock === '24' ? '24' : '12';
      if ('show_dash' in inp) settings.show_dash = !!inp.show_dash;
      if ('hide_no_signal' in inp) settings.hide_no_signal = !!inp.hide_no_signal;
      if ('photo_min' in inp) settings.photo_min = Math.max(1, Math.min(240, parseInt(inp.photo_min, 10) || 10));
      if ('controls_always' in inp) settings.controls_always = !!inp.controls_always;
      if ('auto_hide_sec' in inp) settings.auto_hide_sec = Math.max(0, Math.min(600, parseInt(inp.auto_hide_sec, 10) || 0));
      if ('theme' in inp) settings.theme = inp.theme === 'glass' ? 'glass' : 'frame';
      if ('open_on_wake' in inp) settings.open_on_wake = !!inp.open_on_wake;
      if ('art_dim' in inp) settings.art_dim = Math.max(0, Math.min(90, parseInt(inp.art_dim, 10) || 0));
      if ('ui_sounds' in inp) settings.ui_sounds = !!inp.ui_sounds;
      if ('light_dim' in inp) settings.light_dim = !!inp.light_dim;
      if ('light_src' in inp) settings.light_src = String(inp.light_src || 'auto').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 40) || 'auto';
      if ('light_offset' in inp) settings.light_offset = Math.max(-50, Math.min(50, parseInt(inp.light_offset, 10) || 0));
      if ('mesh_photos' in inp) settings.mesh_photos = !!inp.mesh_photos;
      if ('art_fade_min' in inp) settings.art_fade_min = Math.max(0, Math.min(480, parseInt(inp.art_fade_min, 10) || 0));
      if ('device_name' in inp) settings.device_name = String(inp.device_name || '').trim().slice(0, 40);
      if ('unhide' in inp) settings.hidden_apps = settings.hidden_apps.filter(x => x !== inp.unhide);
      if ('hide' in inp && settings.hidden_apps.indexOf(inp.hide) < 0) settings.hidden_apps.push(String(inp.hide));
      if (inp.rename && inp.rename.id) {
        const n = String(inp.rename.name || '').trim().slice(0, 40);
        settings.app_names = Object.assign({}, settings.app_names);
        if (n) settings.app_names[inp.rename.id] = n; else delete settings.app_names[inp.rename.id];
      }
      save();
      return refetch;
    },

    // ------------------------------------------------------------ other TVs (Roku etc.) managed from here
    devices: () => settings.devices || [],
    setDevices(list) { settings.devices = list; save(); },
    /** Other CanvasFrame TVs for "Sync to other TVs" (holds their API keys: never sent to browsers). */
    setPeers(list) { settings.peers = list; save(); },

    // ------------------------------------------------------------ photo tags
    tagsFor: name => settings.photo_tags[name] || [],
    setTags(name, tags) {
      const clean = [].concat(PERIODS, SEASONS, HOLIDAYS).filter(t => tags && tags.indexOf(t) >= 0);
      if (clean.length) settings.photo_tags[name] = clean; else delete settings.photo_tags[name];
      save();
    },
    currentSeason() {
      const m = new Date().getMonth();
      return m >= 2 && m <= 4 ? 'spring' : m >= 5 && m <= 7 ? 'summer' : m >= 8 && m <= 10 ? 'fall' : 'winter';
    },
    currentHolidays() {
      const now = new Date(), y = now.getFullYear(), m = now.getMonth() + 1, d = now.getDate();
      const doy = dayOfYear(now), out = [];
      if ((m === 12 && d === 31) || (m === 1 && d <= 2)) out.push('newyear');
      if (m === 2 && d >= 10 && d <= 14) out.push('valentines');
      if (m === 3 && d >= 14 && d <= 17) out.push('stpatricks');
      if (m === 7 && d <= 4) out.push('july4');
      if (m === 10 && d >= 15) out.push('halloween');
      if (m === 12 && d <= 26) out.push('christmas');
      if (within(doy, dayOfYear(easter(y)), -7, 1)) out.push('easter');
      if (within(doy, dayOfYear(nthWeekday(y, 4, 1, -1)), -3, 0)) out.push('memorial');
      if (within(doy, dayOfYear(nthWeekday(y, 10, 4, 4)), -7, 1)) out.push('thanksgiving');
      return out;
    },
    /** dawn = sunrise -45m … +60m, dusk = sunset -60m … +45m (from the weather data), else fixed hours. */
    currentPeriod() {
      const now = new Date(), mins = now.getHours() * 60 + now.getMinutes();
      let rise, set;
      try {
        rise = minutesOfDay(weather.daily.sunrise[0]);
        set = minutesOfDay(weather.daily.sunset[0]);
      } catch (e) {
        if (mins >= 300 && mins < 480) return 'dawn';
        if (mins >= 480 && mins < 1020) return 'day';
        if (mins >= 1020 && mins < 1200) return 'dusk';
        return 'night';
      }
      if (mins >= rise - 45 && mins < rise + 60) return 'dawn';
      if (mins >= rise + 60 && mins < set - 60) return 'day';
      if (mins >= set - 60 && mins < set + 45) return 'dusk';
      return 'night';
    },
    fitsNow(tags) {
      if (!tags.length) return true;
      const p = c.currentPeriod(), s = c.currentSeason(), hol = c.currentHolidays();
      let hasP = false, okP = false, hasS = false, okS = false, hasH = false, okH = false;
      for (const t of tags) {
        if (PERIODS.indexOf(t) >= 0) { hasP = true; okP = okP || t === p; }
        else if (SEASONS.indexOf(t) >= 0) { hasS = true; okS = okS || t === s; }
        else if (HOLIDAYS.indexOf(t) >= 0) { hasH = true; okH = okH || hol.indexOf(t) >= 0; }
      }
      return (!hasP || okP) && (!hasS || okS) && (!hasH || okH);
    },
    isHolidayPick(tags) { const hol = c.currentHolidays(); return tags.some(t => hol.indexOf(t) >= 0); },
    renamePhoto(from, to) {
      if (settings.photo_tags[from]) { settings.photo_tags[to] = settings.photo_tags[from]; delete settings.photo_tags[from]; save(); }
    },

    // ------------------------------------------------------------ weather
    async fetchWeather() {
      const s = settings;
      if (!s.weather_on || !s.weather_lat || !s.weather_lon) return;
      const f = s.units !== 'C';
      const url = `https://api.open-meteo.com/v1/forecast?latitude=${s.weather_lat}&longitude=${s.weather_lon}` +
        '&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m' +
        '&hourly=temperature_2m,weather_code,is_day' +
        '&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,daylight_duration' +
        '&forecast_days=7&timezone=auto' + (f ? '&temperature_unit=fahrenheit&wind_speed_unit=mph' : '');
      const w = JSON.parse(await get(url));
      w.fetched = Date.now();
      w.unit = f ? 'F' : 'C';
      weather = w;
      write('weather.json', w);
      bump();
    },
    async geocode(q) {
      const r = JSON.parse(await get('https://geocoding-api.open-meteo.com/v1/search?count=6&language=en&format=json&name=' + encodeURIComponent(q)));
      return (r.results || []).map(g => {
        let name = g.name;
        if (g.admin1 && g.admin1 !== g.name) name += ', ' + g.admin1;
        if (g.country_code) name += ', ' + g.country_code;
        return { name, lat: g.latitude, lon: g.longitude };
      });
    },

    // ------------------------------------------------------------ calendars
    updateCalendars(inp) {
      const cals = settings.calendars;
      if (inp.action === 'add') {
        let url = String(inp.url || '').trim();
        if (url.startsWith('webcal://')) url = 'https://' + url.slice(9);
        if (!/^https?:\/\//.test(url)) throw new Error('Calendar link must start with https:// or webcal://');
        const color = /^#[0-9A-Fa-f]{6}$/.test(inp.color || '') ? inp.color : '#E8C99A';
        cals.push({ id: Date.now().toString(36), name: String(inp.name || '').trim() || 'Calendar ' + (cals.length + 1), url, color, on: true });
      } else if (inp.action === 'remove') {
        settings.calendars = cals.filter(x => x.id !== inp.id);
      } else if (inp.action === 'toggle') {
        for (const x of cals) if (x.id === inp.id) x.on = 'on' in inp ? !!inp.on : !(x.on !== false);
      } else if (inp.action === 'replace') {
        // Sync from another TV: take its calendar list as a whole.
        settings.calendars = (Array.isArray(inp.calendars) ? inp.calendars : []).filter(c => c && /^https?:\/\//.test(String(c.url || '')))
          .map((c, i) => ({ id: Date.now().toString(36) + i, name: String(c.name || 'Calendar').slice(0, 60), url: String(c.url),
            color: /^#[0-9A-Fa-f]{6}$/.test(c.color || '') ? c.color : '#E8C99A', on: c.on !== false }));
      } else if (inp.action !== 'refresh') throw new Error('unknown action');
      save();
    },
    async fetchCalendars() {
      const now = Date.now(), from = now - DAY, to = now + 63 * DAY;
      const all = [], status = {};
      for (let i = 0; i < settings.calendars.length; i++) {
        const cal = settings.calendars[i];
        if (cal.on === false) continue;
        const st = { fetched: now };
        try {
          const evs = ics.parse(await get(cal.url), i, from, to);
          all.push.apply(all, evs);
          st.ok = true; st.count = evs.length;
        } catch (e) { st.ok = false; st.error = e.message; }
        status[cal.id] = st;
      }
      all.sort((a, b) => a.start - b.start);
      calCache = {
        fetched: now, status,
        events: all.map(e => { const cal = settings.calendars[e.cal]; return { s: e.start, e: e.end, a: e.allDay, t: e.title, l: e.location, c: cal.color || '#E8C99A', n: cal.name }; }),
      };
      write('calendar.json', calCache);
      bump();
    },
  };
  return c;
}

function within(doy, center, before, after) { return doy >= center + before && doy <= center + after; }
function dayOfYear(d) { return Math.round((new Date(d.getFullYear(), d.getMonth(), d.getDate()) - new Date(d.getFullYear(), 0, 1)) / DAY) + 1; }
function easter(y) {
  const a = y % 19, b = Math.floor(y / 100), c = y % 100, d = Math.floor(b / 4), e = b % 4, f = Math.floor((b + 8) / 25);
  const g = Math.floor((b - f + 1) / 3), h = (19 * a + b - d - g + 15) % 30, i = Math.floor(c / 4), k = c % 4;
  const l = (32 + 2 * e + 2 * i - h - k) % 7, m = Math.floor((a + 11 * h + 22 * l) / 451);
  const month = Math.floor((h + l - 7 * m + 114) / 31), day = ((h + l - 7 * m + 114) % 31) + 1;
  return new Date(y, month - 1, day);
}
/** n-th weekday (0 = Sunday) of month (0-11); n = -1 for the last one. */
function nthWeekday(y, month, weekday, n) {
  if (n > 0) {
    const d = new Date(y, month, 1);
    while (d.getDay() !== weekday) d.setDate(d.getDate() + 1);
    d.setDate(d.getDate() + 7 * (n - 1));
    return d;
  }
  const d = new Date(y, month + 1, 0);
  while (d.getDay() !== weekday) d.setDate(d.getDate() - 1);
  return d;
}
function minutesOfDay(iso) { const t = iso.slice(iso.indexOf('T') + 1); return +t.slice(0, 2) * 60 + +t.slice(3, 5); }

/** GET a URL as text (follows redirects; 20 s timeout). */
function get(url, redirects) {
  redirects = redirects || 0;
  return new Promise((resolve, reject) => {
    const mod = url.startsWith('https:') ? https : http;
    const req = mod.get(url, { headers: { 'User-Agent': 'CanvasFrame' }, timeout: 20000 }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects < 5) {
        res.resume();
        resolve(get(new URL(res.headers.location, url).toString(), redirects + 1));
        return;
      }
      if (res.statusCode >= 400) { res.resume(); reject(new Error(`HTTP ${res.statusCode} from ${new URL(url).host}`)); return; }
      const chunks = [];
      res.on('data', d => chunks.push(d));
      res.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('timed out')));
    req.on('error', reject);
  });
}

module.exports = { create, get };
