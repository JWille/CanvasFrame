'use strict';
/*
 * LG's own settings as dashboard rows (switch / choice / number), read from and written to the TV's settings
 * service. A row is offered only when the TV reports its key; choices are narrowed to the values the TV marks
 * visible; rows the TV greys out right now (e.g. another input on screen) come back with active: false.
 *
 * The response shape ({sections: [{id, title, rows: [{id, type, title, desc, value, choices, min, max, active}]}]})
 * is platform-neutral: the dashboard renders whatever rows a platform sends.
 * Keys and values are LG's (webOS 22); see also Glasshouse, which mapped many of them first.
 */
const SVC = 'com.webos.service.settings';

const sw = (category, key, title, desc, extra) => Object.assign({ type: 'switch', category, key, title, desc, on: 'on', off: 'off' }, extra || {});
const choice = (category, key, title, desc, choices, extra) => Object.assign({ type: 'choice', category, key, title, desc, choices }, extra || {});
const num = (category, key, title, desc, min, max, extra) => Object.assign({ type: 'number', category, key, title, desc, min, max }, extra || {});
const C = (value, label) => ({ value, label });

const PICTURE_MODES = { vivid: 'Vivid', normal: 'Standard', eco: 'Eco', cinema: 'Cinema', sports: 'Sports', game: 'Game Optimizer',
  filmMaker: 'Filmmaker Mode', expert1: 'Expert (bright room)', expert2: 'Expert (dark room)', photo: 'Photo',
  hdrVivid: 'HDR Vivid', hdrStandard: 'HDR Standard', hdrCinema: 'HDR Cinema', hdrCinemaBright: 'HDR Cinema Home',
  hdrFilmMaker: 'HDR Filmmaker', dolbyHdrVivid: 'Dolby Vision Vivid', dolbyHdrStandard: 'Dolby Vision Standard',
  dolbyHdrCinema: 'Dolby Vision Cinema', dolbyHdrCinemaBright: 'Dolby Vision Cinema Home', personalized: 'Personalized' };

