import { readFileSync } from 'node:fs';

export const VERSION_PATTERN = '(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)(?:\\.(?:0|[1-9]\\d*))?';
export function versionParts(version) {
  if (typeof version !== 'string' || !new RegExp(`^${VERSION_PATTERN}$`).test(version)) throw new Error('Invalid release version');
  return version.split('.').map(BigInt);
}
export function compareVersions(a, b) {
  const left = versionParts(a), right = versionParts(b);
  for (let i = 0; i < 4; i++) if ((left[i] ?? 0n) !== (right[i] ?? 0n)) return (left[i] ?? 0n) > (right[i] ?? 0n) ? 1 : -1;
  return 0;
}
const manifest = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8'));
export const PRODUCT_VERSION = manifest.pocketVersion ?? manifest.version;
export const BUILD_NUMBER = manifest.pocketBuild ?? 1;
versionParts(PRODUCT_VERSION);
