// Plugin suite B: heartbeat and power (unit 10). Scenario ids and titles: docs/e2e/architecture.md §9 B.
//
// Every scenario prepares its own device with ctx.app.prepare(...) (data cleared, every runtime permission granted,
// neutral device state), launches the example app in e2e mode (its page changes nothing on its own) and drives the
// plugin through the debug receiver (ctx.commands). The app stays in the foreground while tracking starts, so the
// foreground service start is allowed on Android 12+. Records are read from the mock back office.
//
// Device state that a scenario changes (deep Doze, battery, screen, airplane mode, Wi-Fi/data, wall clock, time
// zone, battery-optimization allowlist) is restored in `finally`, in addition to the reset that prepare() does.
//
// Time bases used in the assertions:
// - `recorded_at`, `timestamp`, `sent_at`: device wall clock (ISO-8601 UTC);
// - `elapsed_realtime_ms`: device clock since boot (monotonic, unaffected by clock changes);
// - StoredRecord.receivedAt / RequestLogEntry.receivedAt: host clock (epoch ms).
// Assertions never mix device and host clocks without saying so.
import assert from 'node:assert/strict';
import {
  assertions,
  PLACES,
  ROUTES,
  scenario,
  sleep,
  TEST_HEARTBEAT,
  TEST_SYNC_INTERVAL_S,
  waitUntil,
  type HeartbeatStatusJson,
  type LatLon,
  type MockBackOffice,
  type RequestLogEntry,
  type ScenarioContext,
  type StateJson,
  type StoredRecord,
  type WireCoords,
  type WireRecord,
} from '@bricks-soft/e2e-kit';

// ---------------------------------------------------------------------------------------------------------------
// Constants

/** heartbeat.minInterval / maxInterval of the plugin test config, seconds (60 / 120). */
const MIN_S = TEST_HEARTBEAT.minInterval;
const MAX_S = TEST_HEARTBEAT.maxInterval;
/** Slack for alarm delivery, the provider check before a heartbeat, and the upload, seconds. */
const TOLERANCE_S = 30;
/** Spacing of idle-paced backup alarms, seconds (HeartbeatWindow.IDLE_BACKUP_SPACING_MS). */
const IDLE_SPACING_S = 9 * 60;
/**
 * AOSP AlarmManagerService.maxTriggerTime(): an inexact alarm (setAndAllowWhileIdle) may be delivered up to 75 % of
 * its delay later than requested. A 9-minute idle-paced backup alarm may therefore fire up to 6.75 minutes late.
 */
const INEXACT_WINDOW_FACTOR = 0.75;
/** Emulator replay speed of the moving scenarios, m/s (54 km/h, a car in town). */
const DRIVE_SPEED_MPS = 15;
/** http.maxBatchSize of the batching scenario (the field-force preset value). */
const MAX_BATCH = 100;
/** A priority record is uploaded "immediately": sent_at - recorded_at at most this many seconds. */
const IMMEDIATE_S = 10;
/** geolocation.stationaryRadius of pluginTestConfig (used if the config does not say otherwise), meters. */
const DEFAULT_STATIONARY_RADIUS_M = 25;
/**
 * P-H01: a fix the system produced at most this long after the anchor record cannot have replaced the heartbeat's
 * location: the engine stores stationary fixes as the last known location at most every 10 s after a record.
 */
const FIX_SETTLE_MS = 5_000;

const PRIORITY_EVENTS: ReadonlySet<string> = new Set(['heartbeat', 'tracking_start', 'tracking_stop', 'providerchange']);
const NORMAL_EVENTS: ReadonlySet<string> = new Set([
  'location',
  'motionchange',
  'current_position',
  'watch_position',
  'geofence',
]);
const HEARTBEAT_META_KEYS = ['battery_exempt', 'device_idle', 'max_interval', 'min_interval', 'next_at', 'strategy'];

// ---------------------------------------------------------------------------------------------------------------
// Small helpers

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** Epoch ms of an ISO-8601 time; fails with [what] when the value is not a time. */
function epoch(value: string | null | undefined, what: string): number {
  const ms = typeof value === 'string' ? Date.parse(value) : Number.NaN;
  assert.ok(Number.isFinite(ms), `${what}: expected an ISO-8601 time, got ${JSON.stringify(value)}`);
  return ms;
}

function seconds(ms: number): string {
  return `${(ms / 1000).toFixed(1)} s`;
}

/** One record as a single readable line (for failure messages). */
function describe(r: WireRecord): string {
  const detail =
    r.event === 'motionchange'
      ? ` is_moving=${r.is_moving}`
      : r.reason !== undefined
        ? ` reason=${r.reason}`
        : r.heartbeat !== undefined
          ? ` hb=${r.heartbeat.strategy}/idle=${r.heartbeat.device_idle}/exempt=${r.heartbeat.battery_exempt}`
          : '';
  return (
    `${r.event}${detail} recorded_at=${r.recorded_at} timestamp=${r.timestamp ?? 'null'} ` +
    `sent_at=${r.sent_at ?? '-'} elapsed=${r.elapsed_realtime_ms} boot=${r.boot_count} uuid=${r.uuid.slice(0, 8)}`
  );
}

function timeline(records: readonly WireRecord[]): string {
  return records.length === 0 ? '  (no records)' : records.map((r) => `  ${describe(r)}`).join('\n');
}

/** Creation order: elapsed time within one boot (immune to wall-clock changes), else recorded_at. */
function byCreation(a: WireRecord, b: WireRecord): number {
  if (a.boot_count === b.boot_count) return a.elapsed_realtime_ms - b.elapsed_realtime_ms;
  return Date.parse(a.recorded_at) - Date.parse(b.recorded_at);
}

function createdAfter(record: WireRecord, reference: WireRecord): boolean {
  return byCreation(record, reference) > 0 && record.uuid !== reference.uuid;
}

/** Received records (first receipt of each uuid) whose receipt is at or after [sinceHost] (host ms). */
function stored(office: MockBackOffice, sinceHost = 0): StoredRecord[] {
  return office.records({ unique: true, since: sinceHost });
}

/** Received records in creation order. */
function received(office: MockBackOffice, sinceHost = 0): WireRecord[] {
  return stored(office, sinceHost)
    .map((s) => s.record)
    .sort(byCreation);
}

/** Every receipt (duplicates included) in creation order, for the duplicate checks. */
function receivedAll(office: MockBackOffice, sinceHost = 0): WireRecord[] {
  return office
    .records({ since: sinceHost })
    .map((s) => s.record)
    .sort(byCreation);
}

/** The one stored receipt of [uuid]; fails if the record was stored more than once (or never). */
function storedOf(office: MockBackOffice, uuid: string): StoredRecord {
  const found = office.records({ uuid });
  assert.equal(found.length, 1, `record ${uuid} should be stored exactly once, found ${found.length}`);
  return found[0]!;
}

function toLatLon(coords: WireCoords): LatLon {
  return { lat: coords.latitude, lon: coords.longitude };
}

/** Reads a nested config value (the config objects are plain JSON). */
function valueAt(object: unknown, path: readonly string[]): unknown {
  let current: unknown = object;
  for (const key of path) {
    if (current === null || typeof current !== 'object') return undefined;
    current = (current as Record<string, unknown>)[key];
  }
  return current;
}

/** Returns [config] with [fields] set inside `http` (independent of how the kit's `patch` merges). */
function withHttp(config: Record<string, unknown>, fields: Record<string, unknown>): Record<string, unknown> {
  const http = valueAt(config, ['http']);
  const base = http !== null && typeof http === 'object' ? (http as Record<string, unknown>) : {};
  return { ...config, http: { ...base, ...fields } };
}

/** Runs a restore step; a failure is logged and does not hide the scenario's own result. */
async function restore(ctx: ScenarioContext, what: string, action: () => Promise<unknown>): Promise<void> {
  try {
    await action();
  } catch (error) {
    ctx.log(`restore failed (${what}): ${errorText(error)}`);
  }
}

/** Sleeps in small steps so a stop request is honored quickly. */
async function pause(ms: number, stopped: () => boolean): Promise<void> {
  for (let waited = 0; waited < ms && !stopped(); waited += 250) await sleep(250);
}

/** Distance from [point] to the polyline [path] (equirectangular projection around [point]), meters. */
function distanceToPathMeters(point: LatLon, path: readonly LatLon[]): number {
  const radius = 6371008.8;
  const cosLat = Math.cos((point.lat * Math.PI) / 180);
  const project = (p: LatLon) => ({
    x: (((p.lon - point.lon) * Math.PI) / 180) * radius * cosLat,
    y: (((p.lat - point.lat) * Math.PI) / 180) * radius,
  });
  let best = Number.POSITIVE_INFINITY;
  for (let i = 1; i < path.length; i++) {
    const a = project(path[i - 1]!);
    const b = project(path[i]!);
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const lengthSquared = dx * dx + dy * dy;
    const t = lengthSquared === 0 ? 0 : Math.max(0, Math.min(1, -(a.x * dx + a.y * dy) / lengthSquared));
    best = Math.min(best, Math.hypot(a.x + t * dx, a.y + t * dy));
  }
  return best;
}

// ---------------------------------------------------------------------------------------------------------------
// Waiting on the back office

interface WaitRecordOptions {
  /** only records received at or after this host time */
  sinceHost?: number;
  timeoutMs: number;
  /** what is expected, for the failure message */
  what: string;
}

/** Waits for the first received record matching [predicate]; fails with the received timeline. */
async function waitForRecord(
  ctx: ScenarioContext,
  office: MockBackOffice,
  predicate: (record: WireRecord) => boolean,
  options: WaitRecordOptions,
): Promise<WireRecord> {
  try {
    return await office.waitFor((o) => received(o, options.sinceHost).find(predicate), {
      timeoutMs: options.timeoutMs,
      message: options.what,
      signal: ctx.signal,
    });
  } catch (error) {
    throw new Error(
      `expected ${options.what} within ${seconds(options.timeoutMs)}; received instead:\n` +
        `${timeline(received(office, options.sinceHost))}\n(${errorText(error)})`,
    );
  }
}

