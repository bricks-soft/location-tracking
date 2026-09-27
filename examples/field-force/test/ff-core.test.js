// Unit tests of examples/field-force/www/ff-core.js (the field-force startup logic, docs/e2e/architecture.md §10).
// Run: node --test "examples/field-force/test/*.test.js"   (Node >= 22, no dependencies; a directory argument does
// not work on Node 22)
'use strict';

// Fixed time zone without daylight-saving time (Riyadh, UTC+3), so local clock times are predictable. The DST test
// switches the zone for its own duration.
process.env.TZ = 'Asia/Riyadh';

const test = require('node:test');
const assert = require('node:assert/strict');
const core = require('../www/ff-core.js');

/** A local date in the current TZ. month is 1-based. */
function local(year, month, day, hours, minutes, seconds, ms) {
  return new Date(year, month - 1, day, hours, minutes, seconds || 0, ms || 0);
}

function withTimeZone(zone, fn) {
  const previous = process.env.TZ;
  process.env.TZ = zone;
  try {
    return fn();
  } finally {
    process.env.TZ = previous;
  }
}

const DEVICE = {
  platform: 'android',
  manufacturer: 'Google',
  model: 'sdk_gphone64_x86_64',
  brand: 'google',
  osVersion: '14',
  sdkInt: 34,
  pluginVersion: '0.1.0',
  gmsAvailable: true,
  hmsAvailable: false,
  backend: 'gms',
  packagedProviders: ['gms'],
};

const ALL_GRANTED = {
  location: 'granted',
  backgroundLocation: 'granted',
  activityRecognition: 'granted',
  notifications: 'granted',
};

/* ------------------------------------------------------------------ minutesUntil */

test('minutesUntil: minutes until the next local 02:00, rounded up', () => {
  assert.equal(core.minutesUntil('02:00', local(2026, 9, 27, 1, 58, 0)), 2);
  assert.equal(core.minutesUntil('02:00', local(2026, 9, 27, 1, 58, 30)), 2);
  assert.equal(core.minutesUntil('02:00', local(2026, 9, 27, 1, 58, 0, 1)), 2);
  assert.equal(core.minutesUntil('02:00', local(2026, 9, 27, 1, 59, 59, 999)), 1);
  assert.equal(core.minutesUntil('02:00', local(2026, 9, 27, 10, 0, 0)), 16 * 60);
  assert.equal(core.minutesUntil('02:00', local(2026, 9, 27, 23, 30, 0)), 150);
  assert.equal(core.minutesUntil('02:00', local(2026, 9, 27, 0, 0, 0)), 120);
});

test('minutesUntil: never 0; at exactly HH:MM the next occurrence is tomorrow', () => {
  assert.equal(core.minutesUntil('02:00', local(2026, 9, 27, 2, 0, 0)), 1440);
  assert.equal(core.minutesUntil('02:00', local(2026, 9, 27, 2, 0, 0, 1)), 1440);
  assert.equal(core.minutesUntil('14:05', local(2026, 9, 27, 14, 4, 0)), 1);
  assert.equal(core.minutesUntil('14:05', local(2026, 9, 27, 14, 4, 59, 999)), 1);
});

test('minutesUntil: crosses month and year ends', () => {
  assert.equal(core.minutesUntil('02:00', local(2026, 12, 31, 23, 0, 0)), 180);
  assert.equal(core.minutesUntil('02:00', local(2026, 2, 28, 3, 0, 0)), 23 * 60);
});

test('minutesUntil: accepts H:MM, surrounding spaces and a millisecond timestamp', () => {
  const now = local(2026, 9, 27, 1, 0, 0);
  assert.equal(core.minutesUntil('2:00', now), 60);
  assert.equal(core.minutesUntil(' 02:00 ', now), 60);
  assert.equal(core.minutesUntil('02:00', now.getTime()), 60);
  assert.equal(core.minutesUntil('00:00', now), 23 * 60);
  assert.equal(core.minutesUntil('23:59', now), 22 * 60 + 59);
});

test('minutesUntil: rejects times that are not HH:MM with INVALID_OVERRIDES', () => {
  for (const bad of ['24:00', '02:60', '2', '0200', 'ab:cd', '', '2:0', '-1:00', null, undefined, 200, {}]) {
    assert.throws(
      () => core.minutesUntil(bad, local(2026, 9, 27, 1, 0, 0)),
      (e) => e.code === 'INVALID_OVERRIDES',
      `stopAt ${JSON.stringify(bad)}`,
    );
  }
});

