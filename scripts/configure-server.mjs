import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { validateDefaults } from '../connector-core.mjs';

const root = resolve(import.meta.dirname, '..');
const args = process.argv.slice(2);
const option = name => { const index = args.indexOf(name); return index < 0 ? undefined : args[index + 1]; };
try {
  const relay = option('--relay'), certificate = option('--cert');
  if (!relay || !certificate || relay.includes('XX')) throw new Error('Usage: node scripts/configure-server.mjs --relay https://YOUR_HOST:24443 --cert /path/to/public-cert.pem [--signing-sha256 HEX]');
  const signingSha256 = option('--signing-sha256')?.toLowerCase() ?? '';
  if (signingSha256 && !/^[a-f0-9]{64}$/.test(signingSha256)) throw new Error('APK signing SHA-256 must contain 64 hexadecimal characters');
  const caCert = readFileSync(resolve(certificate), 'utf8');
  if (/PRIVATE KEY/.test(caCert)) throw new Error('Use the public TLS certificate, never its private key');
  const defaults = validateDefaults({ relay, caCert });
  mkdirSync(resolve(root, '.private'), { recursive: true, mode: 0o700 });
  mkdirSync(resolve(root, 'android/app/src/main/assets'), { recursive: true });
  for (const file of ['.private/client-defaults.json', 'connector-defaults.json']) writeFileSync(resolve(root, file), JSON.stringify(defaults, null, 2) + '\n', { mode: 0o600 });
  writeFileSync(resolve(root, 'android/app/src/main/assets/server.json'), JSON.stringify({ relay: defaults.relay, pin: defaults.pin, signingSha256 }, null, 2) + '\n');
  console.log('Client defaults generated. Only public server information was written; no account, administrator or pairing credentials were copied.');
} catch (error) { console.error(error.message); process.exitCode = 1; }