const SECTIONS = [
  { id: 'picture', title: 'Picture & power', rows: [
    choice('picture', 'pictureMode', 'Picture mode', 'The TV\'s picture preset for what\'s on screen now.', null, { labels: PICTURE_MODES, dimensioned: true }),
    num('picture', 'backlight', 'Brightness', 'Backlight level for the current picture mode.', 0, 100, { store: 'string' }),
    choice('picture', 'energySaving', 'Energy saving', 'Dims the picture to save power.', [C('auto', 'Auto'), C('off', 'Off'), C('min', 'Minimum'),
      C('med', 'Medium'), C('max', 'Maximum'), C('screen_off', 'Screen off')], { alsoSet: { energySavingModified: 'true' } }),
    choice('time', 'sleepTimer', 'Sleep timer', 'Turns the TV off after this long.', [C('off', 'Off'), C('10', '10 minutes'), C('30', '30 minutes'),
      C('60', '1 hour'), C('90', '1½ hours'), C('120', '2 hours')]),
    sw('option', 'quickStartMode', 'Quick Start+', 'Starts faster from standby, using a little more standby power.'),
    sw('general', 'alwaysOn', 'Always Ready', 'Keeps the screen in a low-power art/clock mode instead of turning fully off.'),
    sw('network', 'wolwowlOnOff', 'Turn on over the network', 'Lets phones, voice assistants and Wake-on-LAN turn the TV on.', { on: 'true', off: 'false' }),
  ] },
  { id: 'promotions', title: 'Ads & suggestions', note: 'On means LG shows them.', rows: [
    sw('general', 'homePromotion', 'Sponsored tiles on LG\'s home', 'Ads marked Sponsored on LG\'s home screen ("Home Promotion").'),
    sw('other', 'contentRecommendation', 'Recommendations on LG\'s home', 'Rows of suggested shows and films ("Content Recommendation").'),
    sw('general', 'screenSaverAd', 'Screen saver ads', 'Ads some apps show in the screen saver ("Screen Saver Promotion").'),
    sw('option', 'livePromotion', 'Ads while watching', 'Offers shown over what\'s on screen when Live Plus is on ("Live Promotion").'),
    sw('general', 'aiNudge', 'Smart tips', 'Feature suggestions that pop up while watching.'),
    sw('general', 'aiSettingsNudge', 'Smart tips in Settings', 'Suggested settings in LG\'s Settings menu.'),
  ] },
  { id: 'game', title: 'Game Optimizer', note: 'Applies while the picture mode is Game Optimizer.', rows: [
    choice('other', 'gameGenre', 'Game genre', 'Tunes the picture for the kind of game.', [C('Standard', 'Standard'), C('FPS', 'First-person shooter'),
      C('RPG', 'Role-playing'), C('RTS', 'Real-time strategy'), C('Sports', 'Sports'), C('USER', 'User')]),
    choice('other', 'inputOptimization', 'Prevent input delay', 'Standard cuts input lag; Boost cuts it further.', [C('auto', 'Standard'), C('on', 'Boost')]),
    sw('other', 'gameOptimization', 'VRR & G-Sync', 'Follows the frame rate a console or PC sends, for smooth motion without tearing.'),
    sw('other', 'freesync', 'AMD FreeSync Premium', 'Variable refresh for AMD graphics.'),
    sw('other', 'enableALLM', 'Auto low-latency mode', 'Switches to Game Optimizer when a console asks for it (ALLM).'),
    choice('other', 'darkMode', 'Dark room mode', 'Dims the screen for playing in the dark.', [C('off', 'Off'), C('level1', 'Level 1'), C('level2', 'Level 2')]),
    num('other', 'blackStabilizer', 'Black stabilizer', 'Brightens dark areas so detail in shadows is easier to see.', 0, 20),
    num('other', 'whiteStabilizer', 'White stabilizer', 'Tames bright areas.', 0, 20),
    sw('sound', 'aigamesound', 'AI game sound', 'Tunes the sound for the game genre.'),
  ] },
  { id: 'sound', title: 'Sound', rows: [
    choice('sound', 'soundOutput', 'Sound output', 'Where the TV plays sound.', [C('tv_speaker', 'TV speakers'), C('external_arc', 'HDMI (ARC) device'),
      C('external_optical', 'Optical device'), C('bt_soundbar', 'Bluetooth device'), C('headphone', 'Wired headphones'),
      C('tv_speaker_external_arc', 'HDMI (ARC) device + TV speakers'), C('tv_speaker_bluetooth', 'Bluetooth + TV speakers'),
      C('tv_speaker_headphone', 'Wired headphones + TV speakers')]),
    sw('sound', 'eArcSupport', 'eARC', 'Full-bandwidth audio (Dolby Atmos, lossless) to an eARC soundbar or receiver.'),
    choice('sound', 'soundOutputDigital', 'Digital sound out', 'Format sent to the soundbar or receiver.', [C('auto', 'Auto'), C('pcm', 'PCM'), C('passThrough', 'Pass through')]),
    choice('sound', 'soundMode', 'Sound mode', 'The TV speakers\' sound preset.', [C('aiSoundPlus', 'AI Sound Pro'), C('aiSound', 'AI Sound'),
      C('standard', 'Standard'), C('news', 'Clear Voice'), C('movie', 'Cinema'), C('sports', 'Sports'), C('music', 'Music'), C('game', 'Game Optimizer')]),
    sw('sound', 'autoVolume', 'Auto volume', 'Evens out loud adverts and quiet scenes.'),
    num('sound', 'audioBalance', 'Balance', 'Left / right balance of the TV speakers.', -50, 50, { store: 'string' }),
    sw('general', 'blueToothSpeakerMode', 'Bluetooth speaker mode', 'Lets a phone play music through the TV.'),
  ] },
  { id: 'hdmi', title: 'HDMI inputs', rows: [
    choice('other', 'uhdDeepColor', 'HDMI Ultra HD Deep Colour', 'Full 4K HDR / high frame-rate signal for the input on screen.',
      [C('off', 'Off'), C('on', 'On'), C('4k', '4K'), C('8k', '8K'), C('auto', 'Auto')]),
    ...[1, 2, 3, 4].map(n => sw('other', 'uhdDeepColorHDMI' + n, `Deep Colour · HDMI ${n}`, 'Full 4K HDR signal on this input.', { unless: 'uhdDeepColor' })),
    ...[1, 2, 3, 4].map(n => choice('sound', 'inputAudioFormatHDMI' + n, `Audio format · HDMI ${n}`, 'Bitstream passes Dolby/DTS through; PCM decodes on the device.',
      [C('bitstream', 'Bitstream'), C('pcm', 'PCM')])),
  ] },
  { id: 'devices', title: 'Connected devices', rows: [
    sw('other', 'simplinkEnable', 'HDMI-CEC (SIMPLINK)', 'Control connected HDMI devices with the TV remote.'),
    sw('other', 'simplinkAutoPowerOn', 'Auto power sync', 'Turns connected devices on and off with the TV.'),
    sw('option', 'enableIpControl', 'IP control', 'Lets home-automation systems control the TV over the network.'),
  ] },
];

