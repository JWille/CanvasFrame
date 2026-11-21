'use strict';
/*
 * The shared "site": what every CanvasFrame TV in the home knows about all of them. Same behaviour as Site.java.
 *
 * One small JSON document, replicated on every TV, editable from any TV's dashboard (no master needed). It is a map of
 * items, each { d: data, t: time ms, by: device id, x: true if deleted }. Merging keeps, per item, the newer (t, by).
 *   dev:<id>    identity of a TV (name, brand, platform, dashboard addresses). Written only by that TV.
 *   env:<id>    that TV's live state (screen, backlight, light level...). Written only by that TV, about once a minute.
 *   place:<id>  where a TV is: room and coordinator priority. Written by any dashboard.
 *   room:<rid>  a room: name and the rooms next to it. Written by any dashboard.
 *   act:<id>    when someone last used that TV (remote, screen on, screen saver dismissed). Written only by that TV, pushed
 *               at once; TVs in the same room (strongly) and next-door rooms (gently) react to it.
 *   pics:<id>   the photo file names that TV has (for showing the same photo together). Written only by that TV.
 *   opt:saver   shared screen saver options: { sync: 'off'|'all'|'room', min }. Written by any dashboard.
 *   auth:<aid>  a dashboard authenticator (TOTP secret). Only ever sent TV-to-TV over the API-key links: browsers never get
 *               the secret, and only items from a peer using an API key are accepted. Deleting one removes it everywhere.
 *   photo:<pid> a photo in the mesh ({name}; pid = sha1(name)[0:16]). Deleted (x, keeping {name}) -> every TV deletes it.
 *               Every TV publishes its own photos; a TV missing one gets it pushed by exactly one TV that has it and
 *               reaches it (the lowest device id among those), a few per round.
 *   tag:<pid>   a photo's tags ({name, tags}). set:home: shared home-screen settings ({values}). set:cal: calendars
 *               ({calendars}; private links, so TV-to-TV only like auth). A TV's existing values enter with t = 1, so
 *               anything really changed in the mesh wins; a joining TV therefore takes the mesh's values.
 *   cmd:<cid>   a short-lived command for some TVs (e.g. "identify"). Each TV runs it once; it expires after 2 minutes.
 *
 * Replication goes over the Sync peers (API key + pinned certificate): every 15 s, and right after a local change, each
 * TV fetches every reachable peer's document, merges it, and sends back its merged one. A TV that can't call out (a
 * one-way network) is still kept current by the TVs that can reach it.
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const CMD_TTL = 2 * 60000;          // commands are ignored (and dropped) after this
const TOMBSTONE_TTL = 7 * 86400000; // deleted items are forgotten after this
const FRESH = 3 * 60000;            // a TV whose state is older than this counts as away

function create(dataDir, sync, cfg, log, me) {
  const FILE = path.join(dataDir, 'site.json');
  const ID_FILE = path.join(dataDir, 'device-id');
  let id;
  try { id = fs.readFileSync(ID_FILE, 'utf8').trim(); } catch (e) { /* new */ }
  if (!/^[a-z0-9]{8,32}$/.test(id || '')) { id = crypto.randomBytes(8).toString('hex'); fs.writeFileSync(ID_FILE, id); }

  let doc;
  try { doc = JSON.parse(fs.readFileSync(FILE, 'utf8')); } catch (e) { doc = { items: {} }; }
  if (!doc.items) doc.items = {};
  const save = () => { try { fs.writeFileSync(FILE + '.tmp', JSON.stringify(doc)); fs.renameSync(FILE + '.tmp', FILE); } catch (e) { log('site: save ' + e.message); } };
  const newer = (a, b) => !b || a.t > b.t || (a.t === b.t && String(a.by) > String(b.by));
  const done = new Set();   // commands already run here (by key)
  const peerState = {};     // peer id -> { ok, at, error }
  let fastUntil = 0;         // sync every 3 s until then (a sensor search)
  let handlers = null;      // set by start(): { envNow, onCommand, onChange }
  let lastAct = 0;

  function put(key, d, del) {
    const it = { d: del ? (key.startsWith('photo:') && d ? d : null) : d, t: Date.now(), by: id };
    if (del) it.x = true;
    const cur = doc.items[key];
    if (cur && it.t <= cur.t) it.t = cur.t + 1;   // a local edit always wins over what it replaces
    doc.items[key] = it;
    save();
  }

  /** Merge another TV's items in; returns true if anything changed. Owner-only items are only taken from their owner. */
  function merge(items, trusted) {
    let changed = false, authChanged = false, meshChanged = false;
    for (const k of Object.keys(items || {})) {
      const it = items[k];
      if (!it || typeof it.t !== 'number' || !/^(dev|env|act|pics|sig|scan|sensor|place|room|opt|auth|sso|photo|tag|set|cmd):[A-Za-z0-9_-]{1,40}$/.test(k)) continue;
      if ((k.startsWith('auth:') || k.startsWith('sso:') || k === 'set:cal' || k === 'set:door') && !trusted) continue;   // secrets / private links only from another TV's key
      if (/^(dev|env|act|pics|sig|scan):/.test(k) && k.slice(k.indexOf(':') + 1) === id) continue;              // nobody else speaks for this TV
      if (newer(it, doc.items[k])) {
        if ((k.startsWith('auth:') || k.startsWith('sso:')) && !it.x && !(it.d && it.d.secret)) continue;   // a copy without the secret: ignore
        if (k === 'set:cal' && !it.x && !(it.d && Array.isArray(it.d.calendars))) continue;   // a stripped copy: ignore
        if (k === 'set:door' && !it.x && !(it.d && it.d.hook)) continue;
        doc.items[k] = { d: it.x ? (k.startsWith('photo:') ? it.d : null) : it.d, t: it.t, by: String(it.by || ''), ...(it.x ? { x: true } : {}) }; changed = true;
        if (k.startsWith('auth:')) authChanged = true;
        if (/^(photo|tag|set):/.test(k)) meshChanged = true;
      }
    }
    if (changed) save();
    if (authChanged) reconcileAuth();
    if (meshChanged) reconcileMesh();
    return changed;
  }

  function prune() {
    const now = Date.now();
    let changed = false;
    for (const k of Object.keys(doc.items)) {
      const it = doc.items[k];
      if ((k.startsWith('cmd:') && now - it.t > CMD_TTL * 3) || (it.x && now - it.t > TOMBSTONE_TTL)) { delete doc.items[k]; changed = true; }
    }
    if (changed) save();
  }

  // ---------------------------------------------------------------- shared authenticators
  /** Make this TV's authenticators match the site: add shared ones, drop deleted ones, publish local ones. */
  function reconcileAuth() {
    if (!me.auth) return;
    const local = new Map(me.auth.list().map(a => [a.id, a]));
    for (const k of Object.keys(doc.items)) {
      if (!k.startsWith('auth:')) continue;
      const aid = k.slice(5), it = doc.items[k];
      if (it.x) { if (local.has(aid)) { me.auth.remove(aid); log('site: authenticator removed (shared)'); } }
      else if (!local.has(aid) && it.d && it.d.secret && me.auth.add(Object.assign({ id: aid }, it.d))) log('site: authenticator added (shared): ' + it.d.name);
    }
    for (const [aid, a] of local) if (!doc.items['auth:' + aid]) put('auth:' + aid, { name: a.name, secret: a.secret, added: a.added });
  }

  // ---------------------------------------------------------------- the mesh: photos, tags, home-screen settings, calendars
  const pid = n => crypto.createHash('sha1').update(String(n)).digest('hex').slice(0, 16);
  const alive = k => (doc.items[k] && !doc.items[k].x && doc.items[k].d) || null;
  /** A value this TV had before joining: oldest possible, so any real change in the mesh wins over it. */
  function putOld(key, d) { if (!doc.items[key]) { doc.items[key] = { d, t: 1, by: id }; save(); } }
  const sameTags = (a, b) => (a || []).slice().sort().join(',') === (b || []).slice().sort().join(',');
  const calNorm = l => (l || []).map(c => ({ name: c.name, url: c.url, color: c.color, on: c.on !== false }));
  const calSig = l => calNorm(l).map(c => [c.name, c.url, c.color, c.on].join('|')).join('\n');
  /** Settings published by another platform may differ in key order and number/string form: compare value by value. */
  const sameValues = (a, b) => Object.keys(a || {}).every(k => String(a[k]) === String((b || {})[k]));
  /** Make this TV match the mesh (delete photos deleted elsewhere, take tags/settings/calendars) and publish what's new here. */
  function reconcileMesh() {
    const m = me.mesh;
    if (!m) return;
    let local = new Set(m.photos());
    for (const k of Object.keys(doc.items)) {   // deletions first
      const it = doc.items[k];
      if (!k.startsWith('photo:') || !it.x || !it.d || !local.has(it.d.name)) continue;
      try { m.deletePhoto(it.d.name); log('site: photo deleted (shared): ' + it.d.name); } catch (e) { log('site: delete ' + e.message); }
    }
    local = new Set(m.photos());
    for (const n of local) if (!doc.items['photo:' + pid(n)] || (doc.items['photo:' + pid(n)].x && !local.has(n))) put('photo:' + pid(n), { name: n });
    for (const n of local) {   // tags
      const it = alive('tag:' + pid(n));
      if (it && it.name === n) { if (!sameTags(m.tagsFor(n), it.tags)) m.setTags(n, it.tags || []); }
      else if (!doc.items['tag:' + pid(n)] && m.tagsFor(n).length) putOld('tag:' + pid(n), { name: n, tags: m.tagsFor(n) });
    }
    const hs = alive('set:home');   // home-screen settings
    if (hs && hs.values) { if (!sameValues(hs.values, m.settings())) m.applySettings(hs.values); }
    else putOld('set:home', { values: m.settings() });
    const cal = alive('set:cal');   // calendars
    if (cal && Array.isArray(cal.calendars)) { if (calSig(cal.calendars) !== calSig(m.calendars())) m.applyCalendars(cal.calendars); }
    else putOld('set:cal', { calendars: calNorm(m.calendars()) });
  }
  const sent = {};   // peer dev -> {name: time} uploads sent lately (their photo list lags a round behind)
  /** Push photos the peer lacks, when this TV is the one responsible for them (lowest id among holders reaching it). */
  async function pushPhotos(peer, peerDev) {
    const m = me.mesh;
    if (!m || !peerDev) return;
    const pdev = alive('dev:' + peerDev), penv = alive('env:' + peerDev), ppics = alive('pics:' + peerDev);
    if (!pdev || pdev.photosIn === false || !ppics) return;
    if (penv && penv.free != null && penv.free < 250 * 1048576) return;   // keep room on a nearly full TV
    const theirs = new Set(ppics.names || []), recent = sent[peerDev] = sent[peerDev] || {};
    const holdersOf = n => Object.keys(doc.items).filter(k => k.startsWith('pics:') && ((alive(k) || {}).names || []).indexOf(n) >= 0)
      .map(k => k.slice(5)).filter(d => d === id || ((alive('env:' + d) || {}).links || []).indexOf(peerDev) >= 0);
    const todo = [];
    for (const n of m.photos().slice().sort()) {
      if (theirs.has(n) || (recent[n] && Date.now() - recent[n] < 600000)) continue;
      const it = doc.items['photo:' + pid(n)];
      if (!it || it.x) continue;
      if (holdersOf(n).sort()[0] !== id) continue;   // another TV takes this one
      todo.push(n);
      if (todo.length >= 4) break;
    }
    for (const n of todo) {
      await sync.call(peer, 'POST', '/api/upload', m.readPhoto(n),
        { 'X-Filename': encodeURIComponent(n), 'X-Tags': m.tagsFor(n).join(','), 'Content-Type': 'application/octet-stream' }, 60000);
      recent[n] = Date.now();
      log('site: photo copied to ' + (pdev.name || peerDev) + ': ' + n);
    }
  }

  // ---------------------------------------------------------------- this TV's own items
  let lastEnv = 0;
  async function refreshSelf(envNow) {
    const st = await me.status().catch(() => ({}));
    const urls = (me.urls() || []).filter(Boolean);
    const d = Object.assign({ name: st.name || st.model || 'TV', brand: st.brand || '', model: st.model || '', platform: 'webos', urls }, me.caps ? me.caps() : {});
    const cur = doc.items['dev:' + id];
    if (!cur || JSON.stringify(cur.d) !== JSON.stringify(d)) put('dev:' + id, d);
    const names = me.photos ? me.photos().slice().sort() : [];
    const cp = doc.items['pics:' + id];
    if (!cp || JSON.stringify((cp.d || {}).names) !== JSON.stringify(names)) put('pics:' + id, { names });
    if (Date.now() - lastEnv < 25000) return;   // state every ~30 s; replication runs every 15 s
    lastEnv = Date.now();
    const e = await envNow().catch(() => null);
    // links: the TVs this one reached in the last 2 minutes (so any dashboard knows who can copy photos to whom).
    const links = Object.values(peerState).filter(s => s.ok && s.dev && Date.now() - s.at < 120000).map(s => s.dev);
    if (e) put('env:' + id, Object.assign({ at: Date.now(), links }, e));
  }

  // ---------------------------------------------------------------- commands
  async function runCommands(onCommand) {
    const now = Date.now();
    for (const k of Object.keys(doc.items)) {
      if (!k.startsWith('cmd:') || done.has(k)) continue;
      const it = doc.items[k];
      if (it.x || !it.d || now - it.t > CMD_TTL) { done.add(k); continue; }
      const tgt = it.d.target || 'all';
      const room = (doc.items['place:' + id] && doc.items['place:' + id].d || {}).room;
      if (tgt !== 'all' && tgt !== 'dev:' + id && !(room && tgt === 'room:' + room)) { done.add(k); continue; }
      done.add(k);
      try { await onCommand(it.d, view()); } catch (e) { log('site: command ' + it.d.action + ' failed: ' + e.message); }
    }
  }

  // ---------------------------------------------------------------- replication
  let busy = null, again = false;
  function round(onCommand) {
    if (busy) { again = true; return busy; }
    busy = (async () => {
      do {
        again = false;
        for (const peer of sync.peers()) {
          const st = peerState[peer.id] = peerState[peer.id] || {};
          try {
            if (await sync.secure(peer)) sync.savePeers();   // moved to HTTPS / certificate recorded
            const theirs = await sync.call(peer, 'GET', '/api/site', undefined, null, 8000);
            if (theirs.self) st.dev = String(theirs.self);   // which TV this link reaches (for "copy photos")
            if (merge(theirs.items, true) && handlers.onChange) handlers.onChange();
            await sync.call(peer, 'POST', '/api/site', { merge: doc.items }, null, 8000);
            Object.assign(st, { ok: true, at: Date.now(), error: '' });
            await pushPhotos(peer, st.dev).catch(e => log('site: photo copy: ' + e.message));
          } catch (e) {
            Object.assign(st, { ok: false, at: Date.now(), error: /404|not found/i.test(e.message) ? 'older CanvasFrame (no shared setup)' : e.message });
          }
        }
        await runCommands(onCommand);
      } while (again);
    })().finally(() => { busy = null; });
    return busy;
  }

  /** The document for the dashboard and for peers, plus who coordinates right now. */
  /** withSecrets: only for another TV calling with an API key. Browsers get authenticators without their secret. */
  /**
   * Showing the same photo together: null when off, else { ms, names } = the change interval and the sorted photo names every
   * TV in the group has (all TVs, or with 'room' those in this TV's room), narrowed to those whose (shared) tags fit right
   * now, holiday ones first; all of them if none fit. Every TV computes the same from the same data and picks with
   * samePhoto(), so no TV coordinates. Screen savers and home-screen backgrounds both use it.
   */
  function syncPlanFor() {
    const items = doc.items, dd = k => (items[k] && !items[k].x && items[k].d) || null;
    const opt = dd('opt:saver') || {};
    if (!opt.sync || opt.sync === 'off' || !dd('pics:' + id)) return null;
    const room = (dd('place:' + id) || {}).room;
    if (opt.sync === 'room' && !room) return null;
    let pool = null;
    for (const k of Object.keys(items)) {
      if (!k.startsWith('dev:')) continue;
      const d = k.slice(4), dev = dd(k), pics = dd('pics:' + d);
      if (!dev || !pics) continue;
      if (opt.sync === 'room' && (dd('place:' + d) || {}).room !== room) continue;
      const set = new Set(pics.names || []);
      pool = pool ? pool.filter(n => set.has(n)) : (pics.names || []).slice();
    }
    if (!pool || pool.length < 2) return null;
    pool.sort();
    const tagsOf = n => { const t = dd('tag:' + crypto.createHash('sha1').update(n).digest('hex').slice(0, 16)); return t && t.name === n ? t.tags || [] : cfg.tagsFor(n); };
    let fit = pool.filter(n => cfg.fitsNow(tagsOf(n)));
    const hol = fit.filter(n => cfg.isHolidayPick(tagsOf(n)));
    if (hol.length >= 2) fit = hol;
    const names = fit.length >= 2 ? fit : pool;
    return { ms: Math.max(1, Math.min(240, +opt.min || 10)) * 60000, names, fit: fit.length, total: pool.length };
  }

  function view(withSecrets) {
    const now = Date.now();
    let items = doc.items;
    if (!withSecrets) {
      items = Object.assign({}, items);
      for (const k of Object.keys(items)) if (k.startsWith('auth:')) {
        const it = items[k];
        items[k] = Object.assign({}, it, { d: it.d ? { name: it.d.name, added: it.d.added } : null });
      }
      if (items['set:door'] && items['set:door'].d) {   // doorbell: API key, stream tokens, webhook token are TV-to-TV only
        const d = items['set:door'].d;
        const bd = { console: d.console, apiKey: !!d.apiKey, live: !!d.live, pkg: !!d.pkg };
        if (Array.isArray(d.alerts)) bd.alerts = d.alerts.map(a => ({ id: a.id, name: a.name, camera: a.camera || '', quiet: !!a.quiet, stream: !!a.stream }));   // no stream tokens
        items['set:door'] = Object.assign({}, items['set:door'], { d: bd });
      }
      for (const k of Object.keys(items)) if (k.startsWith('sso:')) {   // the mesh's sign-in pass secret: TV-to-TV only
        const it = items[k];
        items[k] = Object.assign({}, it, { d: it.d ? { created: it.d.created } : null });
      }
      if (items['set:cal'] && items['set:cal'].d) items['set:cal'] = Object.assign({}, items['set:cal'], { d: { count: (items['set:cal'].d.calendars || []).length } });
    }
    const devs = Object.keys(items).filter(k => k.startsWith('dev:') && !items[k].x).map(k => k.slice(4));
    // Priority 1-9 as set; unset = 50 (automatic, after any set ones); 99 = never coordinate.
    const prio = d => { const p = items['place:' + d]; return p && p.d && p.d.priority != null && isFinite(p.d.priority) ? +p.d.priority : 50; };
    const fresh = d => { const e = items['env:' + d]; return e && e.d && now - (e.d.at || e.t) < FRESH; };
    const coord = devs.filter(d => (fresh(d) || d === id) && prio(d) < 99).sort((a, b) => prio(a) - prio(b) || (a < b ? -1 : 1))[0] || null;
    const plan = syncPlanFor();
    return { self: id, coordinator: coord, items, now, plan: plan ? { ms: plan.ms, names: plan.names, fit: plan.fit, total: plan.total } : null };
  }

  return {
    id,
    view,
    merge,
    /** This TV's signals (lib/signals.js): sig:<id> {list}, only written when they changed; push = tell the others now. */
    /** Sync every few seconds until `until` (a sensor search: other TVs' scan results arrive quickly). */
    fast(until) { if (until > fastUntil) fastUntil = until; },
    /** This TV's sensor-search results: scan:<id> {until, found:[...]}, pushed at once. */
    scanResults(found, until) {
      put('scan:' + id, { until, found });
      if (handlers) round(handlers.onCommand);
    },
    signals(list, push) {
      const d = { list }, cur = doc.items['sig:' + id];
      if (cur && JSON.stringify(cur.d) === JSON.stringify(d)) return;
      put('sig:' + id, d);
      if (push && handlers) round(handlers.onCommand);
    },
    /** The Sync peer record id that reaches TV `devId`, or null. */
    peerFor: devId => { for (const pid of Object.keys(peerState)) if (peerState[pid].dev === devId && peerState[pid].ok) return pid; return null; },
    peerStates: () => sync.peers().map(p => Object.assign({ id: p.id, name: p.name, url: p.url }, peerState[p.id] || {})),
    /** A change from this TV's dashboard: {set: {key: data}}, {del: [keys]}, {identify: target}. */
    edit(body) {
      for (const k of Object.keys(body.set || {})) {
        if (!/^(place|room|opt|sensor):[A-Za-z0-9_-]{1,40}$/.test(k)) throw new Error('That item can only be changed by its own TV');
        put(k, body.set[k]);
      }
      for (const k of body.del || []) {
        if (!/^(place|room|sensor):[A-Za-z0-9_-]{1,40}$/.test(k)) throw new Error('That item can only be changed by its own TV');
        put(k, null, true);
      }
      // "Find sensors" from a dashboard: every TV that can scan listens for a while; all TVs sync faster meanwhile.
      if (body.blescan) {
        const until = Date.now() + Math.max(10, Math.min(120, +body.blescan || 45)) * 1000;
        put('cmd:' + Date.now().toString(36) + crypto.randomBytes(2).toString('hex'), { action: 'blescan', until, target: 'all', from: id });
        this.fast(until);
      }
      // Activity reported from outside (later Home Assistant motion; for now the API): this TV's room counts as in use.
      if (body.activity) { lastAct = 0; this.activity(String(body.activity).slice(0, 40)); }
      // Fix an inconsistency: TV `from` copies the photos TV `to` is missing (run by `from`, over its own link).
      if (body.copy && /^[a-z0-9]{8,32}$/.test(String(body.copy.from)) && /^[a-z0-9]{8,32}$/.test(String(body.copy.to)))
        put('cmd:' + Date.now().toString(36) + crypto.randomBytes(2).toString('hex'),
          { action: 'copyphotos', to: String(body.copy.to), target: 'dev:' + body.copy.from, from: id });
      // Doorbell (from the webhook on one TV): every TV shows it.
      if (body.doorbell && typeof body.doorbell === 'object')
        put('cmd:' + Date.now().toString(36) + crypto.randomBytes(2).toString('hex'),
          { action: 'doorbell', kind: String(body.doorbell.kind || 'ring').slice(0, 20), event: String(body.doorbell.event || '').slice(0, 40), target: 'all', from: id });
      // "Sign out on all TVs": every TV ends the sessions of this sign-in chain.
      if (/^[a-f0-9]{8,64}$/.test(String(body.signout || '')))
        put('cmd:' + Date.now().toString(36) + crypto.randomBytes(2).toString('hex'), { action: 'signout', chain: String(body.signout), target: 'all', from: id });
      if (body.identify) put('cmd:' + Date.now().toString(36) + crypto.randomBytes(2).toString('hex'),
        { action: 'identify', target: String(body.identify), from: id });
    },
    /** Someone used this TV: record it and tell the others now (at most every 20 s). */
    activity(kind) {
      const now = Date.now();
      if (now - lastAct < 20000) return;
      lastAct = now;
      put('act:' + id, { at: now, kind: String(kind || '') });
      if (handlers) round(handlers.onCommand);
    },
    /**
     * Latest activity near this TV: { same, next } = newest act time in this TV's room and in rooms next to it (0 if none).
     * Other TVs only; this TV's own use ends its own screen saver anyway.
     */
    /**
     * Showing the same photo together: null when off, else { ms, names } = the change interval and the sorted photo names
     * every TV in the group has (all TVs, or with 'room' those in this TV's room). Screen savers and home-screen backgrounds
     * both use it, so a TV that can't run CanvasFrame's screen saver (webOS 10) still matches on its home screen.
     * Every TV computes the same thing from the shared document and picks with samePhoto(), so no TV coordinates.
     */
    syncPlan: () => syncPlanFor(),
    /** Other TVs sharing a light-sensor reading (env.roomLight): [{id, name, pct, at, fresh, sameRoom}] (as Site.lightSources). */
    lightSources() {
      const items = doc.items, dd = k => (items[k] && !items[k].x && items[k].d) || null;
      const mine = (dd('place:' + id) || {}).room, out = [];
      for (const k of Object.keys(items)) {
        if (!k.startsWith('env:') || k.slice(4) === id) continue;
        const did = k.slice(4), e = dd(k), dev = dd('dev:' + did);
        if (!e || !dev || !('roomLight' in e)) continue;
        const rl = e.roomLight, room = (dd('place:' + did) || {}).room;
        out.push({ id: did, name: dev.name || 'TV', pct: rl ? +rl.pct || 0 : 0, at: +e.at || 0,
          fresh: !!rl && Date.now() - (+e.at || 0) < 180000, sameRoom: !!mine && mine === room });
      }
      return out;
    },
    nearby() {
      const items = doc.items, dd = k => (items[k] && !items[k].x && items[k].d) || null;
      const mine = (dd('place:' + id) || {}).room, out = { same: 0, next: 0 };
      if (!mine) return out;
      const next = (dd('room:' + mine) || {}).next || [];
      for (const k of Object.keys(items)) {
        if (!k.startsWith('act:') || k.slice(4) === id) continue;
        const a = dd(k), r = (dd('place:' + k.slice(4)) || {}).room;
        if (!a || !r || !isFinite(a.at)) continue;
        if (r === mine) out.same = Math.max(out.same, a.at);
        else if (next.indexOf(r) >= 0) out.next = Math.max(out.next, a.at);
      }
      return out;
    },
    start(envNow, onCommand, onChange) {
      handlers = { envNow, onCommand, onChange };
      const tick = async () => { prune(); reconcileAuth(); try { reconcileMesh(); } catch (e) { log('site: mesh ' + e.message); } await refreshSelf(envNow).catch(e => log('site: ' + e.message)); await round(onCommand); };
      setTimeout(tick, 5000);
      setInterval(tick, 15000);
      setInterval(() => { if (Date.now() < fastUntil) round(onCommand).catch(() => {}); }, 3000);   // during a sensor search
    },
    /** An authenticator was added on this TV: share it now. */
    authChanged() { reconcileAuth(); if (handlers) round(handlers.onCommand); },
    /** The shared doorbell setup (set:door, with its secrets) or null. */
    door() { const it = doc.items['set:door']; return it && !it.x ? it.d : null; },
    setDoor(d) { put('set:door', d || null, !d); if (handlers) round(handlers.onCommand); },
    /** The mesh secret for sign-in passes (made here if no TV has one yet); null while sign-in is off (enabledFn). */
    ssoSecret(enabled) {
      if (!enabled) return null;
      const it = doc.items['sso:key'];
      if (it && !it.x && it.d && it.d.secret) return it.d.secret;
      const secret = crypto.randomBytes(32).toString('hex');
      put('sso:key', { secret, created: Date.now() });
      if (handlers) round(handlers.onCommand);
      return secret;
    },
    /** An authenticator was removed on this TV: remove it on every TV. */
    authRemoved(aid) { if (/^[a-f0-9]{6,40}$/.test(String(aid))) { put('auth:' + aid, null, true); if (handlers) round(handlers.onCommand); } },
    /** Sign-in was reset on this TV (lock-out recovery): remove every shared authenticator everywhere, or the others would hand them back. */
    authReset() {
      for (const k of Object.keys(doc.items)) if ((k.startsWith('auth:') || k.startsWith('sso:')) && !doc.items[k].x) put(k, null, true);
      log('site: sign-in reset, shared authenticators removed');
      if (handlers) round(handlers.onCommand);
    },
    /** Mesh edits made on this TV: share them now. */
    photoAdded(name) { if (me.mesh) { put('photo:' + pid(name), { name }); const t = me.mesh.tagsFor(name); if (t.length) put('tag:' + pid(name), { name, tags: t }); } if (handlers) round(handlers.onCommand); },
    photoDeleted(name) { put('photo:' + pid(name), { name }, true); put('tag:' + pid(name), null, true); if (handlers) round(handlers.onCommand); },
    tagsChanged(name) { if (me.mesh) put('tag:' + pid(name), { name, tags: me.mesh.tagsFor(name) }); if (handlers) round(handlers.onCommand); },
    settingsChanged() { if (me.mesh) put('set:home', { values: me.mesh.settings() }); if (handlers) round(handlers.onCommand); },
    calendarsChanged() { if (me.mesh) put('set:cal', { calendars: calNorm(me.mesh.calendars()) }); if (handlers) round(handlers.onCommand); },
    /** After a local edit: replicate now (push to reachable peers, run commands). */
    now() { return handlers ? round(handlers.onCommand) : Promise.resolve(); },
    /** After a peer pushed items here: just run any commands meant for this TV (no round: that would ping-pong). */
    commands() { return handlers ? runCommands(handlers.onCommand) : Promise.resolve(); },
  };
}

