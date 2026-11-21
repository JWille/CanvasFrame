'use strict';
/*
 * Ad hoc "Sync to other TVs": copies this TV's home-screen settings, calendars and (optionally) photos with their
 * tags to other CanvasFrame TVs, straight TV-to-TV over their normal dashboard API. No server needed.
 * Each target is reached with an API key created on that TV (Privacy > Dashboard sign-in > API keys), so it
 * works when the target has sign-in turned on. Per-TV things (hidden apps, TV settings, privacy) are never sent.
 */
const fs = require('fs');
const path = require('path');
const http = require('http');
const Tls = require('./tls');

const SETTINGS = ['weather_on', 'weather_place', 'weather_lat', 'weather_lon', 'units', 'clock', 'show_dash', 'auto_hide_sec', 'controls_always', 'photo_min'];

const PIN_CHANGED = "That TV's security certificate has changed since it was added, so its key wasn't sent. If that TV was reset or"
  + ' reinstalled, remove it here and add it again with a new key; otherwise something on the network may be intercepting the connection.';

/**
 * Before any key is sent: move a TV added as http:// to its HTTPS address (found via its public /api/tls, no key sent),
 * and on first contact record the certificate it presents. Afterwards keys only go to that exact certificate.
 * A TV running an older CanvasFrame without HTTPS stays on http://. Resolves true if the peer record changed.
 */