test('minutesUntil: daylight-saving nights (Europe/Berlin) count real minutes', () => {
  withTimeZone('Europe/Berlin', () => {
    // 2026-03-29: clocks jump from 02:00 CET to 03:00 CEST, 02:00 does not exist; the Date moves it to 03:00 CEST.
    assert.equal(core.minutesUntil('02:00', new Date(2026, 2, 29, 1, 30)), 30);
    // From 23:00 CET the evening before: 02:00 CET would be 3 h later; 03:00 CEST is the same instant.
    assert.equal(core.minutesUntil('02:00', new Date(2026, 2, 28, 23, 0)), 180);
    // 2026-10-25: clocks go back from 03:00 CEST to 02:00 CET; from 01:30 CEST the first 02:00 is 30 min away.
    assert.equal(core.minutesUntil('02:00', new Date(2026, 9, 25, 1, 30)), 30);
    // From 23:00 CEST the evening before the change: 3 h until 02:00 CEST.
    assert.equal(core.minutesUntil('02:00', new Date(2026, 9, 24, 23, 0)), 180);
  });
});

/* ------------------------------------------------------------------ stop minutes and stop display */

test('stopMinutesFor: a running session keeps its minutes, otherwise they are computed', () => {
  const now = local(2026, 9, 27, 1, 58, 0);
  const running = { enabled: true, config: { geolocation: { stopAfterElapsedMinutes: 777 } } };
  assert.deepEqual(core.stopMinutesFor(running, '02:00', now), { minutes: 777, kept: true });
  const runningNoStop = { enabled: true, config: { geolocation: { stopAfterElapsedMinutes: 0 } } };
  assert.deepEqual(core.stopMinutesFor(runningNoStop, '02:00', now), { minutes: 0, kept: true });
  const stopped = { enabled: false, config: { geolocation: { stopAfterElapsedMinutes: 777 } } };
  assert.deepEqual(core.stopMinutesFor(stopped, '02:00', now), { minutes: 2, kept: false });
  assert.deepEqual(core.stopMinutesFor({ enabled: true }, '02:00', now), { minutes: 2, kept: false });
  assert.deepEqual(core.stopMinutesFor(null, undefined, now), { minutes: 2, kept: false });
});

test('describeStop: stop time and minutes left', () => {
  const start = local(2026, 9, 27, 8, 0, 0).getTime();
  const info = core.describeStop(start, 18 * 60, local(2026, 9, 27, 20, 0, 30).getTime());
  assert.equal(info.stopAtMs, local(2026, 9, 28, 2, 0, 0).getTime());
  assert.equal(info.minutesLeft, 360);
  assert.equal(info.passed, false);
  assert.equal(core.describeStop(start, 60, local(2026, 9, 27, 9, 0, 1).getTime()).passed, true);
  assert.equal(core.describeStop(start, 0, start), null);
  assert.equal(core.describeStop(undefined, 60, start), null);
});

/* ------------------------------------------------------------------ overrides */

test('parseOverridesText: only a JSON object counts, everything else is "no file"', () => {
  for (const text of [null, undefined, '', '   ', 'null', '[]', '1', '"x"', 'true', '{bad', '{"a":1} x']) {
    assert.equal(core.parseOverridesText(text), null, JSON.stringify(text));
  }
  assert.deepEqual(core.parseOverridesText(' {"stopAt":"01:00"}\n'), { stopAt: '01:00' });
});

test('normalizeOverrides: defaults, nulls and unknown keys', () => {
  assert.deepEqual(core.normalizeOverrides(undefined), { premise: null, autoStart: true });
  assert.deepEqual(core.normalizeOverrides({}), { premise: null, autoStart: true });
  assert.deepEqual(
    core.normalizeOverrides({
      stopAt: null,
      heartbeat: null,
      syncInterval: null,
      backendUrl: null,
      premise: null,
      autoStart: null,
      configPatch: null,
      somethingElse: 1,
    }),
    { premise: null, autoStart: true },
  );
});

test('normalizeOverrides: every field of FieldForceOverrides', () => {
  const premise = { id: 'hq', name: 'HQ', latitude: 24.7136, longitude: 46.6753, radius: 150 };
  const out = core.normalizeOverrides({
    stopAt: '01:30',
    heartbeat: { minInterval: 60, maxInterval: 120 },
    syncInterval: 120,
    backendUrl: 'http://10.0.2.2:8787/',
    premise,
    autoStart: false,
    configPatch: { geolocation: { stopTimeout: 1 } },
  });
  assert.deepEqual(out, {
    stopAt: '01:30',
    heartbeat: { minInterval: 60, maxInterval: 120 },
    syncInterval: 120,
    backendUrl: 'http://10.0.2.2:8787',
    premise,
    autoStart: false,
    configPatch: { geolocation: { stopTimeout: 1 } },
  });
  assert.notEqual(out.premise, premise, 'premise is copied');
});

