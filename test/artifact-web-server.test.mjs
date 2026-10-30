import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync, readFileSync, rmSync, symlinkSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { ArtifactWebServer } from '../artifact-web-server.mjs';
import { PreviewTunnel } from '../preview-tunnel.mjs';
import { WorkspaceTools } from '../workspace-tools.mjs';
function fixture(t){mkdirSync('.private/tests',{recursive:true});const base=mkdtempSync(resolve('.private/tests/html-preview-'));t.after(()=>rmSync(base,{recursive:true,force:true}));const root=join(base,'project');mkdirSync(join(root,'pages'),{recursive:true});mkdirSync(join(root,'assets'));writeFileSync(join(root,'pages','review page.html'),'<h1>审核页</h1><script src="../assets/app.js"></script>');writeFileSync(join(root,'assets','app.js'),'document.title="READY"');writeFileSync(join(root,'assets','style.css'),'body { color: green; }');writeFileSync(join(root,'.env'),'SECRET');writeFileSync(join(base,'secret.txt'),'SECRET');symlinkSync(join(base,'secret.txt'),join(root,'escape.txt'));return{root,path:join(root,'pages','review page.html')};}
test('HTML service loads relative and root assets, requires a token and blocks private files',async t=>{
  const f=fixture(t),server=new ArtifactWebServer();t.after(()=>server.close());const url=await server.start(f);
  const html=await fetch(url);assert.equal(html.status,200);assert.match(html.headers.get('content-type'),/text\/html/);assert.match(await html.text(),/审核页/);
  const cookie=html.headers.get('set-cookie').split(';')[0];
  assert.equal((await fetch(new URL('../assets/app.js',url))).status,200);
  assert.equal((await fetch(server.origin+'/assets/style.css')).status,403);
  const css=await fetch(server.origin+'/assets/style.css',{headers:{cookie}});assert.equal(css.status,200);assert.match(await css.text(),/green/);
  assert.equal((await fetch(url,{method:'HEAD'})).headers.get('content-length'),String(readFileSync(f.path).length));
  for(const path of ['/../secret.txt','/%2eenv','/escape.txt','/'+server.prefix.slice(1)+'%2e%2e%2f%2e%2e%2fsecret.txt']){
    const response=await fetch(server.origin+path,{headers:{cookie}});assert.notEqual(response.status,200);assert(!(await response.text()).includes('SECRET'));
  }
  assert.equal((await fetch(url,{method:'POST',body:'not allowed'})).status,405);
  server.close();await assert.rejects(fetch(url));
});
test('file preview survives proxy startup, transports bytes, and closing or switching releases the port',async t=>{
  const f=fixture(t),tunnel=new PreviewTunnel();t.after(()=>tunnel.close());const {url}=await tunnel.openFile(f);
  const session=tunnel.open({url}),target=new URL(url);const {socketId}=await tunnel.connect({previewId:session.previewId,host:target.hostname,port:Number(target.port)});
  await tunnel.write({previewId:session.previewId,socketId,data:Buffer.from(`GET ${target.pathname} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n`).toString('base64')});
  let response='';for(let i=0;i<30;i++){const part=await tunnel.read({previewId:session.previewId,socketId});response+=Buffer.from(part.data,'base64').toString();if(part.done)break;}
  assert.match(response,/200 OK/);assert.match(response,/审核页/);
  tunnel.open({url:'https://example.com/'});await assert.rejects(fetch(url));
  const pending=tunnel.openFile(f);tunnel.close();await assert.rejects(pending,/取消/);assert.equal(tunnel.fileServer,null);
});
test('workspace HTML uses existing project, traversal, symlink and fingerprint authorization',async t=>{
  const f=fixture(t),workspace=new WorkspaceTools({listProjects:async()=>({projects:[{projectId:'qa',path:f.root}]}),storedState:async()=>({cwd:f.root})});
  assert.deepEqual(await workspace.previewFile({projectId:'qa',path:'pages/review page.html'}),f);
  for(const name of ['../secret.txt','escape.txt','.private/page.html','assets/app.js'])await assert.rejects(workspace.previewFile({projectId:'qa',path:name}));
  await assert.rejects(workspace.previewFile({projectId:'unknown',path:'pages/review page.html'}));
  await assert.rejects(workspace.previewFile({projectId:'qa',path:'pages/review page.html',fingerprint:'stale'}),/更新/);
});