/** Waits until at least [count] received records match [predicate]; resolves with all matches in creation order. */
async function waitForRecords(
  ctx: ScenarioContext,
  office: MockBackOffice,
  predicate: (record: WireRecord) => boolean,
  count: number,
  options: WaitRecordOptions,
): Promise<WireRecord[]> {
  try {
    return await office.waitFor(
      (o) => {
        const matches = received(o, options.sinceHost).filter(predicate);
        return matches.length >= count ? matches : undefined;
      },
      { timeoutMs: options.timeoutMs, message: options.what, signal: ctx.signal },
    );
  } catch (error) {
    throw new Error(
      `expected ${options.what} within ${seconds(options.timeoutMs)}; received instead:\n` +
        `${timeline(received(office, options.sinceHost))}\n(${errorText(error)})`,
    );
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Emulator location helpers

/**
 * Sends the same emulator geo fix every [intervalMs] until stopped, so the initial fix after start() finds a GNSS
 * position whenever the plugin turns GPS on. Stopped as soon as the initial motionchange exists, so no fix is
 * injected while the plugin is stationary.
 */
function startGeoFeeder(ctx: ScenarioContext, place: LatLon, intervalMs = 2_000): { stop(): Promise<void> } {
  let stopped = false;
  const done = (async () => {
    while (!stopped && !ctx.signal.aborted) {
      try {
        await ctx.adb.geoFix(place.lat, place.lon);
      } catch (error) {
        ctx.log(`geo fix failed: ${errorText(error)}`);
      }
      await pause(intervalMs, () => stopped);
    }
  })();
  return {
    async stop() {
      stopped = true;
      await done;
    },
  };
}

interface RouteLoop {
  stop(): Promise<void>;
  /** set when a replay failed (not when it was stopped) */
  readonly failure: unknown;
}

/** Replays [lead] once, then [loop] over and over, as emulator geo fixes at [speedMps], one per second. */
function startRouteLoop(ctx: ScenarioContext, lead: readonly LatLon[], loop: readonly LatLon[], speedMps: number): RouteLoop {
  const controller = new AbortController();
  const signal = AbortSignal.any([controller.signal, ctx.signal]);
  let failure: unknown;
  const done = (async () => {
    try {
      if (lead.length >= 2) await ctx.adb.playRoute(lead, { speedMps, intervalMs: 1_000, signal });
      while (!signal.aborted) await ctx.adb.playRoute(loop, { speedMps, intervalMs: 1_000, signal });
    } catch (error) {
      if (!signal.aborted) failure = error;
    }
  })();
  return {
    async stop() {
      controller.abort();
      await done;
    },
    get failure() {
      return failure;
    },
  };
}

// ---------------------------------------------------------------------------------------------------------------
// Starting tracking

interface Started {
  /** host ms taken before ready(): every record of this tracking session is received after it */
  sinceHost: number;
  /** State returned by ready() */
  readyState: StateJson;
  /** State returned by start() */
  startState: StateJson;
  /** the session's tracking_start (reason start), as received */
  trackingStart: WireRecord;
}

/**
 * Puts the emulator at [place], calls ready(config, reset) and start() through the debug receiver (the app is in the
 * foreground), feeds geo fixes until the plugin has recorded the initial motionchange, and waits for the
 * tracking_start upload. The initial motionchange itself may be held back by syncInterval: callers wait for it
 * themselves.
 */
async function startTracking(
  ctx: ScenarioContext,
  office: MockBackOffice,
  config: Record<string, unknown>,
  place: LatLon,
): Promise<Started> {
  const sinceHost = Date.now();
  await ctx.adb.geoFix(place.lat, place.lon);
  const readyState = await ctx.commands.ready(config, true);
  assert.equal(readyState.enabled, false, `ready() before start(): expected enabled false, got ${JSON.stringify(readyState)}`);
  const feeder = startGeoFeeder(ctx, place);
  let startState: StateJson;
  try {
    startState = await ctx.commands.start();
    assert.equal(startState.enabled, true, `start(): expected enabled true, got ${JSON.stringify(startState)}`);
    assert.equal(startState.trackingMode, 'location', `start(): expected trackingMode 'location'`);
    const startRecordAt = startState.lastRecordAt;
    // The initial motionchange is recorded after the first fix (or after locationTimeout, 30 s). It is the first record
    // after tracking_start, so heartbeatStatus().lastRecordAt moves past the tracking_start's creation time.
    await waitUntil(
      async () => {
        if (office.records({ event: 'motionchange', since: sinceHost }).length > 0) return true;
        const status = await ctx.commands.heartbeatStatus();
        return status.lastRecordAt !== null && status.lastRecordAt !== startRecordAt;
      },
      {
        timeoutMs: 75_000,
        intervalMs: 2_000,
        message: 'the initial motionchange after start() (the plugin waits at most locationTimeout, 30 s, for a fix)',
        signal: ctx.signal,
      },
    );
  } finally {
    await feeder.stop();
  }
  const trackingStart = await waitForRecord(ctx, office, (r) => r.event === 'tracking_start' && r.reason === 'start', {
    sinceHost,
    timeoutMs: 120_000,
    what: "the tracking_start record with reason 'start'",
  });
  return { sinceHost, readyState, startState, trackingStart };
}

/** Waits for the initial stationary motionchange (uploaded at once when syncInterval is 0) and checks it has a fix. */
async function waitForAnchor(ctx: ScenarioContext, office: MockBackOffice, started: Started): Promise<WireRecord> {
  const anchor = await waitForRecord(
    ctx,
    office,
    (r) => r.event === 'motionchange' && !r.is_moving && createdAfter(r, started.trackingStart),
    { sinceHost: started.sinceHost, timeoutMs: 60_000, what: 'the initial motionchange (is_moving: false) after tracking_start' },
  );
  assert.ok(
    anchor.coords !== null && anchor.timestamp !== null,
    `the initial motionchange must carry the emulator's geo fix (sent before and during start()); got:\n  ${describe(anchor)}`,
  );
  return anchor;
}

// ---------------------------------------------------------------------------------------------------------------
// Deep Doze

/**
 * App to the background, battery unplugged, screen off, `deviceidle force-idle`; waits for deep state IDLE. The
 * battery is unplugged before the screen is turned off: the CI baseline sets `svc power stayon true`, which keeps the
 * screen awake while plugged in; unplugging restores the normal screen behavior.
 */
async function enterDeepIdle(ctx: ScenarioContext): Promise<void> {
  await ctx.adb.keyHome();
  await ctx.adb.batteryUnplug();
  await ctx.adb.screenOff();
  await ctx.adb.deviceIdle.forceIdle();
  await waitUntil(async () => (await ctx.adb.deviceIdle.state()) === 'IDLE', {
    timeoutMs: 30_000,
    intervalMs: 1_000,
    message: "deep Doze state 'IDLE' after deviceidle force-idle",
    signal: ctx.signal,
  });
}

/**
 * getHeartbeatStatus() once the plugin has seen deep Doze: it re-arms the heartbeat when the asynchronous
 * ACTION_DEVICE_IDLE_MODE_CHANGED broadcast arrives, which can be after `deviceidle` already reports IDLE.
 */
async function idleStatus(ctx: ScenarioContext, strategy: HeartbeatStatusJson['strategy']): Promise<HeartbeatStatusJson> {
  let last: HeartbeatStatusJson | undefined;
  try {
    return await waitUntil(
      async () => {
        last = await ctx.commands.heartbeatStatus();
        return last.isDeviceIdleMode && last.strategy === strategy ? last : undefined;
      },
      { timeoutMs: 20_000, intervalMs: 1_000, message: `heartbeat strategy '${strategy}' in deep Doze`, signal: ctx.signal },
    );
  } catch (error) {
    throw new Error(
      `expected getHeartbeatStatus() to report isDeviceIdleMode true and strategy '${strategy}' within 20 s of deep Doze; ` +
        `last status: ${JSON.stringify(last ?? null)} (${errorText(error)})`,
    );
  }
}

async function leaveDeepIdle(ctx: ScenarioContext): Promise<void> {
  await restore(ctx, 'deviceidle unforce', () => ctx.adb.deviceIdle.unforce());
  await restore(ctx, 'battery reset', () => ctx.adb.batteryReset());
  await restore(ctx, 'screen on', () => ctx.adb.screenOn());
}

// ---------------------------------------------------------------------------------------------------------------
// Heartbeat metadata (contract §5)

/** Asserts the shape of a heartbeat record's `heartbeat` object and returns it. */
function heartbeatMeta(record: WireRecord): NonNullable<WireRecord['heartbeat']> {
  const meta = record.heartbeat;
  assert.ok(meta !== undefined && meta !== null, `heartbeat record without the 'heartbeat' metadata object:\n  ${describe(record)}`);
  assert.deepEqual(
    Object.keys(meta).sort(),
    HEARTBEAT_META_KEYS,
    `heartbeat metadata keys of ${record.uuid}: expected ${HEARTBEAT_META_KEYS.join(', ')}, got ${JSON.stringify(meta)}`,
  );
  assert.ok(
    meta.strategy === 'exact' || meta.strategy === 'listener_with_backup' || meta.strategy === 'idle_paced',
    `heartbeat.strategy must be exact | listener_with_backup | idle_paced, got ${JSON.stringify(meta.strategy)}`,
  );
  assert.equal(typeof meta.min_interval, 'number', `heartbeat.min_interval must be a number: ${JSON.stringify(meta)}`);
  assert.equal(typeof meta.max_interval, 'number', `heartbeat.max_interval must be a number: ${JSON.stringify(meta)}`);
  assert.ok(meta.next_at === null || typeof meta.next_at === 'string', `heartbeat.next_at must be a string or null`);
  assert.equal(typeof meta.battery_exempt, 'boolean', `heartbeat.battery_exempt must be a boolean`);
  assert.equal(typeof meta.device_idle, 'boolean', `heartbeat.device_idle must be a boolean`);
  assert.equal(meta.min_interval, MIN_S, `heartbeat.min_interval: expected the config value ${MIN_S}`);
  assert.equal(meta.max_interval, MAX_S, `heartbeat.max_interval: expected the config value ${MAX_S}`);
  return meta;
}

/**
 * getHeartbeatStatus() once the upload of the heartbeat just received has completed on the device: the server stores
 * a record when the request arrives, the plugin deletes it from its queue only after reading the response, so
 * pendingHeartbeats may still be 1 for a moment.
 */
async function settledStatus(ctx: ScenarioContext): Promise<HeartbeatStatusJson> {
  return waitUntil(
    async () => {
      const status = await ctx.commands.heartbeatStatus();
      return status.pendingHeartbeats === 0 ? status : undefined;
    },
    { timeoutMs: 15_000, intervalMs: 1_000, message: 'getHeartbeatStatus() with pendingHeartbeats 0 after the upload', signal: ctx.signal },
  );
}

interface MetaExpectation {
  strategy: 'exact' | 'listener_with_backup' | 'idle_paced';
  batteryExempt: boolean;
  deviceIdle: boolean;
}

/**
 * Checks a heartbeat's metadata against [expect] and against getHeartbeatStatus() taken right after it (the status
 * must still describe the window this heartbeat opened: its lastRecordAt is the heartbeat's recorded_at).
 */
function assertMetaMatchesStatus(record: WireRecord, status: HeartbeatStatusJson, expect: MetaExpectation): void {
  const meta = heartbeatMeta(record);
  const context = `heartbeat ${describe(record)}\nstatus ${JSON.stringify(status)}`;
  assert.equal(status.lastHeartbeatAt, record.recorded_at, `status.lastHeartbeatAt must be this heartbeat's recorded_at\n${context}`);
  assert.equal(status.lastRecordAt, record.recorded_at, `no record may follow the heartbeat before the status call\n${context}`);
  assert.equal(status.enabled, true, `status.enabled\n${context}`);
  assert.equal(meta.strategy, expect.strategy, `heartbeat.strategy\n${context}`);
  assert.equal(status.strategy, meta.strategy, `status.strategy must equal heartbeat.strategy\n${context}`);
  assert.equal(status.minInterval, meta.min_interval, `status.minInterval must equal heartbeat.min_interval\n${context}`);
  assert.equal(status.maxInterval, meta.max_interval, `status.maxInterval must equal heartbeat.max_interval\n${context}`);
  assert.equal(meta.battery_exempt, expect.batteryExempt, `heartbeat.battery_exempt\n${context}`);
  assert.equal(status.isIgnoringBatteryOptimizations, meta.battery_exempt, `status.isIgnoringBatteryOptimizations\n${context}`);
  assert.equal(meta.device_idle, expect.deviceIdle, `heartbeat.device_idle\n${context}`);
  assert.equal(status.isDeviceIdleMode, meta.device_idle, `status.isDeviceIdleMode\n${context}`);
  assert.equal(status.pendingHeartbeats, 0, `the heartbeat was uploaded (settledStatus), so none is pending\n${context}`);
  const nextAt = epoch(meta.next_at, 'heartbeat.next_at');
  const expectedNext = epoch(record.recorded_at, 'recorded_at') + meta.min_interval * 1000;
  assert.ok(
    Math.abs(nextAt - expectedNext) <= 1_000,
    `heartbeat.next_at must be recorded_at + min_interval (${new Date(expectedNext).toISOString()}) ±1 s\n${context}`,
  );
  const statusNext = epoch(status.nextHeartbeatAt, 'status.nextHeartbeatAt');
  assert.ok(
    Math.abs(statusNext - nextAt) <= 1_000,
    `status.nextHeartbeatAt must equal heartbeat.next_at ±1 s\n${context}`,
  );
}

// ---------------------------------------------------------------------------------------------------------------
// `dumpsys location` parsing (P-H01, P-H02)
//
// Lines parsed, by format:
//
// API 29 and 30 (LocationManagerService): every `UpdateRecord[...]`, in "Location Listeners:" and in
// "Active Records by Provider:", e.g.
//     UpdateRecord[gps com.example(10150 foreground) Request[ACCURACY_FINE gps requested=+1s0ms fastest=+1s0ms] WorkSource{...}]
//     UpdateRecord[passive com.example(10150 foreground) Request[POWER_NONE passive fastest=+0ms] null]
//   (API 30 prints the caller as `com.example/10150`). Registrant = package and uid; provider = first token;
//   quality POWER_NONE or provider `passive` = passive. Every listed record is active.
//   Last known fixes: "Last Known Locations:" lines `gps: Location[gps 24.713600,46.675300 hAcc=5 et=+1h2m3s456ms ...]`.
//
// API 31 to 35 (LocationProviderManager per provider, ListenerMultiplexer.dump):
//     gps provider:
//       service: ProviderRequest[@+1s0ms, HIGH_ACCURACY, WorkSource{10123 com.google.android.gms, 10150 com.example}]
//       listeners:
//         10150/com.example Request[@+1s0ms HIGH_ACCURACY]
//         10150/com.example[attributionTag] [bg] Request[@+1m0s0ms LOW_POWER] (inactive)
//       last location=Location[gps 24.713600,46.675300 hAcc=5.0 et=+1h2m3s456ms alt=... ]
//   The section of a provider starts at `<name> provider:` (`[mock]` for test providers) and ends at the next line
//   indented at or left of that header. Registrant = `<uid>/<package>`; passive = provider `passive` or
//   `Request[PASSIVE...]`; inactive = ` (inactive)` suffix; the `service:` line is the merged request sent to the
//   provider (`ProviderRequest[OFF]` = nothing requested). Lines outside provider sections (event log, historical
//   aggregates) are ignored.
//
// Attribution: a request is attributed to the app when the app registered it (registrant = the app), or when another
// registrant (Google Play services, the system's geofence manager) names the app's package or uid in the request's
// WorkSource.

interface LocationRegistration {
  kind: 'record' | 'listener' | 'service';
  /** provider name, e.g. gps, network, fused, passive */
  provider: string;
  /** registrant package; null for `service:` lines */
  packageName: string | null;
  uid: number | null;
  /** the `Request[...]` / `ProviderRequest[...]` text */
  request: string;
  inactive: boolean;
  line: string;
}

const PROVIDER_HEADER = /^([A-Za-z0-9_.-]+) provider(?: \[mock\])?:$/;
const LISTENER_LINE = /^(\d+)\/([A-Za-z0-9_.]+)(?:\[[^\]]*\])?.*?\s(Request\[.*\])(\s+\(inactive\))?$/;
const UPDATE_RECORD = /UpdateRecord\[(\S+) ([A-Za-z0-9_.]+)[(/](\d+)[^\]]*?(Request\[[^\]]*\])[^\]]*\]/g;

