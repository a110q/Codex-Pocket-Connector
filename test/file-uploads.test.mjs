import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,rmSync,readFileSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {randomUUID} from 'node:crypto';
import {FileUploads,attachmentText} from '../file-uploads.mjs';
import {FileAccess} from '../companion.mjs';
import {digest,validateCommand} from '../protocol.mjs';
function fixture(t){mkdirSync('.private/tests',{recursive:true});const dir=mkdtempSync(resolve('.private/tests/files-'));t.after(()=>rmSync(dir,{force:true,recursive:true}));return dir;}
test('ordinary files survive restart and retries, verify hash, and remain scoped to the chat',t=>{
 const dir=fixture(t),bytes=Buffer.alloc(400*1024,42),id=randomUUID(),args={uploadId:id,threadId:'t',size:bytes.length,name:'报告.txt',mime:'text/plain',sha256:digest(bytes)};let u=new FileUploads(dir);
 u.start(args);const chunk={uploadId:id,offset:0,data:bytes.subarray(0,192*1024).toString('base64')};u.chunk(chunk);assert.equal(u.chunk(chunk).nextOffset,192*1024);
 u=new FileUploads(dir);assert.equal(u.start(args).nextOffset,192*1024);assert.throws(()=>u.resolve([id],'t'),/未上传完成/);assert.throws(()=>u.finish({uploadId:id}),/完整性/);
 for(let offset=192*1024;offset<bytes.length;offset+=192*1024)u.chunk({uploadId:id,offset,data:bytes.subarray(offset,offset+192*1024).toString('base64')});u.finish({uploadId:id});u.finish({uploadId:id});assert(u.start(args).done);assert(readFileSync(u.resolve([id],'t')[0].path).equals(bytes));
 assert.throws(()=>u.resolve([id],'other'),/不属于/);assert.throws(()=>u.resolve([id,id],'t'),/最多/);assert.throws(()=>u.start({...args,threadId:'other'}),/其他文件/);
 const bad={...args,uploadId:randomUUID(),sha256:'a'.repeat(64),size:3};u.start(bad);u.chunk({uploadId:bad.uploadId,offset:0,data:'YWJj'});assert.throws(()=>u.finish(bad),/完整性/);u.cancel(bad);assert.throws(()=>u.load(bad.uploadId),/失效/);
});
test('uploads reject unsafe names, oversized files/chunks and pending quota exhaustion',t=>{
 const u=new FileUploads(fixture(t)),args={threadId:'t',size:1,name:'ok.txt',sha256:digest('a')};
 for(const name of ['../escape','bad\nname','name.','C:foo','CON','NUL.txt','COM1.log'])assert.throws(()=>u.start({...args,name}),/名称/);
 assert.throws(()=>u.start({...args,size:50*1024*1024+1}),/大小/);assert.throws(()=>u.start({...args,uploadId:'../x'}),/标识/);
 const id=u.start(args).uploadId;assert.throws(()=>u.chunk({uploadId:id,offset:0,data:'!'}),/分片/);assert.throws(()=>u.chunk({uploadId:id,offset:1,data:'YQ=='}),/分片/);assert.throws(()=>u.chunk({uploadId:id,offset:0,data:Buffer.alloc(192*1024+1).toString('base64')}),/分片/);
 for(let i=1;i<12;i++)u.start(args);assert.throws(()=>u.start(args),/过多/);
});
test('native attachment wrapper grants preview only for controlled uploaded paths and preserves prompt text',t=>{
 const dir=fixture(t),uploads=new FileUploads(resolve(dir,'uploads')),bytes=Buffer.from('hello'),id=uploads.start({threadId:'t',name:'code.txt',size:5,sha256:digest(bytes)}).uploadId;
 uploads.chunk({uploadId:id,offset:0,data:bytes.toString('base64')});uploads.finish({uploadId:id});const [file]=uploads.resolve([id],'t');
 const access=new FileAccess(uploads.directory),text=attachmentText('分析文档',[file]);const snapshot={thread:{id:'t'},messages:[{id:'u',role:'user',text}]};const result=access.decorate(snapshot);
 assert.equal(result.messages[0].text,'分析文档');assert.equal(result.messages[0].attachments[0].type,'file');assert.equal(result.messages[0].attachments[0].available,true);assert.equal(result.artifacts[0].name,'code.txt');
 const outside=resolve(dir,'private.txt');writeFileSync(outside,'not shared');const blocked=access.decorate({...snapshot,messages:[{role:'user',text:attachmentText('x',[{name:'private.txt',path:outside}])}]});assert.equal(blocked.artifacts.length,0);assert.equal(blocked.messages[0].attachments,undefined);
 const cmd={id:randomUUID(),sentAt:Date.now(),action:'message.send',args:{threadId:'t',text:'',fileUploadIds:[id]}};assert.equal(validateCommand(cmd),cmd);assert.throws(()=>validateCommand({...cmd,args:{...cmd.args,fileUploadIds:[outside]}}),/标识/);
});
