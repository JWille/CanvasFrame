'use strict';
/*
 * Network tab (webOS): connection details, DNS servers (settable) and where the clock comes from (read-only).
 * DNS goes through LG's connection manager (`setdns`, an empty list = back to the router's servers). The clock can't
 * be pointed at an NTP server: webOS has an NTP client, but its time service isn't allowed network access
 * ("Could not send data to NTP server: Permission denied"), so the TV keeps LG's own time service.
 */
const fs = require('fs');
const path = require('path');
const net = require('net');

const CONNMAN = '/var/lib/connman';
const SOURCES = { sdp: "LG's time service (internet)", ntp: 'NTP server', nitz: 'Mobile network', broadcast: 'TV broadcast signal',
  'broadcast-adjusted': 'TV broadcast signal', micom: "TV's built-in clock", manual: 'Set by hand' };

/** True when connman holds DNS servers set by hand for this connection (otherwise they come from the router). */
function manualDns(type, ssid) {
  try {
    const want = type === 'wifi' ? 'SSID=' + Buffer.from(ssid || '', 'utf8').toString('hex') : null;
    for (const d of fs.readdirSync(CONNMAN)) {
      if (type === 'wifi' ? !d.startsWith('wifi_') : !d.startsWith('ethernet_')) continue;
      let s = '';
      try { s = fs.readFileSync(path.join(CONNMAN, d, 'settings'), 'utf8'); } catch (e) { continue; }
      if (want && s.split('\n').indexOf(want) < 0) continue;
      return /^Nameservers=\S/m.test(s);
    }
  } catch (e) { /* unknown */ }
  return null;
}

function create(tv) {
  async function connection() {
    const cm = await tv.luna('com.webos.service.connectionmanager/getStatus', {});
    if (cm.returnValue === false) throw new Error(cm.errorText || "Couldn't read the network status");
    const wired = cm.wired && cm.wired.state === 'connected';
    return { type: wired ? 'wired' : 'wifi', c: (wired ? cm.wired : cm.wifi) || {} };
  }

  return {
    async status() {
      const { type, c } = await connection();
      const servers = ['dns1', 'dns2', 'dns3'].map(k => c[k]).filter(Boolean);
      const t = await tv.luna('com.webos.service.systemservice/time/getSystemTime', {});
      const src = t.systemTimeSource || '';
      return {
        connection: { type, name: type === 'wifi' ? c.ssid || c.displayName || '' : 'Ethernet', ip: c.ipAddress || '', gateway: c.gateway || '',
          netmask: c.netmask || '', method: c.method || '', proxy: (c.proxyinfo && c.proxyinfo.method) || '' },
        dns: { servers, manual: manualDns(type, c.ssid), editable: true },
        time: { source: src, sourceLabel: SOURCES[src] || src, timezone: t.timezone || '', valid: !!t.timeValid, ntpEditable: false,
          note: "LG TVs set their clock from LG's own time service. The TV has an NTP client, but LG doesn't let it reach the network, so a time server can't be chosen here." },
      };
    },

    /** servers: list of IP addresses; empty = use the router's (DHCP) servers again. */
    async setDns(servers) {
      const list = (Array.isArray(servers) ? servers : []).map(s => String(s).trim()).filter(Boolean);
      if (list.length > 3) throw new Error('Up to 3 DNS servers');
      for (const s of list) if (!net.isIP(s)) throw new Error(`"${s}" isn't an IP address (DNS servers are set by address, e.g. 1.1.1.1)`);
      const { type, c } = await connection();
      const params = { dns: list };
      if (type === 'wifi' && c.ssid) params.ssid = c.ssid;
      const r = await tv.luna('com.webos.service.connectionmanager/setdns', params, 15000);
      if (r.returnValue === false) throw new Error(r.errorText || 'The TV refused the DNS change');
    },
  };
}

module.exports = { create };
