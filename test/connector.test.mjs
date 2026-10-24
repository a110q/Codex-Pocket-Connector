import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
import http from 'node:http';
import { DatabaseSync } from 'node:sqlite';
import WebSocket from 'ws';
import { fixture } from './support/connector-fixture.mjs';
import { Enrollment, readJson, connectionCommand, diagnose } from '../connector-core.mjs';
import { startConnector } from '../connector.mjs';
import { relayRequest } from '../companion.mjs';
import { secret, digest, CipherChannel } from '../protocol.mjs';

test('invitations require admin authorization, expire, revoke, redeem once and remain durable', async t => {
  const f = await fixture(); t.after(() => f.close());
  await assert.rejects(f.call('/api/invites', { hours:24 }, secret()), /Unauthorized/);
  await assert.rejects(f.call('/api/invites/list', {}, secret()), /Unauthorized/);
  await assert.rejects(f.call('/api/invites', { hours:0 }, f.admin), /Invalid invitation/);
  const fields = () => ({ hostId:secret().slice(0,32), desktopToken:secret(), pairCode:secret(), name:'other computer' });
  const revoked = await f.invite('revoked'); await f.call('/api/invites/revoke', { id:revoked.id }, f.admin);
  await assert.rejects(f.call('/api/hosts/enroll', { ...fields(), inviteCode:revoked.code }), /邀请码/);
  const expired = await f.invite('expired'); f.advance(25*3600000);
  await assert.rejects(f.call('/api/hosts/enroll', { ...fields(), inviteCode:expired.code }), /邀请码/);
  const invitation = await f.invite('valid'), a = fields(), b = fields();
  const results = await Promise.allSettled([a,b].map(computer=>f.call('/api/hosts/enroll',{...computer,inviteCode:invitation.code})));
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
  const winner = results[0].status==='fulfilled'?a:b;
  assert.equal((await f.call('/api/hosts/enroll',{...winner,inviteCode:invitation.code})).recovered,true);
  await assert.rejects(f.call('/api/hosts/enroll',{...winner,desktopToken:secret(),inviteCode:invitation.code}),/邀请码/);
  await f.restartRelay();
  assert.equal((await f.call('/api/hosts/enroll',{...winner,inviteCode:invitation.code})).recovered,true);
  const db = new DatabaseSync(f.dbPath); t.after(()=>db.close());
  const row = db.prepare('SELECT * FROM invitations WHERE id=?').get(invitation.id);
  assert.equal(row.code_hash,digest(invitation.code)); assert.equal(row.host_id,winner.hostId); assert(!JSON.stringify(row).includes(invitation.code));
  assert.equal(db.prepare('SELECT count(*) AS n FROM hosts').get().n,1);
  assert(!(JSON.stringify(await f.call('/api/invites/list',{},f.admin))).includes(invitation.code));
});

test('registration survives a lost response and app restart without changing computer identity', async t => {
  const f=await fixture();t.after(()=>f.close());const invitation=await f.invite(),paths=f.paths('restart');let lost=true;
  const uncertain = async (...args) => { const result=await relayRequest(...args); if(lost&&args[1]==='/api/hosts/enroll'){lost=false;throw new Error('response lost')}return result };
  await assert.rejects(new Enrollment({paths,defaults:f.defaults,request:uncertain}).register({inviteCode:invitation.code,name:'My Mac'}),/response lost/);
  assert(!existsSync(paths.config)); const before=readJson(paths.enrollment).config;
  await assert.rejects(new Enrollment({paths,defaults:f.defaults}).register({inviteCode:secret(),name:'overwrite'}),/原邀请码/);
  await new Enrollment({paths,defaults:f.defaults}).register({inviteCode:invitation.code,name:'My Mac'});
  const persisted=readJson(paths.config);assert.equal(persisted.hostId,before.hostId);assert.equal(persisted.key,before.key);assert.equal(persisted.managerToken,before.managerToken);
  assert.equal(statSync(paths.config).mode&0o777,0o600);assert(!readFileSync(paths.enrollment,'utf8').includes(persisted.key));
  await assert.rejects(new Enrollment({paths,defaults:f.defaults}).register({inviteCode:invitation.code,name:'new'}),/已经注册/);
  const command=connectionCommand('/Applications/Codex Pocket Connector.app/Contents/Resources/connector',paths,randomUUID());
  assert(command.includes("'"));assert(!command.includes(persisted.key));assert.throws(()=>connectionCommand('/app',paths,'$(touch /tmp/invalid)'),/格式无效/);
  const db=new DatabaseSync(f.dbPath);assert.equal(db.prepare('SELECT count(*) AS n FROM hosts').get().n,1);db.close();
});

