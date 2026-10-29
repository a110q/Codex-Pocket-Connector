import { PRODUCT_VERSION } from './version.mjs';
import { spawn } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { existsSync } from 'node:fs';
import path from 'node:path';
import os from 'node:os';

export { resolveCodexCLI as findCodexCLI } from './codex-installation.mjs';
import { resolveCodexCLI as findCodexCLI } from './codex-installation.mjs';

// The official stdio transport stays inside the desktop process; no TCP port,
// caller chat ID, tool pipe or inherited Codex terminal is needed.
export class AppServerClient extends EventEmitter {
  constructor({ binary, env = process.env, cwd = process.cwd(), timeoutMs = 20000, spawnProcess = spawn } = {}) {
    super(); Object.assign(this, { binary, env, cwd, timeoutMs, spawnProcess });
    this.pending = new Map(); this.serial = 0; this.child = null; this.connecting = null;
  }
  async connect() {
    if (this.connecting) return this.connecting;
    this.connecting = this.start();
    try { return await this.connecting; } finally { this.connecting = null; }
  }
  async start() {
    if (this.child) return;
    const env = { ...this.env };
    for (const key of ['CODEX_THREAD_ID', 'CODEX_DESKTOP_CONTEXT_THREAD_ID', 'CODEX_APP_TOOLS_PIPE_PATH']) delete env[key];
    const child = this.spawnProcess(this.binary ?? findCodexCLI({env:this.env}), ['app-server'], { env, cwd: this.cwd, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    this.child = child; let fragments = [], bufferedBytes = 0;
    const ended = message => {
      if (this.child !== child) return;
      this.child = null;
      for (const waiter of this.pending.values()) { clearTimeout(waiter.timer); waiter.reject(new Error(message)); }
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
          const value = JSON.parse(line);
          const waiter = value.method ? null : this.pending.get(value.id);
          if (waiter) {
            clearTimeout(waiter.timer); this.pending.delete(value.id);
            if (value.error) waiter.reject(Object.assign(new Error(value.error.message), { code: value.error.code }));
            else waiter.resolve(value.result);
          } else if (value.method && value.id != null) this.emit('request', value);
          else if (value.method) this.emit('notification', value);
        } catch { ended('Codex 返回了无效的数据。'); child.kill(); return; }
      }
    });
    try {
      await this.request('initialize', { clientInfo: { name: 'codex_pocket', title: 'Codex Pocket', version: PRODUCT_VERSION }, capabilities: { experimentalApi: true } });
      this.send({ method: 'initialized', params: {} });
    } catch (error) { this.close(); throw error; }
  }
  send(value) {
    if (!this.child?.stdin.writable) throw new Error('Codex 尚未连接。');
    this.child.stdin.write(JSON.stringify(value) + '\n');
  }
  request(method, params = {}, timeoutMs = this.timeoutMs) {
    return new Promise((resolve, reject) => {
      const id = ++this.serial;
      const timer = setTimeout(() => { this.pending.delete(id); const error = new Error(`Codex 请求超时：${method}`); error.uncertain = /^(turn\/|review\/start|thread\/(start|archive|unarchive|delete|name\/set)|skills\/config\/write|plugin\/(install|uninstall)|config\/value\/write|mcpServer\/oauth\/login)/.test(method); reject(error); }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      try { this.send({ id, method, params }); } catch (error) { clearTimeout(timer); this.pending.delete(id); reject(error); }
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
  async closeAndWait() {
    const child = this.child;
    if (!child) return;
    await new Promise((resolve, reject) => {
      let hardTimer;
      const finish = () => { clearTimeout(timer); clearTimeout(hardTimer); resolve(); };
      child.once('exit', finish);
      const timer = setTimeout(() => {
        child.kill('SIGKILL');
        hardTimer = setTimeout(() => reject(new Error('Codex 对话进程尚未退出')), 1000);
      }, 3000);
      this.close();
    });
  }
  close() {
    const child = this.child; if (!child) return;
    this.child = null; child.stdin.end(); child.kill();
    for (const waiter of this.pending.values()) { clearTimeout(waiter.timer); waiter.reject(new Error('Codex 连接已关闭。')); }
    this.pending.clear();
  }
}
