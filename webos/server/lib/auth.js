'use strict';
/*
 * Optional dashboard sign-in with TOTP authenticator apps (RFC 6238: SHA-1, 6 digits, 30 s).
 * Port of launcher/.../Auth.java with the same rules: off until an authenticator is enrolled, several
 * authenticators allowed, 30-day sliding session cookie, ±1 step, no step reused, brute-force lockout.
 * State lives in <data>/auth.json. Reset: `node server.js --reset-auth` on the TV.
 */
const crypto = require('crypto');
const fs = require('fs');
const qr = require('./qr');

const COOKIE = 'cf_session';
const SESSION_MS = 30 * 24 * 3600 * 1000;
const ISSUER = 'CanvasFrame';
const IP_MAX_FAILS = 5, IP_LOCK_MS = 5 * 60000, ALL_MAX_FAILS = 20, ALL_WINDOW_MS = 10 * 60000;
const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

class AuthError extends Error {}

function base32(buf) {
  let out = '', bits = 0, val = 0;
  for (const b of buf) {
    val = (val << 8) | b; bits += 8;
    while (bits >= 5) { out += B32[(val >>> (bits - 5)) & 31]; bits -= 5; }
  }
  if (bits > 0) out += B32[(val << (5 - bits)) & 31];
  return out;
}
function base32Decode(s) {
  s = s.toUpperCase().replace(/[^A-Z2-7]/g, '');
  const out = [];
  let bits = 0, val = 0;
  for (const ch of s) {
    val = (val << 5) | B32.indexOf(ch); bits += 5;
    if (bits >= 8) { out.push((val >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return Buffer.from(out);
}
function totp(key, step) {
  const msg = Buffer.alloc(8);
  msg.writeUInt32BE(Math.floor(step / 0x100000000), 0);
  msg.writeUInt32BE(step >>> 0, 4);
  const h = crypto.createHmac('sha1', key).update(msg).digest();
  const o = h[h.length - 1] & 15;
  return (h.readUInt32BE(o) & 0x7fffffff) % 1000000;
}
const sha256 = s => crypto.createHash('sha256').update(s).digest('hex');

function create(file) {
  let state;
  const load = () => {
    try { state = JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) { state = {}; }
    state.authenticators = state.authenticators || [];
    state.sessions = state.sessions || {};
    state.keys = state.keys || [];
  };
  load();
  const pending = {}, ipFails = {};
  let allWindowStart = 0, allFails = 0;

  const save = () => {
    const tmp = file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(state));
    fs.renameSync(tmp, file);
  };
  const byId = id => state.authenticators.find(a => a.id === id);

  function accept(a, code, now) {
    code = String(code || '').replace(/\s/g, '');
    if (!/^\d{6}$/.test(code)) return false;
    const key = base32Decode(a.secret), step = Math.floor(now / 30000), want = +code;
    for (let s = step - 1; s <= step + 1; s++) {
      if (s > (a.lastStep || 0) && totp(key, s) === want) { a.lastStep = s; return true; }
    }
    return false;
  }
  /** chain: shared by the sign-ins one code sign-in leads to on the other TVs (passes carry it), for "Sign out on all TVs";
   *  a new one for a code sign-in. via: the TV that vouched (pass sign-ins). */
  function newSession(authId, ua, chain, via) {
    const token = crypto.randomBytes(32).toString('hex'), now = Date.now();
    const s = { auth: authId, created: now, seen: now, ua: String(ua || '').slice(0, 200), chain: chain || crypto.randomBytes(12).toString('hex') };
    if (via) s.via = String(via).slice(0, 60);
    state.sessions[sha256(token)] = s;
    save();
    return token;
  }
  // Sign-in passes between TVs (same format as Auth.java): base64url(JSON {v, to, from, exp, n, auth, chain, who}) + "." +
  // base64url(HMAC-SHA256(mesh secret, first part)); one-time, ~2 minutes. The secret is the site item sso:key.
  const usedPasses = {};
  const b64u = b => Buffer.from(b).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const unb64u = s => Buffer.from(String(s).replace(/-/g, '+').replace(/_/g, '/'), 'base64');
  const hmac = (secret, msg) => crypto.createHmac('sha256', Buffer.from(secret, 'utf8')).update(msg, 'utf8').digest();

  const api = {
    COOKIE, SESSION_MS, AuthError,
    enabled: () => state.authenticators.length > 0,
    isPublic: (method, path) => (method === 'GET' && (path === '/' || path === '/index.html' || path === '/api/auth' || path === '/api/tls'))
      || (method === 'POST' && (path === '/api/auth/login' || path === '/api/auth/pass-login')),

    session(token) {
      if (!token) return null;
      const key = sha256(token), s = state.sessions[key];
      if (!s) return null;
      const now = Date.now();
      if (now - s.seen > SESSION_MS || !byId(s.auth)) { delete state.sessions[key]; save(); return null; }
      if (now - s.seen > 10 * 60000) { s.seen = now; save(); }
      return s;
    },

    login(ip, code, ua) {
      const now = Date.now(), f = ipFails[ip];
      if (f && f.lockedUntil > now) throw new AuthError(`Too many wrong codes. Try again in ${Math.floor((f.lockedUntil - now) / 60000) + 1} min.`);
      if (now - allWindowStart > ALL_WINDOW_MS) { allWindowStart = now; allFails = 0; }
      if (allFails >= ALL_MAX_FAILS) throw new AuthError('Too many wrong codes. Try again in a few minutes.');
      const hit = state.authenticators.find(a => accept(a, code, now));
      if (!hit) {
        allFails++;
        const g = ipFails[ip] = ipFails[ip] || { count: 0, lockedUntil: 0 };
        if (++g.count >= IP_MAX_FAILS) { g.count = 0; g.lockedUntil = now + IP_LOCK_MS; }
        throw new AuthError("That code didn't match. Check the TV's clock is right, and use a fresh code.");
      }
      delete ipFails[ip];
      return newSession(hit.id, ua);
    },

    logout(token) { if (token) { delete state.sessions[sha256(token)]; save(); } },

    makePass(secret, from, to, authId, chain, who) {
      const body = b64u(JSON.stringify({ v: 1, to, from, exp: Date.now() + 120000, n: crypto.randomBytes(12).toString('hex'), auth: authId, chain, who }));
      return body + '.' + b64u(hmac(secret, body));
    },
    /** Checks a pass for this TV (me); returns a new session token. Failures count like wrong codes. */
    passLogin(ip, pass, secret, me, ua) {
      const now = Date.now(), f = ipFails[ip];
      if (f && f.lockedUntil > now) throw new AuthError(`Too many failed sign-ins. Try again in ${Math.floor((f.lockedUntil - now) / 60000) + 1} min.`);
      let p = null, why = "That sign-in link isn't valid (it may be old). Sign in with a code instead.";
      try {
        const dot = String(pass || '').indexOf('.');
        if (dot > 0 && secret) {
          const body = pass.slice(0, dot), sig = unb64u(pass.slice(dot + 1)), want = hmac(secret, body);
          if (sig.length === want.length && crypto.timingSafeEqual(sig, want)) p = JSON.parse(unb64u(body).toString('utf8'));
        }
      } catch (e) { p = null; }
      for (const n of Object.keys(usedPasses)) if (usedPasses[n] < now - 120000) delete usedPasses[n];
      if (p) {
        if (p.to !== me) { why = 'That sign-in link was made for another TV.'; p = null; }
        else if (now > p.exp + 60000 || p.exp - now > 300000) { why = 'That sign-in link has expired. Sign in with a code instead.'; p = null; }
        else if (usedPasses[p.n]) { why = 'That sign-in link was already used.'; p = null; }
      }
      if (!p) {
        const g = ipFails[ip] = ipFails[ip] || { count: 0, lockedUntil: 0 };
        if (++g.count >= IP_MAX_FAILS) { g.count = 0; g.lockedUntil = now + IP_LOCK_MS; }
        throw new AuthError(why);
      }
      usedPasses[p.n] = p.exp;
      const a = byId(p.auth) || state.authenticators[0];   // not synced here yet: any (they're the same people)
      if (!a) throw new AuthError("Sign-in isn't set up on this TV.");
      delete ipFails[ip];
      return newSession(a.id, ua, p.chain, p.who);
    },
    /** {auth, chain} of a session for making a pass; a key caller gets the first authenticator. */
    passIdentity(token) {
      const s = token ? state.sessions[sha256(token)] : null;
      const auth = s ? s.auth : (state.authenticators[0] || {}).id || '';
      if (s && !s.chain) { s.chain = crypto.randomBytes(12).toString('hex'); save(); }   // a session from before chains
      return { auth, chain: s ? s.chain : crypto.randomBytes(12).toString('hex') };
    },
    /** "Sign out on all TVs": ends every session of this chain here. */
    revokeChain(chain) {
      if (!chain) return 0;
      let n = 0;
      for (const k of Object.keys(state.sessions)) if (state.sessions[k].chain === chain) { delete state.sessions[k]; n++; }
      if (n) save();
      return n;
    },

    revoke(id, keepToken) {
      const keep = keepToken ? sha256(keepToken) : '';
      for (const k of Object.keys(state.sessions)) {
        if (id === 'others' ? k !== keep : k.startsWith(id)) delete state.sessions[k];
      }
      save();
    },

    enrollStart(name) {
      name = String(name || '').trim() || 'Authenticator';
      if (name.length > 32) name = name.slice(0, 32);
      const secret = base32(crypto.randomBytes(20)), id = crypto.randomBytes(6).toString('hex');
      const uri = `otpauth://totp/${ISSUER}:${encodeURIComponent(name)}?secret=${secret}&issuer=${ISSUER}`;   // SHA1 / 6 digits / 30 s are the defaults
      const now = Date.now();
      for (const k of Object.keys(pending)) if (pending[k].expires < now) delete pending[k];
      pending[id] = { name, secret, expires: now + 15 * 60000 };
      return { id, name, secret, uri, qr: qr.svg(uri) };
    },

    enrollConfirm(id, code, hasSession, ua) {
      const p = pending[id];
      if (!p || p.expires < Date.now()) throw new AuthError('This setup expired. Start again.');
      const a = { id, name: p.name, secret: p.secret, added: Date.now(), lastStep: 0 };
      if (!accept(a, code, Date.now())) throw new AuthError("That code didn't match. Check the TV's clock is right, and use a fresh code.");
      delete pending[id];
      state.authenticators.push(a);
      save();
      return hasSession ? null : newSession(id, ua);
    },

    remove(id) {
      state.authenticators = state.authenticators.filter(a => a.id !== id);
      for (const k of Object.keys(state.sessions)) {
        if (state.sessions[k].auth === id || !state.authenticators.length) delete state.sessions[k];
      }
      save();
    },

    reset() { state.authenticators = []; state.sessions = {}; state.keys = []; save(); },

    // Shared with the other CanvasFrame TVs through the site (lib/site.js 'auth:' items, TV-to-TV only).
    syncList() { return state.authenticators.map(a => ({ id: a.id, name: a.name, secret: a.secret, added: a.added })); },
    /** Adds an authenticator another TV shared; false if it's already here or malformed. */
    addSynced(a) {
      if (!a || !/^[a-f0-9]{6,40}$/.test(String(a.id)) || !/^[A-Z2-7]{16,}$/.test(String(a.secret)) || byId(a.id)) return false;
      state.authenticators.push({ id: a.id, name: String(a.name || 'Authenticator').slice(0, 32), secret: a.secret, added: +a.added || Date.now(), lastStep: 0 });
      save();
      return true;
    },

    // API keys: for other CanvasFrame TVs ("Sync to other TVs") and, later, Home Assistant. A valid key counts as
    // signed in. Only a hash is stored; the key itself is shown once when created.
    createKey(name) {
      const key = 'cfk_' + crypto.randomBytes(24).toString('hex');
      const k = { id: crypto.randomBytes(4).toString('hex'), name: String(name || '').trim().slice(0, 40) || 'API key', hash: sha256(key), created: Date.now(), lastUsed: 0 };
      state.keys.push(k);
      save();
      return { id: k.id, name: k.name, key };
    },
    keyValid(key) {
      if (!key || typeof key !== 'string' || !key.startsWith('cfk_')) return false;
      const h = sha256(key), k = state.keys.find(x => x.hash === h);
      if (!k) return false;
      if (Date.now() - k.lastUsed > 10 * 60000) { k.lastUsed = Date.now(); save(); }
      return true;
    },
    revokeKey(id) { state.keys = state.keys.filter(k => k.id !== id); save(); },
    reload: load,

    status(token) {
      const me = api.session(token), on = api.enabled(), authed = !on || !!me;
      const r = { enabled: on, authed };
      if (!authed) return r;
      r.authenticators = state.authenticators.map(a => ({ id: a.id, name: a.name, added: a.added, lastUsed: (a.lastStep || 0) * 30000 }));
      const mine = token ? sha256(token) : '';
      r.sessions = Object.keys(state.sessions).map(k => {
        const s = state.sessions[k], a = byId(s.auth);
        return { id: k.slice(0, 12), current: k === mine, auth: a ? a.name : '?', ua: s.ua, created: s.created, seen: s.seen, via: s.via || '' };
      });
      if (me) { const a = byId(me.auth); r.me = a ? a.name : ''; }
      r.keys = state.keys.map(k => ({ id: k.id, name: k.name, created: k.created, lastUsed: k.lastUsed }));
      return r;
    },
  };
  return api;
}

module.exports = { create, totp, base32, base32Decode };
