import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { EventEmitter, once } from 'node:events';
import { AppServerClient, findCodexCLI } from '../app-server-client.mjs';
import { StandaloneAdapter } from '../standalone-adapter.mjs';
import { readFileSync } from 'node:fs';

// Unit fixtures must never discover or subscribe to the user's real desktop.
const fixtureAdapter = options => new StandaloneAdapter({ ipcPath: '/codex-pocket-tests/no-ipc.sock', runtimeCatalog: false, ...options });

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
  const child = client.child; await client.closeAndWait();
  assert(child.exitCode !== null || child.signalCode !== null);
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

test('unopened desktop chats get live runtime summaries instead of a separate server notLoaded status', async t => {
  const server = new Server();
  server.pages = async method => { server.calls.push({method}); return [{id:'qa', status:{type:'notLoaded'}, updatedAt:1}]; };
  let status='active'; const calls=[];
  const reader={async connect(){calls.push('connect')},async tool(name,args){calls.push({name,args});return {threads:[{id:'qa',hostId:'local',kind:'codex',status}],pinnedThreads:[]}},close(){}};
  const a=fixtureAdapter({server,runtimeReader:reader});t.after(()=>a.close());
  a.localCatalog=async()=>({threads:[{id:'qa',status:'unknown',projectId:'project',membershipKnown:true}],localComplete:true});
  assert.equal((await a.listThreads()).threads[0].status,'active');
  assert.equal(a.snapshots.size,0);assert.equal(a.subscriptions.size,0);
  status='waitingForApproval';assert.equal((await a.listThreads()).threads[0].status,'waitingForApproval');
  status='idle';assert.equal((await a.listThreads()).threads[0].status,'idle');
  assert(server.calls.every(c=>c.method==='thread/list'),'summary polling never reads or resumes histories');
  assert.equal(calls.filter(c=>c.name==='list_threads').length,3);
  assert.equal((await a.listThreads()).threads[0].projectId,'project');
});

test('Pocket-owned runtime comes from its own process and replaces active status on completion', async t => {
  const server=new Server();server.pages=async()=>[{id:'qa',status:{type:'notLoaded'},updatedAt:1}];
  let status='active';const requests=[];
  const own={async request(method,args){requests.push({method,args});return {thread:{status:{type:status}}}},close(){}};
  const a=fixtureAdapter({server,catalog:false});t.after(()=>a.close());a.ownedThreads.add('qa');a.threadServers.set('qa',own);
  assert.equal((await a.listThreads()).threads[0].status,'active');
  status='idle';assert.equal((await a.listThreads()).threads[0].status,'idle');
  assert(requests.every(c=>c.method==='thread/read'&&c.args.includeTurns===false));
});

test('missing desktop runtime retains unknown evidence and never imports another host or ChatGPT status', async t => {
  const server=new Server();server.pages=async()=>[{id:'qa',status:{type:'notLoaded'},updatedAt:1}];
  let unavailable=false;
  const reader={async connect(){if(unavailable)throw Error('offline')},async tool(){return {threads:[{id:'qa',kind:'chatgpt',status:'active'},{id:'qa',hostId:'remote',status:'active'}]}},close(){}};
  const a=fixtureAdapter({server,catalog:false,runtimeReader:reader});t.after(()=>a.close());
  assert.equal((await a.listThreads()).threads[0].status,'notLoaded');
  unavailable=true;assert.equal((await a.listThreads()).threads[0].status,'notLoaded');
});

function publicCreateFixture(t, options = {}) {
  class PublicServer extends Server {
    async closeAndWait() { if (options.stopError) throw options.stopError; this.close(); }
    async request(method, params) {
      this.calls.push({ method, params });
      if (method === 'thread/start') {
        if (options.startError) throw options.startError;
        return { thread: { id: 'phone-thread' }, model: 'test-model', reasoningEffort: 'high' };
      }
      if (method === 'thread/name/set') return {};
      if (method === 'turn/start') {
        if (options.turnError) throw options.turnError;
        return { turn: { id: 'initial-turn', status: 'inProgress' } };
      }
      if (method === 'thread/read') return { thread: { id: params.threadId, status: { type: options.active ? 'active' : 'idle' }, turns: [] } };
      if (method === 'thread/unsubscribe') { if (options.unsubscribeError) throw options.unsubscribeError; return { status: 'unsubscribed' }; }
      throw Error('unexpected ' + method);
    }
  }
  const server = new PublicServer(), messages = [];
  const a = fixtureAdapter({ server, catalog: false, projectlessDirectory: new URL('../.private/tests/creation/Documents/Codex', import.meta.url).pathname });
  a.ready = true; a.ipcAvailable = true;
  a.ensureDesktopIpc = async () => { if (options.ipcError) throw options.ipcError; };
  a.ipc = { write: value => messages.push(value), close() {} };
  a.listProjects = async () => ({ projects: [{ projectId: 'legacy', canonicalProjectId: 'canonical', path: '/project-root' }] });
  t.after(() => a.close()); return { a, server, messages };
}

