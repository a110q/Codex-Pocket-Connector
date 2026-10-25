import test from 'node:test';
import assert from 'node:assert/strict';
import {approvalResponse,validateForm} from '../approvals.mjs';
import {DesktopAdapter} from '../desktop-adapter.mjs';
const permission={id:'p',method:'item/permissions/requestApproval',params:{threadId:'t',permissions:{network:{enabled:true},fileSystem:{read:['/project'],write:['/project/out'],globScanMaxDepth:3}}}};
test('permission responses can only narrow requested paths, network and depth and preserve scope',()=>{
 const response={permissions:{fileSystem:{read:['/project'],globScanMaxDepth:1}},scope:'session'};assert.deepEqual(approvalResponse(permission,{decision:'accept',response}),response);
 assert.deepEqual(approvalResponse(permission,{decision:'decline'}),{permissions:{},scope:'turn'});
 for(const grant of [{fileSystem:{read:['/']}},{fileSystem:{globScanMaxDepth:4}},{network:{enabled:'true'}},{file_system:{write:['/']}}])assert.throws(()=>approvalResponse(permission,{decision:'accept',response:{permissions:grant,scope:'turn'}}));
 const restricted={...permission,params:{permissions:{fileSystem:{entries:[{access:'write',path:'/project'},{access:'deny',path:'/project/secrets'}]}}}};assert.throws(()=>approvalResponse(restricted,{decision:'accept',response:{scope:'turn',permissions:{fileSystem:{entries:[{access:'write',path:'/project'}]}}}}),/限制/);
 assert.throws(()=>approvalResponse({...permission,params:{permissions:{}}},{decision:'accept',response:{permissions:{network:{enabled:true}},scope:'turn'}}),/未请求/);
});
test('MCP forms validate required, typed, enum, ranges, formats and multi-select values',()=>{
 const schema={type:'object',required:['name','count','ok'],properties:{name:{type:'string',minLength:2,maxLength:8},count:{type:'integer',minimum:1,maximum:5},ok:{type:'boolean'},tags:{type:'array',minItems:1,uniqueItems:true,items:{type:'string',enum:['a','b']}},email:{type:'string',format:'email'}}};
 const content={name:'test',count:2,ok:false,tags:['a']};assert.deepEqual(validateForm(schema,content),content);
 for(const value of [{...content,count:2.5},{...content,name:'x'},{...content,ok:'true'},{...content,tags:['a','a']},{...content,email:'invalid'},{...content,extra:'x'},{name:'test'}])assert.throws(()=>validateForm(schema,value));
 for(const changes of [{oneOf:[]},{properties:{name:{type:'string',pattern:'.*'}}},{properties:{name:{type:'string',format:'unknown'}}}])assert.throws(()=>validateForm({...schema,...changes},content));
 const request={method:'mcpServer/elicitation/request',params:{mode:'form',requestedSchema:schema}};assert.deepEqual(approvalResponse(request,{response:{action:'accept',content}}),{action:'accept',content});
 assert.throws(()=>approvalResponse({...request,params:{mode:'openai/userVerification'}},{response:{action:'accept'}}),/电脑/);assert.deepEqual(approvalResponse(request,{decision:'cancel'}),{action:'cancel',content:null});
});
test('extended approvals route exact desktop IPC envelopes and reject stale/conversation mismatches',async t=>{
 const a=new DesktopAdapter({catalog:false});t.after(()=>a.close());a.ready=true;a.owner=async()=> 'owner';const calls=[];a.request=async(...args)=>{calls.push(args);return{result:{submitted:true}}};
 for(const [request,response,method] of [[permission,{permissions:{network:{enabled:true}},scope:'turn'},'thread-follower-permissions-request-approval-response'],[{id:'m',method:'mcpServer/elicitation/request',params:{mode:'url'}},{action:'accept',content:null},'thread-follower-submit-mcp-server-elicitation-response']]){
  a.snapshots.set('local:t',{state:{requests:[request]}});await a.call('approval.respond',{threadId:'t',requestId:request.id,decision:'accept',response});assert.deepEqual(calls.at(-1),[method,{conversationId:'t',requestId:request.id,response},{hostId:'local',targetClientId:'owner'}]);
 }
 await assert.rejects(a.call('approval.respond',{threadId:'other',requestId:'m',decision:'accept'}),/no longer pending/);a.snapshots.get('local:t').state.requests=[];await assert.rejects(a.call('approval.respond',{threadId:'t',requestId:'m',decision:'accept'}),/no longer pending/);assert.equal(calls.length,2);
});
