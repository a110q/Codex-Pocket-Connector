import { approvalResponse } from './approvals.mjs';
import net from 'node:net';
import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import { readdir, lstat, readFile } from 'node:fs/promises';
import { DatabaseSync } from 'node:sqlite';
import os from 'node:os';
import path from 'node:path';
import { findDesktopEndpoints } from './platform.mjs';

// This adapter targets the installed desktop build. Its IPC is private and may
// change in later releases; incompatible stream versions fail closed.
const VERSIONS = Object.freeze({
  'thread-owner-discovery': 1,
  'thread-follower-start-turn': 2,
  'thread-follower-steer-turn': 1,
  'thread-stream-state-changed': 11,
  'thread-stream-following-changed': 1,
  'thread-stream-following-status-requested': 1,
  'thread-follower-load-complete-history': 1,
  'thread-follower-read-model-settings': 1,
  'thread-follower-update-thread-settings': 2,
  'thread-follower-interrupt-turn': 4,
  'thread-follower-command-approval-decision': 1,
  'thread-follower-file-approval-decision': 1,
  'thread-follower-submit-user-input': 1,
  'thread-follower-permissions-request-approval-response': 1,
  'thread-follower-submit-mcp-server-elicitation-response': 1,
});
const DECISIONS = new Set(['accept', 'acceptForSession', 'decline', 'cancel']);
const FORBIDDEN_KEYS = new Set(['__proto__', 'prototype', 'constructor']);
const PERMISSION_MODES = Object.freeze({
  'read-only': Object.freeze({ permissions: ':read-only', approvalPolicy: 'on-request', approvalsReviewer: 'user' }),
  auto: Object.freeze({ permissions: ':workspace', approvalPolicy: 'on-request', approvalsReviewer: 'user' }),
  'full-access': Object.freeze({ permissions: ':danger-full-access', approvalPolicy: 'never', approvalsReviewer: 'user' }),
});

function record(value) { return value != null && typeof value === 'object' && !Array.isArray(value) ? value : {}; }
function strings(value) { return Array.isArray(value) ? value.filter(item => typeof item === 'string') : []; }
function timestamp(milliseconds, seconds) {
  const value = milliseconds ?? (seconds == null ? null : seconds * 1000);
  return Number.isFinite(value) && Math.abs(value) <= 8.64e15 ? new Date(value).toISOString() : null;
}
function threadKey(thread) { return `${thread.kind ?? 'codex'}:${thread.hostId ?? 'local'}:${thread.id}`; }

// Read only catalog metadata. Conversation bodies, previews and rollout files
// are deliberately outside this query. Legacy membership remains authoritative
// until the desktop has completed its thread-assignment migration.
export async function readDesktopCatalog({ codexHome = process.env.CODEX_HOME ?? path.join(os.homedir(), '.codex'), dbPath = path.join(codexHome, 'state_5.sqlite'), globalStatePath = path.join(codexHome, '.codex-global-state.json') } = {}) {
  const limitations = []; let global = {}; let stateAvailable = false;
  try { global = record(JSON.parse(await readFile(globalStatePath, 'utf8'))); stateAvailable = true; }
  catch { limitations.push('Desktop project membership metadata is unavailable.'); }
  const identity = `local:${codexHome}`;
  const migration = record(record(global['app-server-projects-migration-by-host'])[identity]);
  const legacyToCanonical = record(record(global['app-server-project-id-by-legacy-project-id-by-host'])[identity]);
  const canonicalToLegacy = new Map(Object.entries(legacyToCanonical).filter(([, id]) => typeof id === 'string').map(([legacy, canonical]) => [canonical, legacy]));
  const projects = new Map();
  const localProjects = record(global['local-projects']);
  for (const [id, project] of Object.entries(localProjects)) {
    const value = record(project); const rootPaths = strings(value.rootPaths);
    projects.set(id, { projectId: id, projectKind: 'local', hostId: 'local', label: value.name || 'Untitled project', path: rootPaths[0] ?? null, rootPaths,
      ...(typeof legacyToCanonical[id] === 'string' ? { canonicalProjectId: legacyToCanonical[id] } : {}) });
  }
  let rows = []; let spawned = new Set(); let databaseAvailable = false; let db;
  try {
    db = new DatabaseSync(dbPath, { readOnly: true });
    const columns = new Set(db.prepare('PRAGMA table_info(threads)').all().map(row => row.name));
    if (!columns.has('id')) throw new Error('Unsupported catalog schema');
    const allowed = ['id', 'created_at', 'created_at_ms', 'updated_at', 'updated_at_ms', 'recency_at', 'recency_at_ms', 'source', 'model_provider', 'cwd', 'title', 'name', 'archived', 'model', 'reasoning_effort', 'originator', 'project_id', 'thread_section_id', 'section_position', 'is_pinned'];
    rows = db.prepare(`SELECT ${allowed.filter(name => columns.has(name)).join(', ')} FROM threads`).all();
    try { spawned = new Set(db.prepare('SELECT child_thread_id FROM thread_spawn_edges').all().map(row => row.child_thread_id)); } catch {}
    try {
      const roots = db.prepare('SELECT project_id, path FROM project_roots ORDER BY position').all();
      for (const project of db.prepare('SELECT id, name, position FROM projects ORDER BY position').all()) {
        const id = canonicalToLegacy.get(project.id) ?? project.id; const old = projects.get(id);
        const rootPaths = roots.filter(root => root.project_id === project.id).map(root => root.path);
        projects.set(id, { ...old, projectId: id, canonicalProjectId: project.id, projectKind: 'local', hostId: 'local', label: project.name || old?.label || 'Untitled project',
          rootPaths: rootPaths.length ? rootPaths : old?.rootPaths ?? [], path: rootPaths[0] ?? old?.path ?? null, position: project.position });
      }
    } catch { limitations.push('Some desktop project metadata is unavailable.'); }
    databaseAvailable = true;
  } catch { limitations.push('Full local chat metadata is unavailable; only recent desktop chats can be shown.'); }
  finally { db?.close(); }
  const assignments = record(global['thread-project-assignments']); const projectless = new Set(strings(global['projectless-thread-ids']));
  const membershipHosts = record(global['thread-project-membership-host-ids']);
  const pendingAssignments = new Set(strings(migration.pendingThreadAssignmentIds));
  const threadOrders = record(global['sidebar-project-thread-orders']);
  const threads = rows.map(row => {
    const assignment = record(assignments[row.id]); const explicitProjectless = projectless.has(row.id);
    let projectId = null; let membershipSource = 'unassigned'; let membershipKnown = false;
    const hasLegacyAssignment = typeof assignment.projectId === 'string';
    const protectedAssignment = hasLegacyAssignment && (assignment.projectKind === 'remote' || !Object.hasOwn(localProjects, assignment.projectId));
    const canonicalMembershipKnown = Object.hasOwn(row, 'project_id') && (row.project_id == null || canonicalToLegacy.has(row.project_id));
    if (migration.threadAssignmentsMigrated === true && !pendingAssignments.has(row.id) && !protectedAssignment && canonicalMembershipKnown) {
      projectId = row.project_id == null ? null : canonicalToLegacy.get(row.project_id) ?? row.project_id;
      membershipKnown = true; membershipSource = 'desktop-database';
    } else if (hasLegacyAssignment && (assignment.projectKind === 'local' || assignment.projectKind === 'remote') && (membershipHosts[row.id] == null || membershipHosts[row.id] === 'local')) {
      projectId = assignment.projectId; membershipKnown = true; membershipSource = 'desktop-membership';
    } else if (explicitProjectless) {
      membershipKnown = true; membershipSource = 'desktop-projectless';
    } else if (row.project_id != null) {
      projectId = canonicalToLegacy.get(row.project_id) ?? row.project_id; membershipKnown = true; membershipSource = 'desktop-database';
    }
    const order = projectId == null ? [] : strings(threadOrders[projectId]); const projectIndex = order.indexOf(row.id);
    return { id: row.id, kind: 'codex', hostId: 'local', projectId, membershipKnown, membershipSource,
      title: row.name || row.title || 'Untitled', cwd: row.cwd ?? null, status: 'unknown', archived: Boolean(row.archived),
      createdAt: timestamp(row.created_at_ms, row.created_at), updatedAt: timestamp(row.updated_at_ms, row.updated_at),
      recencyAt: timestamp(row.recency_at_ms ?? row.updated_at_ms, row.recency_at ?? row.updated_at),
      model: row.model ?? null, reasoningEffort: row.reasoning_effort ?? null, modelProvider: row.model_provider ?? null,
      isSubagent: spawned.has(row.id), isPinned: Boolean(row.is_pinned), ...(projectIndex < 0 ? {} : { projectIndex }), source: 'desktop-local-metadata' };
  });
  const projectOrder = strings(global['project-order']);
  const orderedProjects = [...projects.values()].sort((a, b) => {
    const ai = projectOrder.indexOf(a.projectId); const bi = projectOrder.indexOf(b.projectId);
    return (ai < 0 ? Infinity : ai) - (bi < 0 ? Infinity : bi) || (a.position ?? Infinity) - (b.position ?? Infinity) || a.label.localeCompare(b.label);
  });
  return { threads, projects: orderedProjects, source: 'desktop-local-metadata', localComplete: databaseAvailable && stateAvailable, limitations };
}

