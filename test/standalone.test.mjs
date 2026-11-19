import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { EventEmitter, once } from 'node:events';
import { AppServerClient, findCodexCLI } from '../app-server-client.mjs';
import { StandaloneAdapter } from '../standalone-adapter.mjs';
import { mkdirSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';

// Unit fixtures must never discover or subscribe to the user's real desktop.
const fixtureAdapter = options => new StandaloneAdapter({ ipcPath: '/codex-pocket-tests/no-ipc.sock', runtimeCatalog: false, ...options });

test('desktop visible projects override canonical rows, support empty results and retain aliases', async t => {
  const server = new Server();
  server.pages = async () => { throw new Error('must not query canonical projects'); };
  let projects = [{ projectId: 'legacy-keep', canonicalProjectId: 'keep', label: '保留的项目' }];
  const reader = { async connect() {}, async listProjects() { return { projects }; }, close() {} };
  const adapter = fixtureAdapter({ server, catalog: false, projectReader: reader }); t.after(() => adapter.close());
  assert.deepEqual((await adapter.listProjects()).projects, projects);
  projects = []; assert.deepEqual((await adapter.listProjects()).projects, []);
  reader.listProjects = async () => { throw new Error('desktop disconnected'); };
  await assert.rejects(adapter.listProjects(), /desktop disconnected/);
});

test('desktop IPC failure uses the current sidebar state without resurrecting project/list rows', async t => {
  const server = new Server();
  server.pages = async () => { throw new Error('must not query canonical projects'); };
  const reader = { async connect() { throw new Error('desktop disconnected'); }, close() {} };
  const adapter = fixtureAdapter({ server, catalog: false, projectReader: reader }); t.after(() => adapter.close());
  let projects = [{ projectId: 'keep', label: '保留的项目' }];
  adapter.localCatalog = async () => ({ projectAuthority: 'desktop-state', projects });
  assert.deepEqual((await adapter.listProjects()).projects, projects);
  projects = []; assert.deepEqual((await adapter.listProjects()).projects, []);
});

test('project/list controls migrated project existence and keeps legacy aliases only for surviving projects', async t => {
  const server = new Server(); const adapter = fixtureAdapter({ server, catalog: false }); t.after(() => adapter.close());
  let values = [{ id: 'keep', name: '新名称', roots: [{ path: '/new-root' }] }];
  server.pages = async method => { assert.equal(method, 'project/list'); return values; };
  adapter.localCatalog = async () => ({ projectAuthority: 'canonical', projects: [
    { projectId: 'legacy-keep', canonicalProjectId: 'keep', label: '旧名称' },
    { projectId: 'legacy-removed', canonicalProjectId: 'removed' }
  ] });
  const projects = (await adapter.listProjects()).projects;
  assert.equal(projects.length, 1); assert.equal(projects[0].projectId, 'legacy-keep');
  assert.equal(projects[0].label, '新名称'); assert.equal(projects[0].path, '/new-root');
  values = []; assert.deepEqual((await adapter.listProjects()).projects, []);
});

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

test('an unconfirmed App Server exit is still tracked on the next handoff attempt', async () => {
  const client = new AppServerClient();
  const child = Object.assign(new EventEmitter(), { pid: 123, exitCode: null, signalCode: null, stdin: { end() {} }, kill() {} });
  client.child = child;
  await assert.rejects(client.closeAndWait({ graceMs: 5, killWaitMs: 5 }), /尚未退出/);
  assert.equal(client.closing.child, child);
  await assert.rejects(client.closeAndWait({ graceMs: 5, killWaitMs: 5 }), /尚未退出/);
  child.emit('exit', 0);
  await client.closeAndWait();
  assert.equal(client.closing, null);
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

test('runtime discovery pages all desktop summaries including running chats beyond the first fifty', async t => {
  const server=new Server(),calls=[];
  const reader={async connect(){},async tool(name,args){calls.push(args);assert.equal(name,'list_threads');assert.equal(args.mode,'history');
    return args.cursor?{threads:[{id:'older-running',status:'active',hostId:'local'},{id:'other-host',status:'active',hostId:'remote'}]}:{threads:Array.from({length:50},(_,i)=>({id:String(i),status:'idle'})),nextCursor:'page2'}},close(){}};
  const a=fixtureAdapter({server,runtimeReader:reader});t.after(()=>a.close());
  a.localCatalog=async()=>({threads:[...Array.from({length:50},(_,i)=>({id:String(i)})),{id:'older-running'}]});
  const result=await a.listThreads();assert.equal(calls.length,2);assert.equal(result.threads.find(t=>t.id==='older-running').status,'active');
  assert.equal(a.subscriptions.size,0);assert.equal(a.snapshots.size,0);assert(!result.threads.some(t=>t.id==='other-host'));
});

test('a warm runtime monitor pushes unopened task changes and list requests never wait for its next sample', async t => {
  let status='active',release,reads=0;
  const reader={async connect(){},async tool(){reads++;if(release===true)await new Promise(resolve=>{release=resolve});return{threads:[{id:'qa',status}]}},close(){}};
  const a=fixtureAdapter({server:new Server(),runtimeReader:reader,runtimePollMs:15});t.after(()=>a.close());
  a.localCatalog=async()=>({threads:[{id:'qa'}]});
  const events=[];a.on('event',e=>{if(e.type==='threads.status')events.push(e)});
  const wait=async condition=>{const end=Date.now()+2000;while(!condition()){assert(Date.now()<end);await new Promise(r=>setTimeout(r,5));}};
  a.startRuntimeMonitor();await wait(()=>events.length===1);
  assert.equal(a.runtimeSnapshot().threads[0].status,'active');
  status='idle';await wait(()=>events.some(e=>e.threads[0].status==='idle'));
  release=true;await wait(()=>typeof release==='function');
  assert.equal((await a.desktopRuntime({threads:[]})).get('qa').status,'idle');
  a.close();release();await new Promise(r=>setTimeout(r,40));
  assert.equal(a.runtimeSnapshot(),null);assert.equal(a.runtimeTimer,null);assert.equal(a.subscriptions.size,0);
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

class ManagementServer extends Server {
  rows = new Map([['qa', false], ['untouched', false]]);
  held = false;
  readError = null;
  cancelDelete = null;
  async request(method, params) {
    this.calls.push({method, params});
    const { threadId } = params;
    if (method === 'thread/read') {
      if (this.readError) throw this.readError;
      if (!this.rows.has(threadId)) throw Object.assign(Error('thread not loaded: ' + threadId), { code: -32600 });
      return { thread: { id: threadId, status: { type: 'notLoaded' }, cwd: '/project' } };
    }
    if (this.held && threadId === 'qa') throw Object.assign(Error('thread qa already has an active writer'), { code: -32600 });
    if (method === 'thread/archive') this.rows.set(threadId, true);
    else if (method === 'thread/unarchive') this.rows.set(threadId, false);
    else if (method === 'thread/delete') {
      if (this.cancelDelete) throw this.cancelDelete;
      this.rows.delete(threadId);
    } else throw Error('unexpected ' + method);
    return {};
  }
  async pages(method, params) {
    assert.equal(method, 'thread/list');
    return [...this.rows].filter(([, archived]) => archived === (params.archived ?? false))
      .map(([id]) => ({ id, name: id, updatedAt: 1 }));
  }
}

function managementFixture(t, server = new ManagementServer(), options = {}) {
  const a = fixtureAdapter({ server, catalog: false, codexHome: '/codex-pocket-tests/no-codex-home', managementRetryMs: 60000, ...options });
  const notifications = [], events = [];
  a.ready = true; a.ipcAvailable = true;
  a.ipc = { write: value => notifications.push(value), close() {} };
  a.on('event', e => events.push(e));
  t.after(() => a.close());
  return { a, server, notifications, events };
}

test('archive, restore and delete update the real catalogs and notify the desktop', async t => {
  const { a, server, notifications } = managementFixture(t);
  assert.equal((await a.call('thread.archive', { threadId: 'qa', cwd: '/project' })).verified, true);
  assert.equal(server.rows.get('qa'), true);
  assert.equal((await a.call('thread.unarchive', { threadId: 'qa' })).verified, true);
  assert.equal(server.rows.get('qa'), false);
  assert.equal((await a.call('thread.delete', { threadId: 'qa' })).verified, true);
  assert.deepEqual([...server.rows.keys()], ['untouched']);
  assert.deepEqual(notifications.map(n => [n.method, n.version, n.params.conversationId, n.params.cwd]),
    [['thread-archived', 2, 'qa', '/project'], ['thread-unarchived', 1, 'qa', undefined], ['thread-archived', 2, 'qa', '/']]);
  assert.equal((await a.call('threads.list', { includeArchived: true })).threads[0].id, 'untouched');
  await assert.rejects(a.call('thread.archive', { threadId: 'qa', hostId: 'other' }), /本机/);
});

test('delete uses its Pocket-owned worker even during a turn or pending approval', async t => {
  const { a, server } = managementFixture(t);
  const writer = new Server();
  a.threadServers.set('qa', writer); a.ownedThreads.add('qa');
  a.approvals.set('qa:1', { params: { threadId: 'qa' } });
  a.approvals.set('untouched:2', { params: { threadId: 'untouched' } });
  writer.request = async (method, params) => {
    writer.calls.push({ method, params });
    assert.equal(method, 'thread/delete');
    assert.equal(params.threadId, 'qa');
    server.rows.delete(params.threadId);
    return {};
  };
  let exited = false;
  writer.closeAndWait = async () => { exited = true; };
  assert.equal((await a.call('thread.delete', { threadId: 'qa' })).verified, true);
  assert.deepEqual(writer.calls.map(c => c.method), ['thread/delete']);
  assert(exited); assert(!a.ownedThreads.has('qa'));
  assert.deepEqual([...a.approvals.keys()], ['untouched:2']);
});

test('desktop-owned deletion is queued, survives a restart, then deletes and refreshes both clients', async t => {
  const root = new URL('../.private/tests/', import.meta.url).pathname;
  mkdirSync(root, { recursive: true });
  const dir = mkdtempSync(join(root, 'management-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const options = { threadManagementPath: join(dir, 'queue.json'), createThreadServer() { throw Error('must not open a competing writer'); } };
  const { a, server, notifications } = managementFixture(t, undefined, options);
  server.held = true;
  assert.deepEqual(await a.call('thread.delete', { threadId: 'qa', cwd: '/project' }), { threadId: 'qa', pending: true, verified: false, managementReason: 'writer-busy' });
  assert.equal((await a.call('thread.delete', { threadId: 'qa' })).pending, true);
  assert.equal(server.calls.filter(c => c.method === 'thread/delete').length, 1);
  assert.equal((await a.call('threads.list', { includeArchived: true })).threads.find(t => t.id === 'qa').managementStatus, 'pending');
  assert.equal(notifications.length, 0);
  a.close();
  const restarted = managementFixture(t, server, options);
  server.held = false;
  await restarted.a.retryThreadManagement();
  assert.deepEqual([...server.rows.keys()], ['untouched']);
  assert.equal(server.calls.filter(c => c.method === 'thread/delete').length, 2);
  assert.deepEqual(restarted.notifications[0].params, { hostId: 'local', conversationId: 'qa', cwd: '/project' });
  assert.equal(restarted.events.find(e => e.type === 'thread.managed').verified, true);
  const list = await restarted.a.call('threads.list', { includeArchived: true });
  assert.deepEqual(list.completedManagement, [{ threadId: 'qa', action: 'thread.delete' }]);
  assert.deepEqual(list.threads.map(t => t.id), ['untouched']);
});

function sharedManagementFixture(t, loaded) {
  const fix = managementFixture(t);
  fix.server.held = true;
  const methods = [];
  const desktop = {
    async connect() {},
    async pages(method) { assert.equal(method, 'thread/loaded/list'); return loaded; },
    async request(method, params) {
      methods.push([method, params]);
      fix.server.rows.delete(params.threadId);
    },
    close() {},
  };
  fix.a.desktopManagementClient = () => desktop;
  return { ...fix, desktop, methods };
}

test('an external writer conflict routes deletion to the shared server holding the exact chat', async t => {
  const { a, server, methods, events } = sharedManagementFixture(t, ['untouched', 'qa']);
  assert.equal((await a.call('thread.delete', { threadId: 'qa' })).verified, true);
  assert.equal(server.held, true);
  assert.deepEqual(methods, [['thread/delete', { threadId: 'qa' }]]);
  assert.deepEqual([...server.rows.keys()], ['untouched']);
  assert.equal(events.find(e => e.type === 'thread.managed').deleted, true);
});

test('a running daemon does not receive deletion unless it already holds the target', async t => {
  const { a, server, methods, events } = sharedManagementFixture(t, ['untouched']);
  assert.equal((await a.call('thread.delete', { threadId: 'qa' })).managementReason, 'writer-busy');
  assert.deepEqual(methods, []);
  assert.equal(server.rows.size, 2);
  assert.equal(events.some(e => e.type === 'thread.managed'), false);
});

test('desktop archive and restore notifications update mobile without invoking a mutation', { skip: !process.getuid }, async t => {
  const { a, server, events } = managementFixture(t);
  const observer = new EventEmitter();
  const connected = Promise.withResolvers();
  observer.connect = async () => connected.resolve();
  observer.close = () => {};
  a.desktopManagementClient = () => observer;
  a.startDesktopManagementMonitor();
  await connected.promise;
  for (const method of ['thread/archived', 'thread/unarchived']) observer.emit('notification', { method, params: { threadId: 'qa' } });
  assert.deepEqual(events.filter(e => e.type === 'thread.managed').map(e => [e.action, e.verified]), [['thread.archive', true], ['thread.unarchive', true]]);
  assert(!server.calls.some(c => /^thread\/(archive|unarchive|delete)$/.test(c.method)));
  a.stopDesktopManagementMonitor();
  observer.emit('notification', { method: 'thread/deleted', params: { threadId: 'qa' } });
  assert.equal(events.filter(e => e.type === 'thread.managed').length, 2);
});

test('closing the connector during a shared ownership probe cancels submission', async t => {
  const { a, desktop, methods } = sharedManagementFixture(t, ['qa']);
  const entered = Promise.withResolvers(), finish = Promise.withResolvers();
  desktop.pages = async () => { entered.resolve(); return finish.promise; };
  const deletion = a.call('thread.delete', { threadId: 'qa' });
  await entered.promise;
  a.close(); finish.resolve(['qa']);
  assert.equal((await deletion).pending, true);
  assert.equal(a.threadManagement.get('qa').phase, 'waiting');
  assert.deepEqual(methods, []);
});

test('disconnect after a shared-server submission never replays the deletion', async t => {
  const { a, server, desktop, methods, events } = sharedManagementFixture(t, ['qa']);
  desktop.request = async (method, params) => {
    methods.push([method, params]);
    throw Object.assign(Error('连接已断开'), { uncertain: true });
  };
  assert.equal((await a.call('thread.delete', { threadId: 'qa' })).managementReason, 'verifying');
  await a.retryThreadManagement();
  assert.deepEqual(methods, [['thread/delete', { threadId: 'qa' }]]);
  assert.equal(server.calls.filter(c => c.method === 'thread/delete').length, 1);
  assert.equal(events.some(e => e.type === 'thread.managed'), false);
});

test('an ambiguous delete verifies without replaying, and list omissions alone are not proof', async t => {
  const { a, server, events } = managementFixture(t);
  const deleteCalls = () => server.calls.filter(c => c.method === 'thread/delete').length;
  server.cancelDelete = Object.assign(Error('timeout'), { uncertain: true });
  assert.equal((await a.call('thread.delete', { threadId: 'qa' })).pending, true);
  server.rows.delete('qa');
  server.readError = Error('network temporarily unavailable');
  await a.retryThreadManagement();
  assert.equal(events.filter(e => e.type === 'thread.managed').length, 0);
  server.readError = null;
  a.localCatalog = async () => ({ threads: [{ id: 'qa' }] });
  await a.retryThreadManagement();
  assert.equal(events.filter(e => e.type === 'thread.managed').length, 0);
  a.localCatalog = async () => ({ threads: [] });
  await a.retryThreadManagement();
  assert.equal(deleteCalls(), 1);
  assert.equal(events.find(e => e.type === 'thread.managed').deleted, true);
});

test('a release signal preempts a long retry deadline without touching other chats', async t => {
  const { a, server } = managementFixture(t);
  server.held = true;
  assert.equal((await a.call('thread.delete', { threadId: 'qa' })).managementReason, 'writer-busy');
  assert.ok(a.managementTimerAt > Date.now() + 50000);
  server.held = false;
  const settled = once(a, 'event', { signal: AbortSignal.timeout(1000) });
  a.onServerEvent({ method: 'turn/completed', params: { threadId: 'qa' } });
  // The retry timer is deliberately unref'd in production.
  const keepAlive = setTimeout(() => {}, 1100); t.after(() => clearTimeout(keepAlive));
  const [event] = await settled;
  assert.equal(event.type, 'thread.managed');
  assert.equal(event.verified, true);
  assert.deepEqual([...server.rows.keys()], ['untouched']);
});

test('retries start promptly and back off no longer than three seconds by default', async t => {
  const { a, server } = managementFixture(t, undefined, { managementRetryMs: undefined });
  server.held = true;
  const base = Date.now();
  await a.call('thread.delete', { threadId: 'qa' });
  assert.ok(a.threadManagement.get('qa').retryAt >= base + 500);
  assert.ok(a.threadManagement.get('qa').retryAt <= Date.now() + 500);
  for (let i = 0; i < 6; i++) await a.retryThreadManagement();
  assert.ok(a.threadManagement.get('qa').retryAt <= Date.now() + 3000);
  assert.ok(a.threadManagement.get('qa').retryAt >= base + 3000);
});

test('a slow deletion verification does not delay an independent queued deletion', async t => {
  const { a, server, events } = managementFixture(t);
  server.cancelDelete = Object.assign(Error('receipt lost'), { uncertain: true });
  assert.equal((await a.call('thread.delete', { threadId: 'qa' })).managementReason, 'verifying');
  assert.equal((await a.call('thread.delete', { threadId: 'untouched' })).managementReason, 'verifying');
  server.rows.delete('untouched');
  const { promise: blocked, resolve: unblock } = Promise.withResolvers();
  const request = server.request.bind(server);
  server.request = async (method, args) => {
    if (method === 'thread/read' && args.threadId === 'qa') await blocked;
    return request(method, args);
  };
  const settled = once(a, 'event', { signal: AbortSignal.timeout(1000) });
  const keepAlive = setTimeout(() => {}, 1100); t.after(() => clearTimeout(keepAlive));
  const retry = a.retryThreadManagement();
  try {
    const [event] = await settled;
    assert.equal(event.type, 'thread.managed');
    assert.equal(event.threadId, 'untouched');
    assert.equal(a.threadManagement.get('qa').phase, 'verify');
  } finally { unblock(); await retry; }
  assert.equal(events.filter(e => e.type === 'thread.managed').length, 1);
  assert.deepEqual(server.calls.filter(c => c.method === 'thread/delete').map(c => c.params.threadId), ['qa','untouched']);
});

test('archive and restore defer a confirmed request only while an external writer owns it', async t => {
  const { a, server } = managementFixture(t);
  for (const [action, archived] of [['thread.archive', true], ['thread.unarchive', false]]) {
    server.held = true;
    assert.equal((await a.call(action, { threadId: 'qa' })).pending, true);
    assert.equal(server.rows.get('qa'), !archived);
    server.held = false;
    await a.retryThreadManagement();
    assert.equal(server.rows.get('qa'), archived);
  }
});

test('desktop runtime monitoring does not subscribe to its own Pocket-owned conversations', async t => {
  const server = new Server();
  const reader = { async connect() {}, async read(catalog) {
    assert.deepEqual(catalog.threads.map(t => t.id), ['desktop']);
    return new Map([['desktop', { status: 'idle' }]]);
  }, close() {} };
  const a = fixtureAdapter({ server, catalog: false, runtimeReader: reader });
  const writer = new Server();
  writer.request = async (method, params) => {
    assert.equal(method, 'thread/read'); assert.deepEqual(params, { threadId: 'qa', includeTurns: false });
    return { thread: { id: 'qa', status: { type: 'active' } } };
  };
  a.threadServers.set('qa', writer); a.ownedThreads.add('qa'); t.after(() => a.close());
  const runtime = await a.readDesktopRuntime({ threads: [{ id: 'qa' }, { id: 'desktop' }] });
  assert.equal(runtime.get('desktop').status, 'idle');
  assert.equal(runtime.get('qa').status, 'active');
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

test('desktop follows active work with complete history; an open follower cannot keep the idle writer locked', async t => {
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
  assert.equal(await a.releaseThread('phone-thread'), false, 'active work cannot be released');
  opts.active = false;
  assert.equal(await a.releaseThread('phone-thread'), true);
  assert(!a.ownerBridge.followers.has('phone-thread'));
  assert.equal(messages.at(-1).method, 'thread-unarchived');
  a.handleIpc({ type: 'client-discovery-request', requestId: 'after-handoff', request: { method: 'thread-owner-discovery', version: 1, params: { hostId: 'local', conversationId: 'phone-thread' } } });
  assert.equal(messages.at(-1).response.canHandle, false);
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
  const waitUntil = async predicate => {
    const deadline = performance.now() + 3000;
    while (!predicate() && performance.now() < deadline) await new Promise(r=>setTimeout(r,10));
  };
  const repeated = () => server.calls.filter(c=>c.method==='thread/read').length>8;
  await waitUntil(repeated);
  assert(a.ownedThreads.has('phone-thread')); assert(repeated(), 'watchdog kept checking an active thread');
  options.active=false;
  await waitUntil(() => !a.ownedThreads.has('phone-thread'));
  assert(!a.ownedThreads.has('phone-thread'));
});

test('completed Pocket writer publishes idle at handoff even if the monitor missed completion', async t => {
  const options = { active: true }, { a } = publicCreateFixture(t, options);
  await a.createThread({ text: 'first' });
  const catalog = { threads: [{ id: 'phone-thread' }] };
  await a.desktopRuntime(catalog, { fresh: true });
  assert.equal(a.runtimeCache.get('phone-thread').status, 'active');

  // The next desktop sample started before release and has no fresh evidence.
  let finishPoll;
  a.readDesktopRuntime = () => new Promise(resolve => { finishPoll = resolve; });
  const polling = a.desktopRuntime(catalog, { fresh: true });
  const events = [];
  a.on('event', event => { if (event.type === 'threads.status') events.push(event); });
  options.active = false;
  assert.equal(await a.releaseThread('phone-thread'), true);
  assert.equal(events.at(-1)?.threads.find(t => t.id === 'phone-thread')?.status, 'idle');
  finishPoll(new Map([['phone-thread', { status: 'active' }]]));
  assert.equal((await polling).get('phone-thread').status, 'idle');
  assert.equal(a.runtimeCache.get('phone-thread').status, 'idle');

  // A new, authoritative observation can start the next turn normally.
  a.readDesktopRuntime = async () => new Map([['phone-thread', { status: 'active' }]]);
  assert.equal((await a.desktopRuntime(catalog, { fresh: true })).get('phone-thread').status, 'active');
});

test('authoritative idle status hands off without loading history, but never with a pending approval', async t => {
  const { a }=publicCreateFixture(t); await a.createThread({text:'first'});
  a.states.set('phone-thread',{turns:[{id:'old',status:'inProgress'}]});
  a.storedState=async()=>{throw Error('must not load history to release a known idle writer');};
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

test('stored desktop fallback reports loss of live subscription and a later read recovers it', async t => {
  const server=new Server(),a=fixtureAdapter({server,catalog:false});t.after(()=>a.close());a.ready=true;
  a.desktopOwner=async()=> 'desktop-owner';
  const prototype=Object.getPrototypeOf(StandaloneAdapter.prototype),original=prototype.readThread;let available=false;
  prototype.readThread=async function(args,options){assert.equal(options.loadHistory,false);if(!available)throw Error('snapshot timed out');return {thread:{id:'qa',status:'active'},messages:[{id:'fresh',text:'new reply'}],source:'desktop-live'};};
  try{
    const stored=await a.readThread({threadId:'qa',tailOnly:true});assert.equal(stored.live,false);assert.equal(stored.source,'codex-app-server');assert.match(stored.liveUnavailable,/自动重试/);
    available=true;const live=await a.readThread({threadId:'qa',tailOnly:true});assert.equal(live.live,true);assert.equal(live.messages[0].text,'new reply');
    assert(!server.calls.some(c=>c.method==='thread/resume'));
  }finally{prototype.readThread=original}
});
