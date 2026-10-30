import https from 'node:https';
import tls from 'node:tls';
import { createHash, createPublicKey, verify, X509Certificate } from 'node:crypto';
import { readFileSync, existsSync, mkdirSync, createWriteStream, renameSync, rmSync, openSync, readSync, closeSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { spawnSync } from 'node:child_process';
import { PRODUCT_VERSION, BUILD_NUMBER, compareVersions, versionParts } from './version.mjs';

const MAX_MANIFEST = 128 * 1024;
export const updatePlatform = platform => ({ darwin: 'macos', win32: 'windows', android: 'android' })[platform] ?? null;
export function assetName(platform, version) {
  return platform === 'android' ? `Codex-Pocket-${version}.apk` : `Codex-Pocket-Connector-${version}-${platform === 'macos' ? 'macOS.zip' : 'Windows.exe'}`;
}
export function validateUpdateConfig(config) {
  if (!config) throw Error('更新服务尚未配置，请联系维护者。');
  const url = new URL(config.manifestUrl);
  if (url.protocol !== 'https:' || url.username || url.password || url.hash) throw Error('更新服务地址无效。');
  if (!/^[a-f0-9]{64}$/.test(config.pin) || !/^[a-f0-9]{16}$/.test(config.keyId)) throw Error('更新信任配置无效。');
  const key = createPublicKey(config.publicKey);
  if (key.asymmetricKeyType !== 'ed25519' || createHash('sha256').update(key.export({ type:'spki',format:'der' })).digest('hex').slice(0,16) !== config.keyId) throw Error('更新签名公钥无效。');
  if (new X509Certificate(config.caCert).fingerprint256.replaceAll(':','').toLowerCase() !== config.pin) throw Error('更新证书配置无效。');
  return { ...config, manifestUrl:url.href };
}
export function loadUpdateConfig(root) {
  const file = resolve(root, 'update-defaults.json');
  return existsSync(file) ? validateUpdateConfig(JSON.parse(readFileSync(file,'utf8'))) : null;
}
export function verifyRelease(bytes, config) {
  if (bytes.length > MAX_MANIFEST) throw Error('更新清单过大。');
  let envelope, payload;
  try {
    envelope = JSON.parse(bytes.toString('utf8'));
    if (envelope.schema !== 1 || envelope.algorithm !== 'Ed25519' || envelope.keyId !== config.keyId) throw Error();
    if (typeof envelope.payload !== 'string' || typeof envelope.signature !== 'string') throw Error();
    const data = Buffer.from(envelope.payload,'base64'), signature = Buffer.from(envelope.signature,'base64');
    if (signature.length !== 64 || !verify(null, data, config.publicKey, signature)) throw Error();
    payload = JSON.parse(data.toString('utf8'));
  } catch { throw Error('更新清单验签失败，请稍后重试。'); }
  const release = payload;
  try {
    versionParts(release.version);
    if (release.version.length > 64 || release.schema !== 1 || release.channel !== 'stable' || !Number.isSafeInteger(release.build) || release.build < 1) throw Error();
    if (typeof release.notes !== 'string' || release.notes.length > 8192 || !Number.isFinite(Date.parse(release.publishedAt)) || !Array.isArray(release.assets) || release.assets.length > 16) throw Error();
    const names = new Set();
    for (const asset of release.assets) {
      const url = new URL(asset.url), base = new URL(config.manifestUrl);
      if (url.protocol !== 'https:' || url.origin !== base.origin || url.username || url.password || url.hash || url.search || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,180}$/.test(asset.name) || names.has(asset.name)) throw Error();
      if (url.pathname !== `/packages/${release.version}/${asset.name}` || !Number.isSafeInteger(asset.size) || asset.size < 1 || asset.size > 1024 ** 3 || !/^[a-f0-9]{64}$/.test(asset.sha256)) throw Error();
      names.add(asset.name);
    }
  } catch { throw Error('更新清单格式无效，请稍后重试。'); }
  return release;
}