const ROWS = {};
for (const s of SECTIONS) for (const r of s.rows) { r.section = s.id; r.id = r.key; ROWS[r.id] = r; }
const titleCase = v => String(v).replace(/([a-z])([A-Z0-9])/g, '$1 $2').replace(/^./, c => c.toUpperCase());

function create(luna) {
  async function readCategory(cat, keys) {
    const res = await luna(SVC + '/getSystemSettings', { category: cat });
    const values = (res && res.returnValue !== false && res.settings) || {};
    const present = keys.filter(k => values[k] !== undefined);
    const descs = {};
    if (present.length) {
      const d = await luna(SVC + '/getSystemSettingDesc', { category: cat, keys: present });
      for (const x of (d && d.results) || []) descs[x.key] = x;
    }
    return { values, descs, dimension: res && res.dimension };
  }

  function rowOut(r, v, desc) {
    const out = { id: r.id, type: r.type, title: r.title, desc: r.desc };
    if (desc && desc.ui && desc.ui.active === false) out.active = false;
    if (r.type === 'switch') out.value = v === r.on;
    else if (r.type === 'number') {
      out.value = Number(v);
      const range = desc && desc.values && (desc.values.range || desc.values);
      out.min = range && typeof range.min === 'number' ? range.min : r.min;
      out.max = range && typeof range.max === 'number' ? range.max : r.max;
    } else {
      const ext = desc && desc.values && desc.values.arrayExt;
      let choices = r.choices ? r.choices.slice() : [];
      if (ext) {
        const visible = ext.filter(x => x.visible !== false).map(x => x.value);
        if (r.choices) choices = choices.filter(c => visible.indexOf(c.value) >= 0);
        else choices = visible.map(val => C(val, (r.labels && r.labels[val]) || titleCase(val)));
      }
      if (!choices.some(c => c.value === v)) choices.push(C(v, (r.labels && r.labels[v]) || titleCase(v)));
      out.choices = choices;
      out.value = v;
    }
    return out;
  }

  return {
    /** Every section with the rows this TV reports. */
    async read() {
      const cats = {};
      for (const r of Object.values(ROWS)) (cats[r.category] = cats[r.category] || []).push(r.key);
      const data = {};
      for (const cat of Object.keys(cats)) data[cat] = await readCategory(cat, cats[cat]).catch(() => ({ values: {}, descs: {} }));
      const sections = [];
      for (const s of SECTIONS) {
        const rows = [];
        for (const r of s.rows) {
          const d = data[r.category], v = d.values[r.key];
          if (v === undefined) continue;
          if (r.unless && d.values[r.unless] !== undefined) continue;
          rows.push(rowOut(r, v, d.descs[r.key]));
        }
        if (rows.length) sections.push({ id: s.id, title: s.title, note: s.note, rows });
      }
      return { sections };
    },

    async set(id, value) {
      const r = ROWS[id];
      if (!r) throw new Error('Unknown setting');
      let stored;
      if (r.type === 'switch') stored = value === true ? r.on : r.off;
      else if (r.type === 'number') {
        const n = Number(value);
        if (!(Number.isInteger(n) && n >= r.min && n <= r.max)) throw new Error('Out of range');
        stored = r.store === 'string' ? String(n) : n;
      } else {
        stored = String(value);
        if (r.choices && !r.choices.some(c => c.value === stored)) throw new Error('Not a value this setting takes');
      }
      const payload = { category: r.category, settings: Object.assign({ [r.key]: stored }, r.alsoSet || {}) };
      if (r.dimensioned) {
        const cur = await luna(SVC + '/getSystemSettings', { category: r.category, keys: [r.key] });
        if (cur && cur.dimension) payload.dimension = cur.dimension;
      }
      const res = await luna(SVC + '/setSystemSettings', payload);
      if (!res || res.returnValue !== true) throw new Error((res && res.errorText) || 'The TV didn\'t accept that');
    },
  };
}

module.exports = { create };
