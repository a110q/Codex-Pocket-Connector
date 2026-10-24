import { readFileSync } from 'node:fs';
import { resolve, basename } from 'node:path';
import { publishReleaseFile } from './release-layout.mjs';

const root = resolve(import.meta.dirname, '..');
try {
  const directory = resolve(root, 'android/app/build/outputs/apk/release');
  const metadata = JSON.parse(readFileSync(resolve(directory, 'output-metadata.json'), 'utf8'));
  if (metadata.applicationId !== 'dev.codexpocket.app' || metadata.variantName !== 'release' || metadata.elements?.length !== 1) throw new Error('Expected a single release APK');
  const entry = metadata.elements[0];
  if (entry.type !== 'SINGLE' || basename(entry.outputFile) !== entry.outputFile) throw new Error('Unexpected release APK filename');
  if (entry.versionName !== JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8')).version) throw new Error('Android and project versions differ');
  console.log(JSON.stringify(publishReleaseFile(resolve(directory, entry.outputFile), 'android', entry.versionName)));
} catch (error) { console.error(error.message); process.exitCode = 1; }
