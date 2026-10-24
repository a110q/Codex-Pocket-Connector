import { randomBytes, randomUUID, createHash, hkdfSync, createCipheriv, createDecipheriv, timingSafeEqual } from 'node:crypto';

export const PROTOCOL = 1;
export const MAX_FRAME = 2 * 1024 * 1024;
export const secret = () => randomBytes(32).toString('base64url');
export const digest = value => createHash('sha256').update(value).digest('hex');
export function equalHash(a, b) {
  return typeof a === 'string' && typeof b === 'string' && a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b));
}
export function checkSecret(value) { return typeof value === 'string' && /^[A-Za-z0-9_-]{43}$/.test(value); }
export function checkHost(value) { return typeof value === 'string' && /^[A-Za-z0-9_-]{16,64}$/.test(value); }

// Standard HKDF-SHA256 and AES-256-GCM; separate keys for the two directions.
export function deriveKey(pairKey, hostId, direction) {
  return Buffer.from(hkdfSync('sha256', Buffer.from(pairKey, 'base64url'), Buffer.from(hostId), Buffer.from(`codex-pocket/v1/${direction}`), 32));
}
export class CipherChannel {
  constructor(pairKey, hostId, role) {
    if (!checkSecret(pairKey) || !checkHost(hostId) || !['desktop', 'mobile'].includes(role)) throw new Error('Invalid channel configuration');
    this.hostId = hostId;
    this.outgoing = role === 'desktop' ? 'desktop-to-mobile' : 'mobile-to-desktop';
    this.incoming = role === 'desktop' ? 'mobile-to-desktop' : 'desktop-to-mobile';
    this.sendKey = deriveKey(pairKey, hostId, this.outgoing);
    this.receiveKey = deriveKey(pairKey, hostId, this.incoming);
    this.sid = randomUUID(); this.seq = 0; this.received = new Map();
  }
  aad(direction, sid, seq) { return Buffer.from(`codex-pocket/v1|${this.hostId}|${direction}|${sid}|${seq}`); }
  encrypt(payload) {
    const seq = ++this.seq, iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.sendKey, iv);
    cipher.setAAD(this.aad(this.outgoing, this.sid, seq));
    const content = Buffer.concat([cipher.update(JSON.stringify(payload)), cipher.final(), cipher.getAuthTag()]);
    if (content.length > MAX_FRAME - 1024) throw new Error('Message is too large');
    return { v: 1, sid: this.sid, seq, iv: iv.toString('base64url'), data: content.toString('base64url') };
  }
  decrypt(frame) {
    if (frame?.v !== 1 || typeof frame.sid !== 'string' || frame.sid.length > 64 || !Number.isSafeInteger(frame.seq) || frame.seq < 1) throw new Error('Invalid encrypted frame');
    if ((this.received.get(frame.sid) ?? 0) >= frame.seq) throw new Error('Replayed frame');
    const iv = Buffer.from(frame.iv ?? '', 'base64url'), data = Buffer.from(frame.data ?? '', 'base64url');
    if (iv.length !== 12 || data.length < 16 || data.length > MAX_FRAME) throw new Error('Invalid ciphertext');
    const cipher = createDecipheriv('aes-256-gcm', this.receiveKey, iv);
    cipher.setAAD(this.aad(this.incoming, frame.sid, frame.seq));
    cipher.setAuthTag(data.subarray(-16));
    const plaintext = Buffer.concat([cipher.update(data.subarray(0, -16)), cipher.final()]);
    const payload = JSON.parse(plaintext.toString('utf8'));
    this.received.set(frame.sid, frame.seq);
    if (this.received.size > 64) this.received.delete(this.received.keys().next().value);
    return payload;
  }
}

export const ACTIONS = new Set(['threads.list', 'projects.list', 'models.list', 'thread.read', 'thread.history', 'message.read', 'thread.create', 'message.send', 'turn.stop', 'approval.respond', 'file.read', 'thread.settings.update', 'thread.archive', 'thread.unarchive', 'thread.delete', 'preview.open', 'preview.close', 'preview.connect', 'preview.write', 'preview.read', 'preview.disconnect']);
export const MUTATIONS = new Set(['thread.create', 'message.send', 'turn.stop', 'approval.respond', 'thread.settings.update', 'thread.archive', 'thread.unarchive', 'thread.delete']);
export function validateCommand(command) {
  if (!command || !/^[a-f0-9-]{36}$/i.test(command.id ?? '') || !ACTIONS.has(command.action)) throw new Error('Unsupported request');
  if (!Number.isFinite(command.sentAt) || Math.abs(Date.now() - command.sentAt) > 5 * 60_000) throw new Error('请求已过期，请重新操作');
  if (!command.args || typeof command.args !== 'object' || Array.isArray(command.args)) throw new Error('Invalid request arguments');
  if (command.action === 'message.send' || command.action === 'thread.create') {
    if (typeof command.args.text !== 'string' || !command.args.text.trim() || command.args.text.length > 100_000) throw new Error('消息不能为空或过长');
  }
  if (['thread.read','thread.history','message.read','message.send','turn.stop','approval.respond','file.read','thread.settings.update','thread.archive','thread.unarchive','thread.delete'].includes(command.action) && typeof command.args.threadId !== 'string') throw new Error('Missing thread');
  return command;
}
