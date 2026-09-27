// Web stub: ready / setConfig / reset / getState and the NOT_READY rule.
// Runs against the built CommonJS bundle, through the real Capacitor plugin proxy (platform 'web' under Node).
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { test } from 'node:test';

const require = createRequire(import.meta.url);
const { LocationTracking } = require('../dist/plugin.cjs.js');

/** Every documented @default of Config, written out independently of the implementation. */
function expectedDefaults() {
  return {
    geolocation: {
      desiredAccuracy: 'high',
      distanceFilter: 10,
      locationUpdateInterval: 1000,
      fastestLocationUpdateInterval: 500,
      disableElasticity: false,
      elasticityMultiplier: 1,
      stationaryRadius: 25,
      stopTimeout: 5,
      stopAfterElapsedMinutes: 0,
      stopOnStationary: false,
      locationTimeout: 30000,
      filter: {
        useKalman: false,
        trackingAccuracyThreshold: 100,
        maxImpliedSpeed: 80,
        odometerAccuracyThreshold: 20,
        allowIdenticalLocations: false,
        rejectMockLocations: false,
      },
    },
    activity: {
      disableMotionActivityUpdates: false,
      activityRecognitionInterval: 10000,
      minimumActivityRecognitionConfidence: 75,
      motionTriggerDelay: 0,
      disableStopDetection: false,
    },
    heartbeat: { enabled: true, minInterval: 180, maxInterval: 300 },
    http: {
      url: null,
      method: 'POST',
      headers: {},
      params: {},
      autoSync: true,
      autoSyncThreshold: 0,
      syncInterval: 0,
      batchSync: false,
      maxBatchSize: 100,
      disableAutoSyncOnCellular: false,
      rootProperty: 'location',
      locationTemplate: null,
      geofenceTemplate: null,
      timeout: 60000,
      authorization: null,
    },
    persistence: { maxDaysToPersist: 7, maxRecordsToPersist: -1, extras: {} },
    app: { stopOnTerminate: true, startOnBoot: false },
    notification: {
      text: 'Location tracking is active',
      smallIcon: 'drawable/lt_ic_notification',
      priority: 'default',
      channelId: 'location_tracking',
      channelName: 'Location tracking',
      actions: [],
    },
    geofence: { initialTriggerEntry: true },
    logger: { logLevel: 'info', logMaxDays: 3 },
    backgroundPermissionRationale: {},
    locationProvider: 'auto',
  };
}

function expectedState(config) {
  return {
    enabled: false,
    trackingMode: 'location',
    isMoving: false,
    odometer: 0,
    backend: 'web',
    lastRecordAt: null,
    config,
  };
}

async function rejectsWithCode(promise, code) {
  await assert.rejects(promise, (err) => {
    assert.equal(err.code, code, `expected code ${code}, got ${err.code}: ${err.message}`);
    assert.ok(err instanceof Error);
    return true;
  });
}

// Must run first: nothing has called ready() yet in this process.
test('getState before ready() resolves the default state', async () => {
  assert.deepEqual(await LocationTracking.getState(), expectedState(expectedDefaults()));
});

test('non-exempt methods reject with NOT_READY before ready()', async () => {
  await rejectsWithCode(LocationTracking.setConfig({ config: {} }), 'NOT_READY');
  await rejectsWithCode(LocationTracking.reset(), 'NOT_READY');
  await rejectsWithCode(LocationTracking.getCurrentPosition(), 'NOT_READY');
  await rejectsWithCode(
    LocationTracking.watchPosition({}, () => {}),
    'NOT_READY',
  );
  await rejectsWithCode(LocationTracking.clearWatch({ id: 'x' }), 'NOT_READY');
  // Exempt methods keep working.
  assert.equal((await LocationTracking.checkPermissions()).notifications, 'granted');
  assert.equal((await LocationTracking.getDeviceInfo()).platform, 'web');
});

test('ready() rejects a config that is not an object', async () => {
  await rejectsWithCode(LocationTracking.ready({ config: 'nope' }), 'INVALID_ARGUMENT');
});

