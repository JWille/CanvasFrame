'use strict';
// Room light from LG's own light sensor (the "Eye Q" sensor used by AI Brightness / Energy Saving).
// luna://com.webos.service.pqcontroller/getEyeqSensorData {subscribe:true} streams
// {sensorData:{luminance, visibleLuminance}} about every 0.7 s, but only while LG's AI Brightness (aiPicture ai_Brightness)
// or Energy Saving Auto is on. "Dim by room light" (setting light_dim) therefore switches AI Brightness on, and back off
// only if CanvasFrame was the one that switched it on (remembered in <data>/roomlight.json).
// Tested 2026-11-20: AI Brightness doesn't override the backlight CanvasFrame writes, so the two don't fight.
//
// bright(): 0 (dark room) .. 1 (bright room), from luminance on a log scale (0 = dark, REF and above = bright), shifted by
// light_offset (-50..+50, + = treat the room as brighter = dim less). null when there's no fresh reading.

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const REF = 60;                 // luminance that counts as a fully bright room (daylight in a bright room read ~66 on an OLED C4)
const STALE = 30000;            // a reading older than this doesn't count
const HIST_MIN = 24 * 60;       // one point per minute, 24 h

function create(dataDir, luna, cfg, log) {
  let peers = () => [];   // site.lightSources, set by server.js
  const FILE = path.join(dataDir, 'roomlight.json');
  let saved = {};
  try { saved = JSON.parse(fs.readFileSync(FILE, 'utf8')) || {}; } catch (e) { saved = {}; }
  const save = () => { try { fs.writeFileSync(FILE, JSON.stringify(saved)); } catch (e) { /* next time */ } };
  let hist = Array.isArray(saved.hist) ? saved.hist.filter(p => Date.now() - p[0] < HIST_MIN * 60000) : [];
  let cur = null;               // { lum, vis, at }
  let present = null;           // Eyeqsensorcheck from the service
  let ai = null;                // LG's AI Brightness on?
  let minute = null;            // { m, sum, n } running average for the history
  let proc = null, retry = 5000, onChange = () => {};

  function addPoint(lum) {
    const m = Math.floor(Date.now() / 60000);
    if (minute && minute.m !== m) {
      hist.push([minute.m * 60000, Math.round(minute.sum / minute.n * 10) / 10]);
      while (hist.length > HIST_MIN) hist.shift();
      minute = null;
      if (m % 10 === 0) { saved.hist = hist; save(); }
    }
    if (!minute) minute = { m, sum: 0, n: 0 };
    minute.sum += lum; minute.n++;
  }

  function listen() {
    if (proc) return;
    let buf = '';
    try {
      proc = spawn('/usr/bin/luna-send', ['-i', 'luna://com.webos.service.pqcontroller/getEyeqSensorData', JSON.stringify({ subscribe: true })]);
    } catch (e) { proc = null; return; }
    proc.stdout.on('data', d => {
      buf += String(d);
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1);
        if (!line) continue;
        let j; try { j = JSON.parse(line); } catch (e) { continue; }
        if ('Eyeqsensorcheck' in j) present = !!j.Eyeqsensorcheck;
        const s = j.sensorData;
        if (s && isFinite(s.luminance)) {
          const was = bright();
          cur = { lum: +s.luminance, vis: +s.visibleLuminance, at: Date.now() };
          addPoint(cur.lum);
          retry = 5000;
          const now = bright();
          if (was == null || Math.abs(now - was) >= 0.08) onChange();   // a real change, not sensor noise
        }
      }
    });
    proc.on('exit', () => { proc = null; setTimeout(listen, retry); retry = Math.min(retry * 2, 300000); });
    proc.on('error', () => {});
  }

  async function readAi() {
    const r = await luna('com.webos.service.settings/getSystemSettings', { category: 'aiPicture', keys: ['ai_Brightness'] }).catch(() => null);
    ai = r && r.settings && r.settings.ai_Brightness ? r.settings.ai_Brightness === 'on' : null;
    return ai;
  }
  async function setAi(on) {
    const r = await luna('com.webos.service.settings/setSystemSettings', { category: 'aiPicture', settings: { ai_Brightness: on ? 'on' : 'off' } }).catch(() => null);
    if (r && r.returnValue !== false) { ai = on; log('room light: LG AI Brightness ' + (on ? 'on' : 'off')); return true; }
    return false;
  }

  /** Follow the setting: AI Brightness on while "Dim by room light" uses this TV's own sensor (source auto or self); off
   *  again only if CanvasFrame turned it on. A source that is another TV doesn't need this TV's sensor. */
  async function apply() {
    const src = cfg.get().light_src || 'auto';
    const want = !!cfg.get().light_dim && (src === 'auto' || src === 'self');
    const on = await readAi();
    if (on == null) return;   // a TV without AI Brightness
    if (want && !on) { if (await setAi(true)) { saved.weTurnedOn = true; save(); } }
    else if (!want && on && saved.weTurnedOn) { if (await setAi(false)) { saved.weTurnedOn = false; save(); } }
    else if (!want && !on && saved.weTurnedOn) { saved.weTurnedOn = false; save(); }
  }

  function fresh() { return cur && Date.now() - cur.at < STALE ? cur : null; }
  /** This TV's own reading, 0..1 (also what it shares with the other TVs). */
  function own() {
    const c = fresh();
    return c ? Math.max(0, Math.min(1, Math.log(1 + Math.max(0, c.lum)) / Math.log(1 + REF))) : null;
  }
  /** The reading from the chosen source: { v 0..1, from, id } or null. auto = this TV's sensor, else the freshest TV in
   *  this room; a chosen TV without a fresh reading = null (dimming follows the clock). */
  function reading() {
    const src = cfg.get().light_src || 'auto';
    const o = own();
    if (src === 'self' || (src === 'auto' && o != null)) return o == null ? null : { v: o, from: 'this TV', id: 'self' };
    const list = (() => { try { return peers() || []; } catch (e) { return []; } })().filter(s => s.fresh);
    const pick = src === 'auto' ? list.filter(s => s.sameRoom).sort((a, b) => b.at - a.at)[0] : list.find(s => s.id === src);
    return pick ? { v: pick.pct / 100, from: pick.name, id: pick.id } : null;
  }
  const raw = () => { const r = reading(); return r ? r.v : null; };
  function bright() {
    const r = raw();
    if (r == null) return null;
    const off = Math.max(-50, Math.min(50, +cfg.get().light_offset || 0)) / 100;
    return Math.max(0, Math.min(1, r + off));
  }

  return {
    start() { listen(); apply().catch(() => {}); setInterval(() => readAi().catch(() => {}), 300000); },
    apply,
    setOnChange(fn) { onChange = fn; },
    setPeers(fn) { peers = fn; },
    /** 0..1 room brightness after the offset, or null (no sensor, or AI Brightness off). */
    bright,
    /** Use the room light for dimming right now? (switched on and a fresh reading) */
    active() { return !!cfg.get().light_dim && bright() != null; },
    status() {
      const c = fresh();
      const h = hist.concat(minute ? [[minute.m * 60000, Math.round(minute.sum / minute.n * 10) / 10]] : []);
      const r = reading(), o = own();
      const sources = [{ id: 'auto', name: 'Automatic: this TV\'s sensor, else a TV in this room' },
        { id: 'self', name: 'This TV\'s own sensor' + (o == null ? ' (idle)' : ''), pct: o == null ? null : Math.round(o * 100) }]
        .concat(((() => { try { return peers() || []; } catch (e) { return []; } })()).map(s => ({ id: s.id,
          name: s.name + (s.sameRoom ? ' (same room)' : '') + (s.fresh ? '' : ' (no reading now)'), pct: s.fresh ? s.pct : null })));
      return { available: (present !== false && ai != null) || sources.length > 2, present, ai, on: !!cfg.get().light_dim,
        offset: +cfg.get().light_offset || 0, src: cfg.get().light_src || 'auto', sources, from: r ? r.from : null,
        lum: c ? c.lum : null, visible: c ? c.vis : null, raw: raw(), bright: bright(), ref: REF, at: c ? c.at : null,
        weTurnedOn: !!saved.weTurnedOn, history: h };
    },
    /** For the shared site (env): compact, so the other TVs and Home Assistant can see the room light. */
    env() { const c = fresh(); return c ? { lum: c.lum, pct: Math.round(own() * 100) } : null; },   // only its own reading
  };
}

module.exports = { create };
