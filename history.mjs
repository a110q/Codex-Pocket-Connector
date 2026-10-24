import { digest } from './protocol.mjs';

const PAGE_BYTES = 384 * 1024;
const keyOf = message => message.key ?? `${message.turnId}:${message.id}`;
const storeKey = args => JSON.stringify([args.hostId ?? 'local', args.threadId]);
function compact(message) {
  const text = String(message.text ?? ''), detail = message.detail ?? text;
  return { id: message.id, key: keyOf(message), turnId: message.turnId, order: message.order, role: message.role, type: message.type, status: message.status, title: message.title, tool: message.tool,
    text: text.slice(0, 4000), attachments: message.attachments, textComplete: text.length <= 4000, textLength: text.length, sourceTruncated: message.sourceTruncated === true, contentVersion: digest(text), detailVersion: digest(detail), hasDetail: message.detail != null, relatedThreads: message.relatedThreads, command: message.command?.slice(0, 500), exitCode: message.exitCode };
}

// Keep complete desktop snapshots locally; only bounded, explicitly paged
// records cross the relay. No text or tool result is silently discarded.
export class HistoryStore {
  constructor() { this.snapshots = new Map(); }
  put(snapshot) {
    const key = storeKey({ hostId: snapshot.thread.hostId, threadId: snapshot.thread.id });
    this.snapshots.delete(key); this.snapshots.set(key, snapshot);
    while (this.snapshots.size > 4) this.snapshots.delete(this.snapshots.keys().next().value);
    return this.page({ hostId: snapshot.thread.hostId, threadId: snapshot.thread.id });
  }
  get(args) {
    const snapshot = this.snapshots.get(storeKey(args));
    if (!snapshot) throw new Error('会话缓存已更新，请重新打开对话');
    return snapshot;
  }
  page(args) {
    const snapshot = this.get(args), all = snapshot.messages ?? [];
    let end = all.length;
    if (args.before != null) {
      end = all.findIndex(message => keyOf(message) === args.before);
      if (end < 0) throw new Error('历史位置已变化，请刷新会话');
    }
    const messages = []; let bytes = 0, start = end;
    while (start > 0 && messages.length < 40) {
      const message = compact(all[start - 1]); const size = Buffer.byteLength(JSON.stringify(message));
      if (messages.length && bytes + size > PAGE_BYTES) break;
      messages.unshift(message); bytes += size; start--;
    }
    // Diffs are part of the timeline; their full form is read via message.read.
    return { ...snapshot, messages, diffs: [], history: { total: all.length, hasEarlier: start > 0, before: messages[0]?.key ?? null, complete: snapshot.historyComplete === true }, truncated: false };
  }
  read(args) {
    const message = this.get(args).messages.find(message => keyOf(message) === args.messageKey);
    if (!message) throw new Error('记录已变化，请刷新会话');
    const mode = args.mode ?? 'text';
    if (!['text', 'detail'].includes(mode)) throw new Error('Invalid content mode');
    const text = String(mode === 'detail' ? message.detail ?? message.text ?? '' : message.text ?? '');
    const version = digest(text);
    if (args.version !== version) throw new Error('内容正在更新，请关闭详情后重新打开');
    const offset = args.offset ?? 0;
    if (!Number.isSafeInteger(offset) || offset < 0 || offset > text.length) throw new Error('Invalid content offset');
    let end = Math.min(offset + 24000, text.length);
    if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1])) end--;
    return { text: text.slice(offset, end), nextOffset: end, total: text.length, done: end === text.length, version };
  }
}