function parseLocationRegistrations(dump: string): LocationRegistration[] {
  const out: LocationRegistration[] = [];
  let section: { provider: string; indent: number } | null = null;
  for (const raw of dump.split(/\r?\n/)) {
    const text = raw.trim();
    if (text === '') continue;
    const indent = raw.length - raw.trimStart().length;
    for (const m of text.matchAll(UPDATE_RECORD)) {
      out.push({
        kind: 'record',
        provider: m[1]!,
        packageName: m[2]!,
        uid: Number(m[3]),
        request: m[4]!,
        inactive: false,
        line: m[0],
      });
    }
    const header = PROVIDER_HEADER.exec(text);
    if (header) {
      section = { provider: header[1]!, indent };
      continue;
    }
    if (section !== null && indent <= section.indent) section = null;
    if (section === null) continue;
    const service = /^service:\s*(.*)$/.exec(text);
    if (service) {
      if (service[1]!.includes('Request[')) {
        out.push({
          kind: 'service',
          provider: section.provider,
          packageName: null,
          uid: null,
          request: service[1]!,
          inactive: false,
          line: text,
        });
      }
      continue;
    }
    const listener = LISTENER_LINE.exec(text);
    if (listener) {
      out.push({
        kind: 'listener',
        provider: section.provider,
        uid: Number(listener[1]),
        packageName: listener[2]!,
        request: listener[3]!,
        inactive: listener[4] !== undefined,
        line: text,
      });
    }
  }
  return out;
}

function isPassiveRequest(r: LocationRegistration): boolean {
  return r.provider === 'passive' || /Request\[PASSIVE/.test(r.request) || /\bPOWER_NONE\b/.test(r.request);
}

function isActiveRequest(r: LocationRegistration): boolean {
  return !r.inactive && !/ProviderRequest\[OFF\]/.test(r.request);
}

/** GPS: the gps provider, or a high-accuracy request to any provider. */
function usesGps(r: LocationRegistration): boolean {
  return r.provider === 'gps' || /HIGH_ACCURACY|ACCURACY_FINE|POWER_HIGH/.test(r.request);
}

/**
 * Requested interval, ms: API 31+ `Request[@+1s0ms ...]` / `ProviderRequest[@+1s0ms, ...]`, API 29
 * `requested=+1s0ms`, API 30 `interval=+1s0ms`. null when the request text shows none.
 */
function requestIntervalMs(r: LocationRegistration): number | null {
  const m = /Request\[@([+-]?[0-9dhms]+)/.exec(r.request) ?? /\b(?:requested|interval)=([+-]?[0-9dhms]+)/.exec(r.request);
  return m ? parseDurationMs(m[1]!) : null;
}

/**
 * GPS requested on the app's behalf counts as "GPS on" only when it asks for fixes more often than this. The OS
 * geofence of the stationary region is evaluated with a long-interval request that names the app in its WorkSource
 * (the framework geofence manager asks the fused provider every 30 minutes; the AOSP fused provider turns a BALANCED
 * request into a GPS request); that duty-cycled request is part of the design (contract §3), not continuous GPS.
 */
const CONTINUOUS_GPS_INTERVAL_MS = 5 * 60_000;

/** uids listed in the WorkSource part of [line] (`WorkSource{10150 com.example, 10123}` and work chains). */
function workSourceUids(line: string): number[] {
  const at = line.indexOf('WorkSource{');
  if (at < 0) return [];
  return [...line.slice(at).matchAll(/\b(\d{4,})\b/g)].map((m) => Number(m[1]));
}

interface LocationSnapshot {
  /** the app's own registrations (any state) */
  own: LocationRegistration[];
  /** the app's own active non-passive registrations: GPS or network requested by the app itself */
  ownActive: LocationRegistration[];
  /**
   * active non-passive GPS registrations of another registrant (Google Play services, the system) that name the app in
   * their WorkSource and ask for fixes more often than every 5 minutes. A provider's merged `service:` request is not
   * used: its WorkSource lists every contributor, whatever interval each of them asked for.
   */
  attributedGps: LocationRegistration[];
  /** raw lines that mention the app's package or uid (diagnostics) */
  mentioning: string[];
}

async function locationSnapshot(ctx: ScenarioContext, uid: number | null): Promise<LocationSnapshot> {
  const dump = await ctx.adb.dumpsys('location');
  const registrations = parseLocationRegistrations(dump);
  const isOwn = (r: LocationRegistration) =>
    r.kind !== 'service' && (r.packageName === ctx.appId || (uid !== null && r.uid === uid));
  const own = registrations.filter(isOwn);
  const ownActive = own.filter((r) => isActiveRequest(r) && !isPassiveRequest(r));
  const attributedGps = registrations.filter((r) => {
    if (r.kind === 'service' || isOwn(r) || !isActiveRequest(r) || isPassiveRequest(r) || !usesGps(r)) return false;
    if (!r.line.includes(ctx.appId) && !(uid !== null && workSourceUids(r.line).includes(uid))) return false;
    const interval = requestIntervalMs(r);
    // An interval the parser cannot read counts as continuous, so the failure message shows the line.
    return interval === null || interval < CONTINUOUS_GPS_INTERVAL_MS;
  });
  const uidPattern = uid === null ? null : new RegExp(`\\b${uid}\\b`);
  const mentioning = dump
    .split(/\r?\n/)
    .filter((l) => l.includes(ctx.appId) || (uidPattern !== null && uidPattern.test(l)))
    .map((l) => l.trim())
    .slice(0, 40);
  return { own, ownActive, attributedGps, mentioning };
}

function registrationLines(list: readonly LocationRegistration[]): string {
  return list.length === 0 ? '(none)' : list.map((r) => `[${r.provider}/${r.kind}] ${r.line}`).join('\n  ');
}

/** `+1h2m3s456ms` (android.util.TimeUtils.formatDuration) in ms; null when not a duration. */
function parseDurationMs(text: string): number | null {
  if (text === '0') return 0;
  const m = /^([+-])?(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m(?!s))?(?:(\d+)s)?(?:(\d+)ms)?$/.exec(text);
  if (!m || text.replace(/^[+-]/, '') === '') return null;
  const [, sign, d, h, min, s, ms] = m;
  const total =
    Number(d ?? 0) * 86_400_000 + Number(h ?? 0) * 3_600_000 + Number(min ?? 0) * 60_000 + Number(s ?? 0) * 1_000 + Number(ms ?? 0);
  return sign === '-' ? -total : total;
}

/**
 * Elapsed time (ms since boot, `et=` of `Location[...]`) of the newest fix the framework knows of in any provider's
 * last-location lines (API 31+ `last location=`, API 29/30 "Last Known Locations:"). null if none is printed.
 */
function newestFixElapsedMs(dump: string): number | null {
  let newest: number | null = null;
  for (const m of dump.matchAll(/Location\[[^\]\n]*?\bet=([+-]?[0-9dhms]+)/g)) {
    const value = parseDurationMs(m[1]!);
    if (value !== null && (newest === null || value > newest)) newest = value;
  }
  return newest;
}

/** The app's uid (`pm list packages -U`, else `dumpsys package`); null if neither prints it. */
async function appUid(ctx: ScenarioContext): Promise<number | null> {
  try {
    const out = await ctx.adb.shell(`pm list packages -U ${ctx.appId}`);
    for (const line of out.split(/\r?\n/)) {
      const m = /^package:(\S+)\s+uid:(\d+)/.exec(line.trim());
      if (m && m[1] === ctx.appId) return Number(m[2]);
    }
  } catch (error) {
    ctx.log(`pm list packages -U failed: ${errorText(error)}`);
  }
  try {
    const out = await ctx.adb.dumpsys('package', [ctx.appId]);
    const m = /\b(?:userId|appId)=(\d+)/.exec(out);
    if (m) return Number(m[1]);
  } catch (error) {
    ctx.log(`dumpsys package failed: ${errorText(error)}`);
  }
  return null;
}

// ---------------------------------------------------------------------------------------------------------------
// HTTP request log helpers (P-H08, P-H09)

/** Records inside a request body: single, batch, custom rootProperty and bare bodies. */
function bodyRecords(body: string): WireRecord[] {
  let json: unknown;
  try {
    json = JSON.parse(body);
  } catch {
    return [];
  }
  const isRecord = (value: unknown): value is WireRecord =>
    value !== null &&
    typeof value === 'object' &&
    typeof (value as Record<string, unknown>)['uuid'] === 'string' &&
    typeof (value as Record<string, unknown>)['event'] === 'string';
  const fromValue = (value: unknown): WireRecord[] =>
    isRecord(value) ? [value] : Array.isArray(value) ? value.filter(isRecord) : [];
  if (Array.isArray(json) || isRecord(json)) return fromValue(json);
  if (json === null || typeof json !== 'object') return [];
  return Object.values(json as Record<string, unknown>).flatMap(fromValue);
}

function headerValue(entry: RequestLogEntry, name: string): string | undefined {
  const wanted = name.toLowerCase();
  for (const [key, value] of Object.entries(entry.headers)) if (key.toLowerCase() === wanted) return value;
  return undefined;
}

function describeRequest(entry: RequestLogEntry): string {
  const records = bodyRecords(entry.body);
  return (
    `#${entry.id} ${entry.method} ${entry.path} -> ${entry.status}${entry.fault ? ` (fault ${entry.fault})` : ''} ` +
    `auth=${headerValue(entry, 'authorization') ?? '-'} records=[${records.map((r) => `${r.event}:${r.uuid.slice(0, 8)}`).join(', ')}]`
  );
}

function requestLog(entries: readonly RequestLogEntry[]): string {
  return entries.length === 0 ? '  (no requests)' : entries.map((e) => `  ${describeRequest(e)}`).join('\n');
}

function sortedRequests(office: MockBackOffice, path: string, sinceHost = 0): RequestLogEntry[] {
  return office.requests({ path, since: sinceHost }).sort((a, b) => a.id - b.id);
}

/**
 * Successful uploads grouped into drains (one upload pass): consecutive requests whose arrivals are at most [gapMs]
 * apart. The 10 s default is far below the syncInterval spacing (120 s) of the timer-driven passes and far above the
 * spacing of the batches of one pass on the emulator's host network.
 */
function uploadDrains(requests: readonly RequestLogEntry[], gapMs = 10_000): { requests: RequestLogEntry[]; records: WireRecord[] }[] {
  const ok = requests.filter((r) => r.status >= 200 && r.status < 300).sort((a, b) => a.receivedAt - b.receivedAt);
  const drains: { requests: RequestLogEntry[]; records: WireRecord[] }[] = [];
  for (const request of ok) {
    const last = drains[drains.length - 1];
    const previous = last?.requests[last.requests.length - 1];
    if (last !== undefined && previous !== undefined && request.receivedAt - previous.receivedAt <= gapMs) {
      last.requests.push(request);
      last.records.push(...bodyRecords(request.body));
    } else {
      drains.push({ requests: [request], records: bodyRecords(request.body) });
    }
  }
  return drains;
}

/** Drains that hold only normal records, at least one of them a `location`. */
function normalOnlyLocationDrains(requests: readonly RequestLogEntry[]): { requests: RequestLogEntry[]; records: WireRecord[] }[] {
  return uploadDrains(requests).filter(
    (d) => d.records.length > 0 && d.records.every((r) => NORMAL_EVENTS.has(r.event)) && d.records.some((r) => r.event === 'location'),
  );
}

// ---------------------------------------------------------------------------------------------------------------
// Connectivity (P-H07)

/** `dumpsys connectivity` "Active default network:" line: 'none', 'up', or 'unknown' when the line is missing. */
async function defaultNetworkState(ctx: ScenarioContext): Promise<'none' | 'up' | 'unknown'> {
  const out = await ctx.adb.dumpsys('connectivity');
  const m = /Active default network:\s*(\S+)/.exec(out);
  if (!m) return 'unknown';
  return m[1] === 'none' ? 'none' : 'up';
}

// ===============================================================================================================
// Scenarios

scenario(
  'P-H01',
  'stationary heartbeat cadence (60/120 s); recorded_at newer than location.timestamp',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true });
    const config = await ctx.testConfig();
    const started = await startTracking(ctx, office, config, PLACES.hq);
    const anchor = await waitForAnchor(ctx, office, started);
    const anchorCoords = anchor.coords!;
    const anchorFixAt = epoch(anchor.timestamp, 'anchor timestamp');

    // Several heartbeat windows while stationary (GPS off, no fix injected after the anchor).
    const WINDOWS = 3;
    await waitForRecords(ctx, office, (r) => r.event === 'heartbeat' && createdAfter(r, anchor), WINDOWS, {
      sinceHost: started.sinceHost,
      timeoutMs: WINDOWS * (MAX_S + TOLERANCE_S) * 1000 + 30_000,
      what: `${WINDOWS} heartbeats after the stationary motionchange`,
    });
    const records = received(office, started.sinceHost);
    const gaps = assertions.heartbeatCadence(records, {
      minIntervalS: MIN_S,
      maxIntervalS: MAX_S,
      toleranceS: TOLERANCE_S,
      since: started.trackingStart.recorded_at,
    });
    ctx.log(`heartbeat gaps (s): ${gaps.map((g) => g.toFixed(1)).join(', ')}`);

    // While stationary nothing is recorded except heartbeats (a providerchange would be an explained audit record).
    const afterAnchor = records.filter((r) => createdAfter(r, anchor));
    const unexpected = afterAnchor.filter((r) => r.event !== 'heartbeat' && r.event !== 'providerchange');
    assert.equal(
      unexpected.length,
      0,
      `while stationary only heartbeats are expected after the anchor motionchange; timeline:\n${timeline(records)}`,
    );
    const heartbeats = afterAnchor.filter((r) => r.event === 'heartbeat');

    // Every heartbeat carries the last known fix: its own recorded_at is newer than the fix's acquisition time,
    // the fix is never older than the anchor, never goes backwards, and lies at the parked position.
    const radius = Number(valueAt(config, ['geolocation', 'stationaryRadius']) ?? DEFAULT_STATIONARY_RADIUS_M);
    let previousFixAt = anchorFixAt;
    for (const hb of heartbeats) {
      assert.ok(hb.timestamp !== null && hb.coords !== null, `heartbeat without a location although the anchor fix exists:\n  ${describe(hb)}`);
      const fixAt = epoch(hb.timestamp, 'heartbeat timestamp');
      const createdAt = epoch(hb.recorded_at, 'heartbeat recorded_at');
      assert.ok(
        createdAt > fixAt,
        `heartbeat recorded_at (${hb.recorded_at}) must be newer than its location timestamp (${hb.timestamp}), ` +
          `the fix's acquisition time:\n  ${describe(hb)}`,
      );
      assert.ok(fixAt >= anchorFixAt, `heartbeat fix (${hb.timestamp}) is older than the anchor fix (${anchor.timestamp})`);
      assert.ok(fixAt >= previousFixAt, `heartbeat fix times must not go backwards; timeline:\n${timeline(heartbeats)}`);
      const distance = assertions.haversineMeters(toLatLon(anchorCoords), toLatLon(hb.coords));
      assert.ok(
        distance <= radius + hb.coords.accuracy,
        `heartbeat position is ${distance.toFixed(1)} m from the anchor; expected within stationaryRadius ${radius} m + ` +
          `accuracy ${hb.coords.accuracy} m:\n  ${describe(hb)}`,
      );
      previousFixAt = fixAt;
    }

    // `timestamp` stays the anchor fix time while no new fix is accepted. The plugin only listens passively while
    // stationary, so a newer fix can only come from another app turning on a provider (for example Google Play
    // services evaluating a geofence). The framework's last-location lines tell whether any provider produced a fix
    // after the anchor record; if none did, every heartbeat must carry exactly the anchor fix.
    const dump = await ctx.adb.dumpsys('location');
    const newestFix = newestFixElapsedMs(dump);
    const noNewerFix = newestFix === null || newestFix <= anchor.elapsed_realtime_ms + FIX_SETTLE_MS;
    if (noNewerFix) {
      if (newestFix === null) ctx.log('dumpsys location printed no last location; the strict anchor check applies');
      for (const hb of heartbeats) {
        assert.equal(
          epoch(hb.timestamp, 'heartbeat timestamp'),
          anchorFixAt,
          `no provider produced a fix after the anchor (newest fix at elapsed ${newestFix ?? 'unknown'} ms, anchor record at ` +
            `${anchor.elapsed_realtime_ms} ms), so the heartbeat must carry the anchor fix time ${anchor.timestamp}:\n  ${describe(hb)}`,
        );
        assert.equal(hb.coords!.latitude, anchorCoords.latitude, `heartbeat latitude must be the anchor's:\n  ${describe(hb)}`);
        assert.equal(hb.coords!.longitude, anchorCoords.longitude, `heartbeat longitude must be the anchor's:\n  ${describe(hb)}`);
      }
      ctx.log(`all ${heartbeats.length} heartbeats carry the anchor fix time ${anchor.timestamp}`);
    } else {
      const same = heartbeats.filter((hb) => epoch(hb.timestamp, 'timestamp') === anchorFixAt).length;
      ctx.log(
        `a provider produced a fix after the anchor (newest fix at elapsed ${newestFix} ms, anchor record at ` +
          `${anchor.elapsed_realtime_ms} ms): ${same} of ${heartbeats.length} heartbeats carry the anchor fix, the others ` +
          'a later passive fix at the same place (strict equality not asserted)',
      );
    }
  },
  { timeoutMs: 10 * 60_000 },
);

