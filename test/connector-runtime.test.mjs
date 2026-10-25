import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import net from 'node:net';
import { resolve } from 'node:path';
import { fixture } from './support/connector-fixture.mjs';
import { prepareLan, lanAddresses } from '../lan.mjs';
import { ConnectorRuntime } from '../connector-runtime.mjs';
import { readJson, writePrivate } from '../connector-core.mjs';
import { relayRequest } from '../companion.mjs';
import { secret, digest } from '../protocol.mjs';

class Adapter extends EventEmitter {
  async connect() { this.ready = true; }
  diagnostics() { return { capabilities: {} }; }
  close() { this.ready = false; }
}

test('real relay takeover stays stable and reconnect button explicitly resumes the selected connector',async t=>{
  const f=await fixture();const a=new ConnectorRuntime({createAdapter:()=>new Adapter()}),b=new ConnectorRuntime({createAdapter:()=>new Adapter()});
  t.after(async()=>{await a.stop();await b.stop();await f.close()});
  const {paths,config}=await f.enroll('takeover-a');const second=f.paths('takeover-b');writePrivate(second.config,config);
  const until=async condition=>{const end=Date.now()+5000;while(!condition()){if(Date.now()>end)throw Error('Takeover did not settle');await new Promise(r=>setTimeout(r,20));}};
  await a.connect(paths);await until(()=>a.app.status().relayOnline);
  const original=a.app;
  await b.connect(second);await until(()=>b.app.status().relayOnline&&a.app.status().relayState==='replaced');
  await new Promise(r=>setTimeout(r,1200));assert.equal(a.app.status().relayState,'replaced');assert.equal(b.app.status().relayOnline,true);
  await a.connect(paths);await until(()=>a.app.status().relayOnline&&b.app.status().relayState==='replaced');assert.equal(a.app,original);
  await a.connect(paths);await new Promise(r=>setTimeout(r,1200));assert.equal(a.app.status().relayOnline,true);assert.equal(b.app.status().relayState,'replaced');
});

test('one-click connection refreshes an expired unpaired QR and preserves an authorized phone on restart', async t => {
  const f = await fixture(); t.after(() => f.close());
  const reservation = net.createServer();
  await new Promise(r => reservation.listen(0, '127.0.0.1', r));
  const port = reservation.address().port;
  await new Promise(r => reservation.close(r));
  const address = lanAddresses()[0] ?? '10.0.2.2';
  const prepared = prepareLan(f.directory, { address, addresses: [address], port });
  const paths = { state: resolve(f.directory, 'lan'), config: prepared.file };
  const expired = { ...prepared.config, pairExpires: Date.now() - 1000 };
  writePrivate(paths.config, expired);
  const runtime = new ConnectorRuntime({ createAdapter: () => new Adapter() });
  t.after(() => runtime.stop());
  await runtime.connect(paths);
  const refreshed = readJson(paths.config);
  assert.notEqual(refreshed.pairCode, expired.pairCode);
  assert(refreshed.pairExpires > Date.now());
  assert.notEqual(refreshed.key, expired.key);
  const mobile = secret();
  const peer = { ...refreshed, relay: `https://127.0.0.1:${port}` };
  assert.equal((await relayRequest(peer, '/api/pair', { hostId: refreshed.hostId, pairCode: refreshed.pairCode, mobileToken: mobile }, null)).state, 'pending');
  await relayRequest(peer, '/api/pair/decision', { hostId: refreshed.hostId, fingerprint: digest(mobile).slice(0, 12), accept: true });
  await runtime.stop();
  writePrivate(paths.config, { ...readJson(paths.config), pairExpires: Date.now() - 1000 });
  await runtime.connect(paths);
  const restarted = readJson(paths.config);
  assert.equal(restarted.pairCode, refreshed.pairCode);
  assert.equal(restarted.key, refreshed.key);
  assert.equal((await relayRequest(peer, '/api/pair/status', { hostId: refreshed.hostId, mobileToken: mobile }, null)).state, 'approved');
  await runtime.stop();
  await new Promise((r,j)=>{reservation.once('error',j);reservation.listen(port,'127.0.0.1',r)});
  await new Promise(r=>reservation.close(r));
  await runtime.connect(paths);await runtime.stop();
  await new Promise((r,j)=>{reservation.once('error',j);reservation.listen(port,'127.0.0.1',r)});
  await new Promise(r=>reservation.close(r));
});
