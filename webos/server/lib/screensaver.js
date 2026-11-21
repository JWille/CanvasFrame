'use strict';
/*
 * CanvasFrame screen savers for webOS: a moving clock, and a slideshow of the CanvasFrame background photos.
 * Works like Glasshouse's: a staged copy of the screen saver app (manifest + our QML) is bind-mounted over
 * /usr/palm/applications/com.webos.app.screensaver, so the TV runs and dismisses it as its own screen saver.
 * Only offered where the stock screen saver is QML (webOS 9 and earlier); Glasshouse found swapping the
 * Flutter one on webOS 10 can leave picture/sound off until a power cut, so that case is never touched.
 */
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');

const APP_DIR = '/usr/palm/applications/com.webos.app.screensaver';
const MODES = {
  stock: { label: 'LG default', desc: 'The screen saver the TV came with.' },
  clock: { label: 'Clock', desc: 'A large clock and date on black, alternating with the weather and drifting slowly across the screen so nothing burns in.' },
  photos: { label: 'CanvasFrame photos', desc: 'Your CanvasFrame background photos, cross-fading every 30 seconds, with the time and the weather drifting slowly across them.' },
};
const run = (cmd, args) => new Promise(r => execFile(cmd, args, { timeout: 10000 }, (e, out) => r({ err: e, out: String(out || '') })));
const qstr = s => JSON.stringify(String(s));

const HEAD = `import QtQuick 2.4
import Eos.Window 0.1
import QtQuick.Window 2.2

WebOSWindow {
    id: win
    width: Screen.width > 0 ? Screen.width : 1920
    height: Screen.height > 0 ? Screen.height : 1080
    windowType: "_WEBOS_WINDOW_TYPE_SCREENSAVER"
    appId: "com.webos.app.screensaver"
    title: "Screen Saver"
    visible: true
    color: "black"
    FontLoader { id: thin; source: "file:///usr/share/fonts/MuseoSans-Thin.ttf" }
    FontLoader { id: light; source: "file:///usr/share/fonts/MuseoSans-Light.ttf" }
`;

/**
 * The floating panel of both screen savers: time + date, alternating with the weather (30 s / 15 s, 3 s dim between),
 * drifting slowly and continuously around the screen so it's obvious the TV is in screen-saver mode (and nothing stays
 * put). Weather comes from the server over loopback (/api/home/saverinfo; port 8080, else 8081), refreshed every 5 min.
 * `big` = time size as a fraction of the screen height.
 */
