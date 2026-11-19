import { PRODUCT_VERSION } from './version.mjs';
import { spawn } from 'node:child_process';
import { EventEmitter, once } from 'node:events';
import net from 'node:net';
import WebSocket from 'ws';

export { resolveCodexCLI as findCodexCLI } from './codex-installation.mjs';
import { resolveCodexCLI as findCodexCLI } from './codex-installation.mjs';

// JSON-RPC over a private stdio process or the local daemon’s Unix socket.
export class AppServerClient extends EventEmitter {
  constructor({ binary, env = process.env, cwd = process.cwd(), timeoutMs = 20000, spawnProcess = spawn, controlSocket } = {}) {
    super(); Object.assign(this, { binary, env, cwd, timeoutMs, spawnProcess, controlSocket });
    this.pending = new Map(); this.serial = 0; this.child = null; this.socket = null; this.connecting = null; this.closing = null;
  }
  async connect() {
    if (this.connecting) return this.connecting;
    this.connecting = this.start();
    try { return await this.connecting; } finally { this.connecting = null; }
  }
  async start() {
    if (this.controlSocket) return this.startSocket();
    if (this.child) return;
    if (this.closing) await this.closeAndWait();
    const env = { ...this.env };
    for (const key of ['CODEX_THREAD_ID', 'CODEX_DESKTOP_CONTEXT_THREAD_ID', 'CODEX_APP_TOOLS_PIPE_PATH']) delete env[key];
    const child = this.spawnProcess(this.binary ?? findCodexCLI({env:this.env}), ['app-server'], { env, cwd: this.cwd, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    this.child = child; let fragments = [], bufferedBytes = 0;
    const ended = message => {
      if (this.child !== child) return;
      this.child = null;
      for (const waiter of this.pending.values()) { clearTimeout(waiter.timer); waiter.reject(Object.assign(new Error(message), { uncertain: /^thread\/(archive|unarchive|delete)$/.test(waiter.method) })); }
      this.pending.clear(); this.emit('disconnect', new Error(message));
    };
    child.on('error', () => ended('Codex 运行程序启动失败，请检查安装。'));
    child.on('exit', () => ended('Codex 服务已退出，点击重新连接。'));
    child.stdin.on('error', () => ended('Codex 服务连接已关闭。'));
    // stderr can contain provider configuration; never forward it to the UI.
    child.stderr.on('data', () => {});
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', chunk => {
      if (this.child !== child) return;
      for (let offset = 0; offset < chunk.length;) {
        const end = chunk.indexOf('\n', offset);
        const part = chunk.slice(offset, end < 0 ? chunk.length : end);
        bufferedBytes += Buffer.byteLength(part);
        if (bufferedBytes > 64 * 1024 * 1024) { ended('Codex 返回的数据超过传输限制。'); child.kill(); return; }
        fragments.push(part);
        if (end < 0) break;
        // Large history responses are joined once, not scanned and flattened
        // again for every 64 KB stdout chunk.
        const line = fragments.join(''); fragments = []; bufferedBytes = 0; offset = end + 1;
        if (!line.trim()) continue;
        try {
          this.receive(JSON.parse(line));
        } catch { ended('Codex 返回了无效的数据。'); child.kill(); return; }
      }
    });
    try { await this.initialize(); } catch (error) { this.close(); throw error; }
  }
  async startSocket() {
    if (this.socket?.readyState === WebSocket.OPEN) return;
    // The URL selects the daemon's HTTP route; the connection is only AF_UNIX.
    const socket = new WebSocket('ws://localhost/rpc', {
      createConnection: () => net.createConnection(this.controlSocket),
      handshakeTimeout: this.timeoutMs, maxPayload: 64 * 1024 * 1024, perMessageDeflate: false,
    });
    this.socket = socket;
    const ended = () => {
      if (this.socket !== socket) return;
      this.socket = null;
      const message = 'Codex 桌面共享服务连接已断开。';
      for (const waiter of this.pending.values()) {
        clearTimeout(waiter.timer);
        waiter.reject(Object.assign(new Error(message), { uncertain: /^thread\/(archive|unarchive|delete)$/.test(waiter.method) }));
      }
      this.pending.clear();
      this.emit('disconnect', new Error(message));
    };
    socket.on('error', ended);
    socket.on('close', ended);
    socket.on('message', data => {
      if (this.socket !== socket) return;
      try { this.receive(JSON.parse(data.toString())); }
      catch { socket.terminate(); ended(); }
    });
    try { await once(socket, 'open'); await this.initialize(); }
    catch (error) { this.close(); throw error; }
  }
  async initialize() {
    await this.request('initialize', { clientInfo: { name: 'codex_pocket', title: 'Codex Pocket', version: PRODUCT_VERSION }, capabilities: { experimentalApi: true } });
    this.send({ method: 'initialized', params: {} });
  }
  receive(value) {
    const waiter = value.method ? null : this.pending.get(value.id);
    if (waiter) {
      clearTimeout(waiter.timer); this.pending.delete(value.id);
      if (value.error) waiter.reject(Object.assign(new Error(value.error.message), { code: value.error.code }));
      else waiter.resolve(value.result);
    } else if (value.method && value.id != null) this.emit('request', value);
    else if (value.method) this.emit('notification', value);
  }
  send(value) {
    if (this.socket?.readyState === WebSocket.OPEN) { this.socket.send(JSON.stringify(value)); return; }
    if (!this.child?.stdin.writable) throw new Error('Codex 尚未连接。');
    this.child.stdin.write(JSON.stringify(value) + '\n');
  }
  request(method, params = {}, timeoutMs = this.timeoutMs) {
    return new Promise((resolve, reject) => {
      const id = ++this.serial;
      const timer = setTimeout(() => { this.pending.delete(id); const error = new Error(`Codex 请求超时：${method}`); error.uncertain = /^(turn\/|review\/start|thread\/(start|archive|unarchive|delete|name\/set)|skills\/config\/write|plugin\/(install|uninstall)|config\/value\/write|mcpServer\/oauth\/login)/.test(method); reject(error); }, timeoutMs);
      this.pending.set(id, { method, resolve, reject, timer });
      try { this.send({ id, method, params }); } catch (error) { clearTimeout(timer); this.pending.delete(id); if (/^thread\/(archive|unarchive|delete)$/.test(method)) error.notSubmitted = true; reject(error); }
    });
  }
  async pages(method, params = {}) {
    const result = []; const seen = new Set(); let cursor;
    do {
      const page = await this.request(method, { ...params, limit: 100, ...(cursor ? { cursor } : {}) });
      result.push(...(page.data ?? [])); cursor = page.nextCursor;
      if (cursor && seen.has(cursor)) throw new Error('Codex 分页游标重复，请重新连接。');
      if (cursor) seen.add(cursor);
    } while (cursor);
    return result;
  }
  async closeAndWait({ graceMs = 3000, killWaitMs = 1000 } = {}) {
    this.close();
    const closing = this.closing;
    if (!closing) return;
    let timer, hardTimer;
    try {
      await Promise.race([closing.exited, new Promise((_, reject) => {
        timer = setTimeout(() => {
          try { closing.child.kill('SIGKILL'); }
          catch (error) { reject(error); return; }
          hardTimer = setTimeout(() => reject(new Error('Codex 对话进程尚未退出')), killWaitMs);
        }, graceMs);
      })]);
      if (this.closing === closing) this.closing = null;
    } finally { clearTimeout(timer); clearTimeout(hardTimer); }
  }
  close() {
    const socket = this.socket; this.socket = null; socket?.terminate();
    const child = this.child; if (!child && !socket) return;
    this.child = null;
    if (child?.pid && child.exitCode === null && child.signalCode === null) {
      // Keep an exit receipt even if closeAndWait times out. A subsequent
      // handoff must not interpret a detached stdin as a released writer.
      this.closing = { child, exited: new Promise(resolve => child.once('exit', resolve)) };
      child.stdin.end(); child.kill();
    }
    for (const waiter of this.pending.values()) { clearTimeout(waiter.timer); waiter.reject(new Error('Codex 连接已关闭。')); }
    this.pending.clear();
  }
}
