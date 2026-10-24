import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { fixture } from './support/connector-fixture.mjs';
import { RelayProfiles, verifyRelay } from '../relay-profiles.mjs';
import { startConnector } from '../connector.mjs';
import { readJson } from '../connector-core.mjs';

test('custom relay validates TLS, host and protocol, isolates identities, and restores built-in pairing',async t=>{
  const a=await fixture(),b=await fixture();t.after(()=>a.close());t.after(()=>b.close());
  const paths=a.paths('profiles'),profiles=new RelayProfiles(paths,a.defaults);
  assert.equal(profiles.info().builtin,true);assert.equal(profiles.paths().state,paths.state);
  await assert.rejects(verifyRelay({...b.defaults,caCert:a.defaults.caCert}));
  await assert.rejects(profiles.validate({...b.defaults,relay:b.defaults.relay.replace('https:','http:')}),/地址/);
  const app=await startConnector({paths,defaults:a.defaults,port:0});t.after(()=>app.close());
  const api=async(path,body)=>{const res=await fetch(`http://127.0.0.1:${app.server.address().port}${path}`,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${app.token}`,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const json=await res.json();assert.equal(res.status,200,json.error);return json};
  await api('/api/mode',{mode:'cloud'});const builtin=readJson(paths.config);
  await api('/api/relay',b.defaults);const selected=await api('/api/state');assert.equal(selected.registered,false);assert.equal(selected.relayService.builtin,false);assert.equal(selected.relayService.relay,b.defaults.relay);
  await api('/api/mode',{mode:'cloud'});const customPath=profiles.paths();assert.notEqual(customPath.state,paths.state);assert(existsSync(customPath.config));
  const custom=readJson(customPath.config);assert.notEqual(custom.hostId,builtin.hostId);assert.notEqual(custom.key,builtin.key);assert.equal(custom.relay,b.defaults.relay);
  await api('/api/relay',{builtin:true});assert.equal(profiles.paths().state,paths.state);assert.equal(readJson(paths.config).hostId,builtin.hostId);assert.equal((await api('/api/state')).relayService.builtin,true);
  await api('/api/relay',b.defaults);assert.equal(readJson(profiles.paths().config).hostId,custom.hostId);
  const publicState=JSON.stringify(await api('/api/state'));for(const secret of [builtin.desktopToken,custom.key,custom.desktopToken])assert(!publicState.includes(secret));
});

test('public certificate import refuses private keys without persisting their contents',async t=>{
  const f=await fixture();t.after(()=>f.close());const profiles=new RelayProfiles(f.paths('cert-input'),f.defaults);
  await assert.rejects(profiles.validate({...f.defaults,caCert:f.defaults.caCert+'\n-----BEGIN PRIVATE KEY-----\nprivate'}),/不能导入私钥/);
  assert.equal(profiles.info().builtin,true);
});
