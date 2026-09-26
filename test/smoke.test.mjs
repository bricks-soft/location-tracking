import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { test } from 'node:test';

const require = createRequire(import.meta.url);

test('CommonJS bundle exports LocationTracking and event helpers', () => {
  const mod = require('../dist/plugin.cjs.js');
  assert.ok(mod.LocationTracking, 'LocationTracking is defined');
  assert.equal(typeof mod.onLocation, 'function');
  assert.equal(mod.Events.HEARTBEAT, 'heartbeat');
});
