import { generateKeyPairSync, randomBytes, sign, X509Certificate } from 'node:crypto';
import { networkInterfaces } from 'node:os';
import { readFileSync, existsSync, mkdirSync, writeFileSync, renameSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import https from 'node:https';
import { createRelay } from './relay.mjs';
import { secret } from './protocol.mjs';

export function privateIPv4(address, { loopback = false } = {}) {
  if(typeof address!=='string')return false;
  const value=address.replace(/^::ffff:/,'');
  if(loopback&&value==='::1')return true;
  const parts=value.split('.');if(parts.length!==4||parts.some(v=>!/^(0|[1-9]\d{0,2})$/.test(v)||Number(v)>255))return false;
  const [a,b]=parts.map(Number);return a===10||(a===172&&b>=16&&b<=31)||(a===192&&b===168)||(loopback&&a===127);
}
export function lanAddresses(interfaces=networkInterfaces()) {
  return [...new Set(Object.values(interfaces).flat().filter(v=>v&&!v.internal&&v.family==='IPv4'&&privateIPv4(v.address)).map(v=>v.address))];
}
// Minimal DER encoding for a self-signed TLS server certificate. The private
// key stays on the computer; the scanned certificate fingerprint is the trust
// anchor. Uses only Node crypto so Windows needs no external OpenSSL/runtime.
const der=(tag,...values)=>{const bytes=Buffer.concat(values.map(v=>Buffer.from(v)));const size=bytes.length;const length=size<128?Buffer.from([size]):(()=>{const bytes=[];let n=size;while(n){bytes.unshift(n&255);n>>>=8}return Buffer.from([128|bytes.length,...bytes])})();return Buffer.concat([Buffer.from([tag]),length,bytes])};
const seq=(...v)=>der(0x30,...v),oid=bytes=>der(6,Buffer.from(bytes)),integer=bytes=>der(2,bytes);
const algorithm=()=>seq(oid([0x2a,0x86,0x48,0x86,0xf7,0x0d,1,1,0x0b]),der(5));
const name=()=>seq(der(0x31,seq(oid([0x55,4,3]),der(0x0c,Buffer.from('Codex Pocket LAN')))));
const time=value=>der(0x17,Buffer.from(value.toISOString().replace(/[-:T]/g,'').replace(/\.\d{3}Z$/,'Z').slice(2)));
const extension=(id,value,critical=false)=>seq(oid(id),...(critical?[der(1,[255])]:[]),der(4,value));
export function generateLanCertificate(addresses,now=Date.now()) {
  if(addresses.some(ip=>!privateIPv4(ip,{loopback:true})))throw new Error('局域网证书只接受私有 IPv4 地址');
  const {privateKey,publicKey}=generateKeyPairSync('rsa',{modulusLength:2048});
  const serial=randomBytes(16);serial[0]&=0x7f;serial[0]|=1;
  const san=seq(der(0x82,Buffer.from('localhost')),...[...new Set(['127.0.0.1',...addresses])].map(ip=>der(0x87,ip.split('.').map(Number))));
  const tbs=seq(der(0xa0,integer([2])),integer(serial),algorithm(),name(),seq(time(new Date(now-300000)),time(new Date(now+730*86400000))),name(),publicKey.export({type:'spki',format:'der'}),der(0xa3,seq(extension([0x55,0x1d,0x13],seq(der(1,[255])),true),extension([0x55,0x1d,0x11],san),extension([0x55,0x1d,0x25],seq(oid([0x2b,6,1,5,5,7,3,1]))))));
  const certificate=seq(tbs,algorithm(),der(3,[0],sign('sha256',tbs,privateKey)));
  const cert='-----BEGIN CERTIFICATE-----\n'+certificate.toString('base64').match(/.{1,64}/g).join('\n')+'\n-----END CERTIFICATE-----\n';
  return{cert,key:privateKey.export({type:'pkcs8',format:'pem'}),pin:new X509Certificate(cert).fingerprint256.replaceAll(':','').toLowerCase()};
}
function save(path,value){mkdirSync(dirname(path),{recursive:true,mode:0o700});const tmp=path+'.pending';writeFileSync(tmp,JSON.stringify(value,null,2),{mode:0o600});renameSync(tmp,path)}
export function prepareLan(state,{address,port=24444,addresses=lanAddresses(),renew=false}={}) {
  if(!addresses.length)throw new Error('未找到局域网 IPv4 地址，请先连接 Wi-Fi 或网线');
  const file=resolve(state,'lan','companion.json');let config=existsSync(file)?JSON.parse(readFileSync(file,'utf8')):null;
  if(address && !addresses.includes(address))throw new Error('所选局域网地址已失效，请重新选择当前 Wi-Fi 或网线地址');
  let savedAddress;try{savedAddress=new URL(config?.relay).hostname}catch{}
  if(!address)address=addresses.includes(savedAddress)?savedAddress:addresses[0];
  if(!privateIPv4(address)||!Number.isInteger(port)||port<1024||port>65535)throw new Error('局域网地址或端口无效');
  let canReuse=false;
  try { const cert=new X509Certificate(config?.caCert);canReuse=!renew&&config?.mode==='lan'&&config.lanPort===port&&cert.checkIP(address)&&new Date(cert.validTo).getTime()>Date.now()+86400000; } catch {}
  const pairingChanged=!!config && (!canReuse || config.relay!==`https://${address}:${port}`);
  if(!canReuse){
    if(config)save(resolve(state,'lan',`companion-before-${Date.now()}.json`),config);
    const tls=generateLanCertificate(addresses);
    // Refresh TLS for the new network without inventing a second computer or
    // silently revoking the phone. Pairing still requires local confirmation.
    const identity=config?.mode==='lan'&&!renew ? Object.fromEntries(['lanAdmin','name','hostId','desktopToken','pairCode','key','managerToken'].filter(k=>config[k]).map(k=>[k,config[k]])) : {};
    config={mode:'lan',relay:`https://${address}:${port}`,caCert:tls.cert,pin:tls.pin,lanKey:tls.key,lanAdmin:secret(),lanPort:port,name:'局域网电脑',hostId:secret().slice(0,32),desktopToken:secret(),pairCode:secret(),key:secret(),managerToken:secret(),...identity,pairExpires:Date.now()+600000};
  }
  config.relay=`https://${address}:${port}`;if(pairingChanged)config.pairExpires=Date.now()+600000;save(file,config);return{file,config,addresses,pairingChanged};
}
export async function startLanRelay(config,{dbPath,bind='0.0.0.0'}={}) {
  if(config.mode!=='lan'||!privateIPv4(new URL(config.relay).hostname))throw new Error('局域网模式只连接私有 IPv4 地址');
  const relay=createRelay({adminToken:config.lanAdmin,cert:config.caCert,key:config.lanKey,dbPath,allowPeer:ip=>privateIPv4(ip,{loopback:true})});
  try {
    await new Promise((resolve,reject)=>{relay.server.once('error',reject);relay.server.listen(config.lanPort,bind,resolve)});
    // Register exactly this computer on its own local relay. This credential
    // never reaches the public account server or the phone.
    const post=(path,body,token)=>new Promise((resolve,reject)=>{const data=JSON.stringify(body);const req=https.request({hostname:'127.0.0.1',port:config.lanPort,path,method:'POST',agent:false,ca:config.caCert,headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json','Content-Length':Buffer.byteLength(data)}},res=>{let text='';res.on('data',chunk=>text+=chunk);res.on('end',()=>{try{const json=JSON.parse(text);res.statusCode===200?resolve(json):reject(Object.assign(new Error(json.error),{status:res.statusCode}))}catch(e){reject(e)}})});req.on('error',reject);req.end(data)});
    try{await post('/api/hosts/status',{hostId:config.hostId},config.desktopToken)}
    catch(e){if(e.status!==401)throw e;await post('/api/hosts',{hostId:config.hostId,name:config.name,desktopToken:config.desktopToken,pairCode:config.pairCode},config.lanAdmin)}
    return relay;
  }catch(e){await relay.close();throw e}
}