test('ready() applies the given config over the defaults', async () => {
  const state = await LocationTracking.ready({
    config: {
      geolocation: { distanceFilter: 50, filter: { useKalman: true } },
      http: { url: 'https://example.test/locations', headers: { A: '1' } },
      locationProvider: 'hms',
    },
  });
  const expected = expectedDefaults();
  expected.geolocation.distanceFilter = 50;
  expected.geolocation.filter.useKalman = true;
  expected.http.url = 'https://example.test/locations';
  expected.http.headers = { A: '1' };
  expected.locationProvider = 'hms';
  assert.deepEqual(state, expectedState(expected));
  assert.deepEqual(await LocationTracking.getState(), state);
});

test('setConfig deep-merges sections and replaces arrays and maps', async () => {
  await LocationTracking.setConfig({
    config: { notification: { actions: [{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }] } },
  });
  const state = await LocationTracking.setConfig({
    config: {
      geolocation: { stationaryRadius: 40, filter: { maxImpliedSpeed: 0 } },
      http: { headers: { B: '2' }, params: { device: 'x' } },
      notification: { actions: [{ id: 'c', label: 'C' }] },
    },
  });
  const c = state.config;
  // Earlier values survive a deep merge.
  assert.equal(c.geolocation.distanceFilter, 50);
  assert.equal(c.geolocation.filter.useKalman, true);
  assert.equal(c.geolocation.stationaryRadius, 40);
  assert.equal(c.geolocation.filter.maxImpliedSpeed, 0);
  assert.equal(c.geolocation.filter.trackingAccuracyThreshold, 100);
  assert.equal(c.http.url, 'https://example.test/locations');
  // Maps and arrays are replaced, not merged.
  assert.deepEqual(c.http.headers, { B: '2' });
  assert.deepEqual(c.http.params, { device: 'x' });
  assert.deepEqual(c.notification.actions, [{ id: 'c', label: 'C' }]);
});

test('null resets a key, a map or a whole section to its default', async () => {
  await LocationTracking.setConfig({ config: { notification: { title: 'Tracking', color: '#112233' } } });
  const state = await LocationTracking.setConfig({
    config: {
      geolocation: { distanceFilter: null, filter: null },
      http: { url: null, headers: null },
      notification: { title: null },
    },
  });
  const c = state.config;
  assert.equal(c.geolocation.distanceFilter, 10);
  assert.equal(c.geolocation.stationaryRadius, 40);
  assert.deepEqual(c.geolocation.filter, expectedDefaults().geolocation.filter);
  assert.equal(c.http.url, null);
  assert.deepEqual(c.http.headers, {});
  assert.deepEqual(c.http.params, { device: 'x' });
  // A key without a default is removed.
  assert.equal('title' in c.notification, false);
  assert.equal(c.notification.color, '#112233');

  const reset = await LocationTracking.setConfig({ config: { geolocation: null } });
  assert.deepEqual(reset.config.geolocation, expectedDefaults().geolocation);
});

test('http.authorization gets its field defaults when set, and null resets it to null', async () => {
  let state = await LocationTracking.setConfig({
    config: { http: { authorization: { accessToken: 'abc', refreshPayload: { token: '{refreshToken}' } } } },
  });
  assert.deepEqual(state.config.http.authorization, {
    strategy: 'JWT',
    accessToken: 'abc',
    refreshPayload: { token: '{refreshToken}' },
    refreshHeaders: {},
    refreshPayloadEncoding: 'json',
    expires: -1,
  });
  state = await LocationTracking.setConfig({ config: { http: { authorization: { expires: 1234, accessToken: null } } } });
  assert.equal(state.config.http.authorization.expires, 1234);
  assert.equal('accessToken' in state.config.http.authorization, false);
  assert.deepEqual(state.config.http.authorization.refreshPayload, { token: '{refreshToken}' });

  state = await LocationTracking.setConfig({ config: { http: { authorization: null } } });
  assert.equal(state.config.http.authorization, null);

  // Clearing fields of a null authorization leaves it null.
  state = await LocationTracking.setConfig({ config: { http: { authorization: { accessToken: null } } } });
  assert.equal(state.config.http.authorization, null);

  // Resetting the whole http section resets authorization to its real default, null.
  await LocationTracking.setConfig({ config: { http: { authorization: { accessToken: 'abc' } } } });
  state = await LocationTracking.setConfig({ config: { http: null } });
  assert.deepEqual(state.config.http, expectedDefaults().http);
});

