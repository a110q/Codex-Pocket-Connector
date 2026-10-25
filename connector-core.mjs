import { existsSync, readFileSync, mkdirSync, writeFileSync, renameSync, chmodSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { homedir, hostname } from 'node:os';
import { X509Certificate, randomUUID } from 'node:crypto';
import { checkSecret, secret, digest } from './protocol.mjs';
import { relayRequest } from './companion.mjs';
import { connectorStateDirectory, windowsConnectionCommand } from './platform.mjs';

export function connectorPaths(stateDir = process.env.POCKET_STATE_DIR ?? connectorStateDirectory()) {
  const state = resolve(stateDir);
  return { state, config: resolve(state, 'companion.json'), enrollment: resolve(state, 'enrollment.json'), setup: resolve(state, 'connector.json'), account: resolve(state, 'account.json') };
}
export function readJson(path) { return JSON.parse(readFileSync(path, 'utf8')); }
export function writePrivate(path, value, { exclusive = false } = {}) {
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  if (exclusive) { writeFileSync(path, JSON.stringify(value, null, 2), { mode: 0o600, flag: 'wx' }); return; }
  const temporary = `${path}.${randomUUID()}.tmp`;
  writeFileSync(temporary, JSON.stringify(value, null, 2), { mode: 0o600, flag: 'wx' });
  renameSync(temporary, path); chmodSync(path, 0o600);
}
export function validateDefaults(defaults) {
  const url = new URL(defaults.relay);
  if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error('中继地址无效，请填写不带路径的 HTTPS 地址');
  if(typeof defaults.caCert!=='string'||/PRIVATE KEY/.test(defaults.caCert))throw new Error('请使用服务器的公开证书，不能导入私钥');
  let certificate;try{certificate=new X509Certificate(defaults.caCert)}catch{throw new Error('公开证书格式无效，请导入 PEM 格式的 cert.pem')}
  return { relay: url.origin, caCert: defaults.caCert, pin: certificate.fingerprint256.replaceAll(':', '').toLowerCase() };
}
export class Enrollment {
  constructor({ paths = connectorPaths(), defaults, request = relayRequest }) {
    this.paths = paths; this.defaults = validateDefaults(defaults); this.request = request; this.busy = false;
  }
  async prepare({ name = hostname() } = {}) {
    if (this.busy) throw new Error('正在准备连接，请稍候');
    this.busy = true;
    try {
      let previous = null;
      if (existsSync(this.paths.config)) {
        const config=readJson(this.paths.config);
        try {
          if(config.mobileClaim===true) await this.request(config,'/api/desktop/prepare',Object.fromEntries(['hostId','desktopToken','pairCode','name'].map(k=>[k,config[k]])));
          else await this.request(config,'/api/hosts/status',{hostId:config.hostId});
          return {registered:true};
        } catch (error) {
          // A removed computer's credential is permanently revoked. Only an
          // explicit reconnect starts a fresh, unowned identity for phone approval.
          // Permission denials and uncertain network failures retain the identity.
          if(error.statusCode!==401) throw error;
          previous=config;
        }
      }
      let pending = existsSync(this.paths.enrollment) ? readJson(this.paths.enrollment) : null;
      if (!pending?.config || (previous && pending.replacesHostId!==previous.hostId)) pending = {
        ...(previous ? {replacesHostId:previous.hostId} : {}),
        config: { ...this.defaults, name: String(previous?.name ?? name).trim().slice(0,80) || hostname(), hostId: secret().slice(0,32), desktopToken: secret(), pairCode: secret(), key: secret(), managerToken: secret(), pairExpires: Date.now()+10*60_000, mobileClaim: true }
      };
      if (pending.config.relay !== this.defaults.relay || pending.config.pin !== this.defaults.pin) throw new Error('连接配置与当前版本不匹配，请联系管理员');
      writePrivate(this.paths.enrollment, pending);
      const config = pending.config;
      const registered = await this.request(config, '/api/desktop/prepare', Object.fromEntries(['hostId','desktopToken','pairCode','name'].map(k=>[k,config[k]])));
      const verified = await this.request(config, '/api/hosts/status', { hostId: config.hostId });
      if (!verified.registered) throw new Error('连接准备尚未确认，请重试');
      config.mobileClaim = verified.mobileClaim === true || registered.mobileClaim === true;
      // Keep the revoked local file until the replacement is verified. The
      // durable pending identity is reused if a response is lost or the app quits.
      writePrivate(this.paths.config, config, { exclusive: !previous });
      writePrivate(this.paths.enrollment, { completed: true, hostId: config.hostId });
      return { registered: true, ...(previous ? {rebindRequired:true} : {}) };
    } finally { this.busy = false; }
  }
  async register({ inviteCode, name = hostname() }) {
    if (this.busy) throw new Error('电脑正在注册，请稍候');
    if (existsSync(this.paths.config)) throw new Error('这台电脑已经注册，无需再次使用邀请码');
    if (!checkSecret(inviteCode)) throw new Error('请输入完整的一次性邀请码');
    if (typeof name !== 'string' || !name.trim() || name.trim().length > 80) throw new Error('电脑名称需要 1–80 个字符');
    this.busy = true;
    try {
      let pending;
      if (existsSync(this.paths.enrollment)) {
        pending = readJson(this.paths.enrollment);
        if (pending.completed || (!pending.rejected && pending.inviteHash !== digest(inviteCode)) || pending.config.relay !== this.defaults.relay || pending.config.pin !== this.defaults.pin) throw new Error('存在上次未完成的注册，请使用原邀请码重试；不会覆盖原电脑身份');
        if (pending.rejected) { pending.inviteHash = digest(inviteCode); pending.config.name = name.trim(); pending.rejected = false; writePrivate(this.paths.enrollment, pending); }
      } else {
        pending = { inviteHash: digest(inviteCode), config: { ...this.defaults, name: name.trim(), hostId: secret().slice(0, 32), desktopToken: secret(), pairCode: secret(), key: secret(), managerToken: secret(), pairExpires: Date.now() + 10 * 60_000 } };
        writePrivate(this.paths.enrollment, pending, { exclusive: true });
      }
      try {
        await this.request(pending.config, '/api/hosts/enroll', { inviteCode, hostId: pending.config.hostId, name: pending.config.name, desktopToken: pending.config.desktopToken, pairCode: pending.config.pairCode }, null);
      } catch (e) {
        // A definitive rejection differs from a lost response. Allow correcting an
        // invalid code, while retaining the same identity after uncertain delivery.
        if ([400, 401].includes(e.statusCode)) { pending.rejected = true; writePrivate(this.paths.enrollment, pending); }
        throw e;
      }
      // Verify ownership with the new desktop credential before declaring success.
      const verified = await this.request(pending.config, '/api/hosts/status', { hostId: pending.config.hostId });
      if (verified.registered !== true) throw new Error('尚未确认电脑注册状态，请用原邀请码重试');
      writePrivate(this.paths.config, pending.config, { exclusive: true });
      writePrivate(this.paths.enrollment, { completed: true, hostId: pending.config.hostId });
      return { registered: true, name: pending.config.name };
    } finally { this.busy = false; }
  }
  async registerAccount({ name = hostname(), token, user }) {
    if (this.busy) throw new Error('电脑正在注册，请稍候');
    if (!checkSecret(token) || user?.accessState !== 'active') throw new Error('请先登录已批准的账号');
    if (typeof name !== 'string' || !name.trim() || name.trim().length > 80) throw new Error('电脑名称需要 1–80 个字符');
    this.busy = true;
    try {
      if (existsSync(this.paths.config)) {
        const config = readJson(this.paths.config);
        const status = await this.request(config,'/api/hosts/status',{hostId:config.hostId});
        if (!status.accountManaged && user.role === 'admin') await this.request(config,'/api/account/hosts/claim',{hostId:config.hostId,desktopToken:config.desktopToken},token);
        const current=await this.request(this.defaults,'/api/account/me',{},token);
        if(current.host?.id!==config.hostId)throw new Error('当前账号与已注册的电脑不匹配，请使用原账号登录');
        return {registered:true,name:config.name};
      }
      let pending=existsSync(this.paths.enrollment)?readJson(this.paths.enrollment):null;
      if(pending?.config&&(pending.config.relay!==this.defaults.relay||pending.config.pin!==this.defaults.pin))throw new Error('上次注册的服务器不同，请联系管理员处理');
      if(pending?.userId&&pending.userId!==user.id)throw new Error('存在另一个账号未完成的电脑注册，请使用原账号重试');
      if(!pending?.config)pending={config:{...this.defaults,name:name.trim(),hostId:secret().slice(0,32),desktopToken:secret(),pairCode:secret(),key:secret(),managerToken:secret(),pairExpires:Date.now()+10*60_000}};
      pending.userId=user.id;writePrivate(this.paths.enrollment,pending);
      await this.request(pending.config,'/api/account/hosts/enroll',{...Object.fromEntries(['hostId','desktopToken','pairCode','name'].map(k=>[k,pending.config[k]]))},token);
      const verified=await this.request(pending.config,'/api/hosts/status',{hostId:pending.config.hostId});if(!verified.registered||!verified.accountManaged)throw new Error('电脑身份尚未确认，请重试');
      writePrivate(this.paths.config,pending.config,{exclusive:true});writePrivate(this.paths.enrollment,{completed:true,hostId:pending.config.hostId});
      return{registered:true,name:pending.config.name};
    }finally{this.busy=false}
  }
}

export class ConnectorAccount {
  constructor({paths=connectorPaths(),defaults,request=relayRequest}){this.paths=paths;this.defaults=validateDefaults(defaults);this.request=request}
  async login({username,password}){
    const old=existsSync(this.paths.account)?readJson(this.paths.account):{deviceId:secret()};
    writePrivate(this.paths.account,{deviceId:old.deviceId});
    const current=await this.request(this.defaults,'/api/account/login',{username,password,client:'desktop',deviceId:old.deviceId},null);
    writePrivate(this.paths.account,{deviceId:old.deviceId,...current});return{user:current.user};
  }
  async state(){
    if(!existsSync(this.paths.account)||!readJson(this.paths.account).token)return{loggedIn:false};
    const current=readJson(this.paths.account);
    try{const result=await this.request(this.defaults,'/api/account/me',{},current.token);return{loggedIn:true,...result}}catch(e){if(e.statusCode===401)return{loggedIn:false,error:e.message};return{loggedIn:true,user:current.user,error:e.message,verified:false}}
  }
  async logout(){
    if(!existsSync(this.paths.account))return{ok:true};const current=readJson(this.paths.account);
    if(current.token)try{await this.request(this.defaults,'/api/account/logout',{},current.token)}catch(e){if(e.statusCode!==401)throw e}
    writePrivate(this.paths.account,{deviceId:current.deviceId});return{ok:true};
  }
}
export const shellQuote = value => `'${String(value).replaceAll("'", "'\\''")}'`;
export function connectionCommand(root, paths, contextThreadId = '', platform = process.platform) {
  if (contextThreadId && !/^[a-f0-9-]{36}$/i.test(contextThreadId)) throw new Error('聊天 ID 格式无效，请从 Codex 的聊天链接复制 ID');
  if(platform==='win32')return windowsConnectionCommand(root,paths.state,contextThreadId);
  return `env POCKET_STATE_DIR=${shellQuote(paths.state)}${contextThreadId ? ` CODEX_DESKTOP_CONTEXT_THREAD_ID=${shellQuote(contextThreadId)}` : ''} ${shellQuote(resolve(root, 'scripts/connect-installed.command'))}`;
}

export async function companionStatus(config, port, fetcher = fetch) {
  const response = await fetcher(`http://127.0.0.1:${port}/api/status`, { headers: { Authorization: `Bearer ${config.managerToken}` }, signal: AbortSignal.timeout(1500) });
  if (!response.ok) throw new Error('连接端口被其他服务占用，未连接当前电脑');
  return response.json();
}
export function missingCapabilities(current) {
  return ['threads.list', 'projects.list', 'thread.read', 'message.send', 'thread.create', 'models.list'].filter(name => current?.capabilities?.[name] !== true);
}

export async function diagnose({ paths, defaults, managerPort = 43787, request = relayRequest, status = companionStatus }) {
  const checks = [{ id: 'runtime', state: Number(process.versions.node.split('.')[0]) >= 22 ? 'ok' : 'error', label: '运行环境', message: `Node ${process.versions.node} · ${process.platform} ${process.arch}` }];
  if (!existsSync(paths.config)) {
    checks.push({ id: 'registration', state: 'warn', label: '电脑注册', message: '尚未连接，请选择连接方式后继续。' });
    return { checkedAt: new Date().toISOString(), checks };
  }
  const config = readJson(paths.config);
  try {
    const result = await request(config, '/api/hosts/status', { hostId: config.hostId });
    if (!result.registered) throw new Error('注册状态未确认');
    checks.push({ id: 'relay', state: 'ok', label: '中继与电脑身份', message: 'TLS 连接和电脑身份验证通过。' });
    checks.push({ id: 'phone', state: result.mobilePaired ? 'ok' : 'warn', label: '手机配对', message: result.mobilePaired ? '已有已授权的手机。' : '尚未配对手机，启动连接后扫码并在电脑上确认。' });
  } catch (e) { checks.push({ id: 'relay', state: 'error', label: '中继与电脑身份', message: `验证失败：${e.message}` }); }
  try {
    const current = await status(config, managerPort);
    const missing = missingCapabilities(current);
    checks.push({ id: 'desktop', state: current.desktopReady && current.relayOnline && !missing.length ? 'ok' : 'error', label: 'Codex 实际连接', message: current.desktopReady && current.relayOnline ? missing.length ? `当前 Codex 版本缺少所需接口：${missing.join('、')}。请更新连接器适配版本。` : 'Codex 与中继均已连接，项目、对话、发消息和模型接口可用。' : current.error || '连接服务正在运行，Codex 或中继尚未连接。' });
  } catch { checks.push({ id: 'desktop', state: 'warn', label: 'Codex 实际连接', message: '连接服务未启动，请在软件内点击“连接 Codex”。' }); }
  return { checkedAt: new Date().toISOString(), checks };
}
