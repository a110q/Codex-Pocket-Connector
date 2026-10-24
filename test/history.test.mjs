import test from 'node:test';
import assert from 'node:assert/strict';
import { HistoryStore } from '../history.mjs';
import { normalizeSnapshot, DesktopAdapter } from '../desktop-adapter.mjs';

test('all desktop-visible tool classes remain in order; only reasoning summaries leave the adapter', () => {
  const types=['commandExecution','fileChange','mcpToolCall','dynamicToolCall','collabAgentToolCall','functionCallOutput','subAgentActivity','webSearch','imageView','imageGeneration','plan','contextCompaction','modelChanged','futureVisibleTool'];
  const snapshot=normalizeSnapshot({id:'qa',turns:[{id:'turn',items:types.map((type,id)=>({id,type,status:'completed',arguments:{query:'中文'},result:{output:'result'},text:'visible'})).concat([
    {id:'r',type:'reasoning',summary:['Visible summary'],content:['PRIVATE_REASONING'],encryptedContent:'PRIVATE_CIPHER'},
    {id:'s',type:'systemMessage',text:'PRIVATE_SYSTEM'},
    {id:'d',type:'developerMessage',text:'PRIVATE_DEVELOPER'},
  ])}]});
  assert.deepEqual(snapshot.messages.map(message=>message.type),[...types,'reasoning']);
  assert.equal(snapshot.messages.at(-1).text,'Visible summary');
  assert(!JSON.stringify(snapshot).includes('PRIVATE_'));
  assert.deepEqual(snapshot.messages.map(message=>message.order),Array.from({length:15},(_,i)=>i));
  assert(snapshot.messages.find(message=>message.type==='mcpToolCall').detail.includes('query'));
  const linked=normalizeSnapshot({id:'qa',turns:[{id:'t',items:[{id:'c',type:'collabAgentToolCall',receiverThreadIds:['child','child']},{id:'s',type:'subAgentActivity',agentThreadId:'other'}]}]});
  const store=new HistoryStore();const page=store.put(linked);
  assert.deepEqual(page.messages.map(message=>message.relatedThreads.map(thread=>thread.id)),[['child'],['other']]);
});

test('paging covers 240 messages exactly once and reconstructs long Unicode tool details without loss', () => {
  const store=new HistoryStore();
  const detail='手机🦊\n'.repeat(45000);
  const all=Array.from({length:240},(_,i)=>({id:String(i),key:`t:${i}`,turnId:'t',order:i,role:'tool',text:i===239?detail:'output '+i,detail:i===239?detail:undefined}));
  let page=store.put({thread:{id:'qa',hostId:'local'},historyComplete:true,messages:all,requests:[{id:5}]});
  const collected=[...page.messages];
  while(page.history.hasEarlier){page=store.page({threadId:'qa',before:page.history.before});assert(Buffer.byteLength(JSON.stringify(page))<512*1024);collected.unshift(...page.messages)}
  assert.deepEqual(collected.map(message=>message.key),all.map(message=>message.key));
  const message=collected.at(-1);let text='',offset=0;
  for(;;){const chunk=store.read({threadId:'qa',messageKey:message.key,mode:'detail',version:message.detailVersion,offset});text+=chunk.text;offset=chunk.nextOffset;if(chunk.done)break;assert(offset>0)}
  assert.equal(text,detail);assert.equal(collected[0].textComplete,true);assert.equal(message.textComplete,false);
  assert.throws(()=>store.read({threadId:'qa',hostId:'other',messageKey:message.key}),/缓存/);
  assert.throws(()=>store.read({threadId:'qa',messageKey:message.key,version:message.detailVersion,offset:-1}),/offset/);
  store.put({thread:{id:'qa'},messages:all.map(m=>m.key===message.key?{...m,text:'new',detail:'new'}:m)});
  assert.throws(()=>store.read({threadId:'qa',messageKey:message.key,version:message.detailVersion}),/更新/);
});

test('history cursors and content access fail explicitly after eviction or missing records',()=>{
  const store=new HistoryStore();for(let i=0;i<5;i++)store.put({thread:{id:String(i)},messages:[{id:'1',turnId:'t',text:'ok'}]});
  assert.throws(()=>store.page({threadId:'0'}),/缓存/);
  assert.throws(()=>store.page({threadId:'4',before:'unknown'}),/历史位置/);
  assert.throws(()=>store.read({threadId:'4',messageKey:'unknown'}),/记录/);
});

test('summary fallback keeps chronological turn order and marks already shortened desktop output',async()=>{
  const adapter=new DesktopAdapter();adapter.owner=async()=>{throw Error('no-client-found')};
  adapter.tool=async()=>({thread:{id:'qa'},page:{order:'newest_first',hasMore:false},turns:[{id:'new',items:[{id:'a',type:'agentMessage',text:'newer'}]},{id:'old',items:[{id:'c',type:'commandExecution',output:{text:'earlier output',truncated:true}}]}]});
  const result=await adapter.readThread({threadId:'qa'});
  assert.equal(result.source,'desktop-history-summary');assert.equal(result.historyComplete,false);
  assert.deepEqual(result.messages.map(m=>m.text),['earlier output','newer']);
  assert.equal(new HistoryStore().put(result).messages[0].sourceTruncated,true);
});
