'use strict';
/*
 * Roku TVs, controlled over Roku's External Control Protocol (ECP, http://<roku>:8060) by a CanvasFrame server
 * elsewhere on the network (a Roku can't run CanvasFrame itself). Needs the Roku's Settings > System >
 * Advanced system settings > Control by mobile apps > Network access set to Enabled.
 *
 * Exposes the same API shapes the dashboard uses for the local TV (status, inputs, apps, keys, volume), with
 * feature flags hiding what a Roku doesn't have (home screen, TV settings, privacy controls).
 */
const http = require('http');
const dgram = require('dgram');

const KEYS = {
  DPAD_UP: 'Up', DPAD_DOWN: 'Down', DPAD_LEFT: 'Left', DPAD_RIGHT: 'Right', DPAD_CENTER: 'Select', ENTER: 'Select',
  BACK: 'Back', HOME: 'Home', MENU: 'Info', MEDIA_PLAY_PAUSE: 'Play', MEDIA_PLAY: 'Play', MEDIA_PAUSE: 'Play',
  MEDIA_REWIND: 'Rev', MEDIA_FAST_FORWARD: 'Fwd', VOLUME_UP: 'VolumeUp', VOLUME_DOWN: 'VolumeDown', MUTE: 'VolumeMute',
  SLEEP: 'PowerOff', WAKEUP: 'PowerOn',
};
const FEATURES = { appToggle: false, art: false, preview: false, privacy: 'roku', clientResize: false, sdcard: false,
  tvSettings: false, appManage: false, homeScreen: false, volumeLevel: false };

function request(host, method, path, timeout) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host, port: 8060, method, path, timeout: timeout || 5000 }, res => {
      const chunks = [];
      res.on('data', d => chunks.push(d));
      res.on('end', () => {
        const body = Buffer.concat(chunks);
        if (res.statusCode === 403) return reject(new Error('The Roku refused: set Settings > System > Advanced system settings > Control by mobile apps > Network access to Enabled'));
        if (res.statusCode >= 400) return reject(new Error('Roku HTTP ' + res.statusCode));
        resolve({ body, type: res.headers['content-type'] || '' });
      });
    });
    req.on('timeout', () => req.destroy(new Error('The Roku did not answer')));
    req.on('error', e => reject(new Error(e.message === 'The Roku did not answer' ? e.message : 'Cannot reach the Roku (' + e.code + ')')));
    req.end();
  });
}
const tag = (xml, name) => { const m = xml.match(new RegExp('<' + name + '>([^<]*)</' + name + '>')); return m ? decode(m[1]) : ''; };
const decode = s => s.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/ /g, ' ');

/** Roku TVs answering an SSDP search for "roku:ecp" within ~2.5 s. */
function discover() {
  return new Promise(resolve => {
    const found = new Set();
    const sock = dgram.createSocket('udp4');
    const msg = Buffer.from('M-SEARCH * HTTP/1.1\r\nHost: 239.255.255.250:1900\r\nMan: "ssdp:discover"\r\nST: roku:ecp\r\nMX: 2\r\n\r\n');
    sock.on('message', m => { const loc = String(m).match(/LOCATION:\s*http:\/\/([\d.]+):8060/i); if (loc) found.add(loc[1]); });
    sock.on('error', () => {});
    sock.bind(() => { try { sock.send(msg, 1900, '239.255.255.250'); setTimeout(() => sock.send(msg, 1900, '239.255.255.250'), 600); } catch (e) { /* no network */ } });
    setTimeout(() => { try { sock.close(); } catch (e) { /* closed */ } resolve([...found]); }, 2500);
  });
}

function create(host) {
  const get = (path, t) => request(host, 'GET', path, t).then(r => r.body.toString('utf8'));
  const post = (path) => request(host, 'POST', path);
  let appsCache = null, appsAt = 0;
  async function appList() {
    if (appsCache && Date.now() - appsAt < 60000) return appsCache;
    const xml = await get('/query/apps');
    appsCache = [...xml.matchAll(/<app id="([^"]+)" type="([^"]+)"[^>]*>([^<]*)<\/app>/g)].map(m => ({ id: m[1], type: m[2], title: decode(m[3]) }));
    appsAt = Date.now();
    return appsCache;
  }

  const api = {
    host,
    async info() {
      const x = await get('/query/device-info');
      return { name: tag(x, 'friendly-device-name') || tag(x, 'user-device-name') || 'Roku', model: (tag(x, 'vendor-name') + ' ' + tag(x, 'model-name')).trim(),
        isTv: tag(x, 'is-tv') === 'true', ecp: tag(x, 'ecp-setting-mode') };
    },
    async status() {
      const [x, a] = await Promise.all([get('/query/device-info'), get('/query/active-app').catch(() => '')]);
      const m = a.match(/<app id="([^"]*)"[^>]*>([^<]*)<\/app>/);
      const power = tag(x, 'power-mode');
      return {
        platform: 'roku', model: (tag(x, 'vendor-name') + ' ' + tag(x, 'model-name')).trim() || 'Roku',
        osName: 'Roku OS ' + tag(x, 'software-version'), ip: host, adb: 'CONNECTED', screenOn: power === 'PowerOn',
        foreground: m ? (m[1] === '562859' ? 'Roku Home' : decode(m[2])) : (power === 'PowerOn' ? 'Roku Home' : 'Off'),
        foregroundPkg: m ? m[1] : '', uptimeSec: Number(tag(x, 'uptime')) || undefined, build: tag(x, 'software-build'),
        network: tag(x, 'network-type'), name: tag(x, 'friendly-device-name'), brand: 'roku', features: FEATURES,
      };
    },
    async inputs() {
      return (await appList()).filter(a => a.type === 'tvin').map(a => ({ id: a.id, label: a.title }));
    },
    async apps() {
      return { apps: (await appList()).filter(a => a.type === 'appl').map(a => ({ pkg: a.id, label: a.title, enabled: true, system: false,
        launchable: true, protected: true, icon: true })) };
    },
    launch: id => post('/launch/' + encodeURIComponent(id)),
    key(name) {
      const k = KEYS[name];
      if (!k) throw new Error('That key isn\'t available on a Roku');
      return post('/keypress/' + k);
    },
    icon: id => request(host, 'GET', '/query/icon/' + encodeURIComponent(id), 8000),
  };
  return api;
}

module.exports = { create, discover, FEATURES };
