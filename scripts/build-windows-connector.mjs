import { PRODUCT_VERSION, BUILD_NUMBER, versionParts } from '../version.mjs';
import { cpSync, readFileSync, writeFileSync, mkdirSync, readdirSync, rmSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { PNG } from 'pngjs';
import { validateDefaults } from '../connector-core.mjs';
import { windowsResources } from './windows-resources.mjs';
import { releaseDirectory, publishReleaseFile } from './release-layout.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const argv = process.argv.slice(2);
const arg = (name, fallback) => argv.includes(name) ? argv[argv.indexOf(name) + 1] : fallback;

function copyText(source, target) {
  // Windows PowerShell 5.1 needs a BOM for non-ASCII scripts and CRLF for cmd.
  let text = readFileSync(source, 'utf8').replace(/^\uFEFF/, '').replace(/\r?\n/g, '\r\n');
  if (source.endsWith('.ps1')) text = `\uFEFF${text}`;
  writeFileSync(target, text);
}

function icon(source, target) {
  const input = PNG.sync.read(readFileSync(source)), output = new PNG({ width: 256, height: 256 });
  for (let y = 0; y < 256; y++) for (let x = 0; x < 256; x++) {
    const offset = (Math.floor(y * input.height / 256) * input.width + Math.floor(x * input.width / 256)) * 4;
    input.data.copy(output.data, (y * 256 + x) * 4, offset, offset + 4);
  }
  const image = PNG.sync.write(output), header = Buffer.alloc(22);
  header.writeUInt16LE(1, 2); header.writeUInt16LE(1, 4); // one PNG-compressed 256px icon
  header.writeUInt16LE(1, 10); header.writeUInt16LE(32, 12); header.writeUInt32LE(image.length, 14); header.writeUInt32LE(22, 18);
  writeFileSync(target, Buffer.concat([header, image]));
}

function run(command, args, cwd) {
  const result = spawnSync(command, args, { cwd, encoding: 'utf8', windowsHide: true });
  if (result.error || result.status !== 0) throw new Error(`Build failed: ${result.error?.message || result.stderr || result.stdout}`);
}

export function buildWindowsConnector({ out = releaseDirectory('windows'), config = resolve(root, '.private/companion.json'), version = PRODUCT_VERSION } = {}) {
  versionParts(version);
  const defaults = validateDefaults(JSON.parse(readFileSync(config, 'utf8')));
  const stage = resolve(root, '.private', `windows-connector-build-${Date.now()}`), name = `Codex-Pocket-Connector-${version}-Windows`, directory = resolve(stage, name), bundle = resolve(directory, 'app');
  mkdirSync(resolve(bundle, 'scripts'), { recursive: true }); mkdirSync(resolve(directory, 'scripts'), { recursive: true }); mkdirSync(out, { recursive: true });
  const files = ['version.mjs', 'pocket-scheduler.mjs', 'extensions-tools.mjs', 'workspace-tools.mjs', 'file-uploads.mjs', 'approvals.mjs', 'image-uploads.mjs', 'relay-profiles.mjs', 'companion.mjs', 'connector.mjs', 'connector-core.mjs', 'connector-runtime.mjs', 'app-server-client.mjs', 'codex-installation.mjs', 'standalone-adapter.mjs', 'desktop-owner-bridge.mjs', 'platform.mjs', 'lan.mjs', 'relay.mjs', 'accounts.mjs', 'desktop-adapter.mjs', 'history.mjs', 'protocol.mjs', 'relay-connection.mjs', 'preview-tunnel.mjs', 'manager.html', 'connector.html', 'package.json', 'package-lock.json', 'LICENSE', 'THIRD_PARTY_NOTICES.md'];
  for (const file of files) cpSync(resolve(root, file), resolve(bundle, file));
  const manifest = JSON.parse(readFileSync(resolve(bundle, 'package.json'), 'utf8')); manifest.pocketVersion = version; writeFileSync(resolve(bundle, 'package.json'), JSON.stringify(manifest, null, 2));
  for (const file of ['desktop-service.mjs', 'find-codex-node.ps1', 'connect-installed.ps1', 'start-connector.ps1', 'native-window.ps1']) {
    const target = resolve(bundle, 'scripts', file);
    file.endsWith('.ps1') ? copyText(resolve(root, 'scripts', file), target) : cpSync(resolve(root, 'scripts', file), target);
  }
  writeFileSync(resolve(bundle, 'Open-Codex-Pocket.cmd'), '@echo off\r\nstart "" "%SystemRoot%\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" -STA -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "%~dp0scripts\\native-window.ps1"\r\n');
  cpSync(resolve(root, 'desktop/windows/webview2'), resolve(bundle, 'native/webview2'), { recursive: true });
  writeFileSync(resolve(directory, 'Open-Codex-Pocket.cmd'), '@echo off\r\ncall "%~dp0app\\Open-Codex-Pocket.cmd"\r\n');
  copyText(resolve(root, 'scripts/install-windows.ps1'), resolve(directory, 'scripts/install-windows.ps1'));
  copyText(resolve(root, 'scripts/install-windows.cmd'), resolve(directory, 'Install-for-this-user.cmd'));
  mkdirSync(resolve(bundle, 'design/brand'), { recursive: true });
  const logo = resolve(root, 'android/app/src/main/res/drawable-nodpi/codex_mark.png');
  cpSync(logo, resolve(bundle, 'design/brand/codex-pocket-logo.png')); icon(logo, resolve(bundle, 'Pocket.ico'));
  const lock = JSON.parse(readFileSync(resolve(root, 'package-lock.json'), 'utf8'));
  for (const [name, metadata] of Object.entries(lock.packages)) {
    if (!name.startsWith('node_modules/') || metadata.dev) continue;
    if (/\.\.|[\\]/.test(name)) throw new Error('Unexpected dependency path');
    cpSync(resolve(root, name), resolve(bundle, name), { recursive: true, filter: source => !source.includes('/.bin/') });
  }
  writeFileSync(resolve(bundle, 'connector-defaults.json'), JSON.stringify(defaults, null, 2));
  cpSync(resolve(root, 'docs/USER_GUIDE.md'), resolve(directory, 'Quick-Start.md'));
  writeFileSync(resolve(directory, 'READ-ME-FIRST.txt'), '\uFEFFCodex Pocket '+version+' - Windows 11\r\n\r\n1. Install and sign in to the official Codex desktop app.\r\n2. Extract the whole ZIP and double-click Codex-Pocket.exe.\r\n3. A desktop window opens; choose LAN or public connection.\r\n4. Codex connects automatically; if needed, select its installation folder.\r\n5. Scan the QR on Android and approve the phone inside the desktop window.\r\n\r\nThe desktop window uses Microsoft Edge WebView2 Runtime. If missing, install it\r\nfrom https://developer.microsoft.com/microsoft-edge/webview2 .\r\nKeep the desktop window running while using the phone.\r\nOnly local Codex projects and chats are supported by the standalone connection.\r\nRemote hosts and ChatGPT-only chats are not included.\r\n\r\nWindows package has not yet been tested on a Windows 11 device.\r\n');
  const zig=process.env.POCKET_ZIG || resolve(root,'.private/toolchains/zig-npm/package/zig');
  const native=resolve(root,'desktop/windows/Launcher.c'), res=resolve(stage,'portable.res');
  windowsResources({output:res,icon:resolve(bundle,'Pocket.ico'),version});
  const flags=['cc','-target','x86_64-windows-gnu','-municode','-Wl,--subsystem,windows','-O2',`-DPOCKET_VERSION=L"${version}"`];
  run(zig,[...flags,native,res,'-lshell32','-luser32','-lgdi32','-o',resolve(directory,'Codex-Pocket.exe')],stage);
  // Debug symbols include local compiler paths and are not part of the app.
  rmSync(resolve(directory,'Codex-Pocket.pdb'),{force:true});
  cpSync(resolve(directory,'Codex-Pocket.exe'),resolve(bundle,'Codex-Pocket.exe'));
  // Old CMD entry points delegate to the EXE, so every launch has the same
  // kernel-owned lifetime and releases descendant listeners when it exits.
  for(const target of [directory,bundle]) writeFileSync(resolve(target,'Open-Codex-Pocket.cmd'),'@echo off\r\nstart "" "%~dp0Codex-Pocket.exe"\r\n');
  const payload=resolve(stage,'payload.zip');
  if(process.platform==='darwin')run('/usr/bin/ditto',['-c','-k','--norsrc','--keepParent',bundle,payload],stage);
  else run('zip',['-q','-r',payload,'app'],directory);
  const embedded=resolve(stage,'embedded.res'),standalone=resolve(stage,`Codex-Pocket-Connector-${version}-Windows.exe`);
  windowsResources({output:embedded,icon:resolve(bundle,'Pocket.ico'),version,payload});
  const payloadSha=createHash('sha256').update(readFileSync(payload)).digest('hex');
  run(zig,[...flags,'-DPOCKET_EMBEDDED=1',`-DPOCKET_PAYLOAD_SHA="${payloadSha}"`,native,embedded,'-lshell32','-luser32','-lgdi32','-o',standalone],stage);
  const inventory = [];
  function walk(parent, prefix = '') { for (const entry of readdirSync(parent, { withFileTypes: true })) {
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) walk(join(parent, entry.name), relative);
    else inventory.push({ path: relative, sha256: createHash('sha256').update(readFileSync(join(parent, entry.name))).digest('hex') });
  } }
  walk(directory);
  writeFileSync(resolve(directory, 'package-files.json'), JSON.stringify({ version, windowsRuntimeVerified: false, files: inventory }, null, 2));
  const stagedArchive = resolve(stage, `${name}.zip`);
  if (process.platform === 'darwin') run('/usr/bin/ditto', ['-c', '-k', '--norsrc', '--keepParent', directory, stagedArchive], stage);
  else if (process.platform === 'win32') {
    const encoded = Buffer.from(`$ErrorActionPreference='Stop'; Compress-Archive -LiteralPath '${directory.replaceAll("'", "''")}' -DestinationPath '${stagedArchive.replaceAll("'", "''")}' -Force`, 'utf16le').toString('base64');
    run('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', encoded], stage);
  } else run('zip', ['-q', '-r', stagedArchive, name], stage);
  const published = publishReleaseFile(stagedArchive, 'windows', version, out);
  const publishedExe=publishReleaseFile(standalone,'windows',version,out,'exe');
  return { version, executable:publishedExe.path, archive: published.path, kept: published.kept, removed: published.removed, directory, bundle, files: inventory.length, windowsRuntimeVerified: false, signing: 'unsigned Windows GUI EXE, self-extracting EXE and portable ZIP' };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { console.log(JSON.stringify(buildWindowsConnector({ out: resolve(arg('--out', releaseDirectory('windows'))), config: resolve(arg('--config', resolve(root, '.private/companion.json'))), version: arg('--version', PRODUCT_VERSION) }))); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
