import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { existsSync } from 'node:fs';
import { WebSocket } from 'ws';
import { fixture } from './support/connector-fixture.mjs';
import { RelayProfiles, verifyRelay } from '../relay-profiles.mjs';
import { startConnector } from '../connector-server.mjs';
import { Enrollment, readJson } from '../connector-core.mjs';
import { digest, secret } from '../protocol.mjs';

test('custom relay validates TLS, host and protocol, isolates identities, and restores built-in pairing',async t=>{
  const a=await fixture(),b=await fixture({allowPairingOnly:true});t.after(()=>a.close());t.after(()=>b.close());
  const paths=a.paths('profiles'),profiles=new RelayProfiles(paths,a.defaults);
  assert.equal(profiles.info().builtin,true);assert.equal(profiles.paths().state,paths.state);
  await assert.rejects(verifyRelay({...b.defaults,caCert:a.defaults.caCert}));
  await assert.rejects(profiles.validate(a.defaults),/启用免账号配对/);
  await assert.rejects(profiles.validate({...b.defaults,relay:b.defaults.relay.replace('https:','http:')}),/地址/);
  const app=await startConnector({paths,defaults:a.defaults,port:0});t.after(()=>app.close());
  const api=async(path,body)=>{const res=await fetch(`http://127.0.0.1:${app.server.address().port}${path}`,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${app.token}`,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const json=await res.json();assert.equal(res.status,200,json.error);return json};
  await api('/api/mode',{mode:'cloud'});const builtin=readJson(paths.config);
  await api('/api/relay',b.defaults);const selected=await api('/api/state');assert.equal(selected.registered,false);assert.equal(selected.relayService.builtin,false);assert.equal(selected.relayService.relay,b.defaults.relay);
  await api('/api/mode',{mode:'cloud'});const customPath=profiles.paths();assert.notEqual(customPath.state,paths.state);assert(existsSync(customPath.config));
  const custom=readJson(customPath.config);assert.notEqual(custom.hostId,builtin.hostId);assert.notEqual(custom.key,builtin.key);assert.equal(custom.relay,b.defaults.relay);
  assert.equal(custom.mode,'selfhost');assert.equal(custom.mobileClaim,false);
  await api('/api/relay',{builtin:true});assert.equal(profiles.paths().state,paths.state);assert.equal(readJson(paths.config).hostId,builtin.hostId);assert.equal((await api('/api/state')).relayService.builtin,true);
  await api('/api/relay',b.defaults);assert.equal(readJson(profiles.paths().config).hostId,custom.hostId);
  const publicState=JSON.stringify(await api('/api/state'));for(const secret of [builtin.desktopToken,custom.key,custom.desktopToken])assert(!publicState.includes(secret));
});

test('only a relay enabled by its operator accepts desktop-confirmed pairing without an account',async t=>{
  const publicRelay=await fixture(), selfHosted=await fixture({allowPairingOnly:true});
  t.after(()=>publicRelay.close());t.after(()=>selfHosted.close());
  const host={hostId:secret().slice(0,32),desktopToken:secret(),pairCode:secret(),name:'测试电脑'};
  await assert.rejects(publicRelay.call('/api/desktop/prepare',{...host,pairingOnly:true}),/未启用/);
  await selfHosted.call('/api/desktop/prepare',host);
  await assert.rejects(selfHosted.call('/api/desktop/prepare',{...host,pairingOnly:true},host.desktopToken),/不能切换/);
  await assert.rejects(selfHosted.call('/api/pair',{hostId:host.hostId,pairCode:host.pairCode,mobileToken:secret()}),/登录账号/);

  const paths=selfHosted.paths('pairing-only');
  await new Enrollment({paths,defaults:selfHosted.defaults,pairingOnly:true}).prepare();
  const config=readJson(paths.config), {port}=await selfHosted.companion(config,paths);
  const response=await fetch(`http://127.0.0.1:${port}/api/pairing`,{headers:{Authorization:`Bearer ${config.managerToken}`}});
  const {link}=await response.json();
  const qr=JSON.parse(Buffer.from(link.split('data=')[1],'base64url'));
  assert.equal(qr.mode,'selfhost');assert.equal(qr.claimFromMobile,false);
  const pair={hostId:qr.hostId,pairCode:qr.pairCode,mobileToken:secret()};
  assert.equal((await selfHosted.call('/api/pair',pair)).state,'pending');
  assert.equal((await selfHosted.call('/api/pair/status',pair)).state,'pending');
  const decision={hostId:qr.hostId,fingerprint:digest(pair.mobileToken).slice(0,12),accept:true};
  await assert.rejects(selfHosted.call('/api/pair/decision',decision),/Unauthorized/);
  await selfHosted.call('/api/pair/decision',decision,config.desktopToken);
  await selfHosted.restartRelay();
  assert.equal((await selfHosted.call('/api/pair/status',pair)).state,'approved');
  const mobile=new WebSocket(selfHosted.defaults.relay.replace('https:','wss:')+'/relay',{ca:selfHosted.defaults.caCert,headers:{Authorization:`Bearer ${pair.mobileToken}`,'X-Pocket-Host':qr.hostId,'X-Pocket-Role':'mobile'}});
  selfHosted.sockets.push(mobile);await once(mobile,'open');
});

test('public certificate import refuses private keys without persisting their contents',async t=>{
  const f=await fixture();t.after(()=>f.close());const profiles=new RelayProfiles(f.paths('cert-input'),f.defaults);
  await assert.rejects(profiles.validate({...f.defaults,caCert:f.defaults.caCert+'\n-----BEGIN PRIVATE KEY-----\nprivate'}),/不能导入私钥/);
  assert.equal(profiles.info().builtin,true);
});
