import test from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
function launch(t) {
  const base = resolve(root, '.private/tests');
  mkdirSync(base, { recursive: true });
  const fixture = mkdtempSync(resolve(base, 'entry-'));
  t.after(() => rmSync(fixture, { recursive: true, force: true }));
  const state = resolve(fixture, 'local state');
  mkdirSync(state);
  const descriptor = resolve(state, 'ready.json');
  const config = resolve(state, 'connector.json');
  const run = entry => {
    const child = spawnSync(process.execPath, [entry, '--native', '--descriptor', descriptor], {
      cwd: fixture, env: { ...process.env, POCKET_STATE_DIR: state, POCKET_MANAGER_PORT: '43787' }, encoding: 'utf8', timeout: 15000,
    });
    assert.ifError(child.error);
    assert.equal(child.stdout, '');
    assert(!existsSync(descriptor));
    return child;
  };
  return { fixture, config, run };
}

test('native connector executes its entry point through a directory alias', t => {
  const { fixture, config, run } = launch(t);
  const alias = resolve(fixture, 'Codex Pocket alias');
  // Use an NTFS junction on Windows, where symlinks can require elevation.
  symlinkSync(root, alias, process.platform === 'win32' ? 'junction' : 'dir');
  const corrupt = '{"token":"diagnostic-must-not-disclose-this",';
  writeFileSync(config, corrupt);
  const child = run(resolve(alias, 'connector.mjs'));
  assert.equal(child.status, 1);
  assert.equal(child.stderr.trim(), 'POCKET_STARTUP_ERROR:LOCAL_CONFIG:INVALID_JSON');
  assert.equal(readFileSync(config, 'utf8'), corrupt);
});

test('native connector reports a missing module without leaking the absolute path', t => {
  const { fixture, run } = launch(t);
  const entry = resolve(fixture, 'connector.mjs');
  copyFileSync(resolve(root, 'connector.mjs'), entry);
  const child = run(entry);
  assert.equal(child.status, 1);
  assert.equal(child.stderr.trim(), 'POCKET_STARTUP_ERROR:BACKEND_IMPORT:ERR_MODULE_NOT_FOUND');
});
