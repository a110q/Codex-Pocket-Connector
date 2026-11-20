import { test } from 'node:test';
import assert from 'node:assert/strict';
import { assertAndroidRelay } from '../scripts/verify-android-relay.mjs';

const expected = { relay: 'https://relay.example.test:24443', pin: 'a'.repeat(64) };
const official = { ...expected, signingSha256: 'b'.repeat(64) };

test('Android release accepts the expected relay and certificate', () => {
  assert.doesNotThrow(() => assertAndroidRelay(official, expected));
});

test('Android release rejects a stale LAN relay even with the expected certificate', () => {
  assert.throws(() => assertAndroidRelay({ ...official, relay: 'https://192.168.1.12:24443' }, expected), /默认中继地址/);
});

test('Android release rejects the wrong certificate, missing configuration or signing fingerprint', () => {
  for (const actual of [null, { ...official, pin: 'c'.repeat(64) }, { ...official, signingSha256: '' }]) {
    assert.throws(() => assertAndroidRelay(actual, expected), /默认中继地址/);
  }
});
