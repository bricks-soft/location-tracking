import assert from 'node:assert/strict';
import { copyFile, mkdir } from 'node:fs/promises';
import { createServer } from 'node:net';
import { join } from 'node:path';
import {
  PLACES,
  PluginCallError,
  assertions,
  scenario,
  sleep,
  waitUntil,
  type IosWebSmokeResult,
  type LatLon,
  type MockBackOffice,
  type ScenarioContext,
  type StateJson,
  type WireRecord,
} from '@bricks-soft/e2e-kit';

const BOTH = ['android', 'ios'] as const;
const IOS = ['ios'] as const;
const FAR: LatLon = { lat: 24.9, lon: 46.8 };
const ROUTE_SPEED_MPS = 20;
const GEOFENCE_SPEED_MPS = 25;

interface Timing {
  moving: number;
  background: number;
  stationary: number;
  heartbeat: number;
  restore: number;
}

function timing(ctx: ScenarioContext): Timing {
  return ctx.platform === 'android'
    ? { moving: 10 * 60_000, background: 3 * 60_000, stationary: 5 * 60_000, heartbeat: 5 * 60_000, restore: 4 * 60_000 }
    : { moving: 90_000, background: 60_000, stationary: 120_000, heartbeat: 120_000, restore: 120_000 };
}

function mine(ctx: ScenarioContext, office: MockBackOffice): WireRecord[] {
  return office
    .records({ unique: true })
    .map((stored) => stored.record)
    .filter((record) => record.extras?.['scenario'] === ctx.id);
}

async function record(
  ctx: ScenarioContext,
  office: MockBackOffice,
  what: string,
  match: (record: WireRecord) => boolean,
  timeoutMs: number,
): Promise<WireRecord> {
  try {
    return await office.waitFor(() => mine(ctx, office).find(match), { timeoutMs, intervalMs: 1000, message: what, signal: ctx.signal });
  } catch (error) {
    throw new Error(`${what}: not received within ${timeoutMs / 1000} s. Records so far:\n${assertions.timeline(mine(ctx, office))}`, { cause: error });
  }
}

async function until<T>(ctx: ScenarioContext, what: string, probe: () => Promise<T | undefined | false>, timeoutMs: number): Promise<T> {
  return waitUntil(probe, { timeoutMs, intervalMs: 1000, message: what, signal: ctx.signal });
}

async function closedPort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '0.0.0.0', () => {
      const address = server.address();
      const port = typeof address === 'object' && address ? address.port : 0;
      server.close(() => resolve(port));
    });
  });
}

async function fresh(ctx: ScenarioContext, position: LatLon, patch: Record<string, unknown>, url?: string): Promise<Record<string, unknown>> {
  await ctx.driver.reset({ location: 'always', position });
  await ctx.driver.launch();
  await ctx.driver.call('stop').catch(() => undefined);
  const config = await ctx.testConfig(url === undefined ? { patch } : { url, patch });
  await ctx.driver.call('ready', { config, reset: true });
  await ctx.driver.call('destroyLocations');
  return config;
}

const QUIET = {
  heartbeat: { enabled: false, minInterval: 60, maxInterval: 60 },
  geolocation: { stopTimeout: 1, stopAfterElapsedMinutes: 20, filter: { maxImpliedSpeed: 0 } },
};

