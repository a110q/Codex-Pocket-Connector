import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import WebSocket from 'ws';
import { fixture } from './support/connector-fixture.mjs';
import { startCompanion } from '../companion.mjs';
import { startConnector } from '../connector.mjs';
import { StandaloneAdapter } from '../standalone-adapter.mjs';
import { CipherChannel, digest, secret } from '../protocol.mjs';

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(condition, timeout = 2000) {
  const end = Date.now() + timeout;
  while (!condition()) { assert(Date.now() < end, 'Connection readiness did not recover'); await delay(10); }
}
class Adapter extends EventEmitter {
  ready = false; calls = [];
  async connect() { this.ready = true; }
  diagnostics() { return { capabilities: { 'threads.list': true } }; }
  async call(action) { this.calls.push(action); return { threads: [] }; }
  close() { this.ready = false; }
}
async function phone(f, config) {
  const token = secret();
  await f.call('/api/pair', { hostId: config.hostId, pairCode: config.pairCode, mobileToken: token });
  await f.call('/api/pair/decision', { hostId: config.hostId, fingerprint: digest(token).slice(0, 12), accept: true }, config.desktopToken);
  const codec = new CipherChannel(config.key, config.hostId, 'mobile'), channel = randomUUID(), messages = [];
  const socket = new WebSocket(f.defaults.relay.replace('https:', 'wss:') + '/relay', { ca: f.defaults.caCert, headers: { Authorization: `Bearer ${token}`, 'X-Pocket-Host': config.hostId, 'X-Pocket-Role': 'mobile' } });
  f.sockets.push(socket);
  const send = payload => socket.send(JSON.stringify(codec.encrypt({ ...payload, channel })));
  socket.on('message', data => {
    const frame = JSON.parse(data);
    if (frame.type === 'status') { if (frame.peerOnline) send({ type: 'hello', sentAt: Date.now() }); }
    else messages.push(codec.decrypt(frame));
  });
  await until(() => socket.readyState === WebSocket.OPEN);
  return { messages, send, channel };
}

test('standalone reconnection emits readiness and an expired initialization cannot resurrect a closed adapter', async t => {
  class Server extends EventEmitter {
    async connect() {}
    async pages() { return [{ model: 'test-model', supportedReasoningEfforts: [] }]; }
    async request() { return {}; }
    close() {}
  }
  const server = new Server(), adapter = new StandaloneAdapter({ server, connectTimeoutMs: 50 });
  t.after(() => adapter.close());
  adapter.ensureDesktopIpc = async () => {};
  const events = []; adapter.on('event', event => events.push(event.type));
  await adapter.connect();
  server.emit('disconnect', Error('fixture process exited'));
  await adapter.connect();
  assert.deepEqual(events, ['desktop.ready', 'desktop.disconnected', 'desktop.ready']);
  adapter.close();
  let release;
  server.connect = () => new Promise(resolve => { release = resolve; });
  await assert.rejects(adapter.connect(), /超时/);
  server.connect = async () => {};
  await adapter.connect();
  const count = events.length;
  release(); await delay(50);
  assert.equal(events.length, count, 'a timed-out attempt must not announce stale readiness');
  assert.equal(adapter.ready, true);
});

test('slow Codex startup cannot block companion startup, the phone handshake, or encrypted liveness', async t => {
  const f = await fixture(); let release;
  const adapter = new Adapter(), gate = new Promise(resolve => { release = resolve; });
  adapter.connect = async () => { await gate; adapter.ready = true; };
  const { config, paths } = await f.enroll('slow-start');
  const starting = startCompanion(config, { adapter, port: 0, statePath: resolve(paths.state, 'commands.sqlite') });
  t.after(async () => { release(); f.apps.push(await starting); await f.close(); });
  const app = await Promise.race([starting, delay(300).then(() => null)]);
  assert(app, 'the local connector must remain usable while Codex is initializing');
  const status = await fetch(`http://127.0.0.1:${app.manager.address().port}/api/status`, { headers: { Authorization: `Bearer ${config.managerToken}` } }).then(response => response.json());
  assert.equal(status.desktopConnecting, true);
  const p = await phone(f, config);
  await until(() => p.messages.some(m => m.type === 'hello'));
  assert.equal(p.messages.find(m => m.type === 'hello').desktopReady, false);
  assert.equal(p.messages.find(m => m.type === 'hello').capabilities['connection.ping'], true);
  p.send({ type: 'ping', id: 'before-ready' });
  await until(() => p.messages.some(m => m.type === 'pong' && m.id === 'before-ready'));
  assert.equal(p.messages.find(m => m.id === 'before-ready').desktopReady, false);
  p.send({ type: 'request', id: randomUUID(), action: 'threads.list', args: {}, sentAt: Date.now() });
  await until(() => p.messages.some(m => m.type === 'response' && m.error));
  assert.equal(adapter.calls.length, 0);
  release();
  await until(() => p.messages.some(m => m.event?.type === 'desktop.ready'));
  assert.equal(app.status().desktopReady, true);
  p.send({ type: 'ping', id: 'after-ready' });
  await until(() => p.messages.some(m => m.id === 'after-ready'));
  assert.equal(p.messages.find(m => m.id === 'after-ready').desktopReady, true);
});

test('the desktop window retains initialization progress after its service starts and keeps pairing locked', async t => {
  const f = await fixture(); t.after(() => f.close());
  const { paths } = await f.enroll('window-progress');
  const status = { desktopReady: false, desktopConnecting: true, relayOnline: true, error: '' };
  const app = await startConnector({ paths, defaults: f.defaults, port: 0, status: async () => status }); t.after(() => app.close());
  const get = path => fetch(`http://127.0.0.1:${app.server.address().port}${path}`, { headers: { Authorization: `Bearer ${app.token}` } });
  const state = await (await get('/api/state')).json();
  assert.equal(state.connecting, true); assert.equal(state.serviceRunning, true); assert.equal(state.connected, false);
  assert.equal((await get('/api/pairing')).status, 400);
  status.desktopConnecting = false; status.error = 'fixture login required';
  assert.equal((await (await get('/api/state')).json()).connecting, false, 'a failed initialization must allow corrective actions');
});

test('readiness recovers on the existing phone socket even when the adapter supplies no ready event', async t => {
  const f = await fixture(); t.after(() => f.close());
  const { config, paths } = await f.enroll('same-socket');
  const adapter = new Adapter();
  const app = await startCompanion(config, { adapter, port: 0, statePath: resolve(paths.state, 'commands.sqlite'), desktopRetryMs: 30 }); f.apps.push(app);
  const p = await phone(f, config); await until(() => p.messages.some(m => m.type === 'hello' && m.desktopReady));
  adapter.ready = false; adapter.emit('event', { type: 'desktop.disconnected' });
  await until(() => p.messages.some(m => m.event?.type === 'desktop.ready'));
  assert.equal(app.status().desktopReady, true);
});