export function normalizeThreadSettings(state) {
  const next = state.latestThreadSettings ?? {}; const current = state.currentPermissions ?? {};
  const latestTurn = orderedTurns(state).at(-1)?.params ?? {};
  const profile = next.activePermissionProfile !== undefined ? next.activePermissionProfile : next.permissions !== undefined ? (next.permissions == null ? null : { id: next.permissions, extends: null }) : next.sandboxPolicy !== undefined ? null : current.activePermissionProfile ?? (typeof latestTurn.permissions === 'string' ? { id: latestTurn.permissions, extends: null } : null);
  const permissions = { activePermissionProfile: profile, approvalPolicy: next.approvalPolicy ?? latestTurn.approvalPolicy ?? current.approvalPolicy ?? null,
    approvalsReviewer: next.approvalsReviewer ?? latestTurn.approvalsReviewer ?? current.approvalsReviewer ?? null,
    sandboxPolicy: next.sandboxPolicy ?? latestTurn.sandboxPolicy ?? current.sandboxPolicy ?? null };
  const match = Object.entries(PERMISSION_MODES).find(([, value]) => value.permissions === profile?.id && value.approvalPolicy === permissions.approvalPolicy && value.approvalsReviewer === permissions.approvalsReviewer);
  return { model: next.model ?? next.collaborationMode?.settings?.model ?? state.latestCollaborationMode?.settings?.model ?? state.latestModel ?? null,
    reasoningEffort: next.effort !== undefined ? next.effort : next.collaborationMode?.settings?.reasoning_effort ?? state.latestReasoningEffort ?? null,
    permissionMode: match?.[0] ?? (permissions.sandboxPolicy || profile ? 'custom' : null), permissions,
    collaborationMode: next.collaborationMode?.mode ?? state.latestCollaborationMode?.mode ?? null, appliesTo: 'subsequent-turns' };
}

export function encodeFrame(message, maxBytes = 64 * 1024 * 1024) {
  const payload = Buffer.from(JSON.stringify(message));
  if (!payload.length || payload.length > maxBytes) throw new Error('Frame exceeds desktop transport limit');
  const frame = Buffer.allocUnsafe(4 + payload.length);
  frame.writeUInt32LE(payload.length, 0);
  payload.copy(frame, 4);
  return frame;
}