scenario(
  'P-S01',
  'route: start, moving, background, stationary and a background heartbeat; iOS check-in notification',
  async (ctx) => {
    const d = ctx.driver;
    const wait = timing(ctx);
    const office = await ctx.backOffice();
    await fresh(ctx, PLACES.hq, { ...QUIET, notification: { resume: { enabled: true, delay: 120, title: 'Route check-in' } } });

    const provider = await d.call<{ accuracy: string }>('getProviderState');
    const permissions = await d.call<Record<string, string>>('checkPermissions');
    const sensors = await d.call<Record<string, boolean>>('getSensors');
    await ctx.artifacts.writeJson('capabilities.json', { provider, permissions, sensors });
    assert.equal(provider.accuracy, 'precise', 'the route runs with precise location');
    assert.equal(permissions['notifications'], 'granted', 'notifications are allowed (on iOS the check-in UI test grants them)');
    if (d.platform === 'ios') {
      assert.ok(!sensors['significantMotion'] || permissions['activityRecognition'] !== 'granted', 'no Core Motion: movement comes from displacement');
    }

    assert.equal((await d.call<StateJson>('start')).enabled, true, 'start() enables tracking');
    const start = await record(ctx, office, 'tracking_start', (r) => r.event === 'tracking_start', 60_000);
    assert.equal(start.reason, 'start');
    const device = start.device ?? {};
    assert.ok(typeof device['manufacturer'] === 'string' && device['manufacturer'] !== '', `tracking_start has the manufacturer: ${JSON.stringify(device)}`);
    if (d.platform === 'ios') assert.equal(device['manufacturer'], 'Apple');
    assert.ok(Number(device['ram_total_bytes']) > 0, 'tracking_start has the RAM size');
    assert.ok(typeof device['os_version'] === 'string' && device['os_version'] !== '', 'tracking_start has the OS version');
    assert.equal('disk_free_bytes' in device, false, 'tracking_start has no free disk reading');

    let fireBound = 0;
    if (d.platform === 'ios') {
      const initial = await until(ctx, 'check-in scheduled', async () => {
        const snapshot = await d.snapshot();
        return snapshot.pending.length > 0 ? snapshot : undefined;
      }, 45_000);
      fireBound = Date.now() + 120_000;
      assert.equal(initial.pending.length, 1, 'one check-in notification is scheduled');
      assert.equal(initial.pending[0]!.interval, 120, 'the check-in uses notification.resume.delay');
    }

    const route = await d.startRoute([PLACES.hq, FAR], ROUTE_SPEED_MPS);
    try {
      const moving = await record(ctx, office, 'motionchange is_moving true', (r) => r.event === 'motionchange' && r.is_moving, wait.moving);
      await record(ctx, office, 'a location record while moving', (r) => r.event === 'location' && r.recorded_at >= moving.recorded_at, wait.background);
      assert.equal((await d.call<StateJson>('getState')).isMoving, true, 'getState() is moving');

      await d.background();
      if (d.platform === 'ios') {
        await until(ctx, 'the app in the background while Settings is in front', async () => (await d.snapshot()).appState === 'background', 30_000);
      }
      const backgrounded = mine(ctx, office).filter((r) => r.event === 'location').length;
      await until(ctx, 'location records while the app is in the background', async () => mine(ctx, office).filter((r) => r.event === 'location').length >= backgrounded + 3, wait.background);
      if (d.platform === 'ios') {
        assert.equal((await d.snapshot()).appState, 'background', 'the route continues while Settings is in front');
        await sleep(Math.max(0, fireBound + 15_000 - Date.now()), ctx.signal);
        const moved = await d.snapshot();
        assert.ok(moved.pending.length === 1 && moved.delivered === 0 && moved.state.isMoving, `activity moved the check-in past its first fire time: ${JSON.stringify({ pending: moved.pending, delivered: moved.delivered })}`);
      }
      await d.foreground();
    } finally {
      await route.stop();
    }

    const last = mine(ctx, office).filter((r) => r.coords).at(-1);
    await d.setPosition(last?.coords ? { lat: last.coords.latitude, lon: last.coords.longitude } : FAR);
    await until(ctx, 'stationary after stopTimeout', async () => !(await d.call<StateJson>('getState')).isMoving, wait.stationary);
    assert.equal((await d.call<StateJson>('getState')).enabled, true, 'the stationary profile keeps tracking on');

    await d.call('setConfig', { config: { heartbeat: { enabled: true, minInterval: 60, maxInterval: 60, uploadKeepAwake: 1 } } });
    const status = await d.call<{ strategy: string }>('getHeartbeatStatus');
    if (d.platform === 'ios') assert.equal(status.strategy, 'timer', 'iOS arms the heartbeat timer');
    else assert.ok(['exact', 'listener_with_backup', 'idle_paced'].includes(status.strategy), `Android heartbeat strategy: ${status.strategy}`);
    const beforeHeartbeat = new Set(mine(ctx, office).map((r) => r.uuid));
    await d.background();
    const beat = await record(ctx, office, 'a heartbeat while the app is in the background', (r) => r.event === 'heartbeat' && !beforeHeartbeat.has(r.uuid), wait.heartbeat);
    assert.equal(beat.heartbeat?.strategy, status.strategy, 'the heartbeat record names the strategy');
    const thermal = beat.device?.['thermal_status'];
    assert.ok(thermal !== undefined && thermal !== null && thermal !== '', `the heartbeat has a thermal reading: ${JSON.stringify(beat.device)}`);
    if (d.platform === 'ios') assert.equal((await d.snapshot()).appState, 'background', 'the heartbeat came while the app was in the background');

    await d.foreground();
    await d.call('stop');
    if (d.platform === 'ios') {
      await until(ctx, 'stop cancels the check-in', async () => (await d.snapshot()).pending.length === 0, 30_000);
    }
  },
  { timeoutMs: 30 * 60_000, requires: { platforms: BOTH } },
);