test('two computers and their phones are isolated for authentication, approvals and encrypted routing', async t => {
  const f=await fixture();t.after(()=>f.close());const a=await f.enroll('alice'),b=await f.enroll('bob');
  for(const computer of [a,b]){
    computer.phone=secret();await f.call('/api/pair',{hostId:computer.config.hostId,pairCode:computer.config.pairCode,mobileToken:computer.phone});
    await f.call('/api/pair/decision',{hostId:computer.config.hostId,fingerprint:digest(computer.phone).slice(0,12),accept:true},computer.config.desktopToken);
  }
  await assert.rejects(f.call('/api/hosts/status',{hostId:a.config.hostId},b.config.desktopToken),/Unauthorized/);
  await assert.rejects(f.call('/api/pair/reset',{hostId:b.config.hostId,pairCode:secret()},a.config.desktopToken),/Unauthorized/);
  const connect=async(computer,role,token)=>{const ws=new WebSocket(f.defaults.relay.replace('https:','wss:')+'/relay',{ca:f.defaults.caCert,headers:{Authorization:`Bearer ${token}`,'X-Pocket-Host':computer.config.hostId,'X-Pocket-Role':role}});f.sockets.push(ws);await once(ws,'open');return ws};
  const ad=await connect(a,'desktop',a.config.desktopToken),bd=await connect(b,'desktop',b.config.desktopToken),am=await connect(a,'mobile',a.phone);
  const receivedA=[],receivedB=[];ad.on('message',m=>{const j=JSON.parse(m);if(j.v===1)receivedA.push(j)});bd.on('message',m=>{const j=JSON.parse(m);if(j.v===1)receivedB.push(j)});
  const channelA=new CipherChannel(a.config.key,a.config.hostId,'mobile'),serverA=new CipherChannel(a.config.key,a.config.hostId,'desktop'),serverB=new CipherChannel(b.config.key,b.config.hostId,'desktop');
  const delivered=new Promise(resolve=>ad.on('message',m=>{if(JSON.parse(m).v===1)resolve()}));am.send(JSON.stringify(channelA.encrypt({text:'alice private task'})));await delivered;
  assert.equal(receivedA.length,1);assert.equal(receivedB.length,0);assert.equal(serverA.decrypt(receivedA[0]).text,'alice private task');assert.throws(()=>serverB.decrypt(receivedA[0]));
  const bad=new WebSocket(f.defaults.relay.replace('https:','wss:')+'/relay',{ca:f.defaults.caCert,headers:{Authorization:`Bearer ${a.phone}`,'X-Pocket-Host':b.config.hostId,'X-Pocket-Role':'mobile'}});f.sockets.push(bad);bad.on('error',()=>{});const [,response]=await once(bad,'unexpected-response');assert.equal(response.statusCode,401);response.resume();bad.terminate();
  const db=new DatabaseSync(f.dbPath);assert.equal(db.prepare('SELECT count(*) AS n FROM hosts WHERE mobile_hash IS NOT NULL').get().n,2);db.close();
});

test('connector uses authenticated TLS registration and manager status, blocks foreign origins and omits secrets', async t => {
  const f=await fixture();t.after(()=>f.close());const paths=f.paths('web');
  const app=await startConnector({paths,defaults:f.defaults,port:0});t.after(()=>app.close());const base=`http://127.0.0.1:${app.server.address().port}`;
  const api=async(path,body,extra={})=>{const response=await fetch(base+path,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${app.token}`,...(body?{'Content-Type':'application/json'}:{}),...extra},body:body?JSON.stringify(body):undefined});return{status:response.status,body:await response.text()}};
  assert.equal((await fetch(base+'/api/state')).status,401);
  assert.equal((await api('/api/register',{name:'web',inviteCode:secret()},{Origin:'https://foreign.example'})).status,403);
  const foreignHost = await new Promise((resolve,reject)=>{ const req=http.get(base+'/api/state',{headers:{Host:'foreign.example',Authorization:`Bearer ${app.token}`}},r=>{r.resume();resolve(r.statusCode)});req.on('error',reject) });
  assert.equal(foreignHost,403);
  assert.equal((await api('/api/pairing')).status,400);
  const fresh=await api('/api/state');assert.equal(JSON.parse(fresh.body).registered,false);
  assert.equal((await api('/api/register',{name:'web',inviteCode:secret()})).status,400);
  assert.equal(readJson(paths.enrollment).rejected,true);
  const invite=await f.invite();assert.equal((await api('/api/register',{name:'web',inviteCode:invite.code})).status,200);
  assert.equal((await api('/api/pairing')).status,400,'a registered computer without Codex cannot expose a QR');
  const config=readJson(paths.config),current=await api('/api/state');for(const key of [config.key,config.desktopToken,config.managerToken,invite.code])assert(!current.body.includes(key));
  const backend=await f.companion(config,paths);
  const connected=await startConnector({paths,defaults:f.defaults,port:0,managerPort:backend.port});t.after(()=>connected.close());
  const r=await fetch(`http://127.0.0.1:${connected.server.address().port}/api/state`,{headers:{Authorization:`Bearer ${connected.token}`}});assert.equal((await r.json()).connected,true);
  const checks=await diagnose({paths,defaults:f.defaults,managerPort:backend.port});assert.equal(checks.checks.find(c=>c.id==='relay').state,'ok');assert.equal(checks.checks.find(c=>c.id==='desktop').state,'ok');
  for(const value of [config.key,config.desktopToken,config.managerToken,invite.code])assert(!JSON.stringify(checks).includes(value));
  assert.equal(backend.adapter.calls.length,0,'onboarding must not send messages or create real chats');
  const incompatible = await diagnose({paths,defaults:f.defaults,managerPort:backend.port,status:async()=>({desktopReady:true,relayOnline:true,capabilities:{}})});
  assert.equal(incompatible.checks.find(c=>c.id==='desktop').state,'error');
});
