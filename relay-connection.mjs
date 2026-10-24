import WebSocket from 'ws';

// TCP can remain ESTABLISHED after Wi-Fi loss or sleep. Bound both the
// upgrade handshake and each ping/pong exchange instead of waiting for close.
export class RelayConnection {
  constructor(url, options, { onMessage = () => {}, onOpen = () => {}, onDisconnect = () => {}, pingIntervalMs = 15000, pongTimeoutMs = 10000, handshakeTimeoutMs = 12000, retryMinMs = 1000, retryMaxMs = 15000 } = {}) {
    this.url = url; this.options = options; this.callbacks = { onMessage, onOpen, onDisconnect };
    this.timing = { pingIntervalMs, pongTimeoutMs, handshakeTimeoutMs, retryMinMs, retryMaxMs };
    this.socket = null; this.stopped = true; this.retry = null; this.heartbeat = null; this.pongDeadline = null;
    this.state = 'stopped'; this.failures = 0;
  }
  get online() { return this.state === 'online' && this.socket?.readyState === WebSocket.OPEN; }
  isCurrent(socket) { return !this.stopped && this.socket === socket; }
  start() { if (!this.stopped) return; this.stopped = false; this.connect(); }
  clearHeartbeat() { clearInterval(this.heartbeat); clearTimeout(this.pongDeadline); this.heartbeat = null; this.pongDeadline = null; }
  connect() {
    if (this.stopped) return;
    clearTimeout(this.retry); this.retry = null; this.state = 'connecting';
    const socket = new WebSocket(this.url, { ...this.options, handshakeTimeout: this.timing.handshakeTimeoutMs });
    this.socket = socket;
    socket.on('open', () => {
      if (!this.isCurrent(socket)) return socket.terminate();
      this.state = 'online'; this.failures = 0;
      this.heartbeat = setInterval(() => {
        if (!this.isCurrent(socket) || this.pongDeadline) return;
        this.pongDeadline = setTimeout(() => {
          if (!this.isCurrent(socket)) return;
          this.disconnect(socket, 'heartbeat-timeout');
        }, this.timing.pongTimeoutMs);
        socket.ping();
      }, this.timing.pingIntervalMs);
      this.callbacks.onOpen();
    });
    socket.on('pong', () => { if (this.isCurrent(socket)) { clearTimeout(this.pongDeadline); this.pongDeadline = null; } });
    socket.on('message', data => { if (this.isCurrent(socket)) this.callbacks.onMessage(data, socket); });
    socket.on('error', () => this.disconnect(socket, 'transport-error'));
    socket.on('close', () => this.disconnect(socket, 'transport-closed'));
  }
  disconnect(socket, reason) {
    if (!this.isCurrent(socket)) return;
    this.socket = null; this.clearHeartbeat(); this.state = 'reconnecting';
    // Fence the old connection before terminate emits error/close callbacks.
    socket.terminate(); this.callbacks.onDisconnect(reason);
    const delay = Math.min(this.timing.retryMaxMs, this.timing.retryMinMs * 2 ** Math.min(this.failures++, 5));
    if (!this.stopped) this.retry = setTimeout(() => this.connect(), delay);
  }
  send(text) { if (!this.online) return false; this.socket.send(text); return true; }
  close() {
    this.stopped = true; clearTimeout(this.retry); this.retry = null; this.clearHeartbeat();
    const socket = this.socket; this.socket = null; this.state = 'stopped'; socket?.terminate();
  }
}
