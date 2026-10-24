import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { secret } from '../protocol.mjs';
import { relayRequest } from '../companion.mjs';
import { validateDefaults } from '../connector-core.mjs';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');const args=process.argv.slice(2);
const arg=(name,fallback)=>args.includes(name)?args[args.indexOf(name)+1]:fallback;
try{
 if(args[0]!=='bootstrap')throw new Error('Usage: account-admin.mjs bootstrap --out <private JSON file> [--username pocket_admin] [--claim-config existing-companion.json]');
 const file=resolve(arg('--out',''));if(!args.includes('--out'))throw new Error('Specify --out; credentials are saved privately and never printed');
 const config=JSON.parse(readFileSync(arg('--config',resolve(root,'.private/companion.json')),'utf8'));const defaults=validateDefaults(config);
 const admin=readFileSync(arg('--admin-file',resolve(root,'.private/relay-admin.txt')),'utf8').trim();
 let credentials;
 if(existsSync(file)){credentials=JSON.parse(readFileSync(file,'utf8'));if(credentials.relay!==defaults.relay)throw new Error('Existing credentials belong to another server')}
 else{credentials={relay:defaults.relay,username:arg('--username','pocket_admin'),password:secret(),deviceId:secret()};mkdirSync(dirname(file),{recursive:true,mode:0o700});writeFileSync(file,JSON.stringify(credentials,null,2),{flag:'wx',mode:0o600})}
 try{await relayRequest(defaults,'/api/admin/bootstrap',{username:credentials.username,password:credentials.password,displayName:'超级管理员'},admin)}catch(e){if(e.statusCode!==409)throw e}
 const session=await relayRequest(defaults,'/api/account/login',{username:credentials.username,password:credentials.password,client:'desktop',deviceId:credentials.deviceId},null);
 if(session.user.role!=='admin')throw new Error('Server did not authorize the administrator');
 try{
   const claim=arg('--claim-config');if(claim){const old=JSON.parse(readFileSync(claim,'utf8'));await relayRequest(defaults,'/api/account/hosts/claim',{hostId:old.hostId,desktopToken:old.desktopToken},session.token)}
   const current=await relayRequest(defaults,'/api/account/me',{},session.token);if(current.user.accessState!=='active')throw new Error('Administrator is not active');
   writeFileSync(file,JSON.stringify({...credentials,verifiedAt:new Date().toISOString(),claimedHostId:current.host?.id??null},null,2),{mode:0o600});
   console.log(JSON.stringify({administratorVerified:true,existingComputerClaimed:!!current.host,credentialsSavedTo:file}));
 }finally{await relayRequest(defaults,'/api/account/logout',{},session.token)}
}catch(e){console.error(e.message);process.exitCode=1}