function infoQml(h24, big) {
  return `
    property string phase: "clock"
    property var wx: null
    property int wxPort: 8080
    Item {
        id: info
        width: panel.width; height: panel.height
        x: (win.width - width) / 2; y: (win.height - height) / 2
        Rectangle { id: panel; color: "#000000"; opacity: 0.32; radius: win.height * 0.03
            width: Math.max(clockCol.width, wxCol.width) + win.height * 0.08; height: Math.max(clockCol.height, wxCol.height) + win.height * 0.05 }
        Column {
            id: clockCol; anchors.centerIn: panel; opacity: win.phase === "clock" ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 3000; easing.type: Easing.InOutSine } }
            Text { id: time; color: "#f2f2f2"; font.family: thin.name; font.weight: Font.Thin; font.pixelSize: win.height * ${big}; anchors.horizontalCenter: parent.horizontalCenter
                style: Text.Raised; styleColor: "#66000000" }
            Text { id: date; color: "#d0d0d0"; font.family: light.name; font.weight: Font.Light; font.pixelSize: win.height * ${big} * 0.3; anchors.horizontalCenter: parent.horizontalCenter
                style: Text.Raised; styleColor: "#66000000" }
        }
        Column {
            id: wxCol; anchors.centerIn: panel; opacity: win.phase === "wx" ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 3000; easing.type: Easing.InOutSine } }
            Row {
                anchors.horizontalCenter: parent.horizontalCenter; spacing: win.height * 0.025
                Text { id: wxTemp; color: "#f2f2f2"; font.family: thin.name; font.weight: Font.Thin; font.pixelSize: win.height * ${big}
                    style: Text.Raised; styleColor: "#66000000" }
                Text { id: wxCond; anchors.verticalCenter: parent.verticalCenter; color: "#e0e0e0"; font.family: light.name; font.weight: Font.Light
                    font.pixelSize: win.height * ${big} * 0.32; style: Text.Raised; styleColor: "#66000000" }
            }
            Text { id: wxMore; color: "#c8c8c8"; font.family: light.name; font.weight: Font.Light; font.pixelSize: win.height * ${big} * 0.24
                anchors.horizontalCenter: parent.horizontalCenter; style: Text.Raised; styleColor: "#66000000" }
        }
    }
    // Slow drift: glide to a random spot at a gentle, constant speed, then pick the next one.
    NumberAnimation { id: driftX; target: info; property: "x"; easing.type: Easing.InOutSine }
    NumberAnimation { id: driftY; target: info; property: "y"; easing.type: Easing.InOutSine; onStopped: win.drift() }
    function drift() {
        var m = win.height * 0.05;
        var tx = m + Math.random() * Math.max(0, win.width - info.width - 2 * m);
        var ty = m + Math.random() * Math.max(0, win.height - info.height - 2 * m);
        var d = Math.sqrt((tx - info.x) * (tx - info.x) + (ty - info.y) * (ty - info.y));
        var ms = Math.max(8000, d / (win.width * 0.006) * 1000);   // about 0.6 % of the width per second
        driftX.stop(); driftY.stop();
        driftX.to = tx; driftX.duration = ms; driftY.to = ty; driftY.duration = ms;
        driftX.start(); driftY.start();
    }
    function clockTick() {
        var n = new Date();
        time.text = ${h24 ? 'Qt.formatTime(n, "H:mm")' : 'Qt.formatTime(n, "h:mm AP").replace(/ ?[AP]M$/i, "")'};
        date.text = Qt.formatDate(n, "dddd, MMMM d");
    }
    function loadWx() {
        var x = new XMLHttpRequest();
        x.onreadystatechange = function () {
            if (x.readyState !== 4) return;
            var j = null;
            try { j = JSON.parse(x.responseText); } catch (e) { j = null; }
            if (x.status === 200 && j) {
                win.wx = j.on ? j : null;
                if (win.wx) {
                    wxTemp.text = j.temp; wxCond.text = j.cond;
                    var more = [];
                    if (j.hi && j.lo) more.push("High " + j.hi + "  Low " + j.lo);
                    if (j.feels && j.feels !== j.temp) more.push("Feels like " + j.feels);
                    if (j.place) more.push(j.place);
                    wxMore.text = more.join("   \\u00b7   ");
                }
            } else if (win.wxPort === 8080) { win.wxPort = 8081; win.loadWx(); }
        };
        x.open("GET", "http://127.0.0.1:" + win.wxPort + "/api/home/saverinfo");
        x.send();
    }
    // Time and date 30 s, then the weather 15 s (when there is weather); each change dims out and back over 3 s.
    Timer { interval: 1000; running: true; repeat: true; triggeredOnStart: true; onTriggered: win.clockTick() }
    Timer { id: phaseT; interval: 30000; running: true; repeat: true
        onTriggered: {
            if (win.phase === "clock" && win.wx) { win.phase = "wx"; phaseT.interval = 15000; }
            else { win.phase = "clock"; phaseT.interval = 30000; }
        } }
    Timer { interval: 300000; running: true; repeat: true; triggeredOnStart: true; onTriggered: win.loadWx() }
    Timer { interval: 500; running: true; repeat: false; onTriggered: win.drift() }
    // Doorbell (lib/doorbell.js): a centred panel with the Protect snapshot, refreshed about once a second (two images,
    // so the new one only appears once loaded), while the server says an alert is on (/api/home/door, loopback).
    property real doorAt: 0
    property bool doorSnap: false
    property bool doorA: true
    Rectangle {
        id: doorPanel; z: 100; opacity: 0; visible: opacity > 0
        Behavior on opacity { NumberAnimation { duration: 600 } }
        property real u: win.height / 540   // the 960x540 layout of the home page's card
        width: 400 * u; height: doorCol.height + 30 * u
        anchors.centerIn: parent; radius: 22 * u; border.color: "#66C8CCD6"; border.width: Math.max(1, 1.5 * u)
        gradient: Gradient { GradientStop { position: 0; color: "#F52C3039" } GradientStop { position: 1; color: "#F5191C22" } }
        Column {
            id: doorCol; anchors.centerIn: parent; spacing: 13 * doorPanel.u; width: parent.width - 30 * doorPanel.u
            Item {
                width: parent.width; height: 46 * doorPanel.u
                Rectangle {
                    id: bellBadge; width: 44 * doorPanel.u; height: width; radius: width / 2; anchors.verticalCenter: parent.verticalCenter
                    gradient: Gradient { GradientStop { position: 0; color: "#C9AE7C" } GradientStop { position: 1; color: "#6E5A38" } }
                    Canvas {
                        anchors.centerIn: parent; width: 26 * doorPanel.u; height: width
                        onPaint: {
                            var g = getContext("2d"); g.reset(); g.scale(width / 24, height / 24); g.fillStyle = "#ffffff";
                            g.beginPath(); g.moveTo(12, 3.4); g.bezierCurveTo(8.4, 3.4, 6.6, 6.2, 6.6, 10); g.lineTo(6.6, 15.4); g.lineTo(4.6, 17.6);
                            g.lineTo(19.4, 17.6); g.lineTo(17.4, 15.4); g.lineTo(17.4, 10); g.bezierCurveTo(17.4, 6.2, 15.6, 3.4, 12, 3.4); g.closePath(); g.fill();
                            g.beginPath(); g.arc(12, 19.6, 1.9, 0, Math.PI * 2); g.fill();
                            g.beginPath(); g.arc(12, 2.9, 1.1, 0, Math.PI * 2); g.fill();
                        }
                    }
                }
                Column {
                    anchors.left: bellBadge.right; anchors.leftMargin: 13 * doorPanel.u; anchors.right: doorRight.left; anchors.rightMargin: 8 * doorPanel.u
                    anchors.verticalCenter: parent.verticalCenter
                    Text { id: doorLabel; color: "#f4f5f7"; font.family: light.name; font.weight: Font.DemiBold; font.pixelSize: 20 * doorPanel.u; elide: Text.ElideRight; width: parent.width }
                    Text { id: doorCam; color: "#9ca0aa"; font.family: light.name; font.pixelSize: 14 * doorPanel.u; elide: Text.ElideRight; width: parent.width }
                }
                Column {
                    id: doorRight; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; spacing: 5 * doorPanel.u
                    Text { id: doorTime; color: "#c9ccd3"; font.family: light.name; font.pixelSize: 14 * doorPanel.u; anchors.right: parent.right }
                    Rectangle {
                        id: doorLive; visible: false; anchors.right: parent.right; color: "#4A2A30"; radius: height / 2
                        width: liveRow.width + 22 * doorPanel.u; height: 22 * doorPanel.u
                        Row {
                            id: liveRow; anchors.centerIn: parent; spacing: 6 * doorPanel.u
                            Rectangle { width: 7 * doorPanel.u; height: width; radius: width / 2; color: "#E0707A"; anchors.verticalCenter: parent.verticalCenter }
                            Text { text: "LIVE"; color: "#f1c9cd"; font.family: light.name; font.weight: Font.DemiBold; font.pixelSize: 12 * doorPanel.u; font.letterSpacing: 1 }
                        }
                    }
                }
            }
            Rectangle {
                width: parent.width; height: width * 3 / 4; color: "#000000"; radius: 13 * doorPanel.u; clip: true
                Image { id: doorImgA; anchors.fill: parent; fillMode: Image.PreserveAspectCrop; cache: false; asynchronous: true; visible: win.doorA
                    onStatusChanged: if (status === Image.Ready) { doorLive.visible = true; if (!win.doorA) win.doorA = true } }
                Image { id: doorImgB; anchors.fill: parent; fillMode: Image.PreserveAspectCrop; cache: false; asynchronous: true; visible: !win.doorA
                    onStatusChanged: if (status === Image.Ready) { doorLive.visible = true; if (win.doorA) win.doorA = false } }
                Text { anchors.centerIn: parent; visible: !win.doorSnap; color: "#8a8e98"; font.family: light.name; font.pixelSize: 13 * doorPanel.u
                    text: "No picture (no Protect API key set)" }
            }
        }
    }
    function pollDoor() {
        var x = new XMLHttpRequest();
        x.onreadystatechange = function () {
            if (x.readyState !== 4 || x.status !== 200) return;
            var j = null;
            try { j = JSON.parse(x.responseText); } catch (e) { j = null; }
            if (j && j.until) doorCam.text = j.camera || "";
            if (j && j.until && j.at !== win.doorAt) {
                win.doorAt = j.at; win.doorSnap = !!j.snap;
                doorLabel.text = j.label;
                doorTime.text = Qt.formatTime(new Date(j.at), "h:mm AP");
                doorLive.visible = false;
                doorPanel.opacity = 1;
                win.snapDoor();
            } else if ((!j || !j.until) && win.doorAt) { win.doorAt = 0; doorPanel.opacity = 0; }
        };
        x.open("GET", "http://127.0.0.1:" + win.wxPort + "/api/home/door");
        x.send();
    }
    function snapDoor() {
        var u = "http://127.0.0.1:" + win.wxPort + "/api/home/doorsnap?t=" + Date.now();
        if (win.doorA) doorImgB.source = u; else doorImgA.source = u;
    }
    Timer { interval: 1500; running: true; repeat: true; onTriggered: win.pollDoor() }
    Timer { interval: 1000; running: doorPanel.opacity > 0 && win.doorSnap; repeat: true; onTriggered: win.snapDoor() }
`;
}

