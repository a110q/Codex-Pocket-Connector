import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { DatabaseSync } from 'node:sqlite';
import WebSocket from 'ws';
import { fixture } from './support/connector-fixture.mjs';
import { secret, digest, CipherChannel } from '../protocol.mjs';
import { ConnectorAccount, Enrollment, readJson } from '../connector-core.mjs';
import { relayRequest } from '../companion.mjs';
import { existsSync, readFileSync } from 'node:fs';
import { EventEmitter } from 'node:events';
import { setTimeout as delay } from 'node:timers/promises';
import { startConnector } from '../connector.mjs';
import { ConnectorRuntime } from '../connector-runtime.mjs';

const pass = 'Test-only-long-password-429';
async function setup(t) {
  const f=await fixture();t.after(()=>f.close());
  const post=async(path,body,token)=>{try{return{status:200,body:await f.call(path,body,token)}}catch(e){return{status:e.statusCode,error:e.message}}};
  assert.equal((await post('/api/admin/bootstrap',{username:'owner',password:pass,displayName:'Owner'},secret())).status,401);
  await f.call('/api/admin/bootstrap',{username:'owner',password:pass,displayName:'Owner'},f.admin);
  const deviceId=secret();const root=await f.call('/api/account/login',{username:'owner',password:pass,client:'android',deviceId});
  const register=async(name)=>{const user=(await f.call('/api/account/register',{username:name,password:pass,displayName:name,reason:'Independent account test',role:'admin',status:'active'})).user;const deviceId=secret();const phone=await f.call('/api/account/login',{username:name,password:pass,deviceId,client:'android'});return{user,phone,deviceId}};
  const approve=async(user)=>{const result=await f.call('/api/admin/users/decision',{userId:user.id,action:'approve',days:30},root.token);await f.call('/api/admin/users/computers',{userId:user.id,limit:1},root.token);return result};
  const desktop=async(name)=>f.call('/api/account/login',{username:name,password:pass,client:'desktop',deviceId:secret()});
  const host=()=>({hostId:secret().slice(0,32),desktopToken:secret(),pairCode:secret(),name:'Independent test PC'});
  return Object.assign(f,{post,root,register,approve,desktop,host});
}

test('accounts require server-side approval, protect admin routes, survive restart and never expose passwords',async t=>{
  const f=await setup(t),alice=await f.register('alice');
  assert.equal(alice.user.role,'user');assert.equal(alice.user.accessState,'pending');
  assert.equal((await f.post('/api/admin/users',{},alice.phone.token)).status,403);
  assert.equal((await f.post('/api/account/hosts/enroll',f.host(),(await f.desktop('alice')).token)).status,403);
  assert.equal((await f.post('/api/account/login',{username:'alice',password:pass+'wrong',deviceId:secret(),client:'android'})).status,401);
  await f.approve(alice.user);assert.equal((await f.call('/api/account/me',{},alice.phone.token)).user.accessState,'active');
  const differentPhone=await f.post('/api/account/login',{username:'alice',password:pass,deviceId:secret(),client:'android'});assert.equal(differentPhone.status,409);
  const accounts=await f.call('/api/admin/users',{},f.root.token);assert(!JSON.stringify(accounts).includes(pass));assert(!JSON.stringify(accounts).includes('password_hash'));
  await f.restartRelay();assert.equal((await f.call('/api/account/me',{},alice.phone.token)).user.accessState,'active');
  assert.equal((await f.post('/api/admin/bootstrap',{username:'otherroot',password:pass},f.admin)).status,409);
  const db=new DatabaseSync(f.dbPath);const stored=db.prepare('SELECT * FROM accounts WHERE id=?').get(alice.user.id);assert(stored.password_hash.startsWith('scrypt1$'));assert(!stored.password_hash.includes(pass));assert.equal(db.prepare('SELECT * FROM account_sessions WHERE token_hash=?').get(digest(alice.phone.token)).user_id,alice.user.id);db.close();
});

