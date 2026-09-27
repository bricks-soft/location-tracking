// Web stub: getCurrentPosition / watchPosition / clearWatch, permissions and device info, against fake browser APIs.
// Runs against the built CommonJS bundle, through the real Capacitor plugin proxy (platform 'web' under Node).
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { before, test } from 'node:test';

const require = createRequire(import.meta.url);
const { LocationTracking, onLocation } = require('../dist/plugin.cjs.js');

const ISO_MS = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

/** Scriptable stand-in for `navigator.geolocation`. Callbacks fire asynchronously, like a browser. */
class FakeGeolocation {
  constructor() {
    /** Responses for getCurrentPosition, consumed in order: {position} | {error} | {hang: true}. */
    this.queue = [];
    /** Options of every getCurrentPosition call. */
    this.calls = [];
    /** Every watch ever started (kept after clearWatch so tests can fire stale callbacks). */
    this.watches = new Map();
    this.cleared = [];
    this.nextId = 100;
  }
  respond(...responses) {
    this.queue.push(...responses);
  }
  getCurrentPosition(success, error, options) {
    this.calls.push(options);
    const response = this.queue.shift() || { hang: true };
    if (response.hang) return;
    setTimeout(() => (response.position ? success(response.position) : error(response.error)), response.delay || 0);
  }
  watchPosition(success, error, options) {
    const id = this.nextId++;
    this.watches.set(id, { success, error, options });
    return id;
  }
  clearWatch(id) {
    this.cleared.push(id);
  }
}

/** A browser GeolocationPosition. */
function fix(accuracy, { latitude = 24.7136, longitude = 46.6753, timestamp = Date.now(), ...rest } = {}) {
  return {
    coords: {
      latitude,
      longitude,
      accuracy,
      altitude: null,
      altitudeAccuracy: null,
      speed: null,
      heading: null,
      ...rest,
    },
    timestamp,
  };
}

function setGlobal(name, value) {
  Object.defineProperty(globalThis, name, { value, configurable: true, writable: true });
}

let geo;
let uuidCount = 0;

function installBrowser({ permissions } = {}) {
  geo = new FakeGeolocation();
  setGlobal('navigator', { geolocation: geo, userAgent: 'Mozilla/5.0 (X11; Linux x86_64)', platform: 'Linux x86_64', permissions });
  return geo;
}

async function rejectsWithCode(promise, code) {
  let caught;
  await assert.rejects(promise, (err) => {
    assert.equal(err.code, code, `expected code ${code}, got ${err.code}: ${err.message}`);
    caught = err;
    return true;
  });
  return caught;
}

const tick = (ms = 5) => new Promise((resolve) => setTimeout(resolve, ms));

before(() => {
  installBrowser();
  setGlobal('crypto', { randomUUID: () => `00000000-0000-4000-8000-${String(++uuidCount).padStart(12, '0')}` });
});

// Must run first: the web implementation is loaded by these concurrent calls.
test('concurrent first calls share one web implementation', async () => {
  const events = [];
  const [handle, readyState, state] = await Promise.all([
    onLocation((location) => events.push(location)),
    LocationTracking.ready({ config: { geolocation: { distanceFilter: 42 } } }),
    LocationTracking.getState(),
  ]);
  assert.equal(readyState.config.geolocation.distanceFilter, 42);
  assert.ok(state.config.geolocation);
  assert.equal((await LocationTracking.getState()).config.geolocation.distanceFilter, 42);
  // setConfig would reject with NOT_READY if ready() had run on another instance.
  await LocationTracking.setConfig({ config: { geolocation: { distanceFilter: null } } });
  geo.respond({ position: fix(3) });
  await LocationTracking.getCurrentPosition({ samples: 1 });
  assert.equal(events.length, 1, 'the listener lives on the same instance');
  await handle.remove();
});

/* ---------------- getCurrentPosition ---------------- */

