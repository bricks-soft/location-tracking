// Smoke test of examples/field-force/www/app.js without a browser: runs index.html's own scripts (ff-core.js, app.js)
// in a Node vm context with a minimal fake DOM and fake plugins, and checks window.FF_APP (the only part the e2e suite
// uses). The fake document knows only the element ids that index.html declares, so a renamed id fails here.
// Run: node --test "examples/field-force/test/*.test.js"
'use strict';

process.env.TZ = 'Asia/Riyadh';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const WWW = path.join(__dirname, '..', 'www');
const INDEX = fs.readFileSync(path.join(WWW, 'index.html'), 'utf8');
const HTML_IDS = new Set([...INDEX.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1]));
const OWN_SCRIPTS = [...INDEX.matchAll(/<script src="([^"]+)"/g)].map((m) => m[1]).filter((src) => !src.startsWith('vendor/') && src !== 'env.js');
const ALL_GRANTED = { location: 'granted', backgroundLocation: 'granted', activityRecognition: 'granted', notifications: 'granted' };
const OVERRIDES_URL = 'https://localhost/_capacitor_file_/data/data/com.brickssoft.fieldforce.example/files/e2e/ff-overrides.json';

/** Minimal DOM element: what app.js uses (text, class, children, hidden, click listeners). */
function element(tag) {
  const listeners = {};
  return {
    tagName: tag,
    children: [],
    textContent: '',
    className: '',
    hidden: false,
    get firstChild() {
      return this.children[0] || null;
    },
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    removeChild(child) {
      this.children.splice(this.children.indexOf(child), 1);
      return child;
    },
    addEventListener(name, fn) {
      (listeners[name] = listeners[name] || []).push(fn);
    },
    fire(name) {
      (listeners[name] || []).forEach((fn) => fn({}));
    },
  };
}

/** Text of a <dl> rendered by renderRows: "label=value" pairs. */
function rows(dl) {
  const out = {};
  for (let i = 0; i < dl.children.length; i += 2) out[dl.children[i].textContent] = dl.children[i + 1].textContent;
  return out;
}

function fakeTracking(options) {
  const opts = options || {};
  const listeners = {};
  const calls = [];
  let enabled = false;
  let config = null;
  const state = () => ({ enabled, trackingMode: 'location', isMoving: false, odometer: 1234, backend: 'gms', lastRecordAt: null, config });
  return {
    calls,
    /** e.g. the 02:00 stop: tracking is off without the page knowing */
    setEnabled(value) {
      enabled = value;
    },
    emit(name, payload) {
      (listeners[name] || []).forEach((fn) => fn(payload));
    },
    listenerNames: () => Object.keys(listeners).sort(),
    async addListener(name, fn) {
      (listeners[name] = listeners[name] || []).push(fn);
      return { remove: async () => {} };
    },
    async getState() {
      calls.push('getState');
      return state();
    },
    async getDeviceInfo() {
      calls.push('getDeviceInfo');
      return { manufacturer: 'Google', model: 'AVD', brand: 'google', osVersion: '14', sdkInt: 34, pluginVersion: '0.1.0', backend: 'gms', gmsAvailable: true, hmsAvailable: false };
    },
    async ready(args) {
      calls.push('ready');
      config = args.config;
      return state();
    },
    async checkPermissions() {
      return ALL_GRANTED;
    },
    async requestPermissions() {
      calls.push('requestPermissions');
      return ALL_GRANTED;
    },
    async setConfig() {
      calls.push('setConfig');
      return state();
    },
    async start() {
      calls.push('start');
      if (opts.startError) throw opts.startError;
      enabled = true;
      return state();
    },
    async getCount() {
      return { count: 3 };
    },
    async getProviderState() {
      return { enabled: true, gps: true, network: true, permission: 'always', accuracy: 'precise', backend: 'gms' };
    },
    async getHeartbeatStatus() {
      return {
        enabled: true,
        minInterval: 60,
        maxInterval: 120,
        lastRecordAt: null,
        lastHeartbeatAt: null,
        nextHeartbeatAt: null,
        strategy: 'exact',
        canScheduleExactAlarms: false,
        isIgnoringBatteryOptimizations: true,
        isDeviceIdleMode: false,
        isPowerSaveMode: false,
        pendingHeartbeats: 0,
      };
    },
  };
}

