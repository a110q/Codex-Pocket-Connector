import { PRODUCT_VERSION, BUILD_NUMBER, versionParts } from '../version.mjs';
import { cpSync, existsSync, readFileSync, writeFileSync, mkdirSync, chmodSync, lstatSync, renameSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { validateDefaults } from '../connector-core.mjs';
import { releaseDirectory, releaseName, prunePlatformReleases } from './release-layout.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const arg = (key, fallback) => args.includes(key) ? args[args.indexOf(key) + 1] : fallback;
const out = resolve(arg('--out', releaseDirectory('mac')));
const stage = resolve(root, '.private', `connector-build-${Date.now()}`);
const appName = 'Codex Pocket Connector.app', app = resolve(stage, 'payload/Applications', appName), resources = resolve(app, 'Contents/Resources'), bundle = resolve(resources, 'connector');
const version = PRODUCT_VERSION;
function run(cmd, argv) { const result = spawnSync(cmd, argv, { encoding: 'utf8' }); if (result.status !== 0) throw new Error(`${cmd}: ${result.stderr || result.error || result.stdout}`); }
try {
  if (process.platform !== 'darwin') throw new Error('电脑安装包需要在 macOS 构建');
  const defaults = validateDefaults(JSON.parse(readFileSync(arg('--config', resolve(root, '.private/companion.json')), 'utf8')));
  mkdirSync(bundle, { recursive: true }); mkdirSync(resolve(app, 'Contents/MacOS'), { recursive: true }); mkdirSync(out, { recursive: true });
  const files = ['version.mjs', 'pocket-scheduler.mjs', 'extensions-tools.mjs', 'workspace-tools.mjs', 'file-uploads.mjs', 'approvals.mjs', 'image-uploads.mjs', 'relay-profiles.mjs', 'companion.mjs', 'connector.mjs', 'connector-core.mjs', 'connector-runtime.mjs', 'app-server-client.mjs', 'codex-installation.mjs', 'standalone-adapter.mjs', 'desktop-owner-bridge.mjs', 'platform.mjs', 'lan.mjs', 'relay.mjs', 'accounts.mjs', 'desktop-adapter.mjs', 'history.mjs', 'protocol.mjs', 'relay-connection.mjs', 'preview-tunnel.mjs', 'manager.html', 'connector.html', 'package.json', 'package-lock.json', 'LICENSE', 'THIRD_PARTY_NOTICES.md'];
  for (const file of files) cpSync(resolve(root, file), resolve(bundle, file));
  for (const file of ['desktop-service.mjs', 'find-codex-node.sh', 'connect-installed.command']) {
    mkdirSync(resolve(bundle, 'scripts'), { recursive: true }); cpSync(resolve(root, 'scripts', file), resolve(bundle, 'scripts', file)); chmodSync(resolve(bundle, 'scripts', file), 0o755);
  }
  mkdirSync(resolve(bundle, 'design/brand'), { recursive: true });
  cpSync(resolve(root, 'android/app/src/main/res/drawable-nodpi/codex_mark.png'), resolve(bundle, 'design/brand/codex-pocket-logo.png'));
  // Only production dependencies from the lockfile; never copy task private state.
  const lock = JSON.parse(readFileSync(resolve(root, 'package-lock.json'), 'utf8'));
  for (const [path, info] of Object.entries(lock.packages)) {
    if (!path.startsWith('node_modules/') || info.dev) continue;
    cpSync(resolve(root, path), resolve(bundle, path), { recursive: true, filter: source => !source.includes('/.bin/') });
  }
  writeFileSync(resolve(bundle, 'connector-defaults.json'), JSON.stringify(defaults, null, 2));
  // A real AppKit event loop is required by LaunchServices. A long-running
  // shell executable can open a browser yet still be reported as unresponsive.
  const nativeSource = resolve(root, 'desktop/mac/Connector.swift');
  const arm = resolve(stage, 'Connector-arm64'), intel = resolve(stage, 'Connector-x86_64');
  run('/usr/bin/xcrun', ['swiftc', nativeSource, '-O', '-target', 'arm64-apple-macos13.0', '-framework', 'Cocoa', '-framework', 'WebKit', '-o', arm]);
  run('/usr/bin/xcrun', ['swiftc', nativeSource, '-O', '-target', 'x86_64-apple-macos13.0', '-framework', 'Cocoa', '-framework', 'WebKit', '-o', intel]);
  run('/usr/bin/lipo', ['-create', arm, intel, '-output', resolve(app, 'Contents/MacOS/Connector')]);
  const plist = `<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>dev.codexpocket.connector</string><key>CFBundleName</key><string>Codex Pocket Connector</string><key>CFBundleDisplayName</key><string>Codex Pocket Connector</string><key>CFBundleExecutable</key><string>Connector</string><key>CFBundlePackageType</key><string>APPL</string><key>CFBundleShortVersionString</key><string>${version.split('.').slice(0,3).join('.')}</string><key>CFBundleVersion</key><string>${BUILD_NUMBER}</string><key>PocketReleaseVersion</key><string>${version}</string><key>CFBundleIconFile</key><string>Pocket.icns</string><key>LSUIElement</key><true/><key>LSMinimumSystemVersion</key><string>13.0</string></dict></plist>`;
  writeFileSync(resolve(app, 'Contents/Info.plist'), plist.replace('<key>LSUIElement</key><true/>','<key>NSHighResolutionCapable</key><true/><key>NSAppTransportSecurity</key><dict><key>NSAllowsLocalNetworking</key><true/></dict>'));
  const iconset = resolve(stage, 'Pocket.iconset'); mkdirSync(iconset);
  for (const size of [16,32,128,256,512]) for (const scale of [1,2]) run('/usr/bin/sips', ['-z', String(size*scale), String(size*scale), resolve(bundle, 'design/brand/codex-pocket-logo.png'), '--out', resolve(iconset, `icon_${size}x${size}${scale===2?'@2x':''}.png`)]);
  run('/usr/bin/iconutil', ['-c','icns',iconset,'-o',resolve(resources,'Pocket.icns')]);
  run('/usr/bin/codesign', ['--force','--sign','-',app]);
  run('/usr/bin/codesign', ['--verify','--strict',app]);
  const publishedApp = resolve(out, releaseName('mac', version));
  if (existsSync(publishedApp) && (!lstatSync(publishedApp).isDirectory() || lstatSync(publishedApp).isSymbolicLink())) throw new Error('Unexpected existing app target');
  const temporaryApp = resolve(out, `.building-${Date.now()}.app`);
  run('/usr/bin/ditto', [app, temporaryApp]);
  run('/usr/bin/codesign', ['--verify', '--strict', temporaryApp]);
  if (existsSync(publishedApp)) renameSync(publishedApp, resolve(stage, 'previous.app'));
  renameSync(temporaryApp, publishedApp);
  const retention = prunePlatformReleases('mac', out);
  console.log(JSON.stringify({ version, app: publishedApp, bundle: resolve(publishedApp, 'Contents/Resources/connector'), ...retention, signing: 'ad-hoc; not Apple notarized', runtime: 'uses the installed Codex desktop bundled Node; no separate Node installation' }));
} catch (error) { console.error(error.message); process.exitCode = 1; }
