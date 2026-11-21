'use strict';
// When the Magic Remote's pointer appears: on any movement (LG's default) or only when the remote is shaken.
//
// LG's menus don't offer this, but the remote's driver does: luna://com.webos.service.mrcu/cursor/setWakeUpCondition
// {condition: "motion" | "shake"} (found 2026-11-20 in /usr/sbin/lginput2; same on webOS 7 and 10). It's a live command, not
// a stored setting, so the TV forgets it on a restart, a cold boot and possibly when the remote reconnects. CanvasFrame keeps
// the choice in <data>/pointer.json and re-applies it shortly after the service starts and then every minute (sending the
// same condition again has no visible effect). "Magic Remote is not Ready" (remote asleep / not paired) is simply retried.

const fs = require('fs');
const path = require('path');

const CHOICES = [
  { value: 'motion', label: 'When the remote moves (LG default)' },
  { value: 'shake', label: 'Only when the remote is shaken' },
];

function create({ dataDir, luna, log }) {
  const FILE = path.join(dataDir, 'pointer.json');
  let prefs = { wake: 'motion' };
  try { prefs = Object.assign(prefs, JSON.parse(fs.readFileSync(FILE, 'utf8'))); } catch (e) { /* defaults */ }
  let applied = null, lastError = '', retry = null;

  async function apply() {
    const r = await luna('com.webos.service.mrcu/cursor/setWakeUpCondition', { condition: prefs.wake });
    if (r && r.returnValue === true) {
      if (applied !== prefs.wake) log('pointer: appears ' + (prefs.wake === 'shake' ? 'only when shaken' : 'when moved'));
      applied = prefs.wake; lastError = '';
    } else {
      lastError = (r && r.errorText) || 'no answer';
      // Usually "Magic Remote is not Ready": the remote sleeps when nobody holds it. Try again every 3 s until it wakes,
      // so the choice is in place within moments of someone picking it up.
      if (applied !== prefs.wake && !retry) retry = setTimeout(() => { retry = null; apply().catch(() => {}); }, 3000);
    }
  }

  // LG's default needs no upkeep once applied; "shake" is re-applied every minute so the TV can't drift back.
  setTimeout(() => apply().catch(() => {}), 15000);
  setInterval(() => { if (prefs.wake !== 'motion' || applied !== 'motion') apply().catch(() => {}); }, 60000);

  return {
    /** A row for the TV settings tab (same shape as lgsettings rows). */
    row() {
      return { id: 'pointerWake', type: 'choice', title: 'Pointer appears',
        desc: 'When the Magic Remote\'s pointer shows up. "Only when shaken" stops it popping up whenever someone picks up or moves the '
          + 'remote; buttons work as usual. LG\'s menus don\'t offer this; CanvasFrame keeps it applied after restarts.'
          + (lastError && applied !== prefs.wake ? ' (Waiting for the remote: ' + lastError + ')' : ''),
        choices: CHOICES, value: prefs.wake };
    },
    async set(value) {
      if (!CHOICES.some(c => c.value === value)) throw new Error('Not a value this setting takes');
      prefs.wake = value;
      fs.writeFileSync(FILE, JSON.stringify(prefs));
      applied = null;
      await apply();
      return { applied: applied === value, waiting: applied === value ? '' : lastError };
    },
  };
}

module.exports = { create };