/** Loads index.html's own scripts into a fresh vm context. */
function loadApp(options) {
  const opts = options || {};
  const elements = {};
  const storage = new Map(Object.entries(opts.storage || {}));
  const fetched = [];
  const intervals = [];
  const documentListeners = {};
  const document = {
    visibilityState: 'visible',
    /** dispatches a document event, like Capacitor's triggerEvent('resume', 'document') */
    fire(name) {
      (documentListeners[name] || []).forEach((fn) => fn({ type: name }));
    },
    getElementById(id) {
      if (!HTML_IDS.has(id)) return null;
      return (elements[id] = elements[id] || element('x'));
    },
    createElement: element,
    createTextNode: (text) => ({ textContent: text }),
    addEventListener(name, fn) {
      (documentListeners[name] = documentListeners[name] || []).push(fn);
    },
  };
  const tracking = opts.tracking || fakeTracking();
  const premiseCalls = [];
  const sandbox = {
    document,
    console,
    setTimeout,
    clearTimeout,
    setInterval: (fn, ms) => intervals.push({ fn, ms }),
    clearInterval: () => {},
    fetch: async (url, init) => {
      fetched.push({ url, init });
      if (opts.overridesFile === undefined) return { ok: false, status: 404, text: async () => '' };
      return { ok: true, status: 200, text: async () => opts.overridesFile };
    },
    localStorage: {
      getItem: (key) => (storage.has(key) ? storage.get(key) : null),
      setItem: (key, value) => storage.set(key, String(value)),
      removeItem: (key) => storage.delete(key),
    },
    FF_ENV: { backendUrl: 'http://10.0.2.2:8787' },
    Capacitor: {
      isNativePlatform: () => true,
      convertFileSrc: (p) => 'https://localhost/_capacitor_file_' + p,
    },
    capacitorLocationTracking: { LocationTracking: tracking },
    capacitorPremiseMonitor: {
      PremiseMonitor: {
        async startMonitoring(args) {
          premiseCalls.push(args);
          return { monitoring: true, premise: args.premise, inside: null, serviceRunning: false, pendingUploads: 0, lastEntryAt: null, auditUrl: args.auditUrl };
        },
        async getStatus() {
          return { monitoring: false, premise: null, inside: null, serviceRunning: false, pendingUploads: 0, lastEntryAt: null, auditUrl: null };
        },
      },
    },
  };
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  vm.createContext(sandbox);
  for (const src of OWN_SCRIPTS) {
    vm.runInContext(fs.readFileSync(path.join(WWW, src), 'utf8'), sandbox, { filename: src });
  }
  return { sandbox, document, elements, storage, fetched, intervals, tracking, premiseCalls };
}

/** Resolves after pending promise callbacks and zero-delay timers ran. */
function settle() {
  return new Promise((resolve) => setTimeout(resolve, 20));
}

test('index.html loads ff-core.js before app.js, after the vendor bundles', () => {
  const all = [...INDEX.matchAll(/<script src="([^"]+)"/g)].map((m) => m[1]);
  assert.deepEqual(all, ['env.js', 'vendor/capacitor.js', 'vendor/plugin.js', 'vendor/premise-monitor.js', 'ff-core.js', 'app.js']);
});

