'use strict';
// PC link on LG (the same /api/pc as the Hisense, DashboardService.pcEvent): the PC that uses this TV as its screen reports
// its display (CanvasFrame Link, pc/). on = turn the screen on if it's off, switch to the PC's HDMI input and set its picture
// mode; off = only while the PC's input is on screen: CanvasFrame, the screen saver, screen off, or power off (LG's own
// "off"; with Quick Start+ on, "Turn on via Wi-Fi" wakes it again when the PC comes back). Settings per TV in <data>/pc.json: input (app id, "" = the input labelled computer / PC),
// pic (LG picture mode, "" = leave it), off, takeover (idle | always).

const fs = require('fs');
const path = require('path');

const OFF_CHOICES = [
  { id: 'home', name: 'Open CanvasFrame' }, { id: 'saver', name: 'Start the screen saver' },
  { id: 'screenoff', name: 'Turn the screen off' }, { id: 'poweroff', name: 'Turn the TV off' }, { id: 'none', name: 'Stay on the computer' }];
const HOME_APP = 'com.canvasframe.home';

function create({ dataDir, tv, lgs, saver, log }) {
  const FILE = path.join(dataDir, 'pc.json');
  let cfg = { input: '', pic: '', off: 'home', takeover: 'idle' };
  try { cfg = Object.assign(cfg, JSON.parse(fs.readFileSync(FILE, 'utf8'))); } catch (e) { /* defaults */ }
  const save = () => { try { fs.writeFileSync(FILE, JSON.stringify(cfg)); } catch (e) { log('pc: ' + e.message); } };
  let last = null, gen = 0;
  const sleep = ms => new Promise(r => setTimeout(r, ms));

  async function inputs() { return tv.inputs().catch(() => []); }
  async function pcInput() {
    if (cfg.input) return cfg.input;
    const i = (await inputs()).find(x => /\b(computer|pc|desktop|laptop)\b/i.test(x.label || ''));
    return i ? i.id : '';
  }
  async function now() {
    const [pw, fg] = await Promise.all([tv.luna('com.webos.service.tvpower/power/getPowerState').catch(() => ({})), tv.foreground().catch(() => '')]);
    const st = String(pw.state || '');
    return { screen: /screen off/i.test(st) || /standby/i.test(st) ? 'off' : /saver/i.test(st) ? 'saver' : 'on', state: st, app: fg };
  }
  const onInput = (n, id) => n.screen === 'on' && n.app === id;

  async function picModes() {
    try {
      const s = await lgs.read();
      for (const sec of s.sections) for (const r of sec.rows) if (r.id === 'pictureMode') return { modes: r.choices.map(c => ({ id: c.value, name: c.label })), cur: r.value };
    } catch (e) { /* not available */ }
    return { modes: [], cur: '' };
  }

  async function status() {
    const [inp, n, list] = await Promise.all([pcInput(), now(), inputs()]);
    const pm = await picModes();
    const o = { input: inp, inputSet: cfg.input, inputLabel: ((list.find(x => x.id === inp) || {}).label) || '', pic: cfg.pic === '' ? -1 : cfg.pic,
      off: cfg.off, takeover: cfg.takeover, modes: pm.modes, offChoices: OFF_CHOICES, now: n, last };
    if (inp && onInput(n, inp)) o.picNow = pm.cur;
    return o;
  }

  async function set(b) {
    if ('input' in b) cfg.input = String(b.input || '').replace(/[^A-Za-z0-9_.-]/g, '');
    if ('pic' in b) cfg.pic = b.pic === -1 || b.pic === '-1' || b.pic == null ? '' : String(b.pic).replace(/[^A-Za-z0-9_]/g, '');
    if ('off' in b) cfg.off = OFF_CHOICES.some(c => c.id === b.off) ? b.off : 'home';
    if ('takeover' in b) cfg.takeover = b.takeover === 'always' ? 'always' : 'idle';
    save();
    return status();
  }

  async function holdPicture(g, inp) {
    if (!cfg.pic) return;
    let ok = false;
    for (let i = 0; i < 5 && gen === g; i++) {
      if (onInput(await now(), inp)) {
        const cur = (await picModes()).cur;
        if (cur === cfg.pic) { if (ok) return; ok = true; }
        else { ok = false; log('pc: picture mode ' + cur + ' -> ' + cfg.pic); await lgs.set('pictureMode', cfg.pic).catch(e => log('pc: ' + e.message)); }
      }
      await sleep(2000);
    }
  }

  async function event(b) {
    const ev = String(b.event || ''), from = String(b.from || 'computer').replace(/[^\w .-]/g, '').slice(0, 40);
    if (ev !== 'on' && ev !== 'off') throw new Error('event must be on or off');
    const inp = await pcInput(), n = await now(), g = ++gen;
    const label = ((await inputs()).find(x => x.id === inp) || {}).label || inp;
    let result;
    if (!inp) result = 'No input is set for the computer';
    else if (ev === 'on') {
      const idle = n.screen !== 'on' || n.app === HOME_APP || n.app === inp;
      if (!idle && cfg.takeover !== 'always') result = 'Left alone: something else is on screen (' + n.app + ')';
      else {
        result = onInput(n, inp) ? 'Already on ' + label + (cfg.pic ? ', checking the picture mode' : '') : 'Switching to ' + label;
        (async () => {
          if (n.screen === 'off') { await tv.luna('com.webos.service.tvpower/power/turnOnScreen').catch(() => {}); await sleep(800); }
          if (gen !== g) return;
          if (!onInput(await now(), inp)) { await tv.launch(inp).catch(e => log('pc: ' + e.message)); await sleep(2500); }
          if ((await now()).screen === 'saver') {   // an app launched over LG's screen saver may leave it up
            await tv.luna('com.webos.applicationManager/closeByAppId', { id: 'com.webos.app.screensaver' }).catch(() => {});
            await sleep(800);
            await tv.launch(inp).catch(() => {});
          }
          await holdPicture(g, inp);
        })().catch(e => log('pc on: ' + e.message));
      }
    } else if (cfg.off === 'none') result = 'Nothing to do (set to stay on the computer\'s input)';
    else if (!onInput(n, inp)) result = 'Left alone: the computer\'s input isn\'t on screen';
    else {
      const what = cfg.off;
      result = { home: 'Opening CanvasFrame', saver: 'Starting the screen saver', screenoff: 'Turning the screen off', poweroff: 'Turning the TV off' }[what];
      (async () => {
        if (gen !== g) return;
        if (what === 'poweroff') return tv.luna('com.webos.service.tvpower/power/powerOff', { reason: 'remoteKey' });
        if (what === 'screenoff') return tv.luna('com.webos.service.tvpower/power/turnOffScreen');
        if (what === 'saver') await saver.preview(true); else await tv.launch(HOME_APP);
        // The TV sometimes jumps back to an input as its signal goes (seen on the Hisense): for 30 s put CanvasFrame back.
        for (let i = 0, again = 0; i < 15 && again < 3 && gen === g; i++) {
          await sleep(2000);
          if (gen === g && onInput(await now(), inp)) { again++; await tv.launch(HOME_APP).catch(() => {}); }
        }
      })().catch(e => log('pc off: ' + e.message));
    }
    last = { event: ev, from, at: Date.now(), result };
    log('pc: ' + ev + ' from ' + from + ': ' + result);
    return { ok: true, result };
  }

  return { status, set, event };
}

module.exports = { create };
