'use strict';
/*
 * Extra readings for the dashboard's Overview, sampled in the background: per-core CPU, swap, network
 * throughput, a one-hour temperature history, eMMC flash wear, and the Magic Remote's battery.
 * Every field is optional in /api/status; the dashboard shows the ones a platform provides.
 */
const fs = require('fs');

const read = f => { try { return fs.readFileSync(f, 'utf8'); } catch (e) { return ''; } };
const EMMC_LIFE = ['', '0–10%', '10–20%', '20–30%', '30–40%', '40–50%', '50–60%', '60–70%', '70–80%', '80–90%', '90–100%', 'Exceeded'];
const EMMC_EOL = { 1: 'Normal', 2: 'Warning', 3: 'Urgent' };

function create() {
  let lastCores = null, cores = [], lastNet = null, net = null;
  const temps = [];   // {t, c} every 30 s, one hour

  function sampleCpu() {
    const now = {};
    for (const l of read('/proc/stat').split('\n')) {
      const m = l.match(/^cpu(\d+)\s+(.*)$/);
      if (!m) continue;
      const f = m[2].trim().split(/\s+/).map(Number);
      now[m[1]] = { total: f.reduce((a, b) => a + b, 0), idle: f[3] + (f[4] || 0) };
    }
    if (lastCores) {
      cores = Object.keys(now).filter(k => lastCores[k]).sort((a, b) => a - b).map(k => {
        const dt = now[k].total - lastCores[k].total, di = now[k].idle - lastCores[k].idle;
        return dt > 0 ? Math.round(100 * (1 - di / dt)) : 0;
      });
    }
    lastCores = now;
  }

  function defaultIface() {
    const route = read('/proc/net/route').split('\n').slice(1).map(l => l.trim().split(/\s+/)).find(f => f[1] === '00000000');
    return route ? route[0] : null;
  }
  function sampleNet() {
    const iface = defaultIface();
    if (!iface) { net = null; return; }
    const line = read('/proc/net/dev').split('\n').find(l => l.trim().startsWith(iface + ':'));
    if (!line) return;
    const f = line.split(':')[1].trim().split(/\s+/).map(Number);
    const cur = { t: Date.now(), rx: f[0], tx: f[8] };
    if (lastNet && lastNet.iface === iface) {
      const s = (cur.t - lastNet.t) / 1000;
      net = { iface, wifi: /^wl|wlan/.test(iface), rxRate: Math.max(0, (cur.rx - lastNet.rx) / s), txRate: Math.max(0, (cur.tx - lastNet.tx) / s), rxTotal: cur.rx, txTotal: cur.tx };
    }
    lastNet = Object.assign({ iface }, cur);
  }

  function tempNow() {
    for (const p of ['/proc/lg/pm/temperature', '/sys/class/thermal/thermal_zone0/temp']) {
      const v = parseFloat(read(p));
      if (isFinite(v)) return v > 1000 ? v / 1000 : v;
    }
    return null;
  }
  function sampleTemp() {
    const c = tempNow();
    if (c == null) return;
    temps.push({ t: Date.now(), c: Math.round(c * 10) / 10 });
    while (temps.length > 120) temps.shift();
  }

  function swap() {
    const l = read('/proc/swaps').trim().split('\n').slice(1).map(x => x.trim().split(/\s+/));
    if (!l.length || !l[0][2]) return null;
    return { total: l.reduce((a, f) => a + +f[2], 0) * 1024, used: l.reduce((a, f) => a + +f[3], 0) * 1024 };
  }
  function flash() {
    const life = read('/sys/block/mmcblk0/device/life_time').trim();
    if (!life) return null;
    const [a, b] = life.split(/\s+/).map(x => parseInt(x, 16));
    const eol = parseInt(read('/sys/block/mmcblk0/device/pre_eol_info').trim(), 16);
    return { wear: EMMC_LIFE[Math.max(a || 0, b || 0)] || null, eol: EMMC_EOL[eol] || null };
  }
  function remote() {
    const raw = read('/mnt/lg/cmn_data/mrcu/mrcu1.info');
    if (!raw) return null;
    const b = raw.match(/Battery\s*=\s*(\d+)/i), n = raw.match(/Name\s*=\s*([^\r\n]+)/i);
    if (!b && !n) return null;
    return { battery: b ? +b[1] : null, model: n ? n[1].trim() : null };
  }

  sampleCpu(); sampleNet(); sampleTemp();
  setInterval(() => { sampleCpu(); sampleNet(); }, 5000);
  setInterval(sampleTemp, 30000);

  return {
    /** Fields merged into /api/status. */
    snapshot() {
      const t = temps.map(x => x.c);
      return {
        cpuCores: cores.length ? cores : undefined,
        swap: swap() || undefined,
        net: net || undefined,
        tempHistory: t.length ? { samples: t, min: Math.min(...t), max: Math.max(...t), everySec: 30 } : undefined,
        flash: flash() || undefined,
        remote: remote() || undefined,
      };
    },
  };
}

module.exports = { create };
