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

test('evicted long histories and full message details recover automatically while other chats refresh', async () => {
  const store = new HistoryStore();
  const snapshot = { thread: { id: 'reading', hostId: 'local' }, historyComplete: true,
    messages: Array.from({ length: 2842 }, (_, order) => ({ id: String(order), key: `turn:${order}`, turnId: 'turn', order, text: order === 2841 ? '中文🦊'.repeat(2000) : `消息 ${order}` })) };
  const tail = store.put(snapshot), detail = tail.messages.at(-1);
  const evict = () => { for (let i=0;i<8;i++) store.put({ thread: { id: 'background-'+i }, messages: [] }); };
  evict(); let loads=0;
  const load = async () => { loads++; await Promise.resolve(); return snapshot; };
  const [page, chunk] = await Promise.all([
    store.access('thread.history', { threadId: 'reading', before: tail.history.before }, load),
    store.access('message.read', { threadId: 'reading', messageKey: detail.key, version: detail.contentVersion }, load),
  ]);
  assert.equal(loads,1); assert.equal(page.messages.at(-1).order,2801); assert.equal(page.messages.length,40);
  assert.equal(chunk.text,snapshot.messages.at(-1).text);
  const collected=[...page.messages,...tail.messages]; let older=page;
  while(older.history.hasEarlier){evict();older=await store.access('thread.history',{threadId:'reading',before:older.history.before},load);collected.unshift(...older.messages);}
  assert.equal(collected.length,2842); assert.equal(new Set(collected.map(m=>m.key)).size,2842);
  assert.deepEqual(collected.map(m=>m.order),snapshot.messages.map(m=>m.order));
  assert.equal(store.loading.size,0);
});

test('desktop question replies render human questions and answers without internal XML or IDs', () => {
  const replies=[{questionItemId:'private-call-id',question:'请完成登录',answer:'已登录'},{question:'更新方式',answer:'由我决定\n暂不更新'}];
  const text='<send_user_message_question_reply>\n'+JSON.stringify(replies)+'\n</send_user_message_question_reply>';
  const snapshot=normalizeSnapshot({id:'qa',turns:[{id:'t',items:[{id:'u',type:'userMessage',content:[{type:'text',text}]}]}]});
  const message=new HistoryStore().put(snapshot).messages[0];
  assert.equal(message.text,'请完成登录\n已登录\n\n更新方式\n由我决定\n暂不更新');
  assert.deepEqual(message.questionReplies,replies.map(({question,answer})=>({question,answer})));
  assert(!message.text.includes('private-call-id')); assert(!message.text.includes('<send_'));
  for(const literal of ['普通消息','代码示例：'+text,'<send_user_message_question_reply>invalid</send_user_message_question_reply>']) {
    assert.equal(normalizeSnapshot({id:'qa',turns:[{id:'t',items:[{id:'u',type:'userMessage',content:[{type:'text',text:literal}]}]}]}).messages[0].text,literal);
  }
});

test('summary fallback keeps chronological turn order and marks already shortened desktop output',async()=>{
  const adapter=new DesktopAdapter();adapter.owner=async()=>{throw Error('no-client-found')};
  adapter.tool=async()=>({thread:{id:'qa'},page:{order:'newest_first',hasMore:false},turns:[{id:'new',items:[{id:'a',type:'agentMessage',text:'newer'}]},{id:'old',items:[{id:'c',type:'commandExecution',output:{text:'earlier output',truncated:true}}]}]});
  const result=await adapter.readThread({threadId:'qa'});
  assert.equal(result.source,'desktop-history-summary');assert.equal(result.historyComplete,false);
  assert.deepEqual(result.messages.map(m=>m.text),['earlier output','newer']);
  assert.equal(new HistoryStore().put(result).messages[0].sourceTruncated,true);
});
