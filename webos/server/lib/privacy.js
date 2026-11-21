'use strict';
/*
 * webOS privacy: ad/tracker blocking through a hosts table bind-mounted over /etc/hosts, TV software update
 * blocking (every update server the TV's own update program names, plus the known ones), switching off LG
 * background services, and the advertising-ID controls. Everything is offered only where the TV has it.
 *
 * Blocklists follow what Glasshouse (rorygallagher2024/glasshouse) found on webOS 22, extended with the update
 * servers it missed (nsu.lge.com is what this firmware's "network software update" check uses).
 * Lines other tools already put in /etc/hosts (Homebrew Channel, Life's Good Without Spying) are kept.
 */
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');

const MARK = '# CanvasFrame blocklist';
const GLASSHOUSE_MARK = '# LG Ad & Telemetry Blackhole';
const TRANSIENT = '/run/systemd/transient';

// Ads, viewing recognition (ACR) and LG's ad beacons.
const ADS = ['ad.lgsmartad.com', 'ibis.lgappstv.com', 'ibs.lgappstv.com', 'lgsmartad.com', 'rdx.lgtvcommon.com',
  'aic.lgtvcommon.com', 'smartclip.com', 'smartclip-services.com', 'yumenetworks.com', 'alphonso.tv',
  'prov-lg.alphonso.tv', 'prov.alphonso.tv', 'eulacheck.alphonso.tv', 'bl-server.alphonso.tv', 'clockskew.alphonso.tv',
  'acrdb.alphonso.tv', 'ads.alphonso.tv', 'api.alphonso.tv', 'tvads.alphonso.tv', 'sync.alphonso.tv', 'tr.alphonso.tv',
  'tn.alphonso.tv', 'insights.alphonso.tv', 'bwlkup.alphonso.tv'];
// The firmware prefixes these with region / country codes, and hosts files only match whole names.
const REGIONAL = ['info.lgsmartad.com', 'rdx2.lgtvsdp.com', 'ibs.lgappstv.com', 'ibsstat.lgappstv.com',
  'cdpbeacon.lgtvcommon.com', 'cdpsvc.lgtvcommon.com', 'wau.lgtvcommon.com'];
const REGION_PREFIXES = ['aic', 'eic', 'kic', 'eu'];
// LG's platform: home-screen content, recommendations, the Content Store (so app updates too).
const PLATFORM = ['lgtvsdp.com', 'us.lgtvsdp.com', 'gb.lgtvsdp.com', 'eu.lgtvsdp.com', 'ngfts.lge.com', 'aic-ngfts.lge.com'];
// The TV sets its clock from these, so they're only blocked once the clock is valid.
const SDP = ['nextlgsdp.com', 'us.nextlgsdp.com', 'gb.nextlgsdp.com', 'eu.nextlgsdp.com'];
const UPDATE_KNOWN = ['snu.lge.com', 'su.lge.com', 'su-dev.lge.com', 'su-ssl.lge.com', 'nsu.lge.com', 'snu-dev.lge.com'];

// LG background services that can be switched off (unit names on webOS 22; shown only where present).
const SERVICES = [
  { id: 'uploadd', unit: 'uploadd.service', bin: 'uploadd', title: 'Diagnostics uploader', desc: 'Uploads crash logs, diagnostic traces and usage data to LG.' },
  { id: 'rdxd', unit: 'rdxd.service', bin: 'rdxd', title: 'Remote diagnostics', desc: 'Logs diagnostic traces and events to the TV\'s flash memory for LG.' },
  { id: 'crashreportd', unit: 'crashreportd.service', bin: 'crashreportd', title: 'Crash reporter', desc: 'Builds automatic crash reports.' },
  { id: 'contentminer', unit: 'contentminer.service', bin: 'contentminer', title: 'Content miner (ACR)', desc: 'Indexes what\'s on screen for advertising and viewing data.' },
  { id: 'nudge', unit: 'nudge.service', bin: 'nudge', title: 'Promotions & tips pop-ups', desc: 'Pushes marketing notifications, promotions and feature tips.' },
  { id: 'remotelogger', unit: 'remotelogger.service', bin: 'remotelogger', title: 'Remote logger', desc: 'Sends system log messages to LG\'s servers.' },
  { id: 'camera', unit: 'com.webos.service.camera.service', bin: 'com.webos.service.camera2', title: 'USB camera listener', desc: 'Watches for a USB webcam. Not needed without one.' },
  { id: 'mycar', unit: 'com.webos.service.mycar.service', bin: 'com.webos.service.mycar', title: 'Car-to-home telematics', desc: 'Connected-car status for the home dashboard.' },
];
const UPDATE_UNIT = { id: 'update', unit: 'update.service', bin: 'update' };