test('public App Server creation uses a desktop-recognized independent workspace and broadcasts its persisted identity', async t => {
  const { a, server, messages } = publicCreateFixture(t);
  const result = await a.createThread({ text: 'phone prompt', model: 'test-model', thinking: 'high' });
  assert.equal(result.threadId, 'phone-thread'); assert.equal(result.hostId, 'local');
  const [start, name, turn] = server.calls;
  assert.equal(start.method, 'thread/start');
  assert.match(start.params.cwd, /Documents\/Codex\/\d{4}-\d{2}-\d{2}\/pocket-[a-f0-9-]+$/);
  assert.equal(start.params.threadSource, 'user'); assert.equal(start.params.model, 'test-model');
  assert.deepEqual(name, { method: 'thread/name/set', params: { threadId: 'phone-thread', name: 'phone prompt' } });
  assert.equal(turn.params.threadId, 'phone-thread'); assert.equal(turn.params.effort, 'high');
  assert(a.ownedThreads.has(result.threadId)); assert.equal(a.appTools, null);
  assert.equal(messages[0].method, 'thread-unarchived'); assert.equal(messages[0].params.conversationId, result.threadId);
});

test('public project creation retains the desktop project root and canonical assignment', async t => {
  const { a, server } = publicCreateFixture(t);
  await a.createThread({ prompt: 'project prompt', projectId: 'legacy', title: 'QA' });
  assert.deepEqual(server.calls[0].params, { cwd: '/project-root', projectId: 'canonical', threadSource: 'user' });
  assert.equal(server.calls[1].params.name, 'QA');
});

test('unavailable desktop or invalid project is explicitly rejected before creating any thread', async t => {
  const { a, server } = publicCreateFixture(t, { ipcError: Error('closed') });
  await assert.rejects(a.createThread({ text: 'offline' }), e => e.notSubmitted === true && !e.uncertain);
  await assert.rejects(a.createThread({ text: 'invalid project', projectId: 'missing' }), e => e.notSubmitted === true);
  assert.equal(server.calls.length, 0);
});

test('an ambiguous public start or first turn never creates another thread or sends again', async t => {
  const first = publicCreateFixture(t, { startError: Error('start delivery unconfirmed') });
  await assert.rejects(first.a.createThread({ text: 'once' }), e => e.uncertain === true);
  assert.equal(first.server.calls.length, 1);
  const turn = publicCreateFixture(t, { turnError: Error('turn delivery unconfirmed') });
  await assert.rejects(turn.a.createThread({ text: 'once' }), e => e.uncertain === true);
  assert.equal(turn.server.calls.filter(c => c.method === 'thread/start').length, 1);
  assert.equal(turn.server.calls.filter(c => c.method === 'turn/start').length, 1);
});

test('following up uses the same persisted thread and completion refreshes the desktop list', async t => {
  const { a, server, messages } = publicCreateFixture(t);
  const { threadId } = await a.createThread({ text: 'first' });
  await a.sendMessage({ threadId, text: 'second' });
  assert.deepEqual(server.calls.filter(c => c.method === 'turn/start').map(c => c.params.threadId), [threadId, threadId]);
  a.onServerEvent({ method: 'turn/completed', params: { threadId } });
  await new Promise(resolve => setTimeout(resolve, 350));
  assert.equal(messages.length, 2);
  assert(!a.ownedThreads.has(threadId));
  a.close(); assert.equal(a.catalogRefreshTimers.size, 0);
});

