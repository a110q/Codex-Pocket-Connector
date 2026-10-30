// Prepare a local candidate only. Uploading packages and selecting a live
// manifest are separate, explicitly authorized release operations.
import { createHash, createPrivateKey, createPublicKey, sign } from 'node:crypto';
import { cpSync, createReadStream, existsSync, mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { PRODUCT_VERSION, BUILD_NUMBER } from '../version.mjs';
import { assetName, loadUpdateConfig, verifyRelease } from '../updates.mjs';

const root=resolve(import.meta.dirname,'..');
const args=process.argv.slice(2);
function option(name){const index=args.indexOf(name);if(index<0||!args[index+1]||args[index+1].startsWith('--'))throw Error(`Missing ${name}`);return resolve(args[index+1]);}
async function hash(file){const digest=createHash('sha256');for await(const chunk of createReadStream(file))digest.update(chunk);return digest.digest('hex');}
let stage;
try{
  if(args.length!==4||!args.includes('--key')||!args.includes('--notes'))throw Error('Usage: node scripts/prepare-update.mjs --key PRIVATE_KEY_FILE --notes NOTES_FILE');
  const key=createPrivateKey(readFileSync(option('--key'))),notes=readFileSync(option('--notes'),'utf8').trim();
  const config=loadUpdateConfig(root);
  if(!config||createPublicKey(key).export({type:'spki',format:'pem'})!==createPublicKey(config.publicKey).export({type:'spki',format:'pem'}))throw Error('Signing key does not match the bundled update public key');
  if(!notes||notes.length>8192)throw Error('Update notes must contain 1–8192 characters');
  const base=resolve(root,'.private/update-candidates');mkdirSync(base,{recursive:true,mode:0o700});
  const output=resolve(base,PRODUCT_VERSION);if(existsSync(output))throw Error('This version already has a candidate; inspect it before preparing another');
  const sources={android:resolve(root,`releases/android/Codex-Pocket-${PRODUCT_VERSION}.apk`),windows:resolve(root,`releases/windows/Codex-Pocket-Connector-${PRODUCT_VERSION}-Windows.exe`),macos:resolve(root,`releases/mac/Codex-Pocket-Connector-${PRODUCT_VERSION}.app`)};
  for(const [platform,path]of Object.entries(sources))if(!existsSync(path)||(platform==='macos'?!statSync(path).isDirectory():!statSync(path).isFile()))throw Error(`Missing current ${platform} release artifact`);
  const bundle=resolve(sources.macos,'Contents/Resources/connector/package.json');
  const packaged=JSON.parse(readFileSync(bundle,'utf8'));if(packaged.pocketVersion!==PRODUCT_VERSION||packaged.pocketBuild!==BUILD_NUMBER)throw Error('Mac package version does not match source');
  const verified=spawnSync('/usr/bin/codesign',['--verify','--deep','--strict',sources.macos],{encoding:'utf8'});if(verified.status!==0)throw Error('Mac package signature verification failed');
  stage=mkdtempSync(resolve(base,'.preparing-'));
  const packages=resolve(stage,'packages',PRODUCT_VERSION);mkdirSync(packages,{recursive:true});
  const assets=[];
  for(const platform of ['android','macos','windows']){
    const name=assetName(platform,PRODUCT_VERSION),target=resolve(packages,name);
    if(platform==='macos'){
      const result=spawnSync('/usr/bin/ditto',['-c','-k','--sequesterRsrc','--keepParent',sources.macos,target],{encoding:'utf8'});
      if(result.status!==0)throw Error('Could not create macOS update ZIP');
    }else{
      cpSync(sources[platform],target);if(await hash(sources[platform])!==await hash(target))throw Error('Package copy checksum mismatch');
    }
    assets.push({name,platform,kind:platform==='android'?'apk':platform==='macos'?'zip':'exe',size:statSync(target).size,sha256:await hash(target),url:new URL(`/packages/${PRODUCT_VERSION}/${name}`,config.manifestUrl).href});
  }
  const payload={schema:1,channel:'stable',version:PRODUCT_VERSION,build:BUILD_NUMBER,publishedAt:new Date().toISOString(),notes,assets};
  const bytes=Buffer.from(JSON.stringify(payload));
  const envelope={schema:1,algorithm:'Ed25519',keyId:config.keyId,payload:bytes.toString('base64'),signature:sign(null,bytes,key).toString('base64')};
  const signed=Buffer.from(JSON.stringify(envelope,null,2)+'\n');verifyRelease(signed,config);
  mkdirSync(resolve(stage,'updates'));
  writeFileSync(resolve(stage,'updates/stable.json'),signed);
  writeFileSync(resolve(stage,'release.json'),JSON.stringify(payload,null,2)+'\n');
  writeFileSync(resolve(stage,'HOLD.txt'),'LOCAL CANDIDATE — NOT PUBLISHED\nDo not upload or select updates/stable.json until the user confirms the release version.\nThe signing private key is deliberately absent from this directory.\n');
  renameSync(stage,output);stage=null;
  console.log(JSON.stringify({version:PRODUCT_VERSION,build:BUILD_NUMBER,directory:output,published:false,assets:assets.map(({name,size,sha256})=>({name,size,sha256}))}));
}catch(error){console.error(error.message);process.exitCode=1;}
finally{if(stage)rmSync(stage,{recursive:true,force:true});}