test('normalizeOverrides: a wrongly typed field throws INVALID_OVERRIDES', () => {
  const bad = [
    [],
    'x',
    { stopAt: '25:00' },
    { stopAt: 200 },
    { heartbeat: 60 },
    { heartbeat: { minInterval: '60' } },
    { syncInterval: -1 },
    { syncInterval: '120' },
    { backendUrl: 'ftp://host' },
    { backendUrl: 42 },
    { premise: { id: 'hq', latitude: 1, longitude: 2 } },
    { premise: { id: '', latitude: 1, longitude: 2, radius: 10 } },
    { premise: { id: 'hq', latitude: 1, longitude: 2, radius: 0 } },
    { autoStart: 'false' },
    { configPatch: [] },
  ];
  for (const raw of bad) {
    assert.throws(() => core.normalizeOverrides(raw), (e) => e.code === 'INVALID_OVERRIDES', JSON.stringify(raw));
  }
});

test('resolveBackendUrl: override, then FF_ENV, then the AVD host', () => {
  assert.equal(core.resolveBackendUrl({ backendUrl: 'http://a:1' }, { backendUrl: 'http://b:2' }), 'http://a:1');
  assert.equal(core.resolveBackendUrl({}, { backendUrl: 'http://b:2//' }), 'http://b:2');
  assert.equal(core.resolveBackendUrl({}, {}), 'http://10.0.2.2:8787');
  assert.equal(core.resolveBackendUrl(null, undefined), 'http://10.0.2.2:8787');
});

/* ------------------------------------------------------------------ preset */

const PRODUCTION_CONFIG = {
  geolocation: {
    desiredAccuracy: 'high',
    distanceFilter: 20,
    stationaryRadius: 50,
    stopTimeout: 5,
    stopAfterElapsedMinutes: 840,
    filter: { trackingAccuracyThreshold: 50 },
  },
  heartbeat: { enabled: true, minInterval: 180, maxInterval: 300 },
  http: {
    url: 'https://backoffice.example/locations',
    autoSync: true,
    syncInterval: 300,
    batchSync: true,
    maxBatchSize: 100,
    params: {
      worker_id: 'field-force-example',
      device: {
        manufacturer: 'Google',
        model: 'sdk_gphone64_x86_64',
        brand: 'google',
        osVersion: '14',
        sdkInt: 34,
        pluginVersion: '0.1.0',
        backend: 'gms',
        gmsAvailable: true,
        hmsAvailable: false,
      },
    },
    authorization: {
      strategy: 'JWT',
      accessToken: 'ff-initial',
      refreshToken: 'ff-refresh',
      refreshUrl: 'https://backoffice.example/auth/refresh',
      refreshPayload: { refresh_token: '{refreshToken}' },
    },
  },
  app: { stopOnTerminate: false, startOnBoot: true },
  notification: { title: 'Field Force', text: 'Shift tracking is on' },
  logger: { logLevel: 'debug' },
  locationProvider: 'auto',
};

test('buildConfig: the production preset of architecture §10', () => {
  const config = core.buildConfig({
    backendUrl: 'https://backoffice.example',
    stopAfterElapsedMinutes: 840,
    deviceInfo: DEVICE,
    overrides: core.normalizeOverrides({}),
  });
  assert.deepEqual(config, PRODUCTION_CONFIG);
});

test('buildConfig: test overrides (FIELD_FORCE_TEST_OVERRIDES) replace heartbeat and syncInterval', () => {
  const config = core.buildConfig({
    backendUrl: 'http://10.0.2.2:8787',
    stopAfterElapsedMinutes: 2,
    deviceInfo: DEVICE,
    overrides: core.normalizeOverrides({ heartbeat: { minInterval: 60, maxInterval: 120 }, syncInterval: 120 }),
  });
  assert.deepEqual(config.heartbeat, { enabled: true, minInterval: 60, maxInterval: 120 });
  assert.equal(config.http.syncInterval, 120);
  assert.equal(config.http.url, 'http://10.0.2.2:8787/locations');
  assert.equal(config.http.authorization.refreshUrl, 'http://10.0.2.2:8787/auth/refresh');
  assert.equal(config.geolocation.stopAfterElapsedMinutes, 2);
  const zero = core.buildConfig({ backendUrl: 'http://h', stopAfterElapsedMinutes: 1, overrides: { syncInterval: 0 } });
  assert.equal(zero.http.syncInterval, 0, 'syncInterval 0 (off) is a valid override');
});

