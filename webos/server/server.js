'use strict';
/*
 * CanvasFrame for LG webOS: home screen + dashboard server. Runs as root from a webosbrew init.d hook.
 *
 *   /            dashboard (shared with the Android app: launcher/assets/dashboard.html)
 *   /home        the TV home screen, opened by the com.canvasframe.home wrapper app
 *   /api/...     same API as the Android app, plus /api/home* for the home screen
 *
 * Flags: --boot (open the home screen once the TV is ready), --reset-auth (remove all authenticators).
 * Node 12 (the TV's /usr/bin/node): no optional chaining / ?? in this file.
 */
const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const tv = require('./lib/tv');
const Config = require('./lib/config');
const Auth = require('./lib/auth');
const Privacy = require('./lib/privacy');
const Glasshouse = require('./lib/glasshouse');
const LgSettings = require('./lib/lgsettings');
const Metrics = require('./lib/metrics');
const Apps = require('./lib/apps');
const Network = require('./lib/network');
const Screensaver = require('./lib/screensaver');
const Roku = require('./lib/roku');
const Sync = require('./lib/sync');
const Site = require('./lib/site');
const Storage = require('./lib/storage');
const RoomLight = require('./lib/roomlight');
const Wol = require('./lib/wol');
const PcLink = require('./lib/pclink');
const Doorbell = require('./lib/doorbell');
const Ble = require('./lib/ble');
const Signals = require('./lib/signals');
const RoomLog = require('./lib/roomlog');
const { samePhoto } = Site;
const Tls = require('./lib/tls');

const DATA = process.env.CF_DATA || '/media/developer/canvasframe';
const PHOTOS = path.join(DATA, 'photos');
const THUMBS = path.join(PHOTOS, '.thumbs');
const MAX_UPLOAD = 40 * 1024 * 1024;
const ARGS = process.argv.slice(2);
// Changes when the server restarts (e.g. after an update): the home page reloads to pick up new code.
const BUILD = String(Date.now());
for (const d of [DATA, PHOTOS, THUMBS]) { try { fs.mkdirSync(d, { recursive: true }); } catch (e) { /* exists */ } }

const LOG = path.join(DATA, 'server.log');
function log(m) {
  try {
    if (fs.existsSync(LOG) && fs.statSync(LOG).size > 256 * 1024) fs.renameSync(LOG, LOG + '.old');
    fs.appendFileSync(LOG, new Date().toISOString() + ' ' + m + '\n');
  } catch (e) { /* ignore */ }
}

const auth = Auth.create(path.join(DATA, 'auth.json'));
if (ARGS.indexOf('--reset-auth') >= 0) {
  auth.reset();
  // Tell a running server to re-read auth.json (and remove the shared authenticators everywhere), or it would keep (and later
  // save) the old state. With no server running, mark them deleted in site.json directly.
  try { process.kill(+fs.readFileSync('/tmp/canvasframe.pid', 'utf8').trim(), 'SIGUSR2'); }
  catch (e) { try { Site.tombstoneAuthFile(DATA); } catch (x) { /* no site yet */ } }
  console.log('CanvasFrame dashboard sign-in reset: all authenticators removed');
  process.exit(0);
}

let pid = null;
try { pid = fs.readFileSync('/tmp/canvasframe.pid', 'utf8').trim(); } catch (e) { /* none */ }
try { if (pid && pid !== String(process.pid)) process.kill(+pid, 'SIGTERM'); } catch (e) { /* not running */ }
fs.writeFileSync('/tmp/canvasframe.pid', String(process.pid));

const cfg = Config.create(DATA, () => {});
const gh = Glasshouse.create(DATA, log);
const privacy = Privacy.create(DATA, tv.luna, log);
const lgs = LgSettings.create(tv.luna);
const pointer = require('./lib/pointer').create({ dataDir: DATA, luna: tv.luna, log });   // Magic Remote: pointer on motion or only on shake
/** The TV settings rows plus CanvasFrame's own Magic Remote row (pointer wake-up), first in the Magic Remote section. */
function withPointer(out) {
  let sec = out.sections.find(x => x.id === 'remote');
  if (!sec) { sec = { id: 'remote', title: 'Magic Remote', rows: [] }; out.sections.push(sec); }
  sec.rows.unshift(pointer.row());
  return out;
}
const metrics = Metrics.create();
const appMgr = Apps.create(DATA, tv, log);
const saver = Screensaver.create(DATA, tv.luna, cfg, PHOTOS, log);
const pcLink = PcLink.create({ dataDir: DATA, tv, lgs, saver, log });   // the PC that uses this TV as its screen
const network = Network.create(tv);
const storage = Storage.create(tv.luna, log);
// Room light from LG's light sensor; "Dim by room light" uses it instead of the clock (screen saver and OLED idle dimming).
const roomLight = RoomLight.create(DATA, tv.luna, cfg, log);
saver.setRoomLight(roomLight);
roomLight.setOnChange(() => saver.nudge());
roomLight.setPeers(() => site.lightSources());   // "Light from" another TV (set once site exists, below)
roomLight.start();
// Remote sounds on the home screen: LG's own interface sounds (/usr/share/audiod/systemsounds, name = file name without
// "-ondemand.pcm"), played through LG's feedback sink. Only while CanvasFrame's switch and the TV's own "Sound effects"
// setting are both on (rechecked every minute). Presses that arrive while two sounds are still starting are dropped.
// OLED panels: the idle home screen darkens the photo itself at night (each OLED pixel makes its own light, so a darker
// picture really uses less power and wears the panel less). Only on OLEDs: the LCDs' screen savers dim their backlight.
let isOled = null, homeDim = { dim: 0, at: 0 };
tv.status(DATA).then(s => { isOled = /OLED/i.test(String(s.model || '')); }).catch(() => {});
// Weather for CanvasFrame's screen saver (its QML fetches this over loopback every few minutes): plain text, no emoji
// (the QML fonts have none). Same wording as the home screen's weather card.
const WMO_TEXT = { 0: 'Clear', 1: 'Mostly clear', 2: 'Partly cloudy', 3: 'Overcast', 45: 'Fog', 48: 'Fog', 51: 'Drizzle', 53: 'Drizzle',
  55: 'Drizzle', 56: 'Freezing drizzle', 57: 'Freezing drizzle', 61: 'Light rain', 63: 'Rain', 65: 'Heavy rain', 66: 'Freezing rain',
  67: 'Freezing rain', 71: 'Light snow', 73: 'Snow', 75: 'Heavy snow', 77: 'Snow grains', 80: 'Showers', 81: 'Showers',
  82: 'Violent showers', 85: 'Snow showers', 86: 'Snow showers', 95: 'Thunderstorm', 96: 'Storm with hail', 99: 'Storm with hail' };
function saverWeather() {
  const s = cfg.get(), w = s.weather_on !== false && s.weather_place ? cfg.weather() : null;
  if (!w || !w.current) return { on: false };
  const c = w.current, d = w.daily || {}, deg = v => (isFinite(v) ? Math.round(v) + '°' : '');
  return { on: true, temp: deg(c.temperature_2m), cond: WMO_TEXT[c.weather_code] || '', feels: deg(c.apparent_temperature),
    hi: deg((d.temperature_2m_max || [])[0]), lo: deg((d.temperature_2m_min || [])[0]),
    place: String(s.weather_place || '').split(',')[0], humidity: isFinite(c.relative_humidity_2m) ? Math.round(c.relative_humidity_2m) + '%' : '' };
}
const UI_SOUNDS = { move: 'generic-left-right', ok: '4_app-click', open: 'alert-open', close: 'alert-close' };
let lgSoundFx = { on: true, at: 0 }, soundsPlaying = 0;
async function uiSound(kind) {
  const name = UI_SOUNDS[kind];
  if (!name || cfg.get().ui_sounds === false || soundsPlaying >= 2) return;
  if (Date.now() - lgSoundFx.at > 60000) {
    lgSoundFx.at = Date.now();
    const r = await tv.luna('com.webos.service.settings/getSystemSettings', { category: 'sound', keys: ['soundEffect'] }).catch(() => null);
    lgSoundFx.on = !(r && r.settings && r.settings.soundEffect === 'off');
  }
  if (!lgSoundFx.on) return;
  soundsPlaying++;
  require('child_process').execFile('/usr/bin/luna-send', ['-n', '1', 'luna://com.webos.service.audio/systemsounds/playFeedback',
    JSON.stringify({ name, sink: 'pfeedback', play: true, override: true })], { timeout: 3000 }, () => { soundsPlaying--; });
}   // free space, removable LG extras (store demo videos)
const sync = Sync.create(cfg, PHOTOS, log, tv.lanIp,
  async () => cfg.get().device_name || (await tv.status(DATA).catch(() => ({}))).model || 'another TV');