test('the page scripts use ES2017 syntax only (no optional chaining, ??, object spread)', () => {
  for (const src of OWN_SCRIPTS) {
    const code = fs
      .readFileSync(path.join(WWW, src), 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/.*$/gm, '')
      .replace(/'(?:[^'\\\n]|\\.)*'/g, "''");
    assert.doesNotMatch(code, /\?\?|\?\.[A-Za-z_$[(]/, `${src}: optional chaining or ??`);
    assert.doesNotMatch(code, /[{,]\s*\.\.\.[A-Za-z_$]/, `${src}: object spread`);
  }
});

test('app.js: auto start with the overrides file; FF_APP.startup resolves; the status screen shows it', async () => {
  const app = loadApp({
    overridesFile: JSON.stringify({ heartbeat: { minInterval: 60, maxInterval: 120 }, syncInterval: 120, premise: { id: 'hq', latitude: 24.7136, longitude: 46.6753, radius: 150 } }),
    storage: { 'ff.e2e.overrides': JSON.stringify({ syncInterval: 999 }) },
  });
  const FF_APP = app.sandbox.window.FF_APP;
  assert.ok(FF_APP, 'window.FF_APP exists synchronously');
  assert.equal(FF_APP.status, 'running');
  assert.equal(typeof FF_APP.startup.then, 'function');

  const result = JSON.parse(JSON.stringify(await FF_APP.startup));
  assert.deepEqual(app.fetched.map((f) => f.url), [OVERRIDES_URL]);
  assert.equal(result.overridesSource, 'file');
  assert.equal(result.config.http.syncInterval, 120);
  assert.equal(result.state.enabled, true);
  assert.ok(result.stopAfterElapsedMinutes >= 1 && result.stopAfterElapsedMinutes <= 1440);
  assert.equal(result.config.geolocation.stopAfterElapsedMinutes, result.stopAfterElapsedMinutes);
  assert.equal(result.deviceInfo.model, 'AVD');
  assert.deepEqual(app.tracking.calls.slice(0, 4), ['getState', 'getDeviceInfo', 'ready', 'start']);
  assert.deepEqual(JSON.parse(JSON.stringify(app.premiseCalls)), [
    { premise: { id: 'hq', latitude: 24.7136, longitude: 46.6753, radius: 150 }, auditUrl: 'http://10.0.2.2:8787/premise-audit' },
  ]);
  assert.deepEqual(app.tracking.listenerNames(), [
    'activitychange', 'authorization', 'connectivitychange', 'enabledchange', 'geofence', 'geofenceschange', 'heartbeat',
    'http', 'location', 'motionchange', 'notificationaction', 'powersavechange', 'providerchange',
  ]);

  await settle();
  assert.equal(FF_APP.status, 'done');
  assert.equal(FF_APP.error, null);
  const session = JSON.parse(app.storage.get('ff.session'));
  assert.equal(session.minutes, result.stopAfterElapsedMinutes);
  assert.equal(typeof session.startedAt, 'number');

  const shift = rows(app.elements['shift-rows']);
  assert.equal(shift.Tracking, 'on (location)');
  assert.match(shift['Automatic stop'], /^at \d\d:\d\d \(in /);
  assert.equal(shift.Odometer, '1.23 km');
  assert.equal(shift['Queued records'], '3');
  assert.equal(rows(app.elements['heartbeat-rows']).Strategy, 'exact');
  assert.equal(rows(app.elements['provider-rows']).Permission, 'always');
  assert.equal(rows(app.elements['premise-rows']).Monitoring, 'no');
  assert.equal(rows(app.elements['startup-rows'])['Test overrides'], 'from file');
  assert.equal(app.elements['chip-tracking'].textContent, 'tracking: on');
  assert.equal(app.elements.banner.hidden, true);
  assert.deepEqual(app.intervals.map((i) => i.ms), [30000], 'one 30 s status refresh while visible');

  app.tracking.emit('heartbeat', { location: { timestamp: '2026-09-27T05:00:00.000Z', coords: { latitude: 24.7136, longitude: 46.6753, accuracy: 12.4 } } });
  assert.equal(FF_APP.events.length, 1);
  assert.equal(FF_APP.events[0].name, 'heartbeat');
  assert.match(FF_APP.events[0].summary, /^position from 08:00:00, 24\.71360, 46\.67530 ±12 m$/);
  assert.equal(app.elements.events.children.length, 1);

  // A refresh requested while one runs is queued behind it (never merged into the older read).
  const before = app.tracking.calls.filter((c) => c === 'getState').length;
  const first = FF_APP.refresh();
  const second = FF_APP.refresh();
  const third = FF_APP.refresh();
  assert.equal(second, third, 'later calls share the queued run');
  await Promise.all([first, second]);
  assert.equal(app.tracking.calls.filter((c) => c === 'getState').length - before, 2);
  assert.equal(FF_APP.core.minutesUntil('02:00', new Date(2026, 8, 27, 1, 58)), 2);
});

test('app.js: without a file the localStorage overrides are used; a failed start rejects FF_APP.startup', async () => {
  const error = new Error('no location permission');
  error.code = 'PERMISSION_DENIED';
  const app = loadApp({ storage: { 'ff.e2e.overrides': JSON.stringify({ stopAt: '03:00' }) }, tracking: fakeTracking({ startError: error }) });
  const FF_APP = app.sandbox.window.FF_APP;
  await assert.rejects(FF_APP.startup, (e) => e.code === 'PERMISSION_DENIED' && e.step === 'start');
  await settle();
  assert.equal(FF_APP.status, 'failed');
  assert.deepEqual(JSON.parse(JSON.stringify(FF_APP.error)), { code: 'PERMISSION_DENIED', message: 'no location permission', step: 'start' });
  assert.equal(app.elements.banner.hidden, false);
  assert.match(app.elements.banner.textContent, /step "start": PERMISSION_DENIED: no location permission/);
  assert.equal(app.storage.has('ff.session'), false);
  assert.equal(rows(app.elements['shift-rows']).Tracking, 'off');
});

test('app.js: back in the foreground with tracking off (after the 02:00 stop) runs the startup again', async () => {
  const app = loadApp({ overridesFile: JSON.stringify({ syncInterval: 120 }) });
  const FF_APP = app.sandbox.window.FF_APP;
  const first = FF_APP.startup;
  await first;
  await settle();
  assert.equal(FF_APP.startupCount, 1);
  assert.equal(FF_APP.lastStartupReason, 'load');
  assert.equal(FF_APP.lastResume, null);

  // Tracking is on: the activity resumes (document 'resume' + 'visibilitychange'), nothing runs.
  app.document.fire('resume');
  app.document.fire('visibilitychange');
  await settle();
  assert.equal(FF_APP.startupCount, 1);
  assert.equal(FF_APP.startup, first);
  assert.equal(FF_APP.lastResume.outcome, 'busy', 'the second trigger joined the first check');

  // The 02:00 stop happened while the app stayed open; the next morning the worker brings it to the front.
  app.tracking.setEnabled(false);
  const readyBefore = app.tracking.calls.filter((c) => c === 'ready').length;
  app.document.fire('resume');
  app.document.fire('visibilitychange');
  await settle();
  assert.equal(FF_APP.startupCount, 2, 'exactly one new run for the two triggers');
  assert.equal(FF_APP.lastStartupReason, 'resume');
  assert.notEqual(FF_APP.startup, first);
  const result = JSON.parse(JSON.stringify(await FF_APP.startup));
  assert.equal(result.started, true);
  assert.equal(result.state.enabled, true);
  assert.equal(result.config.http.syncInterval, 120, 'the overrides file is read again');
  assert.equal(app.tracking.calls.filter((c) => c === 'ready').length, readyBefore + 1);
  await settle();
  assert.equal(FF_APP.status, 'done');
  assert.match(rows(app.elements['startup-rows'])['Startup runs'], /^2 \(latest: resume\)$/);

  // Now on again: a later resume does nothing; FF_APP.checkResume() answers the same way.
  assert.equal(await FF_APP.checkResume(), 'enabled');
  assert.equal(FF_APP.startupCount, 2);
});

test('app.js: autoStart false in the overrides: a resume with tracking off does not run the startup', async () => {
  const app = loadApp({ overridesFile: JSON.stringify({ autoStart: false }) });
  const FF_APP = app.sandbox.window.FF_APP;
  await FF_APP.startup;
  await settle();
  assert.equal(app.tracking.calls.includes('start'), false);
  app.document.fire('resume');
  await settle();
  assert.equal(FF_APP.startupCount, 1);
  assert.equal(FF_APP.lastResume.outcome, 'autostart_off');
});

test('app.js: autoStart false is also kept after a failed run (a resume does not run the startup)', async () => {
  // premise set but the start of monitoring fails: the run fails at step 'premise', after the overrides were read
  const app = loadApp({ overridesFile: JSON.stringify({ autoStart: false, premise: { id: 'hq', latitude: 1, longitude: 2, radius: 150 } }) });
  app.sandbox.capacitorPremiseMonitor.PremiseMonitor.startMonitoring = async () => {
    const e = new Error('no premise service');
    e.code = 'UNAVAILABLE';
    throw e;
  };
  const FF_APP = app.sandbox.window.FF_APP;
  await assert.rejects(FF_APP.startup, (e) => e.step === 'premise' && e.overrides.autoStart === false);
  await settle();
  assert.equal(FF_APP.status, 'failed');
  app.document.fire('resume');
  await settle();
  assert.equal(FF_APP.startupCount, 1);
  assert.equal(FF_APP.lastResume.outcome, 'autostart_off');
});
