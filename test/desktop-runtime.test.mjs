import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { DesktopRuntime } from '../desktop-runtime.mjs';

class Transport extends EventEmitter {
  constructor(){super();this.calls=[];this.follows=[];this.owners=new Set(['qa']);}
  async connect(){}
  async exchange(m){this.calls.push(m);return m.method==='initialize'?{resultType:'success',result:{clientId:'observer'}}:this.owners.has(m.params.conversationId)?{resultType:'success',handledByClientId:'owner'}:{resultType:'error',error:'no-client-found'};}
  write(m){if(m.method==='thread-stream-following-changed'){this.follows.push(m);if(m.params.following)queueMicrotask(()=>this.snapshot(m.params.conversationId));}}
  snapshot(id='qa',revision=1,status={type:'active',activeFlags:[]}){this.emit('message',{type:'broadcast',method:'thread-stream-state-changed',version:11,sourceClientId:'owner',params:{hostId:'local',conversationId:id,change:{type:'snapshot',revision,conversationState:{threadRuntimeStatus:status,turns:[{text:'large transcript that must not be retained'}]}}}});}
  patch(revision,baseRevision,patches,sourceClientId='owner',hostId='local'){this.emit('message',{type:'broadcast',method:'thread-stream-state-changed',version:11,sourceClientId,params:{hostId,conversationId:'qa',change:{type:'patches',revision,baseRevision,patches}}});}
  close(){}
}
const catalog={threads:[{id:'qa'},{id:'unloaded'},{id:'hidden',isSubagent:true},{id:'remote',hostId:'remote'},{id:'archived',archived:true}]};
function fixture(t){const transport=new Transport(),reader=new DesktopRuntime({createRuntimeTransport:()=>transport});t.after(()=>reader.close());return{reader,transport};}

test('native runtime observation uses IPC only, projects metadata and follows completion without navigation',async t=>{
 const {reader,transport}=fixture(t);let changed=0;reader.on('changed',()=>changed++);
 assert.equal((await reader.read(catalog)).get('qa').status,'active');
 assert.deepEqual(transport.calls.filter(c=>c.method==='thread-owner-discovery').map(c=>c.params.conversationId),['qa','unloaded']);
 assert(!JSON.stringify([...reader.watches]).includes('large transcript'));
 transport.patch(2,1,[{op:'replace',path:['turns',0,'text'],value:'ignored'}]);assert.equal(changed,1);assert.equal(reader.watches.get('qa').revision,2);
 transport.patch(3,2,[{op:'add',path:['threadRuntimeStatus','activeFlags',0],value:'waitingOnApproval'}]);assert.deepEqual(reader.statuses.get('qa').threadRuntimeStatus.activeFlags,['waitingOnApproval']);
 transport.patch(4,3,[{op:'replace',path:['threadRuntimeStatus'],value:{type:'idle'}}]);assert.equal(reader.statuses.get('qa').status,'idle');
 await reader.read(catalog);assert.equal(transport.follows.filter(m=>m.params.following).length,1);
 reader.close();assert.equal(transport.follows.at(-1).params.following,false);assert.equal(reader.watches.size,0);
});

test('runtime discovery covers older tasks after the first fifty and does not retain histories',async t=>{
 const {reader,transport}=fixture(t);transport.owners=new Set(['task-80']);
 const result=await reader.read({threads:Array.from({length:81},(_,i)=>({id:`task-${i}`}))});
 assert.equal(transport.calls.filter(c=>c.method==='thread-owner-discovery').length,81);assert.equal(result.get('task-80').status,'active');assert.equal(reader.watches.size,1);
});

test('runtime rejects foreign, stale and missing revisions and resynchronizes after a gap',async t=>{
 const {reader,transport}=fixture(t);await reader.read(catalog);
 const idle=[{op:'replace',path:['threadRuntimeStatus'],value:{type:'idle'}}];
 transport.patch(2,1,idle,'other-owner');transport.patch(2,1,idle,'owner','remote');transport.patch(1,0,idle);assert.equal(reader.statuses.get('qa').status,'active');
 transport.patch(4,3,idle);assert.equal(transport.follows.length,2);assert.equal(reader.statuses.get('qa').status,'active');
 await Promise.resolve();transport.snapshot('qa',5,{type:'idle'});assert.equal(reader.statuses.get('qa').status,'idle');
});

test('runtime un-follows removed projects, handles owner disconnect and reconnects after desktop restart',async t=>{
 const {reader,transport}=fixture(t);await reader.read(catalog);
 transport.emit('message',{type:'broadcast',method:'client-status-changed',params:{clientId:'owner',status:'disconnected'}});
 assert.equal(reader.statuses.get('qa').status,'notLoaded');assert.equal(reader.watches.size,0);
 await reader.read(catalog);assert.equal(reader.statuses.get('qa').status,'active');
 await reader.read({threads:[]});assert.equal(reader.statuses.size,0);assert.equal(transport.follows.at(-1).params.following,false);
 transport.emit('disconnect',Error('desktop closed'));assert.equal(reader.ready,false);
 await reader.read(catalog);assert.equal(reader.statuses.get('qa').status,'active');
});
