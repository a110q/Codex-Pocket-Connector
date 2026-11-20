import { execFileSync } from 'node:child_process';

export function assertAndroidRelay(actual, expected) {
  if (!actual || actual.relay !== expected.relay || actual.pin !== expected.pin ||
      typeof actual.signingSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(actual.signingSha256)) {
    throw new Error('Android 安装包的默认中继地址、证书指纹或 APK 签名配置有误，请检查发布配置后重新构建。');
  }
}

export function verifyAndroidRelay(apk, expected) {
  let actual;
  try {
    actual = JSON.parse(execFileSync('unzip', ['-p', apk, 'assets/server.json'], {
      encoding: 'utf8', maxBuffer: 8192, stdio: ['ignore', 'pipe', 'pipe'],
    }));
  } catch {
    throw new Error('无法从 Android 安装包读取默认中继配置，请检查 assets/server.json 和 unzip 工具。');
  }
  assertAndroidRelay(actual, expected);
}
