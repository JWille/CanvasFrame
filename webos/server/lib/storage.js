'use strict';
/*
 * Storage: free space on the partition LG apps and CanvasFrame's photos share, and LG extras that can be removed safely.
 * Today that's the in-store demo videos (preload/storedemo/*.lge, ~800 MB on a 2023 QNED): they only play in "Store mode",
 * so on a TV in Home mode they're dead weight. Only those exact files can be deleted through here, and only in Home mode.
 * An LG firmware update may download them again.
 */
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const APPSTORE = '/mnt/lg/appstore';
const DEMO = path.join(APPSTORE, 'preload', 'storedemo');

function create(luna, log) {
  const demoFiles = () => {
    try {
      return fs.readdirSync(DEMO).filter(n => /\.lge$/i.test(n) && n[0] !== '.')
        .map(n => ({ name: n, size: fs.statSync(path.join(DEMO, n)).size }));
    } catch (e) { return []; }
  };
  /** Free and total bytes of the shared partition (Node 12 has no fs.statfs: busybox df). */
  function space() {
    try {
      const out = execFileSync('df', ['-k', APPSTORE], { timeout: 3000 }).toString().trim().split('\n');
      const t = out.slice(1).join(' ').trim().split(/\s+/);   // a long device name can wrap onto its own line
      return { total: +t[t.length - 5] * 1024, free: +t[t.length - 3] * 1024 };
    } catch (e) { return { total: null, free: null }; }
  }
  async function storeMode() {
    const r = await luna('com.webos.service.settings/getSystemSettings', { category: 'option', keys: ['storeMode'] });
    return (r && r.settings && r.settings.storeMode) || '';
  }

  return {
    async status() {
      const files = demoFiles(), mode = await storeMode().catch(() => '');
      const items = [];
      if (files.length) items.push({
        id: 'storedemo', title: 'LG store demo videos',
        desc: 'Showroom videos the TV only plays in Store mode. This TV is in ' + (mode === 'home' ? 'Home mode, so they are never used.' : (mode || 'an unknown') + ' mode.'),
        size: files.reduce((a, f) => a + f.size, 0), files, removable: mode === 'home',
        reason: mode === 'home' ? '' : 'Only removable while the TV is in Home mode',
        note: 'An LG firmware update may download them again.',
      });
      return Object.assign(space(), { items });
    },
    /** Path of a file that may be downloaded (a demo video), or null. */
    file(id, name) {
      if (id !== 'storedemo') return null;
      const f = demoFiles().find(x => x.name === String(name));
      return f ? path.join(DEMO, f.name) : null;
    },
    async remove(id) {
      if (id !== 'storedemo') throw new Error('Unknown item');
      if ((await storeMode()) !== 'home') throw new Error('Only removable while the TV is in Home mode');
      let freed = 0;
      for (const f of demoFiles()) { fs.unlinkSync(path.join(DEMO, f.name)); freed += f.size; }
      log('storage: removed the store demo videos (' + Math.round(freed / 1048576) + ' MB)');
      return freed;
    },
  };
}

module.exports = { create };