test('handoff retains an active thread and only clears ownership after confirmed process exit', async t => {
  const options = { active: true };
  const { a, server } = publicCreateFixture(t, options);
  await a.createThread({ text: 'first' });
  assert.equal(await a.releaseThread('phone-thread'), false);
  assert(!server.calls.some(c => c.method === 'thread/unsubscribe'));
  options.active = false; options.stopError = Error('unconfirmed release');
  await assert.rejects(a.releaseThread('phone-thread'), /unconfirmed/);
  assert(a.ownedThreads.has('phone-thread'));
  delete options.stopError;
  assert.equal(await a.releaseThread('phone-thread'), true);
  assert(!a.ownedThreads.has('phone-thread'));
});

test('handoff waits for an in-flight send and cannot unsubscribe its new active turn', async t => {
  const options = { active: false };
  const { a, server } = publicCreateFixture(t, options);
  await a.createThread({ text: 'first' });
  let resume;
  const gate = new Promise(resolve => { resume = resolve; });
  const writing = a.withThread('phone-thread', async () => { await gate; options.active = true; });
  const handoff = a.releaseThread('phone-thread');
  resume(); await writing;
  assert.equal(await handoff, false);
  assert(!server.calls.some(c => c.method === 'thread/unsubscribe'));
});

test('standalone history includes all stored turns and tool output without resuming or changing a thread', async t => {
  const server=new Server(),a=fixtureAdapter({server,catalog:false});t.after(()=>a.close());a.ready=true;
  const read=await a.readThread({threadId:'qa'});
  assert.equal(read.historyComplete,true);assert.equal(read.messages.length,3);assert.equal(read.messages[1].role,'tool');assert.equal(read.messages[1].text,'/qa');
  assert.deepEqual(server.calls.map(c=>c.method),['thread/read','thread/turns/list']);
});
test('a desktop delivery error never falls back to a second app-server send', async t => {
  const server=new Server(),a=fixtureAdapter({server,catalog:false});t.after(()=>a.close());a.ready=true;a.ipcAvailable=true;
  a.owner=async()=> 'desktop-owner';
  a.request=async()=>{throw new Error('ambiguous desktop delivery')};
  // The read-only snapshot is supplied by a desktop owner for this regression.
  const original=Object.getPrototypeOf(StandaloneAdapter.prototype).readThread;
  Object.getPrototypeOf(StandaloneAdapter.prototype).readThread=async()=>({thread:{id:'qa',activeTurnId:null,cwd:'/qa'},source:'desktop-live'});
  try{await assert.rejects(a.sendMessage({threadId:'qa',text:'only once'}),e=>e.uncertain===true)}finally{Object.getPrototypeOf(StandaloneAdapter.prototype).readThread=original}
  assert(!server.calls.some(c=>c.method.startsWith("turn/")||c.method=== "thread/resume"));
});

function desktopSendFixture(t, options = {}) {
  const server = new Server(), calls = [];
  server.request = async (method, params) => {
    server.calls.push({ method, params });
    if (method === 'thread/read') return { thread: { id: 'qa', status: { type: 'idle' }, turns: [] } };
    if (method === 'thread/resume') throw Error('thread qa already has an active writer');
    throw Error('unexpected ' + method);
  };
  const a = fixtureAdapter({ server, catalog: false, timeoutMs: 15 });
  a.ready = true; a.ipcAvailable = false;
  a.ensureDesktopIpc = async () => { calls.push('connect'); a.ipcAvailable = true; };
  let discovery = 0;
  a.request = async (method, params) => {
    calls.push(method);
    if (method === 'thread-owner-discovery') {
      if (++discovery <= (options.missedDiscovery ?? 0)) throw Error('discovery timed out');
      return { handledByClientId: 'desktop-owner' };
    }
    if (method === 'thread-follower-read-model-settings') return { result: { settings: { model: 'test-model', reasoningEffort: 'high' } } };
    if (method === 'thread-follower-update-thread-settings') return { result: { applied: true } };
    if (options.deliveryError) throw options.deliveryError;
    return { result: { sent: true } };
  };
  a.broadcast = method => {
    calls.push(method);
    if (!options.missingSnapshot) queueMicrotask(() => a.emit('event', { type: 'thread.updated', threadId: 'qa', data: {
      source: 'desktop-live', historyComplete: true,
      thread: { id: 'qa', hostId: 'local', cwd: '/qa', activeTurnId: options.active ? 'active-turn' : null, settings: { model: 'test-model', reasoningEffort: 'high' } },
    } }));
  };
  a.models = [{ id: 'test-model', reasoningEfforts: ['high'] }];
  t.after(() => a.close());
  return { a, server, calls };
}

