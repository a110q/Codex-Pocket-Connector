import { readFileSync } from 'node:fs';

export const VERSION_PATTERN = '(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)(?:\\.(?:0|[1-9]\\d*))?';
export function versionParts(version) {
  if (typeof version !== 'string' || !new RegExp(`^${VERSION_PATTERN}$`).test(version)) throw new Error('Invalid release version');
  return version.split('.').map(BigInt);
}
const manifest = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8'));
export const PRODUCT_VERSION = manifest.pocketVersion ?? manifest.version;
export const BUILD_NUMBER = manifest.pocketBuild ?? 1;
versionParts(PRODUCT_VERSION);
