// Local desktop IPC compatibility for chats being run by Pocket. This uses the
// same owner/follower protocol as two Codex windows; the desktop never needs to
// acquire a second App Server writer. Unknown versions and app-tool deliveries
// are deliberately refused instead of being forwarded as ordinary user input.
const versions = Object.freeze({
  'thread-owner-discovery': 1, 'thread-follower-load-complete-history': 1,
  'thread-follower-start-turn': 2, 'thread-follower-steer-turn': 1,
  'thread-follower-interrupt-turn': 4, 'thread-follower-read-model-settings': 1,
  'thread-follower-update-thread-settings': 2,
  'thread-follower-command-approval-decision': 1,
  'thread-follower-file-approval-decision': 1, 'thread-follower-submit-user-input': 1,
});
const plain = value => value && typeof value === 'object' && !Array.isArray(value);
const ms = seconds => Number.isFinite(seconds) ? seconds * 1000 : null;

export function desktopConversation(state) {
  const settings = state.latestThreadSettings ?? {};
  const model = settings.model ?? state.latestModel ?? state.model ?? '';
  const effort = settings.effort ?? state.latestReasoningEffort ?? state.reasoningEffort ?? null;
  const sandbox = settings.sandboxPolicy ?? { type: 'readOnly' };
  const permission = { approvalPolicy: settings.approvalPolicy ?? 'on-request', approvalsReviewer: settings.approvalsReviewer ?? 'user', sandboxPolicy: sandbox, activePermissionProfile: settings.activePermissionProfile ?? null, runtimeWorkspaceRoots: state.cwd ? [state.cwd] : [] };
  return {
    id: state.id, hostId: 'local', title: state.title ?? state.name ?? null,
    createdAt: ms(state.createdAt) ?? Date.now(), updatedAt: ms(state.updatedAt) ?? Date.now(), recencyAt: ms(state.recencyAt ?? state.updatedAt) ?? Date.now(),
    cwd: state.cwd, rolloutPath: state.path ?? '', sessionId: state.sessionId,
    originator: state.originator, source: state.source, threadSource: state.threadSource,
    forkedFromId: state.forkedFromId ?? null, parentThreadId: null, gitInfo: state.gitInfo ?? null,
    mode: 'default', threadStartKind: 'default', workspaceKind: 'project',
    modelProvider: state.modelProvider ?? '', latestModel: model, latestReasoningEffort: effort,
    previousTurnModel: null, latestCollaborationMode: settings.collaborationMode ?? { mode: 'default', settings: { model, reasoning_effort: effort, developer_instructions: null } },
    latestThreadSettings: settings, currentPermissions: permission,
    resumeState: 'resumed', threadRuntimeStatus: state.status, threadGoal: null,
    hasUnreadTurn: false, latestTokenUsageInfo: null, requests: state.requests ?? [],
    turnsPagination: { olderCursor: null, oldestLoadedTurnId: null, isLoadingOlder: false, hasLoadedOldest: true },
    turns: (state.turns ?? []).map(turn => {
      const first = turn.items?.find(item => item.type !== 'contextCompaction');
      return {
        turnId: turn.id, rootTurnId: turn.rootTurnId, status: turn.status, error: turn.error ?? null,
        turnStartedAtMs: ms(turn.startedAt), durationMs: turn.durationMs ?? null,
        finalAssistantStartedAtMs: ms(turn.completedAt), diff: null,
        permissionParamsSource: 'inferred',
        params: { threadId: state.id, input: first?.type === 'userMessage' ? first.content : [], clientUserMessageId: first?.clientId,
          model, effort, cwd: state.cwd ?? null, ...permission, summary: 'none', personality: null, outputSchema: null, collaborationMode: null, attachments: [] },
        items: (turn.items ?? []).map(item => item.type === 'collabAgentToolCall' ? { ...item, receiverThreads: (item.receiverThreadIds ?? []).map(threadId => ({ threadId, thread: null })) } : item),
      };
    }),
  };
}