test('sending after desktop IPC disconnect reconnects and follows the existing desktop writer', async t => {
  const { a, server, calls } = desktopSendFixture(t);
  assert.deepEqual(await a.sendMessage({ threadId: 'qa', text: 'one message', model: 'test-model', thinking: 'high' }), { sent: true });
  assert(calls.includes('connect')); assert(calls.includes('thread-follower-read-model-settings'));
  assert(!calls.includes('thread-follower-update-thread-settings'));
  assert.equal(calls.filter(c => c === 'thread-follower-start-turn').length, 1);
  assert(server.calls.every(call => call.method === 'thread/queue/list')); assert.equal(a.ownedThreads.size, 0);
});

test('a writer acquired during discovery is followed after a refused resume without duplicate submission', async t => {
  const { a, server, calls } = desktopSendFixture(t, { missedDiscovery: 1, active: true });
  assert.deepEqual(await a.sendMessage({ threadId: 'qa', text: 'steer once' }), { sent: true });
  assert.equal(server.calls.filter(c => c.method === 'thread/resume').length, 1);
  assert(!server.calls.some(c => c.method.startsWith('turn/')));
  assert.equal(calls.filter(c => c === 'thread-follower-steer-turn').length, 1);
  assert(!calls.includes('thread-follower-start-turn'));
});

test('an inaccessible desktop writer rejects before sending and does not leave an uncertain operation', async t => {
  const { a, server, calls } = desktopSendFixture(t, { missedDiscovery: Infinity });
  await assert.rejects(a.sendMessage({ threadId: 'qa', text: 'retain draft' }), e => e.notSubmitted === true && e.uncertain === false && /消息尚未发送/.test(e.message));
  assert(!server.calls.some(c => c.method.startsWith('turn/')));
  assert(!calls.some(c => c === 'thread-follower-start-turn' || c === 'thread-follower-steer-turn'));
});

test('a missing desktop live snapshot cannot trigger a turn from stale stored history', async t => {
  const { a, server, calls } = desktopSendFixture(t, { missingSnapshot: true });
  await assert.rejects(a.sendMessage({ threadId: 'qa', text: 'retain draft' }), e => e.notSubmitted === true && e.uncertain === false);
  assert(!server.calls.some(c => c.method === 'thread/resume' || c.method.startsWith('turn/')));
  assert(!calls.includes('thread-follower-start-turn'));
});

