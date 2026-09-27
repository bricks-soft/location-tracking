import assert from 'node:assert/strict';
import { test } from 'node:test';
import { deepMerge, pluginTestConfig } from '../src/index.ts';

test('pluginTestConfig: the documented defaults', () => {
  assert.deepEqual(pluginTestConfig({ url: 'http://10.0.2.2:8787/locations' }), {
    logger: { logLevel: 'debug' },
    heartbeat: { enabled: true, minInterval: 60, maxInterval: 120 },
    geolocation: { desiredAccuracy: 'high', distanceFilter: 10, stopTimeout: 1, stationaryRadius: 25 },
    http: { url: 'http://10.0.2.2:8787/locations', autoSync: true, syncInterval: 0, batchSync: false, params: { e2e: true } },
    app: { startOnBoot: true, stopOnTerminate: false },
    locationProvider: 'auto',
  });
});

test('pluginTestConfig: options, jwt and patch', () => {
  const config = pluginTestConfig({
    url: 'http://10.0.2.2:9000/locations',
    heartbeat: { minInterval: 180, maxInterval: 300 },
    syncInterval: 120,
    startOnBoot: false,
    stopOnTerminate: true,
    locationProvider: 'android',
    jwt: true,
    patch: {
      geolocation: { stopAfterElapsedMinutes: 2 },
      http: { params: { device: 'x' }, batchSync: true },
    },
  }) as Record<string, any>;
  assert.deepEqual(config['heartbeat'], { enabled: true, minInterval: 180, maxInterval: 300 });
  assert.deepEqual(config['app'], { startOnBoot: false, stopOnTerminate: true });
  assert.equal(config['locationProvider'], 'android');
  assert.deepEqual(config['geolocation'], {
    desiredAccuracy: 'high',
    distanceFilter: 10,
    stopTimeout: 1,
    stationaryRadius: 25,
    stopAfterElapsedMinutes: 2,
  });
  assert.equal(config['http'].syncInterval, 120);
  assert.equal(config['http'].batchSync, true);
  assert.deepEqual(config['http'].params, { device: 'x' }, 'http.params is replaced, not merged');
  assert.deepEqual(config['http'].authorization, {
    accessToken: 'e2e-initial',
    refreshToken: 'e2e-refresh',
    refreshUrl: 'http://10.0.2.2:9000/auth/refresh',
    refreshPayload: { refresh_token: '{refreshToken}' },
  });
});

test('deepMerge: objects merged, arrays and maps replaced, undefined ignored, inputs untouched', () => {
  const base = { a: { b: 1, c: [1, 2] }, http: { params: { x: 1 }, url: 'u' }, keep: true };
  const patch = { a: { c: [3], d: null }, http: { params: { y: 2 } }, keep: undefined };
  const merged = deepMerge(base, patch);
  assert.deepEqual(merged, { a: { b: 1, c: [3], d: null }, http: { params: { y: 2 }, url: 'u' }, keep: true });
  assert.deepEqual(base, { a: { b: 1, c: [1, 2] }, http: { params: { x: 1 }, url: 'u' }, keep: true });
});
