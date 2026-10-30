import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fixture } from './support/connector-fixture.mjs';
import { defaultContacts, validateContacts } from '../contacts.mjs';
import { secret } from '../protocol.mjs';

test('contact defaults contain the supplied QR images and match Android assets',()=>{
  const android=JSON.parse(readFileSync(new URL('../android/app/src/main/assets/contacts.json',import.meta.url)));
  assert.deepEqual(android,defaultContacts);
  for(const [key,file] of [['personalQr','personal-wechat.jpg'],['groupQr','group-wechat.jpg']])assert.deepEqual(Buffer.from(defaultContacts[key].split(',')[1],'base64'),readFileSync(new URL('../design/contacts/'+file,import.meta.url)));
  assert.equal(defaultContacts.groupExpiresOn,'2026-11-06');
  assert.equal(defaultContacts.wechatId,'');
});

test('contacts reject remote URLs, SVG, oversized or malformed images and invalid dates',()=>{
  for(const patch of [{groupQr:'https://example.com/qr.jpg'},{personalQr:'data:image/svg+xml;base64,PHN2Zz4='},{groupQr:'data:image/jpeg;base64,abcd'},{groupQr:'data:image/jpeg;base64,'+'A'.repeat(710000)},{groupExpiresOn:'2026-02-30'},{groupExpiresOn:'tomorrow'},{title:'a'.repeat(61)},{wechatId:42}])assert.throws(()=>validateContacts({...defaultContacts,...patch}),e=>e.status===400);
  assert.equal(validateContacts({...defaultContacts,personalQr:'',groupQr:'',groupExpiresOn:''}).groupQr,'');
});

test('contacts are readable before approval; only admin can change, clear, and persist them',async t=>{
  const f=await fixture();t.after(()=>f.close());
  assert.deepEqual((await f.call('/api/account/contacts',{})).contacts,defaultContacts);
  await assert.rejects(f.call('/api/admin/contacts',defaultContacts),e=>e.statusCode===401);
  const password='Contacts-test-password-2026';
  await f.call('/api/admin/bootstrap',{username:'owner',password},f.admin);
  const root=await f.call('/api/account/login',{username:'owner',password,client:'desktop',deviceId:secret()});
  await f.call('/api/account/register',{username:'visitor',password,reason:'contacts test'});
  const visitor=await f.call('/api/account/login',{username:'visitor',password,client:'desktop',deviceId:secret()});
  await assert.rejects(f.call('/api/admin/contacts',defaultContacts,visitor.token),e=>e.statusCode===403);
  const configured={...defaultContacts,wechatId:'pocket_owner',description:'新的联系说明'};
  const saved=(await f.call('/api/admin/contacts',configured,root.token)).contacts;
  assert(saved.updatedAt>0);assert.equal(saved.wechatId,'pocket_owner');
  await f.restartRelay();
  assert.deepEqual((await f.call('/api/account/contacts',{},visitor.token)).contacts,saved);
  await assert.rejects(f.call('/api/admin/contacts',{...configured,groupExpiresOn:'2026-02-30'},root.token),e=>e.statusCode===400);
  assert.deepEqual((await f.call('/api/account/contacts',{})).contacts,saved,'failed validation must preserve current settings');
  const cleared={...configured,personalQr:'',groupQr:'',wechatId:'',groupExpiresOn:''};
  await f.call('/api/admin/contacts',cleared,root.token);await f.restartRelay();
  const result=(await f.call('/api/account/contacts',{})).contacts;assert.equal(result.groupQr,'');assert.equal(result.personalQr,'');
  assert(!JSON.stringify(result).includes(password));
});
