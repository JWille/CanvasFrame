'use strict';
// UniFi Protect doorbell on LG (same as Doorbell.java). Setup is the shared site item set:door {console, fp, apiKey, live, pkg,
// hook} (TV-to-TV only; browsers see what's set). Per TV in <data>/door.json: show, over, secs, secsOver, corner, size, kinds.
// Over other apps and inputs the card opens in the overlay app com.canvasframe.door (webos/door + server/doorcard.html),
// in the chosen corner; if that app can't start, a system toast.
//
// Protect's Alarm Manager calls http(s)://<any TV>/api/doorbell/hook/<hook>?kind=ring|person|package|motion. That TV shows it and
// sends a site command so the others do. On LG the picture is a snapshot from Protect's integration API (refreshed about once
// a second, served to the home page and the screen saver at /api/home/doorsnap on loopback); the web page can't play RTSP.

const fs = require('fs');
const path = require('path');
const http = require('http');
const crypto = require('crypto');
const Tls = require('./tls');

const LABEL = { ring: 'Doorbell Ringing', person: 'Person at the Door', package: 'Package Delivered', motion: 'Motion at the Door' };
const KINDS = ['ring', 'person', 'package', 'motion'];

function create({ dataDir, site, tv, saver, log }) {
  const FILE = path.join(dataDir, 'door.json');
  let prefs = { show: false, over: false, secs: 45, secsOver: 15, corner: 'br', size: 'm', kinds: KINDS.slice() };
  try { prefs = Object.assign(prefs, JSON.parse(fs.readFileSync(FILE, 'utf8'))); } catch (e) { /* defaults */ }
  const save = () => { try { fs.writeFileSync(FILE, JSON.stringify(prefs)); } catch (e) { log('door: ' + e.message); } };
  const RECENT = path.join(dataDir, 'door-recent.json');   // kept on disk so a deploy or restart doesn't lose them
  let recent = [];
  try { recent = JSON.parse(fs.readFileSync(RECENT, 'utf8')); if (!Array.isArray(recent)) recent = []; } catch (e) { /* none yet */ }
  let state = null, lastEvent = '', lastAt = 0, cameraId = null, cameraName = '', snapCache = null;

  const door = () => site.door();

  function remember(kind, from, body) {
    recent.unshift({ at: Date.now(), kind, from, body: String(body || '').slice(0, 1500) });
    recent.length = Math.min(recent.length, 10);
    try { fs.writeFileSync(RECENT, JSON.stringify(recent)); } catch (e) { /* best effort */ }
    log('door: webhook ' + kind + ' from ' + from);
  }

  function kindOf(query, body) {
    const m = /(?:^|&)kind=([a-z]+)/.exec(query || '');
    if (m) return m[1];
    const b = String(body || '').toLowerCase();
    return b.includes('package') ? 'package' : b.includes('ring') ? 'ring' : /person|human/.test(b) ? 'person' : b.includes('motion') ? 'motion' : 'ring';
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
  async function camera(d) {
    if (cameraId) return cameraId;
    const cams = JSON.parse((await protect(d, '/proxy/protect/integration/v1/cameras')).toString('utf8'));
    const hit = cams.find(c => /doorbell/i.test([c.name, c.modelKey, c.type].join(' '))) || cams[0];
    cameraId = hit ? hit.id : null;
    cameraName = hit ? String(hit.name || '') : '';
    return cameraId;
  }
  async function snapshot() {
    const d = door();
    if (!d || !d.apiKey) throw new Error('No Protect API key set');
    if (snapCache && Date.now() - snapCache.at < 800) return snapCache.jpg;
    const jpg = await protect(d, '/proxy/protect/integration/v1/cameras/' + await camera(d) + '/snapshot');
    snapCache = { at: Date.now(), jpg };
    return jpg;
  }

  /** Shows it here (if switched on): the home page / screen saver poll state(); over other apps a short system toast. */
  async function here(kind, event) {
    if (!prefs.show || !door() || event === lastEvent || !(prefs.kinds || KINDS).includes(kind)) return;
    // Motion fires again and again while someone waits: it never replaces an alert that's up, nor repeats within 30 s.
    if (kind === 'motion' && !String(event).startsWith('test') && (Date.now() - lastAt < 30000 || (state && Date.now() < state.until))) return;
    lastEvent = event; lastAt = Date.now();
    const pw = await tv.luna('com.webos.service.tvpower/power/getPowerState').catch(() => ({}));
    const st = String(pw.state || '');
    if (/screen off|standby/i.test(st)) return;   // asleep
    const fg = await tv.foreground().catch(() => '');
    const ours = /saver/i.test(st) || fg === 'com.canvasframe.home';
    if (ours) {
      const ms = 1000 * Math.max(5, +prefs.secs || 45);
      state = { kind, label: LABEL[kind] || 'Doorbell', at: Date.now(), until: Date.now() + ms, snap: !!door().apiKey };
      saver.brightFor(ms);
    } else if (prefs.over) {
      // The corner card in the overlay app (no keyboard focus: the app underneath keeps the remote).
      const ms = 1000 * Math.max(5, +prefs.secsOver || 15);
      state = { kind, label: LABEL[kind] || 'Doorbell', at: Date.now(), until: Date.now() + ms, snap: !!door().apiKey,
        over: true, corner: prefs.corner || 'br', size: prefs.size || 'm' };
      tv.luna('com.webos.applicationManager/launch', { id: 'com.canvasframe.door' }).then(r => {
        if (r && r.returnValue) return;
        log('door: overlay app failed (' + ((r && r.errorText) || '?') + '), toast instead');
        tv.luna('com.webos.notification/createToast', { sourceId: 'com.canvasframe.home', message: '\uD83D\uDD14 ' + (LABEL[kind] || 'Doorbell') }).catch(() => {});
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
      const cam = cameraName || 'Front Door Camera';
      return Object.assign({}, state, { camera: state.kind === 'package' ? cam + ' \u00b7 package camera' : cam });
    },
    snapshot,
    status() {
      const d = door();
      const o = { configured: !!d, show: prefs.show, over: prefs.over, canOver: true, overPanel: true, secs: +prefs.secs || 45,
        secsOver: +prefs.secsOver || 15, corner: prefs.corner || 'br', size: prefs.size || 'm', kinds: prefs.kinds || KINDS, recent };
      if (d) Object.assign(o, { console: d.console, apiKey: !!d.apiKey, live: !!d.live, pkg: !!d.pkg, hookPath: '/api/doorbell/hook/' + d.hook });
      return o;
    },
    /** Per TV {show, over}; shared {console, apiKey, live, pkg} (blank = keep, "-" = clear); {remove:true}. */
    async set(b) {
      if ('show' in b) prefs.show = !!b.show;
      if ('over' in b) prefs.over = !!b.over;
      if ('secs' in b) prefs.secs = Math.max(5, Math.min(600, parseInt(b.secs, 10) || 45));
      if (Array.isArray(b.kinds)) prefs.kinds = KINDS.filter(k => b.kinds.includes(k));
      if ('secsOver' in b) prefs.secsOver = Math.max(5, Math.min(600, parseInt(b.secsOver, 10) || 15));
      if (/^(br|bl|tr|tl)$/.test(b.corner)) prefs.corner = b.corner;
      if (/^[sml]$/.test(b.size)) prefs.size = b.size;
      save();
      if (b.close) { state = null; return this.status(); }
      if (b.remove) { site.setDoor(null); return this.status(); }
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
      if (door() && door().apiKey) { try { o.snapshot = (await snapshot()).length; } catch (e) { o.snapshotError = e.message; } }
      if (b.here) here(kind, 'test' + Date.now()).catch(() => {}); else alert(kind);
      return o;
    },
  };
}

module.exports = { create };