/**
 * P-H02 phase: MOVING (positive control: the parser sees the app's GPS request), then STATIONARY: the request must
 * disappear and stay away, also across a heartbeat.
 */
async function verifyGpsOffWhileStationary(
  ctx: ScenarioContext,
  office: MockBackOffice,
  uid: number | null,
  started: Started,
  backend: 'android' | 'gms',
): Promise<void> {
  const label = `${backend} backend`;
  // Positive control: while MOVING the app requests high accuracy. With the android backend the app registers with the
  // framework itself; with the gms backend Google Play services registers with the framework and names the app in the
  // WorkSource.
  const movingSince = Date.now();
  await ctx.commands.changePace(true);
  await waitForRecord(ctx, office, (r) => r.event === 'motionchange' && r.is_moving && createdAfter(r, started.trackingStart), {
    sinceHost: movingSince,
    timeoutMs: 30_000,
    what: `motionchange (is_moving: true) after changePace(true) (${label})`,
  });
  const detect = (s: LocationSnapshot) => (backend === 'android' ? s.ownActive : s.attributedGps);
  let controlSeen = false;
  let lastSnapshot: LocationSnapshot | undefined;
  try {
    await waitUntil(
      async () => {
        lastSnapshot = await locationSnapshot(ctx, uid);
        return detect(lastSnapshot).length > 0;
      },
      { timeoutMs: 45_000, intervalMs: 3_000, message: `a high-accuracy request of the app while MOVING (${label})`, signal: ctx.signal },
    );
    controlSeen = true;
    ctx.log(`${label}: MOVING request seen: ${registrationLines(detect(lastSnapshot!))}`);
  } catch (error) {
    const lines = lastSnapshot?.mentioning.join('\n  ') ?? '(no snapshot)';
    if (backend === 'android') {
      throw new Error(
        `${label}: while MOVING the parser found no active non-passive location request registered by ${ctx.appId}, ` +
          `so the stationary check below would prove nothing. dumpsys location lines naming the app:\n  ${lines}\n(${errorText(error)})`,
      );
    }
    ctx.log(
      `${label}: Google Play services does not name the app in its framework requests on this image; only the app's own ` +
        `registrations are checked in this phase. Lines naming the app:\n  ${lines}`,
    );
  }

  // STATIONARY: the app's own requests become passive (or go away) and nothing requests GPS on its behalf.
  const stillSince = Date.now();
  await ctx.commands.changePace(false);
  const stationary = await waitForRecord(
    ctx,
    office,
    (r) => r.event === 'motionchange' && !r.is_moving && createdAfter(r, started.trackingStart),
    { sinceHost: stillSince, timeoutMs: 30_000, what: `motionchange (is_moving: false) after changePace(false) (${label})` },
  );
  const settled = (s: LocationSnapshot) => s.ownActive.length === 0 && (!controlSeen || s.attributedGps.length === 0);
  try {
    await waitUntil(
      async () => {
        lastSnapshot = await locationSnapshot(ctx, uid);
        return settled(lastSnapshot);
      },
      { timeoutMs: 45_000, intervalMs: 3_000, message: `GPS request removed after the stationary motionchange (${label})`, signal: ctx.signal },
    );
  } catch (error) {
    throw new Error(
      `${label}: 45 s after entering STATIONARY a non-passive location request is still attributed to ${ctx.appId}:\n` +
        `  own active: ${registrationLines(lastSnapshot?.ownActive ?? [])}\n` +
        `  GPS on the app's behalf: ${registrationLines(lastSnapshot?.attributedGps ?? [])}\n(${errorText(error)})`,
    );
  }

  // Hold: sample every 15 s until a heartbeat was created while stationary and at least 4 samples were taken. The app's
  // own active non-passive requests must be absent in every sample. A GPS request on the app's behalf by another
  // registrant (interval under 5 minutes, see CONTINUOUS_GPS_INTERVAL_MS) fails only when it is seen in two
  // consecutive samples (15 s or longer): Google Play services may turn GPS on briefly to evaluate the stationary
  // geofence, which is the OS geofence the design relies on.
  let samples = 0;
  let consecutiveGps = 0;
  const holdDeadline = Date.now() + (MAX_S + TOLERANCE_S + 60) * 1000;
  for (;;) {
    await sleep(15_000, ctx.signal);
    const snapshot = await locationSnapshot(ctx, uid);
    samples += 1;
    assert.equal(
      snapshot.ownActive.length,
      0,
      `${label}: STATIONARY sample ${samples}: ${ctx.appId} has an active non-passive location request of its own ` +
        `(GPS/network must be off while stationary):\n  ${registrationLines(snapshot.ownActive)}`,
    );
    if (controlSeen && snapshot.attributedGps.length > 0) {
      consecutiveGps += 1;
      ctx.log(`${label}: sample ${samples}: GPS request naming the app: ${registrationLines(snapshot.attributedGps)}`);
    } else {
      consecutiveGps = 0;
    }
    assert.ok(
      consecutiveGps < 2,
      `${label}: GPS stays requested on behalf of ${ctx.appId} for two consecutive samples while STATIONARY:\n  ` +
        registrationLines(snapshot.attributedGps),
    );
    ctx.log(`${label}: STATIONARY sample ${samples}: own registrations ${registrationLines(snapshot.own)}`);
    const heartbeatSeen = received(office, stillSince).some((r) => r.event === 'heartbeat' && createdAfter(r, stationary));
    if (samples >= 4 && heartbeatSeen) break;
    if (Date.now() > holdDeadline) {
      throw new Error(
        `${label}: no heartbeat was received within ${MAX_S + TOLERANCE_S} s of the stationary motionchange; received:\n` +
          timeline(received(office, stillSince)),
      );
    }
  }
}

