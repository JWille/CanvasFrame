'use strict';
// Room history for the dashboards (Rooms card: presence timeline + climate charts), built from the shared site on every TV,
// so any dashboard can show it even when its own TV has no sensor. Every 30 s it reads all TVs' act:<id> (someone used a
// TV) and sig:<id> signals: presence changes become events, climate readings a point per sensor every 5 minutes. Kept 48 h
// in <data>/roomlog.json. Same logic in RoomLog.java. GET /api/rooms returns it.

const fs = require('fs');
const path = require('path');

const KEEP = 48 * 3600000, STEP = 5 * 60000, FRESH = 10 * 60000;

function create({ dataDir, site, log }) {
  const FILE = path.join(dataDir, 'roomlog.json');
  let st = { ev: [], clim: {}, last: { acts: {}, pres: {} } };
  try { st = Object.assign(st, JSON.parse(fs.readFileSync(FILE, 'utf8'))); } catch (e) { /* new */ }
  let lastSave = 0, cur = {};   // cur: key -> freshest reading { ..., dev }
  const heard = {};             // key -> dev -> { rssi, at } (which TV hears a sensor best: its room)

  function tick() {
    const v = site.view(), items = v.items || {}, now = Date.now();
    for (const k of Object.keys(items)) {
      const it = items[k];
      if (!it || it.x || !it.d) continue;
      const dev = k.slice(k.indexOf(':') + 1);
      if (k.startsWith('act:')) {
        const at = +it.d.at || 0;
        if (at > (st.last.acts[dev] || 0)) {
          if (now - at < KEEP) st.ev.push([at, 'act/' + dev, 'a', String(it.d.kind || ''), dev]);
          st.last.acts[dev] = at;
        }
      } else if (k.startsWith('sig:')) {
        for (const s of it.d.list || []) {
          if (s.presence) {
            const pk = dev + '|' + s.key;
            if (st.last.pres[pk] !== s.presence) {
              st.ev.push([+s.since || +s.at || now, s.key, 'p', s.presence, dev]);
              st.last.pres[pk] = s.presence;
            }
          }
          if (s.t != null || s.h != null || s.light != null || s.co2 != null) {
            const h = heard[s.key] = heard[s.key] || {};
            if (s.rssi != null) h[dev] = { rssi: +s.rssi, at: +s.at || now };
            const c = cur[s.key];
            if (!c || (+s.at || 0) > c.at) cur[s.key] = Object.assign({}, s, { at: +s.at || now, dev });
          }
        }
      }
    }
    const bucket = Math.floor(now / STEP) * STEP;
    for (const key of Object.keys(cur)) {
      const c = cur[key];
      if (now - c.at > FRESH) continue;
      const e = st.clim[key] = st.clim[key] || { hist: [] };
      e.name = c.name; e.kind = c.kind || ''; e.src = c.src;
      const last = e.hist[e.hist.length - 1];
      if (!last || last[0] < bucket) e.hist.push([bucket, c.t == null ? null : c.t, c.h == null ? null : c.h, c.light == null ? null : c.light, c.co2 == null ? null : c.co2]);
    }
    st.ev = st.ev.filter(e => now - e[0] < KEEP).sort((a, b) => a[0] - b[0]).slice(-4000);
    for (const key of Object.keys(st.clim)) {
      st.clim[key].hist = st.clim[key].hist.filter(p => now - p[0] < KEEP);
      if (!st.clim[key].hist.length) delete st.clim[key];
    }
    if (now - lastSave > 5 * 60000) { lastSave = now; try { fs.writeFileSync(FILE, JSON.stringify(st)); } catch (e) { log('roomlog: ' + e.message); } }
  }

  /** Which TV hears this sensor best right now (its room is the sensor's room unless the signal names one). */
  function bestDev(key) {
    const h = heard[key] || {}, now = Date.now();
    let best = null;
    for (const d of Object.keys(h)) if (now - h[d].at < FRESH && (!best || h[d].rssi > h[best].rssi)) best = d;
    return best || (cur[key] && cur[key].dev) || null;
  }

  return {
    start() { setTimeout(() => { try { tick(); } catch (e) { log('roomlog: ' + e.message); } }, 15000);
      setInterval(() => { try { tick(); } catch (e) { log('roomlog: ' + e.message); } }, 30000); },
    get() {
      const now = Date.now(), items = site.view().items || {};
      // Bluetooth sensors count only while added (sensor:<id>); a removed one's history goes.
      for (const key of Object.keys(st.clim)) if (key.startsWith('switchbot/')) {
        const it = items['sensor:' + key.slice(10)];
        if (!it || it.x) { delete st.clim[key]; delete cur[key]; }
      }
      const climate = Object.keys(st.clim).map(key => {
        const e = st.clim[key], c = cur[key] || {};
        return { key, name: e.name || key, kind: e.kind || '', src: e.src || '', dev: bestDev(key), room: c.room || null, where: c.where || null,
          t: c.t, h: c.h, light: c.light, co2: c.co2, bat: c.bat, at: c.at || null, fresh: !!c.at && now - c.at < FRESH, hist: e.hist };
      });
      return { now, keep: KEEP, ev: st.ev, climate };
    },
  };
}

module.exports = { create };