// A message for the home screen (e.g. "Synced from …"), picked up by its home/ver poll.
let notice = null;
saver.start().catch(e => log('screensaver start: ' + e.message));
// Port: CF_PORT, else the one saved by a takeover/restore, else 8080 unless Glasshouse still owns it.
const PORT_FILE = path.join(DATA, 'port');
const PORT = (() => {
  if (process.env.CF_PORT) return parseInt(process.env.CF_PORT, 10);
  const saved = parseInt((() => { try { return fs.readFileSync(PORT_FILE, 'utf8'); } catch (e) { return ''; } })(), 10);
  if (saved) return saved;
  const g = gh.detect();
  return g.present && !g.takenOver ? 8081 : 8080;
})();
// HTTPS next to it: 9443 (9444 while on 8081). Plain HTTP from other devices is sent here once it's listening.
const TLS_PORT = PORT + 1363;
let tlsInfo = null;   // { fingerprint } once the HTTPS server is listening

// ---- the shared site (lib/site.js): every TV's identity, rooms and live state, replicated over the Sync peers
// Home-screen settings shared across the mesh (TV name, theme choice per TV? no: theme is shared too; dimming stays per TV).
const MESH_SETTINGS = ['weather_on', 'weather_place', 'weather_lat', 'weather_lon', 'units', 'clock', 'show_dash', 'hide_no_signal',
  'auto_hide_sec', 'controls_always', 'photo_min', 'theme'];
const site = Site.create(DATA, sync, cfg, log, {
  status: async () => { const s = await tv.status(DATA).catch(() => ({})); return { name: cfg.get().device_name || s.model, model: s.model, brand: 'lg' }; },
  urls: () => [tlsInfo ? 'https://' + tv.lanIp() + ':' + TLS_PORT : 'http://' + tv.lanIp() + ':' + PORT],
  caps: () => {   // own screen saver? takes mesh photos? MACs for Wake-on-LAN
    const m = Wol.macs(), mac = m[m.using === 'eth' ? 'eth' : 'wifi'] || m.wifi || m.eth;
    // Sensors for the Sync tab's chips: light = on while it streams (AI Brightness), idle otherwise; the Magic Remote's
    // push-to-talk mic is never used by CanvasFrame.
    const ls = roomLight.status(), sensors = { mic: 'unused' };
    if (ls.present !== false && ls.ai != null) sensors.light = roomLight.env() ? 'on' : 'idle';
    return Object.assign({ saver: saver.status().supported, photosIn: cfg.get().mesh_photos !== false, sensors }, mac ? { mac, macs: m } : {});
  },
  auth: { list: () => auth.syncList(), add: a => auth.addSynced(a), remove: aid => auth.remove(aid) },   // shared authenticators
  mesh: {   // shared with the other TVs in the mesh (lib/site.js)
    photos: () => { try { return fs.readdirSync(PHOTOS).filter(n => isImage(n) && n[0] !== '.'); } catch (e) { return []; } },
    readPhoto: n => fs.readFileSync(path.join(PHOTOS, path.basename(n))),
    deletePhoto: n => { n = path.basename(n); try { fs.unlinkSync(path.join(PHOTOS, n)); } catch (e) { /* gone */ }
      try { fs.unlinkSync(path.join(THUMBS, n + '.jpg')); } catch (e) { /* none */ }
      cfg.setTags(n, null); if (rot.cur === n) pickPhoto(); cfg.bump(); saver.refresh(); },
    tagsFor: n => cfg.tagsFor(n),
    setTags: (n, t) => { cfg.setTags(n, t); cfg.bump(); },
    settings: () => { const s = cfg.get(), o = {}; for (const k of MESH_SETTINGS) o[k] = s[k]; return o; },
    applySettings: v => { const o = {}; for (const k of MESH_SETTINGS) if (k in v) o[k] = v[k];
      if (o.weather_lat === '' || o.weather_lat == null) { delete o.weather_lat; delete o.weather_lon; delete o.weather_place; }
      if (cfg.update(o)) cfg.fetchWeather().catch(e => log('weather: ' + e.message));
      saver.refresh(); log('site: home-screen settings taken from the mesh'); },
    calendars: () => cfg.get().calendars || [],
    applyCalendars: l => { cfg.updateCalendars({ action: 'replace', calendars: l }); cfg.fetchCalendars().catch(() => {}); log('site: calendars taken from the mesh'); },
  },   // can run CanvasFrame's own screen saver (never on webOS 10)
  photos: () => { try { return fs.readdirSync(PHOTOS).filter(n => /\.(jpe?g|png|webp)$/i.test(n) && n[0] !== '.'); } catch (e) { return []; } },
});
/** This TV's live state for the site, about once a minute. */
async function siteEnv() {
  const [pw, fg, pic] = await Promise.all([tv.luna('com.webos.service.tvpower/power/getPowerState'),
    tv.luna('com.webos.applicationManager/getForegroundAppInfo'),
    tv.luna('com.webos.service.settings/getSystemSettings', { category: 'picture', keys: ['backlight'] })]);
  const st = pw && pw.state, app = (fg && fg.appId) || '', mode = saver.status().mode;
  // What's on screen, for the dashboards' "what each TV shows" wall.
  let shows;
  const plan = site.syncPlan();
  if (st === 'Screen Saver') {
    if (mode === 'photos' && saver.active()) shows = plan ? { kind: 'photo', sync: true } : { kind: 'saver', label: 'Photo screen saver' };
    else if (mode === 'clock' && saver.active()) shows = { kind: 'saver', label: 'Clock screen saver' };
    else shows = { kind: 'saver', label: "LG's screen saver" };
  } else if (st !== 'Active') shows = { kind: 'off' };
  else if (app === tv.HOME_APP) shows = { kind: 'home', photo: rot.cur || '', sync: !!plan, dim: Date.now() - homeDim.at < 90000 ? homeDim.dim : 0 };
  else {
    const s = await tv.status(DATA).catch(() => ({}));
    shows = { kind: /hdmi|livetv/.test(app) ? 'input' : 'app', label: s.foreground || app };
  }
  return { screen: st === 'Active' ? 'on' : st === 'Screen Saver' ? 'saver' : 'off', app,
    backlight: pic && pic.settings && isFinite(pic.settings.backlight) ? +pic.settings.backlight : null, saver: mode, shows,
    roomLight: roomLight.env(),   // {lum, pct} from LG's light sensor while AI Brightness runs it, else null
    ...(() => { const st = photoStorage(); return { free: st.free, total: st.total, photoBytes: st.used }; })() };
}
/** A command from the site (any TV's dashboard). "identify": a small system toast with this TV's name and room. */
async function siteCommand(c, v) {
  if (c.action === 'copyphotos') {   // copy the photos TV c.to is missing, over this TV's link to it (no on-screen messages)
    const pid = site.peerFor(c.to);
    if (!pid) { log('site: copy photos: no link to that TV from here'); return; }
    const r = await sync.run([pid], { photos: true, show: false });
    log('site: copy photos: ' + JSON.stringify(r).slice(0, 200));
    return;
  }
  if (c.action === 'doorbell') { door.command(c); return; }
  if (c.action === 'blescan') { const until = Math.min(+c.until || 0, Date.now() + 130000); site.fast(until); ble.search(until); return; }
  if (c.action === 'signout') {   // "Sign out on all TVs" from another TV
    const n = auth.revokeChain(String(c.chain || ''));
    if (n) log('auth: signed out ' + n + ' session(s) (sign out on all TVs)');
    return;
  }
  if (c.action !== 'identify') return;
  const it = k => (v.items[k] && !v.items[k].x && v.items[k].d) || {};
  const place = it('place:' + site.id), name = it('dev:' + site.id).name || cfg.get().device_name || 'This TV';
  const room = place.room ? it('room:' + place.room).name : '';
  await tv.luna('com.webos.notification/createToast', { sourceId: 'com.canvasframe.home', message: name + (room ? ' · ' + room : ' · no room set') });
  log('site: identify');
}
saver.setNearby(() => site.nearby());
saver.setSyncPlan(() => site.syncPlan());
site.start(siteEnv, siteCommand, () => { saver.nudge(); saver.syncChanged(); });
privacy.start().catch(e => log('privacy start: ' + e.message));