test('buildConfig: configPatch is deep-merged last; arrays and null replace', () => {
  const config = core.buildConfig({
    backendUrl: 'https://backoffice.example',
    stopAfterElapsedMinutes: 840,
    deviceInfo: DEVICE,
    overrides: core.normalizeOverrides({
      heartbeat: { minInterval: 60, maxInterval: 120 },
      configPatch: {
        geolocation: { stopTimeout: 1, filter: { rejectMockLocations: true } },
        heartbeat: { maxInterval: 90 },
        http: { params: { e2e: true }, authorization: null },
        notification: { actions: [{ id: 'a', label: 'A' }] },
        persistence: { extras: { scenario: 'F-01' } },
      },
    }),
  });
  assert.equal(config.geolocation.stopTimeout, 1);
  assert.equal(config.geolocation.distanceFilter, 20);
  assert.deepEqual(config.geolocation.filter, { trackingAccuracyThreshold: 50, rejectMockLocations: true });
  assert.deepEqual(config.heartbeat, { enabled: true, minInterval: 60, maxInterval: 90 });
  assert.equal(config.http.params.e2e, true);
  assert.equal(config.http.params.device.model, 'sdk_gphone64_x86_64');
  assert.equal(config.http.authorization, null);
  assert.deepEqual(config.notification, { title: 'Field Force', text: 'Shift tracking is on', actions: [{ id: 'a', label: 'A' }] });
  assert.deepEqual(config.persistence, { extras: { scenario: 'F-01' } });
});

test('deviceParams: the nine DeviceInfo fields; missing ones are null', () => {
  assert.deepEqual(core.deviceParams(DEVICE), PRODUCTION_CONFIG.http.params.device);
  assert.deepEqual(core.deviceParams({ model: 'x' }), {
    manufacturer: null,
    model: 'x',
    brand: null,
    osVersion: null,
    sdkInt: null,
    pluginVersion: null,
    backend: null,
    gmsAvailable: null,
    hmsAvailable: null,
  });
});

test('deepMerge: copies, never changes its inputs', () => {
  const base = { a: { b: 1, list: [1, 2] }, keep: true };
  const patch = { a: { list: [3], c: { d: 4 } }, skip: undefined };
  const out = core.deepMerge(base, patch);
  assert.deepEqual(out, { a: { b: 1, list: [3], c: { d: 4 } }, keep: true });
  assert.deepEqual(base, { a: { b: 1, list: [1, 2] }, keep: true });
  out.a.c.d = 5;
  assert.equal(patch.a.c.d, 4);
});

test('missingPermissions', () => {
  assert.deepEqual(core.missingPermissions(ALL_GRANTED), []);
  assert.deepEqual(
    core.missingPermissions(Object.assign({}, ALL_GRANTED, { backgroundLocation: 'prompt', notifications: 'denied' })),
    ['backgroundLocation', 'notifications'],
  );
  assert.deepEqual(core.missingPermissions(null), []);
});

/* ------------------------------------------------------------------ runStartup with fake plugins */

/**
 * Fake LocationTracking. options: {initial, enabledAfterReady, permissions, requestError, startError, readyError}.
 * `calls` records [method, args] in call order.
 */
function fakeTracking(options) {
  const opts = options || {};
  const calls = [];
  const initial = opts.initial || { enabled: false, config: { geolocation: { stopAfterElapsedMinutes: 0 } } };
  return {
    calls,
    names: () => calls.map((c) => c[0]),
    async getState() {
      calls.push(['getState']);
      return initial;
    },
    async getDeviceInfo() {
      calls.push(['getDeviceInfo']);
      return DEVICE;
    },
    async ready(args) {
      calls.push(['ready', args]);
      if (opts.readyError) throw opts.readyError;
      const enabled = opts.enabledAfterReady !== undefined ? opts.enabledAfterReady : initial.enabled;
      return { enabled, trackingMode: 'location', config: args.config };
    },
    async checkPermissions() {
      calls.push(['checkPermissions']);
      return opts.permissions || ALL_GRANTED;
    },
    async requestPermissions(args) {
      calls.push(['requestPermissions', args]);
      if (opts.requestError) throw opts.requestError;
      return ALL_GRANTED;
    },
    async setConfig(args) {
      calls.push(['setConfig', args]);
      return { enabled: false, trackingMode: 'location', config: {} };
    },
    async start() {
      calls.push(['start']);
      if (opts.startError) throw opts.startError;
      return { enabled: true, trackingMode: 'location', config: {} };
    },
  };
}

function fakePremise() {
  const calls = [];
  return {
    calls,
    async startMonitoring(args) {
      calls.push(args);
      return { monitoring: true, premise: args.premise, inside: null, serviceRunning: false };
    },
  };
}

function storage(value) {
  return {
    getItem(key) {
      return key === 'ff.e2e.overrides' ? value : null;
    },
  };
}

function coded(code, message) {
  const e = new Error(message);
  e.code = code;
  return e;
}

const HQ = { id: 'hq', name: 'HQ', latitude: 24.7136, longitude: 46.6753, radius: 150 };
const AT_0158 = () => local(2026, 9, 27, 1, 58, 0);

