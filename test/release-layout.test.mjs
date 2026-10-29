import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, symlinkSync, rmSync } from 'node:fs';
import { resolve } from 'node:path';
import { releaseName, listPlatformReleases, prunePlatformReleases, publishReleaseFile } from '../scripts/release-layout.mjs';
import { windowsResources } from '../scripts/windows-resources.mjs';

function fixture(t) {
  mkdirSync('.private/tests', { recursive: true });
  const directory = mkdtempSync(resolve('.private/tests/release-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}
test('release retention compares version numbers and preserves unrelated files', t => {
  const directory = fixture(t);
  for (const version of ['0.4.9', '0.4.10', '0.6.0']) writeFileSync(resolve(directory, releaseName('android', version)), version);
  writeFileSync(resolve(directory, 'personal-backup.apk'), 'keep');
  const result = prunePlatformReleases('android', directory);
  assert.deepEqual(result.kept.map(r => r.version), ['0.6.0', '0.4.10']);
  assert(!existsSync(resolve(directory, releaseName('android', '0.4.9'))));
  assert.equal(readFileSync(resolve(directory, 'personal-backup.apk'), 'utf8'), 'keep');
});
test('macOS retention removes only old app bundles and refuses matching symbolic links', t => {
  const directory = fixture(t);
  for (const version of ['0.4.0', '0.5.0', '0.6.0']) mkdirSync(resolve(directory, releaseName('mac', version)));
  prunePlatformReleases('mac', directory);
  assert.deepEqual(listPlatformReleases('mac', directory).map(r => r.version), ['0.6.0', '0.5.0']);
  const external = resolve(directory, 'unrelated.app'); mkdirSync(external);
  symlinkSync(external, resolve(directory, releaseName('mac', '0.3.0')));
  assert.throws(() => prunePlatformReleases('mac', directory), /Unexpected release entry/);
  assert(existsSync(external));
});
test('publishing a portable Windows package verifies the copy before retaining two versions', t => {
  const directory = fixture(t), source = resolve(directory, 'staged.zip'), target = resolve(directory, 'windows');
  writeFileSync(source, Buffer.from([0x50, 0x4b, 3, 4, 1, 2]));
  for (const version of ['0.5.0', '0.6.0', '0.7.0']) publishReleaseFile(source, 'windows', version, target);
  assert.deepEqual(listPlatformReleases('windows', target).map(r => r.version), ['0.7.0', '0.6.0']);
  assert.deepEqual(readFileSync(resolve(target, releaseName('windows', '0.7.0'))), readFileSync(source));
  assert.throws(() => releaseName('android', '../outside'), /Invalid/);
});

test('Windows EXE and ZIP count as one release version during retention',t=>{
  const directory=fixture(t),source=resolve(directory,'source'),out=resolve(directory,'windows');writeFileSync(source,'fixture');
  for(const version of ['0.6.1','0.7.0','0.8.0'])for(const variant of [undefined,'exe'])publishReleaseFile(source,'windows',version,out,variant);
  const releases=listPlatformReleases('windows',out);assert.equal(releases.length,4);assert.deepEqual([...new Set(releases.map(r=>r.version))],['0.8.0','0.7.0']);
});

test('three and four component releases sort numerically and paired Windows artifacts retain two versions', t => {
  const directory=fixture(t),source=resolve(directory,'source'),out=resolve(directory,'windows');writeFileSync(source,'fixture');
  for(const version of ['0.9.8','0.9.8.1','0.9.8.2','0.9.8.10'])for(const variant of [undefined,'exe'])publishReleaseFile(source,'windows',version,out,variant);
  assert.deepEqual(listPlatformReleases('windows',out).map(r=>r.version),['0.9.8.10','0.9.8.10','0.9.8.2','0.9.8.2']);
  for(const variant of [undefined,'exe'])publishReleaseFile(source,'windows','0.9.9',out,variant);
  assert.deepEqual([...new Set(listPlatformReleases('windows',out).map(r=>r.version))],['0.9.9','0.9.8.10']);
  for(const invalid of ['0.9.8.01','0.9.08.1','0.9.8.1.1','0.9.8.','0.9.8-patch.1','../0.9.8.1'])assert.throws(()=>releaseName('android',invalid),/Invalid/);
  const android=resolve(directory,'android');mkdirSync(android);
  for(const version of ['0.9.8','0.9.8.1'])writeFileSync(resolve(android,releaseName('android',version)),version);
  assert.deepEqual(listPlatformReleases('android',android).map(r=>r.version),['0.9.8.1','0.9.8']);
});

test('Windows manifest and binary file version preserve the fourth component', t => {
  const directory=fixture(t),icon=resolve(directory,'icon.ico'),output=resolve(directory,'app.res');
  const ico=Buffer.alloc(26);ico.writeUInt16LE(1,2);ico.writeUInt16LE(1,4);ico.writeUInt32LE(4,14);ico.writeUInt32LE(22,18);writeFileSync(icon,ico);
  for(const [version,low] of [['0.9.8',8*65536],['0.9.8.1',8*65536+1]]){
    windowsResources({output,icon,version});const bytes=readFileSync(output),signature=bytes.indexOf(Buffer.from('bd04effe','hex'));
    assert(signature>=0);assert.equal(bytes.readUInt32LE(signature+8),9);assert.equal(bytes.readUInt32LE(signature+12),low);
    assert.equal(bytes.readUInt32LE(signature+20),low);
    assert(bytes.includes(Buffer.from(`version="${version.split('.').length===3?version+'.0':version}" processorArchitecture`)));
    assert(!bytes.includes(Buffer.from('version="0.9.8.1.0"')));
  }
  assert.throws(()=>windowsResources({output,icon,version:'0.9.65536.1'}),/65535/);
});