scenario(
  'P-S02',
  'offline: records made while the server is unreachable upload later with their UUIDs',
  async (ctx) => {
    const d = ctx.driver;
    const office = await ctx.backOffice();
    const dead = await closedPort();
    await fresh(ctx, PLACES.hq, { ...QUIET, notification: { resume: { enabled: false } }, http: { timeout: 500 } }, `http://${d.hostAddress}:${dead}/locations`);
    await d.call('start');
    await until(ctx, 'tracking_start queued', async () => (await d.call<{ locations: WireRecord[] }>('getLocations')).locations.some((r) => r.event === 'tracking_start'), 60_000);
    await sleep(5000, ctx.signal);
    const queued = (await d.call<{ locations: WireRecord[] }>('getLocations')).locations;
    const uuids = queued.map((r) => r.uuid);
    assert.ok(queued.some((r) => r.event === 'tracking_start'), 'tracking_start is queued');
    assert.equal(mine(ctx, office).length, 0, 'nothing reached the server while it was unreachable');

    await d.call('setConfig', { config: { http: { url: office.url('/locations', d.hostAddress), timeout: 30_000 } } });
    await d.call('sync').catch((error: unknown) => ctx.log(`sync: ${String(error)}`));
    await until(ctx, 'every queued record uploaded', async () => {
      const received = new Set(mine(ctx, office).map((r) => r.uuid));
      return uuids.every((uuid) => received.has(uuid));
    }, 90_000);
    for (const sent of mine(ctx, office).filter((r) => uuids.includes(r.uuid))) {
      assert.ok(sent.sent_at !== undefined && sent.sent_at >= sent.recorded_at, `sent_at after recorded_at: ${assertions.describeRecord(sent)}`);
    }
    await d.call('stop');
  },
  { timeoutMs: 15 * 60_000, requires: { platforms: BOTH } },
);