test('getCurrentPosition takes 3 samples by default and resolves the most accurate', async () => {
  installBrowser();
  geo.respond({ position: fix(30) }, { position: fix(5, { latitude: 1.5 }) }, { position: fix(12) });
  const location = await LocationTracking.getCurrentPosition();
  assert.equal(geo.calls.length, 3);
  assert.equal(location.coords.accuracy, 5);
  assert.equal(location.coords.latitude, 1.5);
  for (const options of geo.calls) {
    assert.equal(options.enableHighAccuracy, true);
    assert.equal(options.maximumAge, 0);
    // Each call gets what is left of the default 30 s geolocation.locationTimeout.
    assert.ok(options.timeout > 29000 && options.timeout <= 30000, `timeout ${options.timeout}`);
  }
});

test('getCurrentPosition maps the fix to the Location record shape', async () => {
  installBrowser();
  const timestamp = Date.UTC(2026, 8, 26, 10, 15, 30, 123);
  geo.respond({
    position: fix(4.5, { timestamp, altitude: 612.3, altitudeAccuracy: 3, speed: 13.4, heading: NaN }),
  });
  const before = Date.now();
  const location = await LocationTracking.getCurrentPosition({ samples: 1 });
  const after = Date.now();

  assert.equal(location.uuid, `00000000-0000-4000-8000-${String(uuidCount).padStart(12, '0')}`);
  assert.equal(location.event, 'current_position');
  assert.equal(location.timestamp, '2026-09-26T10:15:30.123Z');
  assert.match(location.recorded_at, ISO_MS);
  const recordedAt = Date.parse(location.recorded_at);
  assert.ok(recordedAt >= before && recordedAt <= after);
  assert.ok(Number.isInteger(location.elapsed_realtime_ms) && location.elapsed_realtime_ms >= 0);
  assert.deepEqual(
    { ...location, uuid: undefined, recorded_at: undefined, elapsed_realtime_ms: undefined },
    {
      uuid: undefined,
      event: 'current_position',
      timestamp: '2026-09-26T10:15:30.123Z',
      recorded_at: undefined,
      elapsed_realtime_ms: undefined,
      boot_count: -1,
      is_moving: false,
      odometer: 0,
      mock: false,
      coords: {
        latitude: 24.7136,
        longitude: 46.6753,
        accuracy: 4.5,
        altitude: 612.3,
        altitude_accuracy: 3,
        speed: 13.4,
        speed_accuracy: null,
        heading: null,
        heading_accuracy: null,
      },
      activity: { type: 'unknown', confidence: 0 },
      battery: { level: -1, is_charging: false },
      backend: 'web',
    },
  );
  assert.equal('extras' in location, false);
});

test('getCurrentPosition honours samples, desiredAccuracy and timeout options', async () => {
  installBrowser();
  geo.respond({ position: fix(8) });
  await LocationTracking.getCurrentPosition({ samples: 1, desiredAccuracy: 'balanced', timeout: 5000 });
  assert.equal(geo.calls.length, 1);
  assert.equal(geo.calls[0].enableHighAccuracy, false);
  assert.ok(geo.calls[0].timeout <= 5000 && geo.calls[0].timeout > 4000);
});

test('getCurrentPosition uses geolocation.locationTimeout as the default timeout', async () => {
  installBrowser();
  await LocationTracking.setConfig({ config: { geolocation: { locationTimeout: 60 } } });
  try {
    geo.respond({ hang: true });
    await rejectsWithCode(LocationTracking.getCurrentPosition(), 'TIMEOUT');
    assert.ok(geo.calls[0].timeout <= 60);
  } finally {
    await LocationTracking.setConfig({ config: { geolocation: { locationTimeout: null } } });
  }
});

test('getCurrentPosition merges persistence.extras with the call extras', async () => {
  installBrowser();
  await LocationTracking.setConfig({ config: { persistence: { extras: { driver: 7, shift: 'a' } } } });
  try {
    geo.respond({ position: fix(3) });
    const location = await LocationTracking.getCurrentPosition({ samples: 1, extras: { shift: 'b', stop: 2 } });
    assert.deepEqual(location.extras, { driver: 7, shift: 'b', stop: 2 });
  } finally {
    await LocationTracking.setConfig({ config: { persistence: { extras: null } } });
  }
});

test('getCurrentPosition rejects with TIMEOUT when no fix arrives in time', async () => {
  installBrowser();
  geo.respond({ hang: true });
  const started = Date.now();
  const err = await rejectsWithCode(LocationTracking.getCurrentPosition({ timeout: 50 }), 'TIMEOUT');
  assert.ok(Date.now() - started >= 45, 'waited for the timeout');
  assert.equal(typeof err.message, 'string');
});

