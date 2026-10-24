import test from 'node:test';
import assert from 'node:assert/strict';
import { X509Certificate } from 'node:crypto';
import https from 'node:https';
import WebSocket from 'ws';
import { once } from 'node:events';
import { resolve } from 'node:path';
import { fixture } from './support/connector-fixture.mjs';
import { generateLanCertificate, privateIPv4, prepareLan, startLanRelay, lanAddresses } from '../lan.mjs';
import { relayRequest } from '../companion.mjs';
import { secret, digest } from '../protocol.mjs';
import { readJson } from '../connector-core.mjs';

test('LAN certificates use actual IP SANs, an authentic signature and a private key retained only on the computer',async()=>{
 const tls=generateLanCertificate(['192.168.1.12']);const cert=new X509Certificate(tls.cert);
 assert(new Date(cert.validTo).getTime()>Date.now());
 assert(cert.verify(cert.publicKey));assert.equal(cert.checkIP('192.168.1.12'),'192.168.1.12');assert.equal(cert.checkIP('8.8.8.8'),undefined);assert.equal(cert.checkHost('localhost'),'localhost');assert(cert.checkPrivateKey((await import('node:crypto')).createPrivateKey(tls.key)));
 assert.throws(()=>generateLanCertificate(['203.0.113.10']),/私有/);
 for(const ip of ['10.0.0.1','172.16.2.3','172.31.0.1','192.168.1.2'])assert(privateIPv4(ip));
 for(const ip of ['127.0.0.1','8.8.8.8','172.32.0.1','192.168.1.300','192.168.1.1.attacker','localhost','::1'])assert(!privateIPv4(ip));
 assert.deepEqual(lanAddresses({test:[{address:'192.168.1.12',family:'IPv4',internal:false},{address:'203.0.113.10',family:'IPv4',internal:false}]}),['192.168.1.12']);
});

test('LAN pairing works without an account, requires computer confirmation and survives a local relay restart',async t=>{
 const f=await fixture();t.after(()=>f.close());
 const setup=prepareLan(f.directory,{address:'10.0.2.2',addresses:['10.0.2.2'],port:24445});
 assert.equal(prepareLan(f.directory,{addresses:['10.0.2.2'],port:24445}).config.key,setup.config.key);
 const dbPath=resolve(f.directory,'lan/relay.sqlite');let lan=await startLanRelay(setup.config,{dbPath,bind:'127.0.0.1'});t.after(()=>lan.close());
 const config=setup.config;const mobile=secret();const peer={...config,relay:'https://127.0.0.1:24445'};
 assert.equal((await relayRequest(peer,'/api/pair',{hostId:config.hostId,pairCode:config.pairCode,mobileToken:mobile},null)).state,'pending');
 const rejected=new WebSocket('wss://127.0.0.1:24445/relay',{ca:config.caCert,headers:{Authorization:`Bearer ${mobile}`,'X-Pocket-Host':config.hostId,'X-Pocket-Role':'mobile'}});rejected.on('error',()=>{});const [,r]=await once(rejected,'unexpected-response');assert.equal(r.statusCode,401);r.resume();rejected.terminate();
 await relayRequest(peer,'/api/pair/decision',{hostId:config.hostId,fingerprint:digest(mobile).slice(0,12),accept:true});
 assert.equal((await relayRequest(peer,'/api/pair/status',{hostId:config.hostId,mobileToken:mobile},null)).state,'approved');
 const ws=new WebSocket('wss://127.0.0.1:24445/relay',{ca:config.caCert,headers:{Authorization:`Bearer ${mobile}`,'X-Pocket-Host':config.hostId,'X-Pocket-Role':'mobile'}});f.sockets.push(ws);await once(ws,'open');ws.terminate();await lan.close();
 lan=await startLanRelay(readJson(setup.file),{dbPath,bind:'127.0.0.1'});assert.equal((await relayRequest(peer,'/api/pair/status',{hostId:config.hostId,mobileToken:mobile},null)).state,'approved');
 const backend=await f.companion(config,{state:resolve(f.directory,'local-commands'),config:setup.file});assert(backend.app.status().relayOnline);
 const response=await fetch(`http://127.0.0.1:${backend.port}/api/pairing`,{headers:{Authorization:`Bearer ${config.managerToken}`}});const pairing=await response.json();const payload=JSON.parse(Buffer.from(new URL(pairing.link).searchParams.get('data'),'base64url'));
 assert.equal(payload.mode,'lan');assert.equal(payload.relay,config.relay);assert(!JSON.stringify(payload).includes(config.lanKey));assert(!JSON.stringify(payload).includes(config.lanAdmin));
});
