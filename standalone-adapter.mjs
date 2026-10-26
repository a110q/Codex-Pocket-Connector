import { approvalResponse, APPROVAL_METHODS } from './approvals.mjs';
import { ExtensionTools } from './extensions-tools.mjs';
import { WorkspaceTools } from './workspace-tools.mjs';
import { attachmentText } from './file-uploads.mjs';
import path from 'node:path';
import os from 'node:os';
import { randomUUID } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { DesktopAdapter, FramedSocket, normalizeSnapshot, orderedTurns } from './desktop-adapter.mjs';
import { AppServerClient } from './app-server-client.mjs';
import { DesktopOwnerBridge } from './desktop-owner-bridge.mjs';

const modes = { 'read-only': { permissions: ':read-only', approvalPolicy: 'on-request', approvalsReviewer: 'user' }, auto: { permissions: ':workspace', approvalPolicy: 'on-request', approvalsReviewer: 'user' }, 'full-access': { permissions: ':danger-full-access', approvalPolicy: 'never', approvalsReviewer: 'user' } };
const string = (value, label, length = 100000) => { if (typeof value !== 'string' || !value.trim() || value.length > length) throw new Error(`${label}无效`); return value; };
const writerConflict = error => /^thread [\w-]+ already has an active writer[.!]?$/i.test(error.message);
const notSubmitted = error => Object.assign(error, { notSubmitted: true, uncertain: false });