test('computer enrollment is idempotent, rolls back quota failures and refuses cross-account pairing',async t=>{
  const f=await setup(t),alice=await f.register('alice'),bob=await f.register('bob');await f.approve(alice.user);await f.approve(bob.user);
  await f.call('/api/account/me',{},alice.phone.token);await f.call('/api/account/me',{},bob.phone.token);
  const desktop=await f.desktop('alice'),computer=f.host();
  await f.call('/api/account/hosts/enroll',computer,desktop.token);await f.call('/api/account/hosts/enroll',computer,desktop.token);
  assert.equal((await f.post('/api/account/hosts/enroll',f.host(),desktop.token)).status,409);
  assert.equal((await f.post('/api/account/hosts/enroll',computer,(await f.desktop('bob')).token)).status,409);
  const pair={hostId:computer.hostId,pairCode:computer.pairCode,mobileToken:secret()};
  assert.equal((await f.post('/api/pair',pair)).status,401);assert.equal((await f.post('/api/pair',pair,bob.phone.token)).status,403);
  await f.call('/api/pair',pair,alice.phone.token);await f.call('/api/pair/decision',{hostId:computer.hostId,fingerprint:digest(pair.mobileToken).slice(0,12),accept:true},computer.desktopToken);
  assert.equal((await f.call('/api/pair/status',pair,alice.phone.token)).state,'approved');
  assert.equal((await f.post('/api/pair/status',pair,bob.phone.token)).status,403);
  const db=new DatabaseSync(f.dbPath);assert.equal(db.prepare('SELECT count(*) AS n FROM hosts').get().n,1);assert.equal(db.prepare('SELECT user_id FROM account_hosts WHERE host_id=?').get(computer.hostId).user_id,alice.user.id);db.close();
});

test('disabled and expired accounts cannot keep routing encrypted traffic; removed computers never become legacy hosts',async t=>{
  const f=await setup(t),alice=await f.register('alice');await f.approve(alice.user);await f.call('/api/account/me',{},alice.phone.token);
  const desktop=await f.desktop('alice'),computer=f.host();await f.call('/api/account/hosts/enroll',computer,desktop.token);
  const mobileToken=secret();await f.call('/api/pair',{hostId:computer.hostId,pairCode:computer.pairCode,mobileToken},alice.phone.token);await f.call('/api/pair/decision',{hostId:computer.hostId,fingerprint:digest(mobileToken).slice(0,12),accept:true},computer.desktopToken);
  const connect=async(role,token,session)=>{const ws=new WebSocket(f.defaults.relay.replace('https:','wss:')+'/relay',{ca:f.defaults.caCert,headers:{Authorization:`Bearer ${token}`,'X-Pocket-Host':computer.hostId,'X-Pocket-Role':role,...(session?{'X-Pocket-Session':session}:{})}});f.sockets.push(ws);await once(ws,'open');return ws};
  const reject=async(session)=>{const ws=new WebSocket(f.defaults.relay.replace('https:','wss:')+'/relay',{ca:f.defaults.caCert,headers:{Authorization:`Bearer ${mobileToken}`,'X-Pocket-Host':computer.hostId,'X-Pocket-Role':'mobile',...(session?{'X-Pocket-Session':session}:{})}});f.sockets.push(ws);ws.on('error',()=>{});const [,r]=await once(ws,'unexpected-response');assert([401,403].includes(r.statusCode));r.resume();ws.terminate()};
  await reject();const d=await connect('desktop',computer.desktopToken),m=await connect('mobile',mobileToken,alice.phone.token);
  const key=secret(),sender=new CipherChannel(key,computer.hostId,'mobile'),receiver=new CipherChannel(key,computer.hostId,'desktop');
  const got=new Promise(resolve=>d.on('message',data=>{const j=JSON.parse(data);if(j.v===1)resolve(receiver.decrypt(j))}));m.send(JSON.stringify(sender.encrypt({private:'test'})));assert.equal((await got).private,'test');
  const closed=once(m,'close');await f.call('/api/admin/users/decision',{userId:alice.user.id,action:'disable'},f.root.token);assert.equal((await closed)[0],4003);await reject(alice.phone.token);
  await f.call('/api/admin/users/decision',{userId:alice.user.id,action:'enable',days:1},f.root.token);const active=await connect('mobile',mobileToken,alice.phone.token);active.terminate();
  f.advance(2*86400_000);assert.equal((await f.call('/api/account/me',{},alice.phone.token)).user.accessState,'expired');await reject(alice.phone.token);
  await f.call('/api/admin/users/decision',{userId:alice.user.id,action:'enable',days:30},f.root.token);await f.call('/api/admin/devices/remove',{userId:alice.user.id,device:'computer'},f.root.token);
  assert.equal((await f.post('/api/hosts/status',{hostId:computer.hostId},computer.desktopToken)).status,401);
  await f.call('/api/account/hosts/enroll',f.host(),desktop.token);assert.equal((await f.call('/api/account/me',{},alice.phone.token)).host.name,computer.name);
});

