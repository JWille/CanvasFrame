'use strict';
/*
 * HTTPS for the dashboard and TV-to-TV sync (same scheme as Tls.java on Android). Each TV makes its own key (EC P-256)
 * and self-signed certificate once, with the openssl tool, and keeps them so the fingerprint stays the same. Browsers
 * show a one-time warning; other CanvasFrame TVs pin the fingerprint they saw when the TV was added, and only send
 * their key to that exact certificate. Node 12 compatible.
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const http = require('http');
const tlsMod = require('tls');
const { execFileSync } = require('child_process');

/** SHA-256 of DER bytes as "AB:CD:…" (what browsers and openssl show). */
function fingerprintDer(der) {
  return crypto.createHash('sha256').update(der).digest('hex').toUpperCase().match(/../g).join(':');
}

function pemToDer(pem) {
  const m = /-----BEGIN CERTIFICATE-----([\s\S]+?)-----END CERTIFICATE-----/.exec(String(pem));
  return m ? Buffer.from(m[1].replace(/\s+/g, ''), 'base64') : null;
}

/**
 * Key + certificate in dir (made on first use). Returns { key, cert, fingerprint } or throws if openssl is missing.
 */
function ensure(dir, commonName, ip) {
  const keyF = path.join(dir, 'tls.key'), certF = path.join(dir, 'tls.crt');
  if (!fs.existsSync(keyF) || !fs.existsSync(certF)) {
    fs.mkdirSync(dir, { recursive: true });
    // Own minimal config: the system openssl.cnf may add extensions of its own (a duplicate basicConstraints makes
    // browsers reject the certificate outright).
    const conf = path.join(dir, 'req.cnf');
    fs.writeFileSync(conf, ['[req]', 'distinguished_name = dn', 'x509_extensions = ext', 'prompt = no', '[dn]',
      'CN = ' + String(commonName || 'CanvasFrame').replace(/[^\w .-]/g, ' ').slice(0, 60), '[ext]',
      'basicConstraints = critical,CA:TRUE']
      .concat(/^\d{1,3}(\.\d{1,3}){3}$/.test(ip || '') ? ['subjectAltName = IP:' + ip] : []).join('\n') + '\n');
    execFileSync('openssl', ['req', '-x509', '-config', conf, '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes',
      '-keyout', keyF + '.tmp', '-out', certF + '.tmp', '-days', '7300', '-sha256'], { stdio: 'ignore', timeout: 30000 });
    fs.unlinkSync(conf);
    fs.chmodSync(keyF + '.tmp', 0o600);
    fs.renameSync(keyF + '.tmp', keyF);
    fs.renameSync(certF + '.tmp', certF);
  }
  const key = fs.readFileSync(keyF), cert = fs.readFileSync(certF);
  const der = pemToDer(cert);
  if (!der) throw new Error('bad certificate in ' + certF);
  return { key, cert, fingerprint: fingerprintDer(der) };
}

/**
 * An http.Agent whose connections are TLS to a certificate with fingerprint pin (connection refused before anything
 * is sent otherwise). With pin null it accepts any certificate and records it in seen.fp (first contact).
 */
function pinnedAgent(pin, seen) {
  const agent = new http.Agent({ keepAlive: false });
  agent.createConnection = (opts, cb) => {
    const s = tlsMod.connect({ host: opts.host, port: opts.port, rejectUnauthorized: false, servername: '' });
    let done = false;
    const finish = (e, sock) => { if (!done) { done = true; cb(e, sock); } };
    s.once('secureConnect', () => {
      const c = s.getPeerCertificate(true);
      const fp = c && c.raw ? fingerprintDer(c.raw) : '';
      if (seen) seen.fp = fp;
      if (!fp || (pin && fp !== pin)) {
        s.destroy();
        return finish(Object.assign(new Error('certificate changed'), { pinMismatch: true, seen: fp }));
      }
      finish(null, s);
    });
    s.once('error', e => finish(e));
    return undefined;   // the socket is handed over through cb once the certificate checks out
  };
  return agent;
}

/** GET without a key: parsed JSON on 200, null on any other status. agent: pinnedAgent for https, null for http. */
function fetchJson(url, agent, timeoutMs) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const req = http.request({ host: u.hostname, port: u.port, path: u.pathname + u.search, method: 'GET', agent: agent || undefined,
      timeout: timeoutMs || 8000 }, res => {
      const chunks = [];
      res.on('data', d => chunks.push(d));
      res.on('end', () => {
        if (res.statusCode !== 200) return resolve(null);
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch (e) { resolve(null); }
      });
    });
    req.on('timeout', () => req.destroy(Object.assign(new Error('no answer'), { unreachable: true })));
    req.on('error', e => {
      if (/ECONN|EHOST|ENET|ETIMEDOUT/.test(e.code || '')) e.unreachable = true;
      reject(e);
    });
    req.end();
  });
}

module.exports = { ensure, pinnedAgent, fetchJson, fingerprintDer };