function clockQml(h24) {
  return HEAD + infoQml(h24, 0.18) + `
}
`;
}

/**
 * sync: null, or { ms, files } to show the same photo as the other TVs: every TV picks photo samePhoto(slot) for
 * slot = floor(now / ms) from the same sorted list (lib/site.js), so they change together without talking to each other.
 */
function photosQml(files, h24, sync) {
  if (sync) files = sync.files;
  return HEAD + `
    property var photos: [${files.map(f => qstr('file://' + f)).join(', ')}]
    property real syncMs: ${sync ? sync.ms : 0}
    property real lastSlot: -1
    property int index: 0
    property bool showA: true
    Image { id: a; anchors.fill: parent; fillMode: Image.PreserveAspectCrop; asynchronous: true; opacity: win.showA ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 2500 } } }
    Image { id: b; anchors.fill: parent; fillMode: Image.PreserveAspectCrop; asynchronous: true; opacity: win.showA ? 0 : 1
        Behavior on opacity { NumberAnimation { duration: 2500 } } }
` + infoQml(h24, 0.085) + `
    Text { visible: win.photos.length === 0; anchors.centerIn: parent; color: "#8a8a8a"; font.family: light.name; font.weight: Font.Light; font.pixelSize: win.height * 0.03
        text: "Add photos in the CanvasFrame dashboard (Home screen \\u2192 Background photos)" }
    function show(i) {
        win.index = i;
        if (win.showA) b.source = win.photos[i]; else a.source = win.photos[i];
        win.showA = !win.showA;
    }
    function next() {
        if (win.photos.length === 0) return;
        win.show((win.index + 1) % win.photos.length);
    }
    // Same as samePhoto() in lib/site.js and Site.java.
    function imul(a, b) {   // Math.imul where the QML engine has it
        if (Math.imul) return Math.imul(a, b);
        var ah = (a >>> 16) & 0xffff, al = a & 0xffff, bh = (b >>> 16) & 0xffff, bl = b & 0xffff;
        return ((al * bl) + (((ah * bl + al * bh) << 16) >>> 0)) | 0;
    }
    function hash32(x) {
        var h = x >>> 0;
        h = win.imul(h ^ (h >>> 16), 0x45d9f3b) >>> 0;
        h = win.imul(h ^ (h >>> 16), 0x45d9f3b) >>> 0;
        return (h ^ (h >>> 16)) >>> 0;
    }
    function samePhoto(slot, n) {
        var i = win.hash32(slot) % n;
        if (n > 1 && i === win.hash32(slot - 1) % n) i = (i + 1) % n;
        return i;
    }
    function syncTick() {
        var slot = Math.floor(Date.now() / win.syncMs);
        if (slot === win.lastSlot) return;
        var first = win.lastSlot < 0;
        win.lastSlot = slot;
        var i = win.samePhoto(slot, win.photos.length);
        if (first) { win.index = i; a.source = win.photos[i]; } else win.show(i);
    }
    function tick() {
        var n = new Date();
        if (win.syncMs > 0 && win.photos.length > 1) win.syncTick();
    }
    Component.onCompleted: { if (win.photos.length && win.syncMs <= 0) { win.index = Math.floor(Math.random() * win.photos.length); a.source = win.photos[win.index]; } }
    Timer { interval: 1000; running: true; repeat: true; triggeredOnStart: true; onTriggered: win.tick() }
    Timer { interval: 30000; running: win.photos.length > 1 && win.syncMs <= 0; repeat: true; onTriggered: win.next() }
}
`;
}

