import http from 'node:http';
import https from 'node:https';
import { readFileSync, writeFileSync, mkdirSync, existsSync, realpathSync, statSync, lstatSync, openSync, readSync, closeSync } from 'node:fs';
import { dirname, resolve, basename, relative, extname, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID, X509Certificate } from 'node:crypto';
import { hostname } from 'node:os';
import { DatabaseSync } from 'node:sqlite';
import QRCode from 'qrcode';
import { DesktopAdapter } from './desktop-adapter.mjs';
import { PreviewTunnel } from './preview-tunnel.mjs';
import { ImageUploads } from './image-uploads.mjs';
import { FileUploads } from './file-uploads.mjs';
import { PocketScheduler } from './pocket-scheduler.mjs';
import { HistoryStore } from './history.mjs';
import { RelayConnection } from './relay-connection.mjs';
import { startLanRelay } from './lan.mjs';
import { CipherChannel, secret, digest, equalHash, validateCommand, MUTATIONS, MAX_FRAME } from './protocol.mjs';

const ROOT = dirname(fileURLToPath(import.meta.url));
const CONFIG = resolve(process.env.POCKET_CONFIG ?? `${ROOT}/.private/companion.json`);
export function savePrivate(path, data) {
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  writeFileSync(path, data, { mode: 0o600 });
}
export function relayRequest(config, path, body, token = config.desktopToken) {
  return new Promise((resolve, reject) => {
    const data = Buffer.from(JSON.stringify(body));
    const request = https.request(new URL(path, config.mode==='lan'?`https://127.0.0.1:${config.lanPort}`:config.relay), { method: 'POST', ca: config.caCert, ...(config.mode==='lan'?{agent:false}:{}),
      headers: { 'Content-Type': 'application/json', 'Content-Length': data.length, ...(token ? { Authorization: `Bearer ${token}` } : {}) }, timeout: 15000 }, response => {
      let text = '';
      response.on('data', chunk => { text += chunk; if (text.length > 100000) request.destroy(new Error('Relay response too large')); });
      response.on('end', () => { try { const json = JSON.parse(text); if (response.statusCode !== 200) reject(Object.assign(new Error(json.error ?? `Relay HTTP ${response.statusCode}`), { statusCode: response.statusCode })); else resolve(json); } catch (e) { reject(e); } });
    });
    request.on('timeout', () => request.destroy(new Error('中继请求超时')));
    request.on('error', reject); request.end(data);
  });
}