export class StandaloneAdapter extends DesktopAdapter {
  constructor(options = {}) {
    super(options); this.server = options.server ?? new AppServerClient(options);
    this.models = []; this.ownedThreads = new Set(); this.createdThreads = new Set(); this.catalogRefreshTimers = new Map(); this.states = new Map(); this.approvals = new Map(); this.refreshTimers = new Map(); this.ipcAvailable = false;
    this.ipcConnecting = null; this.ownerErrors = new Map(); this.threadQueues = new Map(); this.releaseTimers = new Map(); this.threadServers = new Map();
    this.ownerBridge = new DesktopOwnerBridge(this);
    this.workspace = new WorkspaceTools(this);
    this.extensions = new ExtensionTools(this);
    this.server.on('notification', message => this.onServerEvent(message));
    this.server.on('request', message => this.onServerRequest(message));
    this.server.on('disconnect', error => {
      for (const id of this.ownedThreads) if (!this.threadServers.has(id)) this.ownedThreads.delete(id);
      if (this.ready) { this.ready = false; this.emit('event', { type: 'desktop.disconnected', error: error.message }); }
    });
  }
  async connectDesktop() {
    await this.server.connect();
    try {
      const [models] = await Promise.all([this.server.pages('model/list'), this.server.request('thread/list', { limit: 1, useStateDbOnly: true, modelProviders: [] }), this.server.request('project/list', { limit: 1 })]);
      this.models = models.filter(model => !model.hidden).map(model => ({ id: model.model, reasoningEfforts: model.supportedReasoningEfforts.map(value => value.reasoningEffort) }));
      if (!this.models.length) throw new Error('Codex 尚未提供可用模型，请先登录并配置模型。');
      try { await this.ensureDesktopIpc(); } catch { /* Stored history remains available while the desktop is closed. */ }
      this.ready = true; return this.diagnostics();
    } catch (error) { this.close(); throw error; }
  }
  diagnostics() {
    return { connected: this.ready, backend: 'codex-app-server', desktopWindowConnected: this.ipcAvailable,
      capabilities: Object.fromEntries(['threads.list','projects.list','thread.read','message.send','thread.create','models.list','turn.stop','approval.respond','thread.settings.update','thread.archive','thread.unarchive','thread.delete','thread.rename','workspace.list','workspace.search','workspace.read','git.status','git.diff','git.commit','git.push','git.pr.create','review.start','extensions.list','skill.read','skill.set','skill.save','skill.delete','plugin.list','plugin.install','plugin.uninstall','plugin.set','account.usage','mcp.list','mcp.login'].map(key => [key, true])),
      limitations: ['Local Codex projects and chats use the installed Codex account. Remote hosts and ChatGPT-only conversations are not included.', ...(!this.ipcAvailable ? ['Open Codex to synchronize live desktop-owned conversations; concurrent desktop sessions cannot be controlled while its window is disconnected.'] : [])] };
  }
  modelCatalog() { return this.models; }
  serverFor(threadId) { return this.threadServers.get(threadId) ?? this.server; }
  async createThreadServer() {
    const client = this.options.createThreadServer?.() ?? (this.options.server ? this.server : new AppServerClient(this.options));
    if (client !== this.server) {
      client.on('notification', message => this.onServerEvent(message));
      client.on('request', message => this.onServerRequest(message, client));
      client.on('disconnect', () => {
        for (const [id, server] of this.threadServers) if (server === client) {
          this.threadServers.delete(id); this.ownedThreads.delete(id);
          for (const [key, request] of this.approvals) if (request.params.threadId === id) this.approvals.delete(key);
        }
      });
    }
    await client.connect?.(); return client;
  }
  async ensureDesktopIpc(timeoutMs = 2500) {
    if (this.ipcAvailable) return;
    if (this.ipcConnecting) return this.ipcConnecting;
    this.ipcConnecting = (async () => {
      this.ipc?.close(); this.clearStreamState(); this.ownerBridge.reset();
      const ipcPath = this.options.ipcPath ?? process.env.POCKET_CODEX_IPC_PATH ?? (process.platform === 'win32' ? '\\\\.\\pipe\\codex-ipc' : path.join(this.options.codexHome ?? process.env.CODEX_HOME ?? path.join(os.homedir(), '.codex'), 'ipc/ipc.sock'));
      const transport = new FramedSocket(ipcPath, timeoutMs); this.ipc = transport;
      transport.on('message', message => this.handleIpc(message));
      transport.on('disconnect', () => { if (this.ipc === transport) { this.ipcAvailable = false; this.clearStreamState(); this.ownerBridge.reset(); } });
      try {
        await transport.connect();
        const response = await transport.exchange({ type: 'request', requestId: randomUUID(), sourceClientId: 'initializing-client', version: 0, method: 'initialize', params: { clientType: 'desktop' } });
        if (response.resultType !== 'success') throw new Error('Desktop IPC unavailable');
        this.clientId = response.result.clientId; this.ipcAvailable = true;
        for (const conversationId of this.ownedThreads) this.broadcast('thread-stream-following-status-requested', { hostId: 'local', conversationId });
      } catch (error) { transport.close(); throw error; }
    })();
    try { return await this.ipcConnecting; } finally { this.ipcConnecting = null; }
  }
  handleIpc(message) {
    if (!this.ownerBridge?.handle(message)) super.handleIpc(message);
  }
  announceThread(threadId, { removed = false, cwd } = {}) {
    if (!this.ipcAvailable) return false;
    try {
      // This observed desktop event reloads persisted metadata into its sidebar.
      // It does not write the thread archive state or bypass App Tools trust.
      this.ipc.write({ type: 'broadcast', method: removed ? 'thread-archived' : 'thread-unarchived', params: { hostId: 'local', conversationId: threadId, ...(removed ? { cwd: cwd ?? '/' } : {}) }, sourceClientId: this.clientId, version: removed ? 2 : 1 });
      return true;
    } catch { return false; }
  }
  async listThreads(args = {}) {
    const catalog = await this.localCatalog();
    const values = await this.server.pages('thread/list', { useStateDbOnly: true, modelProviders: [], ...(args.includeArchived ? {} : { archived: false }) });
    if (args.includeArchived) values.push(...(await this.server.pages('thread/list', { useStateDbOnly: true, modelProviders: [], archived: true })).map(value=>({...value,_pocketArchived:true})));
    const threads = new Map(catalog.threads.map(thread => [thread.id, thread]));
    for (const value of values) {
      const old = threads.get(value.id) ?? {};
      threads.set(value.id, { ...old, id: value.id, kind: 'codex', hostId: 'local', title: value.name || old.title || value.preview || '新对话', cwd: value.cwd,
        projectId: old.membershipKnown ? old.projectId : value.projectId, archived: value._pocketArchived ?? old.archived ?? false,
        status: value.status?.type ?? 'idle', model: value.model, reasoningEffort: value.reasoningEffort,
        updatedAt: new Date(value.updatedAt * 1000).toISOString(), recencyAt: new Date((value.recencyAt ?? value.updatedAt) * 1000).toISOString() });
    }
    let result = [...threads.values()].filter(t => (args.includeArchived || !t.archived) && (args.includeSubagents || !t.isSubagent) && (!Object.hasOwn(args, 'projectId') || t.projectId === args.projectId) && (!args.hostId || args.hostId === 'local'));
    result.sort((a,b) => Number(!!b.isPinned) - Number(!!a.isPinned) || (Date.parse(b.recencyAt ?? b.updatedAt) || 0) - (Date.parse(a.recencyAt ?? a.updatedAt) || 0));
    return { threads: result, pinnedThreads: result.filter(t => t.isPinned), catalog: { localComplete: catalog.localComplete, remoteComplete: false, chatgptComplete: false, source: 'codex-app-server+desktop-membership' } };
  }
  async listProjects() {
    const [catalog, values] = await Promise.all([this.localCatalog(), this.server.pages('project/list')]);
    const projects = [...catalog.projects];
    for (const value of values) if (!projects.some(p => p.canonicalProjectId === value.id || p.projectId === value.id)) projects.push({ projectId: value.id, canonicalProjectId: value.id, projectKind: 'local', hostId: 'local', label: value.name, path: value.roots[0]?.path ?? null, rootPaths: value.roots.map(root => root.path) });
    return { projects, catalog: { localComplete: true, source: 'codex-app-server+desktop-membership' } };
  }
  async owner(threadId, hostId = 'local') {
    await this.ensureDesktopIpc();
    const response = await this.request('thread-owner-discovery', { conversationId: threadId, hostId }, { timeoutMs: this.options.ownerTimeoutMs ?? 6000 });
    if (!response.handledByClientId) throw new Error('no-client-found');
    this.ownerErrors.delete(`${hostId}:${threadId}`);
    this.owners.set(`${hostId}:${threadId}`, response.handledByClientId); return response.handledByClientId;
  }
  async desktopOwner(threadId, hostId = 'local', { recover = false } = {}) {
    // Only discovery is retried. A timed-out send may already have reached the
    // owner and must go through the operation receipt path, never a resend.
    for (let attempt = 0; attempt < (recover ? 3 : 1); attempt++) {
      if (attempt) {
        await new Promise(resolve => setTimeout(resolve, this.options.ownerRetryDelayMs ?? 300 * attempt));
        // A writable socket can still belong to an unresponsive desktop
        // router. Reinitialize it instead of trusting ipcAvailable forever.
        if (!this.ipcConnecting && !this.ipc?.pending?.size) { this.ipcAvailable = false; this.ipc?.close(); }
      }
      try { return await this.owner(threadId, hostId); }
      catch (error) { this.ownerErrors.set(`${hostId}:${threadId}`, error); }
    }
    return null;
  }
  ownerUnavailable(threadId) {
    const cause = this.ownerErrors.get(`local:${threadId}`);
    const reason = /timed? ?out|timeout/i.test(cause?.message ?? '') ? '桌面 Codex 响应超时' :
      /ENOENT|ECONNREFUSED|closed|disconnected/i.test(cause?.message ?? '') ? '桌面 Codex 本地连接已断开' : '桌面 Codex 暂未提供这条对话的控制连接';
    return notSubmitted(Object.assign(new Error(`${reason}，自动重连后仍未恢复。消息尚未发送，草稿已保留。请在电脑连接器点击“重新连接”后重试。`), { code: 'DESKTOP_OWNER_UNAVAILABLE' }));
  }
  async withThread(threadId, operation) {
    const previous = this.threadQueues.get(threadId) ?? Promise.resolve();
    const pending = previous.catch(() => {}).then(operation); this.threadQueues.set(threadId, pending);
    try { return await pending; } finally { if (this.threadQueues.get(threadId) === pending) this.threadQueues.delete(threadId); }
  }
  async releaseThread(threadId) {
    return this.withThread(threadId, () => this.releaseThreadLocked(threadId));
  }
  async releaseThreadLocked(threadId, notify = true) {
      if (!this.ownedThreads.has(threadId)) return false;
      if (this.ownerBridge.retain(threadId)) return false;
      const state = await this.storedState(threadId);
      // Runtime status is authoritative. Interrupted historical turns may
      // retain an old inProgress flag even though the loaded thread is idle.
      if (state.status?.type === 'active' || (state.status?.type !== 'idle' && state.turns.some(turn => turn.status === 'inProgress')) || [...this.approvals.values()].some(request => request.params?.threadId === threadId)) return false;
      if (this.ownerBridge.retain(threadId)) return false;
      const client = this.serverFor(threadId);
      // Unsubscribe alone retains the engine's writer lock. A separate process
      // per mobile-owned thread lets an idle chat release it without stopping
      // another running chat. The catalog process never resumes threads.
      if (client.closeAndWait) await client.closeAndWait(); else client.close();
      this.threadServers.delete(threadId); this.ownedThreads.delete(threadId); this.ownerBridge.forget(threadId); if (notify) this.announceThread(threadId); return true;
  }
  scheduleRelease(threadId, attempt = 0) {
    if (this.releaseTimers.has(threadId)) return;
    const timer = setTimeout(async () => {
      this.releaseTimers.delete(threadId);
      if (!this.ready) return;
      try { if (await this.releaseThread(threadId)) return; } catch {}
      if (this.ready && this.ownedThreads.has(threadId)) this.scheduleRelease(threadId, attempt + 1);
    }, Math.min(250 * 2 ** Math.min(attempt, 5), this.options.releaseMaxDelayMs ?? 5000));
    timer.unref?.();
    this.releaseTimers.set(threadId, timer);
  }
  async storedState(threadId) {
    const server = this.serverFor(threadId);
    const { thread } = await server.request('thread/read', { threadId, includeTurns: true });
    // Paginated histories explicitly require the turns endpoint. Do not silently
    // present an empty metadata response as complete history.
    let turns = thread.turns ?? [];
    if (thread.historyMode === 'paginated') turns = await server.pages('thread/turns/list', { threadId, itemsView: 'full', sortDirection: 'asc' });
    const old = this.states.get(threadId) ?? {};
    const state = { ...old, ...thread, title: thread.name || old.title || thread.preview || '新对话', turns, threadRuntimeStatus: thread.status, turnsPagination: { hasLoadedOldest: true }, requests: [...this.approvals.values()].filter(r => r.params.threadId === threadId), latestModel: thread.model ?? old.latestModel, latestReasoningEffort: thread.reasoningEffort ?? old.latestReasoningEffort };
    this.states.set(threadId, state); return state;
  }
  async readThread(args, options = {}) {
    const threadId = string(args.threadId, '对话 ID', 200);
    if (args.hostId && args.hostId !== 'local') throw new Error('独立连接暂不支持远程主机上的对话。');
    if (!this.ownedThreads.has(threadId)) {
      // The existing desktop remains the owner; never resume its running turn
      // in a second app-server merely to read or send a message.
      const owner = options.owner ?? await this.desktopOwner(threadId, args.hostId);
      if (owner) try { return await super.readThread(args, { ...options, owner, liveOnly: true }); } catch (error) { if (options.liveOnly) throw error; }
      else if (options.liveOnly) throw this.ownerUnavailable(threadId);
    }
    return { ...normalizeSnapshot(await this.storedState(threadId)), tokenUsage:this.extensions.tokens.get(threadId)??null, source: 'codex-app-server', live: true };
  }
  async tool(name, args) {
    if (name !== 'read_thread') throw new Error('此独立连接不使用桌面工具管道。');
    const state = await this.storedState(args.threadId);
    return { thread: state, turns: state.turns, page: { order: 'oldest_first' } };
  }
  rememberSettings(response) {
    const value = response.thread;
    const state = this.states.get(value.id) ?? value;
    state.latestThreadSettings = { model: response.model, effort: response.reasoningEffort, approvalPolicy: response.approvalPolicy, approvalsReviewer: response.approvalsReviewer, activePermissionProfile: response.activePermissionProfile, sandboxPolicy: response.sandbox, collaborationMode: response.collaborationMode };
    this.states.set(value.id, state);
  }
  async resume(threadId) {
    if (this.ownedThreads.has(threadId)) return;
    const state = await this.storedState(threadId);
    if (state.status?.type === 'active' || (state.status?.type !== 'idle' && state.turns.some(t => t.status === 'inProgress'))) throw Object.assign(notSubmitted(new Error('这条对话正在电脑运行，桌面连接尚未恢复。请保持 Codex 打开后重试，消息尚未发送。')), { desktopOwned: true });
    const server = await this.createThreadServer(); let result;
    try { result = await server.request('thread/resume', { threadId, excludeTurns: true }); }
    catch (error) { if (server !== this.server) server.close(); throw error; }
    this.threadServers.set(threadId, server);
    this.ownedThreads.add(threadId); this.rememberSettings(result);
    this.scheduleRelease(threadId);
  }
  async sendMessage(args) {
    return this.withThread(args.threadId, () => this.sendMessageLocked(args));
  }
  async confirmMessage({ threadId, clientUserMessageId }) {
    if (!threadId || !clientUserMessageId) return null;
    const match = turns => turns.flatMap(turn => turn.items ?? []).find(item =>
      (item.type === 'userMessage' && (item.clientId === clientUserMessageId || item.clientUserMessageId === clientUserMessageId)) ||
      (item.type === 'steeringUserMessage' && item.status === 'accepted' && item.serverUserMessageId && (item.serverClientUserMessageId ?? item.clientUserMessageId) === clientUserMessageId));
    let item = match(orderedTurns(this.snapshots.get(`local:${threadId}`)?.state));
    if (!item) try {
      const page = await this.serverFor(threadId).request('thread/turns/list', { threadId, limit: 5, itemsView: 'full', sortDirection: 'desc' }, 3000);
      item = match(page.data ?? []);
    } catch {}
    return item ? { threadId, submitted: true, confirmed: true, messageId: item.serverUserMessageId ?? item.id } : null;
  }
  async sendMessageLocked(args) {
    let threadId, text;
    try {
      threadId = string(args.threadId, '对话 ID', 200); text = (args.imagePaths?.length || args.files?.length) && !(args.text ?? args.prompt)?.trim() ? '' : string(args.text ?? args.prompt, '消息');
      if (args.hostId && args.hostId !== 'local') throw new Error('此连接仅支持本机对话。');
    } catch (error) { throw notSubmitted(error); }
    try { args = { ...args, files: [...(args.files ?? []), ...await this.workspace.references(args)] }; } catch (e) { throw notSubmitted(e); }
    const inputText=attachmentText(text,args.files);
    const input = [...(inputText ? [{ type: 'text', text:inputText, text_elements: [] }] : []), ...(args.imagePaths ?? []).map(path => ({ type: 'localImage', path }))];
    let owner = !this.ownedThreads.has(threadId) ? await this.desktopOwner(threadId) : null;
    if (!owner) {
      try { await this.resume(threadId); }
      catch (error) {
        // A refused resume has not submitted a turn. The desktop may have
        // opened the chat after discovery, or its IPC may just have reconnected.
        if (writerConflict(error) || error.desktopOwned) {
          owner = await this.desktopOwner(threadId, 'local', { recover: true });
          if (!owner) throw this.ownerUnavailable(threadId);
        }
        if (!owner) throw notSubmitted(error);
      }
    }
    if (owner) {
      let snapshot;
      try {
        if (args.model || args.thinking) {
          const result = await this.updateSettingsLocked({ threadId, ...(args.model ? { model: args.model } : {}), ...(args.thinking ? { reasoningEffort: args.thinking } : {}) }, owner);
          if (!result.verified) throw new Error(result.error ?? '模型设置尚未确认，消息尚未发送，请重试。');
        }
        snapshot = await super.readThread({ threadId }, { owner, liveOnly: true, loadHistory: false });
        if (snapshot.source !== 'desktop-live') throw new Error('桌面实时连接尚未恢复，消息尚未发送，请稍后重试。');
      } catch (error) { throw notSubmitted(error); }
      const clientUserMessageId = args.clientUserMessageId ?? randomUUID();
      let method, params;
      if (snapshot.thread.activeTurnId) {
        method = 'thread-follower-steer-turn';
        params = { conversationId: threadId, input, clientUserMessageId, restoreMessage: { id: clientUserMessageId, text, cwd: snapshot.thread.cwd, createdAt: Date.now(), context: { prompt: text, addedFiles: [], fileAttachments: (args.files ?? []).map(file => ({ label:file.name, path:file.path, fsPath:file.path })), ideContext: null, imageAttachments: (args.imagePaths ?? []).map(path => ({ path })), workspaceRoots: snapshot.thread.cwd ? [snapshot.thread.cwd] : [] } } };
      } else {
        method = 'thread-follower-start-turn';
        params = { conversationId: threadId, turnStart: { request: { threadId, input, clientUserMessageId }, context: { inheritThreadSettings: true } } };
      }
      try { return (await this.request(method, params, { targetClientId: owner })).result; }
      catch (error) {
        if (error.rejected && writerConflict(error)) throw notSubmitted(new Error('电脑会话占用暂未释放，消息尚未发送，请稍后重试。'));
        const receipt = await this.confirmMessage({ threadId, clientUserMessageId });
        if (receipt) return { ...receipt, recovered: true };
        error.uncertain = true; throw error;
      }
    }
    let state;
    try { state = await this.storedState(threadId); } catch (error) { throw notSubmitted(error); }
    const active = state.status?.type === 'idle' ? null : state.turns.findLast(t => t.status === 'inProgress');
    const server = this.serverFor(threadId);
    return active ? server.request('turn/steer', { threadId, expectedTurnId: active.id, input, ...(args.clientUserMessageId ? { clientUserMessageId: args.clientUserMessageId } : {}) }) : server.request('turn/start', { threadId, input, ...(args.clientUserMessageId ? { clientUserMessageId: args.clientUserMessageId } : {}), ...(args.model ? { model: args.model } : {}), ...(args.thinking ? { effort: args.thinking } : {}) });
  }
  async createThread(args) {
    let text, title, cwd, project;
    try {
      text = (args.imagePaths?.length || args.files?.length) && !(args.text ?? args.prompt)?.trim() ? '' : string(args.text ?? args.prompt, '消息');
      title = args.title ? string(args.title, '标题', 200) : text.trim().split('\n')[0].slice(0, 80) || (args.files?.length ? '文件对话' : '图片对话');
      if (args.projectId) {
        project = (await this.listProjects()).projects.find(p => p.projectId === args.projectId);
        if (!project?.path) throw new Error('项目不存在，请刷新后重试。');
      }
      try { await this.ensureDesktopIpc(this.timeoutMs); }
      catch { throw new Error('请先打开并登录电脑上的 Codex，再从手机新建对话。'); }
      // The desktop recognizes dated Documents/Codex workspaces as independent
      // chats. The old undated Pocket folder was mistaken for an unknown project.
      cwd = project?.path ?? path.join(this.options.projectlessDirectory ?? path.join(os.homedir(), 'Documents', 'Codex'), new Date().toISOString().slice(0, 10), `pocket-${randomUUID()}`);
      if (!project) mkdirSync(cwd, { recursive: true });
    } catch (error) { error.notSubmitted = true; throw error; }
    let result, server;
    try {
      server = await this.createThreadServer();
    } catch (error) { error.notSubmitted = true; throw error; }
    try {
      result = await server.request('thread/start', { cwd, threadSource: 'user', ...(project ? { projectId: project.canonicalProjectId ?? project.projectId } : {}), ...(args.model ? { model: args.model } : {}) });
      const threadId = result.thread.id;
      this.threadServers.set(threadId, server);
      this.ownedThreads.add(threadId); this.createdThreads.add(threadId); this.rememberSettings(result);
      this.scheduleRelease(threadId);
      const turn = await this.withThread(threadId, async () => {
        await server.request('thread/name/set', { threadId, name: title });
        return server.request('turn/start', { threadId, input: [...(attachmentText(text,args.files) ? [{ type: 'text', text:attachmentText(text,args.files), text_elements: [] }] : []), ...(args.imagePaths ?? []).map(path => ({ type: 'localImage', path }))], ...(args.clientUserMessageId ? { clientUserMessageId: args.clientUserMessageId } : {}), ...(args.thinking ? { effort: args.thinking } : {}) });
      });
      this.announceThread(threadId);
      const timer = setTimeout(() => { this.catalogRefreshTimers.delete(threadId); this.announceThread(threadId); }, 1000);
      this.catalogRefreshTimers.set(threadId, timer);
      return { threadId, hostId: 'local', ...turn };
    } catch (error) { if (!result && server !== this.server) server.close(); error.uncertain = true; throw error; }
  }
  async updateSettings(args) {
    const result = await this.withThread(args.threadId, () => this.updateSettingsLocked(args));
    try { await this.releaseThread(args.threadId); } catch { /* Retain ownership if release was not confirmed. */ }
    return result;
  }
  async updateSettingsLocked(args, knownOwner) {
    if (Object.keys(args).some(key => !['threadId','hostId','model','reasoningEffort','permissionMode'].includes(key))) throw new Error('不支持的设置字段');
    if (!this.ownedThreads.has(args.threadId)) {
      const owner = knownOwner ?? await this.desktopOwner(args.threadId, args.hostId);
      if (owner) return super.updateSettings(args, { owner });
    }
    await this.resume(string(args.threadId, '对话 ID', 200));
    const settings = {};
    if (args.model) { if (!this.models.some(m => m.id === args.model)) throw new Error('模型不可用'); settings.model = args.model; }
    if (Object.hasOwn(args, 'reasoningEffort')) {
      const model = this.models.find(m => m.id === (args.model ?? this.states.get(args.threadId)?.latestThreadSettings?.model));
      if (args.reasoningEffort !== null && !model?.reasoningEfforts.includes(args.reasoningEffort)) throw new Error('思考强度不可用');
      settings.effort = args.reasoningEffort;
    }
    if (args.permissionMode) { if (!Object.hasOwn(modes, args.permissionMode)) throw new Error('权限模式无效'); Object.assign(settings, modes[args.permissionMode]); }
    if (!Object.keys(settings).length) throw new Error('没有要修改的设置');
    await this.serverFor(args.threadId).request('thread/settings/update', { threadId: args.threadId, ...settings });
    // Resume reads the server's persisted next-turn settings, without overrides.
    this.rememberSettings(await this.serverFor(args.threadId).request('thread/resume', { threadId: args.threadId, excludeTurns: true }));
    const current = (await this.readThread({ threadId: args.threadId })).thread.settings;
    const verified = (!args.model || current.model === args.model) && (!Object.hasOwn(args, 'reasoningEffort') || current.reasoningEffort === args.reasoningEffort) && (!args.permissionMode || current.permissionMode === args.permissionMode);
    return { threadId: args.threadId, hostId: 'local', applied: true, verified, settings: current, appliesTo: 'subsequent-turns' };
  }
  onServerEvent({ method, params }) {
    this.extensions?.onEvent({method,params});
    const threadId = params?.threadId ?? params?.thread?.id;
    if(method==='serverRequest/resolved')for(const [key,request]of this.approvals)if(String(request.id)===String(params?.requestId)&&request.params?.threadId===threadId)this.approvals.delete(key);
    if (method === 'thread/status/changed' && this.ownedThreads.has(threadId)) this.scheduleRelease(threadId);
    if (method === 'turn/completed' && this.ownedThreads.has(threadId)) {
      for (const [id, request] of this.approvals) if (request.params?.threadId === threadId) this.approvals.delete(id);
      this.scheduleRelease(threadId);
    }
    if (!threadId || !this.ownedThreads.has(threadId) || this.refreshTimers.has(threadId)) return;
    const timer = setTimeout(async () => {
      this.refreshTimers.delete(threadId);
      try { this.emit('event', { type: 'thread.updated', threadId, data: await this.readThread({ threadId }) }); await this.ownerBridge.publish(threadId); } catch {}
    }, 150);
    this.refreshTimers.set(threadId, timer);
  }
  onServerRequest(message, server = this.server) {
    if (!APPROVAL_METHODS.has(message.method)) {
      server.send({ id: message.id, error: { code: -32601, message: 'Codex Pocket does not support this request type.' } }); return;
    }
    this.approvals.set(`${message.params.threadId}:${message.id}`, { ...message, completed: false });
    this.onServerEvent({ params: message.params });
  }
  async manageThread(action, args) {
    const threadId = string(args.threadId, '对话 ID', 200);
    return this.withThread(threadId, async () => {
      let state;
      try { state = await this.storedState(threadId); }
      catch (error) {
        if (action !== 'thread.delete') throw error;
        const all = [...await this.server.pages('thread/list', { useStateDbOnly: true, modelProviders: [], archived: false }), ...await this.server.pages('thread/list', { useStateDbOnly: true, modelProviders: [], archived: true })];
        if (all.some(thread => thread.id === threadId)) throw error;
        const desktopNotified = this.announceThread(threadId, { removed: true });
        return { threadId, deleted: true, archived: false, alreadyDeleted: true, verified: true, desktopNotified };
      }
      if (state.status?.type === 'active' || state.turns.some(turn => turn.status === 'inProgress')) throw new Error('请先停止正在运行的任务，再管理对话');
      clearTimeout(this.catalogRefreshTimers.get(threadId)); this.catalogRefreshTimers.delete(threadId);
      clearTimeout(this.releaseTimers.get(threadId)); this.releaseTimers.delete(threadId);
      await this.releaseThreadLocked(threadId, false);
      const deleted = action === 'thread.delete', archived = action === 'thread.archive';
      if (archived || (deleted && !state.archived)) {
        const client = await this.createThreadServer();
        try {
          try { await client.request('thread/resume', { threadId, excludeTurns: true }); }
          catch { throw Object.assign(new Error('这条对话仍由电脑持有，请在电脑关闭该对话页面后重试管理操作。'), { notSubmitted: true }); }
          await client.request('thread/archive', { threadId });
        } finally { if (client.closeAndWait) await client.closeAndWait(); else client.close(); }
      }
      if (!archived) await this.server.request(action.replace('.', '/'), { threadId });
      if (deleted) {
        const all = [...await this.server.pages('thread/list', { useStateDbOnly: true, modelProviders: [], archived: false }), ...await this.server.pages('thread/list', { useStateDbOnly: true, modelProviders: [], archived: true })];
        if (all.some(thread => thread.id === threadId)) throw Object.assign(new Error('删除已提交，电脑列表尚未确认，请刷新检查'), { uncertain: true });
      } else {
        const list = await this.server.pages('thread/list', { useStateDbOnly: true, modelProviders: [], archived });
        if (!list.some(thread => thread.id === threadId)) throw Object.assign(new Error('归档状态尚未确认，请刷新检查'), { uncertain: true });
      }
      this.states.delete(threadId); this.snapshots.delete(`local:${threadId}`); this.owners.delete(`local:${threadId}`); this.subscriptions.delete(`local:${threadId}`);
      if (deleted) this.createdThreads.delete(threadId);
      // Deletion also evicts the desktop's cached sidebar entry. The installed
      // IPC exposes archive/unarchive cache events, not a delete broadcast.
      const desktopNotified = this.announceThread(threadId, { removed: deleted || archived, cwd: state.cwd });
      return { threadId, deleted, archived, verified: true, desktopNotified };
    });
  }
  async renameThread(args) {
    const threadId=string(args.threadId,'对话 ID',200),name=string(args.name,'对话名称',60).trim();
    return this.withThread(threadId,async()=>{
      const metadata=(await this.listThreads({includeArchived:true})).threads.find(t=>t.id===threadId);
      await this.server.request('thread/name/set',{threadId,name});
      this.announceThread(threadId,{removed:metadata?.archived===true,cwd:metadata?.cwd});
      const {thread}=await this.server.request('thread/read',{threadId,includeTurns:false});
      this.emit('event',{type:'catalog.updated'});
      return {verified:thread.name===name,name:thread.name??name};
    });
  }
  async startReview(args) {
    const threadId=string(args.threadId,'对话 ID',200),target=args.target??{type:'uncommittedChanges'};
    const labels={uncommittedChanges:'审查当前工作区所有未提交的修改（含暂存、未暂存和新文件）。',baseBranch:`审查当前分支相对基础分支 ${target.branch??''} 的修改。`,commit:`审查提交 ${target.sha??''} 的修改。`,custom:target.instructions};
    if(!Object.hasOwn(labels,target.type))throw notSubmitted(new Error('审查类型无效'));
    if(target.type==='baseBranch')string(target.branch,'基础分支',200);
    if(target.type==='commit'&&!/^[a-f0-9]{7,64}$/i.test(target.sha??''))throw notSubmitted(new Error('提交 ID 无效'));
    const instruction=string(labels[target.type],'审查说明',10000);
    // Send through the current writer, including desktop-owned conversations.
    // This is the same turn/start workflow used by the installed desktop review UI.
    return this.sendMessage({...args,text:`请进行代码审查。${instruction}\n只报告可操作的问题，注明严重程度、文件与行号，解释影响并给出修复建议。不要直接修改文件。`,fileReferences:[],files:[],imagePaths:[]});
  }
  async call(action, args = {}) {
    if (!this.ready) throw new Error('Codex 尚未连接');
    if (args.hostId && args.hostId !== 'local') throw new Error('独立连接只支持本机 Codex。');
    if (['extensions.list','skill.read','skill.set','skill.save','skill.delete','plugin.list','plugin.install','plugin.uninstall','plugin.set','account.usage','mcp.list','mcp.login'].includes(action)) return this.extensions.call(action,args);
    if (action.startsWith('workspace.') || action.startsWith('git.')) return this.workspace.call(action,args);
    switch (action) {
      case 'threads.list': return this.listThreads(args);
      case 'projects.list': return this.listProjects();
      case 'models.list': return { models: this.models, source: 'codex-app-server' };
      case 'thread.read': return this.readThread(args);
      case 'thread.rename': return this.renameThread(args);
      case 'review.start': return this.startReview(args);
      case 'message.send': return this.sendMessage(args);
      case 'thread.create': return this.createThread(args);
      case 'thread.settings.update': return this.updateSettings(args);
      case 'thread.archive': case 'thread.unarchive': case 'thread.delete': return this.manageThread(action, args);
      case 'turn.stop': {
        if (!this.ownedThreads.has(args.threadId)) return super.call(action, args);
        const state = await this.storedState(args.threadId), turnId = args.turnId ?? state.turns.findLast(t => t.status === 'inProgress')?.id;
        if (!turnId) throw new Error('当前没有可停止的任务');
        return this.serverFor(args.threadId).request('turn/interrupt', { threadId: args.threadId, turnId });
      }
      case 'approval.respond': {
        const pending = this.approvals.get(`${args.threadId}:${args.requestId}`) ?? [...this.approvals.values()].find(p => String(p.id) === String(args.requestId));
        if (!pending) return super.call(action, args);
        if (pending.params.threadId !== args.threadId) throw new Error('权限请求属于其他对话');
        const result=approvalResponse(pending,args);
        this.serverFor(args.threadId).send({ id: pending.id, result }); this.approvals.delete(`${args.threadId}:${pending.id}`); this.onServerEvent({ params: pending.params }); return { submitted: true };
      }
      default: return super.call(action, args);
    }
  }
  close() {
    for (const timer of this.refreshTimers?.values() ?? []) clearTimeout(timer);
    this.refreshTimers?.clear(); this.server?.close(); this.ipcAvailable = false;
    for (const server of new Set(this.threadServers.values())) if (server !== this.server) server.close();
    this.threadServers.clear(); this.ownedThreads.clear();
    this.ownerBridge.reset();
    for (const timer of this.catalogRefreshTimers.values()) clearTimeout(timer);
    for (const timer of this.releaseTimers.values()) clearTimeout(timer);
    this.releaseTimers.clear(); this.catalogRefreshTimers.clear(); super.close();
  }
}