/** The photo for time slot `slot` out of n (same function in Site.java and the webOS screen saver QML). */
function hash32(x) {
  let h = x >>> 0;
  h = Math.imul(h ^ (h >>> 16), 0x45d9f3b) >>> 0;
  h = Math.imul(h ^ (h >>> 16), 0x45d9f3b) >>> 0;
  return (h ^ (h >>> 16)) >>> 0;
}
function samePhoto(slot, n) {
  let i = hash32(slot) % n;
  if (n > 1 && i === hash32(slot - 1) % n) i = (i + 1) % n;   // never the same photo twice in a row
  return i;
}

/** For `server.js --reset-auth` when no server is running: mark every shared authenticator deleted in site.json. */
function tombstoneAuthFile(dataDir) {
  const file = path.join(dataDir, 'site.json');
  let doc, id = '';
  try { doc = JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) { return; }
  try { id = fs.readFileSync(path.join(dataDir, 'device-id'), 'utf8').trim(); } catch (e) { /* none */ }
  for (const k of Object.keys(doc.items || {})) {
    const it = doc.items[k];
    if ((k.startsWith('auth:') || k.startsWith('sso:')) && !it.x) doc.items[k] = { d: null, t: Math.max(Date.now(), it.t + 1), by: id, x: true };
  }
  fs.writeFileSync(file, JSON.stringify(doc));
}

module.exports = { create, samePhoto, tombstoneAuthFile };
