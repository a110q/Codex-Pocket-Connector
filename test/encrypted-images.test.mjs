import test from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {randomUUID} from 'node:crypto';
import {readFileSync} from 'node:fs';
import WebSocket from 'ws';
import {fixture} from './support/connector-fixture.mjs';
import {CipherChannel,secret,digest} from '../protocol.mjs';

test('actual encrypted companion routes multi-chunk images and text into one adapter call, with replay protection',async t=>{
  const f=await fixture();t.after(()=>f.close());const {paths,config}=await f.enroll('encrypted-images');const mobileToken=secret();
  await f.call('/api/pair',{hostId:config.hostId,pairCode:config.pairCode,mobileToken});
  await f.call('/api/pair/decision',{hostId:config.hostId,fingerprint:digest(mobileToken).slice(0,12),accept:true},config.desktopToken);
  const {adapter}=await f.companion(config,paths);const calls=[];adapter.call=async(action,args)=>{calls.push({action,args});return{submitted:true}};
  const ws=new WebSocket(config.relay.replace('https:','wss:')+'/relay',{ca:config.caCert,headers:{Authorization:`Bearer ${mobileToken}`,'X-Pocket-Host':config.hostId,'X-Pocket-Role':'mobile'}});f.sockets.push(ws);
  const codec=new CipherChannel(config.key,config.hostId,'mobile'),channel=randomUUID(),pending=new Map();
  let helloResolve;const hello=new Promise(r=>helloResolve=r);
  ws.on('message',bytes=>{const raw=JSON.parse(bytes);if(raw.v!==1)return;const payload=codec.decrypt(raw);if(payload.type==='hello')helloResolve();if(payload.type==='response'){const p=pending.get(payload.id);if(p){clearTimeout(p.timer);pending.delete(payload.id);payload.error?p.reject(Error(payload.error)):p.resolve(payload.result)}}});
  await once(ws,'open');ws.send(JSON.stringify(codec.encrypt({type:'hello',channel,sentAt:Date.now()})));await hello;
  const request=(action,args,id=randomUUID())=>new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('test request timeout')),5000);pending.set(id,{resolve,reject,timer});ws.send(JSON.stringify(codec.encrypt({type:'request',channel,sentAt:Date.now(),action,args,id})));});
  const bytes=Buffer.alloc(400*1024,42);Buffer.from('89504e470d0a1a0a','hex').copy(bytes);const uploadId=randomUUID();
  await request('image.upload.start',{uploadId,threadId:'qa',mime:'image/png',size:bytes.length,sha256:digest(bytes)});
  for(let offset=0;offset<bytes.length;offset+=192*1024)await request('image.upload.chunk',{uploadId,offset,data:bytes.subarray(offset,offset+192*1024).toString('base64')});
  await request('image.upload.finish',{uploadId});const id=randomUUID(),args={threadId:'qa',text:'one image and text',imageUploadIds:[uploadId],imagePaths:['/must-never-reach-adapter']};
  await request('message.send',args,id);await request('message.send',args,id);
  const messages=calls.filter(c=>c.action==='message.send');assert.equal(messages.length,1);assert.equal(messages[0].args.text,args.text);assert.equal(messages[0].args.clientUserMessageId,id);assert.equal(messages[0].args.imagePaths.length,1);assert(readFileSync(messages[0].args.imagePaths[0]).equals(bytes));
  await assert.rejects(request('message.send',{threadId:'other',text:'not this chat',imageUploadIds:[uploadId]}),/不属于/);
});