scenario(
  'P-H02',
  'stationary: no active non-passive location request from the app (dumpsys location)',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true });
    const uid = await appUid(ctx);
    ctx.log(`app uid: ${uid ?? 'unknown (only the package name is matched)'}`);

    // Phase 1: android backend. The app registers with the framework itself, so its own registrations are exact.
    const configAndroid = await ctx.testConfig({ locationProvider: 'android' });
    const android = await startTracking(ctx, office, configAndroid, PLACES.hq);
    assert.equal(android.trackingStart.backend, 'android', `locationProvider 'android': tracking_start backend\n  ${describe(android.trackingStart)}`);
    await waitForAnchor(ctx, office, android);
    await verifyGpsOffWhileStationary(ctx, office, uid, android, 'android');
    await ctx.commands.stop();

    // Phase 2: gms backend (the production backend), only with Google Play services.
    if (!ctx.device.gms) {
      ctx.log('no Google Play services on this image: the gms phase is skipped');
      return;
    }
    const configGms = await ctx.testConfig();
    const gms = await startTracking(ctx, office, configGms, PLACES.hq);
    assert.equal(gms.trackingStart.backend, 'gms', `locationProvider 'auto' with Google Play services: tracking_start backend\n  ${describe(gms.trackingStart)}`);
    await waitForAnchor(ctx, office, gms);
    await verifyGpsOffWhileStationary(ctx, office, uid, gms, 'gms');
  },
  { timeoutMs: 15 * 60_000 },
);

scenario(
  'P-H03',
  'movement (geo fix route) turns GPS back on: motionchange isMoving true',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true });
    const config = await ctx.testConfig();
    const started = await startTracking(ctx, office, config, PLACES.hq);
    const anchor = await waitForAnchor(ctx, office, started);
    const state = await ctx.commands.state();
    assert.equal(state.isMoving, false, `after the initial motionchange the plugin must be STATIONARY: ${JSON.stringify(state)}`);

    // Drive away from HQ (1.5 km north, then to the city loop 1.8 km further) and keep circling the 3 km loop, so the
    // device stays far outside the stationary region until the plugin notices. With GPS off, the plugin notices through
    // the OS geofence EXIT of its stationary region or a passive fix of another app (Google Play services evaluates
    // geofences every few minutes; its documentation allows up to 6 minutes for a stationary device).
    const lead: LatLon[] = [PLACES.hq, PLACES.hqNorth1500m, ROUTES.cityLoop3km[0]];
    const loop: readonly LatLon[] = ROUTES.cityLoop3km;
    const path: LatLon[] = [...lead, ...loop];
    const hostBefore = Date.now();
    const routeStartDevice = await ctx.adb.deviceTime();
    // Device clock minus host clock (± half the adb round trip): used only to judge fix freshness below.
    const deviceMinusHostMs = routeStartDevice - (hostBefore + Date.now()) / 2;
    const route = startRouteLoop(ctx, lead, loop, DRIVE_SPEED_MPS);
    let moving: WireRecord;
    try {
      moving = await waitForRecord(ctx, office, (r) => r.event === 'motionchange' && r.is_moving && createdAfter(r, anchor), {
        sinceHost: started.sinceHost,
        timeoutMs: 10 * 60_000,
        what:
          'motionchange (is_moving: true) after the device left the stationary region (GPS-off exit detection: the OS ' +
          'geofence EXIT of the stationary region or a passive fix of another app)',
      });
      await waitForRecords(ctx, office, (r) => r.event === 'location' && createdAfter(r, moving), 5, {
        sinceHost: started.sinceHost,
        timeoutMs: 90_000,
        what: '5 location records after motionchange (is_moving: true) (GPS on again while driving)',
      });
    } finally {
      await route.stop();
      if (route.failure !== undefined) ctx.log(`route replay failed: ${errorText(route.failure)}`);
    }
    assert.equal(route.failure, undefined, `the emulator route replay failed: ${errorText(route.failure)}`);

    const records = received(office, started.sinceHost);
    assertions.inOrder(records, [
      { event: 'tracking_start', reason: 'start' },
      { event: 'motionchange', is_moving: false },
      { event: 'motionchange', is_moving: true },
      'location',
    ]);
    const detectedAt = epoch(moving.recorded_at, 'motionchange recorded_at');
    assert.ok(
      detectedAt > routeStartDevice,
      `motionchange (is_moving: true) must be recorded after the route started (${new Date(routeStartDevice).toISOString()}):\n  ${describe(moving)}`,
    );
    ctx.log(`movement detected ${seconds(detectedAt - routeStartDevice)} after the route started`);

    // GPS was off while stationary: no location record between the anchor and the moving motionchange.
    const stationaryLocations = records.filter((r) => r.event === 'location' && createdAfter(r, anchor) && byCreation(r, moving) < 0);
    assert.equal(stationaryLocations.length, 0, `location records while STATIONARY (GPS must be off):\n${timeline(stationaryLocations)}`);

    // GPS is on again: fresh fixes along the replayed route, outside the stationary radius, moving forward.
    const radius = Number(valueAt(config, ['geolocation', 'stationaryRadius']) ?? DEFAULT_STATIONARY_RADIUS_M);
    const locations = records.filter((r) => r.event === 'location' && createdAfter(r, moving));
    for (const location of locations) {
      assert.ok(location.coords !== null && location.timestamp !== null, `location record without a fix:\n  ${describe(location)}`);
      assert.equal(location.is_moving, true, `location records after the motionchange are MOVING:\n  ${describe(location)}`);
      // Fresh fix: recorded_at - timestamp in [-2, 10] s. The emulator's GNSS fix time follows either the device clock
      // or the host clock, so the age is also accepted after removing the measured device-host clock offset.
      const age = epoch(location.recorded_at, 'recorded_at') - epoch(location.timestamp, 'timestamp');
      const fresh = (value: number) => value >= -2_000 && value <= IMMEDIATE_S * 1000;
      assert.ok(
        fresh(age) || fresh(age - deviceMinusHostMs),
        `a location record must carry a fresh fix (recorded_at - timestamp in [-2, ${IMMEDIATE_S}] s, with or without the ` +
          `device-host clock offset ${seconds(deviceMinusHostMs)}), got ${seconds(age)}:\n  ${describe(location)}`,
      );
      const offPath = distanceToPathMeters(toLatLon(location.coords), path);
      assert.ok(offPath <= 60, `location ${offPath.toFixed(0)} m away from the replayed route:\n  ${describe(location)}`);
      const fromAnchor = assertions.haversineMeters(toLatLon(anchor.coords!), toLatLon(location.coords));
      assert.ok(fromAnchor > radius, `location only ${fromAnchor.toFixed(0)} m from the anchor (stationaryRadius ${radius} m):\n  ${describe(location)}`);
    }
    const first = locations[0]!;
    const last = locations[locations.length - 1]!;
    const travelled = assertions.haversineMeters(toLatLon(first.coords!), toLatLon(last.coords!));
    assert.ok(travelled >= 30, `the location records must advance along the route; first and last are ${travelled.toFixed(0)} m apart`);
  },
  { timeoutMs: 16 * 60_000, requires: { gms: true } },
);

/** Pairs of consecutive idle heartbeats (no other record between them) whose first heartbeat armed a paced backup. */
function idlePacedPairs(records: readonly WireRecord[]): { prev: WireRecord; cur: WireRecord; paced: boolean }[] {
  const idle = records.filter((r) => r.event === 'heartbeat' && r.heartbeat?.device_idle === true);
  const pairs: { prev: WireRecord; cur: WireRecord; paced: boolean }[] = [];
  for (let i = 1; i < idle.length; i++) {
    const prev = idle[i - 1]!;
    const cur = idle[i]!;
    const nextAt = prev.heartbeat?.next_at ? Date.parse(prev.heartbeat.next_at) : Number.NaN;
    const paced = Number.isFinite(nextAt) && nextAt - Date.parse(prev.recorded_at) >= (IDLE_SPACING_S - TOLERANCE_S) * 1000;
    pairs.push({ prev, cur, paced });
  }
  return pairs;
}

scenario(
  'P-H04',
  'deep Doze, not battery-exempt: strategy idle_paced, heartbeats >= 9 min apart',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true, batteryExempt: false });
    const config = await ctx.testConfig();
    const started = await startTracking(ctx, office, config, PLACES.hq);
    await waitForAnchor(ctx, office, started);

    try {
      await enterDeepIdle(ctx);
      const status = await idleStatus(ctx, 'idle_paced');
      const context = JSON.stringify(status);
      assert.equal(status.isIgnoringBatteryOptimizations, false, `the app is not exempt: ${context}`);
      assert.equal(status.canScheduleExactAlarms, false, `a non-exempt app cannot schedule exact alarms on Android 12+: ${context}`);

      // Two paced gaps: the first backup alarm in deep Doze may fire at the plain due time (no backup fired yet in this
      // boot); from then on every backup alarm is at least 9 minutes after the previous one.
      const deadlineMs = 45 * 60_000;
      try {
        await office.waitFor(
          (o) => {
            const pairs = idlePacedPairs(received(o, started.sinceHost)).filter((p) => p.paced);
            return pairs.length >= 2 ? pairs : undefined;
          },
          { timeoutMs: deadlineMs, intervalMs: 5_000, message: 'two idle-paced heartbeat gaps', signal: ctx.signal },
        );
      } catch (error) {
        let statusText = '(unavailable)';
        try {
          statusText = JSON.stringify(await ctx.commands.heartbeatStatus());
        } catch (statusError) {
          statusText = `(status failed: ${errorText(statusError)})`;
        }
        throw new Error(
          `expected two idle-paced heartbeat gaps within ${seconds(deadlineMs)} of deep Doze; heartbeat status ${statusText}; ` +
            `received:\n${timeline(received(office, started.sinceHost))}\n(${errorText(error)})`,
        );
      }
      ctx.log(`deep Doze state at the end of the observation: ${await ctx.adb.deviceIdle.state()}`);
    } finally {
      await leaveDeepIdle(ctx);
    }

    const records = received(office, started.sinceHost);
    const idle = records.filter((r) => r.event === 'heartbeat' && r.heartbeat?.device_idle === true);
    for (const hb of idle) {
      const meta = heartbeatMeta(hb);
      assert.equal(meta.strategy, 'idle_paced', `heartbeat in deep Doze without the exemption:\n  ${describe(hb)}`);
      assert.equal(meta.battery_exempt, false, `heartbeat.battery_exempt:\n  ${describe(hb)}`);
    }
    for (const { prev, cur, paced } of idlePacedPairs(records)) {
      const between = records.filter((r) => createdAfter(r, prev) && byCreation(r, cur) < 0);
      assert.equal(between.length, 0, `no record is expected between two heartbeats in deep Doze:\n${timeline(between)}`);
      const gapMs = cur.elapsed_realtime_ms - prev.elapsed_realtime_ms;
      const announcedMs = epoch(prev.heartbeat!.next_at, 'heartbeat.next_at') - epoch(prev.recorded_at, 'recorded_at');
      // Never earlier than the previous heartbeat announced (the backup alarm is never delivered early).
      assert.ok(
        gapMs >= announcedMs - TOLERANCE_S * 1000,
        `heartbeat ${seconds(gapMs)} after the previous one, earlier than its next_at (${seconds(announcedMs)} after it):\n` +
          `  ${describe(prev)}\n  ${describe(cur)}`,
      );
      if (paced) {
        assert.ok(
          announcedMs <= (IDLE_SPACING_S + 5) * 1000,
          `idle-paced next_at must be the previous backup fire + 9 min, got ${seconds(announcedMs)} after recorded_at:\n  ${describe(prev)}`,
        );
        assert.ok(
          gapMs >= (IDLE_SPACING_S - TOLERANCE_S) * 1000,
          `idle-paced heartbeats must be >= 9 min apart (tolerance ${TOLERANCE_S} s), got ${seconds(gapMs)}:\n  ${describe(prev)}\n  ${describe(cur)}`,
        );
      }
      // Not later than the inexact backup alarm may be delivered (armed when the previous heartbeat was recorded).
      const latestMs = announcedMs * (1 + INEXACT_WINDOW_FACTOR) + 60_000;
      assert.ok(
        gapMs <= latestMs,
        `heartbeat ${seconds(gapMs)} after the previous one: later than the inexact alarm window allows (<= ${seconds(latestMs)}):\n` +
          `  ${describe(prev)}\n  ${describe(cur)}`,
      );
      ctx.log(`deep Doze gap ${seconds(gapMs)} (next_at announced ${seconds(announcedMs)}, paced ${paced})`);
    }
  },
  { timeoutMs: 60 * 60_000, requires: { long: true, api: 31 } },
);

