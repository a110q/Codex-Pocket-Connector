import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { relayRequest } from '../companion.mjs';
import { validateDefaults } from '../connector-core.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2), operation = args.shift();
function arg(name, fallback) { const index = args.indexOf(name); if (index < 0) return fallback; if (!args[index + 1] || args[index + 1].startsWith('--')) throw new Error(`${name} 缺少参数`); return args[index + 1]; }
try {
  if (!['invite', 'list', 'revoke'].includes(operation)) throw new Error('用法：relay-admin.mjs invite --out <私有文件> [--label 名称] [--hours 24]；或 list；或 revoke --id <邀请码 ID>');
  const config = JSON.parse(readFileSync(arg('--config', resolve(root, '.private/companion.json')), 'utf8'));
  const defaults = validateDefaults(config);
  const admin = readFileSync(arg('--admin-file', resolve(root, '.private/relay-admin.txt')), 'utf8').trim();
  if (operation === 'invite') {
    const output = arg('--out'); if (!output) throw new Error('请用 --out 指定邀请码私有文件，邀请码不会打印到终端');
    mkdirSync(dirname(resolve(output)), { recursive: true, mode: 0o700 });
    // Reserve the destination before issuing a new invitation; never overwrite files.
    writeFileSync(output, '', { flag: 'wx', mode: 0o600 });
    const result = await relayRequest(defaults, '/api/invites', { label: arg('--label', ''), hours: Number(arg('--hours', '24')) }, admin);
    writeFileSync(output, `Codex Pocket 电脑注册邀请\n\n一次性邀请码：\n${result.code}\n\n有效期至：${new Date(result.expiresAt).toISOString()}\n服务器：${defaults.relay}\n邀请码 ID：${result.id}\n\n安装电脑连接器，在第一步粘贴邀请码。每个邀请码只注册一台电脑。\n`, { mode: 0o600 });
    console.log(JSON.stringify({ id: result.id, expiresAt: new Date(result.expiresAt).toISOString(), savedTo: resolve(output) }));
  } else if (operation === 'list') console.log(JSON.stringify(await relayRequest(defaults, '/api/invites/list', {}, admin), null, 2));
  else {
    const id = arg('--id'); if (!id) throw new Error('请指定 --id');
    console.log(JSON.stringify(await relayRequest(defaults, '/api/invites/revoke', { id }, admin)));
  }
} catch (error) { console.error(error.message); process.exitCode = 1; }