export class FrameDecoder {
  constructor(maxBytes = 64 * 1024 * 1024) { this.maxBytes = maxBytes; this.buffer = Buffer.alloc(0); }
  push(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    const messages = [];
    while (this.buffer.length >= 4) {
      const size = this.buffer.readUInt32LE(0);
      if (!size || size > this.maxBytes) throw new Error('Invalid desktop frame length');
      if (this.buffer.length < 4 + size) break;
      messages.push(JSON.parse(this.buffer.subarray(4, 4 + size).toString('utf8')));
      this.buffer = this.buffer.subarray(4 + size);
    }
    return messages;
  }
}

export class FramedSocket extends EventEmitter {
  constructor(socketPath, timeoutMs = 15000, createConnection = socketPath => net.createConnection(socketPath)) {
    super(); this.socketPath = socketPath; this.timeoutMs = timeoutMs;
    this.pending = new Map(); this.decoder = new FrameDecoder(); this.socket = null;
    this.createConnection = createConnection; this.connecting = null; this.disconnected = true;
  }
  async connect() {
    if (this.connecting) return this.connecting;
    if (this.socket && !this.socket.destroyed && !this.disconnected) return;
    this.connecting = new Promise((resolve, reject) => {
      const socket = this.createConnection(this.socketPath); this.socket = socket;
      this.decoder = new FrameDecoder(); this.disconnected = false;
      let connected = false;
      const timer = setTimeout(() => { socket.destroy(); reject(new Error('Desktop socket connection timed out')); }, this.timeoutMs);
      socket.once('connect', () => { clearTimeout(timer); connected = true; resolve(); });
      socket.on('error', error => {
        if (this.socket !== socket) return;
        clearTimeout(timer); reject(error); this.fail(error);
      });
      socket.on('close', () => {
        clearTimeout(timer);
        const error = new Error('Desktop socket closed. App tools require bundled Node launched by the running desktop app.');
        if (!connected) reject(error);
        if (this.socket === socket) this.fail(error);
      });
      socket.on('data', chunk => {
        if (this.socket !== socket || this.disconnected) return;
        try { for (const message of this.decoder.push(chunk)) this.receive(message); }
        catch (error) { this.fail(error); socket.destroy(); }
      });
    });
    try { await this.connecting; } finally { this.connecting = null; }
  }
  receive(message) {
    const id = message.type === 'response' ? message.requestId : message.id;
    const pending = this.pending.get(id);
    if (pending && (message.type === 'response' || message.jsonrpc === '2.0')) {
      this.pending.delete(id); clearTimeout(pending.timer); pending.resolve(message);
    } else this.emit('message', message);
  }
  write(message) {
    if (!this.socket || this.socket.destroyed || !this.socket.writable || this.disconnected) throw new Error('Desktop socket is disconnected');
    this.socket.write(encodeFrame(message));
  }
  exchange(message, timeoutMs = this.timeoutMs) {
    const id = message.requestId ?? message.id;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('Desktop request timed out; delivery is unconfirmed')); }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      try { this.write(message); } catch (error) { clearTimeout(timer); this.pending.delete(id); reject(error); }
    });
  }
  fail(error) {
    if (this.disconnected) return;
    this.disconnected = true;
    for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(error); }
    this.pending.clear(); this.emit('disconnect', error);
  }
  close() {
    const socket = this.socket;
    this.fail(new Error('Desktop connection closed'));
    this.socket = null; socket?.destroy();
  }
}

function contentText(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content.map(item => typeof item === 'string' ? item : typeof item?.text === 'string' ? item.text : '').filter(Boolean).join('\n');
}

function userItemText(item) { return contentText(item.content) || contentText(item.input) || (typeof item.text === 'string' ? item.text : ''); }