scenario(
  'P-H05',
  'deep Doze, battery-exempt: strategy exact, cadence about minInterval',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true, batteryExempt: true });
    const config = await ctx.testConfig();
    const started = await startTracking(ctx, office, config, PLACES.hq);
    const anchor = await waitForAnchor(ctx, office, started);

    const IDLE_HEARTBEATS = 3;
    try {
      await enterDeepIdle(ctx);
      const status = await idleStatus(ctx, 'exact');
      const context = JSON.stringify(status);
      assert.equal(status.isIgnoringBatteryOptimizations, true, `the app is on the battery-optimization allowlist: ${context}`);
      assert.equal(status.canScheduleExactAlarms, true, `an exempt app may schedule exact alarms: ${context}`);
      await waitForRecords(ctx, office, (r) => r.event === 'heartbeat' && r.heartbeat?.device_idle === true, IDLE_HEARTBEATS, {
        sinceHost: started.sinceHost,
        timeoutMs: IDLE_HEARTBEATS * (MAX_S + TOLERANCE_S) * 1000 + 60_000,
        what: `${IDLE_HEARTBEATS} heartbeats created in deep Doze`,
      });
    } finally {
      await leaveDeepIdle(ctx);
      await restore(ctx, 'battery-optimization allowlist', () => ctx.adb.deviceIdle.whitelistRemove(ctx.appId));
    }

    const records = received(office, started.sinceHost);
    const gaps = assertions.heartbeatCadence(records, {
      minIntervalS: MIN_S,
      maxIntervalS: MAX_S,
      toleranceS: TOLERANCE_S,
      since: started.trackingStart.recorded_at,
    });
    ctx.log(`heartbeat gaps (s): ${gaps.map((g) => g.toFixed(1)).join(', ')}`);
    const afterAnchor = records.filter((r) => createdAfter(r, anchor));
    let previous = anchor;
    for (const record of afterAnchor) {
      if (record.event === 'heartbeat' && record.heartbeat?.device_idle === true) {
        const meta = heartbeatMeta(record);
        assert.equal(meta.strategy, 'exact', `heartbeat in deep Doze with the exemption:\n  ${describe(record)}`);
        assert.equal(meta.battery_exempt, true, `heartbeat.battery_exempt:\n  ${describe(record)}`);
        const announced = epoch(meta.next_at, 'heartbeat.next_at') - epoch(record.recorded_at, 'recorded_at');
        assert.ok(Math.abs(announced - MIN_S * 1000) <= 1_000, `exact: next_at must be recorded_at + ${MIN_S} s:\n  ${describe(record)}`);
        // About minInterval after the previous record (elapsed clock): the exact allow-while-idle alarm is not paced.
        const gapMs = record.elapsed_realtime_ms - previous.elapsed_realtime_ms;
        assert.ok(
          gapMs >= (MIN_S - 1) * 1000 && gapMs <= (MIN_S + TOLERANCE_S) * 1000,
          `an exempt app's heartbeat in deep Doze must come about minInterval (${MIN_S} s, tolerance +${TOLERANCE_S} s) after ` +
            `the previous record, got ${seconds(gapMs)}:\n  ${describe(previous)}\n  ${describe(record)}`,
        );
      }
      previous = record;
    }
  },
  { timeoutMs: 12 * 60_000 },
);

scenario(
  'P-H06',
  'wall-clock jump and timezone change: cadence unaffected, boot_count/elapsed consistent',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true });
    const config = await ctx.testConfig();
    const started = await startTracking(ctx, office, config, PLACES.hq);
    const anchor = await waitForAnchor(ctx, office, started);

    const deviceOffsetMs = (await ctx.adb.deviceTime()) - Date.now();
    // An unset persist.sys.timezone means GMT to Android; restore that explicitly so no later scenario runs in otherTz.
    const originalTz = (await ctx.adb.getprop('persist.sys.timezone')).trim() || 'GMT';
    const otherTz = originalTz === 'Asia/Tokyo' ? 'America/New_York' : 'Asia/Tokyo';
    /** wall-clock change applied right after the heartbeat with this uuid, ms (0 = time zone only) */
    const changes = new Map<string, number>();
    const nextHeartbeat = (after: WireRecord, what: string) =>
      waitForRecord(ctx, office, (r) => r.event === 'heartbeat' && r.boot_count === after.boot_count && r.elapsed_realtime_ms > after.elapsed_realtime_ms, {
        sinceHost: started.sinceHost,
        timeoutMs: (MAX_S + TOLERANCE_S) * 1000,
        what,
      });
    /** Moves the device wall clock by [deltaMs]; resolves with the jump actually applied (device delta minus real time). */
    const jumpClock = async (deltaMs: number): Promise<number> => {
      const hostBefore = Date.now();
      const deviceBefore = await ctx.adb.deviceTime();
      await ctx.adb.setTime(deviceBefore + deltaMs);
      const deviceAfter = await ctx.adb.deviceTime();
      return deviceAfter - deviceBefore - (Date.now() - hostBefore);
    };

    let h4: WireRecord;
    try {
      await ctx.adb.setAutoTime(false);
      const h1 = await nextHeartbeat(anchor, 'the first stationary heartbeat');
      changes.set(h1.uuid, await jumpClock(2 * 3_600_000));
      const h2 = await nextHeartbeat(h1, 'a heartbeat after the wall clock jumped 2 h forward');
      changes.set(h2.uuid, await jumpClock(-3 * 3_600_000));
      // After the backward jump the next heartbeat is still due about minInterval from now, not in 3 hours.
      const status = await ctx.commands.heartbeatStatus();
      const untilNext = epoch(status.nextHeartbeatAt, 'status.nextHeartbeatAt') - (await ctx.adb.deviceTime());
      assert.ok(
        untilNext >= -5_000 && untilNext <= (MIN_S + 5) * 1000,
        `after the wall clock jumped 3 h back, status.nextHeartbeatAt must be at most ${MIN_S} s ahead of the device clock, ` +
          `got ${seconds(untilNext)}: ${JSON.stringify(status)}`,
      );
      const h3 = await nextHeartbeat(h2, 'a heartbeat after the wall clock jumped 3 h back');
      await ctx.adb.setTimezone(otherTz);
      changes.set(h3.uuid, 0);
      h4 = await nextHeartbeat(h3, `a heartbeat after the time zone changed from ${originalTz} to ${otherTz}`);
    } finally {
      await restore(ctx, 'wall clock', () => ctx.adb.setTime(Date.now() + deviceOffsetMs));
      await restore(ctx, 'auto time', () => ctx.adb.setAutoTime(true));
      await restore(ctx, 'time zone', () => ctx.adb.setTimezone(originalTz));
    }

    // The upload order is the creation order, and elapsed_realtime_ms is monotonic in it although the wall clock jumped.
    const storedRecords = stored(office, started.sinceHost).sort(
      (a, b) => a.receivedAt - b.receivedAt || a.requestId - b.requestId || a.index - b.index,
    );
    const byArrival = storedRecords.map((s) => s.record);
    const bootCounts = new Set(byArrival.map((r) => r.boot_count));
    assert.equal(bootCounts.size, 1, `one boot: every record must have the same boot_count, got ${[...bootCounts].join(', ')}`);
    for (let i = 1; i < byArrival.length; i++) {
      assert.ok(
        byArrival[i]!.elapsed_realtime_ms > byArrival[i - 1]!.elapsed_realtime_ms,
        `elapsed_realtime_ms must increase in upload (creation) order:\n  ${describe(byArrival[i - 1]!)}\n  ${describe(byArrival[i]!)}`,
      );
    }

    const records = [...byArrival].sort(byCreation);
    const lastIndex = records.findIndex((r) => r.uuid === h4!.uuid);
    for (let i = records.findIndex((r) => r.uuid === anchor.uuid) + 1; i <= lastIndex; i++) {
      const prev = records[i - 1]!;
      const cur = records[i]!;
      const elapsedDelta = cur.elapsed_realtime_ms - prev.elapsed_realtime_ms;
      const wallDelta = epoch(cur.recorded_at, 'recorded_at') - epoch(prev.recorded_at, 'recorded_at');
      const jump = changes.get(prev.uuid) ?? 0;
      const tolerance = changes.has(prev.uuid) && jump !== 0 ? 5_000 : 2_000;
      assert.ok(
        Math.abs(wallDelta - elapsedDelta - jump) <= tolerance,
        `recorded_at must follow the device wall clock: between these records it moved ${seconds(wallDelta)} while the ` +
          `elapsed clock moved ${seconds(elapsedDelta)} and the applied change was ${seconds(jump)} (tolerance ${seconds(tolerance)}):\n` +
          `  ${describe(prev)}\n  ${describe(cur)}`,
      );
      assert.ok(cur.recorded_at.endsWith('Z'), `recorded_at must stay UTC after the time zone change: ${cur.recorded_at}`);
      if (cur.event === 'heartbeat') {
        // Cadence on the elapsed clock: about minInterval after the previous record, whatever the wall clock did.
        assert.ok(
          elapsedDelta >= (MIN_S - 1) * 1000 && elapsedDelta <= (MIN_S + TOLERANCE_S) * 1000,
          `heartbeat ${seconds(elapsedDelta)} (elapsed clock) after the previous record; expected ${MIN_S} s (+${TOLERANCE_S} s) ` +
            `although the wall clock changed by ${seconds(jump)}:\n  ${describe(prev)}\n  ${describe(cur)}`,
        );
      }
    }
  },
  { timeoutMs: 12 * 60_000, requires: { root: true } },
);

