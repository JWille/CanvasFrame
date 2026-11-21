'use strict';
// UniFi Protect alerts on LG (same as Doorbell.java). Setup is the shared site item set:door {console, fp, apiKey, hook,
// alerts [{id, name, camera, stream, quiet}], older live / pkg} (TV-to-TV only; browsers see what's set). With no alerts saved,
// the four defaults ring / person / package / motion apply. Per TV in <data>/door.json: show, over, secs, secsOver, corner,
// size, hide (alert ids not shown here; the older "kinds" list is moved over once).
// Over other apps and inputs the card opens in the overlay app com.canvasframe.door (webos/door + server/doorcard.html),
// in the chosen corner; if that app can't start, a system toast.
//
// Protect's Alarm Manager calls http(s)://<any TV>/api/doorbell/hook/<hook>?kind=<alert id>. That TV shows it and
// sends a site command so the others do. On LG the picture is a snapshot from Protect's integration API (refreshed about once
// a second, served to the home page and the screen saver at /api/home/doorsnap on loopback); the web page can't play RTSP.

const fs = require('fs');
const path = require('path');
const http = require('http');
const crypto = require('crypto');
const Tls = require('./tls');

const DEFAULTS = [{ id: 'ring', name: 'Doorbell Ringing' }, { id: 'person', name: 'Person at the Door' },
  { id: 'package', name: 'Package Delivered' }, { id: 'motion', name: 'Motion at the Door', quiet: true }];
const KINDS = DEFAULTS.map(a => a.id);
/** The alerts: the saved list, or the four defaults (ids match the older ?kind= webhook addresses). */
const alertsOf = d => (d && Array.isArray(d.alerts) && d.alerts.length ? d.alerts : DEFAULTS);
const alertOf = (d, id) => alertsOf(d).find(a => a.id === id) || { id, name: 'UniFi Protect alert' };
/** RTSP token for an alert: its own stream; the four original alerts fall back to the older package / main stream (an alert
 *  of the user's own never borrows another camera's video). */
const streamOf = (d, a) => (a.stream || (a.noStream || !KINDS.includes(a.id) ? '' : (a.id === 'package' && d && d.pkg) || (d && d.live)) || '');