// This client has its own trust configuration. Account tokens and selected relay
// settings never participate in update requests; redirects are deliberately rejected.
export function updateRequest(url, config, { signal, timeout = 15000 } = {}) {
  return new Promise((resolvePromise, reject) => {
    const req = https.get(url, { agent:false, ca:config.caCert, signal:signal ?? AbortSignal.timeout(timeout),
      headers:{ 'Accept-Encoding':'identity', 'Cache-Control':'no-cache' },
      checkServerIdentity(host, cert) {
        const error = tls.checkServerIdentity(host, cert);
        if (error) return error;
        if (createHash('sha256').update(cert.raw).digest('hex') !== config.pin) return Error('更新服务器证书不匹配。');
      }
    }, response => {
      if (response.statusCode !== 200) { response.resume(); reject(Error(`更新服务暂不可用（${response.statusCode}），请稍后重试。`)); return; }
      resolvePromise(response);
    });
    req.on('error', reject);
  });
}
export class UpdateManager {
  constructor({ config, directory, platform = process.platform, version = PRODUCT_VERSION, build = BUILD_NUMBER, reveal = revealUpdate } = {}) {
    Object.assign(this, { config, directory, platform:updatePlatform(platform), version, build, reveal });
    this.controller = null; this.file = null; this.job = null;
    this.value = { status:'idle', currentVersion:version, currentBuild:build, platform:this.platform, release:null, asset:null, download:'idle', received:0, error:'' };
  }
  state() { return structuredClone(this.value); }
  async check() {
    if (this.controller) return this.state();
    this.value = { ...this.value, status:'checking', release:null, asset:null, download:'idle', received:0, error:'' }; this.file = null;
    this.controller = new AbortController();
    const timeout = setTimeout(() => this.controller?.abort(), 12000);
    try {
      const config = validateUpdateConfig(this.config);
      if (!this.platform) throw Error('当前系统暂不支持应用内更新。');
      const response = await updateRequest(config.manifestUrl, config, { signal:this.controller.signal });
      const chunks = []; let length = 0;
      for await (const chunk of response) { length += chunk.length; if (length > MAX_MANIFEST) { response.destroy(); throw Error('更新清单过大。'); } chunks.push(chunk); }
      const release = verifyRelease(Buffer.concat(chunks), config);
      const asset = release.assets.find(a => a.platform === this.platform && a.name === assetName(this.platform, release.version) && a.kind === (this.platform === 'macos' ? 'zip' : this.platform === 'windows' ? 'exe' : 'apk'));
      if (!asset) throw Error('这个版本暂无适合当前系统的安装包，请稍后重试。');
      const comparison = compareVersions(release.version, this.version);
      if (comparison > 0 && release.build <= this.build) throw Error('更新安装包的构建号无效，请联系维护者。');
      this.value = { ...this.value, status:comparison > 0 ? 'available':'current', release, asset, ahead:comparison < 0 };
    } catch (error) { this.value.status = 'error'; this.value.error = updateError(error); }
    finally { clearTimeout(timeout); this.controller = null; }
    return this.state();
  }
  download() {
    if (this.controller || this.value.download === 'ready') return this.state();
    if (this.value.status !== 'available' || !this.value.asset) throw Error('请先检测更新。');
    const asset = this.value.asset;
    const directory = resolve(this.directory, this.value.release.version);
    mkdirSync(directory, { recursive:true, mode:0o700 });
    const target = resolve(directory, asset.name), partial = target + '.partial';
    this.controller = new AbortController(); this.value.download = 'downloading'; this.value.received = 0; this.value.error = '';
    const controller = this.controller;
    this.job = (async () => {
      const timer = setTimeout(() => controller.abort(), 10 * 60 * 1000);
      try {
        rmSync(partial, { force:true });
        const response = await updateRequest(asset.url, this.config, { signal:controller.signal });
        const hash = createHash('sha256');
        const counter = new Transform({ transform:(chunk, _, next) => {
          this.value.received += chunk.length;
          if (this.value.received > asset.size) return next(Error('安装包大小校验失败，请重新下载。'));
          hash.update(chunk); next(null,chunk);
        } });
        await pipeline(response, counter, createWriteStream(partial,{flags:'wx',mode:0o600}), { signal:controller.signal });
        if (this.value.received !== asset.size || hash.digest('hex') !== asset.sha256) throw Error('安装包完整性校验失败，请重新下载。');
        renameSync(partial,target); this.file = target; this.value.download = 'ready';
      } catch (error) { rmSync(partial,{force:true}); this.file = null; this.value.download = 'error'; this.value.error = updateError(error); }
      finally { clearTimeout(timer); this.controller = null; this.job = null; }
    })();
    return this.state();
  }
  open() {
    if (this.value.download !== 'ready') throw Error('请先完成下载。');
    try {
      if (!this.file || !existsSync(this.file)) throw Error('安装包已移除，请重新下载。');
      const hash = createHash('sha256'), buffer = Buffer.alloc(64 * 1024), descriptor = openSync(this.file,'r');
      try { let count; while ((count=readSync(descriptor,buffer,0,buffer.length,null))>0) hash.update(buffer.subarray(0,count)); }
      finally { closeSync(descriptor); }
      if (statSync(this.file).size !== this.value.asset.size || hash.digest('hex') !== this.value.asset.sha256) throw Error('安装包已改变，请重新下载。');
    } catch (error) {
      this.file = null; this.value.download = 'error';
      this.value.error = /安装包/.test(error.message) ? error.message : '无法读取安装包，请重新下载。';
      throw Error(this.value.error);
    }
    try { this.reveal(this.file); this.value.error = ''; }
    catch (error) { this.value.error = updateError(error); throw Error(this.value.error); }
    return this.state();
  }
  async close() { this.controller?.abort(); await this.job; }
}
export function updateError(error) {
  if (/更新|安装包|当前系统|检测/.test(error.message)) return error.message;
  return '暂时无法连接更新服务，请检查网络后重试。';
}
function revealUpdate(file) {
  const result = process.platform === 'win32' ? spawnSync('explorer.exe',['/select,',file],{windowsHide:true,timeout:10000})
    : spawnSync('/usr/bin/open',['-R',file],{timeout:10000});
  if (result.error || (process.platform !== 'win32' && result.status !== 0)) throw Error('无法打开安装包位置，请重新下载后重试。');
}
