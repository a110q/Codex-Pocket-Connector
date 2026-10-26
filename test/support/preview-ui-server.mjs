// Isolated real relay + companion + preview tunnel, never attaches to user chats.
import http from 'node:http';
import { writeFileSync,readFileSync,mkdirSync,existsSync } from 'node:fs';
import { fixture } from './connector-fixture.mjs';
import { digest, secret } from '../../protocol.mjs';
import { PreviewTunnel } from '../../preview-tunnel.mjs';
import {relayRequest} from '../../companion.mjs';
mkdirSync('.private/preview-095',{recursive:true,mode:0o700});
const call = PreviewTunnel.prototype.call;
PreviewTunnel.prototype.call = async function(action,args) {
  const start=Date.now();
  try { const result=await call.call(this,action,args);console.log(JSON.stringify({action,ms:Date.now()-start,socket:args.socketId,bytes:result.data?.length||0,done:result.done}));return result; }
  catch(e){console.log(JSON.stringify({action,error:e.message}));throw e;}
};
const requests=[];
const site=http.createServer((req,res)=>{
  requests.push(req.url);res.setHeader('Cache-Control','no-store');
  if(req.url==='/stalled')return;
  if(req.url==='/slow') {res.setHeader('Content-Type','text/html');res.end('<body>Waiting for module<script src="/slow.js"></script>');return;}
  if(req.url==='/slow.js'){
    res.setHeader('Content-Type','text/javascript');res.write('/*');let ticks=0;
    const timer=setInterval(()=>{if(++ticks===7){clearInterval(timer);res.end("*/document.body.textContent='SLOW_READY';")}else res.write('still downloading\n');},5000);
    res.on('close',()=>clearInterval(timer));return;
  }
  if(req.url==='/app.js') {res.setHeader('Content-Type','text/javascript');res.end(Array.from({length:30},(_,i)=>`import {v as v${i}} from '/module-${i}.js';`).join('\n')+`\ndocument.body.textContent='MODULES_READY '+(${Array.from({length:30},(_,i)=>`v${i}`).join('+')});`);}
  else if(/^\/module-\d+\.js$/.test(req.url)){res.setHeader('Content-Type','text/javascript');res.end('/*'+'x'.repeat(24000)+'*/\nexport const v=1;');}
  else {res.setHeader('Content-Type','text/html; charset=utf-8');res.end('<!doctype html><meta name="viewport" content="width=device-width"><body>Loading modules<script type="module" src="/app.js"></script>');}
});
await new Promise(r=>site.listen(0,'127.0.0.1',r));
const f=await fixture();
let config,paths,publicTest=false;
if(process.env.POCKET_PREVIEW_PUBLIC_CONFIG){
  const source=JSON.parse(readFileSync(process.env.POCKET_PREVIEW_PUBLIC_CONFIG,'utf8'));
  const previous=existsSync('.private/preview-095/public-test-config.json')?JSON.parse(readFileSync('.private/preview-095/public-test-config.json','utf8')):null;
  if(previous&&(previous.relay!==source.relay||previous.name!=='QA isolated preview 0.9.5'))throw new Error('Unexpected fixture identity');
  config=previous??{relay:source.relay,caCert:source.caCert,pin:source.pin,hostId:secret().slice(0,32),desktopToken:secret(),pairCode:secret(),key:secret(),managerToken:secret(),name:'QA isolated preview 0.9.5'};
  paths=f.paths('public-preview');mkdirSync(paths.state,{recursive:true});
  const admin=readFileSync(process.env.POCKET_PREVIEW_ADMIN_FILE,'utf8').trim();
  if(previous)await relayRequest(config,'/api/pair/reset',{hostId:config.hostId,pairCode:config.pairCode});else await relayRequest(config,'/api/hosts',config,admin);publicTest=true;
  writeFileSync('.private/preview-095/public-test-config.json',JSON.stringify(config),{mode:0o600});
}else ({config,paths}=await f.enroll('网页链路验收'));
const request=(path,body,token)=>publicTest?relayRequest(config,path,body,token):f.call(path,body,token);
const mobileToken=secret();
await request('/api/pair',{hostId:config.hostId,pairCode:config.pairCode,mobileToken});
await request('/api/pair/decision',{hostId:config.hostId,fingerprint:digest(mobileToken).slice(0,12),accept:true},config.desktopToken);
await f.companion(config,paths);
const profile={relay:config.relay,hostId:config.hostId,key:config.key,pin:config.pin,mobileToken,approved:true,name:'网页链路验收'};
writeFileSync('.private/preview-095/fixture.json',JSON.stringify({profile,port:new URL(profile.relay).port,url:`http://127.0.0.1:${site.address().port}/`}),{mode:0o600});
console.log('PREVIEW_FIXTURE_READY');
for(const sig of ['SIGINT','SIGTERM']) process.once(sig,async()=>{site.closeAllConnections();await new Promise(r=>site.close(r));if(publicTest)await relayRequest(config,'/api/pair/reset',{hostId:config.hostId,pairCode:secret()});await f.close();process.exit(0)});