test('setConfig applies the Android clamps', async () => {
  const state = await LocationTracking.setConfig({
    config: {
      heartbeat: { minInterval: 10, maxInterval: 30 },
      activity: { minimumActivityRecognitionConfidence: 150 },
      http: { maxBatchSize: 0, syncInterval: -30 },
      notification: {
        actions: [1, 2, 3, 4].map((n) => ({ id: `a${n}`, label: `A${n}` })),
      },
    },
  });
  assert.equal(state.config.heartbeat.minInterval, 60);
  assert.equal(state.config.heartbeat.maxInterval, 60);
  assert.equal(state.config.activity.minimumActivityRecognitionConfidence, 100);
  assert.equal(state.config.http.maxBatchSize, 1);
  assert.equal(state.config.http.syncInterval, 0);
  assert.deepEqual(
    state.config.notification.actions.map((a) => a.id),
    ['a1', 'a2', 'a3'],
  );
  const low = await LocationTracking.setConfig({ config: { activity: { minimumActivityRecognitionConfidence: -5 } } });
  assert.equal(low.config.activity.minimumActivityRecognitionConfidence, 0);
});

test('undefined values and non-object section values are ignored', async () => {
  const before = await LocationTracking.getState();
  const state = await LocationTracking.setConfig({
    config: { geolocation: 5, activity: [1], heartbeat: { enabled: undefined }, logger: { logLevel: 'debug' } },
  });
  const expected = before.config;
  expected.logger.logLevel = 'debug';
  assert.deepEqual(state.config, expected);
});

test('__proto__ keys in a config are ignored; other keys in maps are kept', async () => {
  const state = await LocationTracking.setConfig({
    config: JSON.parse(
      '{"__proto__": {"polluted": true}, "persistence": {"extras": {"__proto__": {"polluted": true}, "constructor": "ACME", "prototype": 1}}}',
    ),
  });
  assert.equal({}.polluted, undefined);
  assert.equal(Object.getPrototypeOf(state.config.persistence.extras), Object.prototype);
  assert.equal(state.config.persistence.extras.polluted, undefined);
  assert.equal(state.config.persistence.extras.constructor, 'ACME');
  assert.equal(state.config.persistence.extras.prototype, 1);
  await LocationTracking.setConfig({ config: { persistence: { extras: null } } });
});

test('setConfig rejects a missing config with INVALID_ARGUMENT', async () => {
  await rejectsWithCode(LocationTracking.setConfig(), 'INVALID_ARGUMENT');
  await rejectsWithCode(LocationTracking.setConfig({ config: null }), 'INVALID_ARGUMENT');
  await rejectsWithCode(LocationTracking.reset({ config: 7 }), 'INVALID_ARGUMENT');
});

test('returned states are copies', async () => {
  const input = { persistence: { extras: { driver: 1 } } };
  const state = await LocationTracking.setConfig({ config: input });
  input.persistence.extras.driver = 2;
  state.config.persistence.extras.driver = 3;
  state.config.geolocation.distanceFilter = 999;
  const fresh = await LocationTracking.getState();
  assert.deepEqual(fresh.config.persistence.extras, { driver: 1 });
  assert.equal(fresh.config.geolocation.distanceFilter, 10);
});

test('ready({reset:false}) keeps the current config; ready() starts again from the defaults', async () => {
  const current = await LocationTracking.getState();
  const kept = await LocationTracking.ready({ reset: false, config: { geolocation: { distanceFilter: 77 } } });
  assert.deepEqual(kept, current);

  const fresh = await LocationTracking.ready({ config: { heartbeat: { minInterval: 240 } } });
  const expected = expectedDefaults();
  expected.heartbeat.minInterval = 240;
  assert.deepEqual(fresh, expectedState(expected));
});

test('reset() restores the defaults plus the given config', async () => {
  await LocationTracking.setConfig({ config: { geolocation: { distanceFilter: 5 } } });
  const state = await LocationTracking.reset({ config: { app: { startOnBoot: true } } });
  const expected = expectedDefaults();
  expected.app.startOnBoot = true;
  assert.deepEqual(state, expectedState(expected));
  assert.deepEqual(await LocationTracking.reset(), expectedState(expectedDefaults()));
});