test('security signals create deduplicated admin alerts; user cannot read or acknowledge other users events',async t=>{
  const f=await setup(t),alice=await f.register('alice');
  const report={signals:['signature_mismatch','signature_mismatch','not_a_signal'],certificate:'a'.repeat(64),version:'0.6.0'};
  assert.equal((await f.call('/api/account/security/report',report,alice.phone.token)).reported,1);assert.equal((await f.call('/api/account/security/report',report,alice.phone.token)).reported,0);
  assert.equal((await f.post('/api/admin/events',{},alice.phone.token)).status,403);
  const events=await f.call('/api/admin/events',{},f.root.token);assert.equal(events.pending,1);assert.equal(events.alerts,1);
  const alert=events.events.find(e=>e.type==='security.signature_mismatch');assert.equal(alert.userId,alice.user.id);assert.equal(alert.details.evidence,'client-reported; may be spoofed or unavailable');
  assert.equal((await f.post('/api/admin/events/ack',{id:alert.id},alice.phone.token)).status,403);
  await f.call('/api/admin/events/ack',{id:alert.id},f.root.token);assert.equal((await f.call('/api/admin/events',{},f.root.token)).alerts,0);
  await f.restartRelay();assert((await f.call('/api/admin/events',{},f.root.token)).events.some(e=>e.id===alert.id&&e.acknowledgedAt));
});

test('connector account enrollment recovers lost responses and revokes sessions on logout and password changes',async t=>{
  const f=await setup(t),alice=await f.register('alice');await f.approve(alice.user);
  const paths=f.paths('account-client'),client=new ConnectorAccount({paths,defaults:f.defaults});
  await client.login({username:'alice',password:pass});assert.equal((await client.state()).user.accessState,'active');
  const session=readJson(paths.account);assert(!readFileSync(paths.account,'utf8').includes(pass));let lost=true;
  const uncertain=async(...args)=>{const r=await relayRequest(...args);if(lost&&args[1]==='/api/account/hosts/enroll'){lost=false;throw new Error('lost account enrollment response')}return r};
  await assert.rejects(new Enrollment({paths,defaults:f.defaults,request:uncertain}).registerAccount({token:session.token,user:session.user}),/lost account/);
  assert(!existsSync(paths.config));const pending=readJson(paths.enrollment).config;
  await new Enrollment({paths,defaults:f.defaults}).registerAccount({token:session.token,user:session.user});assert.equal(readJson(paths.config).hostId,pending.hostId);
  await client.logout();assert.equal((await client.state()).loggedIn,false);assert.equal((await f.post('/api/account/me',{},session.token)).status,401);
  await client.login({username:'alice',password:pass});const next=readJson(paths.account);assert.equal(next.deviceId,session.deviceId);
  await f.call('/api/account/password',{currentPassword:pass,password:pass+'changed'},alice.phone.token);
  assert.equal((await f.post('/api/account/me',{},next.token)).status,401);assert.equal((await f.call('/api/account/me',{},alice.phone.token)).user.accessState,'active');
  await f.call('/api/admin/password/reset',{userId:alice.user.id,password:pass+'reset'},f.root.token);assert.equal((await f.post('/api/account/me',{},alice.phone.token)).status,401);
});