/** Start a fresh copy of this server (it takes over from this one) and exit. */
function restartSelf(delay) {
  setTimeout(() => {
    const { spawn } = require('child_process');
    spawn(process.execPath, [path.join(__dirname, 'server.js')], { cwd: __dirname, detached: true, stdio: 'ignore' }).unref();
    setTimeout(() => process.exit(0), 300);
  }, delay || 800);
}
log('start pid ' + process.pid + ' port ' + PORT + ' args ' + ARGS.join(' '));

// ================================================================ photos & rotation

const isImage = n => /\.(jpe?g|png|webp|gif)$/i.test(n) && n[0] !== '.';
function photoList() {
  try {
    return fs.readdirSync(PHOTOS).filter(isImage).map(n => {
      const st = fs.statSync(path.join(PHOTOS, n));
      return { name: n, size: st.size, mtime: st.mtimeMs };
    }).sort((a, b) => b.mtime - a.mtime);
  } catch (e) { return []; }
}
function photoFile(name) {
  const base = path.basename(String(name || ''));
  const f = path.join(PHOTOS, base);
  if (!base || !isImage(base) || !fs.existsSync(f)) throw new Error('No such photo');
  return f;
}

const STATE = path.join(DATA, 'state.json');
let rot;
try { rot = JSON.parse(fs.readFileSync(STATE, 'utf8')); } catch (e) { rot = { cur: null, time: 0, manual: false }; }
const saveRot = () => { try { fs.writeFileSync(STATE, JSON.stringify(rot)); } catch (e) { /* ignore */ } cfg.bump(); };

/** Random photo that fits right now; holiday-tagged ones first; none fit → plain background (null). */
function pickPhoto() {
  const all = photoList().map(p => p.name);
  let pool = all.filter(n => cfg.fitsNow(cfg.tagsFor(n)));
  const hol = pool.filter(n => cfg.isHolidayPick(cfg.tagsFor(n)));
  if (hol.length) pool = hol;
  if (pool.length > 1) pool = pool.filter(n => n !== rot.cur);
  rot = { cur: pool.length ? pool[Math.floor(Math.random() * pool.length)] : null, time: Date.now(), manual: false };
  saveRot();
}
function rotationTick() {
  // Same photo as the other TVs (Rooms & TVs -> Screen savers together): the shared schedule, checked every 5 s.
  // A "Show now" pick stays until the next change.
  const plan = site.syncPlan();
  if (plan) {
    const slot = Math.floor(Date.now() / plan.ms), name = plan.names[samePhoto(slot, plan.names.length)];
    const pickedThisSlot = rot.manual && Math.floor(rot.time / plan.ms) === slot;
    if (!pickedThisSlot && rot.cur !== name && fs.existsSync(path.join(PHOTOS, name))) { rot = { cur: name, time: Date.now(), manual: false }; saveRot(); }
    return;
  }
  const interval = cfg.get().photo_min * 60000;
  const exists = rot.cur && fs.existsSync(path.join(PHOTOS, rot.cur));
  const due = Date.now() - rot.time >= interval;
  if (!exists && (rot.cur || due || !rot.time)) return pickPhoto();
  if (due) return pickPhoto();
  if (exists && !rot.manual && !cfg.fitsNow(cfg.tagsFor(rot.cur))) return pickPhoto();
  if (!rot.cur && photoList().some(p => cfg.fitsNow(cfg.tagsFor(p.name)))) return pickPhoto();
}
setInterval(() => { if (site.syncPlan()) rotationTick(); }, 5000);
setInterval(rotationTick, 30000);
setTimeout(rotationTick, 2000);

/** Streams an upload to disk under a safe, unique name. */
function saveUpload(req, rawName, len) {
  return new Promise((resolve, reject) => {
    if (!len) return reject(new Error('empty upload'));
    if (len > MAX_UPLOAD) return reject(new Error('photo is larger than 40 MB'));
    let base = decodeURIComponent(rawName || 'photo.jpg').replace(/[\\/]/g, '_').replace(/[^\w.\- ()]/g, '_').replace(/^\.+/, '');
    if (!isImage(base)) {
      if (/\.(heic|heif)$/i.test(base)) return reject(new Error("HEIC photos can't be shown on this TV; export them as JPEG"));
      base = base.replace(/\.[^.]*$/, '') + '.jpg';
    }
    let name = base, i = 2;
    while (fs.existsSync(path.join(PHOTOS, name))) name = base.replace(/(\.[^.]+)$/, `-${i++}$1`);
    const tmp = path.join(DATA, 'upload-' + process.pid + '-' + Date.now());
    const out = fs.createWriteStream(tmp);
    let got = 0;
    req.on('data', d => { got += d.length; if (got > MAX_UPLOAD) req.destroy(); });
    req.pipe(out);
    out.on('finish', () => {
      if (got !== len) { fs.unlink(tmp, () => {}); return reject(new Error('upload incomplete')); }
      fs.renameSync(tmp, path.join(PHOTOS, name));
      resolve(name);
    });
    out.on('error', e => { fs.unlink(tmp, () => {}); reject(e); });
    req.on('error', e => { fs.unlink(tmp, () => {}); reject(e); });
  });
}

function photosJson() {
  return {
    photos: photoList().map(p => {
      const tags = cfg.tagsFor(p.name);
      return { name: p.name, size: p.size, tags, fitsNow: cfg.fitsNow(tags), holidayPick: cfg.isHolidayPick(tags), preview: 0 };
    }),
    sdcardCount: 0, period: cfg.currentPeriod(), season: cfg.currentSeason(), holidays: cfg.currentHolidays(),
    storage: photoStorage(),
  };
}

/** Space the photos take (with thumbnails) and what's left on that partition (Node 12 has no fs.statfs: busybox df). */
function photoStorage() {
  let used = 0;
  for (const d of [PHOTOS, THUMBS]) {
    try { for (const n of fs.readdirSync(d)) { try { const st = fs.statSync(path.join(d, n)); if (st.isFile()) used += st.size; } catch (e) { /* gone */ } } }
    catch (e) { /* missing */ }
  }
  let free = null, total = null;
  try {
    const out = require('child_process').execFileSync('df', ['-k', PHOTOS], { timeout: 3000 }).toString().trim().split('\n');
    const t = out.slice(1).join(' ').trim().split(/\s+/);   // a long device name can wrap onto its own line
    const avail = +t[t.length - 3], size = +t[t.length - 5];
    if (isFinite(avail)) free = avail * 1024;
    if (isFinite(size)) total = size * 1024;
  } catch (e) { /* unknown */ }
  return { used, free, total };
}

// ================================================================ weather / calendar refresh

async function refresh() {
  try { await cfg.fetchWeather(); } catch (e) { log('weather: ' + e.message); }
  try { await cfg.fetchCalendars(); } catch (e) { log('calendars: ' + e.message); }
}
setInterval(refresh, 15 * 60000);
setTimeout(refresh, 3000);

// ================================================================ HTTP helpers

function send(res, code, type, body, headers) {
  const b = Buffer.isBuffer(body) ? body : Buffer.from(body);
  res.writeHead(code, Object.assign({ 'Content-Type': type, 'Content-Length': b.length, 'Cache-Control': 'no-store' }, headers || {}));
  res.end(b);
}
const json = (res, code, obj, headers) => send(res, code, 'application/json', JSON.stringify(obj), headers);
function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let n = 0;
    req.on('data', d => { n += d.length; if (n > limit) { reject(new Error('body too large')); req.destroy(); } else chunks.push(d); });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}
function cookie(header, name) {
  for (const part of String(header || '').split(';')) {
    const eq = part.indexOf('=');
    if (eq > 0 && part.slice(0, eq).trim() === name) return part.slice(eq + 1).trim();
  }
  return null;
}
// Cookies set over HTTPS are marked Secure so a browser never sends them over plain HTTP.
const sessionCookie = (t, secure) => `${auth.COOKIE}=${t}; Path=/; Max-Age=${auth.SESSION_MS / 1000}; HttpOnly; SameSite=Strict${secure ? '; Secure' : ''}`;
/** The host part of a Host header ("10.0.0.5:8080" -> "10.0.0.5"). */
const hostOnly = h => { h = String(h || '').trim(); if (!h || !/^[A-Za-z0-9.:%\[\]-]+$/.test(h)) return tv.lanIp();
  if (h[0] === '[') return h.slice(0, h.indexOf(']') + 1) || tv.lanIp(); return h.split(':')[0]; };
