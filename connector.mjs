import http from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { hostname } from 'node:os';
import { Enrollment, ConnectorAccount, connectorPaths, readJson, writePrivate, validateDefaults, connectionCommand, companionStatus, missingCapabilities, diagnose } from './connector-core.mjs';
import { openBrowser } from './platform.mjs';
import { lanAddresses, prepareLan } from './lan.mjs';
import { secret, digest, equalHash } from './protocol.mjs';
import { CodexInstallation } from './codex-installation.mjs';
import { ConnectorRuntime } from './connector-runtime.mjs';

const ROOT = dirname(fileURLToPath(import.meta.url));
const hashScript = html => createHash('sha256').update(html.match(/<script>([\s\S]*?)<\/script>/)[1]).digest('base64');
export async function startConnector({ paths = connectorPaths(), defaults, port = 43786, managerPort = 43787, root = ROOT, request, status = companionStatus, runtime = new ConnectorRuntime(), installation = new CodexInstallation(paths.state), onShutdown } = {}) {
  const trusted = validateDefaults(defaults);
  const enrollment = new Enrollment({ paths, defaults: trusted, ...(request ? { request } : {}) });
  const account = new ConnectorAccount({ paths, defaults: trusted, ...(request ? { request } : {}) });
  const token = secret();
  const mode=()=>existsSync(paths.setup)&&readJson(paths.setup).mode==='lan'?'lan':'cloud';
  const activePaths=()=>mode()==='lan'?connectorPaths(resolve(paths.state,'lan')):paths;
  const currentPort = config => runtime.port(config) ?? managerPort;
  const currentStatus = config => status(config, currentPort(config));
  const managerRequest = async (path, body) => {
    const config = readJson(activePaths().config);
    const response = await fetch(`http://127.0.0.1:${currentPort(config)}${path}`, { method: body ? 'POST' : 'GET', headers: { Authorization: `Bearer ${config.managerToken}`, ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(5000) });
    const data = await response.json(); if (!response.ok) throw new Error(data.error || '配对服务尚未就绪'); return data;
  };
  const html = readFileSync(resolve(root, 'connector.html'), 'utf8');
  const origin = () => `http://127.0.0.1:${server.address().port}`;
  const server = http.createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store'); res.setHeader('X-Content-Type-Options', 'nosniff'); res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Content-Security-Policy', `default-src 'self'; script-src 'sha256-${hashScript(html)}'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'`);
    const host = req.headers.host;
    if (host !== `127.0.0.1:${server.address().port}` && host !== `localhost:${server.address().port}`) { res.writeHead(403).end(); return; }
    if (req.headers.origin && req.headers.origin !== origin() && req.headers.origin !== `http://localhost:${server.address().port}`) { res.writeHead(403).end(); return; }
    const url = new URL(req.url, origin());
    if (req.method === 'GET' && url.pathname === '/') { res.setHeader('Content-Type', 'text/html; charset=utf-8'); return res.end(html); }
    if (req.method === 'GET' && url.pathname === '/assets/logo.png') { res.setHeader('Content-Type', 'image/png'); return res.end(readFileSync(resolve(root, 'design/brand/codex-pocket-logo.png'))); }
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    if (!equalHash(digest(req.headers.authorization ?? ''), digest(`Bearer ${token}`))) { res.writeHead(401).end(JSON.stringify({ error: '请重新打开 Codex Pocket 电脑连接器，以获取当前管理链接。' })); return; }
    try {
      if (req.method === 'GET' && url.pathname === '/api/state') {
        const active=activePaths();const config = existsSync(active.config) ? readJson(active.config) : null;
        let current = null;
        if (config) try { current = await currentStatus(config); } catch {}
        const missing = missingCapabilities(current);
        return res.end(JSON.stringify({ version: JSON.parse(readFileSync(resolve(root,'package.json'),'utf8')).version, mode:mode(),lanAddresses:lanAddresses(),platform: process.platform, installation: installation.state(), registered: !!config, name: config?.name ?? hostname(),  connected: !!(current?.desktopReady && current?.relayOnline && !missing.length), serviceRunning: !!current,
          connecting: runtime.busy, mobileOnline: !!current?.mobileOnline, pairRequest: current?.pairRequest ?? null, error: current?.desktopReady && missing.length ? `当前 Codex 版本缺少所需接口：${missing.join('、')}，请更新连接器。` : current?.error || runtime.error || '',  pending: existsSync(active.enrollment) && !readJson(active.enrollment).completed && !readJson(active.enrollment).rejected }));
      }
      if (req.method === 'GET' && url.pathname === '/api/pairing') {
        const active=activePaths();
        if(!existsSync(active.config)) throw new Error('请先连接 Codex');
        const current=await currentStatus(readJson(active.config));
        if(!current?.desktopReady || !current?.relayOnline || missingCapabilities(current).length) throw new Error('请先完成第二步，成功连接 Codex 后再配对手机');
        return res.end(JSON.stringify(await managerRequest('/api/pairing')));
      }
      if (req.method === 'GET' && url.pathname === '/api/diagnostics') return res.end(JSON.stringify(await diagnose({ paths:activePaths(), defaults: trusted, managerPort, status:currentStatus, ...(request ? { request } : {}) })));
      if (req.method !== 'POST') { res.writeHead(404).end('{}'); return; }
      let text = ''; for await (const part of req) { text += part; if (Buffer.byteLength(text) > 4096) throw new Error('输入内容过长'); }
      const body = JSON.parse(text || '{}');
      if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error('输入内容无效');
      if (url.pathname === '/api/mode') {
        if(!['cloud','lan'].includes(body.mode))throw new Error('连接模式无效');
        if(runtime.busy)throw new Error('正在连接，请稍后切换。');
        await runtime.stop();
        if(body.mode==='lan')prepareLan(paths.state,{address:body.address,renew:body.renew===true});
        else await enrollment.prepare({name:body.name});
        writePrivate(paths.setup,{...(existsSync(paths.setup)?readJson(paths.setup):{}),mode:body.mode});
        return res.end(JSON.stringify({mode:body.mode}));
      }
      if (url.pathname === '/api/connect') {
        if (mode() === 'cloud') await enrollment.prepare();
        return res.end(JSON.stringify(await runtime.connect(activePaths(), {binary:installation.binary()})));
      }
      if(url.pathname === '/api/codex/pick') return res.end(JSON.stringify(await installation.pick()));
      if(url.pathname === '/api/codex/installation') {
        if(runtime.busy) throw new Error('正在连接，请稍后更改安装位置');
        const result=await installation.save(body.selectedPath); await runtime.stop();
        return res.end(JSON.stringify(result));
      }
      if (url.pathname === '/api/shutdown' && onShutdown) { res.end(JSON.stringify({stopping:true})); onShutdown(); return; }
      if (url.pathname === '/api/disconnect') { if(runtime.busy)throw new Error('正在连接，请稍候。');await runtime.stop(); return res.end(JSON.stringify({ stopped: true })); }
      if (url.pathname === '/api/pair/decision') return res.end(JSON.stringify(await managerRequest('/api/decision', body)));
      if (url.pathname === '/api/pair/renew') return res.end(JSON.stringify(await managerRequest('/api/reset', {})));
      if (url.pathname === '/api/register') return res.end(JSON.stringify(await enrollment.register(body)));
      if (url.pathname === '/api/login') return res.end(JSON.stringify(await account.login(body)));
      if (url.pathname === '/api/logout') return res.end(JSON.stringify(await account.logout()));
      if (url.pathname === '/api/register-account') {
        const current = await account.state();
        if (!current.loggedIn || current.verified === false || current.user?.accessState !== 'active') throw new Error('请先登录已批准的账号');
        return res.end(JSON.stringify(await enrollment.registerAccount({name:body.name,token:readJson(paths.account).token,user:current.user})));
      }
      if (url.pathname === '/api/command') return res.end(JSON.stringify({ command: connectionCommand(root, activePaths(), body.contextThreadId ?? '') }));
      if (url.pathname === '/api/manager') {
        const active=activePaths();if (!existsSync(active.config)) throw new Error('请先配置电脑');
        const config = readJson(active.config); await currentStatus(config);
        return res.end(JSON.stringify({ url: `http://127.0.0.1:${currentPort(config)}/#${config.managerToken}` }));
      }
      res.writeHead(404).end('{}');
    } catch (e) { res.writeHead(400).end(JSON.stringify({ error: e.message })); }
  });
  server.requestTimeout = 20000;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  return { server, token, url: `${origin()}/#${token}`, runtime, close: async () => { await runtime.stop(); await new Promise(resolve => server.close(resolve)); } };
}

