import { mkdtempSync, mkdirSync, readFileSync, rmSync } from 'node:fs';
import { resolve } from 'node:path';
import { EventEmitter } from 'node:events';
import { spawnSync } from 'node:child_process';
import { createRelay } from '../../relay.mjs';
import { relayRequest, startCompanion } from '../../companion.mjs';
import { Enrollment, connectorPaths, readJson } from '../../connector-core.mjs';
import { secret } from '../../protocol.mjs';

export async function fixture(options = {}) {
  const base = resolve('.private/tests'); mkdirSync(base, { recursive: true });
  const directory = mkdtempSync(resolve(base, 'connector-'));
  const certFile = resolve(directory, 'cert.pem'), keyFile = resolve(directory, 'key.pem');
  const result = spawnSync('openssl', ['req','-x509','-newkey','rsa:2048','-nodes','-keyout',keyFile,'-out',certFile,'-days','2','-subj','/CN=localhost','-addext','subjectAltName=IP:127.0.0.1,DNS:localhost'], { stdio: 'ignore' });
  if (result.status !== 0) throw new Error('Test certificate generation failed');
  const cert = readFileSync(certFile, 'utf8'), key = readFileSync(keyFile, 'utf8'), admin = secret(), dbPath = resolve(directory, 'relay.sqlite');
  let now = Date.now(), relay = createRelay({ ...options, adminToken: admin, dbPath, cert, key, clock: () => now });
  const defaults = { caCert: cert };
  async function listen() { await new Promise(resolve => relay.server.listen(0,'127.0.0.1',resolve)); defaults.relay = `https://127.0.0.1:${relay.server.address().port}`; }
  await listen(); const apps = [], sockets = [];
  const call = (path, body, token) => relayRequest(defaults, path, body, token);
  return { directory, defaults, admin, dbPath, apps, sockets, call,
    paths: label => connectorPaths(resolve(directory, label)),
    advance: milliseconds => { now += milliseconds; },
    invite: (label = '') => call('/api/invites', { label, hours: 24 }, admin),
    async enroll(label) { const invitation = await this.invite(label), paths = this.paths(label); await new Enrollment({ paths, defaults }).register({ inviteCode: invitation.code, name: label }); return { paths, config: readJson(paths.config), invitation }; },
    async companion(config, paths, port = 0) {
      class TestAdapter extends EventEmitter { ready = false; calls = []; async connect() { this.ready = true; } diagnostics() { return { capabilities: Object.fromEntries(['threads.list','projects.list','thread.read','message.send','thread.create','models.list'].map(name=>[name,true])) }; } async call(action) { this.calls.push(action); return { threads: [] }; } close() { this.ready = false; } }
      const adapter = new TestAdapter(); const app = await startCompanion(config, { adapter, port, statePath: resolve(paths.state, 'commands.sqlite'), configPath: paths.config }); apps.push(app);
      await new Promise((resolve, reject) => { const start = Date.now(); const timer = setInterval(() => { if (app.status().relayOnline) { clearInterval(timer); resolve(); } else if (Date.now()-start>5000) { clearInterval(timer); reject(new Error('Fixture relay did not connect')); } }, 20); });
      return { app, adapter, port: app.manager.address().port };
    },
    async restartRelay() { await relay.close(); relay = createRelay({ ...options, adminToken: admin, dbPath, cert, key, clock: () => now }); await listen(); },
    async close() { for (const ws of sockets) ws.terminate(); for (const app of apps.reverse()) await app.close(); await relay.close(); rmSync(directory, { recursive: true, force: true }); }
  };
}