const peerAddr = req => String(req.socket.remoteAddress || '').replace(/^::ffff:/, '');
const isLoopback = a => a === '127.0.0.1' || a === '::1';
const isLan = a => isLoopback(a) || /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.|fe80:|fd|fc)/i.test(a);
const MIME = { '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp', '.gif': 'image/gif' };
function sendFile(res, f, type) {
  fs.stat(f, (err, st) => {
    if (err) return json(res, 404, { error: 'not found' });
    res.writeHead(200, { 'Content-Type': type || MIME[path.extname(f).toLowerCase()] || 'application/octet-stream',
      'Content-Length': st.size, 'Cache-Control': 'max-age=86400' });
    fs.createReadStream(f).pipe(res);
  });
}
function asset(name) { return fs.readFileSync(path.join(__dirname, name)); }

/** POST /api/wol {id} (a TV in the shared site) or {mac, ip}: sends a Wake-on-LAN packet from this TV. */
async function wolSend(body) {
  let mac = String(body.mac || ''), ip = String(body.ip || ''), name = '';
  if (body.id) {
    const it = (site.view(false).items || {})['dev:' + body.id], d = it && !it.x ? it.d : null;
    if (!d) throw new Error('Unknown TV');
    mac = d.mac || ''; name = d.name || '';
    try { ip = new URL((d.urls || [])[0]).hostname; } catch (e) { /* no address */ }
    if (!mac) throw new Error(name + " hasn't shared its network address yet (it needs a CanvasFrame update)");
  }
  const sent = await Wol.send(mac, ip);
  return { ok: true, sent, result: 'Wake-up sent' + (name ? ' to ' + name : '') };
}

/** CanvasFrame Link with a hint appended (same as DashboardService.pcApp): JSON {url, name, fp} of this TV, its length
 *  (4 bytes, little-endian) and "CFLINK01". Windows ignores bytes after the program image. */
let pcExe = null;
async function pcApp(hostHeader) {
  if (!pcExe) pcExe = fs.readFileSync(path.join(__dirname, 'pcapp', 'CanvasFrameLink.exe'));
  let name = cfg.get().device_name;
  if (!name) name = (await tv.status(DATA).catch(() => ({}))).model || 'LG TV';
  const js = Buffer.from(JSON.stringify({ url: 'https://' + hostOnly(hostHeader) + ':' + TLS_PORT, name, fp: tlsInfo ? tlsInfo.fingerprint : '' }), 'utf8');
  const len = Buffer.alloc(4); len.writeUInt32LE(js.length, 0);
  return Buffer.concat([pcExe, js, len, Buffer.from('CFLINK01', 'ascii')]);
}

const FEATURES = { sync: true, appToggle: false, art: false, preview: false, privacy: 'webos', clientResize: true, sdcard: false, tvSettings: true, appManage: true, screensaver: true, serviceMenu: true, site: true, storage: true, pcLink: true };

// ================================================================ home screen data

async function labelFor(id) {
  const lp = (await tv.launchPoints()).find(x => x.id === id);
  return lp ? lp.title : id;
}

async function homeData() {
  const s = cfg.get();
  const [apps, inputs] = await Promise.all([tv.apps().catch(() => []), tv.inputs().catch(() => [])]);
  return {
    version: cfg.version(),
    settings: { clock: s.clock, units: s.units, weather_on: s.weather_on, weather_place: s.weather_place, show_dash: s.show_dash,
      hide_no_signal: s.hide_no_signal, auto_hide_sec: s.auto_hide_sec, controls_always: !!s.controls_always, theme: s.theme, photo_min: s.photo_min },
    weather: s.weather_place ? cfg.weather() : null,
    events: s.calendars.length ? cfg.events() : null,
    photo: rot.cur ? { name: rot.cur, at: rot.time } : null,
    period: cfg.currentPeriod(),
    apps: apps.filter(a => s.hidden_apps.indexOf(a.id) < 0).map(a => Object.assign({}, a, { title: (s.app_names || {})[a.id] || a.title })),
    inputs: inputs.filter(i => i.connected || !s.hide_no_signal),
    dash: tlsInfo ? 'https://' + tv.lanIp() + ':' + TLS_PORT : 'http://' + tv.lanIp() + ':' + PORT,
  };
}

const door = Doorbell.create({ dataDir: DATA, site, tv, saver, log });   // UniFi Protect doorbell alerts

// ---- integrations: each one feeds signals (lib/signals.js) and describes itself for the dashboard's Integrations tab.
// Per-TV switches in <data>/integrations.json.
const INTEG_FILE = path.join(DATA, 'integrations.json');
let integPrefs = {};
try { integPrefs = JSON.parse(fs.readFileSync(INTEG_FILE, 'utf8')) || {}; } catch (e) { integPrefs = {}; }
// Bluetooth sensors the user added (site items sensor:<id> {name, room?, where?, vendor, kind}), refreshed every 5 s.
let sensorItems = {};
const refreshSensors = () => { const it = site.view().items || {}, o = {};
  for (const k of Object.keys(it)) if (k.startsWith('sensor:') && !it[k].x && it[k].d) o[k.slice(7)] = it[k].d;
  sensorItems = o; };
setTimeout(refreshSensors, 3000);
setInterval(refreshSensors, 5000);
const ble = Ble.create({ log, enabled: () => integPrefs.ble_scan !== false, adopted: () => new Set(Object.keys(sensorItems)),
  onSearch: (found, until) => site.scanResults(found.map(f => ({ id: f.id, vendor: f.vendor, kind: f.kind, t: f.t, h: f.h, light: f.light,
    co2: f.co2, bat: f.bat, rssi: f.rssi, best: f.best, at: f.at })), until) });
const signals = Signals.create({ site, log });
signals.register('switchbot', () => ble.climate().map(r => { const s = sensorItems[r.id] || {};
  return Object.assign({}, r, { key: 'switchbot/' + r.id, name: s.name || (r.kind + ' ' + r.id.slice(-4).toUpperCase()), kind: r.kind,
    room: s.room || undefined, where: s.where || undefined }, r.light != null ? { light: r.light * 5 } : {}); }));   // light level 1-20 -> %
signals.register('lightsensor', () => { const e = roomLight.env();
  return e ? [{ key: 'light/' + site.id, name: (cfg.get().device_name || 'TV') + ' light sensor', kind: 'TV light sensor', light: e.pct, at: Date.now() }] : []; });
const roomlog = RoomLog.create({ dataDir: DATA, site, log });
ble.start();
roomlog.start();
setInterval(() => signals.publish(false), 30000);
setTimeout(() => signals.publish(true), 25000);

