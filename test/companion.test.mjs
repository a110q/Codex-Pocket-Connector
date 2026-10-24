import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdirSync, writeFileSync, symlinkSync, renameSync } from 'node:fs';
import { resolve } from 'node:path';
import { CommandLedger, FileAccess, boundedSnapshot } from '../companion.mjs';

test('mutation retries share one execution and stay deduplicated after restarting the ledger', async () => {
  const directory = resolve('.private/tests', randomUUID()); mkdirSync(directory, { recursive: true });
  const path = `${directory}/commands.sqlite`; let ledger = new CommandLedger(path), count = 0;
  const command = { id: randomUUID(), action: 'message.send', args: { threadId: 'test', text: 'hello' } };
  const execute = async () => { count++; await new Promise(resolve => setTimeout(resolve, 10)); return { sent: true }; };
  assert.deepEqual(await Promise.all([ledger.run(command, execute), ledger.run(command, execute)]), [{ sent: true }, { sent: true }]);
  assert.equal(count, 1); ledger.close(); ledger = new CommandLedger(path);
  assert.deepEqual(await ledger.run(command, execute), { sent: true }); assert.equal(count, 1);
  await assert.rejects(ledger.run({ ...command, args: { text: 'different' } }, execute), /不同操作/);
  const interrupted = { ...command, id: randomUUID() };
  await assert.rejects(ledger.run(interrupted, async () => { throw new Error('network interrupted after write'); }));
  ledger.close(); ledger = new CommandLedger(path);
  await assert.rejects(ledger.run(interrupted, execute), /不会重复执行/); assert.equal(count, 1); ledger.close();
});
test('a failure explicitly known to precede submission is cached as rejected rather than pending', async () => {
  const directory = resolve('.private/tests', randomUUID()); mkdirSync(directory, { recursive: true });
  const path = `${directory}/commands.sqlite`; let ledger = new CommandLedger(path), attempts = 0;
  const command = { id: randomUUID(), action: 'thread.create', args: { text: 'one prompt' } };
  const reject = async () => { attempts++; throw Object.assign(new Error('desktop not connected'), { notSubmitted: true }); };
  await assert.rejects(ledger.run(command, reject), e => e.uncertain === false && e.notSubmitted === true);
  ledger.close(); ledger = new CommandLedger(path);
  await assert.rejects(ledger.run(command, reject), e => e.uncertain === false && e.message === 'desktop not connected');
  assert.equal(attempts, 1);
  assert.deepEqual(await ledger.run({ ...command, id: randomUUID() }, async () => ({ threadId: 'new-thread' })), { threadId: 'new-thread' });
  ledger.close();
});

test('confirming a legacy refused writer acquisition clears pending without resending, while ambiguous errors stay pending', async () => {
  const directory = resolve('.private/tests', randomUUID()); mkdirSync(directory, { recursive: true });
  const ledger = new CommandLedger(`${directory}/commands.sqlite`);
  let executions = 0;
  for (const [error, certain] of [['thread existing-id already has an active writer', true], ['request timed out after sending', false], ['provider returned an error', false]]) {
    const command = { id: randomUUID(), action: 'message.send', args: { threadId: 'existing-id', text: 'original draft' } };
    await assert.rejects(ledger.run(command, async () => { executions++; throw Error(error); }));
    await assert.rejects(ledger.run(command, async () => { executions++; return { sent: true }; }), e => certain ? e.notSubmitted === true && e.uncertain === false : e.uncertain === true);
  }
  assert.equal(executions, 3); ledger.close();
});