test('runStartup: fresh install, file overrides, all permissions granted', async () => {
  const tracking = fakeTracking();
  const steps = [];
  const result = await core.runStartup({
    tracking,
    premise: fakePremise(),
    readOverridesFile: async () => JSON.stringify({ heartbeat: { minInterval: 60, maxInterval: 120 }, syncInterval: 120 }),
    storage: storage(JSON.stringify({ stopAt: '03:00' })),
    env: { backendUrl: 'http://10.0.2.2:8787' },
    now: AT_0158,
    onStep: (name) => steps.push(name),
  });
  assert.deepEqual(tracking.names(), ['getState', 'getDeviceInfo', 'ready', 'checkPermissions', 'start']);
  assert.deepEqual(steps, ['overrides', 'getState', 'stopTime', 'getDeviceInfo', 'ready', 'permissions', 'start', 'premise', 'done']);
  const readyArgs = tracking.calls[2][1];
  assert.equal(readyArgs.reset, true);
  assert.equal(readyArgs.config, result.config);
  assert.equal(result.overridesSource, 'file', 'the file wins over localStorage');
  assert.equal(result.stopAfterElapsedMinutes, 2, '01:58 -> 2 minutes until 02:00 (localStorage stopAt ignored)');
  assert.equal(result.config.geolocation.stopAfterElapsedMinutes, 2);
  assert.deepEqual(result.config.heartbeat, { enabled: true, minInterval: 60, maxInterval: 120 });
  assert.equal(result.config.http.syncInterval, 120);
  assert.equal(result.config.http.url, 'http://10.0.2.2:8787/locations');
  assert.deepEqual(result.deviceInfo, DEVICE);
  assert.equal(result.state.enabled, true);
  assert.equal(result.started, true);
  assert.equal(result.startCalledAt, AT_0158().getTime());
  assert.equal(result.stopMinutesKept, false);
  assert.equal(result.permissionsRequested, false);
  assert.equal(result.premiseStatus, null);
  assert.deepEqual(result.warnings, []);
});

test('runStartup: no file -> localStorage overrides (stopAt, backendUrl)', async () => {
  for (const fileResult of [async () => null, async () => '', async () => 'not json', async () => { throw new Error('404'); }, undefined]) {
    const tracking = fakeTracking();
    const premise = fakePremise();
    const result = await core.runStartup({
      tracking,
      premise,
      readOverridesFile: fileResult,
      storage: storage(JSON.stringify({ stopAt: '03:00', backendUrl: 'http://localhost:9999', premise: HQ })),
      env: { backendUrl: 'http://10.0.2.2:8787' },
      now: AT_0158,
    });
    assert.equal(result.overridesSource, 'localStorage');
    assert.equal(result.stopAfterElapsedMinutes, 62);
    assert.equal(result.config.http.url, 'http://localhost:9999/locations');
    assert.deepEqual(premise.calls, [{ premise: HQ, auditUrl: 'http://localhost:9999/premise-audit' }]);
  }
});

test('runStartup: no overrides at all -> production values and FF_ENV backend', async () => {
  const tracking = fakeTracking();
  const result = await core.runStartup({
    tracking,
    premise: null,
    readOverridesFile: async () => null,
    storage: storage(null),
    env: { backendUrl: 'https://backoffice.example' },
    now: () => local(2026, 9, 27, 7, 0, 0),
  });
  assert.equal(result.overridesSource, 'none');
  assert.deepEqual(result.config, Object.assign({}, PRODUCTION_CONFIG, {
    geolocation: Object.assign({}, PRODUCTION_CONFIG.geolocation, { stopAfterElapsedMinutes: 19 * 60 }),
  }));
});

test('runStartup: an invalid localStorage value rejects before any plugin call', async () => {
  const tracking = fakeTracking();
  await assert.rejects(
    core.runStartup({ tracking, readOverridesFile: async () => null, storage: storage('{oops'), now: AT_0158 }),
    (e) => e.code === 'INVALID_OVERRIDES' && e.step === 'overrides',
  );
  await assert.rejects(
    core.runStartup({ tracking, readOverridesFile: async () => '{"syncInterval":"x"}', now: AT_0158 }),
    (e) => e.code === 'INVALID_OVERRIDES' && e.step === 'overrides',
  );
  assert.deepEqual(tracking.calls, []);
});

test('runStartup: a running session keeps its stop minutes and is not started again', async () => {
  const tracking = fakeTracking({
    initial: { enabled: true, trackingMode: 'location', config: { geolocation: { stopAfterElapsedMinutes: 777 } } },
  });
  const result = await core.runStartup({ tracking, readOverridesFile: async () => '{"stopAt":"01:59"}', now: AT_0158 });
  assert.deepEqual(tracking.names(), ['getState', 'getDeviceInfo', 'ready', 'checkPermissions']);
  assert.equal(tracking.calls[2][1].config.geolocation.stopAfterElapsedMinutes, 777);
  assert.equal(result.stopAfterElapsedMinutes, 777);
  assert.equal(result.stopMinutesKept, true);
  assert.equal(result.started, false);
  assert.equal(result.state.enabled, true);
});

