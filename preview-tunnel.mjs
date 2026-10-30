import net from 'node:net';
import { lookup } from 'node:dns/promises';
import { randomUUID } from 'node:crypto';
import { gzipSync } from 'node:zlib';
import { ArtifactWebServer } from './artifact-web-server.mjs';

const LIMIT = 512 * 1024;
function privateAddress(address) {
  if (net.isIP(address) === 6) return !/^[23][0-9a-f]{0,3}:/i.test(address);
  const [a,b,c] = address.split('.').map(Number);
  return a === 0 || a === 10 || a === 127 || a >= 224 || (a === 100 && b >= 64 && b <= 127)
    || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31)
    || (a === 192 && (b === 168 || (b === 0 && (c === 0 || c === 2))))
    || (a === 198 && (b === 18 || b === 19 || (b === 51 && c === 100)))
    || (a === 203 && b === 0 && c === 113);
}

// A WebView HTTP proxy transports complete TCP streams, so forms, cookies,
// JavaScript, redirects, HTTPS certificate validation and WebSockets keep their
// browser semantics. No website content is terminated at the public relay.
export class PreviewTunnel {
  constructor() { this.session = null; this.sockets = new Map(); }
  open({ url }) {
    const target = new URL(url);
    if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password) throw new Error('预览地址必须是 HTTP 或 HTTPS');
    if (Number(target.port) === 43785) throw new Error('配对管理端口不能通过网页预览访问');
    const fileServer = this.fileServer?.owns(target) ? this.fileServer : null;
    if (fileServer) this.fileServer = null;
    this.close();
    this.fileServer = fileServer;
    this.session = { id: randomUUID(), host: target.hostname.replace(/^\[|\]$/g, ''), port: Number(target.port || (target.protocol === 'https:' ? 443 : 80)), touched: Date.now() };
    return { previewId: this.session.id, url: target.href };
  }
  async openFile(file) {
    this.close();
    const generation = this.generation;
    const server = new ArtifactWebServer();
    try {
      const url = await server.start(file);
      if (generation !== this.generation) throw new Error('文件预览已取消');
      this.fileServer = server;
      return { url };
    } catch (error) { server.close(); throw error; }
  }
  check(args) {
    if (!this.session || args.previewId !== this.session.id || Date.now() - this.session.touched > 30 * 60_000) throw new Error('网页预览已断开，请重新打开');
    this.session.touched = Date.now();
  }
  async connect(args) {
    this.check(args);
    const { host, port } = args;
    if (typeof host !== 'string' || host.length > 253 || !Number.isInteger(port) || port < 1 || port > 65535 || port === 43785) throw new Error('无效的预览目标');
    if (this.sockets.size >= 16) throw new Error('网页连接过多，请关闭其他页面后重试');
    const explicit = host === this.session.host && port === this.session.port;
    if (!explicit && port !== 80 && port !== 443) throw new Error('请在地址栏打开这个本地服务后重试');
    const addresses = await lookup(host, { all: true });
    this.check(args);
    // Recheck after DNS: concurrent lookups must not all reserve the last slot.
    if (this.sockets.size >= 16) throw new Error('网页连接过多，请关闭其他页面后重试');
    if (!explicit && addresses.some(item => privateAddress(item.address))) throw new Error('跨站访问其他内网服务被拒绝，请直接打开其地址');
    if (!addresses.length) throw new Error('找不到网页地址');
    const id = randomUUID();
    const socket = net.createConnection({ host: addresses[0].address, port });
    const state = { socket, queue: [], size: 0, ended: false, error: null, waiting: null };
    this.sockets.set(id, state);
    socket.setTimeout(120_000, () => socket.destroy(new Error('网页连接空闲超时')));
    socket.on('data', data => { state.queue.push(data); state.size += data.length; if (state.size >= LIMIT) socket.pause(); state.waiting?.(); });
    socket.on('end', () => { state.ended = true; state.waiting?.(); });
    socket.on('close', () => { state.ended = true; state.waiting?.(); });
    socket.on('error', error => { state.error = error.message; state.ended = true; state.waiting?.(); });
    try {
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => socket.destroy(new Error('连接网页超时')), 10_000);
        socket.once('connect', () => { clearTimeout(timer); resolve(); });
        socket.once('error', error => { clearTimeout(timer); reject(error); });
      });
      this.check(args); return { socketId: id };
    } catch (error) { socket.destroy(); this.sockets.delete(id); throw error; }
  }
  state(args) { this.check(args); const state = this.sockets.get(args.socketId); if (!state) throw new Error('网页连接已关闭'); return state; }
  async write(args) {
    const state = this.state(args);
    if (typeof args.data !== 'string' || args.data.length > 256 * 1024) throw new Error('网页数据过大');
    const data = Buffer.from(args.data, 'base64');
    if (state.ended) throw new Error(state.error || '网页连接已关闭');
    await new Promise((resolve, reject) => state.socket.write(data, error => error ? reject(error) : resolve()));
    return { written: data.length };
  }
  async read(args) {
    const state = this.state(args);
    if (state.waiting) throw new Error('同一网页连接不能同时读取');
    if (!state.size && !state.ended) await new Promise(resolve => {
      const timer = setTimeout(() => { state.waiting = null; resolve(); }, 800);
      state.waiting = () => { clearTimeout(timer); state.waiting = null; resolve(); };
    });
    const data = Buffer.concat(state.queue, state.size), out = data.subarray(0, 192 * 1024), rest = data.subarray(out.length);
    state.queue = rest.length ? [rest] : []; state.size = rest.length;
    if (state.size < LIMIT) state.socket.resume();
    const done = state.ended && !state.size;
    if (done) this.sockets.delete(args.socketId);
    // Compress inside the encrypted channel, preserving the website's exact TCP
    // bytes (including TLS/WebSockets). Older phones keep receiving raw chunks.
    const zipped = args.encoding === 'gzip' && out.length >= 1024 ? gzipSync(out, { level: 1 }) : null;
    const compressed = zipped && zipped.length < out.length * 0.9;
    return { data: (compressed ? zipped : out).toString('base64'), ...(compressed ? { encoding: 'gzip' } : {}), done, error: state.error };
  }
  closeSocket(args) { const state = this.state(args); state.socket.destroy(); state.waiting?.(); this.sockets.delete(args.socketId); return { closed: true }; }
  close() { this.generation = (this.generation ?? 0) + 1; this.fileServer?.close(); this.fileServer = null; for (const state of this.sockets.values()) { state.socket.destroy(); state.waiting?.(); } this.sockets.clear(); this.session = null; return { closed: true }; }
  async call(action, args) {
    if (action === 'preview.open') return this.open(args);
    if (action === 'preview.close') { this.check(args); return this.close(); }
    if (action === 'preview.connect') return this.connect(args);
    if (action === 'preview.write') return this.write(args);
    if (action === 'preview.read') return this.read(args);
    if (action === 'preview.disconnect') return this.closeSocket(args);
    throw new Error('Unsupported preview request');
  }
}
