import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { DesktopAdapter, normalizeQueuedMessages, normalizeSnapshot } from '../desktop-adapter.mjs';
import { StandaloneAdapter } from '../standalone-adapter.mjs';
import { HistoryStore } from '../history.mjs';

const queued = (id, text = id) => ({ id, text, context: { prompt: text, developerInstructions: 'PRIVATE', imageAttachments: [], fileAttachments: [] } });
const state = { id: 'qa', turns: [{ id: 'A', status: 'inProgress', items: [{ id: 'question', type: 'userMessage', content: [{ type: 'text', text: 'Run A' }] }] }] };
const envelope = (messages, overrides = {}) => ({ type: 'broadcast', method: 'thread-queued-followups-changed', version: 2, sourceClientId: 'owner', params: { hostId: 'local', conversationId: 'qa', messages }, ...overrides });
function fixture(t, options = {}) {
  const a = new DesktopAdapter({ readQueuedFollowUps: async () => ({}), ...options });
  a.broadcast = () => {};
  a.subscriptions.set('local:qa', { threadId: 'qa', hostId: 'local', owner: 'owner', streamId: 'stream' });
  a.snapshots.set('local:qa', { revision: 10, state });
  t.after(() => a.close()); return a;
}

test('queued input appears after running A, preserving order, paused/delivery states and only visible fields', () => {
  const pending = normalizeQueuedMessages([queued('B', 'After A'), { ...queued('C'), pausedReason: 'PRIVATE reason' }, { ...queued('D'), submission: { status: 'sending' } }, { ...queued('E'), submission: { status: 'outcome-unknown' } }, queued('B'), null, { text: 'malformed' }]);
  const result = normalizeSnapshot(state, { queuedMessages: pending });
  assert.deepEqual(result.messages.map(m => m.text), ['Run A', 'After A', 'C', 'D', 'E']);
  assert.deepEqual(result.messages.slice(1).map(m => m.status), ['queued', 'paused', 'sending', 'outcome-unknown']);
  assert(result.messages.slice(1).every(m => m.type === 'queuedUserMessage' && m.role === 'user'));
  assert.equal(result.thread.activeTurnId, 'A'); assert(!JSON.stringify(result).includes('PRIVATE'));
});

test('server queues decode typed input and canonical client identity removes only the sent item', () => {
  const pending = normalizeQueuedMessages([{ id: 'server-B', clientUserMessageId: 'client-B', input: [{ type: 'text', text: 'same' }] }, { id: 'server-C', clientUserMessageId: 'client-C', input: [{ type: 'text', text: 'same' }] }]);
  const next = structuredClone(state);
  next.turns.push({ id: 'B', items: [{ id: 'accepted', type: 'userMessage', clientId: 'client-B', content: [{ type: 'text', text: 'same' }] }] });
  const result = normalizeSnapshot(next, { queuedMessages: pending });
  assert.deepEqual(result.messages.map(m => m.id), ['question', 'accepted', 'server-C']);
  next.turns.at(-1).items = []; next.turns.at(-1).params = { clientUserMessageId: 'client-B', input: [{ type: 'text', text: 'same' }] };
  assert.equal(normalizeSnapshot(next, { queuedMessages: pending }).messages.filter(m => m.type === 'queuedUserMessage').length, 1);
  const image = normalizeQueuedMessages([{ id: 'image-only', context: { imageAttachments: [{ src: '/private/image.png' }] } }])[0];
  assert.equal(image.text, '附带 1 张图片'); assert(!JSON.stringify(image).includes('/private'));
});

test('live queue add, edit, reorder and clear publish at unchanged conversation revision', t => {
  const a = fixture(t), events = []; a.on('event', e => events.push(e));
  a.handleIpc(envelope([queued('B'), queued('C')]));
  assert.deepEqual(events.at(-1).data.messages.slice(1).map(m => m.id), ['B', 'C']);
  a.handleIpc(envelope([queued('C', 'edited'), queued('B')]));
  assert.deepEqual(events.at(-1).data.messages.slice(1).map(m => m.text), ['edited', 'B']);
  const latest = events.at(-1).data;
  a.handleIpc(envelope([queued('C', 'edited'), queued('B')])); assert.equal(events.length, 2);
  a.handleIpc(envelope([])); assert.equal(events.at(-1).data.messages.length, 1);
  assert.equal(events.at(-1).data.revision, 10); assert(events.at(-1).data.queueRevision > latest.queueRevision);
  const store = new HistoryStore(); store.put(events.at(-1).data); store.put(latest);
  assert.equal(store.page({ threadId: 'qa' }).messages.length, 1, 'late read cannot resurrect removed queue at same stream revision');
});

test('foreign owner, host, unknown protocol and unsubscribed queues cannot change this conversation', t => {
  const a = fixture(t);
  for (const event of [envelope([queued('B')], { version: 1 }), envelope([queued('B')], { sourceClientId: 'other' }), envelope([queued('B')], { params: { hostId: 'remote', conversationId: 'qa', messages: [queued('B')] } }), envelope(null)]) a.handleIpc(event);
  assert.equal(a.snapshotData('local:qa').messages.length, 1);
});

