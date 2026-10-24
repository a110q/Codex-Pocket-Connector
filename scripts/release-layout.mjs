import { cpSync, existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync } from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const versionPattern = '(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)';
const formats = {
  mac: { prefix: 'Codex-Pocket-Connector-', suffix: '.app', directory: true },
  windows: { prefix: 'Codex-Pocket-Connector-', suffix: '-Windows.zip' },
  android: { prefix: 'Codex-Pocket-', suffix: '.apk' },
};
const escape = text => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
function format(platform) {
  const entry = formats[platform];
  if (!entry) throw new Error('Unknown release platform');
  return entry;
}
export function releaseDirectory(platform) { format(platform); return resolve(root, 'releases', platform); }
export function releaseName(platform, version, variant) {
  const entry = format(platform);
  if (!new RegExp(`^${versionPattern}$`).test(version)) throw new Error('Invalid release version');
  return `${entry.prefix}${version}${platform==='windows'&&variant==='exe'?'-Windows.exe':entry.suffix}`;
}
export function listPlatformReleases(platform, directory = releaseDirectory(platform)) {
  const entry = format(platform);
  if (!existsSync(directory)) return [];
  const pattern = new RegExp(`^${escape(entry.prefix)}${versionPattern}${platform==='windows'?'-Windows\\.(zip|exe)':escape(entry.suffix)}$`);
  const releases = [];
  for (const candidate of readdirSync(directory, { withFileTypes: true })) {
    const match = candidate.name.match(pattern);
    if (!match) continue;
    if (candidate.isSymbolicLink() || (entry.directory ? !candidate.isDirectory() : !candidate.isFile())) throw new Error(`Unexpected release entry: ${candidate.name}`);
    releases.push({ name: candidate.name, path: resolve(directory, candidate.name), version: match.slice(1,4).join('.') });
  }
  return releases.sort((a, b) => {
    const av = a.version.split('.').map(BigInt), bv = b.version.split('.').map(BigInt);
    for (let i = 0; i < 3; i++) if (av[i] !== bv[i]) return av[i] > bv[i] ? -1 : 1;
    return 0;
  });
}
export function prunePlatformReleases(platform, directory = releaseDirectory(platform)) {
  const releases = listPlatformReleases(platform, directory);
  const versions=[...new Set(releases.map(r=>r.version))].slice(0,2);
  const kept=releases.filter(r=>versions.includes(r.version)), removed=releases.filter(r=>!versions.includes(r.version));
  for (const entry of removed) rmSync(entry.path, { recursive: !!format(platform).directory });
  return { kept, removed };
}
export function publishReleaseFile(source, platform, version, directory = releaseDirectory(platform), variant) {
  if (format(platform).directory) throw new Error('Use the macOS app builder for application bundles');
  if (!lstatSync(source).isFile()) throw new Error('Release source must be a regular file');
  mkdirSync(directory, { recursive: true });
  const destination = resolve(directory, releaseName(platform, version, variant));
  if (existsSync(destination) && !lstatSync(destination).isFile()) throw new Error('Release target must be a regular file');
  const temporary = resolve(directory, `.release-${randomUUID()}.partial`);
  try {
    cpSync(source, temporary);
    const hash = path => createHash('sha256').update(readFileSync(path)).digest('hex');
    if (hash(source) !== hash(temporary)) throw new Error('Release copy checksum mismatch');
    renameSync(temporary, destination);
  } finally { if (existsSync(temporary)) rmSync(temporary); }
  const retention = prunePlatformReleases(platform, directory);
  return { path: destination, ...retention };
}