const tempText = t => t == null ? '' : cfg.get().units === 'C' ? t.toFixed(1) + ' °C' : (t * 9 / 5 + 32).toFixed(1) + ' °F';
const ago = at => { const m = Math.round((Date.now() - at) / 60000); return m < 1 ? 'just now' : m < 60 ? m + ' min ago' : Math.round(m / 60) + ' h ago'; };
/** The Integrations tab: [{id, name, desc, status {state ok|idle|off|warn, text}, settings [...], readings [{label, text}], panel?, info?}]. */
function integrations() {
  const on = integPrefs.ble_scan !== false, clim = ble.climate(), d = door.status(), ls = roomLight.status(), posted = signals.posted();
  const lightNow = roomLight.env();
  return [
    { id: 'switchbot', name: 'Bluetooth sensors', icon: 'bt',
      desc: 'Temperature, humidity and light from SwitchBot sensors (Hub 2, Meter, Meter Plus, Outdoor Meter, Meter Pro), read straight from their Bluetooth broadcasts: no app, account or pairing. Add the ones you want below (Find sensors); only those are kept and shared. This TV listens for 8 seconds every 2 minutes.',
      status: !on ? { state: 'off', text: 'Off on this TV' } : ble.searching() ? { state: 'warn', text: 'Searching for sensors…' }
        : clim.length ? { state: 'ok', text: 'Hearing ' + clim.length + ' of your sensor' + (clim.length === 1 ? '' : 's') } : { state: 'idle', text: Object.keys(sensorItems).length ? 'None of your sensors heard here' : 'No sensors added yet' },
      settings: [{ key: 'ble_scan', label: 'Listen for Bluetooth sensors on this TV', type: 'switch', value: on }],
      panel: 'sensors',
      readings: clim.map(r => ({ label: (sensorItems[r.id] || {}).name || r.kind + ' ' + r.id.slice(-4).toUpperCase(),
        text: [tempText(r.t), r.h != null ? r.h + ' %' : '', r.light != null ? 'light ' + r.light * 5 + ' %' : '', r.co2 != null ? r.co2 + ' ppm CO₂' : '',
          r.bat != null ? 'battery ' + r.bat + ' %' : '', 'signal ' + r.rssi + ' dBm', ago(r.at)].filter(Boolean).join(' · ') })) },
    { id: 'lightsensor', name: 'TV light sensor', icon: 'sun',
      desc: "This TV's own ambient light sensor. LG only runs it while AI Brightness is on (Home screen → Screen saver → Dim by room light switches that for you). Its reading is shared with the other TVs in the room.",
      status: ls.present === false ? { state: 'off', text: 'No sensor on this TV' } : lightNow ? { state: 'ok', text: 'Room light ' + lightNow.pct + ' %' } : { state: 'idle', text: "Idle (LG's AI Brightness is off)" },
      readings: lightNow ? [{ label: 'Room light', text: lightNow.pct + ' % (raw ' + lightNow.lum + ')' }] : [], link: { tab: 'homescreen', label: 'Dimming settings' } },
    { id: 'protect', name: 'UniFi Protect', icon: 'camera',
      desc: 'Alerts from your UniFi Protect cameras and doorbell (Alarm Manager webhooks), shown on your TVs with the camera picture.',
      status: d.configured ? { state: 'ok', text: (d.alerts || []).length + ' alert' + ((d.alerts || []).length === 1 ? '' : 's') + ' · ' + d.console } : { state: 'idle', text: 'Not set up' },
      panel: 'protect' },
    { id: 'api', name: 'Signals API', icon: 'api',
      desc: 'Anything on your network (Home Assistant, a script, an ESP32 sensor) can add presence or readings to your TVs: POST /api/signals with an API key (Privacy → API keys) and the header X-CanvasFrame: 1. Posted signals count for 15 minutes after the last post.',
      status: posted.length ? { state: 'ok', text: posted.length + ' signal' + (posted.length === 1 ? '' : 's') + ' posted here' } : { state: 'idle', text: 'Nothing posted here yet' },
      readings: posted.map(p => ({ label: p.name, text: [p.presence, tempText(p.t), p.h != null ? p.h + ' %' : '', ago(p.at)].filter(Boolean).join(' · ') })),
      info: { endpoint: '/api/signals', example: '{"signals":[{"key":"hall-motion","name":"Hall motion","room":"<room id>","presence":"someone"}]}' } },
  ];
}
function integrationSet(b) {
  if (b.id === 'switchbot' && b.set && 'ble_scan' in b.set) integPrefs.ble_scan = !!b.set.ble_scan;
  else throw new Error('Nothing to change there');
  try { fs.writeFileSync(INTEG_FILE, JSON.stringify(integPrefs)); } catch (e) { log('integrations: ' + e.message); }
  return { integrations: integrations() };
}

// ================================================================ router

