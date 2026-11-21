'use strict';
// Bluetooth sensors (SwitchBot): they broadcast their readings in the open (no app, account or pairing).
// - Normal: every 2 minutes an 8 s scan through LG's Bluetooth service (luna bluetooth2/le/startScan, subscribed; killing
//   luna-send ends the scan). Only sensors the user added (site items sensor:<id>) are kept and shared as signals.
// - Search ("Find sensors" on any dashboard -> site command blescan {until}): a continuous scan until then; every 3 s the
//   sensors heard (any decodable model, added or not, with their current signal strength) go to the site as scan:<id>, so
//   every dashboard can list them and spot the one held next to a TV. Nothing else heard is ever kept.
// Models: Hub 2 (temperature, humidity, light level), Meter, Meter Plus, Outdoor Meter, Meter Pro (temperature, humidity),
// Meter Pro CO2 (+ CO2).
//
// SwitchBot packets: service data UUID 0xFD3D, first byte & 0x7F = model; manufacturer data (company 0x0969) starts with
// the device MAC. Temperature bytes (decimal, integer|sign, humidity): t = (b1 & 0x7F) + (b0 & 0x0F) / 10, negative when
// b1 & 0x80 is 0; humidity = b2 & 0x7F. Meter / Meter Plus carry them in service data [3..5]; Outdoor Meter / Meter Pro in
// manufacturer data [8..10]; Hub 2 in manufacturer data [13..15] with the light level (1-20) in [12] & 0x1F.

const { spawn } = require('child_process');

const MODELS = { 0x76: 'Hub 2', 0x54: 'Meter', 0x69: 'Meter Plus', 0x77: 'Outdoor Meter', 0x35: 'Meter Pro', 0x34: 'Meter Pro CO2' };
const EVERY = 120000, SCAN = 8000, FRESH = 10 * 60000;

/** Decodes one advertisement (scanRecord bytes) into a reading, or null when it isn't a supported sensor. */
function decode(rec) {
  let svc = null, mfr = null;
  for (let k = 0; k < rec.length;) {
    const len = rec[k];
    if (!len) break;
    const type = rec[k + 1], data = rec.slice(k + 2, k + 1 + len);
    if (type === 0x16 && data[0] === 0x3D && data[1] === 0xFD) svc = data.slice(2);
    if (type === 0xFF && (data[0] | data[1] << 8) === 0x0969) mfr = data.slice(2);
    k += len + 1;
  }
  if (!svc || !svc.length) return null;
  const model = svc[0] & 0x7F, kind = MODELS[model];
  if (!kind) return null;
  const temp = (b0, b1, b2) => {
    const t = ((b1 & 0x7F) + (b0 & 0x0F) / 10) * (b1 & 0x80 ? 1 : -1), h = b2 & 0x7F;
    return t > -40 && t < 70 && h <= 100 ? { t: Math.round(t * 10) / 10, h } : null;
  };
  let r = null;
  if ((model === 0x54 || model === 0x69) && svc.length >= 6) r = temp(svc[3], svc[4], svc[5]);
  else if (model === 0x76 && mfr && mfr.length >= 16) {
    r = temp(mfr[13], mfr[14], mfr[15]);
    if (r) r.light = mfr[12] & 0x1F;
  } else if ((model === 0x77 || model === 0x35 || model === 0x34) && mfr && mfr.length >= 11) {
    r = temp(mfr[8], mfr[9], mfr[10]);
    if (r && model === 0x34 && mfr.length >= 15) r.co2 = mfr[13] << 8 | mfr[14];
  }
  if (!r) return null;
  if (model !== 0x76 && svc.length >= 3) r.bat = svc[2] & 0x7F;
  return Object.assign({ vendor: 'SwitchBot', kind }, r);
}

/**
 * enabled(): may this TV scan; adopted(): Set of added sensor ids; onSearch(found, until): search results (every 3 s).
 */
function create({ log, enabled, adopted, onSearch }) {
  const seen = {};     // added sensors: id -> { id, kind, t, h, light?, bat?, co2?, rssi, at }
  let found = {};      // during a search: every decodable sensor heard, rssi = the latest
  let proc = null, searchUntil = 0, searchTimer = null;

  /** Runs luna-send's scan, feeding every decodable advertisement to onAdv(id, reading, rssi). */
  function runScan(ms, onAdv, onEnd) {
    let buf = '';
    try { proc = spawn('luna-send', ['-i', 'luna://com.webos.service.bluetooth2/le/startScan', '{"subscribe":true}']); }
    catch (e) { proc = null; if (onEnd) onEnd(); return; }
    const me = proc;
    me.stdout.on('data', d => {
      buf += d;
      let nl;
      while ((nl = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!line.startsWith('{')) continue;
        let o;
        try { o = JSON.parse(line); } catch (e) { continue; }
        for (const dev of o.devices || []) {
          const r = decode(dev.scanRecord || []);
          const id = String(dev.address || '').toLowerCase().replace(/[^0-9a-f]/g, '');
          if (r && id) onAdv(id, r, +dev.rssi || -127);
        }
      }
    });
    me.on('error', () => {});
    me.on('exit', () => { if (proc === me) proc = null; if (onEnd) onEnd(); });
    setTimeout(() => { try { me.kill(); } catch (e) { /* gone */ } }, ms);
  }

  function keep(id, r, rssi) {
    if (!adopted().has(id)) return;
    const prev = seen[id] && Date.now() - seen[id].at < SCAN + 2000 ? seen[id] : null;
    seen[id] = Object.assign({ id }, r, { rssi: Math.max(rssi, prev ? prev.rssi : -127), at: Date.now() });
  }

  function scan() {
    if (proc || Date.now() < searchUntil || (enabled && !enabled())) return;
    runScan(SCAN, keep);
  }

  /** A sensor search until `until`: continuous scanning, results every 3 s. */
  function search(until) {
    if (enabled && !enabled()) return;
    if (until <= searchUntil) return;
    searchUntil = until;
    found = {};
    if (proc) { try { proc.kill(); } catch (e) { /* gone */ } }
    const loop = () => {
      if (Date.now() >= searchUntil) return;
      runScan(Math.min(20000, searchUntil - Date.now()), (id, r, rssi) => {
        found[id] = Object.assign({ id }, r, { rssi, best: Math.max(rssi, found[id] ? found[id].best : -127), at: Date.now() });
        keep(id, r, rssi);
      }, () => setTimeout(loop, 300));
    };
    setTimeout(loop, 300);
    clearInterval(searchTimer);
    const report = () => { try { onSearch(Object.values(found), searchUntil); } catch (e) { log('ble: ' + e.message); } };
    searchTimer = setInterval(() => { report(); if (Date.now() >= searchUntil) clearInterval(searchTimer); }, 3000);
    log('ble: sensor search for ' + Math.round((until - Date.now()) / 1000) + ' s');
  }

  return {
    start() { setTimeout(scan, 20000); setInterval(scan, EVERY); log('ble: sensor scan every ' + EVERY / 60000 + ' min'); },
    search,
    searching: () => Date.now() < searchUntil,
    /** Fresh readings of the added sensors: [{id, kind, t, h, light?, bat?, co2?, rssi, at}]. */
    climate() { const a = adopted(); return Object.values(seen).filter(r => a.has(r.id) && Date.now() - r.at < FRESH); },
    decode,
  };
}

module.exports = { create, decode, MODELS };