test('only an explicit writer rejection is certain; other desktop delivery failures remain uncertain', async t => {
  const writer = desktopSendFixture(t, { deliveryError: Object.assign(Error('thread qa already has an active writer'), { rejected: true }) });
  await assert.rejects(writer.a.sendMessage({ threadId: 'qa', text: 'once' }), e => e.notSubmitted === true && e.uncertain === false);
  const ambiguous = desktopSendFixture(t, { deliveryError: Object.assign(Error('handler failed after submission'), { rejected: true }) });
  await assert.rejects(ambiguous.a.sendMessage({ threadId: 'qa', text: 'once' }), e => e.uncertain === true && !e.notSubmitted);
  assert.equal(ambiguous.calls.filter(c => c === 'thread-follower-start-turn').length, 1);
  assert(!ambiguous.server.calls.some(c=>c.method.startsWith("turn/")||c.method==="thread/resume"));
});
test('approval decisions enforce conversation identity, and disconnect forgets owned sessions', async t => {
  const server=new Server(),a=fixtureAdapter({server,catalog:false});t.after(()=>a.close());a.ready=true;
  const request={id:3,method:'item/commandExecution/requestApproval',params:{threadId:'qa'}};
  a.onServerRequest(request);
  await assert.rejects(a.call('approval.respond',{threadId:'other',requestId:3,decision:'accept'}),/其他对话/);
  await assert.rejects(a.call('approval.respond',{threadId:'qa',requestId:3,decision:'invalid'}),/无效/);
  await a.call('approval.respond',{threadId:'qa',requestId:3,decision:'decline'});
  assert.deepEqual(server.replies,[{id:3,result:{decision:'decline'}}]);
  a.ownedThreads.add('qa');server.emit('disconnect',Error('closed'));assert.equal(a.ownedThreads.size,0);assert.equal(a.ready,false);
});
test('worker handoff closes only the idle chat and routes equal approval IDs to their own processes', async t => {
  const main = new Server(), one = new Server(), two = new Server();
  let oneClosed = 0, twoClosed = 0;
  one.closeAndWait = async () => { oneClosed++; }; two.closeAndWait = async () => { twoClosed++; };
  const a = fixtureAdapter({ server: main, catalog: false }); a.ready = true; t.after(() => a.close());
  for (const [id, client] of [['one', one], ['two', two]]) {
    a.threadServers.set(id, client); a.ownedThreads.add(id);
    a.onServerRequest({ id: 3, method: 'item/commandExecution/requestApproval', params: { threadId: id } }, client);
  }
  await a.call('approval.respond', { threadId: 'one', requestId: 3, decision: 'decline' });
  assert.equal(one.replies.length, 1); assert.equal(two.replies.length, 0); assert.equal(main.replies.length, 0);
  assert.equal(await a.releaseThread('one'), true); assert.equal(oneClosed, 1); assert.equal(twoClosed, 0);
  assert(a.ownedThreads.has('two')); assert.equal(await a.releaseThread('two'), false);
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
      if(method==='thread/read'){if(this.deleted)throw Error('thread not loaded');return{thread:{id:'qa',status:{type:this.active?'active':'idle'},turns:[]}};}
      if(method==='thread/resume')return{thread:{id:'qa'}};
      if(method==='thread/archive')this.archived=true;
      else if(method==='thread/unarchive')this.archived=false;
      else if(method==='thread/delete')this.deleted=true;
      else throw Error(method);return{};
    }
    async pages(method,params){assert.equal(method,'thread/list');return !this.deleted&&params.archived===this.archived?[{id:'qa'}]:[]}
  }
  const server=new Managed(),a=fixtureAdapter({server,catalog:false}),notifications=[];a.ready=true;t.after(()=>a.close());
  a.ipcAvailable=true;a.ipc={write:value=>notifications.push(value),close(){}};
  server.active=true;await assert.rejects(a.call('thread.delete',{threadId:'qa'}),/停止/);assert(!server.deleted);server.active=false;
  assert.equal((await a.call('thread.archive',{threadId:'qa'})).verified,true);assert(server.archived);
  assert.equal((await a.call('thread.unarchive',{threadId:'qa'})).verified,true);assert(!server.archived);
  assert.equal((await a.call('thread.delete',{threadId:'qa'})).verified,true);assert(server.deleted);
  assert.deepEqual(notifications.map(n=>[n.method,n.version,n.params.conversationId]),[['thread-archived',2,'qa'],['thread-unarchived',1,'qa'],['thread-archived',2,'qa']]);
  assert.equal((await a.call('thread.delete',{threadId:'qa'})).alreadyDeleted,true);
  assert.equal(notifications.at(-1).method,'thread-archived');
  await assert.rejects(a.call('thread.archive',{threadId:'qa',hostId:'other'}),/本机/);
});

test('receipt reconciliation reads exact client IDs and never accepts equal text or rejected steering',async t=>{
  const {a,server}=desktopSendFixture(t);const client='request-id';
  a.snapshots.set('local:qa',{state:{turns:[{items:[{id:'placeholder',type:'steeringUserMessage',status:'accepted',serverUserMessageId:'server-id',clientUserMessageId:client}]}]}});
  assert.equal((await a.confirmMessage({threadId:'qa',clientUserMessageId:client})).messageId,'server-id');
  a.snapshots.clear();server.request=async(method)=>{assert.equal(method,'thread/turns/list');return{data:[{items:[{id:'canonical',type:'userMessage',clientId:client,content:[{type:'text',text:'one prompt'}]}]}]}};
  assert.equal((await a.confirmMessage({threadId:'qa',clientUserMessageId:client})).messageId,'canonical');
  assert.equal(await a.confirmMessage({threadId:'qa',clientUserMessageId:'other'}),null);
});
test('text and uploaded images enter one public first turn with the same client request ID',async t=>{
  const {a,server}=publicCreateFixture(t);
  await a.createThread({text:'describe this',imagePaths:['/phone/one.jpg','/phone/two.png'],clientUserMessageId:'client'});
  const input=server.calls.find(c=>c.method==='turn/start').params;
  assert.equal(input.clientUserMessageId,'client');assert.deepEqual(input.input.map(i=>i.type),['text','localImage','localImage']);
});

