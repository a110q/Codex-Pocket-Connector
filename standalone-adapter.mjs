import path from 'node:path';
import os from 'node:os';
import { mkdirSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { DesktopAdapter, FramedSocket, normalizeSnapshot } from './desktop-adapter.mjs';
import { AppServerClient } from './app-server-client.mjs';

const modes = { 'read-only': { permissions: ':read-only', approvalPolicy: 'on-request', approvalsReviewer: 'user' }, auto: { permissions: ':workspace', approvalPolicy: 'on-request', approvalsReviewer: 'user' }, 'full-access': { permissions: ':danger-full-access', approvalPolicy: 'never', approvalsReviewer: 'user' } };
const string = (value, label, length = 100000) => { if (typeof value !== 'string' || !value.trim() || value.length > length) throw new Error(`${label}无效`); return value; };

export class StandaloneAdapter extends DesktopAdapter {
  constructor(options = {}) {
    super(options); this.server = options.server ?? new AppServerClient(options);
    this.models = []; this.ownedThreads = new Set(); this.states = new Map(); this.approvals = new Map(); this.refreshTimers = new Map(); this.ipcAvailable = false;
    this.server.on('notification', message => this.onServerEvent(message));
    this.server.on('request', message => this.onServerRequest(message));
    this.server.on('disconnect', error => {
      this.ownedThreads.clear(); this.approvals.clear();
      if (this.ready) { this.ready = false; this.emit('event', { type: 'desktop.disconnected', error: error.message }); }
    });
  }
  async connectDesktop() {
    await this.server.connect();
    try {
      const [models] = await Promise.all([this.server.pages('model/list'), this.server.request('thread/list', { limit: 1, useStateDbOnly: true, modelProviders: [] }), this.server.request('project/list', { limit: 1 })]);
      this.models = models.filter(model => !model.hidden).map(model => ({ id: model.model, reasoningEfforts: model.supportedReasoningEfforts.map(value => value.reasoningEffort) }));
      if (!this.models.length) throw new Error('Codex 尚未提供可用模型，请先登录并配置模型。');
      const ipcPath = this.options.ipcPath ?? process.env.POCKET_CODEX_IPC_PATH ?? (process.platform === 'win32' ? '\\\\.\\pipe\\codex-ipc' : path.join(this.options.codexHome ?? process.env.CODEX_HOME ?? path.join(os.homedir(), '.codex'), 'ipc/ipc.sock'));
      this.ipc = new FramedSocket(ipcPath, 2500);
      this.ipc.on('message', message => this.handleIpc(message));
      this.ipc.on('disconnect', () => { this.ipcAvailable = false; });
      try {
        await this.ipc.connect();
        const result = await this.ipc.exchange({ type: 'request', requestId: randomUUID(), sourceClientId: 'initializing-client', version: 0, method: 'initialize', params: { clientType: 'desktop' } });
        if (result.resultType !== 'success') throw new Error('Desktop IPC unavailable');
        this.clientId = result.result.clientId; this.ipcAvailable = true;
      } catch { this.ipc.close(); this.ipcAvailable = false; }
      this.ready = true; return this.diagnostics();
    } catch (error) { this.close(); throw error; }
  }
  diagnostics() {
    return { connected: this.ready, backend: 'codex-app-server', desktopWindowConnected: this.ipcAvailable,
      capabilities: Object.fromEntries(['threads.list','projects.list','thread.read','message.send','thread.create','models.list','turn.stop','approval.respond','thread.settings.update','thread.archive','thread.unarchive','thread.delete'].map(key => [key, true])),
      limitations: ['Local Codex projects and chats use the installed Codex account. Remote hosts and ChatGPT-only conversations are not included.', ...(!this.ipcAvailable ? ['Open Codex to synchronize live desktop-owned conversations; concurrent desktop sessions cannot be controlled while its window is disconnected.'] : [])] };
  }
  modelCatalog() { return this.models; }
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
    if (!this.ipcAvailable) throw new Error('Codex 桌面窗口未连接');
    return super.owner(threadId, hostId);
  }
  async storedState(threadId) {
    const { thread } = await this.server.request('thread/read', { threadId, includeTurns: true });
    // Paginated histories explicitly require the turns endpoint. Do not silently
    // present an empty metadata response as complete history.
    let turns = thread.turns ?? [];
    if (thread.historyMode === 'paginated') turns = await this.server.pages('thread/turns/list', { threadId, itemsView: 'full', sortDirection: 'asc' });
    const old = this.states.get(threadId) ?? {};
    const state = { ...old, ...thread, title: thread.name || old.title || thread.preview || '新对话', turns, threadRuntimeStatus: thread.status, turnsPagination: { hasLoadedOldest: true }, requests: [...this.approvals.values()].filter(r => r.params.threadId === threadId), latestModel: thread.model ?? old.latestModel, latestReasoningEffort: thread.reasoningEffort ?? old.latestReasoningEffort };
    this.states.set(threadId, state); return state;
  }
  async readThread(args) {
    const threadId = string(args.threadId, '对话 ID', 200);
    if (args.hostId && args.hostId !== 'local') throw new Error('独立连接暂不支持远程主机上的对话。');
    if (this.ipcAvailable && !this.ownedThreads.has(threadId)) {
      // The existing desktop remains the owner; never resume its running turn
      // in a second app-server merely to read or send a message.
      try { const result = await super.readThread(args); if (result.source === 'desktop-live') return result; } catch {}
    }
    return { ...normalizeSnapshot(await this.storedState(threadId)), source: 'codex-app-server', live: true };
  }
  async tool(name, args) {
    if (name !== 'read_thread') throw new Error('此独立连接不使用桌面工具管道。');
    const state = await this.storedState(args.threadId);
    return { thread: state, turns: state.turns, page: { order: 'oldest_first' } };
  }
  rememberSettings(response) {
    const value = response.thread;
    const state = this.states.get(value.id) ?? value;
    state.latestThreadSettings = { model: response.model, effort: response.reasoningEffort, approvalPolicy: response.approvalPolicy, approvalsReviewer: response.approvalsReviewer, activePermissionProfile: response.activePermissionProfile, sandboxPolicy: response.sandbox };
    this.states.set(value.id, state);
  }
  async resume(threadId) {
    if (this.ownedThreads.has(threadId)) return;
    const state = await this.storedState(threadId);
    if (state.status?.type === 'active' || state.turns.some(t => t.status === 'inProgress')) throw new Error('这个对话仍在电脑运行，请先连接 Codex 桌面窗口再继续。');
    const result = await this.server.request('thread/resume', { threadId, excludeTurns: true });
    this.ownedThreads.add(threadId); this.rememberSettings(result);
  }
  async sendMessage(args) {
    const threadId = string(args.threadId, '对话 ID', 200), text = string(args.text ?? args.prompt, '消息');
    if (args.hostId && args.hostId !== 'local') throw new Error('此连接仅支持本机对话。');
    const input = [{ type: 'text', text, text_elements: [] }];
    let owner;
    if (!this.ownedThreads.has(threadId) && this.ipcAvailable) try { owner = await this.owner(threadId); } catch {}
    if (owner) {
      if (args.model || args.thinking) await this.updateSettings({ threadId, ...(args.model ? { model: args.model } : {}), ...(args.thinking ? { reasoningEffort: args.thinking } : {}) });
      const snapshot = await super.readThread({ threadId }); const clientUserMessageId = randomUUID();
      let method, params;
      if (snapshot.thread.activeTurnId) {
        method = 'thread-follower-steer-turn';
        params = { conversationId: threadId, input, clientUserMessageId, restoreMessage: { id: clientUserMessageId, text, cwd: snapshot.thread.cwd, createdAt: Date.now(), context: { prompt: text, addedFiles: [], fileAttachments: [], ideContext: null, imageAttachments: [], workspaceRoots: snapshot.thread.cwd ? [snapshot.thread.cwd] : [] } } };
      } else {
        method = 'thread-follower-start-turn';
        params = { conversationId: threadId, turnStart: { request: { threadId, input, clientUserMessageId }, context: { inheritThreadSettings: true } } };
      }
      try { return (await this.request(method, params, { targetClientId: owner })).result; }
      catch (error) { error.uncertain = true; throw error; }
    }
    await this.resume(threadId);
    const state = await this.storedState(threadId), active = state.turns.findLast(t => t.status === 'inProgress');
    return active ? this.server.request('turn/steer', { threadId, expectedTurnId: active.id, input }) : this.server.request('turn/start', { threadId, input, ...(args.model ? { model: args.model } : {}), ...(args.thinking ? { effort: args.thinking } : {}) });
  }
  async createThread(args) {
    const text = string(args.text ?? args.prompt, '消息'); let project;
    if (args.projectId) {
      project = (await this.listProjects()).projects.find(p => p.projectId === args.projectId);
      if (!project) throw new Error('项目不存在，请刷新后重试。');
    }
    const cwd = project?.path ?? this.options.projectlessDirectory ?? path.join(os.homedir(), 'Documents', 'Codex', 'Pocket');
    if (!project) mkdirSync(cwd, { recursive: true });
    const result = await this.server.request('thread/start', { cwd, ...(project ? { projectId: project.canonicalProjectId ?? project.projectId } : {}), ...(args.model ? { model: args.model } : {}) });
    const threadId = result.thread.id; this.ownedThreads.add(threadId); this.rememberSettings(result);
    if (args.title) await this.server.request('thread/name/set', { threadId, name: string(args.title, '标题', 200) });
    try {
      const turn = await this.server.request('turn/start', { threadId, input: [{ type: 'text', text, text_elements: [] }], ...(args.thinking ? { effort: args.thinking } : {}) });
      return { threadId, hostId: 'local', ...turn };
    } catch (error) { error.uncertain = true; throw error; }
  }
  async updateSettings(args) {
    if (Object.keys(args).some(key => !['threadId','hostId','model','reasoningEffort','permissionMode'].includes(key))) throw new Error('不支持的设置字段');
    if (!this.ownedThreads.has(args.threadId)) {
      let owner; if (this.ipcAvailable) try { owner = await this.owner(args.threadId, args.hostId); } catch {}
      if (owner) return super.updateSettings(args);
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
    await this.server.request('thread/settings/update', { threadId: args.threadId, ...settings });
    // Resume reads the server's persisted next-turn settings, without overrides.
    this.rememberSettings(await this.server.request('thread/resume', { threadId: args.threadId, excludeTurns: true }));
    const current = (await this.readThread({ threadId: args.threadId })).thread.settings;
    const verified = (!args.model || current.model === args.model) && (!Object.hasOwn(args, 'reasoningEffort') || current.reasoningEffort === args.reasoningEffort) && (!args.permissionMode || current.permissionMode === args.permissionMode);
    return { threadId: args.threadId, hostId: 'local', applied: true, verified, settings: current, appliesTo: 'subsequent-turns' };
  }
  onServerEvent({ method, params }) {
    const threadId = params?.threadId ?? params?.thread?.id;
    if (!threadId || !this.ownedThreads.has(threadId) || this.refreshTimers.has(threadId)) return;
    const timer = setTimeout(async () => {
      this.refreshTimers.delete(threadId);
      try { this.emit('event', { type: 'thread.updated', threadId, data: await this.readThread({ threadId }) }); } catch {}
    }, 150);
    this.refreshTimers.set(threadId, timer);
  }
  onServerRequest(message) {
    const allowed = ['item/commandExecution/requestApproval','item/fileChange/requestApproval','item/tool/requestUserInput'];
    if (!allowed.includes(message.method)) {
      this.server.send({ id: message.id, error: { code: -32601, message: 'Codex Pocket does not support this request type.' } }); return;
    }
    this.approvals.set(String(message.id), { ...message, completed: false });
    this.onServerEvent({ params: message.params });
  }
  async call(action, args = {}) {
    if (!this.ready) throw new Error('Codex 尚未连接');
    if (args.hostId && args.hostId !== 'local') throw new Error('独立连接只支持本机 Codex。');
    switch (action) {
      case 'threads.list': return this.listThreads(args);
      case 'projects.list': return this.listProjects();
      case 'models.list': return { models: this.models, source: 'codex-app-server' };
      case 'thread.read': return this.readThread(args);
      case 'message.send': return this.sendMessage(args);
      case 'thread.create': return this.createThread(args);
      case 'thread.settings.update': return this.updateSettings(args);
      case 'thread.archive': case 'thread.unarchive': case 'thread.delete': {
        const threadId=string(args.threadId,'对话 ID',200);
        const state=await this.storedState(threadId);
        if(state.status?.type==='active'||state.turns.some(turn=>turn.status==='inProgress'))throw new Error('请先停止正在运行的任务，再管理对话');
        await this.server.request(action.replace('.','/'),{threadId});
        this.states.delete(threadId);this.ownedThreads.delete(threadId);
        const archived=action==='thread.archive';
        if(action==='thread.delete') {
          const all=[...await this.server.pages('thread/list',{useStateDbOnly:true,modelProviders:[],archived:false}),...await this.server.pages('thread/list',{useStateDbOnly:true,modelProviders:[],archived:true})];
          if(all.some(thread=>thread.id===threadId))throw Object.assign(new Error('删除已提交，电脑列表尚未确认，请刷新检查'),{uncertain:true});
        } else {
          const list=await this.server.pages('thread/list',{useStateDbOnly:true,modelProviders:[],archived});
          if(!list.some(thread=>thread.id===threadId))throw Object.assign(new Error('归档状态尚未确认，请刷新检查'),{uncertain:true});
        }
        return{threadId,deleted:action==='thread.delete',archived,verified:true};
      }
      case 'turn.stop': {
        if (!this.ownedThreads.has(args.threadId)) return super.call(action, args);
        const state = await this.storedState(args.threadId), turnId = args.turnId ?? state.turns.findLast(t => t.status === 'inProgress')?.id;
        if (!turnId) throw new Error('当前没有可停止的任务');
        return this.server.request('turn/interrupt', { threadId: args.threadId, turnId });
      }
      case 'approval.respond': {
        const pending = this.approvals.get(String(args.requestId));
        if (!pending) return super.call(action, args);
        if (pending.params.threadId !== args.threadId) throw new Error('权限请求属于其他对话');
        let result;
        if (pending.method === 'item/tool/requestUserInput') {
          const answers = args.response?.answers;
          if (!answers || typeof answers !== 'object' || Array.isArray(answers) || !Object.keys(answers).length) throw new Error('请输入回答');
          const ids = new Set((pending.params.questions ?? []).map(q => q.id));
          for (const [id, answer] of Object.entries(answers)) if (!ids.has(id) || !Array.isArray(answer?.answers) || answer.answers.some(value => typeof value !== 'string' || value.length > 20000)) throw new Error('回答格式无效');
          result = { answers };
        } else {
          if (!['accept','acceptForSession','decline','cancel'].includes(args.decision)) throw new Error('授权选项无效'); result = { decision: args.decision };
        }
        this.server.send({ id: pending.id, result }); this.approvals.delete(String(pending.id)); this.onServerEvent({ params: pending.params }); return { submitted: true };
      }
      default: return super.call(action, args);
    }
  }
  close() {
    for (const timer of this.refreshTimers?.values() ?? []) clearTimeout(timer);
    this.refreshTimers?.clear(); this.server?.close(); this.ipcAvailable = false; super.close();
  }
}
