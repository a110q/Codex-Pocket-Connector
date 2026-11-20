import { BUILD_NUMBER, PRODUCT_VERSION } from '../version.mjs';
import { readFileSync } from 'node:fs';
import { resolve, basename } from 'node:path';
import { readJson, validateDefaults } from '../connector-core.mjs';
import { verifyAndroidRelay } from './verify-android-relay.mjs';
import { publishReleaseFile } from './release-layout.mjs';

const root = resolve(import.meta.dirname, '..');
try {
  const directory = resolve(root, 'android/app/build/outputs/apk/release');
  const metadata = JSON.parse(readFileSync(resolve(directory, 'output-metadata.json'), 'utf8'));
  if (metadata.applicationId !== 'dev.codexpocket.app' || metadata.variantName !== 'release' || metadata.elements?.length !== 1) throw new Error('Expected a single release APK');
  const entry = metadata.elements[0];
  if (entry.type !== 'SINGLE' || basename(entry.outputFile) !== entry.outputFile) throw new Error('Unexpected release APK filename');
  if (entry.versionName !== PRODUCT_VERSION || entry.versionCode !== BUILD_NUMBER) throw new Error('Android and project versions differ');
  const apk = resolve(directory, entry.outputFile);
  if (process.env.POCKET_OFFICIAL_RELEASE === '1') {
    verifyAndroidRelay(apk, validateDefaults(readJson(resolve(root, '.private/companion.json'))));
  }
  console.log(JSON.stringify(publishReleaseFile(apk, 'android', entry.versionName)));
} catch (error) { console.error(error.message); process.exitCode = 1; }
