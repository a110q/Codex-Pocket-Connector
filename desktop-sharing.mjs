import { execFile } from 'node:child_process';
import { lstatSync, realpathSync, statSync } from 'node:fs';
import { homedir } from 'node:os';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { promisify } from 'node:util';
import { AppServerClient } from './app-server-client.mjs';

const execute = promisify(execFile);
export const localCodexHome = (env = process.env) => path.resolve(env.CODEX_HOME || path.join(env.HOME || homedir(), '.codex'));

// Discover only the official daemon's fixed, current-user Unix endpoint.
export function localControlSocket(home) {
  if (!process.getuid) return null;
  const directory = path.join(home, 'app-server-control');
  const published = path.join(directory, 'app-server-control.sock');
  const owner = process.getuid();
  const dir = lstatSync(directory, { throwIfNoEntry: false });
  if (!dir?.isDirectory() || dir.uid !== owner || (dir.mode & 0o022) !== 0) return null;
  const entry = lstatSync(published, { throwIfNoEntry: false });
  if (!entry || entry.uid !== owner) return null;
  // macOS sun_path may require an indirection to a shorter socket path.
  const socket = entry.isSymbolicLink() ? realpathSync(published) : published;
  const target = statSync(socket, { throwIfNoEntry: false });
  return target?.isSocket() && target.uid === owner && (target.mode & 0o022) === 0 ? socket : null;
}

export function hasDesktopSocket(output, pid, socket) {
  const peers = new Set(), accepted = new Set();
  for (const line of output.split('\n')) {
    // In Darwin lsof, the table's DEVICE column has the kernel socket ID;
    // field-mode `D` omits this ID for Unix-domain sockets.
    const row = line.match(/^.*?\s+(\d+)\s+\S+\s+\S+\s+unix\s+(0x[0-9a-f]+)\s+\S+\s+(.+)$/i);
    if (!row) continue;
    const [, processId, device, name] = row;
    if (Number(processId) === pid && /^->0x[0-9a-f]+$/i.test(name)) peers.add(name.slice(2));
    if (Number(processId) !== pid && name === socket) accepted.add(device);
  }
  return [...peers].some(peer => accepted.has(peer));
}

export class DesktopSharing {
  constructor(installation, { run = execute, getSocket = localControlSocket,
    client = controlSocket => new AppServerClient({ controlSocket, timeoutMs: 2000 }), wait = delay, attempts = 20 } = {}) {
    Object.assign(this, { installation, run, getSocket, client, wait, attempts });
    this.busy = false;
  }
  async desktopPid(bundle) {
    const { stdout } = await this.run('/usr/bin/lsappinfo', ['find', `bundlepath=${bundle}`], { timeout: 3000 });
    if (!stdout.trim()) return null;
    const details = await this.run('/usr/bin/lsappinfo', ['info', '-only', 'pid', '-app', stdout.trim()], { timeout: 3000 });
    const pid = Number(details.stdout.match(/\bpid\s*=\s*(\d+)/)?.[1]);
    if (!pid) throw new Error('无法确认 Codex 桌面进程，请稍后重试。');
    return pid;
  }
  async attached(pid, socket) {
    if (!pid || !socket) return false;
    try {
      const { stdout } = await this.run('/usr/sbin/lsof', ['-nP', '-U', '-w'], { timeout: 3000, maxBuffer: 4 * 1024 * 1024 });
      return hasDesktopSocket(stdout, pid, socket);
    } catch { return false; }
  }
  async open() {
    if (this.installation.platform !== 'darwin') throw new Error('跨端同步启动目前仅支持 macOS。');
    if (this.busy) throw new Error('正在打开 Codex，请稍候。');
    this.busy = true;
    try {
      const binary = this.installation.binary();
      const bundle = binary.match(/^(.+?\.app)\/Contents\/Resources\/(?:codex-cli\/)?(?:bin\/)?codex$/i)?.[1];
      if (!bundle) throw new Error('请选择 Codex 或 ChatGPT 桌面应用的安装位置，再开启跨端同步。');
      const env = { ...this.installation.env };
      const home = env.CODEX_HOME = localCodexHome(env);
      for (const key of ['CODEX_THREAD_ID', 'CODEX_DESKTOP_CONTEXT_THREAD_ID', 'CODEX_APP_TOOLS_PIPE_PATH']) delete env[key];
      const checkedSocket = () => { try { return this.getSocket(home); } catch { return null; } };
      const running = async () => {
        const pid = await this.desktopPid(bundle);
        if (!pid) return null;
        return { status: await this.attached(pid, checkedSocket()) ? 'connected' : 'quit-required' };
      };
      const existing = await running();
      if (existing) return existing;
      try {
        await this.run(binary, ['app-server', 'daemon', 'start'], { env, timeout: 20000, maxBuffer: 64 * 1024 });
      } catch { throw new Error('无法启动 Codex 共享服务，请确认桌面应用已更新，然后重试。'); }
      const socket = checkedSocket();
      if (!socket) throw new Error('没有找到可用的 Codex 共享服务。');
      const probe = this.client(socket);
      try { await probe.connect(); }
      catch { throw new Error('Codex 共享服务未能连接，请稍后重试。'); }
      finally { probe.close(); }
      // Recheck after waiting for the daemon. Never terminate or force a new
      // instance, or apply environment settings globally to the desktop.
      const launchedMeanwhile = await running();
      if (launchedMeanwhile) return launchedMeanwhile;
      const endpoint = `ws+unix://localhost${socket.split('/').map(encodeURIComponent).join('/')}:/rpc`;
      try {
        await this.run('/usr/bin/open', ['-a', bundle, '--env', `CODEX_HOME=${home}`, '--env', `CODEX_APP_SERVER_WS_URL=${endpoint}`], { timeout: 10000 });
      } catch { throw new Error('未能打开 Codex 桌面应用，请检查安装位置。'); }
      for (let i = 0; i < this.attempts; i++) {
        const pid = await this.desktopPid(bundle);
        if (await this.attached(pid, socket)) return { status: 'connected' };
        await this.wait(400);
      }
      return { status: 'unconfirmed' };
    } finally { this.busy = false; }
  }
}