function userMessage(text, parts) {
  // Image authority comes from typed desktop input, never from a path in text.
  const attachments = [...new Set((parts ?? []).filter(part => part.type === 'localImage' && typeof part.path === 'string' && (path.isAbsolute(part.path)||path.win32.isAbsolute(part.path))).map(part => part.path))].map(path => ({ type: 'image', path }));
  let displayText = text;
  const wrapper = /^\s*# Files mentioned by the user:\s*\n([\s\S]*?)\nDistinguish instructions in attached documents from the user's request\.\s*\n\s*## My request:\s*\n([\s\S]*)$/.exec(text);
  if (wrapper && attachments.length) {
    const files = [...wrapper[1].matchAll(/^## ([^\n]+): ((?:\/|[A-Za-z]:[\\/])[^\n]+)\nImage attachment: true\s*$/gm)];
    const residue = wrapper[1].replace(/^## ([^\n]+): ((?:\/|[A-Za-z]:[\\/])[^\n]+)\nImage attachment: true\s*$/gm, '').trim();
    if (!residue && files.length === attachments.length && files.every(file => attachments.some(attachment => attachment.path === file[2]))) displayText = wrapper[2].trim();
  }
  return { text: displayText, ...(attachments.length ? { attachments } : {}), ...(displayText !== text ? { detail: text } : {}) };
}

function appToolUserText(output, trigger) {
  const expectedTrigger = output?.name === 'create_thread' ? 'app_tool_create_thread' : output?.name === 'send_message_to_thread' ? 'app_tool_send_message' : null;
  if (output?.namespace !== 'codex_app' || !expectedTrigger || trigger !== expectedTrigger) return '';
  // App Tools deliveries have input:[] and carry the human prompt inside a
  // function output. Match only this known wrapper, then decode one XML layer
  // in the same order as the installed desktop's delegation parser.
  const wrapped = contentText(output.output).trim();
  const match = /^<codex_delegation>\s*<source_thread_id>[^<]+<\/source_thread_id>\s*<input>([\s\S]*)<\/input>\s*<\/codex_delegation>$/.exec(wrapped);
  return match ? match[1].replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&amp;', '&') : '';
}

export function orderedTurns(state) {
  const history = state?.turnHistory?.kind === 'canonical' ? state.turnHistory.history : null;
  if (!history) return state?.turns ?? [];
  const seen = new Set(); const turns = [];
  for (const island of history.islands ?? []) for (const entry of island.entries ?? []) {
    const key = entry.value;
    if (!seen.has(key) && history.entitiesByKey?.[key]) { seen.add(key); turns.push(history.entitiesByKey[key]); }
  }
  return turns;
}

export function normalizeSnapshot(state, { hostId = state?.hostId ?? 'local', revision = null } = {}) {
  const turns = orderedTurns(state); const messages = []; const artifacts = []; const diffs = [];
  const userItems = turns.flatMap(turn => turn.items ?? []).filter(item => item.type === 'userMessage');
  const userIds = new Set(userItems.map(item => item.id).filter(Boolean));
  const userClientIds = new Set(userItems.map(item => item.clientId ?? item.clientUserMessageId).filter(Boolean));
  const settings = normalizeThreadSettings(state);
  for (const turn of turns) {
    const turnId = turn.turnId ?? turn.id;
    const items = turn.items ?? [];
    const hasUserItem = items.some(item => item.type === 'userMessage' && (userItemText(item) || item.content?.some(part => part.type === 'localImage')));
    const delegationTexts = new Map(items.filter(item => item.type === 'functionCallOutput').map(item => [item, appToolUserText(item, turn.params?.turnTrigger)]).filter(([, text]) => text));
    if (!hasUserItem) {
      const text = contentText(turn.params?.input) || appToolUserText(turn.params?.toolOutput, turn.params?.turnTrigger);
      const input = userMessage(text, turn.params?.input);
      if ((text || input.attachments?.length) && ![...delegationTexts.values()].includes(text)) messages.push({ id: `${turnId}:input`, turnId, role: 'user', ...input });
    }
    for (const [index, item] of items.entries()) {
      const base = { id: item.id ?? `${turnId}:item:${index}`, turnId, type: item.type, status: item.status };
      if (item.type === 'userMessage' || item.type === 'steeringUserMessage') {
        // A desktop steering placeholder can survive in an older turn after
        // the accepted user message appears in canonical history. Match only
        // server/client identity; identical text can be two real submissions.
        if (item.type === 'steeringUserMessage' && (userIds.has(item.serverUserMessageId) || userClientIds.has(item.serverClientUserMessageId ?? item.clientUserMessageId))) continue;
        const text = userItemText(item);
        const input = userMessage(text, [...(item.content ?? []), ...(item.input ?? [])]);
        if (text || input.attachments?.length) messages.push({ ...base, role: 'user', ...input, detail: JSON.stringify(item, null, 2) });
      } else if (delegationTexts.has(item)) {
        messages.push({ ...base, role: 'user', text: delegationTexts.get(item), source: 'desktop-app-tool-input' });
      } else if (item.type === 'agentMessage') {
        messages.push({ ...base, role: 'assistant', text: item.text ?? '', phase: item.phase });
      } else if (item.type === 'commandExecution') {
        const output = item.aggregatedOutput ?? item.output ?? '';
        messages.push({ ...base, role: 'tool', title: '命令执行', text: typeof output === 'string' ? output : output.text ?? JSON.stringify(output, null, 2), sourceTruncated: output?.truncated === true, detail: JSON.stringify(item, null, 2), command: item.command, cwd: item.cwd, exitCode: item.exitCode });
      } else if (item.type === 'fileChange') {
        diffs.push({ ...base, changes: item.changes ?? [] });
        messages.push({ ...base, role: 'tool', title: '文件修改', text: (item.changes ?? []).map(change => `${change.path}\n${change.diff ?? ''}`).join('\n'), detail: JSON.stringify(item, null, 2) });
      } else if (item.type === 'reasoning') {
        // Only desktop-visible summaries are transportable. Never forward raw
        // reasoning content or encrypted reasoning, even in the details view.
        const text = contentText(item.summary);
        if (text) messages.push({ ...base, role: 'summary', title: '思考摘要', text });
      } else if (!['hookPrompt', 'systemMessage', 'developerMessage'].includes(item.type)) {
        const titles = { mcpToolCall: '工具调用', dynamicToolCall: '工具调用', functionCall: '工具调用', functionCallOutput: '工具结果', plan: '计划', collabAgentToolCall: '协作 Agent', subAgentActivity: 'Agent 动态', webSearch: '网页搜索', imageView: '查看图片', imageGeneration: '生成图片', sleep: '等待', contextCompaction: '上下文压缩', modelChanged: '模型变更', enteredReviewMode: '进入审查', exitedReviewMode: '审查结果' };
        const relatedThreads = [...new Set([item.agentThreadId, ...(item.receiverThreadIds ?? [])].filter(value => typeof value === 'string' && value))].map(id => ({ id, hostId, title: `Agent · ${id.slice(-6)}` }));
        messages.push({ ...base, role: 'tool', title: titles[item.type] ?? item.type, tool: item.tool ?? item.name, relatedThreads, text: (item.text ?? item.query ?? contentText(item.output)) || JSON.stringify(item, null, 2), detail: JSON.stringify(item, null, 2) });
        if (['imageGeneration', 'imageView'].includes(item.type)) {
          const imagePath = item.savedPath ?? item.path ?? item.result;
          if (typeof imagePath === 'string' && (path.isAbsolute(imagePath)||path.win32.isAbsolute(imagePath))) artifacts.push({ ...base, path: imagePath });
        }
      }
    }
    if (turn.diff) {
      diffs.push({ id: `${turnId}:diff`, turnId, diff: turn.diff });
      messages.push({ id: `${turnId}:diff`, turnId, role: 'tool', type: 'turnDiff', title: '本轮修改汇总', text: turn.diff });
    }
    if (turn.error) messages.push({ id: `${turnId}:error`, turnId, role: 'tool', type: 'turnError', title: '运行错误', status: 'failed', text: JSON.stringify(turn.error, null, 2) });
  }
  messages.forEach((message, order) => { message.key = `${message.turnId}:${message.id}`; message.order = order; });
  const activeTurn = [...turns].reverse().find(turn => turn.status === 'inProgress');
  return {
    thread: { id: state.id, title: state.title || state.fallbackSearchTitle || 'Untitled', cwd: state.cwd, hostId,
      status: state.threadRuntimeStatus?.type ?? state.status ?? (activeTurn ? 'active' : 'idle'),
      model: settings.model, reasoningEffort: settings.reasoningEffort, settings, currentPermissions: state.currentPermissions ?? null, updatedAt: state.updatedAt,
      lastTurnId: turns.at(-1)?.turnId ?? turns.at(-1)?.id ?? null, lastTurnStatus: turns.at(-1)?.status ?? null, activeTurnId: activeTurn?.turnId ?? activeTurn?.id ?? null },
    messages, requests: (state.requests ?? []).map(request => ({ id: request.id, method: request.method, params: request.params })),
    artifacts, diffs, revision, source: 'desktop-live',
    historyComplete: state.turnHistory?.history?.isComplete ?? state.turnsPagination?.hasLoadedOldest ?? false,
  };
}

export function applyPatches(state, patches) {
  const result = structuredClone(state);
  for (const patch of patches) {
    if (!Array.isArray(patch.path) || !patch.path.length || patch.path.some(key => FORBIDDEN_KEYS.has(String(key)))) throw new Error('Unsupported desktop patch path');
    let object = result;
    for (const key of patch.path.slice(0, -1)) {
      if (object == null || !Object.hasOwn(object, key)) throw new Error('Desktop patch target missing');
      object = object[key];
    }
    const key = patch.path.at(-1);
    if (patch.op === 'remove') { if (Array.isArray(object)) object.splice(Number(key), 1); else delete object[key]; }
    else if (patch.op === 'replace' || patch.op === 'add') {
      const value = structuredClone(patch.value);
      if (patch.op === 'add' && Array.isArray(object)) object.splice(Number(key), 0, value); else object[key] = value;
    } else throw new Error('Unsupported desktop patch operation');
  }
  return result;
}

export function modelsFromTools(tools) {
  const description = tools.find(tool => tool.name === 'send_message_to_thread')?.inputSchema?.properties?.model?.description ?? '';
  const models = [];
  const pattern = /\b(gpt-[a-zA-Z0-9.-]+)\s*\(([^)]*)\)/g;
  for (const match of description.matchAll(pattern)) {
    const efforts = /supported reasoning efforts:\s*([^;)]*)/.exec(match[2])?.[1]?.split(',').map(value => value.trim()).filter(Boolean) ?? [];
    models.push({ id: match[1], reasoningEfforts: efforts });
  }
  return models;
}

async function findAppToolsPath() {
  if (process.env.CODEX_APP_TOOLS_PIPE_PATH) return process.env.CODEX_APP_TOOLS_PIPE_PATH;
  const directory = '/tmp/codex-app-tools'; const paths = [];
  for (const name of await readdir(directory)) {
    if (!name.endsWith('.sock')) continue;
    const candidate = path.join(directory, name); const stat = await lstat(candidate);
    if (stat.isSocket() && stat.uid === process.getuid?.()) paths.push(candidate);
  }
  if (paths.length !== 1) throw new Error('Set CODEX_APP_TOOLS_PIPE_PATH to the running desktop socket');
  return paths[0];
}

export class DesktopAdapter extends EventEmitter {
  constructor(options = {}) {
    super(); this.options = options; this.timeoutMs = options.timeoutMs ?? 15000;
    this.callerThreadId = options.callerThreadId ?? process.env.CODEX_DESKTOP_CONTEXT_THREAD_ID ?? process.env.CODEX_THREAD_ID;
    this.snapshots = new Map(); this.owners = new Map(); this.subscriptions = new Map();
    this.ready = false; this.tools = []; this.ipc = null; this.appTools = null; this.connecting = null; this.incompatible = false;
  }
  async connect() {
    if (this.incompatible) throw new Error('Desktop stream version changed; update and restart the companion.');
    if (this.ready) return this.diagnostics();
    if (this.connecting) return this.connecting;
    this.connecting = this.connectDesktop();
    try { return await this.connecting; } finally { this.connecting = null; }
  }
  async connectDesktop() {
    this.close();
    if (!this.callerThreadId) throw new Error('Set CODEX_DESKTOP_CONTEXT_THREAD_ID to an existing desktop chat ID');
    const discovered = this.options.ipcPath && this.options.toolsPath ? this.options : await findDesktopEndpoints();
    const ipcPath = this.options.ipcPath ?? discovered.ipcPath;
    const toolsPath = this.options.toolsPath ?? discovered.toolsPath;
    this.ipc = new FramedSocket(ipcPath, this.timeoutMs);
    this.appTools = new FramedSocket(toolsPath, this.timeoutMs);
    this.ipc.on('message', message => this.handleIpc(message));
    for (const transport of [this.ipc, this.appTools]) transport.on('disconnect', error => {
      if (this.ready) { this.ready = false; this.emit('event', { type: 'desktop.disconnected', error: error.message }); }
    });
    try {
      await this.ipc.connect();
      const initialized = await this.ipc.exchange({ type: 'request', requestId: randomUUID(), sourceClientId: 'initializing-client', version: 0, method: 'initialize', params: { clientType: 'desktop' } });
      if (initialized.resultType !== 'success') throw new Error(initialized.error ?? 'Desktop initialization failed');
      this.clientId = initialized.result.clientId;
      await this.appTools.connect();
      const listed = await this.appTools.exchange({ id: randomUUID(), jsonrpc: '2.0', method: 'tools/list', params: { threadStartKind: 'all' } });
      if (listed.error) throw new Error(listed.error.message);
      this.tools = listed.result.tools;
      this.ready = true; this.emit('event', { type: 'desktop.ready', capabilities: this.diagnostics().capabilities });
      return this.diagnostics();
    } catch (error) { this.close(); throw error; }
  }
  diagnostics() {
    const names = new Set(this.tools.map(tool => tool.name));
    return { connected: this.ready, backend: 'existing-desktop', streamVersion: VERSIONS['thread-stream-state-changed'],
      capabilities: { 'threads.list': names.has('list_threads'), 'projects.list': names.has('list_projects'),
        'thread.read': names.has('read_thread'), 'message.send': names.has('send_message_to_thread'),
        'thread.create': names.has('create_thread'), 'models.list': modelsFromTools(this.tools).length > 0,
        'turn.stop': true, 'approval.respond': true, 'thread.settings.update': true },
      limitations: ['Private desktop IPC may change after an app update.', 'Stop and approval require a live desktop owner.', 'Command, file and user-input approval mappings have mock coverage; live mutation is unverified.'] };
  }
  modelCatalog() { return modelsFromTools(this.tools); }
  async tool(name, args) {
    if (!this.ready) throw new Error('Desktop is disconnected');
    const tool = this.tools.find(value => value.name === name);
    if (!tool) throw new Error('Desktop capability unavailable');
    const response = await this.appTools.exchange({ id: randomUUID(), jsonrpc: '2.0', method: 'tools/call', params: {
      namespace: tool.namespace, tool: name, arguments: args, callerSource: 'codex', hostId: 'local',
      threadId: this.callerThreadId, turnId: 'codex-pocket-human-request', callId: randomUUID(),
    } });
    if (response.error) throw new Error(response.error.message);
    const result = response.result; const text = (result.contentItems ?? []).filter(item => item.type === 'inputText').map(item => item.text).join('\n');
    if (!result.success) throw new Error(text || 'Desktop rejected the request');
    try { return JSON.parse(text); } catch { return { text }; }
  }
  async request(method, params, options = {}) {
    if (!Object.hasOwn(VERSIONS, method) || !method.startsWith('thread-')) throw new Error('Unsupported desktop IPC method');
    const hostId = options.hostId ?? 'local';
    let version = VERSIONS[method];
    if (method.startsWith('thread-follower-') && hostId !== 'local') version += 1;
    if (method === 'thread-follower-interrupt-turn' && hostId === 'local' && params.expectedTurnId == null) version = 3;
    const response = await this.ipc.exchange({ type: 'request', requestId: randomUUID(), sourceClientId: this.clientId, version, method, params,
      ...(options.targetClientId ? { targetClientId: options.targetClientId } : {}),
      ...(hostId === 'local' ? {} : { hostId }), timeoutMs: options.timeoutMs ?? this.timeoutMs }, options.timeoutMs);
    if (response.resultType !== 'success') throw Object.assign(new Error(response.error ?? 'Desktop request failed'), { rejected: true });
    return response;
  }
  broadcast(method, params, targetClientIds) {
    this.ipc.write({ type: 'broadcast', method, params, sourceClientId: this.clientId, version: VERSIONS[method], ...(targetClientIds ? { targetClientIds } : {}) });
  }
  handleIpc(message) {
    if (message.type === 'client-discovery-request') { this.ipc.write({ type: 'client-discovery-response', requestId: message.requestId, response: { canHandle: false } }); return; }
    this.emit('notification', message);
    if (message.type !== 'broadcast') return;
    if (message.method === 'thread-stream-following-status-requested') {
      const p = message.params; const key = `${p.hostId}:${p.conversationId}`;
      if (this.subscriptions.has(key)) this.broadcast('thread-stream-following-changed', { hostId: p.hostId, conversationId: p.conversationId, following: true }, [message.sourceClientId]);
      return;
    }
    if (message.method !== 'thread-stream-state-changed') return;
    if (message.version !== VERSIONS[message.method]) {
      this.incompatible = true; this.close();
      this.emit('event', { type: 'desktop.incompatible', error: 'Desktop stream version changed; update and restart the companion.' }); return;
    }
    const { hostId, conversationId, change } = message.params;
    const key = `${hostId}:${conversationId}`;
    if (!this.subscriptions.has(key)) return;
    const current = this.snapshots.get(key);
    try {
      if (change.type === 'snapshot') this.snapshots.set(key, { revision: change.revision, state: change.conversationState });
      else if (change.type === 'patches' && current?.revision === change.baseRevision && !(change.acceptedTextChanges?.length)) {
        this.snapshots.set(key, { revision: change.revision, state: applyPatches(current.state, change.patches) });
      } else { this.broadcast('thread-stream-following-changed', { conversationId, hostId, following: true }, [message.sourceClientId]); return; }
      this.owners.set(key, message.sourceClientId);
      const snapshot = this.snapshots.get(key);
      this.emit('event', { type: 'thread.updated', threadId: conversationId, data: normalizeSnapshot(snapshot.state, { hostId, revision: snapshot.revision }) });
    } catch { this.broadcast('thread-stream-following-changed', { conversationId, hostId, following: true }, [message.sourceClientId]); }
  }
  async owner(threadId, hostId = 'local') {
    const response = await this.request('thread-owner-discovery', { conversationId: threadId, hostId });
    const key = `${hostId}:${threadId}`; this.owners.set(key, response.handledByClientId); return response.handledByClientId;
  }
  async readThread(args, { owner: knownOwner } = {}) {
    const threadId = requireString(args.threadId, 'threadId'); const hostId = args.hostId ?? 'local'; const key = `${hostId}:${threadId}`;
    try {
      const owner = knownOwner ?? await this.owner(threadId, hostId); this.subscriptions.set(key, { threadId, hostId });
      // The phone displays one chat at a time; retain only a small recent set.
      while (this.subscriptions.size > 4) {
        const [oldKey, old] = this.subscriptions.entries().next().value;
        this.broadcast('thread-stream-following-changed', { conversationId: old.threadId, hostId: old.hostId, following: false }, this.owners.has(oldKey) ? [this.owners.get(oldKey)] : undefined);
        this.subscriptions.delete(oldKey); this.snapshots.delete(oldKey); this.owners.delete(oldKey);
      }
      const snapshotPromise = new Promise((resolve, reject) => {
        const listener = event => { if (event.type === 'thread.updated' && event.threadId === threadId && event.data.thread.hostId === hostId) { clearTimeout(timer); this.off('event', listener); resolve(event.data); } };
        const timer = setTimeout(() => { this.off('event', listener); reject(new Error('Desktop snapshot timed out')); }, Math.min(this.timeoutMs, 5000));
        this.on('event', listener);
      });
      this.broadcast('thread-stream-following-changed', { conversationId: threadId, hostId, following: true }, [owner]);
      const initial = await snapshotPromise;
      if (initial.historyComplete) return initial;
      try {
        const loaded = await this.request('thread-follower-load-complete-history', { conversationId: threadId }, { hostId, targetClientId: owner, timeoutMs: 20000 });
        const revision = loaded.result?.revision;
        const current = () => this.snapshots.get(key);
        const matches = () => current() && (revision == null || current().revision >= revision) && normalizeSnapshot(current().state, { hostId }).historyComplete;
        if (!matches()) await new Promise((resolve, reject) => {
          const listener = () => { if (matches()) { clearTimeout(timer); this.off('event', listener); resolve(); } };
          const timer = setTimeout(() => { this.off('event', listener); reject(new Error('完整历史尚未同步，请刷新重试')); }, 4000);
          this.on('event', listener); listener();
        });
        return normalizeSnapshot(current().state, { hostId, revision: current().revision });
      } catch (error) { return { ...initial, historyError: error.message }; }
    } catch (error) {
      const result = await this.tool('read_thread', { threadId, hostId, turnLimit: Math.min(10, Math.max(1, args.turnLimit ?? 10)), includeOutputs: true, maxOutputCharsPerItem: 20000 });
      const turns = result.page?.order === 'newest_first' ? [...result.turns].reverse() : result.turns;
      const normalized = normalizeSnapshot({ ...result.thread, turns, requests: [] }, { hostId });
      return { ...normalized, source: 'desktop-history-summary', live: false, liveUnavailable: error.message, page: result.page };
    }
  }
  async localCatalog() {
    if (this.options.catalog === false) return { threads: [], projects: [], localComplete: false, limitations: ['Full local catalog is disabled.'] };
    return readDesktopCatalog({ codexHome: this.options.codexHome, dbPath: this.options.catalogDbPath, globalStatePath: this.options.catalogGlobalStatePath });
  }
  async listThreads(args) {
    const [result, catalog] = await Promise.all([this.tool('list_threads', { limit: 50 }), this.localCatalog()]);
    const unique = new Map(catalog.threads.map(thread => [threadKey(thread), thread]));
    for (const thread of [...(result.threads ?? []), ...(result.pinnedThreads ?? [])]) {
      const key = threadKey(thread); const stored = unique.get(key); const merged = { ...stored, ...thread };
      if (stored?.membershipKnown) { merged.projectId = stored.projectId; merged.membershipSource = stored.membershipSource; }
      unique.set(key, merged);
    }
    let threads = [...unique.values()].filter(thread => (args.includeArchived === true || !thread.archived) && (args.includeSubagents === true || !thread.isSubagent));
    if (Object.hasOwn(args, 'projectId')) threads = threads.filter(thread => thread.projectId === args.projectId);
    if (args.hostId) threads = threads.filter(thread => (thread.hostId ?? 'local') === args.hostId);
    threads.sort((a, b) => (a.pinnedIndex ?? (a.isPinned ? 0 : Infinity)) - (b.pinnedIndex ?? (b.isPinned ? 0 : Infinity)) ||
      (a.projectId === b.projectId && a.projectIndex != null && b.projectIndex != null ? a.projectIndex - b.projectIndex : 0) ||
      (Date.parse(b.recencyAt ?? b.updatedAt) || 0) - (Date.parse(a.recencyAt ?? a.updatedAt) || 0));
    return { ...result, threads, pinnedThreads: threads.filter(thread => thread.pinnedIndex != null || thread.isPinned),
      catalog: { source: 'desktop-local-metadata+app-tools', localComplete: catalog.localComplete, remoteComplete: false, chatgptComplete: false, limitations: catalog.limitations } };
  }
  async listProjects() {
    const [result, catalog] = await Promise.all([this.tool('list_projects', {}), this.localCatalog()]);
    const projects = new Map(catalog.projects.map(project => [`${project.projectKind}:${project.hostId ?? 'local'}:${project.projectId}`, project]));
    for (const project of result.projects ?? []) {
      const key = `${project.projectKind}:${project.hostId ?? 'local'}:${project.projectId}`; const stored = projects.get(key);
      projects.set(key, { ...stored, ...project, rootPaths: stored?.rootPaths?.length ? stored.rootPaths : project.path ? [project.path] : [] });
    }
    return { ...result, projects: [...projects.values()], catalog: { source: 'desktop-local-metadata+app-tools', localComplete: catalog.localComplete, limitations: catalog.limitations } };
  }
  async updateSettings(args, { owner: knownOwner } = {}) {
    const allowed = new Set(['threadId', 'hostId', 'model', 'reasoningEffort', 'permissionMode']);
    if (!args || typeof args !== 'object' || Array.isArray(args) || Object.keys(args).some(key => !allowed.has(key))) throw new Error('Unsupported thread setting field');
    const threadId = requireString(args.threadId, 'threadId'); const hostId = args.hostId == null ? 'local' : requireString(args.hostId, 'hostId'); const threadSettings = {};
    const models = this.modelCatalog();
    if (Object.hasOwn(args, 'model')) {
      requireString(args.model, 'model', 200);
      if (!models.some(model => model.id === args.model)) throw new Error('Model is not available in the desktop catalog');
      threadSettings.model = args.model;
    }
    if (Object.hasOwn(args, 'permissionMode')) {
      if (!Object.hasOwn(PERMISSION_MODES, args.permissionMode)) throw new Error('Unsupported permission mode');
      Object.assign(threadSettings, PERMISSION_MODES[args.permissionMode]);
    }
    const owner = knownOwner ?? await this.owner(threadId, hostId);
    const before = (await this.request('thread-follower-read-model-settings', { conversationId: threadId }, { hostId, targetClientId: owner })).result.settings;
    if (Object.hasOwn(args, 'reasoningEffort')) {
      const selected = models.find(model => model.id === (args.model ?? before?.model));
      if (args.reasoningEffort !== null && (typeof args.reasoningEffort !== 'string' || !selected?.reasoningEfforts.includes(args.reasoningEffort))) throw new Error('Reasoning effort is not supported by this model');
      threadSettings.effort = args.reasoningEffort;
    } else if (args.model && before?.reasoningEffort != null && !models.find(model => model.id === args.model).reasoningEfforts.includes(before.reasoningEffort)) {
      throw new Error('Select a supported reasoning effort with this model');
    }
    if (!Object.keys(threadSettings).length) throw new Error('No thread settings were supplied');
    const response = await this.request('thread-follower-update-thread-settings', { conversationId: threadId, threadSettings,
      ...(before ? { condition: { ifModelEquals: before.model, ifEffortEquals: before.reasoningEffort } } : {}) }, { hostId, targetClientId: owner });
    const applied = response.result.applied === true;
    let settings = null; let verified = false;
    if (applied) {
      // A transport success alone does not confirm that the setting persisted.
      // Refresh the actual owner snapshot; never synthesize a saved UI state.
      try {
        const snapshot = await this.readThread({ threadId, hostId });
        if (snapshot.source === 'desktop-live') {
          settings = snapshot.thread.settings;
          verified = (!Object.hasOwn(args, 'model') || settings.model === args.model) &&
            (!Object.hasOwn(args, 'reasoningEffort') || settings.reasoningEffort === args.reasoningEffort) &&
            (!Object.hasOwn(args, 'permissionMode') || settings.permissionMode === args.permissionMode);
        }
      } catch {}
    }
    return { threadId, hostId, applied, verified, appliesTo: 'subsequent-turns', settings,
      ...(!applied ? { error: 'Thread settings changed concurrently; refresh and try again.' } : !verified ? { error: 'The desktop accepted the update, but a fresh settings read could not confirm it. Refresh before retrying.' } : {}) };
  }
  async call(action, args = {}) {
    if (!this.ready) throw new Error('Desktop is disconnected');
    switch (action) {
      case 'threads.list': return this.listThreads(args);
      case 'projects.list': return this.listProjects();
      case 'models.list': return { models: modelsFromTools(this.tools), source: 'desktop-capability-catalog' };
      case 'thread.read': return this.readThread(args);
      case 'thread.settings.update': return this.updateSettings(args);
      case 'message.send': {
        const threadId = requireString(args.threadId, 'threadId'); const prompt = requireString(args.text ?? args.prompt, 'text', 100000);
        return this.tool('send_message_to_thread', { threadId, prompt, ...(args.hostId ? { hostId: args.hostId } : {}), ...(args.model ? { model: args.model } : {}), ...(args.thinking ? { thinking: args.thinking } : {}) });
      }
      case 'thread.create': {
        const prompt = requireString(args.text ?? args.prompt, 'text', 100000);
        const target = args.projectId ? { type: 'project', projectId: requireString(args.projectId, 'projectId'), environment: { type: 'local' } } : { type: 'projectless' };
        return this.tool('create_thread', { prompt, target, ...(args.title ? { title: args.title } : {}), ...(args.model ? { model: args.model } : {}), ...(args.thinking ? { thinking: args.thinking } : {}) });
      }
      case 'turn.stop': {
        const threadId = requireString(args.threadId, 'threadId'); const hostId = args.hostId ?? 'local'; const key = `${hostId}:${threadId}`;
        const owner = await this.owner(threadId, hostId);
        const active = this.snapshots.get(key);
        const expectedTurnId = args.turnId ?? (active && normalizeSnapshot(active.state).thread.activeTurnId);
        if (!expectedTurnId) throw new Error('Read the active thread before stopping; an exact active turn ID is required');
        return (await this.request('thread-follower-interrupt-turn', { conversationId: threadId, mode: 'user-stop', expectedTurnId }, { hostId, targetClientId: owner })).result;
      }
      case 'approval.respond': {
        const threadId = requireString(args.threadId, 'threadId'); const hostId = args.hostId ?? 'local';
        const snapshot = this.snapshots.get(`${hostId}:${threadId}`);
        const request = snapshot?.state.requests?.find(item => String(item.id) === String(args.requestId) && item.completed !== true);
        if (!request) throw new Error('Approval is no longer pending; refresh the thread');
        const result=approvalResponse(request,args);
        const method=request.method==='item/commandExecution/requestApproval'?'thread-follower-command-approval-decision':request.method==='item/fileChange/requestApproval'?'thread-follower-file-approval-decision':request.method==='item/permissions/requestApproval'?'thread-follower-permissions-request-approval-response':request.method==='mcpServer/elicitation/request'?'thread-follower-submit-mcp-server-elicitation-response':'thread-follower-submit-user-input';
        const response=['item/commandExecution/requestApproval','item/fileChange/requestApproval'].includes(request.method)?result:{response:result};
        const owner = await this.owner(threadId, hostId);
        if (!this.snapshots.get(`${hostId}:${threadId}`)?.state.requests?.some(item => item.id === request.id && item.method === request.method && item.completed !== true)) throw new Error('Approval is no longer pending; refresh the thread');
        return (await this.request(method, { conversationId: threadId, requestId: request.id, ...response }, { hostId, targetClientId: owner })).result;
      }
      default: throw new Error('Unsupported phone action');
    }
  }
  close() {
    this.ready = false;
    for (const [key, sub] of this.subscriptions) {
      try { this.broadcast('thread-stream-following-changed', { conversationId: sub.threadId, hostId: sub.hostId, following: false }, this.owners.has(key) ? [this.owners.get(key)] : undefined); } catch {}
    }
    this.subscriptions.clear(); this.snapshots.clear(); this.owners.clear(); this.ipc?.close(); this.appTools?.close();
  }
}

function requireString(value, name, maximum = 512) {
  if (typeof value !== 'string' || !value.trim() || value.length > maximum) throw new Error(`Invalid ${name}`);
  return value;
}