test('desktop discovery advertises only local Pocket-owned chats and supported protocol versions', async t => {
  const { a, messages } = publicCreateFixture(t, { active: true });
  await a.createThread({ text: 'first' });
  const ask = (method, version, params = {}, hostId) => {
    a.handleIpc({ type: 'client-discovery-request', requestId: 'discover', request: { method, version, params: { conversationId: 'phone-thread', hostId: 'local', ...params }, ...(hostId ? { hostId } : {}) } });
    return messages.at(-1).response.canHandle;
  };
  assert(ask('thread-owner-discovery', 1));
  assert(ask('thread-follower-start-turn', 2));
  assert(!ask('thread-follower-start-turn', 99));
  assert(!ask('thread-owner-discovery', 1, { conversationId: 'someone-else' }));
  assert(!ask('thread-owner-discovery', 1, {}, 'remote'));
  assert(!ask('ide-context', 0));
});

test('desktop follows an active mobile writer, receives complete tool history and keeps the idle input usable', async t => {
  const opts = { active: true }, { a, server, messages } = publicCreateFixture(t, opts);
  await a.createThread({ text: 'first' });
  a.states.set('phone-thread', { id: 'phone-thread', latestThreadSettings: { model: 'test-model', effort: 'high' } });
  const original = server.request.bind(server);
  server.request = async (method, params) => method === 'thread/read' ? { thread: { id: params.threadId, status: { type: opts.active ? 'active' : 'idle' }, turns: [{ id: 't', status: opts.active ? 'inProgress' : 'completed', items: [{ id: 'u', type: 'userMessage', content: [{type:'text',text:'first'}] }, { id: 'cmd', type:'commandExecution',command:'pwd',aggregatedOutput:'/project',status:'completed',exitCode:0 }] }] } } : original(method,params);
  a.handleIpc({ type: 'broadcast', method: 'thread-stream-following-changed', version: 1, sourceClientId: 'desktop-window', params: { hostId: 'local', conversationId: 'phone-thread', following: true } });
  await new Promise(setImmediate);
  const snapshot = messages.find(m => m.method === 'thread-stream-state-changed');
  assert.deepEqual(snapshot.targetClientIds, ['desktop-window']);
  assert.equal(snapshot.version, 11);
  const state = snapshot.params.change.conversationState;
  assert.equal(state.resumeState, 'resumed'); assert.equal(state.latestCollaborationMode.settings.model, 'test-model');
  assert.equal(state.turns[0].turnId, 't'); assert.equal(state.turns[0].params.input[0].text, 'first');
  assert.equal(state.turns[0].items[1].aggregatedOutput, '/project');
  opts.active = false;
  assert.equal(await a.releaseThread('phone-thread'), false, 'desktop follower must retain its usable owner');
  a.handleIpc({ type: 'broadcast', method: 'thread-stream-following-changed', version: 1, sourceClientId: 'desktop-window', params: { hostId: 'local', conversationId: 'phone-thread', following: false } });
  assert.equal(await a.releaseThread('phone-thread'), true);
});

test('a desktop user turn reaches the existing worker once and app-tool deliveries stay blocked', async t => {
  const { a, server, messages } = publicCreateFixture(t, { active: true });
  await a.createThread({ text: 'first' });
  const base = { type:'request', method:'thread-follower-start-turn', version:2, sourceClientId:'desktop-window' };
  a.handleIpc({ ...base, requestId:'user', params:{ conversationId:'phone-thread',turnStart:{request:{threadId:'phone-thread',input:[{type:'text',text:'desktop input'}]}}} });
  await new Promise(setImmediate);
  assert.equal(messages.find(m=>m.requestId==='user').resultType,'success');
  assert.equal(server.calls.filter(c=>c.method==='turn/start'&&c.params.input[0].text==='desktop input').length,1);
  a.handleIpc({ ...base, requestId:'untrusted',params:{conversationId:'phone-thread',turnStart:{request:{input:[{type:'text',text:'must not send'}]},context:{responseItems:[{type:'function_call_output'}]}}} });
  await new Promise(setImmediate);
  assert.equal(messages.find(m=>m.requestId==='untrusted').resultType,'error');
  assert(!server.calls.some(c=>c.params?.input?.[0]?.text==='must not send'));
});