export class CommandLedger {
  constructor(path) {
    this.db = new DatabaseSync(path); this.pending = new Map();
    this.db.exec(`PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS commands(id TEXT PRIMARY KEY, action TEXT, args_hash TEXT, state TEXT, response TEXT, created_at INTEGER);`);
  }
  async run(command, execute, reconcile) {
    if (!MUTATIONS.has(command.action)) return execute();
    const fingerprint = digest(JSON.stringify(command.args));
    const previous = this.db.prepare('SELECT * FROM commands WHERE id=?').get(command.id);
    if (previous) {
      if (previous.action !== command.action || previous.args_hash !== fingerprint) throw new Error('请求 ID 已被用于不同操作');
      if (this.pending.has(command.id)) return this.pending.get(command.id);
      if (previous.state === 'done') return JSON.parse(previous.response);
      // Old connectors recorded a refused writer acquisition as ambiguous even
      // though no turn was sent. Confirming that exact failure clears the phone
      // pending state and restores its draft, without replaying the command.
      if (previous.state === 'uncertain' && previous.action === 'message.send' && /^thread [\w-]+ already has an active writer[.!]?$/i.test(JSON.parse(previous.response ?? '{}').error ?? '')) {
        previous.state = 'rejected';
        previous.response = JSON.stringify({ error: '上次消息因电脑会话占用而未发送，已解除等待确认，请重新发送。' });
        this.db.prepare('UPDATE commands SET state=?,response=? WHERE id=?').run(previous.state, previous.response, command.id);
      }
      if (previous.state === 'rejected') throw Object.assign(new Error(JSON.parse(previous.response).error), { notSubmitted: true, uncertain: false });
      if (reconcile) {
        const receipt = await reconcile();
        if (receipt) {
          this.db.prepare('UPDATE commands SET state=?,response=? WHERE id=?').run('done', JSON.stringify(receipt), command.id);
          return receipt;
        }
      }
      throw Object.assign(new Error('该操作的执行结果尚未确认，请查看会话后再决定；系统不会重复执行'), { uncertain: true });
    }
    this.db.prepare('INSERT INTO commands VALUES(?,?,?,?,?,?)').run(command.id, command.action, fingerprint, 'started', null, Date.now());
    const promise = (async () => {
      try {
        const result = await Promise.resolve().then(execute);
        this.db.prepare('UPDATE commands SET state=?,response=? WHERE id=?').run('done', JSON.stringify(result ?? {}), command.id);
        return result;
      } catch (e) {
        // A read-only receipt query may have confirmed this exact message
        // while the desktop IPC acknowledgement was still pending.
        const confirmed = this.db.prepare('SELECT state,response FROM commands WHERE id=?').get(command.id);
        if (confirmed?.state === 'done') return JSON.parse(confirmed.response);
        if (e.notSubmitted === true && e.uncertain !== true) {
          this.db.prepare('UPDATE commands SET state=?,response=? WHERE id=?').run('rejected', JSON.stringify({ error: e.message }), command.id);
          throw Object.assign(e, { uncertain: false });
        }
        this.db.prepare('UPDATE commands SET state=?,response=? WHERE id=?').run('uncertain', JSON.stringify({ error: e.message }), command.id);
        throw Object.assign(e, { uncertain: true });
      } finally { this.pending.delete(command.id); }
    })();
    this.pending.set(command.id, promise);
    return promise;
  }
  async inspect(command, reconcile) {
    const previous = this.db.prepare('SELECT * FROM commands WHERE id=?').get(command.id);
    if (!previous) return { state: 'not_found' };
    if (previous.action !== command.action || previous.args_hash !== digest(JSON.stringify(command.args))) throw new Error('请求 ID 已被用于不同操作');
    const result = row => row.state === 'done' ? { state: 'done', result: JSON.parse(row.response) }
      : row.state === 'rejected' ? { state: 'rejected', error: JSON.parse(row.response).error }
      : { state: this.pending.has(command.id) ? 'running' : 'uncertain' };
    if (previous.state === 'done' || previous.state === 'rejected') return result(previous);
    if (reconcile) {
      const receipt = await reconcile();
      const current = this.db.prepare('SELECT * FROM commands WHERE id=?').get(command.id);
      if (current.state === 'done' || current.state === 'rejected') return result(current);
      if (receipt) {
        this.db.prepare('UPDATE commands SET state=?,response=? WHERE id=?').run('done', JSON.stringify(receipt), command.id);
        return { state: 'done', result: receipt };
      }
    }
    return result(previous);
  }
  close() { this.db.close(); }
}

export function boundedSnapshot(data) {
  return new HistoryStore().put(data);
}