async function secure(peer) {
  let changed = false;
  if (/^http:\/\//.test(peer.url)) {
    const t = await Tls.fetchJson(peer.url + '/api/tls', null);
    if (!t || !(t.port > 0) || t.ready === false) return false;
    peer.url = 'https://' + peer.url.replace(/^http:\/\//, '').replace(/:\d+$/, '') + ':' + t.port;
    changed = true;
  }
  if (/^https:\/\//.test(peer.url) && !peer.pin) {
    const seen = {};
    await Tls.fetchJson(peer.url + '/api/tls', Tls.pinnedAgent(null, seen));
    if (!seen.fp) throw new Error("That TV didn't present a certificate");
    peer.pin = seen.fp;
    changed = true;
  }
  return changed;
}

function normUrl(u) {
  u = String(u || '').trim().replace(/\/+$/, '');
  if (!/^https?:\/\//.test(u)) u = 'http://' + u;
  if (!/:\d+$/.test(u.replace(/^https?:\/\//, ''))) u += ':8080';
  return u;
}

/** One request to a peer. body: object (JSON) or Buffer (raw upload). */
function call(peer, method, p, body, headers, timeoutMs) {
  return new Promise((resolve, reject) => {
    const u = new URL(peer.url + p);
    const raw = Buffer.isBuffer(body) ? body : body !== undefined ? Buffer.from(JSON.stringify(body)) : null;
    const h = Object.assign({ 'X-CanvasFrame': '1', 'X-CanvasFrame-Key': peer.key || '' }, headers || {});
    if (raw) { h['Content-Length'] = raw.length; if (!Buffer.isBuffer(body)) h['Content-Type'] = 'application/json'; }
    let agent;
    if (u.protocol === 'https:') {
      if (!peer.pin) return reject(new Error('No certificate recorded for that TV yet'));
      agent = Tls.pinnedAgent(peer.pin, null);   // the key is only sent once the certificate matches
    }
    const req = http.request({ host: u.hostname, port: u.port, path: u.pathname + u.search, method, headers: h, agent,
      timeout: timeoutMs || 60000 }, res => {
      const chunks = [];
      res.on('data', d => chunks.push(d));
      res.on('end', () => {
        let j = {};
        try { j = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'); } catch (e) { /* not JSON */ }
        if (res.statusCode === 401) return reject(Object.assign(new Error("That TV doesn't recognise the saved key (it was revoked or replaced). Create a new key on that TV, then remove and re-add the TV here."), { badKey: true }));
        if (res.statusCode >= 400) return reject(new Error(j.error || 'HTTP ' + res.statusCode));
        resolve(j);
      });
    });
    req.on('timeout', () => req.destroy(new Error('no answer')));
    req.on('error', e => { if (e.pinMismatch) return reject(new Error(PIN_CHANGED));
      const err = new Error(e.message === 'no answer' ? 'no answer' : 'cannot reach it (' + (e.code || e.message) + ')');
      if (e.message === 'no answer' || /ECONN|EHOST|ENET|ETIMEDOUT/.test(e.code || '')) err.unreachable = true; reject(err); });
    if (raw) req.write(raw);
    req.end();
  });
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

function create(cfg, photosDir, log, myIp, myName) {
  const explain = (peer, e) => e && e.unreachable ? `This TV (${myIp() || 'this TV'}) can't connect to ${peer.url.replace(/^https?:\/\//, '')}. Your router or firewall probably doesn't allow connections from this TV's network to that one. Sync from the other TV instead (it can often reach this one), or allow the connection on your router.` : (e && e.message) || String(e);
  const peers = () => cfg.get().peers || [];
  const savePeers = list => cfg.setPeers(list);

  return {
    SETTINGS, normUrl, call, secure,
    /** The peer records themselves (with keys): for the shared site's replication only, never for browsers. */
    peers,
    savePeers: () => savePeers(peers()),
    list: () => peers().map(p => ({ id: p.id, name: p.name, url: p.url, brand: p.brand || '', lastSync: p.lastSync || 0, pin: p.pin || '' })),

    /** The list, after checking each TV can be reached from here (and picking up name changes). */
    async check() {
      const all = peers();
      await Promise.all(all.map(async p => {
        try { await secure(p); const st = await call(p, 'GET', '/api/status', undefined, null, 4000); p.reachable = true; p.why = ''; p.badKey = false;
          if (st.name || st.model) p.name = st.name || st.model; if (st.brand) p.brand = st.brand; }
        catch (e) { p.reachable = false; p.why = explain(p, e); p.badKey = !!e.badKey; }
      }));
      savePeers(all.map(p => { const c = Object.assign({}, p); delete c.reachable; delete c.why; delete c.badKey; return c; }));
      return all.map(p => ({ id: p.id, name: p.name, url: p.url, brand: p.brand || '', lastSync: p.lastSync || 0, pin: p.pin || '', reachable: p.reachable, why: p.why, badKey: p.badKey }));
    },

    async add(url, key) {
      const peer = { url: normUrl(url), key: String(key || '').trim() };
      if (!peer.key) throw new Error('Paste the API key created on that TV');
      if (peers().some(p => p.url === peer.url)) throw new Error('That TV is already in the list');
      let st;
      try { await secure(peer); } catch (e) { throw new Error(explain(peer, e)); }
      if (peers().some(p => p.url === peer.url)) throw new Error('That TV is already in the list');
      try { st = await call(peer, 'GET', '/api/status', undefined, null, 8000); await call(peer, 'GET', '/api/settings', undefined, null, 8000); }   // proves the key works
      catch (e) { throw new Error(explain(peer, e)); }
      peer.name = st.name || st.model || peer.url;
      peer.brand = st.brand || '';
      peer.id = Date.now().toString(36);
      savePeers(peers().concat([peer]));
      log('sync: added ' + peer.name + ' at ' + peer.url);
    },
    remove(id) { savePeers(peers().filter(p => p.id !== id)); },

    /** Copy to each target; returns per-target results. */
    async run(ids, what) {
      const s = cfg.get(), results = [];
      const me = myName ? await myName() : 'another TV';
      // On-TV feedback (on unless turned off): open the target's home screen first so the changes appear in front of
      // whoever is watching, with a message before and after. Older CanvasFrame versions just ignore the message.
      const show = what.show !== false;
      const notify = (peer, message) => call(peer, 'POST', '/api/home', { message }, null, 8000).catch(() => {});
      for (const peer of peers().filter(p => ids.indexOf(p.id) >= 0)) {
        const r = { name: peer.name, ok: true, done: [] };
        try { await secure(peer); }   // move to HTTPS / record the certificate before any key is sent
        catch (e) { r.ok = false; r.error = explain(peer, e); results.push(r); continue; }
        if (show) { await notify(peer, `Syncing from ${me}…`); await sleep(2500); }
        try {
          if (what.settings) {
            const out = {};
            for (const k of SETTINGS) out[k] = s[k];
            if (what.theme) out.theme = s.theme;
            if (!s.weather_lat) { delete out.weather_lat; delete out.weather_lon; delete out.weather_place; }
            await call(peer, 'POST', '/api/settings', out);
            r.done.push(what.theme ? 'settings + theme' : 'settings');
          }
          if (what.calendars) {
            await call(peer, 'POST', '/api/calendars', { action: 'replace',
              calendars: (s.calendars || []).map(c => ({ name: c.name, url: c.url, color: c.color, on: c.on !== false })) });
            r.done.push((s.calendars || []).length + ' calendar' + ((s.calendars || []).length === 1 ? '' : 's'));
          }
          if (what.photos) {
            const theirs = {};
            for (const p of (await call(peer, 'GET', '/api/photos')).photos || []) theirs[p.name] = p;
            let added = 0, retagged = 0;
            const mine = fs.readdirSync(photosDir).filter(n => /\.(jpe?g|png|webp)$/i.test(n) && n[0] !== '.');
            for (const n of mine) {
              const tags = cfg.tagsFor(n);
              if (!theirs[n]) {
                await call(peer, 'POST', '/api/upload', fs.readFileSync(path.join(photosDir, n)),
                  { 'X-Filename': encodeURIComponent(n), 'X-Tags': tags.join(','), 'Content-Type': 'application/octet-stream' });
                added++;
              } else if ((theirs[n].tags || []).slice().sort().join(',') !== tags.slice().sort().join(',')) {
                await call(peer, 'POST', '/api/photos/tags', { name: n, tags });
                retagged++;
              }
            }
            r.done.push(`${added} photo${added === 1 ? '' : 's'} added${retagged ? `, ${retagged} retagged` : ''}`);
          }
          peer.lastSync = Date.now();
        } catch (e) { r.ok = false; r.error = explain(peer, e); }
        if (show && !(r.error && /can't connect/.test(r.error))) await notify(peer, r.ok ? `Synced from ${me}: ${r.done.join(', ')}` : `Sync from ${me} failed`);
        results.push(r);
      }
      savePeers(peers());
      log('sync: ' + results.map(r => r.name + (r.ok ? ' ok' : ' failed: ' + r.error)).join('; '));
      return results;
    },
  };
}

module.exports = { create, normUrl };