async function route(req, res) {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname, method = req.method, peer = peerAddr(req);
  if (!isLan(peer)) return send(res, 403, 'text/plain', 'LAN only');
  // Plain HTTP from another device: send browsers to HTTPS and refuse keys, so neither keys nor sign-in cookies cross
  // the network unencrypted. The TV's own home screen (loopback) stays on HTTP; /api/tls stays reachable so other TVs
  // can find the HTTPS port. If HTTPS couldn't start, HTTP keeps working as before.
  const overTls = !!req.socket.encrypted;
  if (p.startsWith('/api/doorbell/hook/') && (method === 'POST' || method === 'GET')) {
    const b = await readBody(req, 16 * 1024).catch(() => Buffer.alloc(0));
    const kind = door.hook(p.slice('/api/doorbell/hook/'.length), url.search.slice(1), b.toString('utf8'), peer);
    return kind ? json(res, 200, { ok: true, kind }) : json(res, 404, { error: 'not found' });
  }
  if (!overTls && tlsInfo && !isLoopback(peer) && p !== '/api/tls') {
    req.resume();
    const to = 'https://' + hostOnly(req.headers.host) + ':' + TLS_PORT;
    if (req.headers['x-canvasframe-key']) return json(res, 403, { error: 'API keys are only accepted over HTTPS (' + to + ')', https: to });
    if (method === 'GET' || method === 'HEAD') return send(res, 302, 'text/plain', 'Moved to ' + to + req.url, { Location: to + req.url });
    return json(res, 403, { error: 'Use ' + to, https: to });
  }

  if (method === 'POST' && !req.headers['x-canvasframe']) return json(res, 403, { error: 'Missing X-CanvasFrame header' });
  const token = cookie(req.headers.cookie, auth.COOKIE);
  // An API key (another CanvasFrame TV, Home Assistant) counts as signed in.
  const sentKey = req.headers['x-canvasframe-key'];
  const keyOk = auth.keyValid(sentKey);
  const signedIn = !!auth.session(token) || keyOk;
  // A key this TV doesn't know (revoked, or made on another TV) is refused even while sign-in is off, so the TV
  // that sent it finds out now rather than the day sign-in is turned on.
  if (sentKey && !keyOk) {
    req.resume();
    return json(res, 401, { error: 'API key not recognised', badKey: true });
  }
  // The TV's own home screen (loopback) never needs a code.
  if (!signedIn && !isLoopback(peer) && auth.enabled() && !auth.isPublic(method, p) && p !== '/home' && p !== '/home/doorcard') {
    req.resume();
    return json(res, 401, { error: 'Sign in required', signin: true });
  }

  if (method === 'GET' && (p === '/' || p === '/index.html')) return send(res, 200, 'text/html; charset=utf-8', asset('dashboard.html'));
  if (method === 'GET' && p === '/home') return send(res, 200, 'text/html; charset=utf-8', asset('home.html'));
  if (method === 'GET' && p === '/home/doorcard') return send(res, 200, 'text/html; charset=utf-8', asset('doorcard.html'));   // the overlay app's card

  // Binary routes
  if (method === 'GET' && p === '/api/photo') {
    let f;
    try { f = photoFile(url.searchParams.get('name')); } catch (e) { return json(res, 404, { error: e.message }); }
    const thumb = path.join(THUMBS, path.basename(f) + '.jpg');
    return sendFile(res, url.searchParams.get('thumb') && fs.existsSync(thumb) ? thumb : f);
  }
  if (method === 'GET' && (p === '/api/pcapp' || p === '/api/pcapp/CanvasFrameLink.exe')) {
    // CanvasFrame Link for Windows (pc/, bundled in the ipk); the download carries this TV's address (see pcApp).
    let exe;
    try { exe = await pcApp(req.headers.host); } catch (e) { return json(res, 200, { available: false }); }
    if (p.endsWith('.exe')) return send(res, 200, 'application/octet-stream', exe, { 'Content-Disposition': 'attachment; filename="CanvasFrameLink.exe"' });
    let info = {};
    try { info = JSON.parse(fs.readFileSync(path.join(__dirname, 'pcapp', 'CanvasFrameLink.json'), 'utf8')); } catch (e) { /* version unknown */ }
    return json(res, 200, Object.assign(info, { available: true, size: exe.length, sha256: crypto.createHash('sha256').update(exe).digest('hex'),
      url: '/api/pcapp/CanvasFrameLink.exe' }));
  }
  if (method === 'GET' && p === '/api/home/doorsnap') {   // the doorbell snapshot for the home page / screen saver
    try { return send(res, 200, 'image/jpeg', await door.snapshot()); } catch (e) { return json(res, 503, { error: e.message }); }
  }
  if (method === 'GET' && p === '/api/icon') {
    const f = await tv.iconPath(url.searchParams.get('id') || '');
    if (!f) return json(res, 404, { error: 'no icon' });
    return sendFile(res, f);
  }
  if (method === 'POST' && p === '/api/upload') {
    try {
      const name = await saveUpload(req, req.headers['x-filename'], parseInt(req.headers['content-length'] || '0', 10));
      const tags = String(req.headers['x-tags'] || '').toLowerCase().split(/\s*,\s*/).filter(Boolean);
      if (tags.length) cfg.setTags(name, tags);
      if (!rot.cur) rotationTick();
      cfg.bump();
      saver.refresh();
      site.photoAdded(name);   // into the mesh
      return json(res, 200, { name, tags: cfg.tagsFor(name) });
    } catch (e) { return json(res, 400, { error: e.message }); }
  }
  // A removable LG extra, downloaded as a backup before deleting it (signed in like any other /api call).
  if (method === 'GET' && p === '/api/storage/file') {
    const f = storage.file(url.searchParams.get('id'), url.searchParams.get('name'));
    if (!f) return json(res, 404, { error: 'not found' });
    return fs.stat(f, (err, st) => {
      if (err) return json(res, 404, { error: 'not found' });
      res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': st.size,
        'Content-Disposition': 'attachment; filename="' + path.basename(f).replace(/"/g, '') + '"' });
      fs.createReadStream(f).pipe(res);
    });
  }
  const dm = p.match(/^\/api\/d\/([^/]+)\/(.*)$/);
  if (dm && method === 'GET' && dm[2] === 'icon') {
    const d = rokuFor(dm[1]);
    if (!d) return json(res, 404, { error: 'No such TV' });
    try { const r = await d.icon(url.searchParams.get('id') || ''); return send(res, 200, r.type || 'image/png', r.body, { 'Cache-Control': 'max-age=86400' }); }
    catch (e) { return json(res, 404, { error: e.message }); }
  }
  if (method === 'POST' && p === '/api/apps/install') {
    try { await appMgr.installUpload(req, parseInt(req.headers['content-length'] || '0', 10)); return json(res, 200, await appsJson()); }
    catch (e) { return json(res, 400, { error: e.message }); }
  }
  if (method === 'POST' && p === '/api/upload/thumb') {
    try {
      const f = photoFile(url.searchParams.get('name'));
      const body = await readBody(req, 2 * 1024 * 1024);
      fs.writeFileSync(path.join(THUMBS, path.basename(f) + '.jpg'), body);
      return json(res, 200, { ok: true });
    } catch (e) { return json(res, 400, { error: e.message }); }
  }

  let body = {};
  if (method === 'POST') {
    try { const b = await readBody(req, 64 * 1024); body = b.length ? JSON.parse(b.toString('utf8')) : {}; }
    catch (e) { return json(res, 400, { error: 'bad request body' }); }
  }

  try {
    // Sign-in
    if (p.startsWith('/api/auth')) {
      const ua = req.headers['user-agent'] || '';
      let set = null, r;
      switch (method + ' ' + p) {
        case 'GET /api/auth': r = auth.status(token); break;
        case 'POST /api/auth/login': { const t = auth.login(peer, body.code, ua); set = sessionCookie(t, overTls); r = auth.status(t); break; }
        case 'POST /api/auth/pass': {   // a one-time pass that signs this browser (or the PC app's window) in on another TV
          if (auth.enabled() && !signedIn) throw new auth.AuthError('Sign in first');
          const it = (site.view(false).items || {})['dev:' + body.to], d = it && !it.x ? it.d : null;
          if (!d) throw new Error('Unknown TV');
          r = { url: (d.urls || [])[0] || '' };
          const secret = site.ssoSecret(auth.enabled());
          if (secret) { const w = auth.passIdentity(token); r.pass = auth.makePass(secret, site.id, String(body.to), w.auth, w.chain, cfg.get().device_name || 'LG TV'); }
          break;
        }
        case 'POST /api/auth/pass-login': { const t = auth.passLogin(peer, body.pass, site.ssoSecret(auth.enabled()), site.id, ua); set = sessionCookie(t, overTls); r = auth.status(t); break; }
        case 'POST /api/auth/logout':
          if (body.everywhere && token) {   // also on every other TV: the sessions of this sign-in chain
            const chain = auth.passIdentity(token).chain;
            auth.revokeChain(chain);
            site.edit({ signout: chain }); site.now();
          }
          auth.logout(token); set = `${auth.COOKIE}=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict${overTls ? '; Secure' : ''}`; r = { ok: true }; break;
        case 'POST /api/auth/enroll': r = auth.enrollStart(body.name); break;
        case 'POST /api/auth/confirm': {
          const t = auth.enrollConfirm(String(body.id || ''), body.code, signedIn, ua);
          site.authChanged();   // share the new authenticator with the other TVs
          let tk = token;
          if (t) { set = sessionCookie(t, overTls); tk = t; }
          r = auth.status(tk);
          break;
        }
        case 'POST /api/auth/remove': auth.remove(String(body.id || '')); site.authRemoved(String(body.id || '')); r = auth.status(token); break;   // removed on every TV
        case 'POST /api/auth/revoke': auth.revoke(String(body.id || ''), token); r = auth.status(token); break;
        case 'POST /api/auth/key-create': { if (auth.enabled() && !signedIn) throw new auth.AuthError('Sign in first'); const k = auth.createKey(body.name); r = Object.assign(auth.status(token), { newKey: k }); break; }
        case 'POST /api/auth/key-revoke': if (auth.enabled() && !signedIn) throw new auth.AuthError('Sign in first'); auth.revokeKey(String(body.id || '')); r = auth.status(token); break;
        default: return json(res, 404, { error: 'not found' });
      }
      return json(res, 200, r, set ? { 'Set-Cookie': set } : null);
    }

    if (dm) return json(res, 200, await deviceCall(dm[1], method, dm[2], body));
    let r;
    switch (method + ' ' + p) {
      case 'GET /api/peers': r = { peers: url.searchParams.get('check') ? await sync.check() : sync.list() }; break;
      case 'POST /api/peers':
        if (body.action === 'remove') sync.remove(String(body.id || '')); else await sync.add(body.url, body.key);
        r = { peers: sync.list() };
        break;
      case 'GET /api/site': r = Object.assign(site.view(keyOk), { peers: site.peerStates() }); break;   // secrets only for another TV's key
      case 'POST /api/site':
        if (body.merge) { if (site.merge(body.merge, keyOk)) { saver.nudge(); saver.syncChanged(); } site.commands(); r = { ok: true }; }   // pushed by another TV
        else { site.edit(body); saver.syncChanged(); site.now(); r = Object.assign(site.view(), { peers: site.peerStates() }); }   // from this dashboard
        break;
      case 'POST /api/sync': r = { results: await sync.run(Array.isArray(body.ids) ? body.ids : [], body.what || {}), peers: sync.list() }; break;
      case 'GET /api/devices': r = await deviceList(); break;
      case 'GET /api/devices/discover': {
        const known = cfg.devices().map(d => d.host);
        r = { found: (await Roku.discover()).filter(h => known.indexOf(h) < 0) };
        break;
      }
      case 'POST /api/devices': {
        const host = String(body.host || '').trim();
        if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) throw new Error('Enter the Roku\'s IP address, e.g. 192.168.1.50');
        if (cfg.devices().some(d => d.host === host)) throw new Error('That TV is already added');
        const info = await Roku.create(host).info();
        const id = 'roku-' + host.replace(/\./g, '-');
        cfg.setDevices(cfg.devices().concat([{ id, type: 'roku', host, name: info.name, model: info.model }]));
        log('devices: added Roku ' + host + ' (' + info.model + ')');
        r = await deviceList();
        break;
      }
      case 'POST /api/devices/remove':
        cfg.setDevices(cfg.devices().filter(d => d.id !== body.id));
        r = await deviceList();
        break;
      // ---- home screen
      case 'GET /api/home': r = await homeData(); break;
      // Public: lets browsers and other TVs find the HTTPS port and compare this TV's certificate fingerprint.
      case 'GET /api/tls': r = { port: TLS_PORT, ready: !!tlsInfo, fingerprint: tlsInfo ? tlsInfo.fingerprint : undefined }; break;
      // Readable from any origin: a home page left on the old port uses it to find the server after a port change.
      case 'GET /api/home/ver': return json(res, 200, { version: cfg.version(), build: BUILD,
        notice: notice ? { id: notice.id, text: notice.text, age: Date.now() - notice.at } : null }, { 'Access-Control-Allow-Origin': '*' });
      case 'POST /api/home/log': log('home: ' + String(body.m || '').slice(0, 200)); r = { ok: true }; break;
      case 'POST /api/home/reopen':
        // Home was pressed while CanvasFrame was in front: LG's home is now a layer on top. Relaunch to close it.
        log('home key on the home page: reopening');
        [700, 2000].forEach(d => setTimeout(() => tv.launch(tv.HOME_APP).catch(() => {}), d));
        r = { ok: true };
        break;

      // ---- dashboard
      case 'GET /api/status': r = Object.assign(await tv.status(DATA), metrics.snapshot(), { features: FEATURES, port: PORT });
        r.brand = 'lg'; r.name = cfg.get().device_name || r.model; r.hdmi = await tv.inputs().then(l => l.map(h => Object.assign(h, { active: h.id === r.foregroundPkg }))).catch(() => undefined); break;
      case 'GET /api/inputs': r = { inputs: await tv.inputs() }; break;
      case 'POST /api/input': await tv.launch(String(body.id)); r = { ok: true }; break;
      case 'GET /api/pc': r = await pcLink.status(); break;
      case 'POST /api/pc': r = 'event' in body ? await pcLink.event(body) : await pcLink.set(body); break;
      case 'POST /api/wol': r = await wolSend(body); break;
      case 'POST /api/launch': await tv.launch(String(body.pkg || body.id)); r = { ok: true }; break;
      case 'POST /api/home':
        // Optional message to show on the home screen once it's open (sync uses this for on-TV feedback).
        if (body.message) notice = { id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6), text: String(body.message).slice(0, 200), at: Date.now() };
        await tv.launch(tv.HOME_APP); r = { ok: true }; break;
      case 'POST /api/key': await tv.key(String(body.key)); r = { ok: true }; break;
      case 'POST /api/volume':
        if ('mute' in body) await tv.setMuted(body.mute);
        if ('level' in body) await tv.setVolume(+body.level);
        r = { ok: true };
        break;
      case 'GET /api/apps': r = await appsJson(); break;
      case 'POST /api/apps/rename': cfg.update({ rename: { id: String(body.pkg), name: body.name } }); r = await appsJson(); break;
      case 'POST /api/apps/uninstall': await appMgr.uninstall(String(body.pkg)); r = await appsJson(); break;
      case 'POST /api/apps/install-url': await appMgr.installUrl(String(body.url || '')); r = await appsJson(); break;
      case 'GET /api/screensaver': r = saver.status(); break;
      case 'GET /api/storage': r = await storage.status(); break;
      case 'GET /api/roomlight': {   // the light sensor, and how dark CanvasFrame would be now (idle since now / since 2 h)
        r = roomLight.status();
        r.dimNow = saver.dimFor(Date.now());
        break;
      }
      case 'GET /api/home/saverinfo': r = saverWeather(); break;   // the screen saver's weather panel (loopback QML)
      case 'GET /api/home/door': r = door.state(); break;   // the doorbell panel (home page / screen saver, loopback)
      case 'GET /api/doorbell': r = door.status(); break;
      case 'POST /api/doorbell': r = await door.set(body); break;
      case 'POST /api/doorbell/test': r = await door.test(body); break;
      case 'GET /api/doorbell/cameras': r = { cameras: await door.cameras() }; break;   // the alert editor's camera picker
      case 'GET /api/integrations': r = { integrations: integrations() }; break;
      case 'POST /api/integrations': r = integrationSet(body); break;
      case 'POST /api/signals': r = signals.post(body, peer); break;   // from outside: Home Assistant, scripts, sensors
      case 'GET /api/rooms': r = Object.assign(roomlog.get(), { units: cfg.get().units === 'C' ? 'C' : 'F' }); break;
      case 'GET /api/home/dim': {   // the idle home screen asks how dark to be (OLED only)
        const since = +url.searchParams.get('since') || Date.now();
        const dim = isOled ? saver.dimFor(since) : 0;
        homeDim = { dim, at: Date.now() };
        r = { oled: !!isOled, dim };
        break;
      }
      case 'POST /api/home/sound': uiSound(String(body.kind || '')); r = { ok: true }; break;   // remote sounds (home page)
      case 'POST /api/storage': { const freed = await storage.remove(String(body.remove || '')); r = Object.assign(await storage.status(), { freed }); break; }
      case 'POST /api/screensaver':
        { const already = body.preview ? await saver.preview(!!body.openHome) : (await saver.set(String(body.mode || '')), false);
          r = saver.status(); if (already) r.already = true; }
        break;
      case 'GET /api/servicemenu': r = await serviceMenu(); break;
      case 'POST /api/servicemenu':
        if ('locked' in body) {
          const w = await tv.luna('com.webos.service.settings/setSystemSettings', { category: 'other', settings: { svcMenuFlag: !!body.locked } });
          if (w.returnValue !== true) throw new Error('The TV would not change it');
        }
        if (body.open) {
          const which = body.open === 'inStart' ? 'inStart' : 'ezAdjust';
          const l = await tv.luna('com.webos.applicationManager/launch', { id: 'com.webos.app.factorywin', params: { irKey: which } });
          if (l.returnValue === false) throw new Error(l.errorText || 'The TV would not open it');
        }
        r = await serviceMenu();
        break;
      case 'GET /api/tvsettings': r = withPointer(await lgs.read()); break;
      case 'POST /api/tvsettings':
        if (body.id === 'pointerWake') await pointer.set(String(body.value || '')); else await lgs.set(String(body.id || ''), body.value);
        r = withPointer(await lgs.read()); break;
      case 'GET /api/privacy': r = Object.assign(await privacy.status(), { glasshouse: gh.detect() }); break;
      case 'GET /api/network': r = await network.status(); break;
      case 'POST /api/network':
        if ('dns' in body) { await network.setDns(body.dns); log('network: DNS ' + ((body.dns || []).join(', ') || 'automatic')); }
        if (body.ntp) throw new Error("This TV can't use an NTP server");
        await new Promise(res2 => setTimeout(res2, 1500));   // let the connection manager apply it
        r = await network.status();
        break;
      case 'POST /api/privacy': r = Object.assign(await privacy.set(body), { glasshouse: gh.detect() }); break;
      case 'POST /api/takeover': {
        if (body.action === 'glasshouse') {
          const det = gh.detect();
          if (!det.present || det.takenOver) throw new Error('Glasshouse is not active on this TV');
          await gh.takeOver();
          await privacy.adopt(det.settings);
          if (cfg.get().hidden_apps.indexOf(gh.APP_ID) < 0) cfg.update({ hide: gh.APP_ID });
          fs.writeFileSync(PORT_FILE, '8080');
          r = { ok: true, port: 8080, adopted: det.settings };
          restartSelf();
        } else if (body.action === 'restore') {
          await gh.restore();
          await privacy.set({ adblock: 'off', blockUpdates: false });
          fs.writeFileSync(PORT_FILE, '8081');
          r = { ok: true, port: 8081 };
          // Free 8080, start Glasshouse's own boot hook, then come back up on 8081.
          setTimeout(() => { server.close(); setTimeout(() => { gh.startNow(); restartSelf(500); }, 1000); }, 500);
        } else throw new Error('unknown action');
        break;
      }
      case 'GET /api/settings': r = await settingsJson(); break;
      case 'POST /api/settings':
        if (cfg.update(body)) cfg.fetchWeather().catch(e => log('weather: ' + e.message));
        if ('photo_min' in body) rotationTick();
        if ('clock' in body) saver.refresh();
        if ('light_dim' in body || 'light_src' in body) await roomLight.apply().catch(e => log('room light: ' + e.message));
        if ('light_dim' in body || 'light_src' in body || 'light_offset' in body || 'art_dim' in body) saver.nudge();
        if (MESH_SETTINGS.some(k => k in body)) site.settingsChanged();   // shared across the mesh
        r = await settingsJson();
        break;
      case 'GET /api/geocode': r = { results: await cfg.geocode(url.searchParams.get('q') || '') }; break;
      case 'POST /api/weather': await cfg.fetchWeather(); r = await settingsJson(); break;
      case 'POST /api/calendars': cfg.updateCalendars(body); if (body.action !== 'refresh') site.calendarsChanged(); await cfg.fetchCalendars(); r = await settingsJson(); break;
      case 'GET /api/photos': r = photosJson(); break;
      case 'POST /api/photos/delete': {
        const f = photoFile(body.name), n = path.basename(f);
        fs.unlinkSync(f);
        try { fs.unlinkSync(path.join(THUMBS, n + '.jpg')); } catch (e) { /* none */ }
        cfg.setTags(n, null);
        if (rot.cur === n) pickPhoto();
        saver.refresh();
        site.photoDeleted(n);   // deleted on every TV in the mesh
        r = photosJson();
        break;
      }
      case 'POST /api/photos/tags': {
        const n = path.basename(photoFile(body.name));
        cfg.setTags(n, Array.isArray(body.tags) ? body.tags : []);
        site.tagsChanged(n);   // shared across the mesh
        rotationTick();
        r = photosJson();
        break;
      }
      case 'POST /api/photos/show': {
        rot = { cur: path.basename(photoFile(body.name)), time: Date.now(), manual: true };
        saveRot();
        r = { ok: true };
        break;
      }
      default: return json(res, 404, { error: 'not found' });
    }
    return json(res, 200, r);
  } catch (e) {
    if (e instanceof auth.AuthError) return json(res, 403, { error: e.message });
    return json(res, 500, { error: e.message || String(e) });
  }
}