test('getCurrentPosition resolves the best fix so far when the timeout ends sampling', async () => {
  installBrowser();
  geo.respond({ position: fix(40) }, { position: fix(20) }, { hang: true });
  const location = await LocationTracking.getCurrentPosition({ samples: 3, timeout: 100 });
  assert.equal(location.coords.accuracy, 20);
  assert.equal(geo.calls.length, 3);
});

test('getCurrentPosition maps GeolocationPositionError codes', async () => {
  installBrowser();
  geo.respond({ error: { code: 3, message: 'Timeout expired' } });
  const timeout = await rejectsWithCode(LocationTracking.getCurrentPosition(), 'TIMEOUT');
  assert.equal(timeout.message, 'Timeout expired');

  geo.respond({ error: { code: 1, message: 'User denied Geolocation' } });
  await rejectsWithCode(LocationTracking.getCurrentPosition(), 'PERMISSION_DENIED');

  geo.respond({ error: { code: 2, message: '' } });
  const unavailable = await rejectsWithCode(LocationTracking.getCurrentPosition(), 'UNAVAILABLE');
  assert.ok(unavailable.message.length > 0, 'falls back to a default message');
});

test('getCurrentPosition with maximumAge reuses a recent fix without asking the browser', async () => {
  installBrowser();
  geo.respond({ position: fix(6, { latitude: 10 }) });
  const first = await LocationTracking.getCurrentPosition({ samples: 1 });

  const cached = await LocationTracking.getCurrentPosition({ maximumAge: 60000 });
  assert.equal(geo.calls.length, 1, 'no new browser request');
  assert.equal(cached.coords.latitude, 10);
  assert.equal(cached.timestamp, first.timestamp);
  assert.notEqual(cached.uuid, first.uuid);
  assert.equal(cached.event, 'current_position');

  // A fix older than maximumAge is not reused. maximumAge goes to the browser's first request only, so the
  // remaining samples are fresh fixes instead of the same cached one.
  await tick(20);
  geo.respond({ position: fix(6, { latitude: 11 }) }, { position: fix(4, { latitude: 12 }) });
  const fresh = await LocationTracking.getCurrentPosition({ samples: 2, maximumAge: 10 });
  assert.equal(geo.calls.length, 3);
  assert.equal(geo.calls[1].maximumAge, 10);
  assert.equal(geo.calls[2].maximumAge, 0);
  assert.equal(fresh.coords.latitude, 12);
});

test('getCurrentPosition emits a location event unless persist is false', async () => {
  installBrowser();
  const events = [];
  const handle = await onLocation((location) => {
    events.push(structuredClone(location));
    // Each listener gets its own copy, as on native.
    location.coords = null;
  });
  try {
    geo.respond({ position: fix(3) }, { position: fix(3) });
    const persisted = await LocationTracking.getCurrentPosition({ samples: 1 });
    await LocationTracking.getCurrentPosition({ samples: 1, persist: false });
    assert.equal(events.length, 1);
    assert.deepEqual(events[0], persisted);
    assert.equal(persisted.coords.accuracy, 3, 'the listener did not change the returned location');
  } finally {
    await handle.remove();
  }
});

test('a throwing location listener neither fails the call nor skips other listeners', async () => {
  installBrowser();
  const originalError = console.error;
  const logged = [];
  const received = [];
  console.error = (...args) => logged.push(args);
  const failing = await onLocation(() => {
    throw new Error('listener bug');
  });
  const working = await onLocation((location) => received.push(location.uuid));
  try {
    geo.respond({ position: fix(3) });
    const location = await LocationTracking.getCurrentPosition({ samples: 1 });
    assert.equal(location.event, 'current_position');
    assert.deepEqual(received, [location.uuid]);
    assert.equal(logged.length, 1);
  } finally {
    console.error = originalError;
    await failing.remove();
    await working.remove();
  }
});

/* ---------------- watchPosition / clearWatch ---------------- */

