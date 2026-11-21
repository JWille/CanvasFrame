'use strict';
// Runs on the TV (via deploy.ps1): installs an .ipk through the app installer and waits for the result.
// Usage: node remote-install.js /tmp/canvasframe.ipk [app id]
const { spawn } = require('child_process');
const ipk = process.argv[2];
const p = spawn('/usr/bin/luna-send', ['-i', 'luna://com.webos.appInstallService/dev/install',
  JSON.stringify({ id: process.argv[3] || 'com.canvasframe.home', ipkUrl: ipk, subscribe: true })]);
let out = '';
const done = (ok, msg) => { clearTimeout(timer); try { p.kill(); } catch (e) { /* gone */ } console.log(msg); process.exit(ok ? 0 : 1); };
const timer = setTimeout(() => done(false, 'install timed out:\n' + out), 90000);
p.stdout.on('data', d => {
  out += d;
  if (/"state"\s*:\s*"installed"/.test(out)) done(true, 'installed');
  const fail = out.match(/"state"\s*:\s*"([^"]*fail[^"]*)"|"errorText"\s*:\s*"([^"]+)"/i);
  if (fail) done(false, 'install failed: ' + (fail[1] || fail[2]) + '\n' + out);
});
p.on('exit', () => done(/installed/.test(out), out || 'luna-send exited'));
