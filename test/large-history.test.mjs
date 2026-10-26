import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { DesktopAdapter, FrameDecoder, encodeFrame, applyPatches } from '../desktop-adapter.mjs';
import { StandaloneAdapter } from '../standalone-adapter.mjs';
import { AppServerClient } from '../app-server-client.mjs';
import { HistoryStore } from '../history.mjs';

const envelope = (change, owner = 'owner') => ({ type: 'broadcast', method: 'thread-stream-state-changed', version: 11, sourceClientId: owner, params: { hostId: 'local', conversationId: 'qa', change } });
const state = (text = 'before') => ({ id: 'qa', cwd: '/qa', turnsPagination: { hasLoadedOldest: true }, turns: [{ id: 'turn', status: 'completed', items: [{ id: 'a', type: 'agentMessage', text }] }] });

test('a 44 MB snapshot uses one payload allocation across thousands of chunks', () => {
  const value = { text: '汉字🦊'.repeat(4_400_000) }; // 44 MB UTF-8, like the reported chat.
  const frame = encodeFrame(value), decoder = new FrameDecoder(), messages = [];
  decoder.push(frame.subarray(0, 4)); const allocation = decoder.payload;
  for (let i = 4; i < frame.length; i += 16384) {
    assert.equal(decoder.payload, allocation);
    messages.push(...decoder.push(frame.subarray(i, i + 16384)));
  }
  assert.equal(messages.length, 1); assert.equal(messages[0].text, value.text);
  assert.equal(decoder.buffer.length, 0);
});

test('text patches share untouched history and a failed batch leaves the original intact', () => {
  const original = { history: Object.freeze({ large: 'output'.repeat(100000) }), turns: [{ items: [{ text: 'a' }, { text: 'b' }] }] };
  const changed = applyPatches(original, [{ op: 'replace', path: ['turns', 0, 'items', 1, 'text'], value: 'new' }]);
  assert.equal(changed.history, original.history); assert.equal(changed.turns[0].items[0], original.turns[0].items[0]);
  assert.notEqual(changed.turns[0], original.turns[0]); assert.equal(original.turns[0].items[1].text, 'b');
  assert.throws(() => applyPatches(original, [{ op: 'replace', path: ['turns', 0, 'items', 0, 'text'], value: 'partial' }, { op: 'remove', path: ['turns', -1] }]), /index/);
  assert.equal(original.turns[0].items[0].text, 'a');
  for (const index of ['nope', -1, 0.5, 10, '__proto__']) assert.throws(() => applyPatches(original, [{ op: 'add', path: ['turns', index], value: {} }]));
});

test('concurrent and repeated reads subscribe once; reconnects and owner changes discard old state', async t => {
  const a = new DesktopAdapter({ timeoutMs: 100 }); t.after(() => a.close());
  let owner = 'owner', reads = 0;
  a.owner = async () => owner;
  a.broadcast = (_method, params) => { if (params.following) { reads++; queueMicrotask(() => a.handleIpc(envelope({ type: 'snapshot', revision: reads, conversationState: state(`read ${reads}`) }, owner))); } };
  const results = await Promise.all(Array.from({ length: 5 }, () => a.readThread({ threadId: 'qa' })));
  assert.equal(reads, 1); assert(results.every(r => r.messages[0].text === 'read 1'));
  assert.equal((await a.readThread({ threadId: 'qa' })).messages[0].text, 'read 1'); assert.equal(reads, 1);
  a.clearStreamState(); const second = await a.readThread({ threadId: 'qa' });
  assert.notEqual(second.streamId, results[0].streamId); assert.equal(reads, 2);
  owner = 'new-owner'; const third = await a.readThread({ threadId: 'qa' });
  assert.equal(third.messages[0].text, 'read 3'); assert.notEqual(third.streamId, second.streamId);
});

test('stream bursts project the newest revision once and preserve full tool output for paging', async t => {
  const a = new DesktopAdapter({ streamThrottleMs: 20 }); t.after(() => a.close());
  const big = state(); big.turns[0].items.unshift({ id: 'tool', type: 'commandExecution', aggregatedOutput: 'tool output '.repeat(100000) });
  a.subscriptions.set('local:qa', { threadId: 'qa', hostId: 'local', owner: 'owner', streamId: 'stream' });
  a.broadcast = () => { throw Error('A contiguous stream must not request snapshots'); };
  const events = []; a.on('event', e => events.push(e));
  a.handleIpc(envelope({ type: 'snapshot', revision: 0, conversationState: big }));
  for (let i = 1; i <= 100; i++) a.handleIpc(envelope({ type: 'patches', baseRevision: i - 1, revision: i, patches: [{ op: 'replace', path: ['turns', 0, 'items', 1, 'text'], value: `delta ${i}` }], acceptedTextChanges: [{ type: 'text', edits: [{ insert: `delta ${i}` }] }] }));
  assert.equal(a.snapshots.get('local:qa').revision, 100); assert.equal(events.length, 1);
  await new Promise(resolve => setTimeout(resolve, 35));
  assert.equal(events.length, 2); assert.equal(events[1].data.messages[1].text, 'delta 100');
  const store = new HistoryStore(), page = store.put(events[1].data), tool = page.messages[0];
  assert.equal(tool.textComplete, false); assert.equal(tool.textLength, big.turns[0].items[0].aggregatedOutput.length);
  let output = '', offset = 0;
  for (;;) { const chunk = store.read({ threadId: 'qa', messageKey: tool.key, version: tool.contentVersion, offset }); output += chunk.text; offset = chunk.nextOffset; if (chunk.done) break; }
  assert.equal(output, big.turns[0].items[0].aggregatedOutput);
});