test('legacy host claim preserves credentials and limits migration to the original phone until account upgrade',async t=>{
  const f=await fixture();t.after(()=>f.close());const legacy=await f.enroll('legacy'),c=legacy.config,mobile=secret();
  await f.call('/api/pair',{hostId:c.hostId,pairCode:c.pairCode,mobileToken:mobile});await f.call('/api/pair/decision',{hostId:c.hostId,fingerprint:digest(mobile).slice(0,12),accept:true},c.desktopToken);
  const db=new DatabaseSync(f.dbPath);t.after(()=>db.close());const before=db.prepare('SELECT desktop_hash,mobile_hash FROM hosts WHERE id=?').get(c.hostId);
  await f.call('/api/admin/bootstrap',{username:'owner',password:pass},f.admin);const root=await f.call('/api/account/login',{username:'owner',password:pass,client:'android',deviceId:secret()});
  await f.call('/api/account/hosts/claim',{hostId:c.hostId,desktopToken:c.desktopToken},root.token);
  assert.deepEqual(db.prepare('SELECT desktop_hash,mobile_hash FROM hosts WHERE id=?').get(c.hostId),before);
  assert.equal(db.prepare('SELECT mobile_hash FROM account_legacy_mobile WHERE host_id=?').get(c.hostId).mobile_hash,digest(mobile));
  const connect=async(session)=>{const ws=new WebSocket(f.defaults.relay.replace('https:','wss:')+'/relay',{ca:f.defaults.caCert,headers:{Authorization:`Bearer ${mobile}`,'X-Pocket-Host':c.hostId,'X-Pocket-Role':'mobile',...(session?{'X-Pocket-Session':session}:{})}});f.sockets.push(ws);await once(ws,'open');return ws};
  (await connect()).terminate();(await connect(root.token)).terminate();assert.equal(db.prepare('SELECT count(*) AS n FROM account_legacy_mobile').get().n,0);
  const bad=new WebSocket(f.defaults.relay.replace('https:','wss:')+'/relay',{ca:f.defaults.caCert,headers:{Authorization:`Bearer ${mobile}`,'X-Pocket-Host':c.hostId,'X-Pocket-Role':'mobile'}});f.sockets.push(bad);bad.on('error',()=>{});const [,r]=await once(bad,'unexpected-response');assert.equal(r.statusCode,401);r.resume();bad.terminate();
});

test('relay compares the reported certificate with its configured release certificate',async t=>{
  const f=await fixture({androidCertificate:'a'.repeat(64)});t.after(()=>f.close());await f.call('/api/admin/bootstrap',{username:'owner',password:pass},f.admin);
  const root=await f.call('/api/account/login',{username:'owner',password:pass,client:'android',deviceId:secret()});
  assert.equal((await f.call('/api/account/security/report',{certificate:'a'.repeat(64),signals:[],version:'0.6.0'},root.token)).reported,0);
  assert.equal((await f.call('/api/account/security/report',{certificate:'b'.repeat(64),signals:[],version:'repacked-test'},root.token)).reported,1);
  assert.equal((await f.call('/api/admin/events',{},root.token)).alerts,1);
});

test('desktop prepares without login; only an active Android account can claim after computer approval',async t=>{
  const f=await setup(t),alice=await f.register('mobileclaim'),bob=await f.register('otherclaim'),h=f.host();
  await f.call('/api/desktop/prepare',h);
  assert.equal((await f.call('/api/desktop/prepare',h,h.desktopToken)).recovered,true);
  assert.equal((await f.post('/api/desktop/prepare',h,secret())).status,401);
  const pair={hostId:h.hostId,pairCode:h.pairCode,mobileToken:secret()};
  assert.equal((await f.post('/api/pair',pair)).status,401);
  assert.equal((await f.post('/api/pair',pair,alice.phone.token)).status,403);
  await f.approve(alice.user);await f.call('/api/account/me',{},alice.phone.token);
  assert.equal((await f.post('/api/pair',pair,(await f.desktop('mobileclaim')).token)).status,403);
  assert.equal((await f.post('/api/pair',{...pair,pairCode:secret()},alice.phone.token)).status,401);
  await f.call('/api/pair',pair,alice.phone.token);
  assert.equal((await f.call('/api/account/me',{},alice.phone.token)).host,null,'pending does not consume computer quota');
  await f.approve(bob.user);await f.call('/api/account/me',{},bob.phone.token);
  assert.equal((await f.post('/api/pair/status',pair,bob.phone.token)).status,403);
  await f.restartRelay();
  assert.equal((await f.call('/api/pair/status',pair,alice.phone.token)).state,'pending');
  await f.call('/api/pair/decision',{hostId:h.hostId,fingerprint:digest(pair.mobileToken).slice(0,12),accept:true},h.desktopToken);
  assert.equal((await f.call('/api/account/me',{},alice.phone.token)).host.id,h.hostId);
  assert.equal((await f.call('/api/pair/status',pair,alice.phone.token)).state,'approved');
  assert.equal((await f.post('/api/pair/status',pair)).status,401);
  assert.equal((await f.post('/api/pair/status',pair,bob.phone.token)).status,403);
  const second=f.host();await f.call('/api/desktop/prepare',second);
  assert.equal((await f.post('/api/pair',{...pair,hostId:second.hostId,pairCode:second.pairCode},alice.phone.token)).status,409);
  const db=new DatabaseSync(f.dbPath);assert.equal(db.prepare('SELECT user_id FROM account_hosts WHERE host_id=?').get(h.hostId).user_id,alice.user.id);assert.equal(db.prepare('SELECT count(*) n FROM mobile_claim_hosts WHERE host_id=?').get(h.hostId).n,0);db.close();
  await f.call('/api/admin/users/decision',{userId:alice.user.id,action:'disable'},f.root.token);
  assert.equal((await f.post('/api/pair/status',pair,alice.phone.token)).status,403);
});

