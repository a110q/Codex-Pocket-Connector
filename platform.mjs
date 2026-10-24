import path from 'node:path';
import os from 'node:os';
import { readdir, lstat } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

const platformPath = platform => platform === 'win32' ? path.win32 : path.posix;
const psQuote = value => `'${String(value).replaceAll("'", "''")}'`;

export function connectorStateDirectory({ platform = process.platform, env = process.env, home = os.homedir() } = {}) {
  const paths = platformPath(platform);
  const state = env.POCKET_STATE_DIR || (platform === 'win32'
    ? paths.join(env.LOCALAPPDATA || paths.join(home, 'AppData', 'Local'), 'Codex Pocket')
    : platform === 'darwin' ? paths.join(home, 'Library', 'Application Support', 'Codex Pocket')
      : paths.join(env.XDG_STATE_HOME || paths.join(home, '.local', 'state'), 'Codex Pocket'));
  if (!paths.isAbsolute(state)) throw new Error('Codex Pocket state directory must be an absolute path');
  return paths.normalize(state);
}

function powershell(script, { env = process.env, run = spawnSync } = {}) {
  const executable = path.win32.join(env.SystemRoot || env.SYSTEMROOT || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
  return run(executable, ['-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')],
    { encoding: 'utf8', windowsHide: true, timeout: 10000, maxBuffer: 1024 * 1024 });
}

export function openBrowser(value, { platform = process.platform, env = process.env, run = spawnSync } = {}) {
  const url = new URL(value);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error('Only HTTP(S) browser URLs are supported');
  const result = platform === 'win32'
    ? powershell(`$ErrorActionPreference='Stop'; Start-Process -FilePath ${psQuote(url.href)}`, { env, run })
    : run(platform === 'darwin' ? '/usr/bin/open' : 'xdg-open', [url.href], { windowsHide: true, encoding: 'utf8', timeout: 10000 });
  if (result.error || result.status !== 0) throw new Error('无法打开默认浏览器，请检查系统默认浏览器设置');
}

// Use the creation time and executable identity as well as the PID. If a process
// cannot be inspected, return null so callers never stop an unverified process.
export function processIdentity(pid, { platform = process.platform, env = process.env, run = spawnSync } = {}) {
  if (!Number.isSafeInteger(pid) || pid <= 0) return null;
  const result = platform === 'win32'
    ? powershell(`$ErrorActionPreference='Stop'; $p=Get-CimInstance -ClassName Win32_Process -Filter 'ProcessId = ${pid}'; if($null -eq $p){exit 2}; [ordered]@{pid=[int]$p.ProcessId;created=$p.CreationDate.ToUniversalTime().ToString('o');executable=$p.ExecutablePath;command=$p.CommandLine} | ConvertTo-Json -Compress`, { env, run })
    : run('/bin/ps', ['-p', String(pid), '-o', 'lstart=', '-o', 'command='], { encoding: 'utf8', timeout: 10000, maxBuffer: 1024 * 1024 });
  if (result.error || result.status !== 0 || !result.stdout?.trim()) return null;
  let identity = result.stdout.trim();
  if (platform === 'win32') {
    try {
      const value = JSON.parse(identity.replace(/^\uFEFF/, ''));
      if (value.pid !== pid || !value.created || !value.executable || !value.command) return null;
      identity = JSON.stringify([value.pid, value.created, value.executable, value.command]);
    } catch { return null; }
  }
  // Existing macOS service records stored ps output verbatim; preserve that
  // format so upgrading the connector can still identify its own live service.
  return platform === 'win32' ? createHash('sha256').update(identity).digest('hex') : identity;
}

function localPipe(value, label) {
  if (typeof value !== 'string' || !/^\\\\\.\\pipe\\[^\0\r\n]+$/i.test(value) || value.slice(9).split('\\').some(part => !part || part === '.' || part === '..')) {
    throw new Error(`${label} 必须是本机 Windows 命名管道（\\\\.\\pipe\\…）`);
  }
  return value;
}

export async function findDesktopEndpoints({ platform = process.platform, env = process.env, codexHome, home = os.homedir(), readDirectory = readdir, statPath = lstat, uid = process.getuid?.() } = {}) {
  const paths = platformPath(platform);
  if (platform === 'win32') {
    // Static evidence from the inspected desktop package: Fc() uses this exact
    // IPC pipe; Moe() makes a random tools pipe and publishes it in this env var.
    // Never enumerate the Windows pipe namespace or choose another app's pipe.
    if (!env.CODEX_APP_TOOLS_PIPE_PATH) throw new Error('未收到 Codex 工具管道。请在已安装并登录的 Codex 桌面应用内置终端执行连接命令；不要在普通 PowerShell 或 WSL 终端执行。');
    return {
      ipcPath: localPipe(env.POCKET_CODEX_IPC_PATH || '\\\\.\\pipe\\codex-ipc', 'Codex IPC'),
      toolsPath: localPipe(env.CODEX_APP_TOOLS_PIPE_PATH, 'Codex 工具管道'),
      source: env.POCKET_CODEX_IPC_PATH ? 'explicit-environment' : 'desktop-convention-and-environment',
    };
  }
  const ipcPath = env.POCKET_CODEX_IPC_PATH || paths.join(codexHome || env.CODEX_HOME || paths.join(home, '.codex'), 'ipc', 'ipc.sock');
  if (!paths.isAbsolute(ipcPath)) throw new Error('Codex IPC socket must be an absolute path');
  if (env.CODEX_APP_TOOLS_PIPE_PATH) {
    if (!paths.isAbsolute(env.CODEX_APP_TOOLS_PIPE_PATH)) throw new Error('Codex tools socket must be an absolute path');
    return { ipcPath, toolsPath: env.CODEX_APP_TOOLS_PIPE_PATH, source: 'environment' };
  }
  const directory = '/tmp/codex-app-tools', sockets = [];
  let names;
  try { names = await readDirectory(directory); }
  catch { throw new Error('未找到 Codex 工具连接，请在 Codex 内置终端启动连接器'); }
  for (const name of names) {
    if (!name.endsWith('.sock') || paths.basename(name) !== name) continue;
    const candidate = paths.join(directory, name);
    try { const stat = await statPath(candidate); if (stat.isSocket() && uid != null && stat.uid === uid) sockets.push(candidate); } catch { /* A stale socket may disappear during discovery. */ }
  }
  if (sockets.length !== 1) throw new Error('Set CODEX_APP_TOOLS_PIPE_PATH to the running desktop socket');
  return { ipcPath, toolsPath: sockets[0], source: 'owned-socket' };
}

export function windowsConnectionCommand(root, state, contextThreadId = '') {
  if (contextThreadId && !/^[a-f0-9-]{36}$/i.test(contextThreadId)) throw new Error('聊天 ID 格式无效');
  if (!path.win32.isAbsolute(root) || !path.win32.isAbsolute(state)) throw new Error('Windows connection paths must be absolute');
  return `$env:POCKET_STATE_DIR=${psQuote(state)};${contextThreadId ? ` $env:CODEX_DESKTOP_CONTEXT_THREAD_ID=${psQuote(contextThreadId)};` : ''} & "$env:SystemRoot\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File ${psQuote(path.win32.join(root, 'scripts', 'connect-installed.ps1'))}`;
}
