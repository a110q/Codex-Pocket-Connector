import { mkdirSync, existsSync, readFileSync, writeFileSync, statSync, readdirSync, rmSync, renameSync, appendFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { digest } from './protocol.mjs';

const MAX_IMAGE = 10 * 1024 * 1024, MAX_CHUNK = 192 * 1024;
const extensions = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp', 'image/gif': 'gif' };
function matches(data, mime) {
  return mime === 'image/png' ? data.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex')) :
    mime === 'image/jpeg' ? data[0] === 255 && data[1] === 216 && data[2] === 255 :
    mime === 'image/webp' ? data.toString('ascii', 0, 4) === 'RIFF' && data.toString('ascii', 8, 12) === 'WEBP' :
    mime === 'image/gif' && /^GIF8[79]a$/.test(data.toString('ascii', 0, 6));
}

// Only opaque upload IDs cross the encrypted channel. Never accept a phone's
// filesystem path as a Codex attachment. Finished images remain for chat history.
export class ImageUploads {
  constructor(directory) { this.directory = resolve(directory); mkdirSync(this.directory, { recursive: true, mode: 0o700 }); this.prune(); }
  path(id, suffix) { if (!/^[a-f0-9-]{36}$/i.test(id ?? '')) throw Error('图片上传标识无效'); return resolve(this.directory, `${id}.${suffix}`); }
  load(id) { try { return JSON.parse(readFileSync(this.path(id, 'json'), 'utf8')); } catch { throw Error('图片上传已失效，请重新选择图片'); } }
  save(meta) { const file = this.path(meta.id, 'json'); writeFileSync(`${file}.tmp`, JSON.stringify(meta), { mode: 0o600 }); renameSync(`${file}.tmp`, file); }
  prune() {
    for (const name of readdirSync(this.directory).filter(n => /^[a-f0-9-]{36}\.json$/i.test(n))) {
      const meta = this.load(name.slice(0, -5));
      if (!meta.done && Date.now() - meta.createdAt > 24 * 3600_000) this.cancel({ uploadId: meta.id });
    }
  }
  start({ uploadId = randomUUID(), threadId, size, mime, sha256 }) {
    this.path(uploadId, 'json');
    if (typeof threadId !== 'string' || !threadId || threadId.length > 200 || !Number.isSafeInteger(size) || size < 12 || size > MAX_IMAGE || !extensions[mime] || !/^[a-f0-9]{64}$/.test(sha256 ?? '')) throw Error('图片格式或大小无效（支持 PNG、JPEG、WebP、GIF，单张最多 10 MB）');
    if (existsSync(this.path(uploadId, 'json'))) {
      const old = this.load(uploadId);
      if (old.threadId !== threadId || old.size !== size || old.mime !== mime || old.sha256 !== sha256) throw Error('图片上传标识已用于其他图片');
      return { uploadId, nextOffset: statSync(this.path(uploadId, old.done ? extensions[mime] : 'part')).size, done: !!old.done };
    }
    this.prune();
    const partials = readdirSync(this.directory).filter(n => n.endsWith('.part'));
    if (partials.length >= 12 || partials.reduce((n, name) => n + statSync(resolve(this.directory, name)).size, 0) + size > 100 * 1024 * 1024) throw Error('未完成的图片过多，请稍后重试');
    writeFileSync(this.path(uploadId, 'part'), Buffer.alloc(0), { mode: 0o600, flag: 'wx' });
    this.save({ id: uploadId, threadId, size, mime, sha256, createdAt: Date.now(), done: false });
    return { uploadId, nextOffset: 0, done: false };
  }
  chunk({ uploadId, offset, data }) {
    const meta = this.load(uploadId), file = this.path(uploadId, meta.done ? extensions[meta.mime] : 'part'), current = statSync(file).size;
    if (!Number.isSafeInteger(offset) || offset < 0 || typeof data !== 'string' || data.length > MAX_CHUNK * 4 / 3 + 4 || !/^[A-Za-z0-9+/]*={0,2}$/.test(data)) throw Error('图片分片无效');
    const bytes = Buffer.from(data, 'base64');
    if (!bytes.length || bytes.length > MAX_CHUNK || offset + bytes.length > meta.size) throw Error('图片分片大小无效');
    if (offset < current && offset + bytes.length <= current && readFileSync(file).subarray(offset, offset + bytes.length).equals(bytes)) return { uploadId, nextOffset: current };
    if (meta.done || offset !== current) throw Error('图片分片顺序不一致，请重新上传');
    appendFileSync(file, bytes); return { uploadId, nextOffset: current + bytes.length };
  }
  finish({ uploadId }) {
    const meta = this.load(uploadId), file = this.path(uploadId, meta.done ? extensions[meta.mime] : 'part');
    const bytes = readFileSync(file);
    if (bytes.length !== meta.size || digest(bytes) !== meta.sha256 || !matches(bytes, meta.mime)) throw Error('图片完整性校验失败，请重新选择图片');
    if (!meta.done) { renameSync(file, this.path(uploadId, extensions[meta.mime])); meta.done = true; this.save(meta); }
    return { uploadId, done: true };
  }
  cancel({ uploadId }) {
    const meta = this.load(uploadId);
    if (meta.done) return { done: true };
    rmSync(this.path(uploadId, 'part'), { force: true }); rmSync(this.path(uploadId, 'json'), { force: true }); return { cancelled: true };
  }
  resolve(ids = [], threadId) {
    if (!Array.isArray(ids) || ids.length > 4 || new Set(ids).size !== ids.length) throw Error('每条消息最多添加 4 张图片');
    return ids.map(id => { const meta = this.load(id); if (!meta.done || meta.threadId !== threadId) throw Error('图片未上传完成或不属于当前对话'); return this.path(id, extensions[meta.mime]); });
  }
  call(action, args) { const method = action.replace('image.upload.', ''); if (!['start', 'chunk', 'finish', 'cancel'].includes(method)) throw Error('不支持的图片操作'); return this[method](args); }
}
