import test from 'node:test';
import assert from 'node:assert/strict';
import { CipherChannel, secret, deriveKey, validateCommand, digest } from '../protocol.mjs';
import { randomUUID } from 'node:crypto';

test('mobile and desktop encrypt in separate directions and reject tampering and replay', () => {
  const key = secret(), hostId = secret().slice(0, 32);
  const mobile = new CipherChannel(key, hostId, 'mobile'), desktop = new CipherChannel(key, hostId, 'desktop');
  const frame = mobile.encrypt({ text: '你好，电脑', id: randomUUID() });
  assert(!JSON.stringify(frame).includes('你好'));
  const tampered = { ...frame, seq: 2 };
  assert.throws(() => desktop.decrypt(tampered));
  assert.equal(desktop.decrypt(frame).text, '你好，电脑');
  assert.throws(() => desktop.decrypt(frame), /Replayed/);
  assert.throws(() => mobile.decrypt(frame));
  assert.equal(mobile.decrypt(desktop.encrypt({ ok: true })).ok, true);
  assert.notDeepEqual(deriveKey(key, hostId, 'mobile-to-desktop'), deriveKey(key, hostId, 'desktop-to-mobile'));
  assert.throws(() => new CipherChannel(secret(), hostId, 'desktop').decrypt(mobile.encrypt({ private: true })));
});
test('invalid, stale and arbitrary desktop methods cannot pass command validation', () => {
  const valid = { id: randomUUID(), action: 'message.send', args: { threadId: 'example', text: 'test' }, sentAt: Date.now() };
  assert.equal(validateCommand(valid), valid);
  assert.throws(() => validateCommand({ ...valid, action: 'tools/call' }));
  assert.throws(() => validateCommand({ ...valid, sentAt: Date.now() - 600000 }));
  assert.throws(() => validateCommand({ ...valid, args: { text: 'test' } }));
  assert.throws(() => validateCommand({ ...valid, args: { threadId: 'example', text: '' } }));
});