test('runStartup: autoStart false does not start; a state enabled after ready() is not started again', async () => {
  const off = fakeTracking();
  const result = await core.runStartup({ tracking: off, readOverridesFile: async () => '{"autoStart":false}', now: AT_0158 });
  assert.deepEqual(off.names(), ['getState', 'getDeviceInfo', 'ready', 'checkPermissions']);
  assert.equal(result.state.enabled, false);
  assert.equal(result.started, false);
  assert.equal(result.stopAfterElapsedMinutes, 2, 'the stop minutes are still computed for a later start');

  const restored = fakeTracking({ enabledAfterReady: true });
  const r2 = await core.runStartup({ tracking: restored, now: AT_0158 });
  assert.equal(restored.names().includes('start'), false);
  assert.equal(r2.state.enabled, true);
});

test('runStartup: missing permissions are requested; a failed request is a warning, start still runs', async () => {
  const missing = Object.assign({}, ALL_GRANTED, { backgroundLocation: 'prompt' });
  const asked = fakeTracking({ permissions: missing });
  const r1 = await core.runStartup({ tracking: asked, now: AT_0158 });
  assert.deepEqual(asked.names(), ['getState', 'getDeviceInfo', 'ready', 'checkPermissions', 'requestPermissions', 'start']);
  assert.equal(asked.calls[4][1], undefined, 'requestPermissions() without options (all types)');
  assert.equal(r1.permissionsRequested, true);
  assert.deepEqual(r1.permissions, ALL_GRANTED);

  const failing = fakeTracking({ permissions: missing, requestError: coded('NO_ACTIVITY', 'no activity') });
  const r2 = await core.runStartup({ tracking: failing, now: AT_0158 });
  assert.equal(failing.names().includes('start'), true);
  assert.deepEqual(r2.warnings, ['permissions: no activity']);
});

test('runStartup: premise monitoring starts after tracking with the audit URL of the back office', async () => {
  const tracking = fakeTracking();
  const premise = fakePremise();
  const order = [];
  const origStart = tracking.start;
  tracking.start = async () => {
    order.push('start');
    return origStart.call(tracking);
  };
  const origMonitor = premise.startMonitoring;
  premise.startMonitoring = async (args) => {
    order.push('startMonitoring');
    return origMonitor.call(premise, args);
  };
  const result = await core.runStartup({
    tracking,
    premise,
    readOverridesFile: async () => JSON.stringify({ premise: HQ }),
    env: { backendUrl: 'http://10.0.2.2:8787/' },
    now: AT_0158,
  });
  assert.deepEqual(order, ['start', 'startMonitoring']);
  assert.deepEqual(premise.calls, [{ premise: HQ, auditUrl: 'http://10.0.2.2:8787/premise-audit' }]);
  assert.equal(result.premiseStatus.monitoring, true);
});

test('runStartup: premise monitoring also starts with autoStart false', async () => {
  const premise = fakePremise();
  await core.runStartup({
    tracking: fakeTracking(),
    premise,
    readOverridesFile: async () => JSON.stringify({ premise: HQ, autoStart: false }),
    now: AT_0158,
  });
  assert.equal(premise.calls.length, 1);
});

test('runStartup: errors carry the step and keep the plugin code', async () => {
  await assert.rejects(
    core.runStartup({ tracking: fakeTracking({ startError: coded('PERMISSION_DENIED', 'no location') }), now: AT_0158 }),
    (e) => e.step === 'start' && e.code === 'PERMISSION_DENIED' && e.message === 'no location',
  );
  await assert.rejects(
    core.runStartup({ tracking: fakeTracking({ readyError: coded('INVALID_ARGUMENT', 'bad config') }), now: AT_0158 }),
    (e) => e.step === 'ready' && e.code === 'INVALID_ARGUMENT',
  );
  await assert.rejects(
    core.runStartup({ tracking: fakeTracking(), premise: null, readOverridesFile: async () => JSON.stringify({ premise: HQ }), now: AT_0158 }),
    (e) => e.step === 'premise' && e.code === 'UNAVAILABLE',
  );
  const plain = fakeTracking();
  plain.getState = () => Promise.reject({ code: 'INTERNAL', message: 'not an Error object' });
  await assert.rejects(core.runStartup({ tracking: plain, now: AT_0158 }), (e) => e instanceof Error && e.step === 'getState' && e.code === 'INTERNAL');
});

test('runStartup: configPatch may replace the stop minutes; the result reports the value given to ready()', async () => {
  const tracking = fakeTracking();
  const result = await core.runStartup({
    tracking,
    readOverridesFile: async () => JSON.stringify({ configPatch: { geolocation: { stopAfterElapsedMinutes: 5 } } }),
    now: AT_0158,
  });
  assert.equal(tracking.calls[2][1].config.geolocation.stopAfterElapsedMinutes, 5);
  assert.equal(result.stopAfterElapsedMinutes, 5);
});