// ---------------------------------------------------------------- other TVs (Roku)
const rokus = {};
function rokuFor(id) {
  const d = cfg.devices().find(x => x.id === id && x.type === 'roku');
  if (!d) return null;
  if (!rokus[id] || rokus[id].host !== d.host) rokus[id] = Roku.create(d.host);
  return rokus[id];
}
async function deviceList() {
  const inf = await tv.status(DATA).catch(() => ({}));
  return { devices: [{ id: 'local', type: 'webos', name: cfg.get().device_name || inf.model || 'This TV', model: inf.model || '' }]
    .concat(cfg.devices().map(d => ({ id: d.id, type: d.type, name: d.name, model: d.model, host: d.host }))) };
}
/** The dashboard API, answered for another TV. */
async function deviceCall(id, method, path, body) {
  const d = rokuFor(id);
  if (!d) throw new Error('No such TV');
  switch (method + ' ' + path) {
    case 'GET status': return d.status();
    case 'GET inputs': return { inputs: await d.inputs() };
    case 'GET apps': return d.apps();
    case 'POST input': await d.launch(String(body.id)); return { ok: true };
    case 'POST launch': await d.launch(String(body.pkg || body.id)); return { ok: true };
    case 'POST home': await d.key('HOME'); return { ok: true };
    case 'POST key': await d.key(String(body.key)); return { ok: true };
    case 'POST volume': if (body.mute !== undefined) await d.key('MUTE'); return { ok: true };
    case 'GET privacy': return { platform: 'roku' };
    case 'GET auth': return auth.status(null);
    default: throw new Error('Not available on this TV');
  }
}

