import test from 'node:test';
import assert from 'node:assert/strict';
import { DesktopSharing, hasDesktopSocket } from '../desktop-sharing.mjs';

const bundle = '/Applications/ChatGPT.app';
const binary = `${bundle}/Contents/Resources/codex-cli/bin/codex`;
const socket = '/private/tmp/test socket';
const sockets = `ChatGPT   27  user  5u  unix 0x123      0t0      ->0xaaa
codex     45  user  6u  unix 0xaaa      0t0      ${socket}\n`;

function fixture({ platform = 'darwin', pids = [null, null, 27], reports = sockets, fail = '', selectedBinary = binary } = {}) {
  const commands = [], probes = [];
  const service = new DesktopSharing({ platform, binary: () => selectedBinary, env: { CODEX_HOME: '/test-home', CODEX_THREAD_ID: 'unrelated' } }, {
    getSocket(home) { assert.equal(home, '/test-home'); return socket; },
    async run(file, args, options) {
      commands.push({ file, args, options });
      if (file === fail) throw new Error('private diagnostics');
      if (file === '/usr/bin/lsappinfo') return { stdout: args[0] === 'find' ? (pids.shift() === 27 ? 'ASN:test\n' : '\n') : 'pid = 27\n' };
      return { stdout: file === '/usr/sbin/lsof' ? reports : '' };
    },
    client(endpoint) { assert.equal(endpoint, socket); return { async connect() { probes.push('connected'); }, close() { probes.push('closed'); } }; },
    async wait() {}, attempts: 2,
  });
  return { service, commands, probes };
}

test('launches the chosen Mac app without forcing a second instance and verifies attachment', async () => {
  const { service, commands, probes } = fixture();
  assert.deepEqual(await service.open(), { status: 'connected' });
  assert.deepEqual(probes, ['connected', 'closed']);
  const daemon = commands.find(c => c.file === binary);
  assert.deepEqual(daemon.args, ['app-server', 'daemon', 'start']);
  assert.equal(daemon.options.env.CODEX_THREAD_ID, undefined);
  assert.deepEqual(commands.find(c => c.file === '/usr/bin/open').args, ['-a', bundle, '--env', 'CODEX_HOME=/test-home', '--env', 'CODEX_APP_SERVER_WS_URL=ws+unix://localhost/private/tmp/test%20socket:/rpc']);
});

test('an existing private desktop needs normal exit; an existing shared desktop stays open', async () => {
  for (const [reports, status] of [['', 'quit-required'], [sockets, 'connected']]) {
    const { service, commands, probes } = fixture({ pids: [27], reports });
    assert.deepEqual(await service.open(), { status });
    assert(probes.length === 0 && commands.every(c => c.file !== binary && c.file !== '/usr/bin/open'));
  }
});

test('opening the app does not claim success without a matching peer socket', async () => {
  const { service } = fixture({ reports: sockets.replace('->0xaaa', '->0xbb2') });
  assert.deepEqual(await service.open(), { status: 'unconfirmed' });
  assert(!hasDesktopSocket(sockets, 50, socket));
  assert(!hasDesktopSocket(sockets, 27, `${socket}2`));
});

test('desktop opened during preparation is not restarted; daemon and launch errors are safe to retry', async () => {
  const race = fixture({ pids: [null, 27], reports: '' });
  assert.deepEqual(await race.service.open(), { status: 'quit-required' });
  assert(!race.commands.some(c => c.file === '/usr/bin/open'));
  for (const [fail, message] of [[binary, /无法启动/], ['/usr/bin/open', /未能打开/]]) {
    const {service} = fixture({fail}); await assert.rejects(service.open(), message); assert.equal(service.busy, false);
  }
});

test('non-Mac and CLI-only selections never launch an app or daemon', async () => {
  for (const options of [{ platform: 'win32' }, { selectedBinary: '/opt/codex' }]) {
    const { service, commands } = fixture(options); await assert.rejects(service.open(), /仅支持 macOS|安装位置/); assert.deepEqual(commands, []);
  }
});