scenario(
  'P-H07',
  'airplane mode: records queue, upload after reconnect with original recorded_at and later sent_at',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true });
    const config = await ctx.testConfig();
    const started = await startTracking(ctx, office, config, PLACES.hq);
    const anchor = await waitForAnchor(ctx, office, started);

    // Device times: records created before airplaneOnAtDevice are "online"; records created from offlineAtDevice (no
    // default network confirmed) until onlineAtDevice are "offline". Records in between are in neither group: the
    // network went down at an unknown moment within that interval.
    const heartbeatTimesSeenOffline = new Set<string>();
    let wifiAndDataOff = false;
    let airplaneOnAtDevice = 0;
    let offlineAtDevice = 0;
    let onlineAtDevice = 0;
    let onlineAtHost = 0;
    let insertedUuid = '';
    let insertWindow = { from: 0, to: 0 };
    try {
      airplaneOnAtDevice = await ctx.adb.deviceTime();
      await ctx.adb.setAirplaneMode(true);
      let network: 'none' | 'up' | 'unknown' = 'unknown';
      try {
        network = await waitUntil(
          async () => {
            const state = await defaultNetworkState(ctx);
            return state === 'up' ? undefined : state;
          },
          { timeoutMs: 15_000, intervalMs: 1_000, message: 'no default network after airplane mode on', signal: ctx.signal },
        );
      } catch {
        network = 'up';
      }
      if (network === 'up') {
        // Android 13+ keeps Wi-Fi on in airplane mode when the user turned it on in airplane mode before.
        ctx.log('a default network is still up in airplane mode; turning Wi-Fi and mobile data off as well');
        wifiAndDataOff = true;
        await ctx.adb.setWifi(false);
        await ctx.adb.setData(false);
        await waitUntil(async () => (await defaultNetworkState(ctx)) !== 'up', {
          timeoutMs: 15_000,
          intervalMs: 1_000,
          message: 'no default network with airplane mode, Wi-Fi and data off',
          signal: ctx.signal,
        });
      } else if (network === 'unknown') {
        ctx.log('dumpsys connectivity has no "Active default network" line; relying on pendingHeartbeats below');
      }
      offlineAtDevice = await ctx.adb.deviceTime();

      // A normal record while offline (insertLocation), then wait until two heartbeats are queued.
      insertWindow = { from: await ctx.adb.deviceTime(), to: 0 };
      insertedUuid = await ctx.commands.insertLocation({
        coords: { latitude: PLACES.hq.lat, longitude: PLACES.hq.lon, accuracy: 8 },
        extras: { offline: true },
      });
      insertWindow.to = await ctx.adb.deviceTime();
      try {
        await waitUntil(
          async () => {
            const status = await ctx.commands.heartbeatStatus();
            // lastHeartbeatAt shows only the newest heartbeat; polling every 5 s sees each one (they are 60 s apart).
            if (status.pendingHeartbeats > 0 && status.lastHeartbeatAt !== null && Date.parse(status.lastHeartbeatAt) >= offlineAtDevice) {
              heartbeatTimesSeenOffline.add(status.lastHeartbeatAt);
            }
            return heartbeatTimesSeenOffline.size >= 2 && status.pendingHeartbeats >= 2 ? status : undefined;
          },
          {
            timeoutMs: 2 * (MAX_S + TOLERANCE_S) * 1000 + 30_000,
            intervalMs: 5_000,
            message: 'two heartbeats created after the network was confirmed down and still queued',
            signal: ctx.signal,
          },
        );
      } catch (error) {
        throw new Error(
          `expected two queued heartbeats while offline; received meanwhile (should be nothing new):\n` +
            `${timeline(received(office, started.sinceHost))}\n(${errorText(error)})`,
        );
      }
      const leaked = received(office, started.sinceHost).filter((r) => epoch(r.recorded_at, 'recorded_at') >= offlineAtDevice);
      assert.equal(leaked.length, 0, `records created while offline must not reach the server before reconnecting:\n${timeline(leaked)}`);
      onlineAtDevice = await ctx.adb.deviceTime();
      onlineAtHost = Date.now();
    } finally {
      await restore(ctx, 'airplane mode off', () => ctx.adb.setAirplaneMode(false));
      if (wifiAndDataOff) {
        await restore(ctx, 'Wi-Fi on', () => ctx.adb.setWifi(true));
        await restore(ctx, 'mobile data on', () => ctx.adb.setData(true));
      }
    }

    // After reconnecting, the queue drains by itself (connectivity regained triggers an upload pass).
    const offlineCreated = (r: WireRecord) => {
      const at = epoch(r.recorded_at, 'recorded_at');
      return at >= offlineAtDevice && at < onlineAtDevice;
    };
    await office.waitFor(
      (o) => {
        const got = received(o, started.sinceHost);
        const heartbeatsBack = [...heartbeatTimesSeenOffline].every((t) => got.some((r) => r.event === 'heartbeat' && r.recorded_at === t));
        return heartbeatsBack && got.some((r) => r.uuid === insertedUuid) ? true : undefined;
      },
      { timeoutMs: 120_000, message: 'the records queued offline uploaded after reconnecting', signal: ctx.signal },
    );
    const status = await waitUntil(
      async () => {
        const s = await ctx.commands.heartbeatStatus();
        return s.pendingHeartbeats === 0 ? s : undefined;
      },
      { timeoutMs: 60_000, intervalMs: 3_000, message: 'pendingHeartbeats back to 0 after reconnecting', signal: ctx.signal },
    );
    ctx.log(`after reconnecting: ${JSON.stringify(status)}`);

    const records = received(office, started.sinceHost);
    assertions.noDuplicates(receivedAll(office, started.sinceHost));
    const offline = records.filter(offlineCreated);
    const offlineHeartbeats = offline.filter((r) => r.event === 'heartbeat');
    assert.ok(offlineHeartbeats.length >= 2, `at least two heartbeats were created offline; received:\n${timeline(records)}`);
    assert.ok(offline.some((r) => r.uuid === insertedUuid), `the record inserted offline (${insertedUuid}) must be uploaded`);

    // Original recorded_at: each heartbeat reported by the device while it sat in the queue arrives with that time.
    for (const time of heartbeatTimesSeenOffline) {
      assert.ok(
        offlineHeartbeats.some((r) => r.recorded_at === time),
        `the device reported a queued heartbeat created at ${time}; no uploaded heartbeat has that recorded_at:\n${timeline(offlineHeartbeats)}`,
      );
    }
    const inserted = offline.find((r) => r.uuid === insertedUuid)!;
    assertions.withinWindow(inserted.recorded_at, { from: insertWindow.from - 1_000, to: insertWindow.to + 1_000 }, 'recorded_at of the inserted record');

    // Later sent_at: built after the network came back, and in creation order (oldest first).
    const offlineStored = stored(office, started.sinceHost).filter((s) => offlineCreated(s.record));
    for (const s of offlineStored) {
      const sentAt = epoch(s.record.sent_at, 'sent_at');
      assert.ok(
        sentAt > onlineAtDevice,
        `a record created offline must be sent after reconnecting (device time ${new Date(onlineAtDevice).toISOString()}):\n  ${describe(s.record)}`,
      );
      assert.ok(s.receivedAt >= onlineAtHost, `a record created offline arrived before reconnecting:\n  ${describe(s.record)}`);
    }
    const inCreationOrder = [...offlineStored].sort((a, b) => byCreation(a.record, b.record));
    for (let i = 1; i < inCreationOrder.length; i++) {
      const prev = inCreationOrder[i - 1]!;
      const cur = inCreationOrder[i]!;
      assert.ok(
        prev.requestId < cur.requestId || (prev.requestId === cur.requestId && prev.index < cur.index),
        `queued records must upload oldest first (request #${prev.requestId}/${prev.index} then #${cur.requestId}/${cur.index}):\n` +
          `  ${describe(prev.record)}\n  ${describe(cur.record)}`,
      );
    }
    // Priority records created online (2 s before airplane mode, so no upload was cut off) went out at once.
    const online = records.filter((x) => epoch(x.recorded_at, 'recorded_at') < airplaneOnAtDevice - 2_000 && PRIORITY_EVENTS.has(x.event));
    for (const r of online) {
      const delay = epoch(r.sent_at, 'sent_at') - epoch(r.recorded_at, 'recorded_at');
      assert.ok(delay <= IMMEDIATE_S * 1000, `a priority record created online must be sent at once, waited ${seconds(delay)}:\n  ${describe(r)}`);
    }
    ctx.log(`anchor ${anchor.uuid.slice(0, 8)}; ${offline.length} records created offline, all delivered late with their original recorded_at`);
  },
  { timeoutMs: 10 * 60_000 },
);

scenario(
  'P-H08',
  'server 500 then 200 is retried; 401 refreshes the JWT via /auth/refresh and retries',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true });
    const config = await ctx.testConfig({ jwt: true });

    // Phase 1: the first upload (the tracking_start) is answered 500; the next upload pass retries it.
    office.setFault({ path: '/locations', status: 500, count: 1 });
    const started = await startTracking(ctx, office, config, PLACES.hq);
    const ts = started.trackingStart;
    await waitForAnchor(ctx, office, started);
    const phase1 = sortedRequests(office, '/locations', started.sinceHost);
    const first = phase1[0];
    assert.ok(first !== undefined, 'no upload request arrived');
    assert.equal(first.status, 500, `the first upload must be answered by the 500 fault:\n${requestLog(phase1)}`);
    const firstRecords = bodyRecords(first.body);
    assert.ok(firstRecords.some((r) => r.uuid === ts.uuid), `the first upload must carry the tracking_start:\n${requestLog(phase1)}`);
    assert.equal(headerValue(first, 'authorization'), 'Bearer e2e-initial', `the first upload uses the configured access token:\n${requestLog(phase1)}`);
    const retry = phase1.find((r) => r.id > first.id && bodyRecords(r.body).some((x) => x.uuid === ts.uuid));
    assert.ok(retry !== undefined, `the tracking_start answered 500 must be sent again:\n${requestLog(phase1)}`);
    assert.equal(retry.status, 200, `the retry must be accepted:\n${requestLog(phase1)}`);
    assert.equal(headerValue(retry, 'authorization'), 'Bearer e2e-initial', `a 500 does not refresh the token:\n${requestLog(phase1)}`);
    const firstSent = epoch(firstRecords.find((r) => r.uuid === ts.uuid)!.sent_at, 'sent_at of the 500 request');
    const retrySent = epoch(bodyRecords(retry.body).find((r) => r.uuid === ts.uuid)!.sent_at, 'sent_at of the retry');
    assert.ok(retrySent > firstSent, `a retry is a new request with a later sent_at (${firstSent} -> ${retrySent})`);
    const storedTs = storedOf(office, ts.uuid);
    assert.equal(storedTs.record.sent_at, bodyRecords(retry.body).find((r) => r.uuid === ts.uuid)!.sent_at, 'the stored tracking_start comes from the retry');
    assert.equal(sortedRequests(office, '/auth/refresh', started.sinceHost).length, 0, 'a 500 must not trigger a token refresh');

    // Phase 2: the next upload is answered 401: one refresh at /auth/refresh, then the same records again with the new
    // token.
    const phase2Since = Date.now();
    office.setFault({ path: '/locations', status: 401, count: 1 });
    const insertedUuid = await ctx.commands.insertLocation({
      coords: { latitude: PLACES.hq.lat, longitude: PLACES.hq.lon, accuracy: 8 },
      extras: { phase: 'jwt-401' },
    });
    await office.waitFor(
      (o) =>
        sortedRequests(o, '/locations', phase2Since).find(
          (r) => r.status === 200 && headerValue(r, 'authorization') === 'Bearer e2e-access-1',
        ),
      { timeoutMs: 90_000, message: 'an upload with Authorization: Bearer e2e-access-1 accepted after the 401', signal: ctx.signal },
    );
    const uploads = sortedRequests(office, '/locations', phase2Since);
    const refreshes = sortedRequests(office, '/auth/refresh', phase2Since);
    const log = `uploads:\n${requestLog(uploads)}\nrefreshes:\n${requestLog(refreshes)}`;
    const rejected = uploads[0]!;
    assert.equal(rejected.status, 401, `the first upload after setting the fault must be answered 401\n${log}`);
    assert.equal(headerValue(rejected, 'authorization'), 'Bearer e2e-initial', `the rejected upload carried the old token\n${log}`);
    assert.equal(refreshes.length, 1, `exactly one token refresh\n${log}`);
    const refresh = refreshes[0]!;
    assert.ok(refresh.receivedAt >= rejected.receivedAt, `the refresh follows the 401\n${log}`);
    assert.equal(refresh.status, 200, `the refresh is answered 200\n${log}`);
    let refreshBody: unknown;
    try {
      refreshBody = JSON.parse(refresh.body);
    } catch {
      refreshBody = refresh.body;
    }
    assert.deepEqual(refreshBody, { refresh_token: 'e2e-refresh' }, `refreshPayload with {refreshToken} replaced\n${log}`);
    const retried = uploads[1];
    assert.ok(retried !== undefined, `the rejected upload must be retried\n${log}`);
    assert.equal(headerValue(retried, 'authorization'), 'Bearer e2e-access-1', `the retry uses the refreshed token\n${log}`);
    assert.equal(retried.status, 200, `the retry is accepted\n${log}`);
    assert.ok(retried.receivedAt >= refresh.receivedAt, `the retry follows the refresh\n${log}`);
    assert.deepEqual(
      bodyRecords(retried.body).map((r) => r.uuid),
      bodyRecords(rejected.body).map((r) => r.uuid),
      `the retry carries the same records as the rejected upload\n${log}`,
    );
    await office.waitFor((o) => o.records({ uuid: insertedUuid }).length > 0 || undefined, {
      timeoutMs: 60_000,
      message: 'the inserted record uploaded',
      signal: ctx.signal,
    });
    assert.equal(storedOf(office, insertedUuid).authorization, 'Bearer e2e-access-1', 'the inserted record was stored from an upload with the new token');

    // The refreshed token is kept: the next upload uses it without another refresh, and the config holds the new tokens.
    const phase3Since = Date.now();
    await ctx.commands.insertLocation({ coords: { latitude: PLACES.hq.lat, longitude: PLACES.hq.lon, accuracy: 8 }, extras: { phase: 'after-refresh' } });
    const next = await office.waitFor((o) => sortedRequests(o, '/locations', phase3Since).find((r) => r.status === 200), {
      timeoutMs: 60_000,
      message: 'an upload after the refresh',
      signal: ctx.signal,
    });
    assert.equal(headerValue(next, 'authorization'), 'Bearer e2e-access-1', `later uploads keep the refreshed token: ${describeRequest(next)}`);
    assert.equal(sortedRequests(office, '/auth/refresh', phase2Since).length, 1, 'no second refresh while the token is valid');
    const state = await ctx.commands.state();
    assert.equal(valueAt(state.config, ['http', 'authorization', 'accessToken']), 'e2e-access-1', 'the refreshed access token is saved in the config');
    assert.equal(valueAt(state.config, ['http', 'authorization', 'refreshToken']), 'e2e-refresh-1', 'the refreshed refresh token is saved in the config');
  },
  { timeoutMs: 10 * 60_000 },
);