test('rejecting a phone leaves the computer unowned; revocation and quota races cannot bypass final approval',async t=>{
  const f=await setup(t),alice=await f.register('revokedclaim');await f.approve(alice.user);await f.call('/api/account/me',{},alice.phone.token);
  const h=f.host();await f.call('/api/desktop/prepare',h);const pair={hostId:h.hostId,pairCode:h.pairCode,mobileToken:secret()};
  const decision={hostId:h.hostId,fingerprint:digest(pair.mobileToken).slice(0,12),accept:true};
  await f.call('/api/pair',pair,alice.phone.token);
  await f.call('/api/pair/decision',{...decision,accept:false},h.desktopToken);
  assert.equal((await f.call('/api/account/me',{},alice.phone.token)).host,null);
  await f.call('/api/pair',pair,alice.phone.token);await f.call('/api/account/logout',{},alice.phone.token);
  assert.equal((await f.post('/api/pair/decision',decision,h.desktopToken)).status,403);
  const fresh=await f.call('/api/account/login',{username:'revokedclaim',password:pass,client:'android',deviceId:alice.deviceId});
  await f.call('/api/pair',pair,fresh.token);
  const other=f.host();await f.call('/api/account/hosts/enroll',other,(await f.desktop('revokedclaim')).token);
  assert.equal((await f.post('/api/pair/decision',decision,h.desktopToken)).status,409);
  const db=new DatabaseSync(f.dbPath);assert.equal(db.prepare('SELECT mobile_hash FROM hosts WHERE id=?').get(h.hostId).mobile_hash,null);assert.equal(db.prepare('SELECT count(*) n FROM account_hosts WHERE host_id=?').get(h.hostId).n,0);db.close();
});

test('anonymous computer preparation persists identity after response loss without storing an account',async t=>{
  const f=await setup(t),paths=f.paths('prepare-recovery');let lost=true;
  const request=async(...args)=>{const result=await relayRequest(...args);if(lost&&args[1]==='/api/desktop/prepare'){lost=false;throw Error('lost prepare response')}return result};
  await assert.rejects(new Enrollment({paths,defaults:f.defaults,request}).prepare(),/lost prepare/);
  const before=readJson(paths.enrollment).config;
  await new Enrollment({paths,defaults:f.defaults}).prepare();
  const after=readJson(paths.config);assert.equal(after.hostId,before.hostId);assert.equal(after.key,before.key);assert.equal(after.mobileClaim,true);assert(!existsSync(paths.account));
  await new Enrollment({paths,defaults:f.defaults}).prepare();
  const db=new DatabaseSync(f.dbPath);assert.equal(db.prepare('SELECT count(*) n FROM hosts').get().n,1);assert.equal(db.prepare('SELECT count(*) n FROM account_hosts').get().n,0);db.close();
});