export class DesktopOwnerBridge {
  constructor(adapter) { this.adapter = adapter; this.followers = new Map(); this.interest = new Map(); this.revisions = new Map(); }
  owns(id) { return this.adapter.ready && this.adapter.ownedThreads.has(id); }
  retain(id) { return !!this.followers.get(id)?.size || Date.now() - (this.interest.get(id) ?? 0) < 5000; }
  accepts(request) {
    const p = request?.params;
    return plain(p) && (request.hostId == null || request.hostId === 'local') && (p.hostId == null || p.hostId === 'local') && this.owns(p.conversationId) &&
      Object.hasOwn(versions, request.method) && (request.version === versions[request.method] || (request.method === 'thread-follower-interrupt-turn' && request.version === 3));
  }
  handle(message) {
    const a = this.adapter;
    if (message.type === 'client-discovery-request') {
      const canHandle = this.accepts(message.request);
      if (canHandle) this.interest.set(message.request.params.conversationId, Date.now());
      a.ipc.write({ type: 'client-discovery-response', requestId: message.requestId, response: { canHandle } }); return true;
    }
    if (message.type === 'request') {
      const reply = result => { try { a.ipc.write({ type: 'response', requestId: message.requestId, ...result }); } catch {} };
      if (!this.accepts(message)) { reply({ resultType: 'error', error: 'no-client-found: Pocket no longer owns this thread or protocol version differs' }); return true; }
      this.interest.set(message.params.conversationId, Date.now());
      this.request(message).then(result => reply({ resultType: 'success', method: message.method, handledByClientId: a.clientId, result }), error => reply({ resultType: 'error', error: error.message })); return true;
    }
    if (message.type === 'broadcast' && message.method === 'thread-stream-following-changed' && message.version === 1) {
      const p = message.params;
      if (p?.hostId !== 'local' || !this.owns(p.conversationId) || !message.sourceClientId) return false;
      const clients = this.followers.get(p.conversationId) ?? new Set();
      if (p.following === true) { clients.add(message.sourceClientId); this.followers.set(p.conversationId, clients); void this.publish(p.conversationId).catch(() => {}); }
      else if (p.following === false) { clients.delete(message.sourceClientId); if (!clients.size) { this.followers.delete(p.conversationId); this.interest.delete(p.conversationId); a.scheduleRelease(p.conversationId); } }
      return true;
    }
    if (message.type === 'broadcast' && message.method === 'client-status-changed' && message.params?.status === 'disconnected') {
      for (const [id, clients] of this.followers) { clients.delete(message.params.clientId); if (!clients.size) { this.followers.delete(id); this.interest.delete(id); a.scheduleRelease(id); } }
    }
    return false;
  }
  async publish(id) {
    if (!this.owns(id) || !this.followers.get(id)?.size || !this.adapter.ipcAvailable) return null;
    const state = await this.adapter.storedState(id);
    if (!this.owns(id) || !this.followers.get(id)?.size) return null;
    const revision = (this.revisions.get(id) ?? 0) + 1; this.revisions.set(id, revision);
    this.adapter.broadcast('thread-stream-state-changed', { hostId: 'local', conversationId: id, change: { type: 'snapshot', revision, conversationState: desktopConversation(state) } }, [...this.followers.get(id)]);
    return revision;
  }
  async request({ method, params: p }) {
    const a = this.adapter, id = p.conversationId;
    if (method === 'thread-owner-discovery') return { supportsUntrustedAppInput: false };
    if (method === 'thread-follower-load-complete-history') return { revision: await this.publish(id) };
    if (method === 'thread-follower-read-model-settings') {
      const state = await a.storedState(id), s = state.latestThreadSettings ?? {};
      return { settings: { model: s.model ?? state.latestModel, reasoningEffort: s.effort ?? state.latestReasoningEffort, resumeState: 'resumed', mode: 'default', threadSource: state.threadSource ?? null, modelProvider: state.modelProvider ?? null, daybreakEnabled: null } };
    }
    return a.withThread(id, async () => {
      if (!this.owns(id)) throw Error('no-client-found: thread owner changed');
      const server = a.serverFor(id);
      let result;
      if (method === 'thread-follower-start-turn' || method === 'thread-follower-steer-turn') {
        const request = method === 'thread-follower-start-turn' ? p.turnStart?.request : { input: p.input, clientUserMessageId: p.clientUserMessageId };
        if (!plain(request) || (request.threadId != null && request.threadId !== id) || p.turnStart?.context?.responseItems?.length || request.toolOutput || request.untrustedAppMessage || request.mcpAppModelContextAttachments?.length || p.toolOutput) throw Error('Pocket does not support app-tool delivery through this route');
        if (!Array.isArray(request.input) || !request.input.length || request.input.some(item => !plain(item) || !['text', 'localImage', 'image'].includes(item.type))) throw Error('Unsupported desktop user input');
        const allowed = ['input','clientUserMessageId','model','effort','cwd','approvalPolicy','approvalsReviewer','permissions','sandboxPolicy','runtimeWorkspaceRoots','serviceTier','personality','summary','outputSchema','collaborationMode','additionalContext'];
        const args = Object.fromEntries(allowed.filter(key => request[key] !== undefined).map(key => [key, request[key]]));
        args.threadId = id;
        if (method === 'thread-follower-steer-turn') {
          const state = await a.storedState(id), active = state.turns.findLast(turn => turn.status === 'inProgress');
          if (!active) throw Error('No active turn to steer');
          args.expectedTurnId = active.id;
        }
        result = { result: await server.request(method === 'thread-follower-start-turn' ? 'turn/start' : 'turn/steer', args) };
      } else if (method === 'thread-follower-interrupt-turn') {
        const state = await a.storedState(id), turnId = state.turns.findLast(turn => turn.status === 'inProgress')?.id;
        if (p.expectedTurnId && p.expectedTurnId !== turnId) throw Error('Active turn changed');
        if (turnId) await server.request('turn/interrupt', { threadId: id, turnId });
        result = { ok: true, interruptedTurnId: turnId ?? null };
      } else if (method === 'thread-follower-update-thread-settings') {
        if (p.activeTurnId != null) throw Error('Pocket supports next-turn settings only');
        const current = (await a.storedState(id)).latestThreadSettings ?? {};
        if (p.condition && ((Object.hasOwn(p.condition, 'ifModelEquals') && p.condition.ifModelEquals !== current.model) || (Object.hasOwn(p.condition, 'ifEffortEquals') && p.condition.ifEffortEquals !== current.effort))) return { applied: false };
        const allowed = ['model','effort','approvalPolicy','approvalsReviewer','permissions','sandboxPolicy','runtimeWorkspaceRoots','serviceTier','collaborationMode'];
        if (!plain(p.threadSettings) || Object.keys(p.threadSettings).some(key => !allowed.includes(key))) throw Error('Unsupported desktop thread settings');
        await server.request('thread/settings/update', { ...p.threadSettings, threadId: id });
        a.rememberSettings(await server.request('thread/resume', { threadId: id, excludeTurns: true }));
        result = { applied: true };
      } else {
        const pending = a.approvals.get(`${id}:${p.requestId}`);
        const expected = { 'thread-follower-command-approval-decision': 'item/commandExecution/requestApproval', 'thread-follower-file-approval-decision': 'item/fileChange/requestApproval', 'thread-follower-submit-user-input': 'item/tool/requestUserInput' }[method];
        if (!pending || pending.method !== expected) throw Error('Approval is no longer pending');
        await a.call('approval.respond', { threadId: id, requestId: p.requestId, decision: p.decision, response: p.response }); result = { ok: true };
      }
      await this.publish(id); a.scheduleRelease(id); return result;
    });
  }
  reset() { this.followers.clear(); this.interest.clear(); }
  forget(id) { this.followers.delete(id); this.interest.delete(id); this.revisions.delete(id); }
}
