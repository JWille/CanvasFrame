'use strict';
// Wake-on-LAN (same as Wol.java and the PC app's Wol.cs): the magic packet (6 x 0xFF + the MAC 16 times) as UDP to ports 9
// and 7, to the limited broadcast, each local network's broadcast address and the target's last IP. Plus this TV's own MAC
// addresses for the shared site, so the PC, the other TVs and Home Assistant can wake it (LG "Turn on via Wi-Fi").

const fs = require('fs');
const os = require('os');
const dgram = require('dgram');

function packet(mac) {
  const hex = String(mac || '').replace(/[^0-9a-f]/gi, '');
  if (hex.length !== 12) throw new Error('Not a MAC address: ' + mac);
  const m = Buffer.from(hex, 'hex');
  const p = Buffer.alloc(102, 0xff);
  for (let r = 0; r < 16; r++) m.copy(p, 6 + 6 * r);
  return p;
}

function broadcasts() {
  const out = new Set(['255.255.255.255']);
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family !== 'IPv4' || a.internal || !a.netmask) continue;
      const ip = a.address.split('.').map(Number), nm = a.netmask.split('.').map(Number);
      out.add(ip.map((b, i) => (b & nm[i]) | (~nm[i] & 255)).join('.'));
    }
  }
  return [...out];
}

/** Sends the packet; resolves to the addresses it went to. */
function send(mac, ip) {
  const p = packet(mac);
  const to = broadcasts();
  if (/^\d+\.\d+\.\d+\.\d+$/.test(ip || '')) to.push(ip);
  return new Promise(resolve => {
    const s = dgram.createSocket('udp4');
    const sent = [];
    s.bind(() => {
      s.setBroadcast(true);
      let left = to.length * 2;
      const done = () => { if (--left === 0) { s.close(); resolve(sent); } };
      for (const addr of to) for (const port of [9, 7]) s.send(p, port, addr, err => { if (!err && port === 9) sent.push(addr); done(); });
    });
  });
}

/** This TV's MAC addresses: { wifi, eth, using }. */
function macs() {
  const rd = f => { try { return fs.readFileSync(f, 'utf8').trim(); } catch (e) { return ''; } };
  const o = {};
  for (const [k, dev] of [['wifi', 'wlan0'], ['eth', 'eth0']]) {
    const a = rd('/sys/class/net/' + dev + '/address').toLowerCase();
    if (/^([0-9a-f]{2}:){5}[0-9a-f]{2}$/.test(a) && a !== '00:00:00:00:00:00') {
      o[k] = a;
      if (!o.using && rd('/sys/class/net/' + dev + '/operstate') === 'up') o.using = k;
    }
  }
  return o;
}

module.exports = { packet, send, macs };
