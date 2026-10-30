import { randomUUID, randomBytes, scrypt as derive, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';
import { defaultContacts, validateContacts } from './contacts.mjs';
import { checkSecret, digest, equalHash, secret } from './protocol.mjs';

const scrypt = promisify(derive);
const fail = (status, message) => Object.assign(new Error(message), { status });
const text = (value, max, label, min = 1) => {
  if (typeof value !== 'string' || value.trim().length < min || value.trim().length > max) throw fail(400, `${label}格式不正确`);
  return value.trim();
};
function username(value) {
  const name = text(value, 32, '账号', 3).toLowerCase();
  if (!/^[a-z0-9][a-z0-9_.-]*$/.test(name)) throw fail(400, '账号使用 3–32 位英文字母、数字、点、短横线或下划线');
  return name;
}
function password(value) {
  if (typeof value !== 'string' || value.length < 10 || value.length > 128) throw fail(400, '密码需要 10–128 个字符');
  return value;
}

// Password work is asynchronous and bounded: do not block routing or let many
// concurrent KDFs exhaust the small relay container's memory.
export class Accounts {
  constructor(db, { clock = Date.now, administrator, disconnect = () => {}, androidCertificate = '', computerOnline = () => null } = {}) {
    this.db = db; this.clock = clock; this.administrator = administrator; this.disconnect = disconnect;
    this.computerOnline = computerOnline;
    this.androidCertificate = androidCertificate.toLowerCase().replaceAll(':', ''); this.hashing = 0; this.attempts = new Map();
    db.exec(`CREATE TABLE IF NOT EXISTS contact_settings (id INTEGER PRIMARY KEY CHECK(id=1), value TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS accounts (
      id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL, display_name TEXT NOT NULL,
      password_hash TEXT NOT NULL, role TEXT NOT NULL, status TEXT NOT NULL,
      reason TEXT NOT NULL, rejection_reason TEXT NOT NULL DEFAULT '', expires_at INTEGER,
      created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS account_sessions (
      token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL, client TEXT NOT NULL,
      device_hash TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, revoked_at INTEGER);
      CREATE INDEX IF NOT EXISTS account_sessions_user ON account_sessions(user_id);
      CREATE TABLE IF NOT EXISTS account_phones (user_id TEXT PRIMARY KEY, device_hash TEXT NOT NULL, name TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS account_hosts (host_id TEXT PRIMARY KEY, user_id TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS account_legacy_mobile (host_id TEXT PRIMARY KEY, mobile_hash TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS account_events (
      id TEXT PRIMARY KEY, user_id TEXT, actor_id TEXT, type TEXT NOT NULL, severity TEXT NOT NULL,
      summary TEXT NOT NULL, details TEXT NOT NULL, created_at INTEGER NOT NULL, acknowledged_at INTEGER);
      CREATE INDEX IF NOT EXISTS account_events_created ON account_events(created_at);
      CREATE TABLE IF NOT EXISTS account_security_dedup (
      user_id TEXT NOT NULL, device_hash TEXT NOT NULL, signal TEXT NOT NULL, seen_at INTEGER NOT NULL,
      PRIMARY KEY(user_id,device_hash,signal));`);
    // Migrate the previous one-computer constraint without changing ownership.
    const singleHostIndex = db.prepare('PRAGMA index_list(account_hosts)').all().some(index => index.unique && db.prepare(`PRAGMA index_info("${index.name.replaceAll('"','""')}")`).all().some(column => column.name === 'user_id'));
    db.exec('BEGIN IMMEDIATE');
    try {
      if (singleHostIndex) db.exec(`CREATE TABLE account_hosts_multi (host_id TEXT PRIMARY KEY,user_id TEXT NOT NULL);
        INSERT INTO account_hosts_multi SELECT host_id,user_id FROM account_hosts;
        DROP TABLE account_hosts; ALTER TABLE account_hosts_multi RENAME TO account_hosts;`);
      if (!db.prepare('PRAGMA table_info(accounts)').all().some(column => column.name === 'max_hosts')) db.exec('ALTER TABLE accounts ADD COLUMN max_hosts INTEGER NOT NULL DEFAULT 5');
      db.exec('CREATE INDEX IF NOT EXISTS account_hosts_user ON account_hosts(user_id); COMMIT');
    } catch (error) { db.exec('ROLLBACK'); throw error; }
  }
  row(id) { return this.db.prepare('SELECT * FROM accounts WHERE id=?').get(id); }
  state(row) { return !row ? 'revoked' : row.status === 'active' && row.expires_at != null && row.expires_at <= this.clock() ? 'expired' : row.status; }
  public(row) {
    return { id: row.id, username: row.username, displayName: row.display_name, role: row.role, status: row.status, accessState: this.state(row), expiresAt: row.expires_at, reason: row.reason, rejectionReason: row.rejection_reason, createdAt: row.created_at, updatedAt: row.updated_at, maxComputers: row.max_hosts };
  }
  event(userId, actorId, type, severity, summary, details = {}) {
    const id = randomUUID(); this.db.prepare('INSERT INTO account_events VALUES(?,?,?,?,?,?,?,?,NULL)').run(id, userId, actorId, type, severity, summary, JSON.stringify(details), this.clock()); return id;
  }
  async hash(value, encoded) {
    if (this.hashing >= 2) throw fail(429, '登录服务繁忙，请稍后重试');
    this.hashing++;
    try {
      const salt = encoded ? encoded.split('$')[1] : randomBytes(16).toString('hex');
      const key = await scrypt(value, salt, 32, { N: 16384, r: 8, p: 1, maxmem: 24 * 1024 * 1024 });
      const result = `scrypt1$${salt}$${key.toString('hex')}`;
      return encoded ? timingSafeEqual(Buffer.from(result), Buffer.from(encoded)) : result;
    } finally { this.hashing--; }
  }
  loginLimit(req, name) {
    const now = this.clock(), key = `${req.socket.remoteAddress}|${name}`;
    const r = this.attempts.get(key); const current = r && r.until > now ? r : { count: 0, until: now + 60_000 };
    this.attempts.set(key, current); if (++current.count > 8) throw fail(429, '尝试过于频繁，请一分钟后重试');
    if (this.attempts.size > 4096) for (const [key, value] of this.attempts) if (value.until <= now) this.attempts.delete(key);
  }
  session(req, { active = false, admin = false } = {}) {
    const token = req.headers.authorization?.replace(/^Bearer /, '');
    if (!checkSecret(token)) throw fail(401, '请登录账号');
    const session = this.db.prepare('SELECT * FROM account_sessions WHERE token_hash=? AND revoked_at IS NULL AND expires_at>?').get(digest(token), this.clock());
    const user = session && this.row(session.user_id); if (!user) throw fail(401, '登录已失效，请重新登录');
    if ((active || admin) && this.state(user) !== 'active') throw fail(403, '账号尚未批准、已到期或已停用');
    if (admin && user.role !== 'admin') throw fail(403, '需要管理员权限');
    if ((active || admin) && session.client === 'android') {
      const phone = this.db.prepare('SELECT device_hash FROM account_phones WHERE user_id=?').get(user.id);
      if (!phone || !equalHash(phone.device_hash, session.device_hash)) throw fail(403, '本手机尚未绑定，或已被管理员移除，请重新登录');
    }
    return { user, session };
  }
  androidSession(req) {
    const current = this.session(req, { active: true });
    if (current.session.client !== 'android') throw fail(403, '请在手机 App 登录已批准的账号');
    return current;
  }
  verifyClaimSession(tokenHash, userId) {
    const session = this.db.prepare(`SELECT * FROM account_sessions WHERE token_hash=? AND user_id=? AND client='android' AND revoked_at IS NULL AND expires_at>?`).get(tokenHash, userId, this.clock());
    const phone = this.db.prepare('SELECT device_hash FROM account_phones WHERE user_id=?').get(userId);
    if (!session || this.state(this.row(userId)) !== 'active' || !phone || !equalHash(phone.device_hash, session.device_hash)) throw fail(403, '手机登录或使用权限已失效，请重新扫码');
    return session;
  }
  owner(hostId) { return this.db.prepare('SELECT user_id FROM account_hosts WHERE host_id=?').get(hostId)?.user_id ?? null; }
  requireMobileSession(req, hostId, mobileHash) {
    const owner = this.assertHostAllowed(hostId); if (!owner) return;
    if (!req.headers['x-pocket-session']) {
      const legacy = this.db.prepare('SELECT mobile_hash FROM account_legacy_mobile WHERE host_id=?').get(hostId);
      if (legacy && equalHash(legacy.mobile_hash, mobileHash)) return;
      throw fail(401, '请在手机 App 登录已批准的账号');
    }
    const { user, session } = this.session({ headers: { authorization: `Bearer ${req.headers['x-pocket-session']}` } }, { active: true });
    if (user.id !== owner || session.client !== 'android') throw fail(403, '当前账号不能连接这台电脑');
    // A successful authenticated mobile upgrade consumes the narrow legacy bridge.
    this.db.prepare('DELETE FROM account_legacy_mobile WHERE host_id=?').run(hostId);
  }
  assertHostAllowed(hostId) {
    const owner = this.owner(hostId); if (owner && this.state(this.row(owner)) !== 'active') throw fail(403, '账号已到期或已停用，请联系管理员');
    return owner;
  }
  requireHostOwner(req, hostId) {
    const owner = this.assertHostAllowed(hostId); if (!owner) return;
    const { user } = this.session(req, { active: true }); if (user.id !== owner) throw fail(403, '这台电脑不属于当前账号');
  }
  bindHost(userId, hostId) {
    this.assertComputerCapacity(userId, hostId);
    if (this.owner(hostId) && this.owner(hostId) !== userId) throw fail(403, '这台电脑已属于其他账号');
    this.db.prepare('INSERT OR IGNORE INTO account_hosts(host_id,user_id) VALUES(?,?)').run(hostId, userId);
  }
  computers(userId) { return this.db.prepare('SELECT h.id,h.name FROM hosts h JOIN account_hosts a ON a.host_id=h.id WHERE a.user_id=? ORDER BY h.created_at,h.id').all(userId); }
  assertComputerCapacity(userId, hostId) {
    if (this.owner(hostId) === userId) return;
    const user=this.row(userId);
    if (!user || this.state(user)!=='active') throw fail(403, '账号尚未批准、已到期或已停用');
    if (this.db.prepare('SELECT count(*) AS n FROM account_hosts WHERE user_id=?').get(userId).n >= user.max_hosts) throw fail(409, `已达到 ${user.max_hosts} 台电脑上限，请联系管理员调整额度`);
  }
  changed(userId, hostIds) { this.disconnect(userId, hostIds); }
  async create(body, { role = 'user', status = 'pending', actor = null } = {}) {
    const name = username(body.username), pass = password(body.password), displayName = text(body.displayName ?? name, 60, '名称');
    const reason = text(body.reason ?? '管理员创建账号', 500, '申请说明');
    if (this.db.prepare('SELECT id FROM accounts WHERE username=?').get(name)) throw fail(409, '该账号已被注册');
    const passwordHash = await this.hash(pass); const id = randomUUID(), now = this.clock();
    if (role === 'admin' && this.db.prepare("SELECT 1 FROM accounts WHERE role='admin'").get()) throw fail(409, '超级管理员已初始化');
    try {
      this.db.prepare('INSERT INTO accounts(id,username,display_name,password_hash,role,status,reason,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)').run(id, name, displayName, passwordHash, role, status, reason, status === 'active' && role !== 'admin' ? now + this.days(body.days) : null, now, now);
    } catch (e) { if (String(e).includes('UNIQUE')) throw fail(409, '该账号已被注册'); throw e; }
    this.event(id, actor, status === 'pending' ? 'application' : 'account.created', 'info', status === 'pending' ? '收到新的使用申请' : '管理员创建了账号');
    return this.public(this.row(id));
  }
  days(value = 30) { if (!Number.isInteger(value) || value < 1 || value > 3650) throw fail(400, '有效期需要 1–3650 天'); return value * 86400_000; }
  async handle(req, path, body) {
    if (path === '/api/account/contacts') return { contacts: JSON.parse(this.db.prepare('SELECT value FROM contact_settings WHERE id=1').get()?.value ?? JSON.stringify(defaultContacts)) };
    if (path === '/api/admin/bootstrap') {
      this.administrator(req);
      if (this.db.prepare("SELECT 1 FROM accounts WHERE role='admin'").get()) throw fail(409, '超级管理员已初始化');
      return { user: await this.create(body, { role: 'admin', status: 'active' }) };
    }
    if (path === '/api/account/register') {
      this.loginLimit(req, 'register');
      return { user: await this.create(body) };
    }
    if (path === '/api/account/login') {
      const name = username(body.username); this.loginLimit(req, name);
      const pass = password(body.password), user = this.db.prepare('SELECT * FROM accounts WHERE username=?').get(name);
      const dummy = 'scrypt1$00000000000000000000000000000000$' + '0'.repeat(64);
      if (!await this.hash(pass, user?.password_hash ?? dummy) || !user || !this.row(user.id)) throw fail(401, '账号或密码不正确');
      if (!['android', 'desktop'].includes(body.client) || !checkSecret(body.deviceId)) throw fail(400, '设备信息无效');
      const deviceHash = digest(body.deviceId);
      if (body.client === 'android' && this.state(user) === 'active') {
        const phone = this.db.prepare('SELECT device_hash FROM account_phones WHERE user_id=?').get(user.id);
        if (phone && !equalHash(phone.device_hash, deviceHash)) throw fail(409, '账号已绑定另一部手机，请联系管理员移除旧设备');
        this.db.prepare('INSERT OR IGNORE INTO account_phones VALUES(?,?,?)').run(user.id, deviceHash, String(body.deviceName ?? 'Android').slice(0,80));
      }
      const token = secret(), expiresAt = this.clock() + 30 * 86400_000;
      // One active session per client/device. The other client stays connected.
      this.db.prepare('UPDATE account_sessions SET revoked_at=? WHERE user_id=? AND client=? AND device_hash=? AND revoked_at IS NULL').run(this.clock(), user.id, body.client, deviceHash);
      this.db.prepare('INSERT INTO account_sessions VALUES(?,?,?,?,?,?,NULL)').run(digest(token), user.id, body.client, deviceHash, this.clock(), expiresAt);
      return { token, expiresAt, user: this.public(user) };
    }
    if (path === '/api/account/me') {
      const { user, session } = this.session(req);
      if (session.client === 'android' && this.state(user) === 'active') {
        const phone = this.db.prepare('SELECT device_hash FROM account_phones WHERE user_id=?').get(user.id);
        if (!phone) this.db.prepare('INSERT INTO account_phones VALUES(?,?,?)').run(user.id, session.device_hash, 'Android');
        else if (!equalHash(phone.device_hash, session.device_hash)) throw fail(403, '账号已绑定另一部手机，请重新登录或联系管理员');
      }
      const hosts=this.computers(user.id);return { user: this.public(user), host: hosts[0] ?? null, hosts };
    }
    if (path === '/api/account/logout') {
      const { session } = this.session(req); this.db.prepare('UPDATE account_sessions SET revoked_at=? WHERE token_hash=?').run(this.clock(), session.token_hash); return { ok: true };
    }
    if (path === '/api/account/apply') {
      const { user } = this.session(req); if (['disabled','active'].includes(this.state(user))) throw fail(409, '当前账号状态不能重复申请');
      const reason = text(body.reason, 500, '申请说明');
      this.db.prepare("UPDATE accounts SET status='pending',reason=?,rejection_reason='',updated_at=? WHERE id=?").run(reason, this.clock(), user.id);
      this.event(user.id, user.id, 'application', 'info', '用户重新提交了使用申请'); return { user: this.public(this.row(user.id)) };
    }
    if (path === '/api/account/password') {
      const { user, session } = this.session(req); if (!await this.hash(password(body.currentPassword), user.password_hash)) throw fail(401, '原密码不正确');
      const next = await this.hash(password(body.password));
      this.db.prepare('UPDATE accounts SET password_hash=?,updated_at=? WHERE id=?').run(next, this.clock(), user.id);
      this.db.prepare('UPDATE account_sessions SET revoked_at=? WHERE user_id=? AND token_hash<>?').run(this.clock(), user.id, session.token_hash);
      this.event(user.id, user.id, 'password.changed', 'info', '用户修改了登录密码'); return { ok: true };
    }
    if (path === '/api/account/security/report') {
      const { user, session } = this.session(req); if (session.client !== 'android') throw fail(400, '仅接受手机客户端状态报告');
      const supported = new Set(['signature_mismatch', 'debuggable_release', 'debugger_attached']);
      const signals = Array.isArray(body.signals) ? [...new Set(body.signals)].filter(x=>supported.has(x)) : [];
      const certificate = typeof body.certificate === 'string' && /^[a-f0-9]{64}$/.test(body.certificate) ? body.certificate : '';
      if (this.androidCertificate && certificate !== this.androidCertificate && !signals.includes('signature_mismatch')) signals.push('signature_mismatch');
      if (typeof body.version !== 'string' || body.version.length > 40) throw fail(400, '版本信息无效');
      let reported = 0;
      for (const signal of signals) {
        const previous = this.db.prepare('SELECT seen_at FROM account_security_dedup WHERE user_id=? AND device_hash=? AND signal=?').get(user.id,session.device_hash,signal);
        if (previous && previous.seen_at > this.clock() - 86400_000) continue;
        this.db.prepare('INSERT OR REPLACE INTO account_security_dedup VALUES(?,?,?,?)').run(user.id,session.device_hash,signal,this.clock());
        this.event(user.id, user.id, 'security.'+signal, 'warning', ({signature_mismatch:'客户端签名与正式发布证书不一致',debuggable_release:'客户端报告发布包允许调试',debugger_attached:'客户端报告检测到调试器'})[signal], { version: body.version, certificate, evidence: 'client-reported; may be spoofed or unavailable' }); reported++;
      }
      return { ok: true, reported };
    }
    if (!path.startsWith('/api/admin/')) return null;
    const { user: actor } = this.session(req, { admin: true });
    if (path === '/api/admin/contacts') {
      const contacts = { ...validateContacts(body), updatedAt:this.clock() };
      this.db.exec('BEGIN IMMEDIATE');
      try {
        this.db.prepare('INSERT OR REPLACE INTO contact_settings(id,value) VALUES(1,?)').run(JSON.stringify(contacts));
        this.event(null,actor.id,'contacts.updated','info','管理员更新了联系方式');
        this.db.exec('COMMIT');
      } catch (error) { this.db.exec('ROLLBACK'); throw error; }
      return { contacts };
    }
    if (path === '/api/admin/users') return { users: this.db.prepare('SELECT * FROM accounts ORDER BY created_at DESC LIMIT 500').all().map(row=>{const computers=this.computers(row.id).map(computer=>({...computer,online:this.state(row)==='active'?this.computerOnline(computer.id):false}));return{ ...this.public(row), phone: this.db.prepare('SELECT name FROM account_phones WHERE user_id=?').get(row.id)?.name ?? null, computer: computers[0] ?? null, computers };}) };
    if (path === '/api/admin/users/computers') {
      const target=this.row(body.userId),limit=body.limit;
      if(!target)throw fail(404,'账号不存在');
      if(!Number.isInteger(limit)||limit<1||limit>50)throw fail(400,'电脑额度需要 1–50 台');
      if(this.computers(target.id).length>limit)throw fail(409,'请先移除超出额度的电脑');
      this.db.prepare('UPDATE accounts SET max_hosts=?,updated_at=? WHERE id=?').run(limit,this.clock(),target.id);
      this.event(target.id,actor.id,'account.computers','info','管理员调整了电脑额度',{limit});return{user:this.public(this.row(target.id))};
    }
    if (path === '/api/admin/users/create') return { user: await this.create(body, { status: 'active', actor: actor.id }) };
    if (path === '/api/admin/users/delete') {
      if (typeof body.userId !== 'string' || !body.userId) throw fail(400, '请选择要删除的账号');
      const target = this.row(body.userId);
      if (!target) throw fail(404, '账号不存在或已删除，请刷新列表');
      if (target.role === 'admin' || target.id === actor.id) throw fail(403, '不能删除超级管理员或当前登录账号');
      if (body.confirmUsername !== target.username) throw fail(400, '请输入完整登录账号以确认删除');
      const hostIds = this.computers(target.id).map(host => host.id);
      this.db.exec('BEGIN IMMEDIATE');
      try {
        for (const hostId of hostIds) {
          this.db.prepare('UPDATE hosts SET desktop_hash=?,mobile_hash=NULL,mobile_name=NULL,pending_hash=NULL,pending_name=NULL,pair_hash=NULL,pair_expires=NULL WHERE id=?').run(digest(secret()), hostId);
          // Retain a revoked owner so deleted accounts never leave an unmanaged
          // host that could accept old credentials through the legacy path.
          this.db.prepare('UPDATE account_hosts SET user_id=? WHERE host_id=?').run('revoked:' + target.id + ':' + randomUUID(), hostId);
          this.db.prepare('DELETE FROM account_legacy_mobile WHERE host_id=?').run(hostId);
        }
        this.db.prepare('UPDATE hosts SET pending_hash=NULL,pending_name=NULL WHERE id IN (SELECT host_id FROM mobile_claim_hosts WHERE pending_user_id=?)').run(target.id);
        this.db.prepare('UPDATE mobile_claim_hosts SET pending_user_id=NULL,pending_session_hash=NULL WHERE pending_user_id=?').run(target.id);
        this.db.prepare('DELETE FROM account_sessions WHERE user_id=?').run(target.id);
        this.db.prepare('DELETE FROM account_phones WHERE user_id=?').run(target.id);
        this.db.prepare('DELETE FROM account_security_dedup WHERE user_id=?').run(target.id);
        this.db.prepare("UPDATE account_events SET acknowledged_at=? WHERE user_id=? AND severity='warning' AND acknowledged_at IS NULL").run(this.clock(), target.id);
        this.db.prepare('DELETE FROM accounts WHERE id=?').run(target.id);
        this.event(target.id, actor.id, 'account.deleted', 'info', '管理员删除了账号', { username: target.username, displayName: target.display_name, computers: hostIds.length });
        this.db.exec('COMMIT');
      } catch (e) { this.db.exec('ROLLBACK'); throw e; }
      this.changed(target.id, hostIds);
      return { ok: true };
    }
    if (path === '/api/admin/users/decision') {
      const target = this.row(body.userId); if (!target) throw fail(404, '账号不存在');
      if (target.role === 'admin') throw fail(403, '不能在用户审批中修改超级管理员');
      const statuses = { approve: 'active', reject: 'rejected', disable: 'disabled', enable: 'active' }, status = statuses[body.action];
      if (!status) throw fail(400, '审批操作无效');
      const note = body.note == null || body.note === '' ? '' : text(body.note, 500, '说明');
      const expires = status === 'active' ? this.clock()+this.days(body.days) : target.expires_at;
      this.db.prepare('UPDATE accounts SET status=?,expires_at=?,rejection_reason=?,updated_at=? WHERE id=?').run(status, expires, note, this.clock(), target.id);
      this.event(target.id, actor.id, 'account.'+body.action, 'info', ({approve:'管理员批准了申请',reject:'管理员拒绝了申请',disable:'管理员停用了账号',enable:'管理员恢复了账号'})[body.action], { days: status === 'active' ? (body.days ?? 30) : null, note });
      this.changed(target.id); return { user: this.public(this.row(target.id)) };
    }
    if (path === '/api/admin/devices/remove') {
      const target = this.row(body.userId);
      if (!target || (target.role === 'admin' && (target.id !== actor.id || body.device !== 'computer'))) throw fail(403, '不能移除该账号的设备');
      if (!['phone','computer'].includes(body.device)) throw fail(400, '设备类型无效');
      if (body.device === 'computer' && ((body.hostId !== undefined && (typeof body.hostId !== 'string' || !body.hostId)) || (target.role === 'admin' && !body.hostId))) throw fail(400, '请选择要移除的电脑');
      // Invalidate the existing host's mobile credential before allowing another device.
      const computers = this.db.prepare('SELECT host_id FROM account_hosts WHERE user_id=?').all(target.id).filter(computer=>body.device==='phone'||!body.hostId||body.hostId===computer.host_id);
      if(body.device==='computer'&&body.hostId&&!computers.length)throw fail(404,'电脑不属于该账号');
      const hostIds = computers.map(computer => computer.host_id);
      this.changed(target.id, body.device === 'computer' ? hostIds : undefined);
      for(const computer of computers)this.db.prepare('UPDATE hosts SET mobile_hash=NULL,mobile_name=NULL,pending_hash=NULL,pending_name=NULL,pair_hash=NULL WHERE id=?').run(computer.host_id);
      if (body.device === 'phone') {
        this.db.prepare('DELETE FROM account_phones WHERE user_id=?').run(target.id);
        this.db.prepare("UPDATE account_sessions SET revoked_at=? WHERE user_id=? AND client='android'").run(this.clock(),target.id);
      } else for(const computer of computers) {
        // Keep ownership as a tombstone so old desktop credentials never regain legacy access.
        this.db.prepare('UPDATE hosts SET desktop_hash=? WHERE id=?').run(digest(secret()), computer.host_id);
        this.db.prepare('UPDATE account_hosts SET user_id=? WHERE host_id=?').run('revoked:'+target.id+':'+randomUUID(), computer.host_id);
      }
      this.event(target.id,actor.id,'device.removed','info','管理员移除了已绑定设备',{device:body.device,hostIds});return {ok:true};
    }
    if (path === '/api/admin/password/reset') {
      const target=this.row(body.userId);if(!target||target.role==='admin')throw fail(403,'不能重置该账号');
      const encoded=await this.hash(password(body.password));this.db.prepare('UPDATE accounts SET password_hash=?,updated_at=? WHERE id=?').run(encoded,this.clock(),target.id);
      this.db.prepare('UPDATE account_sessions SET revoked_at=? WHERE user_id=?').run(this.clock(),target.id);this.changed(target.id);
      this.event(target.id,actor.id,'password.reset','info','管理员重置了用户密码');return{ok:true};
    }
    if (path === '/api/admin/events') {
      const events = this.db.prepare('SELECT e.*,a.username,a.display_name FROM account_events e LEFT JOIN accounts a ON a.id=e.user_id ORDER BY e.created_at DESC,e.rowid DESC LIMIT 200').all();
      return { events: events.map(e=>({id:e.id,userId:e.user_id,username:e.username,displayName:e.display_name,type:e.type,severity:e.severity,summary:e.summary,details:JSON.parse(e.details),createdAt:e.created_at,acknowledgedAt:e.acknowledged_at})), pending: this.db.prepare("SELECT count(*) AS n FROM accounts WHERE status='pending'").get().n, alerts: this.db.prepare("SELECT count(*) AS n FROM account_events WHERE severity='warning' AND acknowledged_at IS NULL").get().n };
    }
    if (path === '/api/admin/events/ack') {
      if(typeof body.id!=='string')throw fail(400,'记录 ID 无效');this.db.prepare('UPDATE account_events SET acknowledged_at=? WHERE id=? AND acknowledged_at IS NULL').run(this.clock(),body.id);return{ok:true};
    }
    throw fail(404,'管理接口不存在');
  }
}
