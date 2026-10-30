import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { mkdirSync, mkdtempSync, writeFileSync, rmSync, symlinkSync } from 'node:fs';
import { localFilePath, linkedFilePaths } from '../file-links.mjs';
import { FileAccess } from '../companion.mjs';

test('local Markdown files accept URLs, editor suffixes, encoded spaces and Windows paths', () => {
  assert.equal(localFilePath('file:///project/design%20review.html#section', '/project'), '/project/design review.html');
  assert.equal(localFilePath('./pages/a.html:12:3', '/project'), '/project/pages/a.html');
  assert.equal(localFilePath('file://localhost/project/a.html', '/project'), '/project/a.html');
  assert.equal(localFilePath('file:///C:/work/review%20page.html', 'C:\\work', path.win32), 'C:\\work\\review page.html');
  assert.equal(localFilePath('C:\\work\\review.html:12', 'C:\\work', path.win32), 'C:\\work\\review.html');
  assert.equal(localFilePath('pages/review.html', 'C:\\work', path.win32), 'C:\\work\\pages\\review.html');
  for (const value of ['https://example.com/page.html','javascript:alert(1)','file://remote/etc/passwd','bad%XX.html','a\0b']) assert.equal(localFilePath(value, '/project'), null);
  assert.deepEqual(linkedFilePaths('[审核](<./design review.html>) [代码](./code.txt:10 "标题") [括号](./a\\(b\\).html) [网页](https://example.com)', '/project'), ['/project/design review.html','/project/code.txt','/project/a(b).html']);
});
test('resolve refreshes authorization for relative HTML links and refuses unrelated files', t => {
  mkdirSync('.private/tests',{recursive:true});const base=mkdtempSync(path.resolve('.private/tests/file-links-'));t.after(()=>rmSync(base,{recursive:true,force:true}));
  const root=path.join(base,'project');mkdirSync(root);writeFileSync(path.join(root,'review page.html'),'<h1>Review</h1>');writeFileSync(path.join(root,'unlinked.html'),'private');writeFileSync(path.join(base,'outside.html'),'secret');symlinkSync(path.join(base,'outside.html'),path.join(root,'escape.html'));
  const snapshot={thread:{id:'qa',cwd:root},messages:[{role:'assistant',text:'[审核](./review%20page.html) [越界](../outside.html) [符号链接](./escape.html)'}]};
  const files=new FileAccess();const artifact=files.resolve(snapshot,{link:'./review%20page.html:10'}).artifact;
  assert.equal(artifact.mime,'text/html');assert.equal(files.authorized({threadId:'qa',artifactId:artifact.id}).path,path.join(root,'review page.html'));
  assert.throws(()=>files.resolve(snapshot,{link:'unlinked.html'}),/授权/);
  assert.throws(()=>files.resolve(snapshot,{link:'../outside.html'}),/授权/);
  assert.throws(()=>files.resolve(snapshot,{link:'escape.html'}),/授权/);
  assert.throws(()=>files.authorized({threadId:'other',artifactId:artifact.id}),/授权/);
  writeFileSync(artifact.path,'changed and longer');assert.throws(()=>files.authorized({threadId:'qa',artifactId:artifact.id}),/变化/);
});