test('idle watchdog survives more than eight active checks and a missing completion notification', async t => {
  const options={active:true}, {a,server}=publicCreateFixture(t,options);
  a.options.releaseMaxDelayMs=10;
  await a.createThread({text:'first'});
  await new Promise(r=>setTimeout(r,150));
  assert(a.ownedThreads.has('phone-thread')); assert(server.calls.filter(c=>c.method==='thread/read').length>8);
  options.active=false;
  await new Promise(r=>setTimeout(r,50));
  assert(!a.ownedThreads.has('phone-thread'));
});

test('authoritative idle runtime releases a stale historical inProgress turn but never a pending approval', async t => {
  const { a }=publicCreateFixture(t); await a.createThread({text:'first'});
  a.storedState=async()=>({status:{type:'idle'},turns:[{id:'old',status:'inProgress'}]});
  a.approvals.set('phone-thread:1',{params:{threadId:'phone-thread'}});
  assert.equal(await a.releaseThread('phone-thread'),false);
  a.approvals.clear(); assert.equal(await a.releaseThread('phone-thread'),true);
});

test('ordinary file references use native text input on creation and owned followups, with no unsupported input type',async t=>{
 const {a,server}=publicCreateFixture(t);const files=[{name:'report.txt',path:'/controlled-uploads/id/report.txt'}];await a.createThread({text:'review',files});await a.sendMessage({threadId:'phone-thread',text:'',files});
 const turns=server.calls.filter(c=>c.method==='turn/start');assert.equal(turns.length,2);for(const turn of turns){assert.equal(turn.params.input[0].type,'text');assert.match(turn.params.input[0].text,/## report\.txt: \/controlled-uploads\/id\/report\.txt/);assert(!turn.params.input.some(p=>p.type==='localFile'));}
});

test('standalone extended approvals clear on resolution and respond on the owning process',async t=>{
 const server=new Server(),a=fixtureAdapter({server,catalog:false});t.after(()=>a.close());a.ready=true;
 a.onServerRequest({id:9,method:'item/permissions/requestApproval',params:{threadId:'qa',permissions:{network:{enabled:true}}}});await a.call('approval.respond',{threadId:'qa',requestId:9,decision:'accept'});assert.deepEqual(server.replies[0],{id:9,result:{permissions:{network:{enabled:true}},scope:'turn'}});assert.equal(a.approvals.size,0);
 a.onServerRequest({id:10,method:'mcpServer/elicitation/request',params:{threadId:'qa',mode:'url'}});a.onServerEvent({method:'serverRequest/resolved',params:{threadId:'qa',requestId:10}});assert.equal(a.approvals.size,0);assert.equal(server.replies.length,1);
});

test('a writable but stale desktop connection is rebuilt after writer refusal and sends exactly once', async t => {
  const { a, server, calls } = desktopSendFixture(t);
  let connections = 0, closes = 0;
  a.ipcAvailable = false;
  a.options.ownerRetryDelayMs = 0;
  a.ipc = { pending: new Map(), write() {}, close() { closes++; } };
  a.ensureDesktopIpc = async () => { if (!a.ipcAvailable) connections++; a.ipcAvailable = true; };
  const request = a.request;
  a.request = async (method, ...args) => {
    if (method === 'thread-owner-discovery' && connections < 2) throw Error('no-client-found');
    return request(method, ...args);
  };
  assert.deepEqual(await a.sendMessage({ threadId: 'qa', text: 'once', clientUserMessageId: 'stable-id' }), { sent: true });
  assert.equal(connections, 2); assert.equal(closes, 1);
  assert.equal(calls.filter(c => c === 'thread-follower-start-turn').length, 1);
  assert(!server.calls.some(c => c.method.startsWith('turn/')));
});

test('recovering another owner never closes a socket while a delivery is still awaiting its receipt', async t => {
  const { a } = desktopSendFixture(t, { missedDiscovery: Infinity });
  a.options.ownerRetryDelayMs = 0;
  let closes = 0;
  a.ipc = { pending: new Map([['in-flight-send', {}]]), close() { closes++; }, write() {} };
  await assert.rejects(a.sendMessage({ threadId: 'qa', text: 'draft' }), e => e.notSubmitted && e.code === 'DESKTOP_OWNER_UNAVAILABLE');
  assert.equal(closes, 0);
});