test('only real linked artifacts inside the selected project are readable', () => {
  const directory = resolve('.private/tests', randomUUID()); mkdirSync(`${directory}/project`, { recursive: true });
  writeFileSync(`${directory}/project/result.txt`, 'verified output'); writeFileSync(`${directory}/outside.txt`, 'private');
  symlinkSync(`${directory}/outside.txt`, `${directory}/project/escape.txt`);
  const files = new FileAccess();
  const snapshot = files.decorate({ thread: { id: 't1', cwd: `${directory}/project` }, messages: [{ role: 'assistant', text: `[result](${directory}/project/result.txt) [escape](${directory}/project/escape.txt)` }] });
  assert.equal(snapshot.artifacts.length, 1);
  const result = files.read({ threadId: 't1', artifactId: snapshot.artifacts[0].id });
  assert.equal(Buffer.from(result.data, 'base64').toString(), 'verified output'); assert.equal(result.done, true);
  assert.throws(() => files.read({ threadId: 'other', artifactId: snapshot.artifacts[0].id }));
  assert.throws(() => files.read({ threadId: 't1', artifactId: snapshot.artifacts[0].id, offset: -1 }));
  writeFileSync(`${directory}/project/result.txt`, 'changed');
  assert.throws(() => files.read({ threadId: 't1', artifactId: snapshot.artifacts[0].id }), /变化/);
});
test('large history is bounded without dropping pending approvals', () => {
  const snapshot = boundedSnapshot({ thread: { id: 't' }, requests: [{ id: 1 }], messages: Array.from({ length: 200 }, (_, id) => ({ id, role: 'tool', text: 'a'.repeat(20000) })) });
  assert(Buffer.byteLength(JSON.stringify(snapshot)) < 1024 * 1024);
  assert.equal(snapshot.history.hasEarlier, true); assert.equal(snapshot.history.total, 200); assert.equal(snapshot.messages[0].textComplete, false); assert.equal(snapshot.requests.length, 1);
});

test('only explicitly attached desktop images can be read outside the project and survive history paging', () => {
  const directory = resolve('.private/tests', randomUUID()); mkdirSync(`${directory}/project`, { recursive: true });
  const image = `${directory}/clipboard.jpg`, other = `${directory}/other.jpg`, doc = `${directory}/private.txt`;
  writeFileSync(image, 'image bytes'); writeFileSync(other, 'other'); writeFileSync(doc, 'private');
  symlinkSync(other, `${directory}/escape.jpg`);
  const files = new FileAccess();
  const snapshot = files.decorate({ thread: { id: 'pictures', cwd: `${directory}/project` }, messages: [
    { role: 'user', id: 'u', turnId: 'turn', text: 'hello', attachments: [image, doc, `${directory}/escape.jpg`, `${directory}/missing.png`].map(path => ({ type: 'image', path })) },
    { role: 'assistant', text: `[other](${other})` },
  ] });
  assert.equal(snapshot.artifacts.length, 1);
  const attachments = boundedSnapshot(snapshot).messages[0].attachments;
  assert.deepEqual(attachments.map(image => image.available), [true, false, false, false]);
  assert.equal(Buffer.from(files.read({ threadId: 'pictures', artifactId: attachments[0].id }).data, 'base64').toString(), 'image bytes');
  assert.throws(() => files.read({ threadId: 'other-thread', artifactId: attachments[0].id }));
  const unsafeRoot = files.decorate({ thread: { id: 'home', cwd: '/' }, messages: [{ role: 'assistant', text: `[image](${other})` }] });
  assert.equal(unsafeRoot.artifacts.length, 0);
  renameSync(image, `${directory}/moved.jpg`); symlinkSync(other, image);
  assert.throws(() => files.read({ threadId: 'pictures', artifactId: attachments[0].id }), /变化/);
});

test('an uncertain delivery can be reconciled with its client ID after restart without executing again',async()=>{
  const dir=resolve('.private/tests',randomUUID());mkdirSync(dir,{recursive:true});const path=resolve(dir,'commands.sqlite');let ledger=new CommandLedger(path),sends=0;
  const cmd={id:randomUUID(),action:'message.send',args:{threadId:'t',text:'one prompt'}};
  await assert.rejects(ledger.run(cmd,()=>{sends++;throw Error('receipt lost')}));ledger.close();ledger=new CommandLedger(path);
  const receipt={submitted:true,confirmed:true,messageId:'canonical'};
  assert.deepEqual(await ledger.run(cmd,()=>{sends++;},()=>receipt),receipt);
  assert.deepEqual(await ledger.run(cmd,()=>{sends++;}),receipt);assert.equal(sends,1);ledger.close();
});