function run(cmd, args) {
  return new Promise(resolve => execFile(cmd, args, { timeout: 8000 }, (err, out) => resolve({ err, out: String(out || '') })));
}

function create(dataDir, luna, log) {
  const STATE = path.join(dataDir, 'privacy.json');
  const TABLE = path.join(dataDir, 'hosts');
  const INHERITED = path.join(dataDir, 'hosts.inherited');
  let state;
  try { state = JSON.parse(fs.readFileSync(STATE, 'utf8')); } catch (e) { state = {}; }
  state = Object.assign({ adblock: 'off', blockUpdates: false, updateServiceOff: false, services: [] }, state);
  const save = () => fs.writeFileSync(STATE, JSON.stringify(state));

  const visibleHosts = () => { try { return fs.readFileSync('/etc/hosts', 'utf8'); } catch (e) { return ''; } };
  const oursMounted = () => visibleHosts().indexOf(MARK) >= 0;
  let clockValid = false, country = '';

  /** Update servers named in the TV's own update program, plus the known ones. */
  let updateHostsCache = null;
  function updateHosts() {
    if (updateHostsCache) return updateHostsCache;
    const set = new Set(UPDATE_KNOWN);
    try {
      const txt = fs.readFileSync('/usr/sbin/update').toString('latin1');
      for (const m of txt.match(/[a-z0-9-]+(\.[a-z0-9-]+)*\.lge\.com/g) || []) set.add(m);
    } catch (e) { /* no update program */ }
    return (updateHostsCache = [...set].sort());
  }

  function adsList() {
    const prefixes = REGION_PREFIXES.concat(/^[a-z]{2}$/.test(country) ? [country] : ['us', 'gb', 'au', 'br', 'ca', 'de', 'fr']);
    const list = ADS.slice();
    for (const n of REGIONAL) for (const h of [n].concat(prefixes.map(p => p + '.' + n))) if (list.indexOf(h) < 0) list.push(h);
    return list;
  }
  function storeHost() {
    try {
      const j = JSON.parse(fs.readFileSync('/var/palm/data/com.webos.appInstallService/serverInfo', 'utf8'));
      const m = /^[a-z]+:\/\/([^/:?#]+)/i.exec(String(j.serverUrl || ''));
      return m ? m[1].toLowerCase() : null;
    } catch (e) { return null; }
  }
  function blocked() {
    const out = [];
    const add = h => { if (out.indexOf(h) < 0) out.push(h); };
    if (state.adblock !== 'off') adsList().forEach(add);
    if (state.adblock === 'full') {
      PLATFORM.forEach(add);
      const s = storeHost(); if (s) add(s);
      if (clockValid) SDP.forEach(add);
    }
    if (state.blockUpdates) updateHosts().forEach(add);
    return out;
  }

  function table() {
    const lines = ['127.0.0.1\tlocalhost.localdomain\tlocalhost', '::1\tlocalhost ip6-localhost ip6-loopback',
      'fe00::0\tip6-localnet', 'ff00::0\tip6-mcastprefix', 'ff02::1\tip6-allnodes', 'ff02::2\tip6-allrouters', ''];
    let inherited = '';
    try { inherited = fs.readFileSync(INHERITED, 'utf8'); } catch (e) { /* none */ }
    if (inherited.trim()) lines.push('# Kept from what was in /etc/hosts before', inherited.trim(), '');
    lines.push(MARK);
    // Both address families: with only an IPv4 sinkhole the resolver asks DNS for AAAA and connects over IPv6.
    for (const h of blocked()) lines.push('0.0.0.0\t' + h, '::\t' + h);
    return lines.join('\n') + '\n';
  }

  /** Sinkhole lines from whatever is visible now (other tools' tables), without localhost or Glasshouse's block. */
  function rememberInherited() {
    const keep = [];
    let inGlasshouse = false;
    for (const l of visibleHosts().split('\n')) {
      if (l.indexOf(GLASSHOUSE_MARK) >= 0) { inGlasshouse = true; continue; }
      if (inGlasshouse) continue;
      const m = l.trim().match(/^(0\.0\.0\.0|::|127\.0\.0\.1)\s+(\S+)/);
      if (m && !/^(localhost|ip6-)/.test(m[2])) keep.push(l.trim());
    }
    fs.writeFileSync(INHERITED, [...new Set(keep)].join('\n'));
  }

  async function applyHosts() {
    const need = state.adblock !== 'off' || state.blockUpdates;
    if (need) {
      if (!oursMounted()) rememberInherited();
      fs.writeFileSync(TABLE, table());
      if (!oursMounted()) {
        const r = await run('/bin/mount', ['--bind', TABLE, '/etc/hosts']);
        if (r.err) throw new Error('could not mount the hosts table');
      }
    } else if (oursMounted()) {
      await run('/bin/umount', ['/etc/hosts']);
    }
  }

  // ---------------------------------------------------------------- services

  const unitExists = u => ['/lib/systemd/system/', '/etc/systemd/system/', '/usr/lib/systemd/system/'].some(d => fs.existsSync(d + u));
  const masked = u => { try { return fs.readlinkSync(path.join(TRANSIENT, u)) === '/dev/null'; } catch (e) { return false; } };
  function runningPids(bin) {
    const out = [];
    let pids = [];
    try { pids = fs.readdirSync('/proc').filter(p => /^\d+$/.test(p)); } catch (e) { return out; }
    for (const p of pids) { try { if (path.basename(fs.readlinkSync('/proc/' + p + '/exe')) === bin) out.push(+p); } catch (e) { /* gone */ } }
    return out;
  }
  async function setUnit(svc, off) {
    const link = path.join(TRANSIENT, svc.unit);
    if (off) {
      try { fs.mkdirSync(TRANSIENT, { recursive: true }); } catch (e) { /* exists */ }
      if (!masked(svc.unit)) { try { fs.unlinkSync(link); } catch (e) { /* none */ } fs.symlinkSync('/dev/null', link); }
      await run('systemctl', ['daemon-reload']);
      await run('systemctl', ['stop', svc.unit]);
      for (const pid of runningPids(svc.bin)) { try { process.kill(pid, 'SIGTERM'); } catch (e) { /* gone */ } }
    } else {
      if (masked(svc.unit)) fs.unlinkSync(link);
      await run('systemctl', ['daemon-reload']);
      await run('systemctl', ['start', svc.unit]);
    }
  }
  /** Re-apply switched-off services: masks live in /run (lost at reboot) and some are started on demand. */
  async function enforce() {
    const offs = SERVICES.filter(s => state.services.indexOf(s.id) >= 0);
    if (state.updateServiceOff) offs.push(UPDATE_UNIT);
    for (const s of offs) {
      if (!masked(s.unit) || runningPids(s.bin).length) await setUnit(s, true);
    }
  }

  // ---------------------------------------------------------------- clock, country, status

  async function refreshClock() {
    const r = await luna('com.webos.service.systemservice/time/getSystemTime');
    const v = r.timeValid !== undefined ? r.timeValid : r.timevalid;
    const was = clockValid;
    clockValid = v !== undefined ? (v === true || v === 'true') : Number(r.utc) > 1577836800;
    if (clockValid !== was && state.adblock === 'full' && oursMounted()) fs.writeFileSync(TABLE, table());
  }
  async function refreshCountry() {
    const r = await luna('com.webos.service.update/getCurrentSWInformation');
    country = String(r.country_group || '').toLowerCase();
  }

  const api = {
    MARK,
    /** Boot / start: rebuild and mount the table, mask services, then keep checking. */
    async start() {
      await refreshCountry().catch(() => {});
      await refreshClock().catch(() => {});
      await applyHosts().catch(e => log('privacy: ' + e.message));
      await enforce().catch(e => log('privacy: ' + e.message));
      setInterval(() => { refreshClock().catch(() => {}); enforce().catch(() => {}); }, 60000);
    },

    async status() {
      const [ad, upd, lmt] = await Promise.all([luna('com.webos.service.admanager/getAdid'), luna('com.webos.service.update/getStatus'),
        luna('com.webos.settingsservice/getSystemSettings', { category: 'general', keys: ['lmt'] })]);
      const lmtVal = lmt.settings && lmt.settings.lmt;
      const services = SERVICES.filter(s => unitExists(s.unit)).map(s => ({
        // Off if we switched it off or something else (e.g. Glasshouse) has it masked right now.
        id: s.id, title: s.title, desc: s.desc, off: state.services.indexOf(s.id) >= 0 || masked(s.unit), running: runningPids(s.bin).length > 0 }));
      return {
        platform: 'webos',
        adblock: { mode: state.adblock, active: oursMounted(), count: state.adblock === 'off' ? 0 : blocked().length - (state.blockUpdates ? updateHosts().length : 0), clockValid },
        updates: { blocked: state.blockUpdates, servers: updateHosts(), serviceOff: state.updateServiceOff,
          serviceAvailable: unitExists(UPDATE_UNIT.unit), pendingBytes: upd.nsuImageSize || 0, status: upd.status || '' },
        services,
        ads: { available: lmtVal !== undefined, limitTracking: lmtVal === 'on', adIdAvailable: ad.returnValue !== false && !!ad.IFA },
      };
    },

    async set(inp) {
      if ('adblock' in inp) state.adblock = ['off', 'ads', 'full'].indexOf(inp.adblock) >= 0 ? inp.adblock : 'off';
      if ('blockUpdates' in inp) state.blockUpdates = !!inp.blockUpdates;
      if ('adblock' in inp || 'blockUpdates' in inp) { save(); await applyHosts(); }
      if ('updateServiceOff' in inp) {
        state.updateServiceOff = !!inp.updateServiceOff; save();
        await setUnit(UPDATE_UNIT, state.updateServiceOff);
      }
      if (inp.service) {
        const svc = SERVICES.find(s => s.id === inp.service);
        if (!svc) throw new Error('unknown service');
        state.services = state.services.filter(x => x !== svc.id);
        if (inp.off) state.services.push(svc.id);
        save();
        await setUnit(svc, !!inp.off);
      }
      if ('limitTracking' in inp) {
        const r = await luna('com.webos.settingsservice/setSystemSettings', { category: 'general', settings: { lmt: inp.limitTracking ? 'on' : 'off' } });
        if (r.returnValue !== true) throw new Error('the TV would not change it');
      }
      if (inp.resetAdId) await luna('com.webos.service.admanager/resetIFA');
      if (inp.clearAdCookies) await luna('com.webos.service.admanager/inactivateCookies');
      return api.status();
    },

    /** Adopt settings from another tool (Glasshouse takeover). */
    async adopt(s) {
      if (s.adblock) state.adblock = s.adblock;
      if (s.blockUpdates) state.blockUpdates = true;
      if (Array.isArray(s.services)) state.services = s.services.filter(id => SERVICES.some(x => x.id === id));
      save();
      await applyHosts();
      await enforce();
    },
  };
  return api;
}

module.exports = { create, GLASSHOUSE_MARK };