async function main() {
  const native = process.argv.includes('--native');
  const paths = connectorPaths(); const port = native ? 0 : Number(process.env.POCKET_SETUP_PORT ?? 43786), managerPort = Number(process.env.POCKET_MANAGER_PORT ?? 43787);
  if ((!native && (!Number.isInteger(port) || port < 1024 || port > 65535)) || !Number.isInteger(managerPort) || managerPort < 1024 || managerPort > 65535 || port === managerPort) throw new Error('连接器端口无效');
  const open = url => { if (process.env.POCKET_NO_OPEN !== '1') openBrowser(url); };
  if (!native && existsSync(paths.setup)) {
    const previous = readJson(paths.setup);
    if (previous.root === ROOT && previous.port === port) {
      try {
        const r = await fetch(`http://127.0.0.1:${port}/api/state`, { headers: { Authorization: `Bearer ${previous.token}` }, signal: AbortSignal.timeout(1500) });
        if (r.ok) { open(`http://127.0.0.1:${port}/#${previous.token}`); return; }
      } catch {}
    }
  }
  const app = await startConnector({ paths, defaults: readJson(resolve(ROOT, 'connector-defaults.json')), port, managerPort, onShutdown:()=>setTimeout(()=>app.close().then(()=>process.exit(0)),10) });
  writePrivate(paths.setup, { ...(existsSync(paths.setup)?readJson(paths.setup):{}), root: ROOT, port:app.server.address().port, token: app.token, pid: process.pid });
  if (native) {
    const descriptor = process.argv.indexOf('--descriptor');
    if (descriptor >= 0 && process.argv[descriptor + 1]) writePrivate(resolve(process.argv[descriptor + 1]), { event: 'ready', url: app.url });
    else process.stdout.write(JSON.stringify({ event: 'ready', url: app.url })+'\n');
  } else open(app.url);
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => app.close().then(() => process.exit(0)));
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(e => { console.error(e.message); process.exitCode = 1; });