test('a delayed coalesced event cannot regress history, and a new stream may restart revision at zero', () => {
  const store = new HistoryStore();
  const snapshot = (streamId, revision, text) => ({ thread: { id: 'qa' }, source: 'desktop-live', streamId, revision, messages: [{ id: 'a', text }] });
  store.put(snapshot('first', 20, 'current'));
  assert.equal(store.put(snapshot('first', 19, 'stale')).messages[0].text, 'current');
  assert.equal(store.put(snapshot('second', 0, 'reconnected')).messages[0].text, 'reconnected');
});

test('a snapshot timeout loads stored history once and never permits a send from it', async t => {
  const server = new EventEmitter(); let reads = 0, pages = 0;
  server.close = () => {}; server.request = async () => { reads++; return { thread: { ...state(), historyMode: 'paginated' } }; };
  server.pages = async () => { pages++; return state().turns; };
  const a = new StandaloneAdapter({ server, timeoutMs: 20, ipcPath: '/pocket-tests/nonexistent' }); t.after(() => a.close());
  a.ready = true; a.desktopOwner = async () => 'owner'; a.broadcast = () => {};
  const result = await a.readThread({ threadId: 'qa' });
  assert.equal(result.source, 'codex-app-server'); assert.equal(reads, 1); assert.equal(pages, 1);
  await assert.rejects(a.sendMessage({ threadId: 'qa', text: 'do not send' }), e => e.notSubmitted && !e.uncertain);
  assert.equal(reads, 1, 'send preparation must not fetch historical fallback');
});

test('sending can use current live state without first loading complete old history', async t => {
  const a = new StandaloneAdapter({ ipcPath: '/pocket-tests/nonexistent', timeoutMs: 100 }); t.after(() => a.close());
  a.ready = true; a.desktopOwner = async () => 'owner';
  const partial = state(); partial.turnsPagination.hasLoadedOldest = false;
  a.broadcast = (_method, params) => { if (params.following) queueMicrotask(() => a.handleIpc(envelope({ type: 'snapshot', revision: 1, conversationState: partial }))); };
  const calls = []; a.request = async method => { calls.push(method); assert.equal(method, 'thread-follower-start-turn'); return { result: { sent: true } }; };
  assert.deepEqual(await a.sendMessage({ threadId: 'qa', text: 'only once' }), { sent: true });
  assert.deepEqual(calls, ['thread-follower-start-turn']);
});

test('model-only updates verify through the lightweight settings endpoint with no history read', async t => {
  const a = new DesktopAdapter(); t.after(() => a.close());
  a.modelCatalog = () => [{ id: 'test', reasoningEfforts: ['low', 'high'] }]; a.owner = async () => 'owner';
  let settings = { model: 'test', reasoningEffort: 'low' }; const calls = [];
  a.readThread = () => { throw Error('must not read all history for model settings'); };
  a.request = async (method, params) => { calls.push(method); if (method === 'thread-follower-read-model-settings') return { result: { settings } }; settings = { model: 'test', reasoningEffort: params.threadSettings.effort }; return { result: { applied: true } }; };
  assert.equal((await a.updateSettings({ threadId: 'qa', reasoningEffort: 'high' })).verified, true);
  assert.equal((await a.updateSettings({ threadId: 'qa', model: 'test', reasoningEffort: 'high' })).unchanged, true);
  assert.equal(calls.filter(m => m === 'thread-follower-update-thread-settings').length, 1);
});

test('stdio parses large Unicode replies, several replies per chunk and fragmented line endings', async t => {
  const child = new EventEmitter(); child.stdout = new PassThrough(); child.stderr = new PassThrough(); child.stdin = new PassThrough(); child.kill = () => {};
  const client = new AppServerClient({ binary: 'mock', timeoutMs: 1000, spawnProcess: () => child }); t.after(() => client.close());
  child.stdin.on('data', bytes => {
    const request = JSON.parse(bytes.toString()); if (request.method !== 'initialize') return;
    queueMicrotask(() => child.stdout.write(JSON.stringify({ id: request.id, result: {} }) + '\n'));
  });
  await client.connect();
  const values = [{ text: '汉字🦊'.repeat(1_000_000) }, { ok: true }];
  const promises = [client.request('first'), client.request('second')], ids = [...client.pending.keys()];
  const bytes = Buffer.from(values.map((result, i) => JSON.stringify({ id: ids[i], result }) + '\n').join(''));
  for (let i = 0; i < bytes.length; i += 16383) child.stdout.write(bytes.subarray(i, i + 16383));
  assert.deepEqual(await Promise.all(promises), values);
  assert.equal(client.pending.size, 0);
});
