import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { validateDefaults } from '../connector-core.mjs';

const root=resolve(import.meta.dirname,'..'),defaults=validateDefaults(JSON.parse(readFileSync(resolve(root,'.private/companion.json'))));
const result=spawnSync(resolve(process.env.ANDROID_HOME??resolve(process.env.HOME,'Library/Android/sdk'),'build-tools/37.0.0/apksigner'),['verify','--print-certs',resolve(root,'android/app/build/outputs/apk/release/app-release.apk')],{env:{...process.env,JAVA_HOME:process.env.JAVA_HOME??'/Applications/Android Studio.app/Contents/jbr/Contents/Home'},encoding:'utf8'});
const certificate=result.stdout?.match(/(?:Signer #1|V2 Signer):? certificate SHA-256 digest: ([a-f0-9]{64})/i)?.[1]?.toLowerCase();
if(result.status!==0||!certificate)throw new Error('Cannot read the public release signing certificate from the previous signed APK');
const asset=resolve(root,'android/app/src/main/assets');mkdirSync(asset,{recursive:true});
writeFileSync(resolve(asset,'server.json'),JSON.stringify({relay:defaults.relay,pin:defaults.pin,signingSha256:certificate},null,2));
writeFileSync(resolve(root,'.private/release-android-public.json'),JSON.stringify({certificate},null,2));
console.log('Public server configuration and release certificate generated; no account or pairing credentials included.');