test('watchPosition delivers fixes and errors until clearWatch', async () => {
  installBrowser();
  const calls = [];
  const id = await LocationTracking.watchPosition({ extras: { trip: 1 } }, (location, error) =>
    calls.push({ location, error }),
  );
  assert.equal(typeof id, 'string');
  assert.equal(geo.watches.size, 1);
  const [[browserId, watch]] = [...geo.watches];
  assert.equal(watch.options.enableHighAccuracy, true);

  watch.success(fix(7, { latitude: 3 }));
  watch.success(fix(9, { latitude: 4 }));
  watch.error({ code: 1, message: 'denied' });
  watch.error({ code: 2, message: 'unavailable' });
  watch.error({ code: 3, message: 'timeout' });

  assert.equal(calls.length, 5);
  assert.equal(calls[0].location.event, 'watch_position');
  assert.equal(calls[0].location.coords.latitude, 3);
  assert.equal(calls[0].location.backend, 'web');
  assert.deepEqual(calls[0].location.extras, { trip: 1 });
  assert.equal(calls[0].error, undefined);
  assert.equal(calls[1].location.coords.latitude, 4);
  assert.notEqual(calls[0].location.uuid, calls[1].location.uuid);
  assert.deepEqual(calls.slice(2), [
    { location: null, error: { code: 'PERMISSION_DENIED', message: 'denied' } },
    { location: null, error: { code: 'UNAVAILABLE', message: 'unavailable' } },
    { location: null, error: { code: 'TIMEOUT', message: 'timeout' } },
  ]);

  await LocationTracking.clearWatch({ id });
  assert.deepEqual(geo.cleared, [browserId]);
  // Late browser callbacks after clearWatch are dropped.
  watch.success(fix(1));
  watch.error({ code: 2, message: 'late' });
  assert.equal(calls.length, 5);

  // Clearing again, or clearing an unknown id, is a no-op.
  await LocationTracking.clearWatch({ id });
  await LocationTracking.clearWatch({ id: 'unknown' });
  assert.deepEqual(geo.cleared, [browserId]);
});

test('concurrent watches get distinct ids and are cleared independently', async () => {
  installBrowser();
  const a = [];
  const b = [];
  const idA = await LocationTracking.watchPosition({ desiredAccuracy: 'low' }, (l) => a.push(l));
  const idB = await LocationTracking.watchPosition({}, (l) => b.push(l));
  assert.notEqual(idA, idB);
  const [[browserA, watchA], [browserB, watchB]] = [...geo.watches];
  assert.equal(watchA.options.enableHighAccuracy, false);

  await LocationTracking.clearWatch({ id: idA });
  watchA.success(fix(5));
  watchB.success(fix(5));
  assert.equal(a.length, 0);
  assert.equal(b.length, 1);
  assert.deepEqual(geo.cleared, [browserA]);
  await LocationTracking.clearWatch({ id: idB });
  assert.deepEqual(geo.cleared, [browserA, browserB]);
});

test('clearWatch stops the watch on the Geolocation object that started it', async () => {
  const original = installBrowser();
  const id = await LocationTracking.watchPosition({}, () => {});
  const [browserId] = [...original.watches.keys()];
  const replacement = installBrowser();
  await LocationTracking.clearWatch({ id });
  assert.deepEqual(original.cleared, [browserId]);
  assert.deepEqual(replacement.cleared, []);
});

test('watchPosition emits location events only with persist: true', async () => {
  installBrowser();
  const events = [];
  const handle = await onLocation((location) => events.push(location));
  try {
    const plain = await LocationTracking.watchPosition({}, () => {});
    const persisted = await LocationTracking.watchPosition({ persist: true }, () => {});
    const [[, plainWatch], [, persistedWatch]] = [...geo.watches];
    plainWatch.success(fix(5));
    persistedWatch.success(fix(6));
    assert.equal(events.length, 1);
    assert.equal(events[0].event, 'watch_position');
    assert.equal(events[0].coords.accuracy, 6);
    await LocationTracking.clearWatch({ id: plain });
    await LocationTracking.clearWatch({ id: persisted });
  } finally {
    await handle.remove();
  }
});

