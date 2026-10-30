import { mkdirSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createHash, generateKeyPairSync, sign, X509Certificate } from 'node:crypto';
import https from 'node:https';
import { assetName } from '../../updates.mjs';

export async function updateFixture({ version='0.9.10', build=36 }={}) {
  const base=resolve('.private/tests');mkdirSync(base,{recursive:true});
  const directory=mkdtempSync(resolve(base,'updates-'));
  const certFile=resolve(directory,'cert.pem'),keyFile=resolve(directory,'key.pem');
  const command=spawnSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-keyout',keyFile,'-out',certFile,'-days','2','-subj','/CN=localhost','-addext','subjectAltName=IP:127.0.0.1,DNS:localhost'],{stdio:'ignore'});
  if(command.status!==0)throw Error('Cannot generate update fixture certificate');
  const cert=readFileSync(certFile,'utf8'), key=readFileSync(keyFile,'utf8');
  const keys=generateKeyPairSync('ed25519');
  const fixture={directory,version,build,mode:'normal',requests:[],package:Buffer.from('Codex Pocket verified test package\n'),delay:0};
  const server=https.createServer({cert,key},(req,res)=>{
    fixture.requests.push({url:req.url,authorization:req.headers.authorization});
    if(req.url==='/updates/stable.json'){
      if(fixture.mode==='unavailable'){res.writeHead(503).end('{}');return;}
      const payload=fixture.payload();
      if(fixture.mode==='missing')payload.assets=payload.assets.filter(a=>a.platform!=='macos');
      if(fixture.mode==='foreign')payload.assets[0].url='https://example.com/packages/other.apk';
      const envelope=fixture.envelope(payload);
      if(fixture.mode==='tampered')envelope.payload=Buffer.from(JSON.stringify({...payload,notes:'tampered'})).toString('base64');
      const send=()=>{res.setHeader('Content-Type','application/json');res.end(JSON.stringify(envelope));};
      if(fixture.delay)setTimeout(send,fixture.delay);else send();return;
    }
    if(req.url.startsWith('/packages/')){
      if(fixture.mode==='redirect'){res.writeHead(302,{location:'https://example.com/unsafe'}).end();return;}
      if(fixture.mode==='broken'){res.destroy();return;}
      res.end(fixture.mode==='corrupt'?Buffer.from('corrupt'):fixture.package);return;
    }
    res.writeHead(404).end();
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const origin=`https://127.0.0.1:${server.address().port}`;
  fixture.config={manifestUrl:origin+'/updates/stable.json',caCert:cert,pin:new X509Certificate(cert).fingerprint256.replaceAll(':','').toLowerCase(),publicKey:keys.publicKey.export({type:'spki',format:'pem'}),keyId:createHash('sha256').update(keys.publicKey.export({type:'spki',format:'der'})).digest('hex').slice(0,16)};
  fixture.payload=()=>({schema:1,channel:'stable',version:fixture.version,build:fixture.build,publishedAt:'2026-10-30T00:00:00.000Z',notes:'新增检测更新。\n下载并校验安装包，保留原账号与配对信息。',assets:['android','macos','windows'].map(platform=>{const name=assetName(platform,fixture.version);return{name,platform,kind:platform==='macos'?'zip':platform==='windows'?'exe':'apk',size:fixture.package.length,sha256:createHash('sha256').update(fixture.package).digest('hex'),url:`${origin}/packages/${fixture.version}/${name}`}})});
  fixture.envelope=payload=>{const bytes=Buffer.from(JSON.stringify(payload));return{schema:1,algorithm:'Ed25519',keyId:fixture.config.keyId,payload:bytes.toString('base64'),signature:sign(null,bytes,keys.privateKey).toString('base64')}};
  fixture.close=async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));rmSync(directory,{recursive:true,force:true})};
  return fixture;
}
