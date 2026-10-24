import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { processIdentity, openBrowser } from '../platform.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const state = resolve(process.env.POCKET_STATE_DIR ?? (process.env.POCKET_CONFIG ? dirname(process.env.POCKET_CONFIG) : resolve(root, '.private')));
const pidFile = resolve(state, 'desktop-service.json');
const configFile = resolve(process.env.POCKET_CONFIG ?? resolve(state, 'companion.json'));
const port = Number(process.env.POCKET_MANAGER_PORT ?? 43785);
const operation = process.argv[2] ?? 'start';
const identity = processIdentity;
const saved = () => { try { return JSON.parse(readFileSync(pidFile, 'utf8')); } catch { return {}; } };
const owned = record => record.root === root && Number.isSafeInteger(record.pid) && record.identity && identity(record.pid) === record.identity;
const manager = config => {
  if (process.env.POCKET_NO_OPEN !== '1') openBrowser(`http://127.0.0.1:${port}/#${config.managerToken}`);
};
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

try {
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('管理端口无效');
  if (operation === 'status') console.log(owned(saved()) ? '电脑连接服务正在运行。' : '电脑连接服务未运行。');
  else if (operation === 'stop') {
    const record = saved();
    if (owned(record)) {
      // Windows terminates the supervisor without invoking its JS signal handler.
      // Only stop a child whose recorded creation identity still matches.
      if (process.platform === 'win32' && Number.isSafeInteger(record.childPid) && record.childIdentity && identity(record.childPid) === record.childIdentity) process.kill(record.childPid, 'SIGTERM');
      process.kill(record.pid, 'SIGTERM'); console.log('已请求停止本项目的电脑连接服务。');
    }
    else console.log('没有检测到本项目启动的连接服务。');
  } else if (operation === 'manager') manager(JSON.parse(readFileSync(configFile, 'utf8')));
  else if (operation === 'start') {
    const config = JSON.parse(readFileSync(configFile, 'utf8'));
    if (!config.desktopToken) throw new Error('请先打开电脑连接器，登录已批准的账号并绑定电脑');
    if (owned(saved())) {
      console.log('本项目的连接服务已在运行。');
      if (process.argv.includes('--open-manager')) manager(config);
    } else {
      const context = process.env.CODEX_DESKTOP_CONTEXT_THREAD_ID ?? process.env.CODEX_THREAD_ID ?? config.contextThreadId;
      if (!context || !/^[a-f0-9-]{36}$/i.test(context)) throw new Error('缺少 Codex 聊天 ID。请在 Codex 内置终端运行，或在连接器第二步填写聊天 ID 后复制命令。');
      if (config.contextThreadId !== context) {
        config.contextThreadId = context;
        writeFileSync(configFile, JSON.stringify(config, null, 2), { mode: 0o600 });
      }
      mkdirSync(state, { recursive: true, mode: 0o700 });
      console.log('正在连接桌面 Codex。请保持此 Codex 内置终端运行；可将桌面应用最小化。');
      const child = spawn(process.execPath, [resolve(root, 'companion.mjs'), 'start'], { cwd: root, stdio: 'inherit' });
      const record = { root, pid: process.pid, childPid: child.pid, childIdentity: identity(child.pid), identity: identity(process.pid), startedAt: Date.now() };
      writeFileSync(pidFile, JSON.stringify(record), { mode: 0o600 });
      let exited = false, rejected = false;
      child.on('exit', code => {
        exited = true;
        if (saved().pid === process.pid) writeFileSync(pidFile, JSON.stringify({ root, stoppedAt: Date.now() }), { mode: 0o600 });
        process.exitCode = rejected ? 1 : code ?? 0;
      });
      child.on('error', error => { console.error(error.message); exited = true; process.exitCode = 1; });
      for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { if (!exited) child.kill('SIGTERM'); });
      let online = false;
      for (let attempt = 0; attempt < 20 && !exited; attempt++) {
        try {
          const response = await fetch(`http://127.0.0.1:${port}/api/status`, { headers: { Authorization: `Bearer ${config.managerToken}` }, signal: AbortSignal.timeout(1000) });
          const status = await response.json();
          if (status.desktopReady && status.relayOnline) { online = true; console.log('桌面 Codex 和公网中继均已连接。'); break; }
          if (attempt >= 10 && /App tools require|write EPIPE|untrusted-process-ancestry/.test(status.error ?? '')) {
            console.error('桌面应用拒绝此进程来源。请在 Codex 应用的内置终端中运行启动脚本；系统终端和 launchd 暂不支持。');
            rejected = true; child.kill('SIGTERM'); break;
          }
        } catch { /* The child is still opening its local manager. */ }
        await delay(1000);
      }
      if (!online && !exited && !rejected) console.log('尚未完成连接，请在管理页查看错误；电脑连接服务会继续尝试。');
      if (!exited && !rejected && process.argv.includes('--open-manager')) manager(config);
    }
  } else throw new Error('支持 start、stop、status、manager');
} catch (error) { console.error(error.message); process.exitCode = 1; }