test('watchPosition and clearWatch validate their arguments', async () => {
  installBrowser();
  await rejectsWithCode(LocationTracking.watchPosition({}), 'INVALID_ARGUMENT');
  await rejectsWithCode(LocationTracking.clearWatch({}), 'INVALID_ARGUMENT');
  await rejectsWithCode(LocationTracking.clearWatch(), 'INVALID_ARGUMENT');
  assert.equal(geo.watches.size, 0);
});

test('geolocation methods reject with UNAVAILABLE without navigator.geolocation', async () => {
  setGlobal('navigator', { userAgent: 'x' });
  try {
    await rejectsWithCode(LocationTracking.getCurrentPosition(), 'UNAVAILABLE');
    await rejectsWithCode(LocationTracking.watchPosition({}, () => {}), 'UNAVAILABLE');
  } finally {
    installBrowser();
  }
});

test('uuids fall back to crypto.getRandomValues, then Math.random', async () => {
  const originalCrypto = globalThis.crypto;
  try {
    setGlobal('crypto', { getRandomValues: (bytes) => bytes.fill(0xab) });
    installBrowser();
    geo.respond({ position: fix(3) });
    const fromRandomValues = await LocationTracking.getCurrentPosition({ samples: 1 });
    assert.equal(fromRandomValues.uuid, 'abababab-abab-4bab-abab-abababababab');

    setGlobal('crypto', undefined);
    geo.respond({ position: fix(3) });
    const fromMathRandom = await LocationTracking.getCurrentPosition({ samples: 1 });
    assert.match(fromMathRandom.uuid, UUID_V4);
  } finally {
    setGlobal('crypto', originalCrypto);
  }
});

/* ---------------- permissions ---------------- */

function permissionsApi(...states) {
  const queries = [];
  return {
    queries,
    async query(descriptor) {
      queries.push(descriptor);
      return { state: states.length > 1 ? states.shift() : states[0] };
    },
  };
}

test('checkPermissions reads location from the Permissions API; the rest are granted', async () => {
  const permissions = permissionsApi('denied');
  installBrowser({ permissions });
  assert.deepEqual(await LocationTracking.checkPermissions(), {
    location: 'denied',
    backgroundLocation: 'granted',
    activityRecognition: 'granted',
    notifications: 'granted',
  });
  assert.deepEqual(permissions.queries, [{ name: 'geolocation' }]);
});

test('checkPermissions reports prompt when the Permissions API is missing or fails', async () => {
  installBrowser();
  assert.equal((await LocationTracking.checkPermissions()).location, 'prompt');
  installBrowser({
    permissions: {
      query: async () => {
        throw new TypeError('unsupported');
      },
    },
  });
  assert.equal((await LocationTracking.checkPermissions()).location, 'prompt');
});

test('requestPermissions triggers the browser prompt with a position request', async () => {
  // The Permissions API still says 'prompt' afterwards (a one-time grant); the fix proves permission was granted.
  const permissions = permissionsApi('prompt');
  installBrowser({ permissions });
  geo.respond({ position: fix(50) });
  const status = await LocationTracking.requestPermissions();
  assert.equal(status.location, 'granted');
  assert.equal(geo.calls.length, 1);
  assert.equal(geo.calls[0].maximumAge, Infinity);
  assert.equal(geo.calls[0].enableHighAccuracy, false);
});

test('requestPermissions reports denied when the prompt is refused', async () => {
  installBrowser({ permissions: permissionsApi('prompt', 'denied') });
  geo.respond({ error: { code: 1, message: 'User denied Geolocation' } });
  assert.equal((await LocationTracking.requestPermissions()).location, 'denied');
});

test('requestPermissions keeps the Permissions API answer after a dismissed prompt', async () => {
  installBrowser({ permissions: permissionsApi('prompt', 'prompt') });
  geo.respond({ error: { code: 1, message: 'dismissed' } });
  assert.equal((await LocationTracking.requestPermissions({ permissions: ['location'] })).location, 'prompt');
});

test('requestPermissions without the Permissions API reports the prompt outcome', async () => {
  installBrowser();
  geo.respond({ error: { code: 1, message: 'denied' } });
  assert.equal((await LocationTracking.requestPermissions()).location, 'denied');
  geo.respond({ position: fix(10) });
  assert.equal((await LocationTracking.requestPermissions()).location, 'granted');
  // TIMEOUT / POSITION_UNAVAILABLE only happen after permission was granted.
  geo.respond({ error: { code: 3, message: 'timeout' } });
  assert.equal((await LocationTracking.requestPermissions()).location, 'granted');
});

