import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import WebSocket from 'ws';
import { createRelay } from '../relay.mjs';
import { secret, CipherChannel, digest } from '../protocol.mjs';

test('relay requires valid credentials, desktop approval, supports encrypted routing and durable revocation', async t => {
  const directory = `.private/tests/${randomUUID()}`; mkdirSync(directory, { recursive: true });
  const dbPath = `${directory}/relay.sqlite`, admin = secret(), desktopToken = secret(), mobileToken = secret(), pairCode = secret(), hostId = secret().slice(0,32), key = secret();
  let relay = createRelay({ adminToken: admin, dbPath, allowInsecureLocal: true });
  await new Promise(resolve => relay.server.listen(0, '127.0.0.1', resolve));
  let base = `http://127.0.0.1:${relay.server.address().port}`;
  const clients = []; t.after(async () => { for (const ws of clients) ws.terminate(); await relay.close(); });
  const post = async (path, body, token) => { const r = await fetch(base + path, { method:'POST', headers: {'Content-Type':'application/json', ...(token?{Authorization:`Bearer ${token}`}:{})}, body:JSON.stringify(body) }); return { status:r.status, body:await r.json() }; };
  async function connect(role, token) {
    const ws = new WebSocket(base.replace('http:','ws:') + '/relay', { headers:{Authorization:`Bearer ${token}`,'X-Pocket-Host':hostId,'X-Pocket-Role':role} }); clients.push(ws);
    await once(ws, 'open'); return ws;
  }
  async function rejected(role, token) {
    const ws = new WebSocket(base.replace('http:','ws:')+'/relay',{headers:{Authorization:`Bearer ${token}`,'X-Pocket-Host':hostId,'X-Pocket-Role':role}});clients.push(ws);
    ws.on('error',()=>{}); const [,response]=await once(ws,'unexpected-response'); assert.equal(response.statusCode,401); response.resume(); ws.terminate();
  }
  assert.equal((await post('/api/hosts',{hostId,name:'test',desktopToken,pairCode},secret())).status,401);
  assert.equal((await post('/api/hosts',{hostId,name:'test',desktopToken,pairCode},admin)).status,200);
  assert.equal((await post('/api/pair',{hostId,pairCode:secret(),mobileToken})).status,401);
  assert.equal((await post('/api/pair',{hostId,pairCode,mobileToken,name:'test phone'})).status,200);
  await rejected('mobile',mobileToken); await rejected('desktop',secret());
  const desktop = await connect('desktop',desktopToken);
  const fingerprint=digest(mobileToken).slice(0,12);
  assert.equal((await post('/api/pair/decision',{hostId,fingerprint,accept:true},mobileToken)).status,401);
  assert.equal((await post('/api/pair/decision',{hostId,fingerprint:'wrong',accept:true},desktopToken)).status,409);
  assert.equal((await post('/api/pair/decision',{hostId,fingerprint,accept:true},desktopToken)).status,200);
  assert.equal((await post('/api/pair/status',{hostId,mobileToken})).body.state,'approved');
  assert.equal((await post('/api/pair',{hostId,pairCode,mobileToken:secret()})).status,401);
  const mobile = await connect('mobile',mobileToken);
  const m = new CipherChannel(key,hostId,'mobile'), d = new CipherChannel(key,hostId,'desktop');
  const routed = new Promise(resolve => desktop.on('message', bytes => { const frame=JSON.parse(bytes.toString()); if(frame.v===1)resolve(frame); }));
  const frame=m.encrypt({action:'message.send',text:'private task正文'});mobile.send(JSON.stringify(frame));
  const received=await routed; assert(!JSON.stringify(received).includes('private task'));assert.equal(d.decrypt(received).text,'private task正文');
  const db = new DatabaseSync(dbPath); const row=db.prepare('SELECT * FROM hosts WHERE id=?').get(hostId);db.close();
  assert.equal(row.mobile_hash,digest(mobileToken));assert(!JSON.stringify(row).includes(mobileToken));
  assert.equal((await post('/api/pair/reset',{hostId,pairCode:secret()},desktopToken)).status,200);
  assert.equal((await post('/api/pair/status',{hostId,mobileToken})).status,401);
  await rejected('mobile',mobileToken);
  for (const ws of clients) ws.terminate();await relay.close();
  relay=createRelay({adminToken:admin,dbPath,allowInsecureLocal:true});await new Promise(resolve=>relay.server.listen(0,'127.0.0.1',resolve));base=`http://127.0.0.1:${relay.server.address().port}`;
  await connect('desktop',desktopToken);await rejected('mobile',mobileToken);
});
