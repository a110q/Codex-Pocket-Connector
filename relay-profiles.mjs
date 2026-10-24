import https from 'node:https';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { connectorPaths, readJson, writePrivate, validateDefaults } from './connector-core.mjs';
import { digest } from './protocol.mjs';

export function verifyRelay(defaults) {
  const trusted = validateDefaults(defaults);
  return new Promise((accept, reject) => {
    const req = https.get(new URL('/healthz', trusted.relay), { ca: trusted.caCert, timeout: 10000 }, res => {
      let text = '';
      res.on('data', part => { text += part; if (text.length > 8192) req.destroy(Error('中继健康检查响应过大')); });
      res.on('end', () => { try { const data = JSON.parse(text); if (res.statusCode !== 200 || data.ok !== true || data.service !== 'codex-pocket' || data.protocol !== 1 || data.accounts !== 'approval-v1' || data.desktopEnrollment !== 'mobile-claim-v1') throw Error('服务器不是支持账号与扫码配对的 Codex Pocket 中继'); accept(trusted); } catch (error) { reject(error); } });
    });
    req.on('timeout', () => req.destroy(Error('连接中继超时，请检查地址、端口和防火墙'))); req.on('error', reject);
  });
}

export class RelayProfiles {
  constructor(paths, defaults) { this.base = paths; this.builtin = validateDefaults(defaults); }
  setup() { return existsSync(this.base.setup) ? readJson(this.base.setup) : {}; }
  defaults() { return this.setup().customRelay ? validateDefaults(this.setup().customRelay) : this.builtin; }
  paths() { const current = this.defaults(); return current.relay === this.builtin.relay && current.pin === this.builtin.pin ? this.base : connectorPaths(resolve(this.base.state, 'relays', digest(`${current.relay}|${current.pin}`).slice(0, 24))); }
  info() { const current = this.defaults(); return { relay: current.relay, pin: current.pin, builtin: current.relay === this.builtin.relay && current.pin === this.builtin.pin }; }
  async validate(body) { return body.builtin === true ? null : verifyRelay({ relay: body.relay, caCert: body.caCert }); }
  select(trusted) { const setup = this.setup(); if (trusted) setup.customRelay = trusted; else delete setup.customRelay; writePrivate(this.base.setup, { ...setup, mode: 'cloud' }); return this.info(); }
}
