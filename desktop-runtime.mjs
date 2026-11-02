import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import os from 'node:os';
import { FramedSocket, applyPatches } from './desktop-adapter.mjs';

// A native launcher is not an App Tools caller. Observe the existing desktop
// owner/follower channel instead. Retain only runtime metadata, never histories.
export class DesktopRuntime extends EventEmitter {
  constructor(options = {}) {
    super(); this.options = options; this.watches = new Map(); this.missing = new Map(); this.statuses = new Map(); this.generation = 0;
  }
  async connect() {
    if (this.ready) return;
    if (this.connecting) return this.connecting;
    const generation = this.generation;
    this.connecting = (async () => {
      const socketPath = this.options.ipcPath ?? process.env.POCKET_CODEX_IPC_PATH ?? (process.platform === 'win32' ? '\\\\.\\pipe\\codex-ipc' : path.join(this.options.codexHome ?? process.env.CODEX_HOME ?? path.join(os.homedir(), '.codex'), 'ipc/ipc.sock'));
      const ipc = this.options.createRuntimeTransport?.() ?? new FramedSocket(socketPath, 2500); this.ipc = ipc;
      ipc.on('message', message => { if (this.ipc === ipc) this.handle(message); });
      ipc.on('disconnect', () => { if (this.ipc === ipc) { this.ready = false; this.reset(); this.emit('unavailable'); } });
      await ipc.connect();
      const result = await ipc.exchange({ type: 'request', requestId: randomUUID(), sourceClientId: 'initializing-client', version: 0, method: 'initialize', params: { clientType: 'desktop' } });
      if (generation !== this.generation) throw new Error('桌面状态连接已取消');
      if (result.resultType !== 'success') throw new Error('桌面状态连接失败');
      this.clientId = result.result.clientId; this.ready = true;
    })();
    try { await this.connecting; } finally { this.connecting = null; }
  }
  follow(id, watch, following = true) {
    this.ipc.write({ type: 'broadcast', sourceClientId: this.clientId, method: 'thread-stream-following-changed', version: 1,
      params: { hostId: 'local', conversationId: id, following }, targetClientIds: [watch.owner] });
  }
  publish(id, status) {
    if (!status || typeof status.type !== 'string') return;
    const value = { status: status.type, threadRuntimeStatus: structuredClone(status) };
    if (JSON.stringify(this.statuses.get(id)) === JSON.stringify(value)) return;
    this.statuses.set(id, value); this.emit('changed', new Map(this.statuses));
  }
  handle(message) {
    if (message.type === 'client-discovery-request') {
      this.ipc.write({ type: 'client-discovery-response', requestId: message.requestId, response: { canHandle: false } }); return;
    }
    if (message.type !== 'broadcast') return;
    const p = message.params;
    if (message.method === 'client-status-changed' && p?.status === 'disconnected') {
      for (const [id, watch] of this.watches) if (watch.owner === p.clientId) {
        this.watches.delete(id); watch.resolve(); this.missing.delete(id); this.publish(id, { type: 'notLoaded' });
      }
      return;
    }
    if (p?.hostId !== 'local') return;
    const watch = this.watches.get(p.conversationId);
    if (!watch || watch.owner !== message.sourceClientId) return;
    if (message.method === 'thread-stream-following-status-requested' && message.version === 1) { this.follow(p.conversationId, watch); return; }
    if (message.method !== 'thread-stream-state-changed') return;
    if (message.version !== 11) { this.close(); this.emit('unavailable'); return; }
    const c = p.change;
    if (!Number.isSafeInteger(c?.revision) || (watch.revision != null && c.revision <= watch.revision)) return;
    try {
      if (c.type === 'snapshot') {
        watch.state = { threadRuntimeStatus: c.conversationState?.threadRuntimeStatus }; watch.resync = false;
      } else if (c.type === 'patches' && watch.revision === c.baseRevision && !watch.resync) {
        // Structural revisions still advance for text-only changes, but their
        // contents are discarded. Nested activeFlags patches remain supported.
        watch.state = applyPatches(watch.state, (c.patches ?? []).filter(patch => patch.path?.[0] === 'threadRuntimeStatus'));
      } else { if (!watch.resync) { watch.resync = true; this.follow(p.conversationId, watch); } return; }
      watch.revision = c.revision; this.publish(p.conversationId, watch.state.threadRuntimeStatus); watch.resolve();
    } catch { if (!watch.resync) { watch.resync = true; this.follow(p.conversationId, watch); } }
  }
  async read(catalog) {
    await this.connect(); const generation = this.generation, ipc = this.ipc;
    const ids = new Set(catalog.threads.filter(t => !t.archived && !t.isSubagent && (t.kind ?? 'codex') === 'codex' && (t.hostId ?? 'local') === 'local').map(t => t.id));
    for (const [id, watch] of this.watches) if (!ids.has(id)) { this.follow(id, watch, false); this.watches.delete(id); this.statuses.delete(id); watch.resolve(); }
    for (const id of this.statuses.keys()) if (!ids.has(id)) this.statuses.delete(id);
    for (const id of this.missing.keys()) if (!ids.has(id)) this.missing.delete(id);
    const candidates = [...ids].filter(id => !this.watches.has(id) && Date.now() >= (this.missing.get(id) ?? 0));
    let index = 0; const pending = [];
    await Promise.all(Array.from({ length: Math.min(6, candidates.length) }, async () => {
      while (index < candidates.length && generation === this.generation) {
        const id = candidates[index++];
        const result = await ipc.exchange({ type: 'request', requestId: randomUUID(), sourceClientId: this.clientId, method: 'thread-owner-discovery', version: 1, params: { hostId: 'local', conversationId: id } });
        if (generation !== this.generation) return;
        if (result.resultType !== 'success' || !result.handledByClientId) { this.missing.set(id, Date.now() + 2000); continue; }
        let resolve; const first = new Promise(done => { resolve = done; });
        const watch = { owner: result.handledByClientId, revision: null, state: {}, resolve }; this.watches.set(id, watch);
        this.follow(id, watch); pending.push(first);
      }
    }));
    if (pending.length) {
      let timer;
      try { await Promise.race([Promise.all(pending), new Promise(resolve => { timer = setTimeout(resolve, this.options.runtimeSnapshotTimeoutMs ?? 10000); })]); }
      finally { clearTimeout(timer); }
    }
    // A missed initial frame is retried without retaining a conversation body.
    for (const [id, watch] of this.watches) if (watch.revision == null && generation === this.generation) this.follow(id, watch);
    return new Map(this.statuses);
  }
  reset() {
    this.generation++; for (const watch of this.watches.values()) watch.resolve(); this.watches.clear(); this.missing.clear(); this.statuses.clear();
  }
  close() {
    if (this.ready) for (const [id, watch] of this.watches) { try { this.follow(id, watch, false); } catch {} }
    this.ready = false; const ipc = this.ipc; this.ipc = null; this.reset(); ipc?.close();
  }
}
