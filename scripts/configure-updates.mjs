import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createHash, createPublicKey, X509Certificate } from 'node:crypto';
import { validateUpdateConfig } from '../updates.mjs';

const arg = name => { const index=process.argv.indexOf(name); if(index<0||!process.argv[index+1])throw Error(`Missing ${name}`);return process.argv[index+1]; };
const root=resolve(import.meta.dirname,'..');
try {
  const caCert=readFileSync(resolve(arg('--cert')),'utf8');
  const publicKey=createPublicKey(readFileSync(resolve(arg('--public-key')),'utf8'));
  const key=publicKey.export({type:'spki',format:'der'});
  const config=validateUpdateConfig({manifestUrl:arg('--manifest'),caCert,pin:new X509Certificate(caCert).fingerprint256.replaceAll(':','').toLowerCase(),publicKey:publicKey.export({type:'spki',format:'pem'}),keyId:createHash('sha256').update(key).digest('hex').slice(0,16)});
  const json=JSON.stringify(config,null,2)+'\n';
  for(const file of ['update-defaults.json','android/app/src/main/assets/updates.json'])writeFileSync(resolve(root,file),json);
  console.log('Configured desktop and Android updates; public certificates and verification key only. No server changes.');
} catch(error){console.error(error.message);process.exitCode=1;}
