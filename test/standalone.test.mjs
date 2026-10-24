import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { EventEmitter, once } from 'node:events';
import { AppServerClient, findCodexCLI } from '../app-server-client.mjs';
import { StandaloneAdapter } from '../standalone-adapter.mjs';
import { readFileSync } from 'node:fs';

test('stdio connects without a caller chat, handles partial frames, paging, errors, timeouts and notifications', async t => {
  const client = new AppServerClient({ binary:process.execPath, env:{...process.env,CODEX_THREAD_ID:'private-thread',CODEX_APP_TOOLS_PIPE_PATH:'private-pipe'}, timeoutMs:1000, spawnProcess:(binary,args,opts)=>spawn(binary,['test/support/app-server-fixture.mjs'],opts) });
  t.after(()=>client.close()); await client.connect();
  assert.deepEqual(await client.request('test/environment'),{CODEX_THREAD_ID:null,CODEX_DESKTOP_CONTEXT_THREAD_ID:null,CODEX_APP_TOOLS_PIPE_PATH:null});
  assert.deepEqual(await client.pages('test/pages'),[1,2]);
  await assert.rejects(client.pages('test/repeat'),/游标重复/);
  await assert.rejects(client.request('test/error'),error=>error.code===123&&error.message==='test failure');
  await assert.rejects(client.request('test/timeout',{},20),/超时/);
  const event=once(client,'notification');await client.request('test/event');assert.equal((await event)[0].method,'item/started');
  assert.equal(client.pending.size,0);
});

test('Codex discovery uses installed bundled executables and does not require tool-pipe environment', () => {
  const exe='/Applications/Codex.app/Contents/Resources/cua_node/bin/node',cli='/Applications/Codex.app/Contents/Resources/codex-cli/bin/codex';
  assert.equal(findCodexCLI({env:{},executable:exe,platform:'darwin',exists:p=>p===cli}),cli);
  const win='C:\\Codex\\resources\\codex-cli\\bin\\codex.exe';
  assert.equal(findCodexCLI({env:{},executable:'C:\\Codex\\resources\\cua_node\\bin\\node.exe',platform:'win32',exists:p=>p===win}),win);
  assert.throws(()=>findCodexCLI({env:{},exists:()=>false}),/未找到/);
});

class Server extends EventEmitter {
  calls=[]; replies=[];
  async request(method,params) {
    this.calls.push({method,params});
    if(method==='thread/read')return{thread:{id:'qa',model:'test-model',name:'QA',historyMode:'paginated',status:{type:'idle'},turns:[]}};
    throw Error('unexpected '+method);
  }
  async pages(method) {
    this.calls.push({method});
    if(method==='thread/turns/list')return[{id:'old',status:'completed',items:[{id:'u',type:'userMessage',content:[{type:'text',text:'question'}]},{id:'tool',type:'commandExecution',command:'pwd',aggregatedOutput:'/qa',exitCode:0}]},{id:'new',status:'completed',items:[{id:'a',type:'agentMessage',text:'answer'}]}];
    return[];
  }
  send(value){this.replies.push(value)}close(){}
}
test('standalone history includes all stored turns and tool output without resuming or changing a thread', async t => {
  const server=new Server(),a=new StandaloneAdapter({server,catalog:false});t.after(()=>a.close());a.ready=true;
  const read=await a.readThread({threadId:'qa'});
  assert.equal(read.historyComplete,true);assert.equal(read.messages.length,3);assert.equal(read.messages[1].role,'tool');assert.equal(read.messages[1].text,'/qa');
  assert.deepEqual(server.calls.map(c=>c.method),['thread/read','thread/turns/list']);
});
test('a desktop delivery error never falls back to a second app-server send', async t => {
  const server=new Server(),a=new StandaloneAdapter({server,catalog:false});t.after(()=>a.close());a.ready=true;a.ipcAvailable=true;
  a.owner=async()=> 'desktop-owner';
  a.request=async()=>{throw new Error('ambiguous desktop delivery')};
  // The read-only snapshot is supplied by a desktop owner for this regression.
  const original=Object.getPrototypeOf(StandaloneAdapter.prototype).readThread;
  Object.getPrototypeOf(StandaloneAdapter.prototype).readThread=async()=>({thread:{id:'qa',activeTurnId:null,cwd:'/qa'},source:'desktop-live'});
  try{await assert.rejects(a.sendMessage({threadId:'qa',text:'only once'}),e=>e.uncertain===true)}finally{Object.getPrototypeOf(StandaloneAdapter.prototype).readThread=original}
  assert.equal(server.calls.length,0);
});
test('approval decisions enforce conversation identity, and disconnect forgets owned sessions', async t => {
  const server=new Server(),a=new StandaloneAdapter({server,catalog:false});t.after(()=>a.close());a.ready=true;
  const request={id:3,method:'item/commandExecution/requestApproval',params:{threadId:'qa'}};
  a.onServerRequest(request);
  await assert.rejects(a.call('approval.respond',{threadId:'other',requestId:3,decision:'accept'}),/其他对话/);
  await assert.rejects(a.call('approval.respond',{threadId:'qa',requestId:3,decision:'invalid'}),/无效/);
  await a.call('approval.respond',{threadId:'qa',requestId:3,decision:'decline'});
  assert.deepEqual(server.replies,[{id:3,result:{decision:'decline'}}]);
  a.ownedThreads.add('qa');server.emit('disconnect',Error('closed'));assert.equal(a.ownedThreads.size,0);assert.equal(a.ready,false);
});
test('connector UI has no dangling controls or manually entered caller chat ID', () => {
  const html=readFileSync(new URL('../connector.html',import.meta.url),'utf8');
  const ids=new Set([...html.matchAll(/id="([^"]+)"/g)].map(m=>m[1]));
  for(const m of html.matchAll(/\$\('([^']+)'\)/g)){const id=m[1].match(/^#([\w-]+)/)?.[1];if(id)assert(ids.has(id),'Missing control: '+m[1]);}
  assert(!ids.has('context'));assert(ids.has('connect'));assert(ids.has('qr'));
});

test('archive, restore and delete change the backend and verify the fresh list; active tasks are protected',async t=>{
  class Managed extends Server{
    archived=false;deleted=false;active=false;
    async request(method,params){this.calls.push({method,params});
      if(method==='thread/read')return{thread:{id:'qa',status:{type:this.active?'active':'idle'},turns:[]}};
      if(method==='thread/archive')this.archived=true;
      else if(method==='thread/unarchive')this.archived=false;
      else if(method==='thread/delete')this.deleted=true;
      else throw Error(method);return{};
    }
    async pages(method,params){assert.equal(method,'thread/list');return !this.deleted&&params.archived===this.archived?[{id:'qa'}]:[]}
  }
  const server=new Managed(),a=new StandaloneAdapter({server,catalog:false});a.ready=true;t.after(()=>a.close());
  server.active=true;await assert.rejects(a.call('thread.delete',{threadId:'qa'}),/停止/);assert(!server.deleted);server.active=false;
  assert.equal((await a.call('thread.archive',{threadId:'qa'})).verified,true);assert(server.archived);
  assert.equal((await a.call('thread.unarchive',{threadId:'qa'})).verified,true);assert(!server.archived);
  assert.equal((await a.call('thread.delete',{threadId:'qa'})).verified,true);assert(server.deleted);
  await assert.rejects(a.call('thread.archive',{threadId:'qa',hostId:'other'}),/本机/);
});