test('runStartup: time spent before start() (permission dialogs) is taken out of the stop minutes', async () => {
  const tracking = fakeTracking({ permissions: Object.assign({}, ALL_GRANTED, { location: 'prompt' }) });
  let calls = 0;
  // 1st now(): the stopTime step at 01:50:00 -> 10 min; later: 01:54:30, after the dialogs -> 6 min.
  const clock = () => (++calls === 1 ? local(2026, 9, 27, 1, 50, 0) : local(2026, 9, 27, 1, 54, 30));
  const result = await core.runStartup({ tracking, now: clock });
  assert.deepEqual(tracking.names(), ['getState', 'getDeviceInfo', 'ready', 'checkPermissions', 'requestPermissions', 'setConfig', 'start']);
  assert.equal(tracking.calls[2][1].config.geolocation.stopAfterElapsedMinutes, 10, 'ready() got the step 3 value');
  assert.deepEqual(tracking.calls[5][1], { config: { geolocation: { stopAfterElapsedMinutes: 6 } } });
  assert.equal(result.stopAfterElapsedMinutes, 6);
  assert.equal(result.config.geolocation.stopAfterElapsedMinutes, 6);
  assert.equal(result.stopMinutesRecomputed, true);
  assert.equal(result.startCalledAt, local(2026, 9, 27, 1, 54, 30).getTime());
});

test('runStartup: no setConfig when the minutes did not change or configPatch sets them', async () => {
  const same = fakeTracking();
  const r1 = await core.runStartup({ tracking: same, now: AT_0158 });
  assert.equal(same.names().includes('setConfig'), false);
  assert.equal(r1.stopMinutesRecomputed, false);

  const patched = fakeTracking();
  let calls = 0;
  const clock = () => (++calls === 1 ? local(2026, 9, 27, 1, 50, 0) : local(2026, 9, 27, 1, 54, 30));
  const r2 = await core.runStartup({
    tracking: patched,
    readOverridesFile: async () => JSON.stringify({ configPatch: { geolocation: { stopAfterElapsedMinutes: 30 } } }),
    now: clock,
  });
  assert.equal(patched.names().includes('setConfig'), false);
  assert.equal(r2.stopAfterElapsedMinutes, 30);
});

test('runStartup: a file read that fails or a file that is not an object is a warning, not an error', async () => {
  const timedOut = await core.runStartup({
    tracking: fakeTracking(),
    readOverridesFile: () => Promise.reject(new Error('timed out after 5000 ms')),
    storage: storage(JSON.stringify({ syncInterval: 60 })),
    now: AT_0158,
  });
  assert.equal(timedOut.overridesSource, 'localStorage');
  assert.deepEqual(timedOut.warnings, ['overrides file not read: timed out after 5000 ms']);

  const notObject = await core.runStartup({ tracking: fakeTracking(), readOverridesFile: async () => '[1,2]', now: AT_0158 });
  assert.equal(notObject.overridesSource, 'none');
  assert.deepEqual(notObject.warnings, ['overrides file ignored: its content is not a JSON object']);

  const absent = await core.runStartup({ tracking: fakeTracking(), readOverridesFile: async () => null, now: AT_0158 });
  assert.deepEqual(absent.warnings, [], 'no file (404) is the normal production case');
});

test('runStartup: a session that ended during ready() is started with fresh minutes, not the old kept ones', async () => {
  const tracking = fakeTracking({
    initial: { enabled: true, trackingMode: 'location', config: { geolocation: { stopAfterElapsedMinutes: 777 } } },
    enabledAfterReady: false,
  });
  const result = await core.runStartup({ tracking, now: AT_0158 });
  assert.deepEqual(tracking.names(), ['getState', 'getDeviceInfo', 'ready', 'checkPermissions', 'setConfig', 'start']);
  assert.equal(tracking.calls[2][1].config.geolocation.stopAfterElapsedMinutes, 777);
  assert.deepEqual(tracking.calls[4][1], { config: { geolocation: { stopAfterElapsedMinutes: 2 } } });
  assert.equal(result.stopAfterElapsedMinutes, 2);
  assert.equal(result.stopMinutesKept, false);
  assert.equal(result.started, true);
});

/* ------------------------------------------------------------------ createAutoStarter (load and resume) */

/** A promise with its resolve/reject exposed. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** Lets pending promise callbacks run. */
function tick() {
  return new Promise((resolve) => setImmediate(resolve));
}

/**
 * Auto starter with fake plugins. `env.enabled` is what getState() answers; each start() call gets a deferred that
 * the test settles, so a run can be kept "in progress".
 */
