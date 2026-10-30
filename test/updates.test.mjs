import test from 'node:test';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { readFileSync, existsSync, writeFileSync, rmSync } from 'node:fs';
import { compareVersions } from '../version.mjs';
import { UpdateManager, verifyRelease, updateRequest } from '../updates.mjs';
import { updateFixture } from './support/update-fixture.mjs';
import { fixture } from './support/connector-fixture.mjs';
import { startConnector } from '../connector.mjs';

test('update versions compare numeric third and fourth components without treating a prefix as equal',()=>{
  for(const [a,b]of[['0.9.10','0.9.9.3'],['0.9.9.10','0.9.9.3'],['0.10.0','0.9.99.99'],['1.0.0','0.99.99.99']]){assert.equal(compareVersions(a,b),1);assert.equal(compareVersions(b,a),-1)}
  assert.equal(compareVersions('0.9.10','0.9.10.0'),0);
  for(const value of ['v0.9.10','0.9','0.9.10-beta','0.09.10','1.2.3.4.5'])assert.throws(()=>compareVersions(value,'0.9.10'));
});
test('real pinned TLS and signed release select Mac and Windows downloads; latest and ahead remain distinct',async t=>{
  const f=await updateFixture();t.after(()=>f.close());
  for(const platform of ['darwin','win32']){
    const updater=new UpdateManager({config:f.config,directory:resolve(f.directory,platform),platform,version:'0.9.9.3',build:35});t.after(()=>updater.close());
    const result=await updater.check();assert.equal(result.status,'available');assert.equal(result.release.version,'0.9.10');assert.equal(result.currentVersion,'0.9.9.3');assert.equal(result.asset.kind,platform==='darwin'?'zip':'exe');
  }
  for(const [current,ahead]of[['0.9.10',false],['0.9.10.1',true]]){
    const updater=new UpdateManager({config:f.config,directory:f.directory,platform:'darwin',version:current});
    const result=await updater.check();assert.equal(result.status,'current');assert.equal(result.ahead,ahead);
    assert.throws(()=>updater.download(),/检测更新/);
  }
  f.build=35;
  const invalid=new UpdateManager({config:f.config,directory:f.directory,platform:'darwin',version:'0.9.9.3',build:35});
  assert.equal((await invalid.check()).status,'error');assert.match(invalid.state().error,/构建号/);
  assert(f.requests.every(r=>r.authorization===undefined),'updates never receive account credentials');
});
test('failed checks can retry; unsigned, wrong-origin and wrong-platform releases are rejected',async t=>{
  const f=await updateFixture();t.after(()=>f.close());const u=new UpdateManager({config:f.config,directory:f.directory,platform:'darwin',version:'0.9.9.3',build:35});t.after(()=>u.close());
  for(const [mode,message]of[['tampered','验签失败'],['foreign','格式无效'],['missing','当前系统'],['unavailable','暂不可用']]){f.mode=mode;const value=await u.check();assert.equal(value.status,'error');assert(value.error.includes(message),value.error);assert.equal(value.release,null);assert.throws(()=>u.download())}
  f.mode='normal';assert.equal((await u.check()).status,'available');
  const unsigned=f.envelope(f.payload());unsigned.signature='';assert.throws(()=>verifyRelease(Buffer.from(JSON.stringify(unsigned)),f.config),/验签/);
  await assert.rejects(updateRequest(f.config.manifestUrl,{...f.config,pin:'0'.repeat(64)}),/证书不匹配/);
  await assert.rejects(updateRequest(f.config.manifestUrl,{...f.config,caCert:undefined}));
});
test('downloads enforce size/hash and refuse redirects, remove partial files, retry and open only verified files',async t=>{
  const f=await updateFixture();t.after(()=>f.close());let opened='';const u=new UpdateManager({config:f.config,directory:resolve(f.directory,'download'),platform:'win32',version:'0.9.9.3',build:35,reveal:file=>opened=file});t.after(()=>u.close());
  const state=await u.check(),destination=resolve(f.directory,'download',state.release.version,state.asset.name);
  assert.throws(()=>u.open(),/完成下载/);
  for(const mode of ['corrupt','redirect','broken']){
    f.mode=mode;u.download();await u.job;assert.equal(u.state().download,'error');assert(!existsSync(destination));assert(!existsSync(destination+'.partial'));
  }
  f.mode='normal';u.download();await u.job;assert.equal(u.state().download,'ready');assert.deepEqual(readFileSync(destination),f.package);u.open();assert.equal(opened,destination);
  writeFileSync(destination,'changed');assert.throws(()=>u.open(),/已改变/);
  assert.match(u.state().error,/已改变/);assert.equal(u.state().download,'error');
  u.download();await u.job;rmSync(destination);assert.throws(()=>u.open(),/已移除/);
  assert.match(u.state().error,/已移除/);assert.equal(u.state().download,'error');
  u.download();await u.job;assert.equal(u.state().download,'ready');u.open();assert.equal(u.state().error,'');
});
test('connector update actions require local authentication and do not stop its connection runtime',async t=>{
  const f=await updateFixture();t.after(()=>f.close());const relay=await fixture();t.after(()=>relay.close());
  const u=new UpdateManager({config:f.config,directory:resolve(f.directory,'connector'),platform:'darwin',version:'0.9.9.3',build:35});
  const app=await startConnector({paths:relay.paths('updates'),defaults:relay.defaults,port:0,updates:u});t.after(()=>app.close());
  const base=`http://127.0.0.1:${app.server.address().port}`;
  assert.equal((await fetch(base+'/api/updates')).status,401);
  const headers={Authorization:`Bearer ${app.token}`,'Content-Type':'application/json'};
  assert.equal((await fetch(base+'/api/updates/check',{method:'POST',headers:{...headers,Origin:'https://foreign.example'},body:'{}'})).status,403);
  f.delay=50;
  const calls=await Promise.all([1,2].map(()=>fetch(base+'/api/updates/check',{method:'POST',headers,body:'{}'}).then(r=>r.json())));
  assert(calls.some(s=>s.status==='available'));assert.equal(f.requests.filter(r=>r.url==='/updates/stable.json').length,1);
  assert.equal((await fetch(base+'/api/updates',{headers}).then(r=>r.json())).status,'available');
  const response=await fetch(base+'/api/updates/download',{method:'POST',headers,body:'{}'});assert.equal(response.status,200);await u.job;assert.equal(u.state().download,'ready');assert.equal(app.runtime.busy,false);
});
