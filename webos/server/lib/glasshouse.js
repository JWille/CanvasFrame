'use strict';
/*
 * Detects Glasshouse (rorygallagher2024/glasshouse, server in /var/lib/tvweb) and takes over from it when the
 * user chooses to: adopts its privacy settings, moves its boot hooks into a backup folder (nothing is deleted),
 * stops its server, lifts its hosts table, and lets CanvasFrame use port 8080. restore() puts the hooks back.
 */
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');

const DIR = '/var/lib/tvweb';
const INITD = '/var/lib/webosbrew/init.d';
const HOOKS = ['20-tvweb-services', '50-tvweb', 'dashboard-autostart.sh', '20-services.sh'];
const APP_ID = 'com.tvweb.dashboard';

/** Move a file across filesystems (init.d is on /var, the backup on /media): copy with its mode, then remove. */
function moveFile(from, to) {
  const mode = fs.statSync(from).mode;
  fs.copyFileSync(from, to);
  fs.chmodSync(to, mode & 0o7777);
  fs.unlinkSync(from);
}
const read = f => { try { return fs.readFileSync(f, 'utf8').trim(); } catch (e) { return ''; } };
const run = (cmd, args) => new Promise(r => execFile(cmd, args, { timeout: 8000 }, () => r()));

function create(dataDir, log) {
  const BACKUP = path.join(dataDir, 'glasshouse-backup');
  const MANIFEST = path.join(BACKUP, 'manifest.json');

  function pids() {
    const out = [];
    let list = [];
    try { list = fs.readdirSync('/proc').filter(p => /^\d+$/.test(p)); } catch (e) { return out; }
    for (const p of list) {
      try { if (fs.readFileSync('/proc/' + p + '/cmdline', 'utf8').indexOf(DIR + '/') >= 0) out.push(+p); } catch (e) { /* gone */ }
    }
    return out;
  }
  const activeHooks = () => HOOKS.filter(h => fs.existsSync(path.join(INITD, h)));

  const api = {
    /** {present, active, takenOver, settings} */
    detect() {
      const present = fs.existsSync(path.join(DIR, 'tvweb.js'));
      const takenOver = fs.existsSync(MANIFEST);
      if (!present && !takenOver) return { present: false };
      let services = [];
      try { services = JSON.parse(read(path.join(DIR, 'disabled_services.json')) || '[]'); } catch (e) { /* none */ }
      const flag = read(path.join(DIR, 'adblock_enabled'));
      return {
        present, takenOver, running: pids().length > 0, hooks: activeHooks(),
        settings: {
          adblock: !fs.existsSync(path.join(DIR, 'adblock_enabled')) ? 'off' : flag === 'ads' ? 'ads' : 'full',
          blockUpdates: fs.existsSync('/var/luna/preferences/webosbrew_block_updates'),
          services,
        },
      };
    },

    /** Steps run by the server; privacy.adopt() and the port switch happen around this. */
    async takeOver() {
      fs.mkdirSync(BACKUP, { recursive: true });
      const moved = [];
      for (const h of activeHooks()) {
        const from = path.join(INITD, h), to = path.join(BACKUP, h);
        const link = fs.lstatSync(from).isSymbolicLink() ? fs.readlinkSync(from) : null;
        if (link) { fs.writeFileSync(to + '.symlink', link); fs.unlinkSync(from); }
        else moveFile(from, to);
        moved.push({ name: h, link });
      }
      fs.writeFileSync(MANIFEST, JSON.stringify({ at: Date.now(), moved }, null, 2));
      for (const pid of pids()) { try { process.kill(pid, 'SIGTERM'); } catch (e) { /* gone */ } }
      // Lift its hosts table if it's the top layer (ours goes on next).
      if (read('/etc/hosts').indexOf('# LG Ad & Telemetry Blackhole') >= 0) await run('/bin/umount', ['/etc/hosts']);
      log('glasshouse: taken over; hooks moved to ' + BACKUP + ': ' + moved.map(m => m.name).join(', '));
      return moved;
    },

    /** Put Glasshouse's boot hooks back and start it again (CanvasFrame moves back to 8081 first). */
    async restore() {
      let man;
      try { man = JSON.parse(fs.readFileSync(MANIFEST, 'utf8')); } catch (e) { throw new Error('No Glasshouse backup found'); }
      for (const m of man.moved) {
        const to = path.join(INITD, m.name);
        if (fs.existsSync(to)) continue;
        if (m.link) fs.symlinkSync(m.link, to);
        else moveFile(path.join(BACKUP, m.name), to);
      }
      fs.renameSync(MANIFEST, MANIFEST + '.restored-' + Date.now());
      log('glasshouse: restored hooks ' + man.moved.map(m => m.name).join(', '));
    },

    /** Start Glasshouse's own boot hook now (after restore()). */
    startNow() {
      const hook = path.join(INITD, '50-tvweb');
      if (fs.existsSync(hook)) execFile('/bin/sh', [hook], { timeout: 20000 }, () => {});
    },

    APP_ID,
  };
  return api;
}

module.exports = { create };