test('removed computers reconnect with fresh credentials and can be approved under a different phone account',async t=>{
  const f=await setup(t),alice=await f.register('rebinduser');await f.approve(alice.user);await f.call('/api/account/me',{},alice.phone.token);
  const paths=f.paths('rebind-computer');await new Enrollment({paths,defaults:f.defaults}).prepare({name:'Transfer PC'});
  const old=readJson(paths.config),oldPhone=secret();
  await f.call('/api/pair',{hostId:old.hostId,pairCode:old.pairCode,mobileToken:oldPhone},alice.phone.token);
  await f.call('/api/pair/decision',{hostId:old.hostId,fingerprint:digest(oldPhone).slice(0,12),accept:true},old.desktopToken);
  class Adapter extends EventEmitter {
    async connect(){this.ready=true}
    diagnostics(){return{capabilities:Object.fromEntries(['threads.list','projects.list','thread.read','message.send','thread.create','models.list'].map(key=>[key,true]))}}
    close(){this.ready=false}
  }
  const runtime=new ConnectorRuntime({createAdapter:()=>new Adapter()});
  const app=await startConnector({paths,defaults:f.defaults,port:0,runtime,installation:{binary:()=>null,state:()=>({detected:true})}});t.after(()=>app.close());
  const api=async(path,body)=>{
    const res=await fetch(`http://127.0.0.1:${app.server.address().port}${path}`,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${app.token}`,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
    const json=await res.json();assert.equal(res.status,200,json.error);return json;
  };
  const until=async predicate=>{for(let i=0;i<200;i++){const state=await api('/api/state');if(predicate(state))return state;await delay(20)}assert.fail('Connector state did not settle')};
  await api('/api/connect',{});await until(state=>state.connected);const oldPort=runtime.port(old);
  await f.call('/api/admin/devices/remove',{userId:alice.user.id,device:'computer',hostId:old.hostId},f.root.token);
  await api('/api/mode',{mode:'cloud'});
  const fresh=readJson(paths.config);
  for(const field of ['hostId','desktopToken','pairCode','key','managerToken'])assert.notEqual(fresh[field],old[field],field+' must rotate');
  assert.equal(fresh.name,'Transfer PC');assert.equal(fresh.mobileClaim,true);assert(!existsSync(paths.account));
  assert.equal((await api('/api/state')).rebindRequired,true);
  await assert.rejects(fetch(`http://127.0.0.1:${oldPort}/api/status`),/fetch failed/);
  await api('/api/connect',{});await until(state=>state.connected);
  const qr=await api('/api/pairing'),pairing=JSON.parse(Buffer.from(new URL(qr.link).searchParams.get('data'),'base64url'));
  assert.equal(pairing.hostId,fresh.hostId);assert.equal(pairing.key,fresh.key);assert.equal(pairing.claimFromMobile,true);
  await f.call('/api/pair',{hostId:fresh.hostId,pairCode:fresh.pairCode,mobileToken:secret()},f.root.token);
  const pending=await until(state=>!!state.pairRequest);
  const db=new DatabaseSync(f.dbPath);t.after(()=>db.close());
  assert.equal(db.prepare('SELECT user_id FROM account_hosts WHERE host_id=?').get(fresh.hostId),undefined,'scan alone cannot change ownership');
  await api('/api/pair/decision',{accept:true,fingerprint:pending.pairRequest.fingerprint});
  assert.equal((await f.call('/api/account/me',{},alice.phone.token)).hosts.length,0);
  assert((await f.call('/api/account/me',{},f.root.token)).hosts.some(host=>host.id===fresh.hostId));
  assert.equal((await f.post('/api/hosts/status',{hostId:old.hostId},old.desktopToken)).status,401);
  assert.equal(db.prepare('SELECT mobile_hash FROM hosts WHERE id=?').get(old.hostId).mobile_hash,null);
  await new Enrollment({paths,defaults:f.defaults}).prepare();assert.equal(readJson(paths.config).hostId,fresh.hostId);
});

test('legacy account computers retain a staged replacement across response loss and restart',async t=>{
  const f=await setup(t),alice=await f.register('legacyrebind');await f.approve(alice.user);
  const session=await f.desktop('legacyrebind'),paths=f.paths('legacy-rebind');
  await new Enrollment({paths,defaults:f.defaults}).registerAccount({name:'Legacy PC',token:session.token,user:(await f.call('/api/account/me',{},session.token)).user});
  const oldText=readFileSync(paths.config,'utf8'),old=readJson(paths.config);
  await f.call('/api/admin/devices/remove',{userId:alice.user.id,device:'computer',hostId:old.hostId},f.root.token);
  let lost=true;
  const request=async(...args)=>{const result=await relayRequest(...args);if(lost&&args[1]==='/api/desktop/prepare'){lost=false;throw Error('lost replacement response')}return result};
  await assert.rejects(new Enrollment({paths,defaults:f.defaults,request}).prepare(),/lost replacement/);
  assert.equal(readFileSync(paths.config,'utf8'),oldText,'no unverified replacement may overwrite the local identity');
  const staged=readJson(paths.enrollment);assert.equal(staged.replacesHostId,old.hostId);
  assert.equal((await new Enrollment({paths,defaults:f.defaults}).prepare()).rebindRequired,true);
  const fresh=readJson(paths.config);assert.equal(fresh.hostId,staged.config.hostId);assert.equal(fresh.key,staged.config.key);assert.equal(fresh.mobileClaim,true);
  const db=new DatabaseSync(f.dbPath);assert.equal(db.prepare('SELECT count(*) n FROM hosts').get().n,2);db.close();
});

test('account denials and uncertain network failures never reset a healthy computer identity',async t=>{
  const f=await setup(t),alice=await f.register('deniedrebind');await f.approve(alice.user);await f.call('/api/account/me',{},alice.phone.token);
  const paths=f.paths('denied-rebind');await new Enrollment({paths,defaults:f.defaults}).prepare();const config=readJson(paths.config),mobileToken=secret();
  await f.call('/api/pair',{hostId:config.hostId,pairCode:config.pairCode,mobileToken},alice.phone.token);
  await f.call('/api/pair/decision',{hostId:config.hostId,fingerprint:digest(mobileToken).slice(0,12),accept:true},config.desktopToken);
  const original=()=>[readFileSync(paths.config,'utf8'),readFileSync(paths.enrollment,'utf8')],before=original();
  await f.call('/api/admin/users/decision',{userId:alice.user.id,action:'disable'},f.root.token);
  await assert.rejects(new Enrollment({paths,defaults:f.defaults}).prepare(),error=>error.statusCode===403);
  assert.deepEqual(original(),before);
  for(const error of [Error('network timeout'),Object.assign(Error('server unavailable'),{statusCode:503})]){
    await assert.rejects(new Enrollment({paths,defaults:f.defaults,request:async()=>{throw error}}).prepare(),e=>e===error);
    assert.deepEqual(original(),before);
  }
});

test('multiple computers preserve ownership, enforce configurable quotas and revoke the selected computer',async t=>{
  const f=await setup(t),alice=await f.register('multicomputer'),bob=await f.register('outside');
  assert.equal(alice.user.maxComputers,5);await f.approve(alice.user);await f.approve(bob.user);await f.call('/api/account/me',{},alice.phone.token);
  assert.equal((await f.post('/api/admin/users/computers',{userId:alice.user.id,limit:3},alice.phone.token)).status,403);
  await f.call('/api/admin/users/computers',{userId:alice.user.id,limit:3},f.root.token);
  const computers=[f.host(),f.host(),f.host()];
  for(const [i,h]of computers.entries()){
    h.name='Computer '+i;await f.call('/api/desktop/prepare',h);const pair={hostId:h.hostId,pairCode:h.pairCode,mobileToken:secret()};
    await f.call('/api/pair',pair,alice.phone.token);await f.call('/api/pair/decision',{hostId:h.hostId,fingerprint:digest(pair.mobileToken).slice(0,12),accept:true},h.desktopToken);
    assert.equal((await f.call('/api/pair/status',pair,alice.phone.token)).state,'approved');
    assert.equal((await f.post('/api/pair/status',pair,bob.phone.token)).status,403);
  }
  const listed=(await f.call('/api/account/me',{},alice.phone.token)).hosts;assert.deepEqual(new Set(listed.map(h=>h.id)),new Set(computers.map(h=>h.hostId)));
  const fourth=f.host();await f.call('/api/desktop/prepare',fourth);
  assert.equal((await f.post('/api/pair',{hostId:fourth.hostId,pairCode:fourth.pairCode,mobileToken:secret()},alice.phone.token)).status,409);
  assert.equal((await f.post('/api/admin/users/computers',{userId:alice.user.id,limit:2},f.root.token)).status,409);
  await f.restartRelay();assert.equal((await f.call('/api/account/me',{},alice.phone.token)).hosts.length,3);
  await f.call('/api/admin/devices/remove',{userId:alice.user.id,device:'computer',hostId:computers[1].hostId},f.root.token);
  assert.equal((await f.call('/api/account/me',{},alice.phone.token)).hosts.length,2);
  assert.equal((await f.post('/api/hosts/status',{hostId:computers[1].hostId},computers[1].desktopToken)).status,401);
  assert.equal((await f.call('/api/hosts/status',{hostId:computers[0].hostId},computers[0].desktopToken)).registered,true);
  const db=new DatabaseSync(f.dbPath);assert.equal(db.prepare('SELECT count(*) n FROM account_hosts WHERE user_id=?').get(alice.user.id).n,2);db.close();
});
