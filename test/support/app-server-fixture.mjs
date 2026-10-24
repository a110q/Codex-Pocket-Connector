import readline from 'node:readline';
const lines = readline.createInterface({ input: process.stdin });
for await (const line of lines) {
  const message = JSON.parse(line);
  if (message.id == null) continue;
  let result;
  if (message.method === 'initialize') result = { platformFamily: 'test' };
  else if (message.method === 'test/environment') result = Object.fromEntries(['CODEX_THREAD_ID','CODEX_DESKTOP_CONTEXT_THREAD_ID','CODEX_APP_TOOLS_PIPE_PATH'].map(key => [key, process.env[key] ?? null]));
  else if (message.method === 'test/error') { process.stdout.write(JSON.stringify({ id:message.id,error:{code:123,message:'test failure'} })+'\n'); continue; }
  else if (message.method === 'test/timeout') continue;
  else if (message.method === 'test/pages') result = { data: [message.params.cursor ? 2 : 1], nextCursor: message.params.cursor ? null : 'next' };
  else if (message.method === 'test/repeat') result = { data: [], nextCursor: 'same' };
  else if (message.method === 'test/event') { process.stdout.write(JSON.stringify({method:'item/started',params:{threadId:'test-thread'}})+'\n'); result={ok:true}; }
  else result = { ok:true };
  const output = JSON.stringify({ id: message.id, result })+'\n';
  // Exercise partial stdout frames, not just JSON returned in one data event.
  process.stdout.write(output.slice(0,5)); process.stdout.write(output.slice(5));
}
