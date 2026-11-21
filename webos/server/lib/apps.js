'use strict';
/*
 * App management on webOS: install an .ipk (uploaded or from a link) and uninstall removable apps through the
 * TV's app installer. Renaming is CanvasFrame's own (config app_names) and only changes the home screen tile.
 */
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const https = require('https');
const http = require('http');
const zlib = require('zlib');

/** The package id from an .ipk (ar archive -> control.tar.gz -> ./control "Package:"). */
function packageId(file) {
  const buf = fs.readFileSync(file);
  if (buf.toString('latin1', 0, 8) !== '!<arch>\n') throw new Error("That isn't a webOS .ipk package");
  const cstr = (b, s, e) => { const v = b.toString('latin1', s, e); const z = v.indexOf('\u0000'); return (z >= 0 ? v.slice(0, z) : v).trim(); };
  let off = 8;
  while (off + 60 <= buf.length) {
    const name = buf.toString('latin1', off, off + 16).trim().replace(/\/$/, '');
    const size = parseInt(buf.toString('latin1', off + 48, off + 58).trim(), 10);
    const body = buf.slice(off + 60, off + 60 + size);
    if (name === 'control.tar.gz') {
      const tar = zlib.gunzipSync(body);
      for (let t = 0; t + 512 <= tar.length;) {
        const fname = cstr(tar, t, t + 100);
        const fsize = parseInt(cstr(tar, t + 124, t + 136) || '0', 8);
        if (!fname) break;
        if (fname.replace(/^\.\//, '') === 'control') {
          const m = tar.toString('utf8', t + 512, t + 512 + fsize).match(/^Package:\s*(\S+)/m);
          if (m) return m[1];
        }
        t += 512 + Math.ceil(fsize / 512) * 512;
      }
    }
    off += 60 + size + (size % 2);
  }
  throw new Error('No package id in that .ipk');
}

const PROTECTED = ['com.canvasframe.home', 'org.webosbrew.hbchannel'];

function create(dataDir, tv, log) {
  /** Run a luna-send subscription until a reply matches ok/fail, or time out. */
  function subscribe(uri, params, okRe, failRe, timeoutMs) {
    return new Promise((resolve, reject) => {
      const p = spawn('/usr/bin/luna-send', ['-i', 'luna://' + uri, JSON.stringify(params)]);
      let out = '';
      const end = (err, val) => { clearTimeout(t); try { p.kill(); } catch (e) { /* gone */ } err ? reject(err) : resolve(val); };
      const t = setTimeout(() => end(new Error('The TV did not finish in time')), timeoutMs);
      p.stdout.on('data', d => {
        out += d;
        if (okRe.test(out)) end(null, out);
        const f = out.match(failRe);
        if (f) end(new Error(f[1] || f[2] || 'The TV refused'));
      });
      p.on('exit', () => end(okRe.test(out) ? null : new Error(out.trim().slice(-200) || 'luna-send exited'), out));
    });
  }

  async function install(ipk) {
    const id = packageId(ipk);
    await subscribe('com.webos.appInstallService/dev/install', { id, ipkUrl: ipk, subscribe: true },
      /"state"\s*:\s*"installed"|"statusValue"\s*:\s*30/, /"state"\s*:\s*"([^"]*fail[^"]*)"|"errorText"\s*:\s*"([^"]+)"/i, 180000);
    await tv.launchPoints(true);
  }

  function download(url, dest, redirects) {
    redirects = redirects || 0;
    return new Promise((resolve, reject) => {
      const mod = url.startsWith('https:') ? https : http;
      const req = mod.get(url, { headers: { 'User-Agent': 'CanvasFrame' }, timeout: 30000 }, res => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects < 5) {
          res.resume(); return resolve(download(new URL(res.headers.location, url).toString(), dest, redirects + 1));
        }
        if (res.statusCode >= 400) { res.resume(); return reject(new Error('HTTP ' + res.statusCode)); }
        const out = fs.createWriteStream(dest);
        let n = 0;
        res.on('data', d => { n += d.length; if (n > 300 * 1024 * 1024) req.destroy(new Error('file too large')); });
        res.pipe(out);
        out.on('finish', () => resolve(n));
        out.on('error', reject);
      });
      req.on('timeout', () => req.destroy(new Error('timed out')));
      req.on('error', reject);
    });
  }

  const tmp = () => path.join(dataDir, 'install-' + Date.now() + '.ipk');
  const isIpk = f => { try { const b = Buffer.alloc(8); const fd = fs.openSync(f, 'r'); fs.readSync(fd, b, 0, 8, 0); fs.closeSync(fd); return b.toString('latin1') === '!<arch>\n'; } catch (e) { return false; } };

  return {
    PROTECTED,
    /** Install from an uploaded body (stream) of the given length. */
    installUpload(req, len) {
      return new Promise((resolve, reject) => {
        if (!len || len > 300 * 1024 * 1024) return reject(new Error('Choose an .ipk file (up to 300 MB)'));
        const f = tmp(), out = fs.createWriteStream(f);
        req.pipe(out);
        out.on('finish', async () => {
          try {
            if (!isIpk(f)) throw new Error("That isn't a webOS .ipk package");
            await install(f); log('apps: installed upload'); resolve();
          } catch (e) { reject(e); } finally { fs.unlink(f, () => {}); }
        });
        out.on('error', e => { fs.unlink(f, () => {}); reject(e); });
      });
    },
    async installUrl(url) {
      if (!/^https?:\/\//.test(url)) throw new Error('The link must start with https://');
      const f = tmp();
      try {
        await download(url, f);
        if (!isIpk(f)) throw new Error("That link isn't a webOS .ipk package");
        await install(f);
        log('apps: installed ' + url);
      } finally { fs.unlink(f, () => {}); }
    },
    async uninstall(id) {
      if (PROTECTED.indexOf(id) >= 0) throw new Error("CanvasFrame won't remove that app");
      const lp = (await tv.launchPoints(true)).find(x => x.id === id);
      if (!lp) throw new Error('No such app');
      if (!lp.removable) throw new Error('LG doesn\'t allow removing that app');
      const dev = /\/media\/developer\//.test(lp.icon || lp.largeIcon || '');
      let r = await tv.luna(dev ? 'com.webos.appInstallService/dev/remove' : 'com.webos.appInstallService/remove', { id }, 20000);
      if (r.returnValue === false && !dev) r = await tv.luna('com.webos.appInstallService/dev/remove', { id }, 20000);
      if (r.returnValue === false) throw new Error(r.errorText || 'The TV refused');
      // Removal finishes in the background: wait until it's gone from the launcher list.
      let gone = false;
      for (let i = 0; i < 40 && !gone; i++) {
        await new Promise(res => setTimeout(res, 1000));
        gone = !(await tv.launchPoints(true)).some(x => x.id === id);
      }
      if (!gone) throw new Error('The TV is still removing it; check again in a moment');
      log('apps: removed ' + id);
    },
  };
}

module.exports = { create };