scenario(
  'P-S03',
  'SIGKILL while tracking, relaunch: tracking restored with the original session; iOS willTerminate honours stopOnTerminate',
  async (ctx) => {
    const d = ctx.driver;
    const wait = timing(ctx);
    const office = await ctx.backOffice();
    const config = await fresh(ctx, PLACES.hq, { ...QUIET, notification: { resume: { enabled: true, delay: 120 } } });
    for (const stopOnTerminate of [true, false]) {
      await d.call('setConfig', { config: { app: { stopOnTerminate } } });
      const earlier = new Set(mine(ctx, office).map((r) => r.uuid));
      await d.call('start');
      await record(ctx, office, 'tracking_start before the kill', (r) => r.event === 'tracking_start' && r.reason === 'start' && !earlier.has(r.uuid), 60_000);
      const before = d.platform === 'ios' ? (await d.snapshot()).runtime : undefined;
      const seen = new Set(mine(ctx, office).map((r) => r.uuid));
      await d.kill();
      await d.launch();
      const restore = await record(ctx, office, `tracking_start after the relaunch (stopOnTerminate ${stopOnTerminate})`, (r) => r.event === 'tracking_start' && !seen.has(r.uuid), wait.restore);
      assert.equal(restore.reason, 'restore', `SIGKILL with stopOnTerminate ${stopOnTerminate} restores: ${assertions.describeRecord(restore)}`);
      const app = { ...(config['app'] as Record<string, unknown>), stopOnTerminate };
      assert.equal((await d.call<StateJson>('ready', { config: { ...config, app }, reset: true })).enabled, true, 'ready() after the relaunch keeps tracking on');
      if (d.platform === 'ios') {
        const restored = await d.snapshot();
        assert.equal(restored.runtime['trackingStartedAt'], before?.['trackingStartedAt'], 'the session start is kept');
        assert.equal(restored.runtime['trackingStartedElapsed'], before?.['trackingStartedElapsed'], 'the session deadline is kept');
        assert.equal(restored.audit[0]?.event, 'tracking_start', 'the native listener registered before bootstrap sees the restore first');
        assert.equal(restored.audit[0]?.reason, 'restore');
        const terminated = await d.willTerminate();
        assert.equal(terminated.enabled, !stopOnTerminate, `willTerminate with stopOnTerminate ${stopOnTerminate}`);
        if (stopOnTerminate) {
          const stopped = await d.snapshot();
          assert.equal(stopped.state.lastStopReason, 'terminate');
          assert.equal(stopped.pending.length, 0, 'termination cancels the check-in');
        }
      }
      await d.call('stop');
    }
  },
  { timeoutMs: 20 * 60_000, requires: { platforms: BOTH } },
);

scenario(
  'P-S04',
  'device restart while tracking: tracking restored (Android at boot, iOS at the next launch)',
  async (ctx) => {
    const d = ctx.driver;
    const wait = timing(ctx);
    const office = await ctx.backOffice();
    const config = await fresh(ctx, PLACES.hq, { ...QUIET, notification: { resume: { enabled: false } } });
    await d.call('start');
    await record(ctx, office, 'tracking_start before the restart', (r) => r.event === 'tracking_start' && r.reason === 'start', 60_000);
    const before = d.platform === 'ios' ? (await d.snapshot()).runtime : undefined;
    const seen = new Set(mine(ctx, office).map((r) => r.uuid));
    await d.restartDevice();
    await d.setPosition(PLACES.hq);
    const restarted = (r: WireRecord) => r.event === 'tracking_start' && !seen.has(r.uuid);
    if (d.platform === 'android') {
      const boot = await record(ctx, office, 'tracking_start at boot', restarted, wait.restore);
      assert.equal(boot.reason, 'boot', assertions.describeRecord(boot));
    }
    await d.launch();
    if (d.platform === 'ios') await record(ctx, office, 'tracking_start after the relaunch', restarted, wait.restore);
    assert.equal((await d.call<StateJson>('ready', { config, reset: true })).enabled, true, 'ready() after the restart keeps tracking on');
    if (d.platform === 'ios') {
      const restore = await record(ctx, office, 'tracking_start after the relaunch', restarted, wait.restore);
      assert.equal(restore.reason, 'restore');
      const restored = await d.snapshot();
      assert.equal(restored.runtime['trackingStartedAt'], before?.['trackingStartedAt'], 'the restart keeps the session start');
      assert.equal(restored.audit[0]?.reason, 'restore', 'the restore is recorded before JavaScript runs');
    }
    await d.call('stop');
  },
  { timeoutMs: 20 * 60_000, requires: { platforms: BOTH } },
);