async function serviceMenu() {
  const app = fs.existsSync('/usr/palm/applications/com.webos.app.factorywin');
  const r = await tv.luna('com.webos.service.settings/getSystemSettings', { category: 'other', keys: ['svcMenuFlag'] });
  const flag = r && r.returnValue === true && r.settings ? r.settings.svcMenuFlag : undefined;
  return { available: app, lockable: flag !== undefined, locked: flag === undefined ? null : flag === true || flag === 'true' };
}

async function appsJson() {
  const s = cfg.get(), list = await tv.apps();
  return { apps: list.filter(a => !a.symbol).map(a => {
    const hidden = s.hidden_apps.indexOf(a.id) >= 0, name = (s.app_names || {})[a.id];
    return { pkg: a.id, label: name || a.title, original: a.title, renamed: !!name, enabled: true, system: a.system, launchable: true,
      protected: true, hidden, removable: a.removable && appMgr.PROTECTED.indexOf(a.id) < 0, dev: a.dev,
      note: hidden ? 'Hidden from the home screen' : '' };
  }) };
}

async function settingsJson() {
  const lps = await tv.launchPoints().catch(() => []);
  const label = id => { const lp = lps.find(x => x.id === id); return lp ? lp.title : id; };
  return cfg.toJson(label);
}

const server = http.createServer((req, res) => {
  route(req, res).catch(e => { log('route: ' + e.stack); try { json(res, 500, { error: e.message }); } catch (x) { /* sent */ } });
});
// A previous instance (just sent SIGTERM above) may still hold the port for a moment: retry for ~15 s.
let listenTries = 0;
server.on('error', e => {
  if (e.code === 'EADDRINUSE' && ++listenTries < 30) { setTimeout(() => server.listen(PORT), 500); return; }
  log('server: ' + e.message);
  setTimeout(() => process.exit(1), 1000);
});
server.listen(PORT, () => log('listening on ' + PORT));

// HTTPS: same routes, this TV's own certificate (made once with openssl, kept in <data>/tls).
(function startTls() {
  let creds;
  try { creds = Tls.ensure(path.join(DATA, 'tls'), 'CanvasFrame ' + (cfg.get().device_name || 'LG'), tv.lanIp()); }
  catch (e) { log('https: not available (' + e.message + '); the dashboard stays on plain HTTP'); return; }
  const tlsServer = https.createServer({ key: creds.key, cert: creds.cert }, (req, res) => {
    route(req, res).catch(e => { log('route: ' + e.stack); try { json(res, 500, { error: e.message }); } catch (x) { /* sent */ } });
  });
  let tries = 0;
  tlsServer.on('tlsClientError', () => { /* a browser that hasn't accepted the certificate yet */ });
  tlsServer.on('error', e => {
    if (e.code === 'EADDRINUSE' && ++tries < 30) { setTimeout(() => tlsServer.listen(TLS_PORT), 500); return; }
    log('https: ' + e.message);
  });
  tlsServer.listen(TLS_PORT, () => { tlsInfo = { fingerprint: creds.fingerprint }; log('https listening on ' + TLS_PORT + ' (' + creds.fingerprint + ')'); });
})();

// ================================================================ boot, Home button, wake

// LG's input menus (webOS 22: Home Dashboard). The Input button opens one of these.
const INPUT_APPS = ['com.webos.app.homeconnect', 'com.webos.app.inputpicker'];
let lastLaunch = 0;
function openHome(reason) {
  if (Date.now() - lastLaunch < 800) return;
  lastLaunch = Date.now();
  tv.launch(tv.HOME_APP).then(() => log('home opened (' + reason + ')'), e => log('home open failed (' + reason + '): ' + e.message));
}

if (ARGS.indexOf('--boot') >= 0 && cfg.get().open_on_wake) {
  let tries = 0;
  const attempt = () => tv.launch(tv.HOME_APP).then(() => log('boot: home opened'),
    () => { if (++tries < 30) setTimeout(attempt, 2000); });
  setTimeout(attempt, 1500);
}
if (ARGS.indexOf('--no-watch') < 0) {
  tv.watchHome(() => openHome('home key'), log);
  // Whatever opens LG's own home (Home button, an app exiting, boot), show CanvasFrame instead.
  let lastApps = null, lastSaver = false;
  tv.watchForeground(wins => {
    saver.onForeground(wins);
    // Activity for the shared site: the screen saver was dismissed, or what's on screen changed (an app/input picked,
    // the TV turned on). Nearby TVs' screen savers react to it.
    const saverNow = wins.some(w => w.appId === 'com.webos.app.screensaver');
    const apps = wins.filter(w => w.appId !== 'com.webos.app.screensaver').map(w => w.appId).join(',');
    if ((lastSaver && !saverNow) || (lastApps !== null && apps && apps !== lastApps && !saverNow)) site.activity(lastSaver && !saverNow ? 'screen saver dismissed' : 'remote');
    lastSaver = saverNow; if (apps) lastApps = apps;
    // The Input button opens LG's Home Dashboard: replace it with CanvasFrame's Inputs row.
    if (wins.some(w => INPUT_APPS.indexOf(w.appId) >= 0)) {
      INPUT_APPS.forEach(id => tv.luna('com.webos.applicationManager/closeByAppId', { id }).catch(() => {}));
      tv.launch(tv.HOME_APP, { open: 'inputs' }).then(() => log('input menu replaced'), e => log('input menu: ' + e.message));
      return;
    }
    if (wins.some(w => w.appId === tv.STOCK_HOME || /HOME/i.test(w.windowType))) openHome('stock home');
  }, log);
}

// Standby freezes this process, so a long gap between 1 s heartbeats means the TV just woke up.
let beat = Date.now();
setInterval(() => {
  const now = Date.now(), gap = now - beat;
  beat = now;
  if (gap > 15000 && cfg.get().open_on_wake) {
    log('wake detected (' + Math.round(gap / 1000) + ' s)');
    [0, 1500, 3000].forEach(d => setTimeout(() => tv.launch(tv.HOME_APP).catch(() => {}), d));
  }
}, 1000);

process.on('SIGUSR2', () => { auth.reload(); log('auth reloaded'); site.authReset(); });
process.on('uncaughtException', e => log('uncaught: ' + (e && e.stack)));
process.on('unhandledRejection', e => log('unhandled: ' + (e && e.stack)));