function uploadedReferences(message) {
  const wrapper=/^\s*# Files mentioned by the user:\s*\n([\s\S]*?)\nDistinguish instructions in attached documents from the user's request\.\s*\n\s*## My request:\s*\n([\s\S]*)$/.exec(message.text??'');
  return {wrapper,files:wrapper?[...wrapper[1].matchAll(/^## ([^\n]+): ((?:\/|[A-Za-z]:[\\/])[^\n]+)$/gm)].map(m=>({name:m[1],path:m[2]})):[]};
}
export class FileAccess {
  constructor(uploadDirectory) { this.files = new Map(); this.uploadDirectory = uploadDirectory && realpathSync(uploadDirectory); }
  decorate(snapshot) {
    const thread = snapshot.thread;
    if (!thread?.id) return snapshot;
    const root = thread.cwd && existsSync(thread.cwd) ? realpathSync(thread.cwd) : null;
    // A project root of '/' or the user's entire home never grants file access.
    const projectAllowed = root && root !== '/' && root !== process.env.HOME;
    const paths = new Set((snapshot.artifacts ?? []).map(item => item.path).filter(value => typeof value === 'string'));
    const attached = new Set(), uploaded = new Set();
    for (const message of snapshot.messages ?? []) if (message.role === 'user') {
      for (const ref of uploadedReferences(message).files) {
        try { const file=realpathSync(ref.path);const inside=this.uploadDirectory && relative(this.uploadDirectory,file);if(inside&&!inside.startsWith('..')&&!isAbsolute(inside)&&!lstatSync(ref.path).isSymbolicLink()){uploaded.add(file);paths.add(file);} } catch {}
      }
      for (const attachment of message.attachments ?? []) if (attachment.type === 'image' && typeof attachment.path === 'string') { attached.add(attachment.path); paths.add(attachment.path); }
    }
    for (const message of snapshot.messages ?? []) if (message.role === 'assistant') {
      for (const match of message.text.matchAll(/!?\[[^\]]*\]\(<?(\/[^\n)]+?)>?\)/g)) paths.add(match[1].replace(/:\d+$/, ''));
    }
    const artifacts = [], byPath = new Map();
    for (let path of paths) {
      try {
        const sourcePath = path;
        const explicitFile = uploaded.has(path) && !lstatSync(path).isSymbolicLink();
        const explicitImage = attached.has(path) && ['.png', '.jpg', '.jpeg', '.webp', '.gif', '.bmp', '.heic', '.heif'].includes(extname(path).toLowerCase()) && !lstatSync(path).isSymbolicLink();
        path = realpathSync(path);
        const inside = projectAllowed ? relative(root, path) : null;
        if (!explicitImage && !explicitFile && (!inside || inside.startsWith('..') || isAbsolute(inside))) continue;
        const stat = statSync(path); if (!stat.isFile() || stat.size > 100 * 1024 * 1024) continue;
        const id = digest(`${thread.id}|${path}|${stat.size}|${stat.mtimeMs}`).slice(0, 32);
        const extension = extname(path).toLowerCase();
        const mime = ({ '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp':'image/webp', '.gif':'image/gif', '.bmp':'image/bmp', '.heic':'image/heic', '.heif':'image/heif', '.pdf':'application/pdf', '.md':'text/markdown', '.txt':'text/plain', '.json':'application/json', '.html':'text/html', '.css':'text/css', '.js':'text/javascript', '.ts':'text/plain', '.kt':'text/plain', '.py':'text/plain', '.yaml':'text/plain', '.yml':'text/plain', '.xml':'text/plain', '.csv':'text/plain', '.svg':'image/svg+xml' })[extension] ?? 'application/octet-stream';
        this.files.set(`${thread.id}:${id}`, { path, sourcePath, root: explicitImage || explicitFile ? dirname(path) : root, size: stat.size, modified: stat.mtimeMs });
        const artifact = { id, name: basename(path), size: stat.size, mime, path };
        artifacts.push(artifact); byPath.set(sourcePath, artifact);
      } catch { /* A linked file may no longer exist. */ }
    }
    const messages = (snapshot.messages ?? []).map(message => {
      const attachments=(message.attachments ?? []).map(attachment => byPath.has(attachment.path) ? { ...attachment, ...byPath.get(attachment.path), available:true } : { ...attachment,name:basename(attachment.path),available:false });
      const references=uploadedReferences(message);
      if(message.role==='user')for(const ref of references.files)if(uploaded.has(ref.path)&&byPath.has(ref.path)&&!attachments.some(a=>a.path===ref.path))attachments.push({...byPath.get(ref.path),type:'file',available:true});
      const clean=references.wrapper&&references.files.length&&references.files.every(ref=>attachments.some(a=>a.path===ref.path&&a.available));
      return {...message,...(clean?{text:references.wrapper[2].trim(),detail:message.detail??message.text}:{}),...(attachments.length?{attachments}:{})};
    });
    return { ...snapshot, messages, artifacts };
  }
  read(args) {
    const file = this.files.get(`${args.threadId}:${args.artifactId}`);
    if (!file) throw new Error('文件不在当前会话授权的交付文件中，请刷新会话');
    const current = realpathSync(file.path), inside = relative(file.root, current);
    if (lstatSync(file.sourcePath).isSymbolicLink() || realpathSync(file.sourcePath) !== current) throw new Error('文件路径已变化');
    if (current !== file.path || inside.startsWith('..') || isAbsolute(inside)) throw new Error('文件路径已变化');
    const stat = statSync(current);
    if (stat.size !== file.size || stat.mtimeMs !== file.modified) throw new Error('文件内容已变化，请刷新后重试');
    const offset = args.offset ?? 0;
    if (!Number.isSafeInteger(offset) || offset < 0 || offset > stat.size) throw new Error('Invalid file offset');
    const data = Buffer.alloc(Math.min(192 * 1024, stat.size - offset));
    const fd = openSync(current, 'r'); let bytes;
    try { bytes = readSync(fd, data, 0, data.length, offset); } finally { closeSync(fd); }
    return { name: basename(current), data: data.subarray(0, bytes).toString('base64'), total: stat.size, nextOffset: offset + bytes, done: offset + bytes === stat.size };
  }
}

export async function startCompanion(config, { adapter = new DesktopAdapter({ callerThreadId: config.contextThreadId }), port = 43785, statePath = resolve(ROOT, '.private/commands.sqlite'), configPath = CONFIG } = {}) {
  mkdirSync(dirname(statePath), { recursive: true, mode: 0o700 });
  const uploads = new ImageUploads(resolve(dirname(statePath), 'image-uploads'));
  const fileUploads = new FileUploads(resolve(dirname(statePath), 'file-uploads'));
  const ledger = new CommandLedger(statePath), files = new FileAccess(fileUploads.directory), preview = new PreviewTunnel(), history = new HistoryStore();
  const scheduler=new PocketScheduler(adapter,resolve(dirname(statePath),'pocket-schedules.json'));
  let channel = null, crypto = new CipherChannel(config.key, config.hostId, 'desktop');
  let stopping = false, mobileOnline = false, pairRequest = null, lastError = '', relayError = '', desktopRetry, desktopConnecting;
  const send = payload => { if (link.online && channel) link.send(JSON.stringify(crypto.encrypt({ ...payload, channel }))); };
  let eventTimer; const latestEvents = new Map();
  adapter.on('event', event => {
    if (event.type === 'thread.updated') {
      try { latestEvents.set(event.threadId, { ...event, data: history.put(files.decorate(event.data)) }); } catch (e) { lastError = e.message; return; }
      if (!eventTimer) eventTimer = setTimeout(() => { eventTimer = null; for (const event of latestEvents.values()) send({ type: 'event', event }); latestEvents.clear(); }, 150);
    } else send({ type: 'event', event });
  });
  async function connectDesktop() {
    if (stopping || adapter.ready) return;
    if (desktopConnecting) return desktopConnecting;
    desktopConnecting = (async () => {
      try { await adapter.connect(); lastError = ''; } catch (e) { lastError = `Codex: ${e.message}`; }
      finally { desktopConnecting = null; }
    })();
    return desktopConnecting;
  }
  const url = new URL('/relay', config.mode==='lan'?`https://127.0.0.1:${config.lanPort}`:config.relay); url.protocol = 'wss:';
  const link = new RelayConnection(url, { ca: config.caCert, maxPayload: MAX_FRAME, headers: { Authorization: `Bearer ${config.desktopToken}`, 'X-Pocket-Host': config.hostId, 'X-Pocket-Role': 'desktop' } }, {
    onOpen: () => { relayError = ''; },
    onDisconnect: reason => { preview.close(); mobileOnline = false; channel = null; relayError = reason === 'heartbeat-timeout' ? '中继心跳超时，正在自动重连' : '中继连接中断，正在自动重连'; },
    onMessage: async (data, socket) => {
      try {
        const frame = JSON.parse(data.toString());
        if (frame.type === 'status') { mobileOnline = !!frame.peerOnline; if (!mobileOnline) { channel = null; preview.close(); } return; }
        if (frame.type === 'pair/cancelled') { if(pairRequest?.fingerprint===frame.fingerprint)pairRequest=null;return; }
        if (frame.type === 'pair/request') { pairRequest = { name: String(frame.name).slice(0, 80), fingerprint: frame.fingerprint }; return; }
        const payload = crypto.decrypt(frame);
        if (payload.type === 'hello') {
          if (Math.abs(Date.now() - payload.sentAt) > 120000 || typeof payload.channel !== 'string' || !/^[a-f0-9-]{36}$/i.test(payload.channel)) return;
          preview.close(); channel = payload.channel; await connectDesktop();
          if (!link.isCurrent(socket) || channel !== payload.channel) return;
          send({ type: 'hello', host: { id: config.hostId, name: config.name }, capabilities: { ...adapter.diagnostics().capabilities, 'file.upload': true, 'automations.list': true, 'command.status': true }, desktopReady: adapter.ready, error: lastError }); return;
        }
        if (payload.type !== 'request' || !channel || payload.channel !== channel) return;
        const requestChannel = channel;
        try {
          validateCommand(payload);
          if (payload.action === 'command.status') {
            const command = payload.args;
            const result = await ledger.inspect(command, command.action === 'message.send' && adapter.confirmMessage ? () => adapter.confirmMessage({ ...command.args, clientUserMessageId: command.id }) : undefined);
            if (link.isCurrent(socket) && channel === requestChannel) send({ type: 'response', id: payload.id, result });
            return;
          }
          await connectDesktop();
          if (!adapter.ready) throw new Error('电脑上的 Codex 尚未连接，请检查电脑端应用');
          const result = await ledger.run(payload, async () => {
            if (payload.action==='automations.list'||payload.action.startsWith('automation.')) return scheduler.call(payload.action,payload.args);
            if (payload.action.startsWith('file.upload.')) return fileUploads.call(payload.action, payload.args);
            if (payload.action.startsWith('image.upload.')) return uploads.call(payload.action, payload.args);
            if (payload.action.startsWith('preview.')) return preview.call(payload.action, payload.args);
            if (payload.action === 'file.read') return files.read(payload.args);
            if (payload.action === 'thread.history') return history.page(payload.args);
            if (payload.action === 'message.read') return history.read(payload.args);
            let args = payload.args;
            if (['message.send', 'thread.create'].includes(payload.action)) {
              try { args = { ...args, files: fileUploads.resolve(args.fileUploadIds, payload.action === 'thread.create' ? '__new__' : args.threadId), imagePaths: uploads.resolve(args.imageUploadIds, payload.action === 'thread.create' ? '__new__' : args.threadId), clientUserMessageId: payload.id }; }
              catch (error) { throw Object.assign(error, { notSubmitted: true }); }
            }
            const result = await adapter.call(payload.action, args);
            return payload.action === 'thread.read' ? history.put(files.decorate(result)) : result;
          }, payload.action === 'message.send' && adapter.confirmMessage ? () => adapter.confirmMessage({ ...payload.args, clientUserMessageId: payload.id }) : undefined);
          if (link.isCurrent(socket) && channel === requestChannel) send({ type: 'response', id: payload.id, result: result ?? {} });
        } catch (e) { if (link.isCurrent(socket) && channel === requestChannel) send({ type: 'response', id: payload.id, error: e.message, uncertain: !!e.uncertain }); }
      } catch { /* Unauthenticated or replayed content cannot reach desktop methods. */ }
    }
  });
  const manager = http.createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store'); res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer'); res.setHeader('X-Frame-Options', 'DENY');
    const url = new URL(req.url, 'http://127.0.0.1');
    if (!['127.0.0.1', 'localhost'].includes((req.headers.host ?? '').split(':')[0])) { res.writeHead(403).end(); return; }
    if (req.method === 'GET' && url.pathname === '/') { res.setHeader('Content-Type','text/html; charset=utf-8'); res.end(readFileSync(`${ROOT}/manager.html`)); return; }
    if (req.method === 'GET' && url.pathname === '/assets/logo.png') { res.setHeader('Content-Type','image/png'); res.end(readFileSync(`${ROOT}/design/brand/codex-pocket-logo.png`)); return; }
    if (!equalHash(digest(req.headers.authorization ?? ''), digest(`Bearer ${config.managerToken}`))) { res.writeHead(401).end('{}'); return; }
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    try {
      if (req.method === 'GET' && url.pathname === '/api/status') return res.end(JSON.stringify({ name: config.name, relayOnline: link.online, relayState: link.state, mobileOnline, desktopReady: adapter.ready, error: relayError || lastError, pairRequest, capabilities: { ...adapter.diagnostics().capabilities, 'file.upload': true, 'automations.list': true } }));
      if (req.method === 'GET' && url.pathname === '/api/pairing') {
        const pairing = { claimFromMobile:config.mobileClaim === true, v:1, mode:config.mode==='lan'?'lan':'cloud', relay:config.relay, hostId:config.hostId, name:config.name, pairCode:config.pairCode, key:config.key, pin:config.pin, expiresAt:config.pairExpires };
        const link = 'codexpocket://pair?data=' + Buffer.from(JSON.stringify(pairing)).toString('base64url');
        return res.end(JSON.stringify({ link, qr: await QRCode.toDataURL(link, { width: 420, margin: 2 }), expiresAt: config.pairExpires }));
      }
      if (req.method === 'POST') {
        let body = ''; for await (const part of req) { body += part; if (body.length > 4096) throw new Error('Request too large'); }
        const json = JSON.parse(body || '{}');
        if (url.pathname === '/api/decision') {
          if (!pairRequest || json.fingerprint !== pairRequest.fingerprint) throw new Error('配对请求已变化，请刷新');
          await relayRequest(config, '/api/pair/decision', { hostId: config.hostId, fingerprint: json.fingerprint, accept: json.accept === true }); pairRequest = null;
          return res.end(JSON.stringify({ ok: true }));
        }
        if (url.pathname === '/api/reset') {
          const nextCode = secret(), nextKey = secret();
          await relayRequest(config, '/api/pair/reset', { hostId: config.hostId, pairCode: nextCode });
          config.pairCode = nextCode; config.key = nextKey; config.pairExpires = Date.now() + 10 * 60_000;
          pairRequest = null; channel = null; crypto = new CipherChannel(config.key, config.hostId, 'desktop');
          savePrivate(configPath, JSON.stringify(config, null, 2));
          return res.end(JSON.stringify({ ok: true }));
        }
      }
      res.writeHead(404).end('{}');
    } catch (e) { res.writeHead(400).end(JSON.stringify({ error: e.message })); }
  });
  await new Promise((resolve, reject) => { manager.once('error', reject); manager.listen(port, '127.0.0.1', resolve); });
  link.start(); await connectDesktop(); desktopRetry = setInterval(connectDesktop, 10000);
  return { manager, adapter, status: () => ({ relayOnline: link.online, mobileOnline, desktopReady: adapter.ready }), close: async () => {
    stopping = true; scheduler.close(); preview.close(); clearTimeout(eventTimer); clearInterval(desktopRetry); link.close(); adapter.close();
    await new Promise(resolve => manager.close(resolve)); ledger.close();
  } };
}