function create(dataDir, luna, cfg, photosDir, log) {
  const STAGE = path.join(dataDir, 'screensaver');
  const STATE = path.join(dataDir, 'screensaver.json');
  let state;
  try { state = JSON.parse(fs.readFileSync(STATE, 'utf8')); } catch (e) { state = { mode: 'stock' }; }

  const stockInfo = () => { try { return JSON.parse(fs.readFileSync(path.join(APP_DIR, 'appinfo.json'), 'utf8')); } catch (e) { return null; } };
  const mounted = () => fs.existsSync(path.join(APP_DIR, '.canvasframe'));
  let stockType = null;   // LG's own screen saver type, read while nothing is laid over it
  const readText = f => { try { return fs.readFileSync(f, 'utf8'); } catch (e) { return ''; } };
  const osMajor = () => { const m = readText('/etc/os-release').match(/VERSION_ID="?(\d+)/); return m ? +m[1] : 0; };
  // Another tool (e.g. Glasshouse) may have its own screen saver mounted over LG's, hiding the real type.
  const foreignMount = () => !mounted() && readText('/proc/mounts').split('\n').some(l => l.split(' ')[1] === APP_DIR);
  function supported() {
    if (osMajor() >= 10) return false;   // webOS 10+: never touch the screen saver (see top of file)
    if (!stockType) {
      if (foreignMount()) stockType = readText('/var/lib/tvweb/screensaver-stock-type').trim() || null;
      else if (!mounted()) { const i = stockInfo(); stockType = i ? i.type : null; }
    }
    return stockType === 'qml';
  }
  const photoFiles = () => {
    try { return fs.readdirSync(photosDir).filter(n => /\.(jpe?g|png|webp)$/i.test(n) && n[0] !== '.').map(n => path.join(photosDir, n)); }
    catch (e) { return []; }
  };

  /** Write the staged app for the current mode (the files can change while mounted). */
  function stage() {
    fs.mkdirSync(path.join(STAGE, 'qml'), { recursive: true });
    if (!fs.existsSync(path.join(STAGE, 'appinfo.json')) || !mounted()) {
      const info = stockInfo() || { id: 'com.webos.app.screensaver', version: '2.0.0', title: 'Screen Saver', visible: false,
        noSplashOnLaunch: true, defaultWindowType: 'screenSaver' };
      info.type = 'qml'; info.main = 'qml/main.qml';
      fs.writeFileSync(path.join(STAGE, 'appinfo.json'), JSON.stringify(info, null, 2));
    }
    fs.writeFileSync(path.join(STAGE, '.canvasframe'), state.mode);
    const h24 = cfg.get().clock === '24';
    const sync = syncFiles();
    planSig = JSON.stringify(sync);
    fs.writeFileSync(path.join(STAGE, 'qml', 'main.qml'), state.mode === 'photos' ? photosQml(photoFiles(), h24, sync) : clockQml(h24));
  }

  async function apply() {
    if (state.mode === 'stock' || !supported()) {
      if (mounted()) await run('/bin/umount', [APP_DIR]);
    } else {
      stage();
      if (!mounted()) {
        const r = await run('/bin/mount', ['--bind', STAGE, APP_DIR]);
        if (r.err) throw new Error('Could not switch the screen saver');
      }
    }
    // A screen saver not on screen right now is simply closed; the TV loads the new one next time. Never close one that's
    // showing: that ends it (seen on webOS 7, 2026-11-20) and wakes the screen at full backlight.
    if (!saverOn) await luna('com.webos.applicationManager/closeByAppId', { id: 'com.webos.app.screensaver' });
  }
  /** CanvasFrame's screen saver chosen but not mounted (seen after a reboot on 2026-11-20: LG's ran instead, with no error
   *  logged): mount it again. Run every minute and before the Screen saver button starts it. */
  async function ensureMounted(why) {
    if (state.mode === 'stock' || !supported() || mounted()) return;
    try {
      stage();
      const r = await run('/bin/mount', ['--bind', STAGE, APP_DIR]);
      log('screensaver: ' + (r.err ? 'mount failed (' + why + '): ' + r.err.message : 'was not mounted, mounted again (' + why + ')'));
    } catch (e) { log('screensaver: mount (' + why + '): ' + e.message); }
  }

  // ---------------------------------------------------------------- night dimming
  // While a CanvasFrame screen saver is on screen, lower the backlight for the time of day (like the Android CanvasArt):
  // none by day, half the "Dim at night" setting at dawn/dusk, all of it at night. LG keeps the backlight per picture
  // mode and input ("dimension"), so the original value is saved together with its dimension and written back to exactly
  // that one. The saved file survives a restart, so a crash mid-dim is put right on the next start.
  const DIM = path.join(dataDir, 'backlight.json');
  let dim = null;          // {orig, dimension} while dimmed
  try { dim = JSON.parse(fs.readFileSync(DIM, 'utf8')); } catch (e) { dim = null; }
  let saverOn = false, saverSince = 0, dimTimer = null, dimBusy = Promise.resolve();
  // Activity near this TV (from the shared site): { same, next } times. Set by server.js.
  let nearby = () => ({ same: 0, next: 0 });
  // Room light (lib/roomlight.js), set by server.js: with "Dim by room light" on and a fresh reading it replaces the clock.
  let room = null;
  // Same photo together (lib/site.js syncPlan): null or { ms, names }. Set by server.js.
  let syncPlan = () => null, planSig = '';
  const syncFiles = () => { const p = syncPlan(); return p ? { ms: p.ms, files: p.names.map(n => path.join(photosDir, n)) } : null; };
  const FADE_SPAN = 5 * 60000;   // the late fade to the minimum takes this long (one step per minute)
  let brightUntil = 0;   // the doorbell panel is up: no dimming for a moment
  const level = (idleSince) => {
    if (Date.now() < brightUntil) return 0;
    const c = cfg.get();
    const pct = Math.max(0, Math.min(90, +(c.art_dim == null ? 40 : c.art_dim))) / 100;
    // By room light: the darker the room, the dimmer (all of "Dim at night" in a dark room, none in a bright one); a dark
    // room counts as night for the late fade. Otherwise (or with no fresh reading) by the clock.
    const rb = room && room.active() ? room.bright() : null;
    const p = rb != null ? (rb < 0.15 ? 'night' : 'day') : cfg.currentPeriod();
    let a = rb != null ? pct * (1 - rb) : p === 'night' ? pct : p === 'day' ? 0 : pct / 2;
    // Late at night: after the screen saver has run a while, slide the rest of the way down to backlight 0.
    const fadeMin = +(c.art_fade_min == null ? 30 : c.art_fade_min);
    // Someone using a TV in this room counts as touching this one (the fade starts over); in a room next door it counts
    // as if it happened half a fade-delay ago (fading is delayed, a deep fade lifts partway).
    const n = nearby(), since = Math.max(idleSince == null ? saverSince : idleSince, n.same, n.next - fadeMin * 30000);
    const late = Date.now() - since - fadeMin * 60000;
    if (p === 'night' && pct > 0 && fadeMin > 0 && late > 0) a += (1 - a) * Math.min(1, late / FADE_SPAN);
    return a;
  };
  async function readBacklight() {
    const r = await luna('com.webos.service.settings/getSystemSettings', { category: 'picture', keys: ['backlight'] });
    const v = r && r.settings ? parseInt(r.settings.backlight, 10) : NaN;
    return isFinite(v) ? { value: v, dimension: r.dimension || null } : null;
  }
  function writeBacklight(v, dimension) {
    const p = { category: 'picture', settings: { backlight: String(Math.max(0, Math.min(100, Math.round(v)))) } };
    if (dimension) p.dimension = dimension;
    return luna('com.webos.service.settings/setSystemSettings', p);
  }
  /** One step at a time (each luna call is a process), queued so restore always runs after a dim in progress. */
  const queued = fn => (dimBusy = dimBusy.then(fn).catch(e => log('backlight: ' + e.message)));
  function dimNow() {
    return queued(async () => {
      if (!saverOn || state.mode === 'stock') return;
      if (!dim) {
        const cur = await readBacklight();
        if (!cur) return;
        dim = { orig: cur.value, dimension: cur.dimension };
        fs.writeFileSync(DIM, JSON.stringify(dim));
      }
      const target = Math.round(dim.orig * (1 - level()));
      const r = await writeBacklight(target, dim.dimension);
      if (r.returnValue === false) log('backlight: ' + (r.errorText || 'not set'));
    });
  }
  function restoreBacklight(why) {
    return queued(async () => {
      if (!dim) return;
      const r = await writeBacklight(dim.orig, dim.dimension);
      if (r.returnValue === false) { log('backlight restore failed: ' + (r.errorText || '?')); return; }
      log('backlight restored to ' + dim.orig + ' (' + why + ')');
      dim = null;
      try { fs.unlinkSync(DIM); } catch (e) { /* gone */ }
    });
  }

  return {
    setNearby(fn) { nearby = fn; },
    setRoomLight(r) { room = r; },
    /** How dark to be (0..1) for a screen idle since `since`: the same night level, late fade and neighbour rules. */
    dimFor(since) { return level(+since || Date.now()); },
    setSyncPlan(fn) { syncPlan = fn; },
    /** The shared document changed: restage the photo screen saver if the same-photo plan changed (used from its next start). */
    syncChanged() { if (state.mode === 'photos' && mounted() && JSON.stringify(syncFiles()) !== planSig) { try { stage(); log('screensaver: same-photo plan updated'); } catch (e) { /* next time */ } } },
    /** Activity near this TV changed: re-check the dimming now rather than at the next minute. */
    nudge() { if (saverOn && state.mode !== 'stock' && supported()) dimNow(); },
    /** Full brightness for ms (the doorbell), then the normal dimming again. */
    brightFor(ms) {
      brightUntil = Date.now() + ms;
      if (saverOn && state.mode !== 'stock' && supported()) dimNow();
      setTimeout(() => { if (saverOn && state.mode !== 'stock' && supported()) dimNow(); }, ms + 500);
    },
    /** From the foreground watcher: the screen saver window appeared or went away. */
    onForeground(wins) {
      const on = wins.some(w => w.appId === 'com.webos.app.screensaver');
      if (on === saverOn) return;
      saverOn = on;
      clearInterval(dimTimer); dimTimer = null;
      if (on && state.mode !== 'stock' && supported()) {
        saverSince = Date.now();
        dimNow();
        dimTimer = setInterval(dimNow, 60000);   // dusk -> night while it runs
      } else if (!on) {
        restoreBacklight('screen saver ended');
      }
    },
    async start() {
      // Left dimmed by a previous run: put it back, unless the screen saver is still on (the foreground watcher reports it
      // within a few seconds of starting; restoring at once flashed the backlight to full during a restart).
      if (dim) setTimeout(() => { if (!saverOn) restoreBacklight('left dimmed by a previous run'); }, 8000);
      supported();
      if (state.mode !== 'stock') await apply().catch(e => log('screensaver: ' + e.message));
      setInterval(() => ensureMounted('check'), 60000);
    },
    /** CanvasFrame's own screen saver is the one the TV will show (chosen, possible here, and mounted). */
    active() { return state.mode !== 'stock' && supported() && mounted(); },
    status() {
      return { supported: supported(), mode: state.mode,
        modes: Object.keys(MODES).map(id => Object.assign({ id }, MODES[id])), photos: photoFiles().length };
    },
    async set(mode) {
      if (!MODES[mode]) throw new Error('Unknown screen saver');
      if (mode !== 'stock' && !supported()) throw new Error('Custom screen savers are not used on this TV');
      state.mode = mode;
      fs.writeFileSync(STATE, JSON.stringify(state));
      await apply();
      log('screensaver: ' + mode);
    },
    /** Photos or the clock format changed: refresh the staged QML. */
    refresh() { if (state.mode !== 'stock' && mounted()) { try { stage(); } catch (e) { /* next time */ } } },
    /** Starts the screen saver now. Returns true if it was already running (LG then answers "Invalid State change Request"). */
    /** Start the screen saver now. `openHome` (Overview's Screen saver button): over a TV input, open the CanvasFrame home
     *  screen first, since LG only starts it over apps and menus; the screen saver then ends on the home screen. */
    async preview(openHome) {
      const pw = await luna('com.webos.service.tvpower/power/getPowerState');
      if (pw && pw.state === 'Screen Saver') return true;
      const fg = await luna('com.webos.applicationManager/getForegroundAppInfo');
      await ensureMounted('screen saver button');   // so the TV starts CanvasFrame's screen saver, not LG's
      if (/^com\.webos\.app\.(hdmi|livetv)/.test(fg.appId || '')) {
        if (!openHome) throw new Error('Switch away from the TV input first: LG only starts the screen saver over apps and menus');
        await luna('com.webos.applicationManager/launch', { id: 'com.canvasframe.home' });
        await new Promise(r => setTimeout(r, 2500));
      }
      const r = await luna('com.webos.service.tvpower/power/turnOnScreenSaver');
      if (r.returnValue === false) throw new Error(r.errorText || 'The TV would not start the screen saver');
      return false;
    },
  };
}

module.exports = { create };