scenario(
  'P-H09',
  'syncInterval batches normal records while moving; audit records upload immediately',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true });
    const syncS = TEST_SYNC_INTERVAL_S;
    // batchSync / maxBatchSize are set on the returned config (withHttp) rather than through the kit's `patch`, whose
    // merge rules for nested objects are not part of the contract; the ready() state below confirms all three values.
    const config = withHttp(await ctx.testConfig({ syncInterval: syncS }), { batchSync: true, maxBatchSize: MAX_BATCH });
    const routeStart = ROUTES.cityLoop3km[0];
    const started = await startTracking(ctx, office, config, routeStart);
    assert.equal(valueAt(started.readyState.config, ['http', 'syncInterval']), syncS, 'http.syncInterval in the ready() state');
    assert.equal(valueAt(started.readyState.config, ['http', 'batchSync']), true, 'http.batchSync in the ready() state');
    assert.equal(valueAt(started.readyState.config, ['http', 'maxBatchSize']), MAX_BATCH, 'http.maxBatchSize in the ready() state');

    // Stationary: the initial motionchange (a normal record) is not uploaded on its own before syncInterval; it goes out
    // with a priority record: the first heartbeat, or the tracking_start when the fix came before that upload read the
    // queue.
    await waitForRecord(ctx, office, (r) => r.event === 'heartbeat' && createdAfter(r, started.trackingStart), {
      sinceHost: started.sinceHost,
      timeoutMs: (MAX_S + TOLERANCE_S) * 1000 + 30_000,
      what: 'the first stationary heartbeat',
    });
    const anchor = received(office, started.sinceHost).find((r) => r.event === 'motionchange' && !r.is_moving);
    assert.ok(anchor !== undefined, `the initial motionchange must be uploaded by the first heartbeat:\n${timeline(received(office, started.sinceHost))}`);
    const anchorRequest = storedOf(office, anchor.uuid).requestId;
    const sameRequest = stored(office, started.sinceHost)
      .filter((s) => s.requestId === anchorRequest)
      .map((s) => s.record);
    assert.ok(
      sameRequest.some((r) => PRIORITY_EVENTS.has(r.event)),
      `the initial motionchange must be uploaded together with a priority record (heartbeat or tracking_start), ` +
        `not alone before syncInterval; its request held:\n${timeline(sameRequest)}`,
    );

    // Moving: location records every few seconds; the syncInterval timer uploads them in batches.
    const movingSince = Date.now();
    await ctx.commands.changePace(true);
    const route = startRouteLoop(ctx, [], ROUTES.cityLoop3km, DRIVE_SPEED_MPS);
    let stopSince = 0;
    try {
      try {
        await office.waitFor(
          (o) => (normalOnlyLocationDrains(o.requests({ path: '/locations', since: movingSince })).length >= 2 ? true : undefined),
          { timeoutMs: (2 * syncS + 90) * 1000, intervalMs: 2_000, message: 'two syncInterval uploads of location records', signal: ctx.signal },
        );
      } catch (error) {
        throw new Error(
          `expected two syncInterval uploads of location records while driving (route replay failure: ` +
            `${route.failure === undefined ? 'none' : errorText(route.failure)}):\n` +
            `${requestLog(sortedRequests(office, '/locations', movingSince))}\n(${errorText(error)})`,
        );
      }
      stopSince = Date.now();
      const stopped = await ctx.commands.stop();
      assert.equal(stopped.enabled, false, `stop(): ${JSON.stringify(stopped)}`);
    } finally {
      await route.stop();
    }
    assert.equal(route.failure, undefined, `the emulator route replay failed: ${errorText(route.failure)}`);
    const trackingStop = await waitForRecord(ctx, office, (r) => r.event === 'tracking_stop' && r.reason === 'stop', {
      sinceHost: stopSince,
      timeoutMs: 30_000,
      what: "tracking_stop with reason 'stop' uploaded at once",
    });

    const records = received(office, started.sinceHost);
    assertions.noDuplicates(receivedAll(office, started.sinceHost));
    assertions.inOrder(records, [
      { event: 'tracking_start', reason: 'start' },
      { event: 'motionchange', is_moving: false },
      'heartbeat',
      { event: 'motionchange', is_moving: true },
      'location',
      { event: 'tracking_stop', reason: 'stop' },
    ]);

    // Audit (priority) records go out at once; normal records at most syncInterval (+ tolerance) after creation.
    for (const r of records) {
      const delay = epoch(r.sent_at, 'sent_at') - epoch(r.recorded_at, 'recorded_at');
      if (PRIORITY_EVENTS.has(r.event)) {
        assert.ok(delay <= IMMEDIATE_S * 1000, `a ${r.event} must be sent at once, it waited ${seconds(delay)}:\n  ${describe(r)}`);
      } else {
        assert.ok(
          delay <= (syncS + TOLERANCE_S) * 1000,
          `a ${r.event} must be sent at most syncInterval (${syncS} s) + ${TOLERANCE_S} s after creation, it waited ${seconds(delay)}:\n  ${describe(r)}`,
        );
      }
    }

    // Uploads while driving: batches of at most maxBatchSize; an upload of normal records only happens once the oldest
    // queued record is syncInterval old.
    const requests = sortedRequests(office, '/locations', movingSince);
    for (const request of requests) {
      const count = bodyRecords(request.body).length;
      assert.ok(count >= 1 && count <= MAX_BATCH, `a batch holds 1..${MAX_BATCH} records: ${describeRequest(request)}`);
      const times = bodyRecords(request.body).map((r) => epoch(r.recorded_at, 'recorded_at'));
      assert.deepEqual(times, [...times].sort((a, b) => a - b), `records of a batch go oldest first: ${describeRequest(request)}`);
    }
    // Only uploads that arrived before stop(): the stop's own pass is caused by the tracking_stop (a priority record) and
    // may be split into batches; none of its requests arrives before stop() was sent.
    const drains = normalOnlyLocationDrains(requests.filter((r) => r.receivedAt < stopSince));
    for (const drain of drains) {
      const oldest = Math.min(...drain.records.map((r) => epoch(r.recorded_at, 'recorded_at')));
      const firstSent = Math.min(...drain.records.map((r) => epoch(r.sent_at, 'sent_at')));
      assert.ok(
        firstSent - oldest >= (syncS - 5) * 1000,
        `an upload of normal records only must wait until the oldest is syncInterval (${syncS} s) old, it went after ` +
          `${seconds(firstSent - oldest)}:\n${requestLog(drain.requests)}`,
      );
      assert.ok(drain.records.length >= 2, `a syncInterval upload carries a batch, got one record:\n${requestLog(drain.requests)}`);
    }
    const locationRecords = records.filter((r) => r.event === 'location');
    const locationRequests = requests.filter((r) => bodyRecords(r.body).some((x) => x.event === 'location'));
    assert.ok(
      locationRecords.length >= 5 * locationRequests.length,
      `normal records are batched: ${locationRecords.length} location records in ${locationRequests.length} requests ` +
        `(expected at least 5 per request):\n${requestLog(locationRequests)}`,
    );

    // tracking_stop drains the queue: nothing created before it arrives after it.
    const stopStored = storedOf(office, trackingStop.uuid);
    for (const s of stored(office, started.sinceHost)) {
      if (byCreation(s.record, trackingStop) < 0) {
        assert.ok(s.receivedAt <= stopStored.receivedAt, `tracking_stop must take the queued records along; this one arrived after it:\n  ${describe(s.record)}`);
      }
    }
    ctx.log(`${locationRecords.length} location records in ${locationRequests.length} requests, ${drains.length} syncInterval uploads`);
  },
  { timeoutMs: 14 * 60_000 },
);

scenario(
  'P-H10',
  'heartbeat records carry the heartbeat metadata object',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true, batteryExempt: false });
    const config = await ctx.testConfig();
    const started = await startTracking(ctx, office, config, PLACES.hq);
    const anchor = await waitForAnchor(ctx, office, started);
    const awakeStrategy = ctx.device.api >= 31 ? 'listener_with_backup' : 'exact';
    const nextHeartbeat = (after: WireRecord, what: string) =>
      waitForRecord(ctx, office, (r) => r.event === 'heartbeat' && createdAfter(r, after), {
        sinceHost: started.sinceHost,
        timeoutMs: (MAX_S + TOLERANCE_S) * 1000,
        what,
      });

    let exempted = false;
    try {
      const h1 = await nextHeartbeat(anchor, 'the first stationary heartbeat');
      assertMetaMatchesStatus(h1, await settledStatus(ctx), { strategy: awakeStrategy, batteryExempt: false, deviceIdle: false });

      const h2 = await nextHeartbeat(h1, 'the second stationary heartbeat');
      assertMetaMatchesStatus(h2, await settledStatus(ctx), { strategy: awakeStrategy, batteryExempt: false, deviceIdle: false });
      const announced = epoch(h1.heartbeat!.next_at, 'next_at');
      assertions.withinWindow(h2.recorded_at, { from: announced - 1_000, to: announced + TOLERANCE_S * 1000 }, 'the second heartbeat vs the first one\'s next_at');

      // Battery exemption granted while tracking: the next heartbeat reports it and is scheduled exactly.
      await ctx.adb.deviceIdle.whitelistAdd(ctx.appId);
      exempted = true;
      const h3 = await nextHeartbeat(h2, 'the heartbeat after the battery-optimization exemption');
      const s3 = await settledStatus(ctx);
      assertMetaMatchesStatus(h3, s3, { strategy: 'exact', batteryExempt: true, deviceIdle: false });
      assert.equal(s3.canScheduleExactAlarms, true, `an exempt app may schedule exact alarms: ${JSON.stringify(s3)}`);
    } finally {
      if (exempted) await restore(ctx, 'battery-optimization allowlist', () => ctx.adb.deviceIdle.whitelistRemove(ctx.appId));
    }

    // Every heartbeat record of the session carries the metadata object.
    const heartbeats = received(office, started.sinceHost).filter((r) => r.event === 'heartbeat');
    assert.ok(heartbeats.length >= 3, `three heartbeats expected:\n${timeline(heartbeats)}`);
    for (const hb of heartbeats) heartbeatMeta(hb);
    // Other records never carry it.
    for (const r of received(office, started.sinceHost).filter((x) => x.event !== 'heartbeat')) {
      assert.equal(r.heartbeat, undefined, `only heartbeat records carry the 'heartbeat' object:\n  ${describe(r)}`);
    }
  },
  { timeoutMs: 10 * 60_000 },
);
