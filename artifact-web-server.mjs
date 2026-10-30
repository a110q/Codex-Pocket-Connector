import http from 'node:http';
import { randomBytes } from 'node:crypto';
import { createReadStream, realpathSync, statSync } from 'node:fs';
import { extname, isAbsolute, relative, resolve, sep } from 'node:path';

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.htm': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.csv': 'text/csv; charset=utf-8', '.txt': 'text/plain; charset=utf-8',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.webp': 'image/webp', '.svg': 'image/svg+xml', '.ico': 'image/x-icon',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.otf': 'font/otf', '.wasm': 'application/wasm', '.pdf': 'application/pdf',
};

// A temporary loopback origin lets the existing encrypted browser tunnel load
// HTML and its relative assets. It never exposes the project on the LAN.
export class ArtifactWebServer {
  async start({ path, root }) {
    this.root = realpathSync(root);
    const file = realpathSync(path), name = relative(this.root, file);
    if (!name || name.startsWith('..' + sep) || isAbsolute(name) || !['.html', '.htm'].includes(extname(file).toLowerCase())) throw new Error('此文件不能作为 HTML 页面预览');
    this.token = randomBytes(24).toString('hex');
    this.prefix = `/_pocket/${this.token}/`;
    this.cookie = `pocket_preview_${this.token.slice(0, 12)}=${this.token}`;
    this.server = http.createServer((req, res) => this.serve(req, res));
    await new Promise((accept, reject) => { this.server.once('error', reject); this.server.listen(0, '127.0.0.1', accept); });
    this.server.unref();
    this.origin = `http://127.0.0.1:${this.server.address().port}`;
    this.url = this.origin + this.prefix + name.split(sep).map(encodeURIComponent).join('/');
    return this.url;
  }
  owns(url) { return url.origin === this.origin && url.pathname.startsWith(this.prefix); }
  serve(req, res) {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405).end(); return; }
    try {
      const url = new URL(req.url, 'http://localhost');
      const hasToken = url.pathname.startsWith(this.prefix);
      const hasCookie = (req.headers.cookie ?? '').split(';').some(cookie => cookie.trim() === this.cookie);
      if (!hasToken && !hasCookie) { res.writeHead(403).end('预览访问已失效，请重新打开文件'); return; }
      const name = decodeURIComponent(hasToken ? url.pathname.slice(this.prefix.length) : url.pathname.slice(1));
      const segments = name.split(/[\\/]/);
      if (segments.some(segment => segment.startsWith('.') || segment.includes('\0'))) { res.writeHead(403).end(); return; }
      const file = realpathSync(resolve(this.root, name)), inside = relative(this.root, file);
      if (!inside || inside.startsWith('..' + sep) || isAbsolute(inside) || inside.split(sep).some(segment => segment.startsWith('.'))) { res.writeHead(403).end(); return; }
      const mime = TYPES[extname(file).toLowerCase()];
      if (!mime) { res.writeHead(403).end(); return; }
      const stat = statSync(file);
      if (!stat.isFile() || stat.size > 50 * 1024 * 1024) { res.writeHead(413).end(); return; }
      res.setHeader('Content-Type', mime);
      res.setHeader('Content-Length', stat.size);
      if (hasToken) res.setHeader('Set-Cookie', `${this.cookie}; HttpOnly; SameSite=Strict; Path=/`);
      res.writeHead(200);
      if (req.method === 'HEAD') res.end();
      else createReadStream(file).on('error', () => res.destroy()).pipe(res);
    } catch { res.writeHead(404).end('文件不存在或已移动'); }
  }
  close() { this.server?.closeAllConnections(); this.server?.close(); }
}
