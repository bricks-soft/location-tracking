// Bundle exports, event helpers, UNIMPLEMENTED methods, and the IIFE bundle used by the example app.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { test } from 'node:test';
import vm from 'node:vm';

const require = createRequire(import.meta.url);
const mod = require('../dist/plugin.cjs.js');
const { LocationTracking } = mod;

const EVENT_NAMES = {
  LOCATION: 'location',
  MOTIONCHANGE: 'motionchange',
  ACTIVITYCHANGE: 'activitychange',
  PROVIDERCHANGE: 'providerchange',
  HEARTBEAT: 'heartbeat',
  GEOFENCE: 'geofence',
  GEOFENCESCHANGE: 'geofenceschange',
  HTTP: 'http',
  CONNECTIVITYCHANGE: 'connectivitychange',
  POWERSAVECHANGE: 'powersavechange',
  ENABLEDCHANGE: 'enabledchange',
  NOTIFICATIONACTION: 'notificationaction',
  AUTHORIZATION: 'authorization',
};

const HELPERS = {
  onLocation: 'location',
  onMotionChange: 'motionchange',
  onActivityChange: 'activitychange',
  onProviderChange: 'providerchange',
  onHeartbeat: 'heartbeat',
  onGeofence: 'geofence',
  onGeofencesChange: 'geofenceschange',
  onHttp: 'http',
  onConnectivityChange: 'connectivitychange',
  onPowerSaveChange: 'powersavechange',
  onEnabledChange: 'enabledchange',
  onNotificationAction: 'notificationaction',
  onAuthorization: 'authorization',
};

/** Every method that must reject with UNIMPLEMENTED on web, with sample arguments. */
const UNIMPLEMENTED = {
  start: [],
  startGeofences: [],
  stop: [],
  changePace: [{ isMoving: true }],
  getOdometer: [],
  setOdometer: [{ odometer: 1 }],
  resetOdometer: [],
  getLocations: [],
  getCount: [],
  insertLocation: [{ location: { coords: { latitude: 1, longitude: 2 } } }],
  destroyLocations: [],
  destroyLocation: [{ uuid: 'x' }],
  sync: [],
  addGeofence: [{ geofence: { identifier: 'a', latitude: 1, longitude: 2, radius: 100 } }],
  addGeofences: [{ geofences: [] }],
  removeGeofence: [{ identifier: 'a' }],
  removeGeofences: [],
  getGeofences: [],
  getGeofence: [{ identifier: 'a' }],
  geofenceExists: [{ identifier: 'a' }],
  getHeartbeatStatus: [],
  getProviderState: [],
  isPowerSaveMode: [],
  getBatteryOptimizationStatus: [],
  openBatteryOptimizationSettings: [],
  getPowerManagerInfo: [],
  openPowerManagerSettings: [],
  openLocationSettings: [],
  openAppSettings: [],
  getSensors: [],
  log: [{ level: 'info', message: 'hi' }],
  getLog: [],
  destroyLog: [],
  uploadLog: [{ url: 'https://example.test/logs' }],
  emailLog: [{ email: 'a@example.test' }],
};

test('CommonJS bundle exports LocationTracking, Events and the event helpers', () => {
  assert.ok(LocationTracking, 'LocationTracking is defined');
  assert.deepEqual({ ...mod.Events }, EVENT_NAMES);
  assert.equal(typeof mod.on, 'function');
  for (const name of Object.keys(HELPERS)) {
    assert.equal(typeof mod[name], 'function', name);
  }
});

test('each helper subscribes to its event on the web implementation', async () => {
  const received = [];
  const handles = [];
  // Capture the LocationTrackingWeb instance behind the proxy (the bundle shares this @capacitor/core module),
  // so the test can call its protected notifyListeners.
  const { WebPlugin } = require('@capacitor/core');
  const originalAddListener = WebPlugin.prototype.addListener;
  let impl;
  WebPlugin.prototype.addListener = function (...args) {
    impl = this;
    return originalAddListener.apply(this, args);
  };
  try {
    for (const [helper, eventName] of Object.entries(HELPERS)) {
      handles.push(await mod[helper]((event) => received.push([eventName, event])));
    }
    handles.push(await mod.on('heartbeat', (event) => received.push(['on:heartbeat', event])));
  } finally {
    WebPlugin.prototype.addListener = originalAddListener;
  }
  assert.ok(impl, 'listeners reached the web implementation');

  for (const eventName of Object.values(HELPERS)) {
    impl.notifyListeners(eventName, { name: eventName });
  }
  assert.deepEqual(
    received.map(([name]) => name),
    [...Object.values(HELPERS).slice(0, 5), 'on:heartbeat', ...Object.values(HELPERS).slice(5)],
  );
  for (const [name, event] of received) {
    assert.deepEqual(event, { name: name.replace('on:', '') });
  }

  for (const handle of handles) {
    assert.equal(typeof handle.remove, 'function');
    await handle.remove();
  }
  received.length = 0;
  impl.notifyListeners('location', {});
  assert.equal(received.length, 0, 'removed listeners get nothing');

  await mod.onLocation(() => received.push('late'));
  await LocationTracking.removeAllListeners();
  impl.notifyListeners('location', {});
  assert.equal(received.length, 0, 'removeAllListeners clears everything');
});

test('methods without a web implementation reject with UNIMPLEMENTED', async () => {
  await LocationTracking.ready();
  for (const [method, args] of Object.entries(UNIMPLEMENTED)) {
    await assert.rejects(LocationTracking[method](...args), (err) => {
      assert.equal(err.code, 'UNIMPLEMENTED', method);
      assert.equal(err.message, 'Not implemented on web.', method);
      return true;
    });
  }
});

test('the IIFE bundle loads in a browser-like global next to capacitor.js', async () => {
  const context = vm.createContext({ console, setTimeout, clearTimeout, navigator: { userAgent: 'test' } });
  vm.runInContext(readFileSync(require.resolve('@capacitor/core/dist/capacitor.js'), 'utf8'), context);
  vm.runInContext(readFileSync(new URL('../dist/plugin.js', import.meta.url), 'utf8'), context);
  const plugin = vm.runInContext('capacitorLocationTracking', context);
  assert.equal(plugin.Events.HEARTBEAT, 'heartbeat');
  const state = await plugin.LocationTracking.getState();
  assert.equal(state.backend, 'web');
  assert.equal(state.config.heartbeat.minInterval, 180);
  await assert.rejects(plugin.LocationTracking.setConfig({ config: {} }), (err) => err.code === 'NOT_READY');
  await assert.rejects(plugin.LocationTracking.start(), (err) => err.code === 'UNIMPLEMENTED');
});
