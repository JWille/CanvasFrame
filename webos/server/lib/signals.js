'use strict';
// Signals: what this TV knows about its surroundings, in one shape, shared over the mesh as the site item sig:<id> {list}.
// Integrations register a source (a function returning signals); outside sources (Home Assistant, a script, an ESP32...) post
// them with POST /api/signals {signals:[...]} (signed in or with an API key). Consumers (Rooms view, presence timeline,
// climate charts, later Home Assistant export) only read signals, never a particular integration.
//
// A signal: { key (unique per TV, e.g. "switchbot/c52a353085b0"), src (integration id), name, room? (room id override),
//   presence? "someone"|"nobody", since? (ms), t? (deg C), h? (%), light? (0-20 or %), co2? (ppm), bat? (%), rssi?, at (ms) }.
// Same in Signals.java.

const KEY = /^[A-Za-z0-9_.:\/-]{1,60}$/;
const NUMS = ['t', 'h', 'light', 'co2', 'bat', 'rssi', 'since'];
const POSTED_FOR = 15 * 60000;   // an outside signal counts this long after its last post

function clean(s, src) {
  if (!s || !KEY.test(String(s.key || ''))) return null;
  const o = { key: String(s.key), src: String(s.src || src || 'api').slice(0, 24), name: String(s.name || s.key).slice(0, 40), at: +s.at || Date.now() };
  if (s.room && /^[A-Za-z0-9_-]{1,40}$/.test(String(s.room))) o.room = String(s.room);
  if (s.where) o.where = String(s.where).slice(0, 30);   // a place that isn't one of the rooms (Garage, Kitchen freezer…)
  if (s.presence === 'someone' || s.presence === 'nobody') o.presence = s.presence;
  if (s.kind) o.kind = String(s.kind).slice(0, 24);
  for (const k of NUMS) if (s[k] != null && isFinite(+s[k])) o[k] = Math.round(+s[k] * 10) / 10;
  return o;
}

function create({ site, log }) {
  const sources = {};   // src -> () => [signal]
  const posted = {};    // 'api/' + key -> signal

  function collect() {
    const out = [];
    for (const src of Object.keys(sources)) {
      let list = [];
      try { list = sources[src]() || []; } catch (e) { log('signals: ' + src + ': ' + e.message); }
      for (const s of list) { const c = clean(s, src); if (c) out.push(c); }
    }
    for (const k of Object.keys(posted)) {
      if (Date.now() - posted[k].at > POSTED_FOR) delete posted[k]; else out.push(posted[k]);
    }
    return out;
  }
  const publish = push => site.signals(collect(), push);

  return {
    /** An integration's signals, asked for at every publish. */
    register(src, fn) { sources[src] = fn; },
    publish,
    collect,
    /** POST /api/signals: {signals:[{key, name, presence?, t?, ...}]} from outside. Returns how many were taken. */
    post(body, from) {
      const list = Array.isArray(body && body.signals) ? body.signals.slice(0, 50) : [];
      let n = 0, presence = false;
      for (const s of list) {
        const c = clean(Object.assign({}, s, { src: 'api', at: Date.now() }), 'api');
        if (!c) continue;
        c.key = 'api/' + c.key;
        if (c.presence && (!posted[c.key] || posted[c.key].presence !== c.presence)) presence = true;
        posted[c.key] = c;
        n++;
      }
      if (n) { log('signals: ' + n + ' from ' + from); publish(presence); }
      return { ok: true, taken: n };
    },
    posted: () => Object.values(posted),
  };
}

module.exports = { create, clean };