function fakeStarter(options) {
  const opts = options || {};
  const log = { starts: [], begins: [], resumes: [], getStateCalls: 0, runs: [] };
  const env = { enabled: opts.enabled || false, autoStartOff: false, getStateError: null };
  const starter = core.createAutoStarter({
    start(reason) {
      log.starts.push(reason);
      const run = deferred();
      log.runs.push(run);
      return run.promise;
    },
    async getState() {
      log.getStateCalls += 1;
      if (env.getStateError) throw env.getStateError;
      return { enabled: env.enabled };
    },
    autoStartOff: () => env.autoStartOff,
    onBegin: (reason, promise) => log.begins.push({ reason, promise }),
    onResume: (entry) => log.resumes.push(entry),
  });
  return { starter, log, env };
}

test('autoStarter: load() begins run 1 with reason load', async () => {
  const { starter, log } = fakeStarter();
  const run = starter.load();
  assert.equal(starter.count, 1);
  assert.equal(starter.reason, 'load');
  assert.equal(starter.running(), true);
  assert.equal(log.begins.length, 1);
  assert.equal(log.begins[0].promise, run, 'onBegin gets the run promise that load() returns');
  await tick();
  assert.deepEqual(log.starts, ['load']);
  log.runs[0].resolve({ ok: true });
  assert.deepEqual(await run, { ok: true });
  await tick();
  assert.equal(starter.running(), false);
});

test('autoStarter: resume while tracking is enabled does nothing', async () => {
  const { starter, log } = fakeStarter({ enabled: true });
  starter.load();
  await tick();
  log.runs[0].resolve({});
  await tick();
  assert.equal(await starter.resume('resume'), 'enabled');
  assert.equal(starter.count, 1);
  assert.deepEqual(log.starts, ['load']);
  assert.equal(log.getStateCalls, 1);
  assert.deepEqual(log.resumes.map((e) => [e.trigger, e.outcome]), [['resume', 'enabled']]);
});

test('autoStarter: resume while tracking is not enabled begins exactly one new run (reason resume)', async () => {
  const { starter, log, env } = fakeStarter({ enabled: true });
  starter.load();
  await tick();
  log.runs[0].resolve({});
  await tick();
  env.enabled = false; // the 02:00 stop happened during the night
  // The same foregrounding fires both the document 'resume' event and 'visibilitychange'.
  const [a, b] = await Promise.all([starter.resume('resume'), starter.resume('visibilitychange')]);
  assert.equal(a, 'started');
  assert.equal(b, 'busy');
  assert.equal(starter.count, 2);
  assert.equal(starter.reason, 'resume');
  assert.equal(log.getStateCalls, 1, 'the second trigger joined the first check');
  await tick();
  assert.deepEqual(log.starts, ['load', 'resume']);
  assert.deepEqual(log.begins.map((x) => x.reason), ['load', 'resume']);
  assert.equal(starter.running(), true);
  log.runs[1].resolve({});
  await tick();
  assert.equal(starter.running(), false);
});

test('autoStarter: resume during a running startup (e.g. back from a permission dialog) runs no second startup', async () => {
  const { starter, log } = fakeStarter({ enabled: false });
  starter.load();
  await tick();
  assert.equal(await starter.resume('resume'), 'busy');
  assert.equal(await starter.resume('visibilitychange'), 'busy');
  assert.equal(log.getStateCalls, 0, 'no state read while a run is in progress');
  assert.equal(starter.count, 1);
  // The run fails (e.g. PERMISSION_DENIED); the next foregrounding tries again.
  log.runs[0].reject(coded('PERMISSION_DENIED', 'denied'));
  await tick();
  assert.equal(await starter.resume('resume'), 'started');
  assert.equal(starter.count, 2);
});

test('autoStarter: a run that begins while the state is read makes the check answer busy', async () => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const starts = [];
  const starter = core.createAutoStarter({
    start: (reason) => {
      starts.push(reason);
      return new Promise(() => {});
    },
    getState: () => gate.then(() => ({ enabled: false })),
  });
  const check = starter.resume('resume');
  starter.load();
  release();
  assert.equal(await check, 'busy');
  await tick();
  assert.deepEqual(starts, ['load']);
});

test('autoStarter: autoStart false (tests) and a failed getState never start a run', async () => {
  const { starter, log, env } = fakeStarter({ enabled: false });
  env.autoStartOff = true;
  assert.equal(await starter.resume('resume'), 'autostart_off');
  assert.equal(log.getStateCalls, 0);
  env.autoStartOff = false;
  env.getStateError = new Error('bridge gone');
  assert.equal(await starter.resume('resume'), 'error');
  assert.equal(starter.count, 0);
  assert.deepEqual(log.resumes.map((e) => e.outcome), ['autostart_off', 'error']);
  assert.equal(typeof log.resumes[0].at, 'number');
});