async function main() {
  const command = process.argv[2] ?? 'start';
  if (command === 'init') {
    if (existsSync(CONFIG)) throw new Error('配置已存在；为避免误撤销设备，不会覆盖');
    const arg = key => process.argv[process.argv.indexOf(key) + 1];
    const relay = arg('--relay'), certFile = arg('--cert'), adminFile = arg('--admin-file');
    if (!relay?.startsWith('https://') || !certFile || !adminFile) throw new Error('init --relay https://host:port --cert cert.pem --admin-file admin-token.txt');
    const caCert = readFileSync(certFile, 'utf8'), certificate = new X509Certificate(caCert);
    const config = { relay, name: hostname(), hostId: secret().slice(0, 32), desktopToken: secret(), pairCode: secret(), key: secret(), managerToken: secret(), caCert,
      pin: certificate.fingerprint256.replaceAll(':','').toLowerCase(), contextThreadId: process.env.CODEX_DESKTOP_CONTEXT_THREAD_ID ?? process.env.CODEX_THREAD_ID, pairExpires: Date.now() + 10 * 60_000 };
    if (!config.contextThreadId) throw new Error('需要现有桌面聊天 ID：CODEX_DESKTOP_CONTEXT_THREAD_ID');
    await relayRequest(config, '/api/hosts', { hostId: config.hostId, name: config.name, desktopToken: config.desktopToken, pairCode: config.pairCode }, readFileSync(adminFile, 'utf8').trim());
    savePrivate(CONFIG, JSON.stringify(config, null, 2)); console.log('电脑已注册，配置保存在本机私有目录。'); return;
  }
  const config = JSON.parse(readFileSync(CONFIG, 'utf8'));
  if (command === 'manager-url') { console.log(`http://127.0.0.1:43785/#${config.managerToken}`); return; }
  if (command !== 'start') throw new Error('Supported commands: init, start, manager-url');
  const port = Number(process.env.POCKET_MANAGER_PORT ?? 43785);
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid manager port');
  const lan = config.mode==='lan'?await startLanRelay(config,{dbPath:resolve(dirname(CONFIG),'lan-relay.sqlite')}):null;
  let app;
  try { app = await startCompanion(config, { port, statePath: resolve(dirname(CONFIG), 'commands.sqlite'), configPath: CONFIG }); }
  catch(e){await lan?.close();throw e}
  console.log(`Codex Pocket companion running. Local manager: http://127.0.0.1:${port}`);
  let closing=false;
  for (const signal of ['SIGINT','SIGTERM']) process.on(signal, async () => {if(closing)return;closing=true;await app.close();await lan?.close();process.exit(0)});
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(e => { console.error(e.message); process.exitCode = 1; });
