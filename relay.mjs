import http from 'node:http';
import https from 'node:https';
import { readFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DatabaseSync } from 'node:sqlite';
import { randomUUID } from 'node:crypto';
import { WebSocketServer, WebSocket } from 'ws';
import { checkHost, checkSecret, digest, equalHash, secret, MAX_FRAME } from './protocol.mjs';
import { Accounts } from './accounts.mjs';

function error(status, message) { return Object.assign(new Error(message), { status }); }
async function jsonBody(req) {
  let body = '';
  for await (const chunk of req) { body += chunk; if (Buffer.byteLength(body) > 8192) throw error(413, 'Request too large'); }
  try { return JSON.parse(body); } catch { throw error(400, 'Invalid JSON'); }
}

export function createRelay({ adminToken, dbPath = ':memory:', cert, key, allowInsecureLocal = false, clock = Date.now, androidCertificate = '', allowPeer = () => true } = {}) {
  if (!checkSecret(adminToken)) throw new Error('A 32-byte RELAY_ADMIN_TOKEN is required');
  if (!cert && !allowInsecureLocal) throw new Error('TLS certificate required');
  if (dbPath !== ':memory:') mkdirSync(dirname(resolve(dbPath)), { recursive: true, mode: 0o700 });
  const db = new DatabaseSync(dbPath);
  db.exec(`PRAGMA journal_mode=WAL;
    CREATE TABLE IF NOT EXISTS hosts (
      id TEXT PRIMARY KEY, name TEXT NOT NULL, desktop_hash TEXT NOT NULL,
      pair_hash TEXT, pair_expires INTEGER, mobile_hash TEXT, mobile_name TEXT,
      pending_hash TEXT, pending_name TEXT, created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS invitations (
      id TEXT PRIMARY KEY, code_hash TEXT UNIQUE NOT NULL, label TEXT NOT NULL,
      created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
      revoked_at INTEGER, redeemed_at INTEGER, host_id TEXT UNIQUE
    );`);
  db.exec(`CREATE TABLE IF NOT EXISTS mobile_claim_hosts (
    host_id TEXT PRIMARY KEY, pending_user_id TEXT, pending_session_hash TEXT,
    created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL);`);
  const routes = new Map(), limits = new Map(), preparations = new Map();
  const claimHost = id => db.prepare('SELECT * FROM mobile_claim_hosts WHERE host_id=?').get(id);
  function claimPhone(req, hostId) {
    if (!claimHost(hostId)) return accounts.requireHostOwner(req, hostId);
    const current = accounts.androidSession(req);
    accounts.assertComputerCapacity(current.user.id, hostId);
    return current;
  }
  const getHost = id => db.prepare('SELECT * FROM hosts WHERE id=?').get(id);
  const accounts = new Accounts(db, { clock, administrator: admin, androidCertificate, disconnect(userId, hostIds) {
    for (const [hostId, peers] of routes) if (hostIds ? hostIds.includes(hostId) : accounts.owner(hostId) === userId) for (const ws of Object.values(peers)) ws.close(4003, 'Account authorization changed');
  } });
  function admin(req) {
    if (!equalHash(digest(req.headers.authorization ?? ''), digest(`Bearer ${adminToken}`))) throw error(401, 'Unauthorized');
  }
  function validateHost(body) {
    if (!checkHost(body.hostId) || !checkSecret(body.desktopToken) || !checkSecret(body.pairCode) || typeof body.name !== 'string' || !body.name.trim() || body.name.length > 80) throw error(400, 'Invalid host');
  }
  function insertHost(body) {
    db.prepare('INSERT INTO hosts(id,name,desktop_hash,pair_hash,pair_expires,created_at) VALUES(?,?,?,?,?,?)').run(body.hostId, body.name.trim(), digest(body.desktopToken), digest(body.pairCode), clock() + 10 * 60_000, clock());
  }
  function authenticate(req, role, hostId) {
    if (!checkHost(hostId)) throw error(401, 'Unauthorized');
    const token = req.headers.authorization?.replace(/^Bearer /, '') ?? '';
    const host = getHost(hostId);
    const hash = role === 'desktop' ? host?.desktop_hash : host?.mobile_hash;
    if (!checkSecret(token) || !hash || !equalHash(digest(token), hash)) throw error(401, 'Unauthorized');
    accounts.assertHostAllowed(hostId);
    if (role === 'mobile') { if (claimHost(hostId)) throw error(403, '请先在手机登录并完成配对'); accounts.requireMobileSession(req, hostId, hash); }
    return host;
  }
  function relayState(id) { return routes.get(id) ?? {}; }
  function send(ws, message) { if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(message)); }
  function status(id) {
    const r = relayState(id);
    send(r.desktop, { type: 'status', peerOnline: r.mobile?.readyState === WebSocket.OPEN });
    send(r.mobile, { type: 'status', peerOnline: r.desktop?.readyState === WebSocket.OPEN });
  }
  function pending(id) {
    const host = getHost(id);
    if (host?.pending_hash) send(relayState(id).desktop, { type: 'pair/request', name: host.pending_name, fingerprint: host.pending_hash.slice(0, 12) });
  }
  function rateLimit(req) {
    const ip = req.socket.remoteAddress;
    const now = Date.now(); let entry = limits.get(ip);
    if (!entry || entry.until < now) entry = { until: now + 60_000, count: 0 };
    if (++entry.count > 120) throw error(429, 'Too many requests');
    limits.set(ip, entry);
    if (limits.size > 4096) for (const [key, value] of limits) if (value.until < now) limits.delete(key);
  }
  async function handler(req, res) {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Strict-Transport-Security', 'max-age=31536000');
    try {
      if (!allowPeer(req.socket.remoteAddress)) throw error(403, 'Only local network peers are allowed');
      rateLimit(req);
      const path = new URL(req.url, 'http://relay').pathname;
      if (req.method === 'GET' && path === '/healthz') return res.end(JSON.stringify({ ok: true, protocol: 1, service: 'codex-pocket', enrollment: 'invite-v1', accounts: 'approval-v1', desktopEnrollment: 'mobile-claim-v1', computers:'multi-v1', deviceRemoval:'selected-v1', accountDeletion:'confirmed-v1' }));
      if (req.method !== 'POST') throw error(404, 'Not found');
      const body = await jsonBody(req);
      if (!body || typeof body !== 'object' || Array.isArray(body)) throw error(400, 'Invalid JSON object');
      if (path.startsWith('/api/account/') || path.startsWith('/api/admin/')) {
        const result = await accounts.handle(req, path, body);
        if (result !== null) return res.end(JSON.stringify(result));
      }
      if (path === '/api/desktop/prepare') {
        validateHost(body);
        const existing = getHost(body.hostId);
        if (existing) {
          authenticate(req, 'desktop', body.hostId);
          db.prepare('UPDATE mobile_claim_hosts SET last_seen=? WHERE host_id=?').run(clock(), body.hostId);
          return res.end(JSON.stringify({ ok: true, recovered: true, mobileClaim: !!claimHost(body.hostId) }));
        }
        const ip = req.socket.remoteAddress, now = clock();
        const budget = preparations.get(ip);
        const entry = budget && budget.until > now ? budget : { count: 0, until: now + 60_000 };
        preparations.set(ip, entry);
        if (++entry.count > 10) throw error(429, '连接请求过多，请一分钟后重试');
        for (const [key, value] of preparations) if (value.until <= now) preparations.delete(key);
        // Only unused anonymous preparations expire. Existing managed or paired
        // hosts are never collected or converted back into anonymous computers.
        const stale = db.prepare('SELECT c.host_id FROM mobile_claim_hosts c JOIN hosts h ON h.id=c.host_id WHERE c.last_seen<? AND c.pending_user_id IS NULL AND h.mobile_hash IS NULL').all(now - 7*86400_000);
        for (const row of stale) if (!routes.has(row.host_id)) {
          db.prepare('DELETE FROM mobile_claim_hosts WHERE host_id=?').run(row.host_id);
          db.prepare('DELETE FROM hosts WHERE id=? AND mobile_hash IS NULL AND NOT EXISTS (SELECT 1 FROM account_hosts WHERE host_id=?)').run(row.host_id, row.host_id);
        }
        if (db.prepare('SELECT count(*) n FROM mobile_claim_hosts').get().n >= 2000) throw error(503, '连接服务繁忙，请稍后再试');
        db.exec('BEGIN IMMEDIATE');
        try { insertHost(body); db.prepare('INSERT INTO mobile_claim_hosts VALUES(?,NULL,NULL,?,?)').run(body.hostId, now, now); db.exec('COMMIT'); }
        catch(e) { db.exec('ROLLBACK'); throw e; }
        return res.end(JSON.stringify({ ok: true, mobileClaim: true }));
      }
      if (path === '/api/account/hosts/enroll') {
        const { user, session } = accounts.session(req, { active: true });
        if (session.client !== 'desktop') throw error(403, '请在电脑连接器登录后绑定电脑');
        validateHost(body); db.exec('BEGIN IMMEDIATE');
        try {
          const existing = getHost(body.hostId);
          if (existing && (!equalHash(existing.desktop_hash, digest(body.desktopToken)) || accounts.owner(body.hostId) !== user.id)) throw error(409, '电脑身份已被使用');
          if (!existing) insertHost(body);
          accounts.bindHost(user.id, body.hostId); db.exec('COMMIT');
          accounts.event(user.id, user.id, 'computer.bound', 'info', '用户绑定了电脑', { name: body.name.trim() });
        } catch(e) { db.exec('ROLLBACK'); throw e; }
        return res.end(JSON.stringify({ ok: true, ownerId: user.id }));
      }
      if (path === '/api/account/hosts/claim') {
        const { user } = accounts.session(req, { admin: true });
        const host = checkHost(body.hostId) && getHost(body.hostId);
        if (!host || !checkSecret(body.desktopToken) || !equalHash(host.desktop_hash, digest(body.desktopToken))) throw error(403, '现有电脑凭据不匹配');
        const wasUnmanaged = !accounts.owner(body.hostId);
        accounts.bindHost(user.id, body.hostId);
        if (wasUnmanaged && host.mobile_hash) db.prepare('INSERT OR IGNORE INTO account_legacy_mobile VALUES(?,?)').run(body.hostId,host.mobile_hash);
        return res.end(JSON.stringify({ ok: true }));
      }
      if (path === '/api/invites') {
        admin(req);
        const hours = body.hours ?? 24;
        if (!Number.isInteger(hours) || hours < 1 || hours > 168 || typeof (body.label ?? '') !== 'string' || (body.label ?? '').length > 80) throw error(400, 'Invalid invitation');
        const code = secret(), id = randomUUID(), expiresAt = clock() + hours * 3600_000;
        db.prepare('INSERT INTO invitations(id,code_hash,label,created_at,expires_at) VALUES(?,?,?,?,?)').run(id, digest(code), body.label ?? '', clock(), expiresAt);
        return res.end(JSON.stringify({ id, code, expiresAt }));
      }
      if (path === '/api/invites/list') {
        admin(req);
        return res.end(JSON.stringify({ invitations: db.prepare('SELECT id,label,created_at,expires_at,revoked_at,redeemed_at,host_id FROM invitations ORDER BY created_at DESC LIMIT 500').all() }));
      }
      if (path === '/api/invites/revoke') {
        admin(req);
        if (typeof body.id !== 'string') throw error(400, 'Invalid invitation ID');
        const changed = db.prepare('UPDATE invitations SET revoked_at=? WHERE id=? AND revoked_at IS NULL AND redeemed_at IS NULL').run(clock(), body.id).changes;
        return res.end(JSON.stringify({ revoked: changed === 1 }));
      }
      if (path === '/api/hosts/enroll') {
        if (db.prepare("SELECT 1 FROM accounts WHERE role='admin'").get()) throw error(403, '请在电脑连接器登录已批准的账号后注册电脑');
        validateHost(body);
        if (!checkSecret(body.inviteCode)) throw error(401, '邀请码无效、已过期或已被使用');
        db.exec('BEGIN IMMEDIATE');
        try {
          const invitation = db.prepare('SELECT * FROM invitations WHERE code_hash=?').get(digest(body.inviteCode));
          const existing = getHost(body.hostId);
          // A lost HTTP response can be retried only by the same registered computer.
          if (invitation?.host_id === body.hostId && existing && equalHash(existing.desktop_hash, digest(body.desktopToken))) {
            db.exec('COMMIT'); return res.end(JSON.stringify({ ok: true, recovered: true }));
          }
          if (!invitation || invitation.revoked_at != null || invitation.redeemed_at != null || invitation.expires_at <= clock()) throw error(401, '邀请码无效、已过期或已被使用');
          if (existing) throw error(409, 'Host already registered');
          insertHost(body);
          db.prepare('UPDATE invitations SET redeemed_at=?,host_id=? WHERE id=?').run(clock(), body.hostId, invitation.id);
          db.exec('COMMIT');
        } catch (e) { db.exec('ROLLBACK'); throw e; }
        return res.end(JSON.stringify({ ok: true }));
      }
      if (path === '/api/hosts') {
        admin(req); validateHost(body);
        if (getHost(body.hostId)) throw error(409, 'Host already registered');
        insertHost(body);
        return res.end(JSON.stringify({ ok: true }));
      }
      if (path === '/api/pair') {
        const host = checkHost(body.hostId) ? getHost(body.hostId) : null;
        const claiming = claimPhone(req, body.hostId);
        if (!checkSecret(body.pairCode) || !checkSecret(body.mobileToken) || !host?.pair_hash || !equalHash(host.pair_hash, digest(body.pairCode)) || host.pair_expires < clock()) throw error(401, '配对码已失效，请在电脑端重新生成');
        const mobileHash = digest(body.mobileToken);
        if (host.pending_hash && !equalHash(host.pending_hash, mobileHash)) throw error(409, '已有设备等待确认');
        db.prepare('UPDATE hosts SET pending_hash=?,pending_name=? WHERE id=?').run(mobileHash, String(body.name ?? 'Android').slice(0, 80), body.hostId);
        if (claiming) db.prepare('UPDATE mobile_claim_hosts SET pending_user_id=?,pending_session_hash=? WHERE host_id=?').run(claiming.user.id, claiming.session.token_hash, body.hostId);
        pending(body.hostId);
        return res.end(JSON.stringify({ ok: true, state: 'pending' }));
      }
      if (path === '/api/hosts/status') {
        const host = authenticate(req, 'desktop', body.hostId);
        return res.end(JSON.stringify({ registered: true, name: host.name, mobilePaired: !!host.mobile_hash, pairExpiresAt: host.pair_expires, accountManaged: !!accounts.owner(host.id), mobileClaim: !!claimHost(host.id) }));
      }
      if (path === '/api/pair/status') {
        const host = checkHost(body.hostId) ? getHost(body.hostId) : null;
        const claiming = claimPhone(req, body.hostId);
        if (claiming && claimHost(body.hostId).pending_user_id !== claiming.user.id) throw error(403, '配对请求不属于当前账号');
        if (!host || !checkSecret(body.mobileToken)) throw error(401, 'Unauthorized');
        const hash = digest(body.mobileToken);
        if (host.mobile_hash && equalHash(host.mobile_hash, hash)) return res.end(JSON.stringify({ state: 'approved', name: host.name }));
        if (host.pending_hash && equalHash(host.pending_hash, hash)) return res.end(JSON.stringify({ state: 'pending' }));
        throw error(401, '配对尚未完成或已被撤销');
      }
      if (path === '/api/pair/cancel') {
        const host=checkHost(body.hostId)?getHost(body.hostId):null;
        claimPhone(req,body.hostId);
        if(!host||!checkSecret(body.mobileToken)||!host.pending_hash||!equalHash(host.pending_hash,digest(body.mobileToken)))throw error(401,'配对请求已结束');
        const fingerprint=host.pending_hash.slice(0,12);
        db.prepare('UPDATE hosts SET pending_hash=NULL,pending_name=NULL WHERE id=?').run(body.hostId);
        db.prepare('UPDATE mobile_claim_hosts SET pending_user_id=NULL,pending_session_hash=NULL WHERE host_id=?').run(body.hostId);
        send(relayState(body.hostId).desktop,{type:'pair/cancelled',fingerprint});
        return res.end(JSON.stringify({cancelled:true}));
      }
      if (path === '/api/pair/decision') {
        const host = authenticate(req, 'desktop', body.hostId);
        if (!host.pending_hash) throw error(409, 'No pending device');
        if (body.fingerprint !== host.pending_hash.slice(0, 12)) throw error(409, 'Pairing request changed');
        db.exec('BEGIN IMMEDIATE');
        try {
          const claim = claimHost(body.hostId);
          if (body.accept === true) {
            if (claim) {
              accounts.verifyClaimSession(claim.pending_session_hash, claim.pending_user_id);
              accounts.bindHost(claim.pending_user_id, body.hostId);
            }
            db.prepare('UPDATE hosts SET mobile_hash=pending_hash,mobile_name=pending_name,pending_hash=NULL,pending_name=NULL,pair_hash=NULL WHERE id=?').run(body.hostId);
            db.prepare('DELETE FROM mobile_claim_hosts WHERE host_id=?').run(body.hostId);
          } else {
            db.prepare('UPDATE hosts SET pending_hash=NULL,pending_name=NULL WHERE id=?').run(body.hostId);
            db.prepare('UPDATE mobile_claim_hosts SET pending_user_id=NULL,pending_session_hash=NULL WHERE host_id=?').run(body.hostId);
          }
          db.exec('COMMIT');
        } catch(e) { db.exec('ROLLBACK'); throw e; }
        if (body.accept === true) relayState(body.hostId).mobile?.close(4001, 'Device changed');
        return res.end(JSON.stringify({ ok: true }));
      }
      if (path === '/api/pair/reset') {
        authenticate(req, 'desktop', body.hostId);
        if (!checkSecret(body.pairCode)) throw error(400, 'Invalid pair code');
        db.prepare('UPDATE hosts SET pair_hash=?,pair_expires=?,mobile_hash=NULL,mobile_name=NULL,pending_hash=NULL,pending_name=NULL WHERE id=?').run(digest(body.pairCode), clock() + 10 * 60_000, body.hostId);
        db.prepare('UPDATE mobile_claim_hosts SET pending_user_id=NULL,pending_session_hash=NULL WHERE host_id=?').run(body.hostId);
        relayState(body.hostId).mobile?.close(4001, 'Device revoked');
        return res.end(JSON.stringify({ ok: true }));
      }
      throw error(404, 'Not found');
    } catch (e) { res.statusCode = e.status ?? 500; res.end(JSON.stringify({ error: e.status ? e.message : 'Internal server error' })); }
  }
  const server = cert ? https.createServer({ cert, key, minVersion: 'TLSv1.2' }, handler) : http.createServer(handler);
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_FRAME, perMessageDeflate: false });
  server.on('upgrade', (req, socket, head) => {
    try {
      if (!allowPeer(req.socket.remoteAddress)) throw error(403, 'Only local network peers are allowed');
      rateLimit(req);
      if (req.url !== '/relay' || req.headers.origin) throw error(403, 'Forbidden');
      const id = req.headers['x-pocket-host'], role = req.headers['x-pocket-role'];
      if (!['desktop', 'mobile'].includes(role)) throw error(401, 'Unauthorized');
      authenticate(req, role, id);
      wss.handleUpgrade(req, socket, head, ws => {
        const route = routes.get(id) ?? {};
        route[role]?.close(4000, 'Reconnected'); route[role] = ws; routes.set(id, route);
        ws.alive = true; ws.on('pong', () => ws.alive = true);
        ws.on('error', () => {});
        ws.on('message', (data, isBinary) => {
          try { authenticate(req, role, id); } catch { ws.close(4003, 'Account no longer authorized'); return; }
          if (isBinary) return ws.close(1003, 'Text required');
          let frame;
          try { frame = JSON.parse(data.toString()); } catch { return ws.close(1007, 'Invalid JSON'); }
          if (frame?.v !== 1 || typeof frame.data !== 'string' || frame.data.length > MAX_FRAME) return ws.close(1008, 'Encrypted frames required');
          const peer = relayState(id)[role === 'desktop' ? 'mobile' : 'desktop'];
          if (peer?.readyState === WebSocket.OPEN) {
            if (peer.bufferedAmount > MAX_FRAME * 4) return peer.close(1013, 'Slow connection');
            peer.send(data.toString());
          }
        });
        ws.accountRequest = req;
        ws.on('close', () => { if (relayState(id)[role] === ws) { delete route[role]; status(id); if (!route.desktop && !route.mobile) routes.delete(id); } });
        status(id); if (role === 'desktop') pending(id);
      });
    } catch (e) { socket.end(`HTTP/1.1 ${e.status ?? 401} Unauthorized\r\nConnection: close\r\n\r\n`); }
  });
  const heartbeat = setInterval(() => { for (const [id, peers] of routes) {
    try { accounts.assertHostAllowed(id); } catch { for (const ws of Object.values(peers)) ws.close(4003, 'Account no longer authorized'); continue; }
    for (const [role, ws] of Object.entries(peers)) {
      try { authenticate(ws.accountRequest, role, id); } catch { ws.close(4003, 'Account no longer authorized'); continue; }
      if (!ws.alive) ws.terminate(); else { ws.alive = false; ws.ping(); }
    }
  } }, 25_000);
  heartbeat.unref();
  async function close() { clearInterval(heartbeat); for (const ws of wss.clients) ws.terminate(); await new Promise(resolve => wss.close(resolve)); await new Promise(resolve => server.close(resolve)); db.close(); }
  return { server, close };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const relay = createRelay({ adminToken: process.env.RELAY_ADMIN_TOKEN, dbPath: process.env.RELAY_DB ?? '/data/relay.sqlite', cert: readFileSync(process.env.TLS_CERT ?? '/certs/cert.pem'), key: readFileSync(process.env.TLS_KEY ?? '/certs/key.pem'), androidCertificate: process.env.RELAY_ANDROID_CERT_SHA256 ?? '' });
  const port = Number(process.env.PORT ?? 8443);
  relay.server.listen(port, '0.0.0.0', () => console.log(`Codex Pocket relay listening on TLS port ${port}`));
  for (const signal of ['SIGTERM','SIGINT']) process.on(signal, () => relay.close().then(() => process.exit(0)));
}
