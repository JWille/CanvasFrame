'use strict';
/*
 * webOS side of CanvasFrame: luna-send calls (the server runs as root, so it has full bus access),
 * apps and inputs, remote keys, volume, power, device stats, and the Home-button watcher.
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFile, spawn } = require('child_process');

const LUNA = '/usr/bin/luna-send';
const HOME_APP = 'com.canvasframe.home';
const SETTINGS_APP = 'com.palm.app.settings';
const STOCK_HOME = 'com.webos.app.home';
const HOME_CODE = 773;            // HOME on the Magic Remote (evdev code)
const EVENT_SIZE = 16;            // 32-bit userland input_event

// Two luna-send children at a time: Glasshouse found a burst of parallel children can wedge Node on older
// webOS. Cheap insurance here too.
let running = 0;
const queue = [];
function luna(uri, params, timeout) {
  return new Promise(resolve => {
    queue.push(() => {
      running++;
      execFile(LUNA, ['-n', '1', 'luna://' + uri, JSON.stringify(params || {})],
        { timeout: timeout || 6000, maxBuffer: 8 << 20 }, (err, stdout) => {
          running--;
          let r;
          try { r = JSON.parse(stdout); } catch (e) { r = { returnValue: false, errorText: err ? err.message : 'bad reply' }; }
          resolve(r);
          drain();
        });
    });
    drain();
  });
}
function drain() { while (running < 2 && queue.length) queue.shift()(); }

// ---------------------------------------------------------------- apps & inputs

let lpCache = null, lpAt = 0;
async function launchPoints(force) {
  if (!force && lpCache && Date.now() - lpAt < 60000) return lpCache;
  const r = await luna('com.webos.applicationManager/listLaunchPoints');
  if (r.launchPoints) { lpCache = r.launchPoints; lpAt = Date.now(); }
  return lpCache || [];
}

/** Launchable apps for the home screen (not ourselves, not the input apps). */
async function apps() {
  const lps = await launchPoints();
  const list = lps.filter(lp => lp.id !== HOME_APP && !/^com\.webos\.app\.hdmi/.test(lp.id))
    .map(lp => ({ id: lp.id, title: lp.title, icon: lp.largeIcon || lp.icon || '', color: lp.iconColor || '', system: !!lp.systemApp,
      removable: !!lp.removable, dev: /\/media\/developer\//.test(lp.icon || '') }));
  // TV settings isn't a launch point; give it a tile first, like the Android home screen.
  list.unshift({ id: SETTINGS_APP, title: 'Settings', symbol: '⚙️', system: true });
  return list;
}

async function iconPath(id) {
  const lp = (await launchPoints()).find(x => x.id === id);
  return lp ? (lp.largeIcon || lp.extraLargeIcon || lp.icon || '') : '';
}

async function inputs() {
  const r = await luna('com.webos.service.eim/getAllInputStatus');
  return (r.devices || []).filter(d => /^HDMI/.test(d.id)).map(d => ({
    id: d.appId, label: d.label, port: d.port,
    connected: !!(d.hdmiPlugIn || d.hdmiSignalExist), active: !!d.activate,
  }));
}

async function launch(id, params) {
  const r = await luna('com.webos.applicationManager/launch', params ? { id, params } : { id });
  if (r.returnValue === false) throw new Error(r.errorText || "Couldn't open " + id);
  return r;
}

async function foreground() {
  const r = await luna('com.webos.applicationManager/getForegroundAppInfo');
  return r.appId || '';
}

// ---------------------------------------------------------------- remote, volume, power

// Dashboard key names → evdev codes for networkinput (the same path LG's own remote app uses).
const KEYS = {
  DPAD_UP: 103, DPAD_DOWN: 108, DPAD_LEFT: 105, DPAD_RIGHT: 106, DPAD_CENTER: 28, ENTER: 28,
  BACK: 412, MENU: 139, MEDIA_PLAY_PAUSE: 164, MEDIA_PLAY: 207, MEDIA_PAUSE: 119,
  MEDIA_REWIND: 168, MEDIA_FAST_FORWARD: 208, VOLUME_UP: 115, VOLUME_DOWN: 114, MUTE: 113,
};

async function key(name) {
  if (name === 'SLEEP') return luna('com.webos.service.tvpower/power/turnOffScreen');
  if (name === 'WAKEUP') return luna('com.webos.service.tvpower/power/turnOnScreen');
  if (name === 'HOME') return launch(HOME_APP);
  if (name === 'VOLUME_UP' || name === 'VOLUME_DOWN') return luna(name === 'VOLUME_UP' ? 'com.webos.audio/volumeUp' : 'com.webos.audio/volumeDown');
  const code = KEYS[name];
  if (!code) throw new Error('Unknown key ' + name);
  const r = await luna('com.webos.service.networkinput/test/sendKeyCode', { keyCode: code });
  if (r.returnValue === false) throw new Error(r.errorText || 'Key failed');
  return r;
}

async function volumeStatus() {
  const r = await luna('com.webos.service.audio/master/getVolume');
  const v = r.volumeStatus || r;
  return { volume: v.volume | 0, max: v.maxVolume || 100, muted: !!(v.muteStatus || v.muted), output: v.soundOutput || '' };
}

/** Set an absolute level. Over ARC/CEC (soundbar) only up/down steps work, so step there. */
async function setVolume(level) {
  const st = await volumeStatus();
  level = Math.max(0, Math.min(st.max, level | 0));
  if (/arc|optical|bt/i.test(st.output)) {
    const diff = level - st.volume, n = Math.min(Math.abs(diff), 30);
    for (let i = 0; i < n; i++) await luna(diff > 0 ? 'com.webos.audio/volumeUp' : 'com.webos.audio/volumeDown');
    return;
  }
  await luna('com.webos.audio/setVolume', { volume: level });
}
const setMuted = muted => luna('com.webos.audio/setMuted', { muted: !!muted });

// ---------------------------------------------------------------- stats

let sysInfo = null;
async function info() {
  if (!sysInfo) {
    const r = await luna('com.webos.service.tv.systemproperty/getSystemInfo', { keys: ['modelName', 'firmwareVersion', 'sdkVersion'] });
    if (r.modelName) sysInfo = r;
  }
  return sysInfo || {};
}

let lastCpu = null;
function cpuPct() {
  try {
    const f = fs.readFileSync('/proc/stat', 'utf8').split('\n')[0].trim().split(/\s+/).slice(1).map(Number);
    const total = f.reduce((a, b) => a + b, 0), idle = f[3] + (f[4] || 0);
    const prev = lastCpu;
    lastCpu = [total, idle];
    if (!prev || total === prev[0]) return null;
    return Math.round(100 * (1 - (idle - prev[1]) / (total - prev[0])));
  } catch (e) { return null; }
}
function readNum(p) { try { return parseFloat(fs.readFileSync(p, 'utf8')); } catch (e) { return NaN; } }
function tempC() {
  for (const p of ['/proc/lg/pm/temperature', '/sys/class/thermal/thermal_zone0/temp']) {
    const v = readNum(p);
    if (isFinite(v)) return v > 1000 ? v / 1000 : v;
  }
  return null;
}
function meminfo() {
  const o = {};
  try {
    for (const l of fs.readFileSync('/proc/meminfo', 'utf8').split('\n')) {
      const m = l.match(/^(\w+):\s+(\d+)/);
      if (m) o[m[1]] = +m[2] * 1024;
    }
  } catch (e) { /* ignore */ }
  return { total: o.MemTotal || 0, avail: o.MemAvailable || o.MemFree || 0 };
}
function disk(dir) {
  return new Promise(resolve => execFile('df', ['-k', dir], (e, out) => {
    const l = (out || '').trim().split('\n').pop().trim().split(/\s+/);
    resolve(l.length >= 4 ? { total: +l[1] * 1024, free: +l[3] * 1024 } : { total: 0, free: 0 });
  }));
}
function wifiRssi() {
  try {
    const l = fs.readFileSync('/proc/net/wireless', 'utf8').trim().split('\n')[2];
    if (!l) return null;
    let v = parseFloat(l.trim().split(/\s+/)[3]);
    if (v > 0) v -= 256;   // reported as an unsigned byte: 206 = -50 dBm
    return isFinite(v) ? Math.round(v) : null;
  } catch (e) { return null; }
}
function lanIp() {
  const ifs = os.networkInterfaces();
  for (const name of Object.keys(ifs)) {
    for (const a of ifs[name]) if (a.family === 'IPv4' && !a.internal && /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(a.address)) return a.address;
  }
  return '';
}

async function status(dataDir) {
  const [inf, power, fg, vol, dk] = await Promise.all([info(), luna('com.webos.service.tvpower/power/getPowerState'),
    foreground(), volumeStatus(), disk(dataDir)]);
  const lp = (await launchPoints()).find(x => x.id === fg);
  const NAMES = { 'com.webos.app.home': 'LG Home', 'com.webos.app.livetv': 'Live TV' };
  const mem = meminfo();
  const load = (() => { try { return fs.readFileSync('/proc/loadavg', 'utf8').split(' ').slice(0, 3).join(' '); } catch (e) { return ''; } })();
  return {
    platform: 'webos', model: 'LG ' + (inf.modelName || 'webOS TV'), osName: 'webOS ' + (inf.sdkVersion || ''),
    ip: lanIp(), adb: 'CONNECTED', screenOn: power.state === 'Active', screenSaver: power.state === 'Screen Saver',
    foreground: fg === HOME_APP ? 'CanvasFrame' : lp ? lp.title : NAMES[fg] || (/^com\.webos\.app\.hdmi(\d)/.test(fg) ? 'HDMI ' + fg.slice(-1) : fg), foregroundPkg: fg,
    cpuPct: cpuPct(), loadavg: load, tempC: tempC(),
    memTotal: mem.total, memAvail: mem.avail, diskTotal: dk.total, diskFree: dk.free,
    wifiRssi: wifiRssi(), uptimeSec: Math.round(readNum('/proc/uptime')), build: inf.firmwareVersion || '',
    volume: vol.volume, volumeMax: vol.max, muted: vol.muted,
  };
}

// ---------------------------------------------------------------- Home button

/** Reads every input device (no grab) and calls onHome when HOME is pressed. */
function watchHome(onHome, log) {
  let files = [];
  try { files = fs.readdirSync('/dev/input').filter(f => f.startsWith('event')); } catch (e) { log('no /dev/input: ' + e.message); return; }
  const fds = [], opened = new Set();
  // Device nodes for remotes that aren't connected yet can't be opened (ENXIO); the Magic Remote's appears when it
  // connects, so keep retrying the rest.
  const openAll = first => {
    let list = files;
    try { list = fs.readdirSync('/dev/input').filter(f => f.startsWith('event')); } catch (e) { /* keep */ }
    const added = [];
    for (const f of list) {
      if (opened.has(f)) continue;
      try { fds.push(fs.openSync(path.join('/dev/input', f), fs.constants.O_RDONLY | fs.constants.O_NONBLOCK)); opened.add(f); added.push(f); }
      catch (e) { /* not connected yet */ }
    }
    if (first) log('watching ' + fds.length + ' of ' + list.length + ' input devices for HOME');
    else if (added.length) log('now also watching ' + added.join(', '));
  };
  openAll(true);
  setInterval(() => openAll(false), 15000);
  const buf = Buffer.alloc(EVENT_SIZE * 64);
  const seenCodes = new Set();
  // Non-blocking reads polled from one timer: blocking reads would each pin a libuv thread.
  setInterval(() => {
    for (const fd of fds) {
      let n = 0;
      try { n = fs.readSync(fd, buf, 0, buf.length, null); } catch (e) { continue; }
      for (let off = 0; off + EVENT_SIZE <= n; off += EVENT_SIZE) {
        if (buf.readUInt16LE(off + 8) !== 1 || buf.readInt32LE(off + 12) !== 1) continue;
        const code = buf.readUInt16LE(off + 10);
        if (code === HOME_CODE) onHome();
        // Remotes differ: note each key code once, so the Home key can be identified from the log.
        if (!seenCodes.has(code)) { seenCodes.add(code); log('remote key code seen: ' + code); }
      }
    }
  }, 20);
}

/**
 * Calls cb(windows) whenever what's in front changes: every foreground window, as [{appId, windowType}].
 * Uses the detailed view (extraInfo) because on webOS 7 LG's home opens as a window over the current app, which
 * the plain "foreground app" never reports. luna-send -i prints multi-line JSON, so replies are split on braces.
 */
function watchForeground(cb, log) {
  let last = '';
  const start = () => {
    const p = spawn(LUNA, ['-i', 'luna://com.webos.applicationManager/getForegroundAppInfo', '{"subscribe":true,"extraInfo":true}']);
    let buf = '', depth = 0, from = -1;
    p.stdout.on('data', d => {
      buf += d;
      for (let i = 0; i < buf.length; i++) {
        const ch = buf[i];
        if (ch === '{') { if (depth++ === 0) from = i; }
        else if (ch === '}' && depth > 0 && --depth === 0 && from >= 0) {
          let j = null;
          try { j = JSON.parse(buf.slice(from, i + 1)); } catch (e) { /* partial */ }
          buf = buf.slice(i + 1); i = -1; from = -1;
          if (!j) continue;
          const wins = (j.foregroundAppInfo || (j.appId ? [{ appId: j.appId }] : [])).map(w => ({ appId: w.appId || '', windowType: w.windowType || '' }));
          const key = wins.map(w => w.appId + ':' + w.windowType).join(',');
          if (key !== last) { last = key; log('foreground: ' + (key || '(none)')); cb(wins); }
        }
      }
      if (depth === 0) buf = '';
    });
    p.on('exit', () => { log('foreground watch ended; restarting'); setTimeout(start, 3000); });
  };
  start();
}

module.exports = { watchForeground, STOCK_HOME, luna, apps, inputs, launch, foreground, key, volumeStatus, setVolume, setMuted, status, iconPath,
  launchPoints, watchHome, lanIp, HOME_APP };