test('read-only persisted state restores pre-existing queues and polling clears removed items', async t => {
  const root = await mkdtemp(new URL('../.private/queue-test-', import.meta.url).pathname); t.after(() => rm(root, { recursive: true, force: true }));
  const globalStatePath = `${root}/global.json`;
  await writeFile(globalStatePath, JSON.stringify({ 'queued-follow-ups': { qa: [queued('B'), { ...queued('remote'), submission: { hostId: 'remote' } }] } }));
  const a = fixture(t, { readQueuedFollowUps: undefined, globalStatePath });
  await a.refreshQueuedMessages(); assert.deepEqual(a.snapshotData('local:qa').messages.map(m => m.id), ['question', 'B']);
  await writeFile(globalStatePath, '{'); await a.refreshQueuedMessages(); assert.equal(a.snapshotData('local:qa').messages.length, 2);
  await writeFile(globalStatePath, JSON.stringify({ 'queued-follow-ups': {} }));
  await a.refreshQueuedMessages(); assert.equal(a.snapshotData('local:qa').messages.length, 1);
});

test('live updates outrank an in-flight persisted read and a retired subscription', async t => {
  let resolve;
  const a = fixture(t, { readQueuedFollowUps: () => new Promise(r => { resolve = r; }) });
  const reading = a.refreshQueuedMessages();
  a.handleIpc(envelope([queued('new')])); resolve({ qa: [queued('old')] }); await reading;
  assert.equal(a.snapshotData('local:qa').messages.at(-1).id, 'new');
  const retired = a.refreshQueuedMessages(); a.clearStreamState(); resolve({ qa: [queued('old')] }); await retired;
  assert.equal(a.subscriptions.size, 0); assert.equal(a.snapshots.size, 0);
});

test('first thread read includes existing queue and starts a bounded watcher which closes cleanly', async t => {
  const a = fixture(t, { queuePollMs: 10, readQueuedFollowUps: async () => ({ qa: [queued('B')] }) });
  a.owner = async () => 'owner';
  const read = await a.readThread({ threadId: 'qa' }, { loadHistory: false });
  assert.equal(read.messages.at(-1).text, 'B'); assert(a.queuedRefreshTimer);
  a.close(); assert.equal(a.queuedRefreshTimer, null);
});

class Server extends EventEmitter { close() {} }
test('service queue list is read-only, paged, combines with desktop queue and clears when sent', async t => {
  const server = new Server(), calls = []; let empty = false;
  server.request = async (method, params) => {
    calls.push({ method, params });
    return empty ? { data: [], nextCursor: null } : params.cursor ? { data: [{ id: 'server-C', clientUserMessageId: 'C', input: [{ type: 'text', text: 'C' }] }], nextCursor: null } : { data: [{ id: 'server-B', clientUserMessageId: 'B', input: [{ type: 'text', text: 'B' }] }], nextCursor: 'page2' };
  };
  const a = new StandaloneAdapter({ server, ipcPath: '/no-user-ipc.sock', readQueuedFollowUps: async () => ({ qa: [queued('B'), queued('D')] }) }); t.after(() => a.close()); a.ready = true; a.broadcast = () => {};
  a.subscriptions.set('local:qa', { threadId: 'qa', hostId: 'local', owner: 'owner' }); a.snapshots.set('local:qa', { revision: 1, state });
  await a.refreshQueuedMessages();
  assert.deepEqual(a.snapshotData('local:qa').messages.slice(1).map(m => m.id), ['server-B', 'server-C', 'D']);
  assert(calls.every(c => c.method === 'thread/queue/list'));
  empty = true; a.options.readQueuedFollowUps = async () => ({}); await a.refreshQueuedMessages();
  assert.equal(a.snapshotData('local:qa').messages.length, 1);
  server.request = async () => { throw Object.assign(new Error('Unsupported'), { code: -32601 }); };
  assert.equal(await a.readServerQueuedMessages('qa', 'local'), null); assert(a.serverQueueUnsupported);
});

test('paging retains queue state/status and does not truncate long queued content without a detail route', t => {
  const a = fixture(t); a.handleIpc(envelope([queued('B', '长内容'.repeat(5000))]));
  const store = new HistoryStore(), page = store.put(a.snapshotData('local:qa'));
  const message = page.messages.at(-1); assert.equal(message.type, 'queuedUserMessage'); assert.equal(message.status, 'queued'); assert.equal(message.textComplete, false);
  assert.equal(store.read({ threadId: 'qa', messageKey: message.key, version: message.contentVersion }).text, '长内容'.repeat(5000));
});

test('a service queue read started before a live edit cannot restore its stale result', async t => {
  const a = fixture(t);
  let finish, started;
  const readingStarted = new Promise(resolve => { started = resolve; });
  a.readServerQueuedMessages = () => new Promise(resolve => { finish = resolve; started(); });
  const refresh = a.refreshQueuedMessages(); await readingStarted;
  a.handleIpc(envelope([queued('new')]));
  finish([{ id: 'server-old', input: [{ type: 'text', text: 'stale' }] }]); await refresh;
  assert.deepEqual(a.snapshotData('local:qa').messages.slice(1).map(m => m.id), ['new']);
});