scenario(
  'P-S05',
  'iOS 17+ geofences: limit 20, circle and polygon ENTER/EXIT, DWELL, initialTriggerEntry false, crossings while the app was gone',
  async (ctx) => {
    const d = ctx.driver;
    assert.equal(d.platform, 'ios');
    if (d.platform !== 'ios') return;
    const office = await ctx.backOffice();
    const east = (meters: number) => assertions.offsetMeters(PLACES.hq, 0, meters);
    const start = east(-1500);
    await fresh(ctx, start, { ...QUIET, notification: { resume: { enabled: false } }, geofence: { initialTriggerEntry: true } });
    await d.call('removeGeofences');

    const many = Array.from({ length: 21 }, (_, i) => ({ identifier: `cap${String(i).padStart(2, '0')}`, latitude: PLACES.hq.lat + 0.5, longitude: 46 + i * 0.01, radius: 100 }));
    await assert.rejects(d.call('addGeofences', { geofences: many }), (error: unknown) => error instanceof PluginCallError && error.code === 'TOO_MANY_GEOFENCES');
    await d.call('addGeofences', { geofences: many.slice(0, 20) });
    assert.equal((await d.call<{ geofences: unknown[] }>('getGeofences')).geofences.length, 20, 'twenty geofences are accepted');
    await d.call('removeGeofences');

    const circle = east(-600);
    const square = east(300);
    const finish = east(1500);
    const corner = (north: number, eastward: number) => {
      const point = assertions.offsetMeters(square, north, eastward);
      return [point.lat, point.lon];
    };
    await d.call('addGeofences', {
      geofences: [
        { identifier: 'circle', latitude: circle.lat, longitude: circle.lon, radius: 150 },
        { identifier: 'polygon', vertices: [corner(-120, -120), corner(-120, 120), corner(120, 120), corner(120, -120)] },
        { identifier: 'dwell', latitude: finish.lat, longitude: finish.lon, radius: 150, notifyOnEntry: true, notifyOnExit: true, notifyOnDwell: true, loiteringDelay: 10_000 },
      ],
    });
    const state = await d.call<StateJson>('startGeofences');
    assert.ok(state.enabled && state.trackingMode === 'geofences', 'geofence mode starts');
    await sleep(10_000, ctx.signal);

    const hits = (exclude?: Set<string>) =>
      mine(ctx, office)
        .filter((r) => r.event === 'geofence' && r.geofence && !exclude?.has(r.uuid))
        .map((r) => `${r.geofence!.identifier}:${r.geofence!.action}`);
    const expect = (wanted: string[], what: string, timeoutMs = 240_000, exclude?: Set<string>) =>
      until(ctx, what, async () => {
        const seen = hits(exclude);
        return wanted.every((hit) => seen.includes(hit)) ? seen : undefined;
      }, timeoutMs);

    await d.drive([start, circle], GEOFENCE_SPEED_MPS, ctx.signal);
    let seen = await expect(['circle:ENTER'], 'circle entered');
    assert.ok(!seen.includes('circle:EXIT'), 'the circle ENTER comes from the CLMonitor condition');
    await d.drive([circle, square], GEOFENCE_SPEED_MPS, ctx.signal);
    seen = await expect(['circle:EXIT', 'polygon:ENTER'], 'circle left, polygon entered');
    assert.ok(seen.indexOf('circle:ENTER') < seen.indexOf('circle:EXIT') && !seen.includes('polygon:EXIT'), `circle EXIT follows its ENTER: ${seen.join(', ')}`);
    await d.drive([square, finish], GEOFENCE_SPEED_MPS, ctx.signal);
    seen = await expect(['polygon:EXIT', 'dwell:ENTER'], 'polygon left, dwell geofence entered');
    assert.ok(seen.indexOf('polygon:ENTER') < seen.indexOf('polygon:EXIT'), `polygon EXIT follows its ENTER: ${seen.join(', ')}`);
    seen = await expect(['dwell:DWELL'], 'DWELL after the loitering delay', 90_000);
    const at = (action: string) => Date.parse(mine(ctx, office).find((r) => r.event === 'geofence' && r.geofence?.identifier === 'dwell' && r.geofence.action === action)!.recorded_at);
    const waited = (at('DWELL') - at('ENTER')) / 1000;
    assert.ok(seen.filter((hit) => hit === 'dwell:DWELL').length === 1 && waited >= 9.5, `one DWELL ${waited.toFixed(1)} s after ENTER with a 10 s loitering delay`);

    await d.call('setConfig', { config: { geofence: { initialTriggerEntry: false } } });
    const away = east(2400);
    await d.call('addGeofence', { geofence: { identifier: 'away', latitude: away.lat, longitude: away.lon, radius: 150 } });
    await sleep(15_000, ctx.signal);
    assert.ok(!hits().some((hit) => hit.startsWith('away:')), 'a circle added while outside reports nothing with initialTriggerEntry false');

    const before = new Set(mine(ctx, office).map((r) => r.uuid));
    await d.kill();
    await sleep(2000, ctx.signal);
    await d.drive([finish, away], GEOFENCE_SPEED_MPS, ctx.signal);
    await sleep(5000, ctx.signal);
    await d.launch();
    const stored = (await d.call<{ geofences: Array<{ identifier: string }> }>('getGeofences')).geofences.map((g) => g.identifier).sort();
    assert.deepEqual(stored, ['away', 'circle', 'dwell', 'polygon'], 'stored geofences survive a SIGKILL relaunch');
    const restored = await d.call<StateJson>('getState');
    assert.ok(restored.enabled && restored.trackingMode === 'geofences', 'geofence mode is restored');
    seen = await expect(['dwell:EXIT', 'away:ENTER'], 'crossings while the app was gone are reported after the relaunch', 180_000, before);
    assert.ok(seen.filter((hit) => hit === 'dwell:EXIT').length === 1 && seen.filter((hit) => hit === 'away:ENTER').length === 1, `each crossing once: ${seen.join(', ')}`);
    await d.call('stop');
    await d.call('removeGeofences');
  },
  { timeoutMs: 25 * 60_000, requires: { platforms: IOS, ios: 17 } },
);