test('requestPermissions does not prompt when location is decided or not requested', async () => {
  installBrowser({ permissions: permissionsApi('granted') });
  assert.equal((await LocationTracking.requestPermissions()).location, 'granted');
  installBrowser({ permissions: permissionsApi('denied') });
  assert.equal((await LocationTracking.requestPermissions()).location, 'denied');
  installBrowser({ permissions: permissionsApi('prompt') });
  const status = await LocationTracking.requestPermissions({ permissions: ['notifications', 'backgroundLocation'] });
  assert.equal(status.location, 'prompt');
  assert.equal(status.notifications, 'granted');
  assert.equal(geo.calls.length, 0);
});

/* ---------------- device info ---------------- */

async function deviceInfoFor(userAgent, platform) {
  setGlobal('navigator', { userAgent, platform });
  try {
    return await LocationTracking.getDeviceInfo();
  } finally {
    installBrowser();
  }
}

test('getDeviceInfo reports the web platform and the plugin version', async () => {
  const { version } = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
  const info = await deviceInfoFor(
    'Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A.240205.004) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36',
    'Linux armv81',
  );
  assert.deepEqual(info, {
    platform: 'web',
    manufacturer: 'unknown',
    model: 'Pixel 7',
    brand: 'Chrome 120',
    osVersion: 'Android 14',
    sdkInt: -1,
    pluginVersion: version,
    gmsAvailable: false,
    hmsAvailable: false,
    backend: 'web',
    packagedProviders: [],
  });
});

test('getDeviceInfo parses common user agents', async () => {
  const cases = [
    [
      'Mozilla/5.0 (iPhone; CPU iPhone OS 17_1_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1.2 Mobile/15E148 Safari/604.1',
      'iPhone',
      { manufacturer: 'Apple', model: 'iPhone', osVersion: 'iOS 17.1.2', brand: 'Safari 17' },
    ],
    [
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36',
      'MacIntel',
      { manufacturer: 'Apple', model: 'Macintosh', osVersion: 'macOS 10.15.7', brand: 'Chrome 121' },
    ],
    [
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.2210.91',
      'Win32',
      { manufacturer: 'unknown', model: 'Win32', osVersion: 'Windows 10.0', brand: 'Edge 120' },
    ],
    [
      'Mozilla/5.0 (Android 14; Mobile; rv:121.0) Gecko/121.0 Firefox/121.0',
      undefined,
      { manufacturer: 'unknown', model: 'unknown', osVersion: 'Android 14', brand: 'Firefox 121' },
    ],
    [
      'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
      'Linux armv8l',
      { manufacturer: 'unknown', model: 'Linux armv8l', osVersion: 'Android 10', brand: 'Chrome 120' },
    ],
    [
      'Mozilla/5.0 (Linux; Android 12; HarmonyOS; NOH-AN00; HMSCore 6.11.0.302) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/99.0.4844.88 HuaweiBrowser/14.0.0.320 Mobile Safari/537.36',
      undefined,
      { manufacturer: 'unknown', model: 'NOH-AN00', osVersion: 'Android 12', brand: 'Huawei Browser 14' },
    ],
    [
      'Mozilla/5.0 (Linux; U; Android 4.0.3; ko-kr; LG-L160L Build/IML74K) AppleWebkit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30',
      undefined,
      { manufacturer: 'unknown', model: 'LG-L160L', osVersion: 'Android 4.0.3', brand: 'Safari 4' },
    ],
    ['', undefined, { manufacturer: 'unknown', model: 'unknown', osVersion: 'unknown', brand: 'unknown' }],
  ];
  for (const [userAgent, platform, expected] of cases) {
    const info = await deviceInfoFor(userAgent, platform);
    assert.deepEqual(
      { manufacturer: info.manufacturer, model: info.model, osVersion: info.osVersion, brand: info.brand },
      expected,
      userAgent,
    );
    assert.equal(info.platform, 'web');
  }
});