function create({ dataDir, site, tv, saver, log }) {
  const FILE = path.join(dataDir, 'door.json');
  let prefs = { show: false, over: false, secs: 45, secsOver: 15, corner: 'br', size: 'm' };
  try { prefs = Object.assign(prefs, JSON.parse(fs.readFileSync(FILE, 'utf8'))); } catch (e) { /* defaults */ }
  const save = () => { try { fs.writeFileSync(FILE, JSON.stringify(prefs)); } catch (e) { log('door: ' + e.message); } };
  if (!Array.isArray(prefs.hide)) {   // before 1.36 a "shown" list of the four fixed alerts
    prefs.hide = Array.isArray(prefs.kinds) ? KINDS.filter(k => !prefs.kinds.includes(k)) : [];
    delete prefs.kinds;
    save();
  }
  const RECENT = path.join(dataDir, 'door-recent.json');   // kept on disk so a deploy or restart doesn't lose them
  let recent = [];
  try { recent = JSON.parse(fs.readFileSync(RECENT, 'utf8')); if (!Array.isArray(recent)) recent = []; } catch (e) { /* none yet */ }
  let state = null, lastEvent = '', lastAt = 0, cameraId = null, cameraName = '', snapCache = null;
  const camNames = {};   // Protect camera id -> name

  const door = () => site.door();

  function remember(kind, from, body) {
    recent.unshift({ at: Date.now(), kind, from, body: String(body || '').slice(0, 1500) });
    recent.length = Math.min(recent.length, 10);
    try { fs.writeFileSync(RECENT, JSON.stringify(recent)); } catch (e) { /* best effort */ }
    log('door: webhook ' + kind + ' from ' + from);
  }

  function kindOf(query, body) {
    const m = /(?:^|&)kind=([A-Za-z0-9_-]{1,24})/.exec(query || '');
    if (m) return m[1];
    const b = String(body || '').toLowerCase(), list = alertsOf(door());
    for (const k of ['package', 'ring', 'person', 'motion']) {   // older guesses, if those alerts exist
      if ((b.includes(k) || (k === 'person' && b.includes('human'))) && list.some(a => a.id === k)) return k;
    }
    return list.length ? list[0].id : 'ring';
  }

  /** GET from the console's integration API with the API key, pinned to its certificate. Resolves to a Buffer. */
  function protect(d, p) {
    return new Promise((resolve, reject) => {
      const req = http.request({ host: d.console, port: 443, path: p, method: 'GET', agent: Tls.pinnedAgent(d.fp || null, null),
        headers: { 'X-API-KEY': d.apiKey }, timeout: 6000 }, res => {
        const chunks = [];
        res.on('data', c => chunks.push(c));
        res.on('end', () => res.statusCode === 200 ? resolve(Buffer.concat(chunks)) : reject(new Error('Protect answered ' + res.statusCode)));
      });
      req.on('timeout', () => req.destroy(new Error('no answer from Protect')));
      req.on('error', reject);
      req.end();
    });
  }
  async function cameras(d) {
    const cams = JSON.parse((await protect(d, '/proxy/protect/integration/v1/cameras')).toString('utf8'));
    for (const c of cams) camNames[c.id] = String(c.name || '');
    return cams;
  }
  async function camera(d) {
    if (cameraId) return cameraId;
    const cams = await cameras(d);
    const hit = cams.find(c => /doorbell/i.test([c.name, c.modelKey, c.type].join(' '))) || cams[0];
    cameraId = hit ? hit.id : null;
    cameraName = hit ? String(hit.name || '') : '';
    return cameraId;
  }
  /** A snapshot from camId (the alert's camera), or the one on screen, or the default camera. */
  async function snapshot(camId) {
    const d = door();
    if (!d || !d.apiKey) throw new Error('No Protect API key set');
    const id = camId || (state && state.cam) || await camera(d);
    if (snapCache && snapCache.id === id && Date.now() - snapCache.at < 800) return snapCache.jpg;
    const jpg = await protect(d, '/proxy/protect/integration/v1/cameras/' + id + '/snapshot');
    snapCache = { at: Date.now(), id, jpg };
    return jpg;
  }

  /** Shows it here (if switched on): the home page / screen saver poll state(); over other apps a short system toast. */
  async function here(kind, event) {
    if (!prefs.show || !door() || event === lastEvent || (prefs.hide || []).includes(kind)) return;
    const a = alertOf(door(), kind);
    // Quiet alerts (motion) fire again and again: they never replace an alert that's up, nor repeat within 30 s.
    if (a.quiet && !String(event).startsWith('test') && (Date.now() - lastAt < 30000 || (state && Date.now() < state.until))) return;
    lastEvent = event; lastAt = Date.now();
    const pw = await tv.luna('com.webos.service.tvpower/power/getPowerState').catch(() => ({}));
    const st = String(pw.state || '');
    if (/screen off|standby/i.test(st)) return;   // asleep
    const fg = await tv.foreground().catch(() => '');
    const ours = /saver/i.test(st) || fg === 'com.canvasframe.home';
    if (ours) {
      const ms = 1000 * Math.max(5, +prefs.secs || 45);
      state = { kind, label: a.name, cam: a.camera || '', at: Date.now(), until: Date.now() + ms, snap: !!door().apiKey };
      saver.brightFor(ms);
    } else if (prefs.over) {
      // The corner card in the overlay app (no keyboard focus: the app underneath keeps the remote).
      const ms = 1000 * Math.max(5, +prefs.secsOver || 15);
      state = { kind, label: a.name, cam: a.camera || '', at: Date.now(), until: Date.now() + ms, snap: !!door().apiKey,
        over: true, corner: prefs.corner || 'br', size: prefs.size || 'm' };
      tv.luna('com.webos.applicationManager/launch', { id: 'com.canvasframe.door' }).then(r => {
        if (r && r.returnValue) return;
        log('door: overlay app failed (' + ((r && r.errorText) || '?') + '), toast instead');
        tv.luna('com.webos.notification/createToast', { sourceId: 'com.canvasframe.home', message: '\uD83D\uDD14 ' + a.name }).catch(() => {});
      });
    }
    log('door: ' + kind + (ours ? ' (panel)' : prefs.over ? ' (corner over ' + fg + ')' : ' (not shown over ' + fg + ')'));
  }

  function alert(kind) {
    const event = Date.now().toString(36) + crypto.randomBytes(2).toString('hex');
    here(kind, event).catch(e => log('door: ' + e.message));
    site.edit({ doorbell: { kind, event } });
    site.now();
  }

  return {
    /** The webhook; returns the kind, or null for a wrong token (404). */
    hook(token, query, body, from) {
      const d = door();
      const a = Buffer.from(String((d && d.hook) || '')), b = Buffer.from(String(token || ''));
      if (!d || !d.hook || a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
      const kind = kindOf(query, body);
      remember(kind, from, body);
      alert(kind);
      return kind;
    },
    command(c) { here(String(c.kind || 'ring'), String(c.event || '')).catch(e => log('door: ' + e.message)); },
    /** For the home page and the screen saver (loopback). */
    state() {
      if (!state || Date.now() >= state.until) return { active: false };
      const cam = (state.cam && camNames[state.cam]) || cameraName || 'Front Door Camera';
      return Object.assign({}, state, { camera: state.kind === 'package' && !state.cam ? cam + ' \u00b7 package camera' : cam });
    },
    snapshot: () => snapshot(),
    /** Protect's cameras for the alert editor: [{id, name, model}]. */
    async cameras() {
      const d = door();
      if (!d || !d.apiKey) throw new Error('Add a Protect API key first');
      return (await cameras(d)).map(c => ({ id: c.id, name: c.name || '', model: c.modelKey || c.type || '' }));
    },
    status() {
      const d = door();
      const o = { configured: !!d, show: prefs.show, over: prefs.over, canOver: true, overPanel: true, secs: +prefs.secs || 45,
        secsOver: +prefs.secsOver || 15, corner: prefs.corner || 'br', size: prefs.size || 'm', hide: prefs.hide || [], recent,
        alerts: alertsOf(d).map(a => ({ id: a.id, name: a.name, camera: a.camera || '', quiet: !!a.quiet,
          stream: !!(d && streamOf(d, a)), ownStream: !!a.stream })) };
      if (d) Object.assign(o, { console: d.console, apiKey: !!d.apiKey, live: !!d.live, pkg: !!d.pkg, hookPath: '/api/doorbell/hook/' + d.hook });
      return o;
    },
    /** Per TV {show, over}; shared {console, apiKey, live, pkg} (blank = keep, "-" = clear); {remove:true}. */
    async set(b) {
      if ('show' in b) prefs.show = !!b.show;
      if ('over' in b) prefs.over = !!b.over;
      if ('secs' in b) prefs.secs = Math.max(5, Math.min(600, parseInt(b.secs, 10) || 45));
      if (Array.isArray(b.hide)) prefs.hide = b.hide.map(String).filter(k => /^[A-Za-z0-9_-]{1,24}$/.test(k));
      if ('secsOver' in b) prefs.secsOver = Math.max(5, Math.min(600, parseInt(b.secsOver, 10) || 15));
      if (/^(br|bl|tr|tl)$/.test(b.corner)) prefs.corner = b.corner;
      if (/^[sml]$/.test(b.size)) prefs.size = b.size;
      save();
      if (b.close) { state = null; return this.status(); }
      if (b.remove) { site.setDoor(null); return this.status(); }
      if (Array.isArray(b.alerts)) {   // the shared alert list: [{id?, name, camera, quiet, stream ("" keep, "-" clear, rtsps://… or a token)}]
        const cur = door();
        if (!cur) throw new Error('Set up the Protect console first');
        const old = alertsOf(cur), keep = new Set(b.alerts.map(a => a && a.id).filter(id => old.some(o => o.id === id)));
        const ids = new Set(), out = [];
        for (const a of b.alerts) {
          if (!a || out.length >= 20) continue;
          const name = String(a.name || '').trim().slice(0, 40);
          if (!name) continue;
          let id = String(a.id || '');
          if (!keep.has(id) || ids.has(id)) {   // a new alert: an id from its name, clear of the others (existing ids keep their webhooks)
            const base = (name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'alert').slice(0, 20);
            id = base;
            for (let n = 2; ids.has(id) || keep.has(id); n++) id = base + '-' + n;
          }
          ids.add(id);
          const before = old.find(o => o.id === id);
          let stream = String(a.stream || '').trim();
          const noStream = stream === '-' || (!stream && !!before && !!before.noStream);
          const m = /^rtsps?:\/\/([0-9A-Za-z.-]+):\d+\/([A-Za-z0-9]+)/.exec(stream);
          if (m) stream = m[2];
          else if (stream === '-') stream = '';
          else if (!stream) stream = before ? streamOf(cur, before) : '';   // keep (incl. the older fallback)
          else if (!/^[A-Za-z0-9]{8,64}$/.test(stream)) throw new Error('That stream for ' + name + " isn't an rtsps:// address from Protect");
          const cam = /^[A-Za-z0-9]{0,40}$/.test(String(a.camera || '')) ? String(a.camera || '') : '';
          const o = { id, name };
          if (cam) o.camera = cam;
          if (stream) o.stream = stream;
          else if (noStream) o.noStream = true;   // removed on purpose: no fallback to the older doorbell stream
          if (a.quiet) o.quiet = true;
          out.push(o);
        }
        if (!out.length) throw new Error('Keep at least one alert');
        site.setDoor(Object.assign({}, cur, { alerts: out }));
        return this.status();
      }
      if (['console', 'apiKey', 'live', 'pkg'].some(k => k in b)) {
        const cur = door(), d = Object.assign({}, cur || {});
        for (const k of ['console', 'apiKey', 'live', 'pkg']) {
          if (!(k in b)) continue;
          const v = String(b[k] || '').trim();
          if (v === '-') delete d[k]; else if (v) d[k] = v;
        }
        for (const k of ['live', 'pkg']) {   // a pasted stream address gives the console and the token
          const m = /^rtsps?:\/\/([0-9A-Za-z.-]+):\d+\/([A-Za-z0-9]+)/.exec(d[k] || '');
          if (m) { if (!d.console) d.console = m[1]; d[k] = m[2]; }
        }
        const consoleAddr = String(d.console || '').replace(/^https?:\/\//, '').replace(/\/.*$/, '');
        if (!consoleAddr) throw new Error("Enter the Protect console's address");
        if (consoleAddr !== (cur && cur.console) || !d.fp) {   // record its certificate on first contact
          const seen = {};
          await new Promise(resolve => {
            const r = http.request({ host: consoleAddr, port: 443, path: '/', agent: Tls.pinnedAgent(null, seen), timeout: 5000 }, res => { res.resume(); resolve(); });
            r.on('error', () => resolve()); r.on('timeout', () => { r.destroy(); resolve(); }); r.end();
          });
          if (!seen.fp) throw new Error('The Protect console at ' + consoleAddr + " didn't answer");
          d.fp = seen.fp;
        }
        d.console = consoleAddr;
        if (!d.hook) d.hook = crypto.randomBytes(16).toString('hex');
        cameraId = null; snapCache = null;
        site.setDoor(d);
      }
      return this.status();
    },
    async test(b) {
      const kind = String(b.kind || 'ring'), o = { ok: true };
      if (door() && door().apiKey) {
        try { o.snapshot = (await snapshot(alertOf(door(), kind).camera || '')).length; } catch (e) { o.snapshotError = e.message; }
      }
      if (b.here) here(kind, 'test' + Date.now()).catch(() => {}); else alert(kind);
      return o;
    },
  };
}

module.exports = { create };