scenario(
  'P-S06',
  'iOS 15 and 16: geofence methods reject UNAVAILABLE',
  async (ctx) => {
    const d = ctx.driver;
    await fresh(ctx, PLACES.hq, QUIET);
    const circle = { identifier: 'a', latitude: PLACES.hq.lat, longitude: PLACES.hq.lon, radius: 100 };
    for (const [method, options] of [
      ['startGeofences', {}],
      ['addGeofence', { geofence: circle }],
      ['getGeofences', {}],
      ['geofenceExists', { identifier: 'a' }],
    ] as const) {
      await assert.rejects(d.call(method, options), (error: unknown) => error instanceof PluginCallError && error.code === 'UNAVAILABLE', `${method} rejects UNAVAILABLE`);
    }
  },
  { timeoutMs: 10 * 60_000, requires: { platforms: IOS, ios: { max: 16 } } },
);

scenario(
  'P-S07',
  'iOS WebView smoke: the JS API through the Capacitor bridge of the example app',
  async (ctx) => {
    const d = ctx.driver;
    assert.equal(d.platform, 'ios');
    if (d.platform !== 'ios') return;
    await d.reset({ location: 'always', position: PLACES.hq });
    const route = await d.startRoute([PLACES.hq, FAR], ROUTE_SPEED_MPS);
    let result: IosWebSmokeResult | undefined;
    try {
      result = await d.webSmoke();
    } finally {
      await route.stop();
      const data = await d.simctl.container(d.appId, 'data').catch(() => undefined);
      if (data) {
        await mkdir(ctx.artifacts.dir, { recursive: true });
        for (const name of ['e2e-web-smoke.png', 'e2e-web-state.json']) {
          await copyFile(join(data, 'Library/Application Support', name), ctx.artifacts.path(name)).catch(() => undefined);
        }
      }
    }
    await ctx.artifacts.writeJson('e2e-web-smoke.json', result);
    assert.ok(result);
    assert.equal(result.success, true, `the WebView smoke failed: ${JSON.stringify(result)}`);
    const view = result.view;
    assert.ok(view && view.visible && Math.min(view.width, view.height, view.windowWidth, view.windowHeight) > 0, `the smoke ran in a visible, laid out WebView: ${JSON.stringify(view)}`);
    ctx.log(`${result.checks.length} checks passed`);
  },
  { timeoutMs: 15 * 60_000, requires: { platforms: IOS } },
);
