import { resolve } from 'node:path';
import { existsSync } from 'node:fs';
import { readJson } from './connector-core.mjs';
import { startCompanion, relayRequest } from './companion.mjs';
import { startLanRelay } from './lan.mjs';
import { StandaloneAdapter } from './standalone-adapter.mjs';

export class ConnectorRuntime {
  constructor({ createAdapter = options => new StandaloneAdapter(options), companion = startCompanion, lanRelay = startLanRelay } = {}) {
    Object.assign(this, { createAdapter, companion, lanRelay });
    this.app = null; this.lan = null; this.hostId = null; this.busy = false; this.error = '';
  }
  async connect(paths, options = {}) {
    if (this.busy) throw new Error('正在连接，请稍候。');
    if (!existsSync(paths.config)) throw new Error('请先选择局域网连接，或公网连接。');
    const config = readJson(paths.config);
    if (this.app && this.hostId === config.hostId) return { started: true };
    this.busy = true; this.error = '';
    try {
      await this.stop();
      if (config.mode === 'lan') this.lan = await this.lanRelay(config, { dbPath: resolve(paths.state, 'lan-relay.sqlite') });
      this.app = await this.companion(config, { adapter: this.createAdapter(options), port: 0, statePath: resolve(paths.state, 'commands.sqlite'), configPath: paths.config });
      this.hostId = config.hostId;
      if (config.pairExpires < Date.now()) {
        // Expiry should not force a new user through a dead QR. Never rotate
        // credentials for a computer that already has an authorized phone.
        try {
          const status = await relayRequest(config, '/api/hosts/status', { hostId: config.hostId });
          if (status.mobilePaired === false) {
            const response = await fetch(`http://127.0.0.1:${this.port(config)}/api/reset`, { method: 'POST', headers: { Authorization: `Bearer ${config.managerToken}`, 'Content-Type': 'application/json' }, body: '{}', signal: AbortSignal.timeout(5000) });
            if (!response.ok) throw new Error('Pairing refresh failed');
          }
        } catch { this.error = '配对码需要更新，请在配对页面点击“更新配对二维码”。'; }
      }
      return { started: true };
    } catch (error) { await this.stop(); this.error = error.code === 'EADDRINUSE' ? '局域网端口已被占用，请先关闭另一个 Pocket 连接器后重试。' : error.message; throw new Error(this.error); }
    finally { this.busy = false; }
  }
  port(config) { return this.app && this.hostId === config.hostId ? this.app.manager.address().port : null; }
  async stop() {
    const app = this.app, lan = this.lan; this.app = null; this.lan = null; this.hostId = null;
    try { await app?.close(); } finally { await lan?.close(); }
  }
}
