import test from 'node:test';
import assert from 'node:assert/strict';
import { connectorStateDirectory, findDesktopEndpoints, openBrowser, processIdentity, windowsConnectionCommand } from '../platform.mjs';

test('connector state remains per-user on Windows and honors explicit absolute paths', () => {
  assert.equal(connectorStateDirectory({ platform: 'win32', env: { LOCALAPPDATA: 'C:\\Users\\User Name\\AppData\\Local' }, home: 'C:\\Users\\User Name' }), 'C:\\Users\\User Name\\AppData\\Local\\Codex Pocket');
  assert.equal(connectorStateDirectory({ platform: 'win32', env: {}, home: 'D:\\Users\\Test' }), 'D:\\Users\\Test\\AppData\\Local\\Codex Pocket');
  assert.equal(connectorStateDirectory({ platform: 'win32', env: { POCKET_STATE_DIR: 'D:\\Private State' }, home: 'C:\\Users\\Test' }), 'D:\\Private State');
  assert.throws(() => connectorStateDirectory({ platform: 'win32', env: { POCKET_STATE_DIR: 'relative' } }), /absolute/);
  assert.equal(connectorStateDirectory({ platform: 'darwin', env: {}, home: '/Users/test' }), '/Users/test/Library/Application Support/Codex Pocket');
});

test('Windows endpoints use the observed fixed IPC and explicit tools context without pipe enumeration', async () => {
  let enumerations = 0;
  const readDirectory = () => { enumerations++; throw new Error('must not enumerate'); };
  const env = { CODEX_APP_TOOLS_PIPE_PATH: '\\\\.\\pipe\\codex-app-tools-11111111-2222-3333-4444-555555555555' };
  const found = await findDesktopEndpoints({ platform: 'win32', env, readDirectory });
  assert.equal(found.ipcPath, '\\\\.\\pipe\\codex-ipc'); assert.equal(found.toolsPath, env.CODEX_APP_TOOLS_PIPE_PATH);
  const explicit = await findDesktopEndpoints({ platform: 'win32', env: { ...env, POCKET_CODEX_IPC_PATH: '\\\\.\\pipe\\codex-private-ipc' }, readDirectory });
  assert.equal(explicit.ipcPath, '\\\\.\\pipe\\codex-private-ipc'); assert.equal(explicit.source, 'explicit-environment');
  await assert.rejects(findDesktopEndpoints({ platform: 'win32', env: {}, readDirectory }), /内置终端/);
  for (const untrusted of ['\\\\other-computer\\pipe\\codex-app-tools', 'C:\\socket', '\\\\.\\pipe\\..\\other', '\\\\.\\pipe\\bad\npipe']) {
    await assert.rejects(findDesktopEndpoints({ platform: 'win32', env: { CODEX_APP_TOOLS_PIPE_PATH: untrusted }, readDirectory }), /本机/);
  }
  assert.equal(enumerations, 0);
});

test('Unix discovery rejects ambiguous, stale, foreign and symbolic socket candidates', async () => {
  const statPath = async candidate => {
    if (candidate.endsWith('/stale.sock')) throw new Error('ENOENT');
    return { uid: candidate.endsWith('/foreign.sock') ? 202 : 101, isSocket: () => !candidate.endsWith('/symbolic.sock') };
  };
  const options = { platform: 'darwin', env: {}, codexHome: '/private/test-codex', uid: 101, statPath };
  const found = await findDesktopEndpoints({ ...options, readDirectory: async () => ['own.sock', 'foreign.sock', 'stale.sock', 'symbolic.sock', '../escape.sock'] });
  assert.equal(found.ipcPath, '/private/test-codex/ipc/ipc.sock'); assert.equal(found.toolsPath, '/tmp/codex-app-tools/own.sock');
  await assert.rejects(findDesktopEndpoints({ ...options, readDirectory: async () => ['one.sock', 'two.sock'] }), /CODEX_APP_TOOLS_PIPE_PATH/);
});

test('Windows browser opening encodes a literal URL and fails on OS errors', () => {
  let call;
  const url = 'http://127.0.0.1:43786/#a\';&$(Write-Output%20bad)';
  openBrowser(url, { platform: 'win32', env: { SystemRoot: 'C:\\Windows' }, run: (...args) => { call = args; return { status: 0 }; } });
  assert.equal(call[0], 'C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe');
  assert.ok(call[1].includes('-EncodedCommand')); assert.ok(!call[1].includes(url));
  const script = Buffer.from(call[1].at(-1), 'base64').toString('utf16le');
  assert.match(script, /Start-Process -FilePath 'http:/); assert.match(script, /a'';&\$\(Write-Output/);
  assert.throws(() => openBrowser('file:///C:/Windows/System32/cmd.exe', { platform: 'win32' }), /HTTP/);
  assert.throws(() => openBrowser('https://example.com', { platform: 'win32', run: () => ({ status: 1 }) }), /默认浏览器/);
});

test('process ownership identity changes when a PID is reused and never trusts inaccessible processes', () => {
  const win = created => ({ status: 0, stdout: JSON.stringify({ pid: 123, created, executable: 'C:\\Codex\\node.exe', command: 'node companion.mjs' }) });
  const first = processIdentity(123, { platform: 'win32', run: () => win('2026-10-24T01:02:03Z') });
  const second = processIdentity(123, { platform: 'win32', run: () => win('2026-10-24T01:02:04Z') });
  assert.match(first, /^[a-f0-9]{64}$/); assert.notEqual(first, second);
  assert.equal(processIdentity(-1), null);
  assert.equal(processIdentity(123, { platform: 'win32', run: () => ({ status: 1, stdout: '' }) }), null);
  assert.equal(processIdentity(123, { platform: 'win32', run: () => ({ status: 0, stdout: '{"pid":123}' }) }), null);
  assert.equal(processIdentity(123, { platform: 'win32', run: () => ({ status: 0, stdout: '{bad}' }) }), null);
});

test('host process identity can verify the current service process consistently', () => {
  const identity = processIdentity(process.pid);
  assert.ok(identity);
  assert.equal(processIdentity(process.pid), identity);
  assert.equal(processIdentity(2147483647), null);
});

test('Windows connection commands quote paths, retain desktop context and limit execution policy to child process', () => {
  const command = windowsConnectionCommand("C:\\Users\\O'Brien\\Pocket", "D:\\Pocket's State", '11111111-2222-3333-4444-555555555555');
  assert.match(command, /POCKET_STATE_DIR='D:\\Pocket''s State'/);
  assert.match(command, /CODEX_DESKTOP_CONTEXT_THREAD_ID='11111111/);
  assert.match(command, /-ExecutionPolicy Bypass -File 'C:\\Users\\O''Brien\\Pocket\\scripts\\connect-installed.ps1'/);
  assert.doesNotMatch(command, /Set-ExecutionPolicy/);
  assert.throws(() => windowsConnectionCommand('relative', 'C:\\state'), /absolute/);
  assert.throws(() => windowsConnectionCommand('C:\\Pocket', 'C:\\state', 'bad; Write-Host pwn'), /聊天 ID/);
});
