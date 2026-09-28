// Field-force suite (unit 14): the product setup of docs/e2e/architecture.md §10 on an Android emulator (AVD).
// Scenario ids F-01 … F-12 and their requirements: §9. Kit API: §8 (`@bricks-soft/e2e-kit`, the only dependency, so this
// file can move into the Bricks app as an integration test together with the kit).
//
// How every scenario runs:
// - `setUp()` clears the app's data and writes the test overrides into `files/e2e/ff-overrides.json` before the app
//   starts (§6 "Test-mode files"): heartbeat 60/120 s, `http.syncInterval` 120 s, `geolocation.stopTimeout` 1 min, the
//   mock back office as backend, and per scenario a stop time (`stopAt`) or the premise to monitor.
// - The app is started like a user starts it (launcher activity). Its page runs the startup of §10 (auto start) and
//   publishes `window.FF_APP.startup`, which the scenarios read through the WebView driver.
// - Location comes from the emulator's GNSS (`adb emu geo fix` and route replays). Movement is started with the
//   `changePace(true)` debug command, so the scenarios do not depend on how fast the emulator's motion detection is
//   (the plugin suite P-H03 tests that).
// - Results are read from the mock back office: `/locations` (plugin uploads) and `/premise-audit` (the fake
//   PremiseMonitor's audit entries). Every device setting a scenario changes is restored in its cleanup.
//
// Run a subset: `NODE_OPTIONS='--test-name-pattern=F-0[79]' npm run test:e2e` (on Node 22, `npm run test:e2e --
// --test-name-pattern=…` does not filter). The whole suite takes about 70 minutes.
import {
  FIELD_FORCE_TEST_OVERRIDES,
  PLACES,
  PREMISES,
  ROUTES,
  SERVICES,
  TEST_HEARTBEAT,
  TEST_SYNC_INTERVAL_S,
  assertions,
  scenario,
  sleep,
  waitUntil,
  type Adb,
  type AppUnderTest,
  type FieldForceOverrides,
  type LatLon,
  type MockBackOffice,
  type PremiseAuditEntry,
  type PrepareOptions,
  type RecordEvent,
  type ScenarioContext,
  type StateJson,
  type StoredRecord,
  type WebViewDriver,
  type WireRecord,
} from '@bricks-soft/e2e-kit';

// ---------------------------------------------------------------------------------------------------------------------
// Constants

/** Slack (seconds) for alarm, timer and upload latency in heartbeat windows and upload delays. */
const TOLERANCE_S = 30;
/** The back office shows a device as online while its newest record is at most this old (maxInterval + slack), s. */
const MAX_SILENCE_S = TEST_HEARTBEAT.maxInterval + TOLERANCE_S;
/** Wait for the next heartbeat on a server: one full heartbeat window, the slack and one upload, ms. */
const HEARTBEAT_WAIT_MS = (TEST_HEARTBEAT.maxInterval + TOLERANCE_S + 30) * 1000;
/** `geolocation.stopTimeout` of every scenario, minutes (the production preset uses 5). */
const STOP_TIMEOUT_MIN = 1;
/**
 * The elapsed stop (the 02:00 stop) may be this many seconds later than planned: the engine's timer is a coroutine
 * `delay`, and `tracking_stop` is created when it fires.
 */
const STOP_LATE_S = 60;
/** ... and this many seconds earlier: `trackingStartedAt` is taken a few hundred ms before `tracking_start` is created. */
const STOP_EARLY_S = 3;
/** Minutes until the planned stop in F-03 (enough for a kill, a restore and a cold emulator reboot). */
const F03_STOP_MINUTES = 10;
/** F-06: how long tracking stays off; longer than MAX_SILENCE_S, so the pause is a gap the audit must explain. */
const OFFLINE_S = MAX_SILENCE_S + 10;
/** Speed of the drive into and out of the premise (400 m), m/s. */
const APPROACH_SPEED_MPS = 10;
/** Speed of the F-04 city loop (3 km), m/s (54 km/h). */
const TRIP_SPEED_MPS = 15;
/** `BatteryManager.BATTERY_STATUS_DISCHARGING`. */
const BATTERY_STATUS_DISCHARGING = 3;

/** Test-mode file the field-force page reads at startup (§6 addendum, §10 step 1). */
const OVERRIDES_FILE = 'ff-overrides.json';
/** Where the scenarios without a premise start: 1.5 km south of HQ, far outside the premise. */
const START_PLACE = PLACES.hqSouth1500m;
const PREMISE = PREMISES.hq;
/** GPS on at the start point before the premise is registered again, so Google Play services knows it is outside. */
const OUTSIDE_SETTLE_MS = 15_000;
const PREMISE_CENTRE: LatLon = { lat: PREMISE.latitude, lon: PREMISE.longitude };
/** Geofence identifier PremiseMonitor registers with the tracking plugin (§10). */
const PREMISE_GEOFENCE_ID = `premise:${PREMISE.id}`;
/** Keys of `http.params.device` in the field-force preset (§10), compared with `getDeviceInfo()`. */
const DEVICE_PARAM_KEYS = [
  'manufacturer',
  'model',
  'brand',
  'osVersion',
  'sdkInt',
  'pluginVersion',
  'backend',
  'gmsAvailable',
  'hmsAvailable',
] as const;
/**
 * Events that carry a record, by record event (RecordSink step 4; `geofence` is emitted by the geofence manager).
 * PremiseMonitor must audit each of them as an `event` entry whose payload carries the record's uuid.
 */
const EVENTS_OF_RECORD: Partial<Record<RecordEvent, readonly string[]>> = {
  location: ['location'],
  current_position: ['location'],
  watch_position: ['location'],
  motionchange: ['location', 'motionchange'],
  heartbeat: ['heartbeat'],
  geofence: ['geofence'],
};

// ---------------------------------------------------------------------------------------------------------------------
// Types

/** `LocationTracking.getDeviceInfo()` (src/definitions.ts `DeviceInfo`). */
interface DeviceInfoJson {
  platform: string;
  manufacturer: string;
  model: string;
  brand: string;
  osVersion: string;
  sdkInt: number;
  pluginVersion: string;
  gmsAvailable: boolean;
  hmsAvailable: boolean;
  backend: string;
  packagedProviders?: string[];
}

/**
 * What `window.FF_APP.startup` resolves with (§10 step 9). `overridesSource` and `warnings` are diagnostics of the
 * field-force app (unit 12), not part of the contract; they are only checked when present.
 */
interface FieldForceStartup {
  state: StateJson;
  stopAfterElapsedMinutes: number;
  config: Record<string, any>;
  deviceInfo: DeviceInfoJson;
  overridesSource?: string;
  warnings?: unknown[];
}

/**
 * `PrepareOptions` with the test-mode files of the §6 addendum. Declared here so this file compiles against a kit
 * with or without the `testFiles` field (a variable of this type passes no excess-property check).
 */
type PrepareWithTestFiles = PrepareOptions & { testFiles?: Record<string, unknown> };

/** `AppUnderTest.writeTestFile` of the §6 addendum. */
interface TestFileApi {
  writeTestFile(name: string, value: unknown): Promise<void>;
}

/** One scenario's handles. */
interface Session {
  readonly ctx: ScenarioContext;
  readonly office: MockBackOffice;
  /** host epoch ms after `prepare()`: records and entries received earlier belong to another scenario */
  readonly since: number;
  /** device wall clock minus host wall clock, ms (measured once at set-up) */
  readonly clockOffsetMs: number;
  /** the WebView of the current page (set by launchApp) */
  web: WebViewDriver | undefined;
  /** every driver opened in this scenario (closed by the cleanup) */
  readonly webViews: Set<WebViewDriver>;
}

interface SetUpOptions {
  /** emulator GNSS position before the app starts */
  at: LatLon;
  /** merged over the suite's test overrides (see overridesFor) */
  overrides?: FieldForceOverrides;
  /** battery-optimization exemption (needed for background restarts of the foreground service on Android 12+) */
  batteryExempt?: boolean;
}

/** A stop time for the `stopAt` override. */
interface StopPlan {
  /** 'HH:MM', device-local */
  stopAt: string;
  /** device epoch ms of stopAt (seconds 00) */
  targetMs: number;
  /** device UTC offset, minutes */
  offsetMin: number;
}

// ---------------------------------------------------------------------------------------------------------------------
// Cleanup: every device change registers its undo step here

interface CleanupStep {
  label: string;
  run: () => Promise<unknown>;
  /** the step talks to the app (not only to device settings): skipped after a scenario timeout, see Cleanup.run */
  appStep: boolean;
}

/**
 * Undo steps of one scenario, run in reverse order. A failing step is logged; the other steps still run.
 *
 * After a scenario timeout node:test starts the next scenario at once, while this scenario's body is still unwinding,
 * so its cleanup runs during the next scenario on the same app. Then only the device-setting steps run (they restore
 * values that `prepare()` does not reset, and restoring them is harmless to the next scenario); steps that talk to the
 * app (for example a `stop` command) are skipped, because they would put records into the next scenario.
 */
class Cleanup {
  readonly ctx: ScenarioContext;
  private readonly steps: CleanupStep[] = [];

  constructor(ctx: ScenarioContext) {
    this.ctx = ctx;
  }

  /** A step that restores a device setting. */
  add(label: string, run: () => Promise<unknown>): void {
    this.steps.push({ label, run, appStep: false });
  }

  /** A step that talks to the app; skipped after a timeout. */
  addAppStep(label: string, run: () => Promise<unknown>): void {
    this.steps.push({ label, run, appStep: true });
  }

  /** Runs every step (newest first) and returns the failures. */
  async run(): Promise<string[]> {
    const failures: string[] = [];
    const timedOut = this.ctx.signal.aborted;
    for (let step = this.steps.pop(); step !== undefined; step = this.steps.pop()) {
      if (timedOut && step.appStep) {
        this.ctx.log(`cleanup step skipped after the timeout (the next scenario may be running): ${step.label}`);
        continue;
      }
      try {
        await step.run();
      } catch (error) {
        failures.push(`${step.label}: ${errorText(error)}`);
        this.ctx.log(`cleanup step failed: ${step.label}: ${errorText(error)}`);
      }
    }
    return failures;
  }
}

/**
 * Runs [body] with a [Cleanup] and always runs the cleanup afterwards. When the body failed, its error is rethrown
 * (cleanup failures are only logged); when it passed, a cleanup failure fails the scenario, because the next scenario
 * would otherwise start from a changed device.
 */
async function withCleanup(ctx: ScenarioContext, body: (cleanup: Cleanup) => Promise<void>): Promise<void> {
  const cleanup = new Cleanup(ctx);
  try {
    await body(cleanup);
  } catch (error) {
    await cleanup.run();
    throw error;
  }
  const failures = await cleanup.run();
  if (failures.length > 0) {
    throw new Error(`restoring the device after ${ctx.id} failed: ${failures.join('; ')}`);
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// App set-up and launch

/**
 * The suite's overrides: FIELD_FORCE_TEST_OVERRIDES, the back office of this run, stopTimeout 1 min, then [extra]
 * (an `extra.configPatch` is merged over the stopTimeout patch; the page deep-merges configPatch into its preset).
 */
function overridesFor(office: MockBackOffice, extra: FieldForceOverrides = {}): FieldForceOverrides {
  const shared = FIELD_FORCE_TEST_OVERRIDES.configPatch ?? {};
  const patch = extra.configPatch ?? {};
  const geolocation = {
    ...(isObject(shared['geolocation']) ? shared['geolocation'] : {}),
    stopTimeout: STOP_TIMEOUT_MIN,
    ...(isObject(patch['geolocation']) ? patch['geolocation'] : {}),
  };
  return {
    ...FIELD_FORCE_TEST_OVERRIDES,
    backendUrl: office.url(''),
    ...extra,
    configPatch: { ...shared, ...patch, geolocation },
  };
}

/** Clears the app's data, grants every permission, writes the overrides file and puts the emulator at [options.at]. */
async function setUp(ctx: ScenarioContext, cleanup: Cleanup, options: SetUpOptions): Promise<Session> {
  const office = await ctx.backOffice();
  const overrides = overridesFor(office, options.overrides);
  const prepare: PrepareWithTestFiles = {
    clearData: true,
    permissions: 'all',
    batteryExempt: options.batteryExempt ?? false,
    launch: false,
    testFiles: { [OVERRIDES_FILE]: overrides },
  };
  await ctx.app.prepare(prepare);
  await assertTestFile(ctx, OVERRIDES_FILE, overrides);
  const since = Date.now();
  const clockOffsetMs = await measureClockOffset(ctx.adb);
  await holdPosition(ctx, options.at);
  const session: Session = { ctx, office, since, clockOffsetMs, web: undefined, webViews: new Set() };
  cleanup.add('close the WebView connections', async () => {
    for (const web of session.webViews) await web.close().catch(() => undefined);
  });
  ctx.log(`set up: at ${options.at.lat},${options.at.lon}; device clock offset ${clockOffsetMs} ms`);
  return session;
}

/** Replaces a test-mode file; the page reads it on its next load (§6 addendum). */
async function writeTestFile(ctx: ScenarioContext, name: string, value: unknown): Promise<void> {
  const app = ctx.app as AppUnderTest & Partial<TestFileApi>;
  if (typeof app.writeTestFile !== 'function') {
    throw new Error('e2e-kit: AppUnderTest.writeTestFile is missing (docs/e2e/architecture.md §6, test-mode files)');
  }
  await app.writeTestFile(name, value);
  await assertTestFile(ctx, name, value);
}

/**
 * Reads `files/e2e/<name>` back with run-as and compares it with [expected]. Without the file the page silently runs
 * with production values (heartbeat 180/300 s, syncInterval 300 s, the default backend), and every later check would
 * time out for an unrelated reason.
 */
async function assertTestFile(ctx: ScenarioContext, name: string, expected: unknown): Promise<void> {
  const path = `files/e2e/${name}`;
  let text: string;
  try {
    text = await ctx.adb.runAsCat(ctx.appId, path);
  } catch (error) {
    throw new Error(`the test-mode file ${path} was not written (run-as cat failed: ${errorText(error)})`, { cause: error });
  }
  let actual: unknown;
  try {
    actual = JSON.parse(text);
  } catch {
    throw new Error(`the test-mode file ${path} is not JSON: ${text.slice(0, 200)}`);
  }
  check(
    JSON.stringify(actual) === JSON.stringify(expected),
    `the test-mode file ${path} holds ${JSON.stringify(actual)}, expected ${JSON.stringify(expected)}`,
  );
}

/** Starts the launcher activity (the user opens the app) and waits for the field-force startup to finish. */
async function launchApp(s: Session): Promise<FieldForceStartup> {
  await s.ctx.app.launch();
  const web = await s.ctx.app.webView();
  s.web = web;
  s.webViews.add(web);
  return readStartup(s.ctx, web);
}

/** Awaits `window.FF_APP.startup` of the current page. */
async function readStartup(ctx: ScenarioContext, web: WebViewDriver): Promise<FieldForceStartup> {
  await poll(
    ctx,
    'window.FF_APP.startup to be defined by the field-force page (§10 step 9)',
    () => web.evaluate<boolean>("typeof window.FF_APP === 'object' && window.FF_APP !== null && typeof window.FF_APP.startup === 'object'"),
    45_000,
  );
  let startup: FieldForceStartup;
  try {
    startup = await web.evaluate<FieldForceStartup>('window.FF_APP.startup', { timeoutMs: 90_000 });
  } catch (error) {
    // The page keeps the failure in FF_APP.error ({code, message, step}) and FF_APP.result.warnings.
    const detail = await web
      .evaluate<string>('JSON.stringify({error: window.FF_APP.error, warnings: window.FF_APP.result && window.FF_APP.result.warnings})')
      .catch(() => '(FF_APP.error not readable)');
    throw new Error(`the field-force startup (window.FF_APP.startup) rejected: ${errorText(error)}; ${detail}`, { cause: error });
  }
  check(
    isObject(startup),
    `window.FF_APP.startup resolved with ${JSON.stringify(startup)}, expected {state, stopAfterElapsedMinutes, config, deviceInfo}`,
  );
  // Without the test-mode file the page runs with production values (heartbeat 180/300 s, syncInterval 300 s, the
  // default backend) and every later check would fail for an unrelated reason, so say it here.
  check(
    startup.overridesSource === undefined || startup.overridesSource === 'file',
    `the field-force page did not read ${OVERRIDES_FILE} (overridesSource '${startup.overridesSource}'); ` +
      `warnings: ${JSON.stringify(startup.warnings ?? [])}`,
  );
  ctx.log(
    `FF_APP.startup: stopAfterElapsedMinutes=${startup.stopAfterElapsedMinutes} backend=${startup.deviceInfo?.backend} ` +
      `model=${startup.deviceInfo?.model} overridesSource=${startup.overridesSource}`,
  );
  return startup;
}

// ---------------------------------------------------------------------------------------------------------------------
// Back office reads

function recordedMs(record: WireRecord): number {
  return Date.parse(record.recorded_at);
}

function byRecordedAt(a: WireRecord, b: WireRecord): number {
  return recordedMs(a) - recordedMs(b);
}

/** Records this scenario uploaded (first receipt of each uuid), sorted by `recorded_at`. */
function records(s: Session, event?: RecordEvent | readonly RecordEvent[]): WireRecord[] {
  return s.office
    .records({ since: s.since, unique: true, event })
    .map((stored) => stored.record)
    .sort(byRecordedAt);
}

/** Every upload of this scenario (each receipt, with its request's params and headers). */
function uploads(s: Session): StoredRecord[] {
  return s.office.records({ since: s.since });
}

/** Waits until the back office has a record matching [predicate]; the error lists what arrived instead. */
async function waitForRecord(
  s: Session,
  what: string,
  predicate: (record: WireRecord) => boolean,
  timeoutMs: number,
): Promise<WireRecord> {
  return poll(s.ctx, `${what} on the back office`, () => records(s).find(predicate), timeoutMs, () =>
    `received: ${describeRecords(records(s))}`,
  );
}

/** The `tracking_start` (reason start) of the app's auto start (a priority upload: it arrives at once). */
async function waitForAutoStart(s: Session, afterMs = 0): Promise<WireRecord> {
  return waitForRecord(
    s,
    'tracking_start (reason start) of the auto start',
    (r) => r.event === 'tracking_start' && r.reason === 'start' && recordedMs(r) > afterMs,
    60_000,
  );
}

/** Waits until the plugin's foreground service is gone (after a stop). */
async function waitForTrackingServiceStopped(ctx: ScenarioContext, why: string): Promise<void> {
  await poll(
    ctx,
    `the tracking foreground service to stop after ${why}`,
    async () => !(await ctx.app.isForegroundServiceRunning(SERVICES.tracking)),
    30_000,
  );
}

/**
 * Waits for a heartbeat that PremiseMonitor audited: its `record` entry matching [where], then the `heartbeat` event
 * entry carrying the same uuid. Resolves with the audited `record` entry.
 */
async function waitForAuditedHeartbeat(
  s: Session,
  what: string,
  where: (entry: PremiseAuditEntry, record: WireRecord) => boolean,
  timeoutMs: number,
): Promise<PremiseAuditEntry> {
  const entry = await waitForPremiseEntry(
    s,
    `the record entry of ${what}`,
    (e) => e.kind === 'record' && e.record?.event === 'heartbeat' && where(e, e.record),
    timeoutMs,
  );
  const uuid = recordOf(entry).uuid;
  await waitForPremiseEntry(
    s,
    `the heartbeat event entry of ${what} (${uuid})`,
    (e) => e.kind === 'event' && e.name === 'heartbeat' && eventUuid(e) === uuid,
    60_000,
  );
  return entry;
}

/** PremiseMonitor audit entries this scenario uploaded, in arrival order (= creation order), one per entry id. */
function premiseEntries(s: Session): PremiseAuditEntry[] {
  const seen = new Set<string>();
  const entries: PremiseAuditEntry[] = [];
  for (const stored of s.office.premiseRecords({ since: s.since })) {
    if (seen.has(stored.entry.id)) continue;
    seen.add(stored.entry.id);
    entries.push(stored.entry);
  }
  return entries;
}

async function waitForPremiseEntry(
  s: Session,
  what: string,
  predicate: (entry: PremiseAuditEntry) => boolean,
  timeoutMs: number,
): Promise<PremiseAuditEntry> {
  return poll(s.ctx, `${what} on /premise-audit`, () => premiseEntries(s).find(predicate), timeoutMs, () =>
    `received: ${describeEntries(premiseEntries(s))}`,
  );
}

/** The wire record of a `record` entry. */
function recordOf(entry: PremiseAuditEntry): WireRecord {
  if (!entry.record) throw new Error(`audit entry ${entry.id} (${entry.kind}) has no record`);
  return entry.record;
}

/** uuid of the record an `event` entry carries: `location` events carry the record, the others `{location: record}`. */
function eventUuid(entry: PremiseAuditEntry): string | undefined {
  const payload = entry.payload;
  if (!isObject(payload)) return undefined;
  if (typeof payload['uuid'] === 'string') return payload['uuid'];
  const location = payload['location'];
  if (isObject(location) && typeof location['uuid'] === 'string') return location['uuid'];
  return undefined;
}

/**
 * The first movement at or after [sinceMs] (device clock): the first `motionchange` with is_moving true, and the first
 * `motionchange` with is_moving false after it. The auto start's initial motionchange (is_moving false) can be created
 * a few seconds after a scenario starts moving, so "is_moving false after sinceMs" alone could match that one.
 */
function movementAfter(
  sorted: readonly WireRecord[],
  sinceMs: number,
): { begin: WireRecord; end: WireRecord | undefined } | undefined {
  const begin = sorted.find((r) => r.event === 'motionchange' && r.is_moving && recordedMs(r) >= sinceMs);
  if (!begin) return undefined;
  const end = sorted.find((r) => r.event === 'motionchange' && !r.is_moving && recordedMs(r) > recordedMs(begin));
  return { begin, end };
}

function isPremiseTransition(record: WireRecord | null | undefined, action: 'ENTER' | 'EXIT'): boolean {
  return (
    record?.event === 'geofence' &&
    record.geofence?.identifier === PREMISE_GEOFENCE_ID &&
    record.geofence.action === action
  );
}

/**
 * Asserts that PremiseMonitor audited every record of [expected] (a `record` entry with its uuid) and every event
 * that carries it (EVENTS_OF_RECORD), and that each record entry came before its events (§2 delivery order).
 */
function assertCompanionSawAll(expected: readonly WireRecord[], entries: readonly PremiseAuditEntry[], label: string): void {
  const recordIndex = new Map<string, number>();
  const eventIndex = new Map<string, number>();
  entries.forEach((entry, index) => {
    if (entry.kind === 'record' && entry.record && !recordIndex.has(entry.record.uuid)) {
      recordIndex.set(entry.record.uuid, index);
    }
    const uuid = entry.kind === 'event' ? eventUuid(entry) : undefined;
    if (uuid && entry.name && !eventIndex.has(`${entry.name} ${uuid}`)) eventIndex.set(`${entry.name} ${uuid}`, index);
  });
  const problems: string[] = [];
  for (const record of expected) {
    const recordAt = recordIndex.get(record.uuid);
    if (recordAt === undefined) problems.push(`no 'record' entry for ${describeRecord(record)} ${record.uuid}`);
    for (const name of EVENTS_OF_RECORD[record.event] ?? []) {
      const eventAt = eventIndex.get(`${name} ${record.uuid}`);
      if (eventAt === undefined) problems.push(`no '${name}' event entry for ${describeRecord(record)} ${record.uuid}`);
      else if (recordAt !== undefined && eventAt < recordAt) {
        problems.push(`the '${name}' event entry of ${record.uuid} came before its record entry`);
      }
    }
  }
  if (problems.length > 0) {
    throw new Error(
      `${label}: PremiseMonitor's audit is incomplete (${problems.length} problem(s) for ${expected.length} records): ` +
        `${problems.slice(0, 15).join('; ')}. Audit entries: ${describeEntries(entries)}`,
    );
  }
}

/** Audit entries about any of [uuids]: their record entries and the events that carry them. */
function entriesAbout(entries: readonly PremiseAuditEntry[], uuids: readonly string[]): PremiseAuditEntry[] {
  return entries.filter(
    (e) =>
      (e.kind === 'record' && e.record !== undefined && uuids.includes(e.record.uuid)) ||
      (e.kind === 'event' && uuids.includes(eventUuid(e) ?? '')),
  );
}

// ---------------------------------------------------------------------------------------------------------------------
// Premise flow (PremiseMonitor, §10)

/** Waits for the `monitoring_started` entry of the page's `startMonitoring` (§10 step 8) and checks the status. */
async function waitForMonitoringStarted(s: Session): Promise<void> {
  await waitForPremiseEntry(
    s,
    `PremiseMonitor 'monitoring_started' for '${PREMISE.id}' (field-force startup step 8)`,
    (e) => e.kind === 'premise' && e.type === 'monitoring_started' && e.premise_id === PREMISE.id,
    60_000,
  );
  const status = await s.ctx.commands.premiseStatus();
  expectEqual(status.monitoring, true, 'premise.status monitoring after startMonitoring');
  expectEqual(status.premise?.id, PREMISE.id, 'premise.status premise.id');
  expectEqual(status.auditUrl, s.office.url('/premise-audit'), 'premise.status auditUrl');
}

/**
 * Drives from 400 m east of HQ into the premise centre with GPS on (changePace(true)) and waits for the ENTER:
 * the `enter` entry, the geofence record entry it refers to, and PremiseMonitorService in the foreground.
 */
async function enterPremise(s: Session): Promise<{ enter: PremiseAuditEntry; enterRecord: WireRecord }> {
  const { ctx } = s;
  await startMoving(ctx);
  // Google Play services evaluates a new geofence against its last known position. The page registers the premise at
  // launch, and at that moment the last known position can still be where the previous scenario ended: inside the
  // premise (F-11 after F-10 on the first CI run), so the geofence started "inside" and no ENTER came. Hold the start
  // point with GPS on so Play services sees fresh fixes outside, then register the premise again (the stop and start
  // entries are PremiseMonitor's own audit), then drive in (the same order as the plugin's geofence scenario P-P10).
  await holdPosition(ctx, PLACES.hqEast400m);
  await sleep(OUTSIDE_SETTLE_MS, ctx.signal);
  await ctx.commands.premiseStop();
  await ctx.commands.premiseStart(PREMISE, s.office.url('/premise-audit'));
  await drive(ctx, [PLACES.hqEast400m, PLACES.hq], APPROACH_SPEED_MPS);
  const enter = await waitForPremiseEntry(
    s,
    `PremiseMonitor 'enter' for '${PREMISE.id}' after driving into the premise`,
    (e) => e.kind === 'premise' && e.type === 'enter' && e.premise_id === PREMISE.id,
    3 * 60_000,
  );
  const recordEntry = await waitForPremiseEntry(
    s,
    `the 'record' entry of the ENTER geofence record of '${PREMISE_GEOFENCE_ID}'`,
    (e) => e.kind === 'record' && isPremiseTransition(e.record, 'ENTER'),
    30_000,
  );
  const enterRecord = recordOf(recordEntry);
  expectEqual(enter.location?.uuid, enterRecord.uuid, "the 'enter' entry's location (the triggering geofence record)");
  await poll(
    ctx,
    'PremiseMonitorService to run in the foreground after the ENTER',
    () => ctx.app.isForegroundServiceRunning(SERVICES.premise),
    60_000,
    () => `audit: ${describeEntries(premiseEntries(s))}`,
  );
  const refused = premiseEntries(s).find((e) => e.kind === 'premise' && e.type === 'service_start_failed');
  check(!refused, `PremiseMonitor could not start its service after the ENTER: ${refused?.detail}`);
  ctx.log(`entered the premise: geofence record ${enterRecord.uuid} at ${enterRecord.recorded_at}`);
  return { enter, enterRecord };
}

// ---------------------------------------------------------------------------------------------------------------------
// Position and movement (emulator GNSS)

/** Sets the emulator's GNSS position, three times one second apart, so a GNSS engine that starts late still gets it. */
async function holdPosition(ctx: ScenarioContext, place: LatLon): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await ctx.adb.geoFix(place.lat, place.lon);
    if (i < 2) await sleep(1000, ctx.signal);
  }
}

/** Replays [points] at [speedMps] (one fix per second), then holds the last point. */
async function drive(ctx: ScenarioContext, points: readonly LatLon[], speedMps: number): Promise<void> {
  await ctx.adb.playRoute(points, { speedMps, intervalMs: 1000, signal: ctx.signal });
  await holdPosition(ctx, points[points.length - 1]);
}

/** `changePace(true)`: the engine leaves STATIONARY and requests GPS again (motion detection itself is P-H03). */
async function startMoving(ctx: ScenarioContext): Promise<void> {
  await ctx.commands.changePace(true);
  await poll(ctx, 'state.isMoving after changePace(true)', async () => (await ctx.commands.state()).isMoving, 30_000);
}

/** Repeats a geo fix every [periodMs] until stopped; errors are ignored (the console may be busy during a reboot). */
function startPositionPump(ctx: ScenarioContext, place: LatLon, periodMs = 2000): { stop: () => Promise<void> } {
  let running = true;
  const done = (async () => {
    while (running && !ctx.signal.aborted) {
      await ctx.adb.geoFix(place.lat, place.lon).catch(() => undefined);
      await sleep(periodMs, ctx.signal).catch(() => undefined);
    }
  })();
  return {
    stop: async () => {
      running = false;
      await done;
    },
  };
}

/** Reboots the emulator while keeping its GNSS position at [place], then wakes and unlocks the screen. */
async function rebootHolding(ctx: ScenarioContext, place: LatLon): Promise<void> {
  const pump = startPositionPump(ctx, place);
  try {
    ctx.log('rebooting the emulator');
    await ctx.adb.reboot({ timeoutMs: 8 * 60_000 });
    await ctx.adb.screenOn();
    await holdPosition(ctx, place);
    ctx.log('emulator booted');
  } finally {
    await pump.stop();
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Device clock

/** Device wall clock minus host wall clock, ms. */
async function measureClockOffset(adb: Adb): Promise<number> {
  const before = Date.now();
  const device = await adb.deviceTime();
  const after = Date.now();
  return Math.round(device - (before + after) / 2);
}

/** The device's current UTC offset in minutes (`date +%z`). */
async function deviceUtcOffsetMinutes(adb: Adb): Promise<number> {
  const out = (await adb.shell('date +%z')).trim();
  const match = /^([+-])(\d{2}):?(\d{2})$/.exec(out);
  if (!match) throw new Error(`cannot read the device's UTC offset: 'date +%z' printed '${out}'`);
  const minutes = Number(match[2]) * 60 + Number(match[3]);
  return match[1] === '-' ? -minutes : minutes;
}

/** Epoch ms of the next device-local HH:MM after [nowMs]: later today, or tomorrow (the app's rule, §10 step 3). */
function nextLocalTime(nowMs: number, offsetMin: number, hours: number, minutes: number): number {
  const local = new Date(nowMs + offsetMin * 60_000);
  let candidate =
    Date.UTC(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate(), hours, minutes) - offsetMin * 60_000;
  if (candidate <= nowMs) candidate += 24 * 60 * 60_000;
  return candidate;
}

/** The app's `minutesUntil`: ceil((target − now) / 1 min). */
function minutesUntil(targetMs: number, nowMs: number): number {
  return Math.ceil((targetMs - nowMs) / 60_000);
}

/**
 * The values `minutesUntil(stopFor(t), t)` takes for a device time t in [beforeMs, afterMs] (the app computes its
 * minutes somewhere in that interval), as inclusive ranges. [stopFor] gives the stop time the app aims at from t:
 * a fixed time, or the next occurrence of HH:MM, which moves to the next day when the interval passes it.
 */
function acceptableMinutes(beforeMs: number, afterMs: number, stopFor: (t: number) => number): [number, number][] {
  const first = stopFor(beforeMs);
  const last = stopFor(afterMs);
  if (first === last) return [[minutesUntil(first, afterMs), minutesUntil(first, beforeMs)]];
  // The launch passed the stop time: before it the minutes count down to 1, after it they start from a full day.
  return [
    [1, minutesUntil(first, beforeMs)],
    [minutesUntil(last, afterMs), minutesUntil(last, first)],
  ];
}

/** Asserts that [minutes] is one of [ranges]; [what] describes the expectation for the message. */
function assertMinutesIn(minutes: number, ranges: [number, number][], what: string): void {
  check(
    Number.isInteger(minutes) && ranges.some(([low, high]) => minutes >= low && minutes <= high),
    `FF_APP.startup.stopAfterElapsedMinutes: expected ${what} = ` +
      `${ranges.map(([low, high]) => (low === high ? `${low}` : `${low}..${high}`)).join(' or ')}, received ${minutes}`,
  );
}

function pad2(value: number): string {
  return String(value).padStart(2, '0');
}

/** 'HH:MM' of [epochMs] on the device-local clock. */
function localHhMm(epochMs: number, offsetMin: number): string {
  const local = new Date(epochMs + offsetMin * 60_000);
  return `${pad2(local.getUTCHours())}:${pad2(local.getUTCMinutes())}`;
}

/** Device-local date and time of [epochMs] for messages. */
function localText(epochMs: number, offsetMin: number): string {
  const sign = offsetMin < 0 ? '-' : '+';
  const abs = Math.abs(offsetMin);
  const local = new Date(epochMs + offsetMin * 60_000).toISOString().slice(0, 19).replace('T', ' ');
  return `${local} (UTC${sign}${pad2(Math.floor(abs / 60))}:${pad2(abs % 60)})`;
}

/**
 * A stop time [minutes] from now on the device-local clock, on a whole minute. When the current minute is more than
 * 30 s old it first waits for the next minute, so the app (which starts some seconds later) computes [minutes].
 */
async function planStopAt(ctx: ScenarioContext, minutes: number): Promise<StopPlan> {
  const offsetMin = await deviceUtcOffsetMinutes(ctx.adb);
  let now = await ctx.adb.deviceTime();
  // UTC offsets are whole minutes, so the device-local seconds equal the UTC seconds.
  if (now % 60_000 > 30_000) {
    await sleep(60_000 - (now % 60_000) + 1000, ctx.signal);
    now = await ctx.adb.deviceTime();
  }
  const targetMs = now - (now % 60_000) + minutes * 60_000;
  return { stopAt: localHhMm(targetMs, offsetMin), targetMs, offsetMin };
}

/**
 * Launches the app with the `stopAt` override of [plan] already written and checks that the app computed
 * `stopAfterElapsedMinutes` from the device clock (any value the clock allowed between launch and startup end).
 */
async function launchWithStopAt(s: Session, plan: StopPlan): Promise<number> {
  const before = await s.ctx.adb.deviceTime();
  const startup = await launchApp(s);
  const after = await s.ctx.adb.deviceTime();
  const minutes = startup.stopAfterElapsedMinutes;
  assertMinutesIn(
    minutes,
    acceptableMinutes(before, after, () => plan.targetMs),
    `ceil((stopAt ${plan.stopAt} − device time) / 1 min) for a startup between ${localText(before, plan.offsetMin)} ` +
      `and ${localText(after, plan.offsetMin)}`,
  );
  const state = await s.ctx.commands.state();
  expectEqual(state.config.geolocation?.stopAfterElapsedMinutes, minutes, 'plugin config geolocation.stopAfterElapsedMinutes');
  s.ctx.log(`stopAt ${plan.stopAt} local -> stopAfterElapsedMinutes ${minutes}`);
  return minutes;
}

/** Asserts that [stop] came [minutes] after the session's [start] (and not before [notBeforeMs], device clock). */
function assertStopTiming(stop: WireRecord, start: WireRecord, minutes: number, notBeforeMs?: number): void {
  const plannedMs = recordedMs(start) + minutes * 60_000;
  const elapsedS = (recordedMs(stop) - recordedMs(start)) / 1000;
  assertions.withinWindow(
    stop.recorded_at,
    { from: plannedMs - STOP_EARLY_S * 1000, to: plannedMs + STOP_LATE_S * 1000 },
    `tracking_stop (stop_after_elapsed) ${elapsedS.toFixed(1)} s after the session's tracking_start at ` +
      `${start.recorded_at} (planned ${minutes} min, at most ${STOP_EARLY_S} s earlier or ${STOP_LATE_S} s later)`,
  );
  if (notBeforeMs !== undefined) {
    check(
      recordedMs(stop) >= notBeforeMs,
      `tracking_stop at ${stop.recorded_at} is before the stop time ${new Date(notBeforeMs).toISOString()}: ` +
        'the app must round the minutes up, so tracking never stops before the stop time',
    );
  }
}

/** Sets the device wall clock (root); the cleanup restores the real time and automatic time. */
async function setDeviceClock(ctx: ScenarioContext, cleanup: Cleanup, epochMs: number): Promise<void> {
  const offsetMs = await measureClockOffset(ctx.adb);
  cleanup.add('restore the device clock and automatic time', async () => {
    await ctx.adb.setTime(Date.now() + offsetMs);
    await ctx.adb.setAutoTime(true);
  });
  await ctx.adb.setAutoTime(false);
  await ctx.adb.setTime(epochMs);
}

// ---------------------------------------------------------------------------------------------------------------------
// Other device helpers

/** Simulated battery: unplugged, discharging, [level] %. The cleanup resets it to the real values. */
async function setBattery(ctx: ScenarioContext, cleanup: Cleanup, level: number): Promise<void> {
  cleanup.add('reset the battery simulation', () => ctx.adb.batteryReset());
  await ctx.adb.batteryUnplug();
  await ctx.adb.dumpsys('battery', ['set', 'status', String(BATTERY_STATUS_DISCHARGING)]);
  await ctx.adb.batterySetLevel(level);
}

async function requirePid(ctx: ScenarioContext): Promise<number> {
  const pid = await ctx.app.pid();
  if (pid === null) throw new Error(`${ctx.appId} has no process`);
  return pid;
}

/** Waits until the app runs in a process other than [oldPid] (restored by START_STICKY or the heartbeat alarm). */
async function waitForNewPid(ctx: ScenarioContext, oldPid: number, timeoutMs: number): Promise<number> {
  return poll(
    ctx,
    `the app process to come back with a new pid after kill -9 of pid ${oldPid} (START_STICKY or the heartbeat alarm)`,
    async () => {
      const pid = await ctx.app.pid();
      return pid !== null && pid !== oldPid ? pid : undefined;
    },
    timeoutMs,
  );
}

/**
 * True while a task still holds an activity of the app: a `Hist #n: ActivityRecord{… <appId>/…}` line of
 * `dumpsys activity activities` (fields such as `mLastPausedActivity` are not counted).
 */
async function activityRecordExists(ctx: ScenarioContext): Promise<boolean> {
  const dump = await ctx.adb.dumpsys('activity', ['activities']);
  return new RegExp(`Hist\\s*#\\d+:\\s*ActivityRecord\\{[^}]*\\s${escapeRegExp(ctx.appId)}/`).test(dump);
}

/** The app's Linux uid (`cmd package list packages -U`), or null. */
async function appUid(ctx: ScenarioContext): Promise<number | null> {
  const out = await ctx.adb.shell(`cmd package list packages -U ${ctx.appId}`);
  const line = out.split(/\r?\n/).find((l) => l.trim().startsWith(`package:${ctx.appId} `));
  const match = /uid:(\d+)/.exec(line ?? '');
  return match ? Number(match[1]) : null;
}

// ---------------------------------------------------------------------------------------------------------------------
// GPS use from `dumpsys location` (same idea as P-H02)

interface GpsRequestScan {
  /** a `gps provider:` section was found in the current-state part of the dump */
  sectionFound: boolean;
  /** lines of that section that attribute an active request to the app (package name, or uid in a WorkSource) */
  lines: string[];
}

/**
 * Finds GPS requests attributed to [appId] in `dumpsys location` (Android 12+ layout: one `<name> provider:` section per
 * provider with its registrations and current request). With Google Play services the platform `gps` provider is
 * requested by `com.google.android.gms` with a WorkSource that names the client apps, so both the package name and the
 * uid are searched. `last location` lines, inactive registrations, `ProviderRequest[OFF]` and the history sections
 * after the providers are ignored.
 */
function scanGpsRequests(dump: string, appId: string, uid: number | null): GpsRequestScan {
  const uidPattern = uid === null ? null : new RegExp(`WorkSource\\{[^}]*\\b${uid}\\b|\\b${uid}/`);
  const lines: string[] = [];
  let sectionFound = false;
  let gpsIndent = -1;
  for (const raw of dump.split(/\r?\n/)) {
    if (/^\s{0,4}(Historical|Event Log|Location Events)/i.test(raw)) break;
    const indent = raw.length - raw.trimStart().length;
    const header = /^\s*([\w.-]+) provider\b[^:]*:\s*$/.exec(raw);
    if (header) {
      gpsIndent = header[1] === 'gps' ? indent : -1;
      if (gpsIndent >= 0) sectionFound = true;
      continue;
    }
    if (gpsIndent < 0 || raw.trim() === '') continue;
    if (indent <= gpsIndent) {
      gpsIndent = -1;
      continue;
    }
    if (/last (available )?location|Location\[|inactive|ProviderRequest\[OFF\]/i.test(raw)) continue;
    if (raw.includes(appId) || (uidPattern !== null && uidPattern.test(raw))) lines.push(raw.trim());
  }
  return { sectionFound, lines };
}

/** Fails (and saves the dump as an artifact) if the app has an active GPS request now. */
async function assertNoGps(ctx: ScenarioContext, uid: number | null, when: string): Promise<void> {
  const dump = await ctx.adb.dumpsys('location');
  const scan = scanGpsRequests(dump, ctx.appId, uid);
  if (scan.sectionFound && scan.lines.length === 0) return;
  const file = `dumpsys-location-${when.replace(/[^A-Za-z0-9]+/g, '-')}.txt`;
  await ctx.artifacts.writeText(file, dump).catch(() => '');
  throw new Error(
    scan.sectionFound
      ? `GPS is still requested for ${ctx.appId} (uid ${uid}) ${when}: ${scan.lines.join(' | ')} (dump: ${file})`
      : `'dumpsys location' has no 'gps provider:' section ${when}; the GPS check cannot run (dump: ${file})`,
  );
}

// ---------------------------------------------------------------------------------------------------------------------
// Server-side views

type OnlineStatus = { online: boolean; why: string };

/**
 * What a back office concludes from the records it has at device time [atMs]: online while the newest record is at
 * most MAX_SILENCE_S old and is not a `tracking_stop`.
 */
function trackingStatusAt(sorted: readonly WireRecord[], atMs: number): OnlineStatus {
  const past = sorted.filter((r) => recordedMs(r) <= atMs);
  const last = past[past.length - 1];
  if (!last) return { online: false, why: 'no records' };
  if (last.event === 'tracking_stop') return { online: false, why: `tracking stopped (${last.reason})` };
  const silenceS = Math.round((atMs - recordedMs(last)) / 1000);
  if (silenceS > MAX_SILENCE_S) return { online: false, why: `silent for ${silenceS} s` };
  return { online: true, why: `last record ${last.event} ${silenceS} s ago` };
}

/** Upload delay of a stored record: host receive time minus its `recorded_at` on the host clock, s. */
function uploadDelayS(s: Session, stored: StoredRecord): number {
  return (stored.receivedAt - (recordedMs(stored.record) - s.clockOffsetMs)) / 1000;
}

// ---------------------------------------------------------------------------------------------------------------------
// Small utilities

/** waitUntil with the scenario's abort signal; on timeout the error also carries [detail]() (evaluated then). */
async function poll<T>(
  ctx: ScenarioContext,
  what: string,
  probe: () => T | undefined | null | false | Promise<T | undefined | null | false>,
  timeoutMs: number,
  detail?: () => string,
): Promise<T> {
  try {
    return await waitUntil(probe, { timeoutMs, intervalMs: 1000, message: what, signal: ctx.signal });
  } catch (error) {
    let extra = '';
    try {
      extra = detail ? `; ${detail()}` : '';
    } catch {
      extra = '';
    }
    const reason = ctx.signal.aborted ? 'the scenario timed out' : `timed out after ${Math.round(timeoutMs / 1000)} s`;
    throw new Error(`${reason} waiting for ${what}${extra}`, { cause: error });
  }
}

function check(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function expectEqual(actual: unknown, expected: unknown, label: string): void {
  if (!Object.is(actual, expected)) {
    throw new Error(`${label}: expected ${JSON.stringify(expected)}, received ${JSON.stringify(actual)}`);
  }
}

function isObject(value: unknown): value is Record<string, any> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** `a.b.c` of [value]. */
function valueAt(value: unknown, path: string): unknown {
  let current: unknown = value;
  for (const key of path.split('.')) {
    if (!isObject(current)) return undefined;
    current = current[key];
  }
  return current;
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function describeRecord(r: WireRecord): string {
  let text = r.event;
  if (r.reason) text += `(${r.reason})`;
  if (r.event === 'motionchange') text += `(moving=${r.is_moving})`;
  if (r.geofence) text += `(${r.geofence.identifier} ${r.geofence.action})`;
  return text;
}

/** 'HH:MM:SS event(detail), …' of the last [max] records. */
function describeRecords(list: readonly WireRecord[], max = 25): string {
  if (list.length === 0) return 'no records';
  const shown = list.slice(-max).map((r) => `${r.recorded_at.slice(11, 19)} ${describeRecord(r)}`);
  return `${list.length} record(s)${list.length > max ? `, last ${max}` : ''}: ${shown.join(', ')}`;
}

function describeEntries(list: readonly PremiseAuditEntry[], max = 25): string {
  if (list.length === 0) return 'no audit entries';
  const shown = list.slice(-max).map((e) => {
    const what = e.kind === 'premise' ? e.type : e.kind === 'record' ? (e.record ? describeRecord(e.record) : '?') : e.name;
    return `${(e.at ?? '').slice(11, 19)} ${e.kind}:${what} pid=${e.pid} js=${e.js}`;
  });
  return `${list.length} entr${list.length === 1 ? 'y' : 'ies'}${list.length > max ? `, last ${max}` : ''}: ${shown.join(', ')}`;
}

/** Input of `insertLocation` for a fix at [place] with [accuracy] meters. */
function fixAt(place: LatLon, accuracy: number, note: string): Record<string, unknown> {
  return { coords: { latitude: place.lat, longitude: place.lon, accuracy }, extras: { e2e: note } };
}

// ---------------------------------------------------------------------------------------------------------------------
// F-01 … F-06: tracking for the back office

scenario(
  'F-01',
  'app launch auto-starts tracking: tracking_start + motionchange, device details in params, battery',
  (ctx) =>
    withCleanup(ctx, async (cleanup) => {
      const s = await setUp(ctx, cleanup, { at: START_PLACE });
      await setBattery(ctx, cleanup, 57);
      const offsetMin = await deviceUtcOffsetMinutes(ctx.adb);
      const before = await ctx.adb.deviceTime();
      const startup = await launchApp(s);
      const after = await ctx.adb.deviceTime();

      // The launch alone started tracking (§10 step 7), in the foreground service.
      const state = await ctx.commands.state();
      expectEqual(state.enabled, true, 'state.enabled after the app launch (auto start)');
      expectEqual(state.trackingMode, 'location', 'state.trackingMode after the auto start');
      check(
        await ctx.app.isForegroundServiceRunning(SERVICES.tracking),
        'the tracking foreground service is not running after the auto start',
      );

      // Without a stopAt override the stop is the next 02:00 local, computed from the device clock (§10 step 3).
      // (The UTC offset is read once; on a daylight-saving changeover night the expected value is 60 minutes off.)
      assertMinutesIn(
        startup.stopAfterElapsedMinutes,
        acceptableMinutes(before, after, (t) => nextLocalTime(t, offsetMin, 2, 0)),
        `the minutes until the next 02:00 local (startup between ${localText(before, offsetMin)} and ` +
          `${localText(after, offsetMin)})`,
      );
      expectEqual(
        state.config.geolocation?.stopAfterElapsedMinutes,
        startup.stopAfterElapsedMinutes,
        'plugin config geolocation.stopAfterElapsedMinutes',
      );

      // The field-force preset (§10) with the test overrides.
      const presetChecks: [string, unknown][] = [
        ['geolocation.desiredAccuracy', 'high'],
        ['geolocation.distanceFilter', 20],
        ['geolocation.stationaryRadius', 50],
        ['geolocation.stopTimeout', STOP_TIMEOUT_MIN],
        ['geolocation.filter.trackingAccuracyThreshold', 50],
        ['heartbeat.enabled', true],
        ['heartbeat.minInterval', TEST_HEARTBEAT.minInterval],
        ['heartbeat.maxInterval', TEST_HEARTBEAT.maxInterval],
        ['http.url', s.office.url('/locations')],
        ['http.autoSync', true],
        ['http.syncInterval', TEST_SYNC_INTERVAL_S],
        ['http.batchSync', true],
        ['http.maxBatchSize', 100],
        ['http.params.worker_id', 'field-force-example'],
        ['http.authorization.strategy', 'JWT'],
        ['http.authorization.refreshUrl', s.office.url('/auth/refresh')],
        ['app.stopOnTerminate', false],
        ['app.startOnBoot', true],
        ['notification.title', 'Field Force'],
        ['notification.text', 'Shift tracking is on'],
        ['logger.logLevel', 'debug'],
        ['locationProvider', 'auto'],
      ];
      for (const [path, expected] of presetChecks) expectEqual(valueAt(state.config, path), expected, `plugin config ${path}`);

      // Device details: getDeviceInfo() matches the device, and the preset sends it with every upload.
      const info = startup.deviceInfo;
      check(isObject(info), `FF_APP.startup.deviceInfo is missing: ${JSON.stringify(startup)}`);
      expectEqual(info.platform, 'android', 'deviceInfo.platform');
      expectEqual(info.model, await ctx.adb.getprop('ro.product.model'), 'deviceInfo.model vs ro.product.model');
      expectEqual(
        info.manufacturer,
        await ctx.adb.getprop('ro.product.manufacturer'),
        'deviceInfo.manufacturer vs ro.product.manufacturer',
      );
      expectEqual(info.sdkInt, ctx.device.api, 'deviceInfo.sdkInt vs ro.build.version.sdk');
      expectEqual(info.gmsAvailable, true, 'deviceInfo.gmsAvailable (Google APIs image)');
      expectEqual(info.backend, 'gms', 'deviceInfo.backend (field-force app is gms-only, locationProvider auto)');
      for (const key of DEVICE_PARAM_KEYS) {
        expectEqual(valueAt(state.config, `http.params.device.${key}`), info[key], `plugin config http.params.device.${key}`);
      }

      const start = await waitForAutoStart(s);

      // The battery changes while tracking runs: later records must carry the new level, not a cached one.
      const changedAt = await ctx.adb.deviceTime();
      await ctx.adb.batterySetLevel(42);

      const motion = await waitForRecord(
        s,
        'the initial motionchange (is_moving false)',
        (r) => r.event === 'motionchange' && !r.is_moving,
        (TEST_SYNC_INTERVAL_S + TOLERANCE_S + 60) * 1000,
      );
      const heartbeat = await waitForRecord(
        s,
        'a heartbeat after the initial motionchange',
        (r) => r.event === 'heartbeat' && recordedMs(r) > recordedMs(motion),
        HEARTBEAT_WAIT_MS,
      );
      const all = records(s);
      assertions.inOrder(all, [{ event: 'tracking_start', reason: 'start' }, { event: 'motionchange', is_moving: false }, 'heartbeat']);
      check(recordedMs(start) <= recordedMs(motion), `tracking_start ${start.recorded_at} after motionchange ${motion.recorded_at}`);

      check(motion.coords, `the initial motionchange has no coords: ${JSON.stringify(motion)}`);
      const offsetM = assertions.haversineMeters({ lat: motion.coords.latitude, lon: motion.coords.longitude }, START_PLACE);
      check(offsetM <= 100, `the initial motionchange is ${offsetM.toFixed(0)} m from the emulator position (expected <= 100 m)`);

      // Every upload carries the device details and the JWT of the preset.
      const stored = uploads(s);
      check(stored.length >= 3, `expected at least 3 uploaded records, received ${stored.length}`);
      for (const upload of stored) {
        const device = upload.params['device'];
        const label = `request #${upload.requestId} (${describeRecord(upload.record)})`;
        check(isObject(device), `${label}: params.device is missing; params: ${JSON.stringify(upload.params)}`);
        for (const key of DEVICE_PARAM_KEYS) expectEqual(device[key], info[key], `${label} params.device.${key}`);
        expectEqual(upload.params['worker_id'], 'field-force-example', `${label} params.worker_id`);
        check(
          typeof upload.authorization === 'string' && upload.authorization.startsWith('Bearer '),
          `${label}: expected an 'Authorization: Bearer …' header (JWT preset), received ${JSON.stringify(upload.authorization)}`,
        );
      }

      // Battery in every record: 57 % until the change, 42 % after it, never charging (the battery is unplugged).
      for (const r of all) {
        const label = `${describeRecord(r)} ${r.uuid}`;
        check(
          isObject(r.battery) && typeof r.battery.level === 'number' && typeof r.battery.is_charging === 'boolean',
          `${label}: battery missing or malformed: ${JSON.stringify(r.battery)}`,
        );
        expectEqual(r.battery.is_charging, false, `${label} battery.is_charging (the battery is unplugged and discharging)`);
        const t = recordedMs(r);
        const expected = t < changedAt - 2000 ? [0.57] : t > changedAt + 2000 ? [0.42] : [0.57, 0.42];
        check(
          expected.some((level) => Math.abs(r.battery.level - level) < 0.006),
          `${label} recorded at ${r.recorded_at}: battery.level ${r.battery.level}, expected ${expected.join(' or ')} ` +
            `(level changed to 0.42 at device time ${new Date(changedAt).toISOString()})`,
        );
        expectEqual(r.backend, 'gms', `${label} backend`);
      }
      check(recordedMs(heartbeat) > changedAt + 2000, `the heartbeat ${heartbeat.recorded_at} is not after the battery change`);
      await ctx.crashes.assertNoFgsDidNotStartInTime();
    }),
  { requires: { gms: true } },
);

scenario(
  'F-02',
  '02:00 stop: stop time computed from the device clock, tracking_stop reason stop_after_elapsed',
  (ctx) =>
    withCleanup(ctx, async (cleanup) => {
      const s = await setUp(ctx, cleanup, { at: START_PLACE });

      // Part 1 (real clock): stopAt = device-local now + 2 min -> the stop comes at that time.
      const plan = await planStopAt(ctx, 2);
      await writeTestFile(ctx, OVERRIDES_FILE, overridesFor(s.office, { stopAt: plan.stopAt }));
      const minutes = await launchWithStopAt(s, plan);
      const start = await waitForAutoStart(s);
      const stop = await waitForRecord(
        s,
        `tracking_stop (stop_after_elapsed) at ${plan.stopAt} local`,
        (r) => r.event === 'tracking_stop' && r.reason === 'stop_after_elapsed',
        (minutes * 60 + STOP_LATE_S + 60) * 1000,
      );
      assertStopTiming(stop, start, minutes, plan.targetMs);
      expectEqual(records(s, 'tracking_stop').length, 1, 'number of tracking_stop records');
      await waitForTrackingServiceStopped(ctx, 'the elapsed stop');
      expectEqual((await ctx.commands.state()).enabled, false, 'state.enabled after the elapsed stop');
      await ctx.crashes.assertNoFgsDidNotStartInTime();

      // Part 2 (root, device clock at 01:58 local, no override = the production default 02:00): 2 minutes.
      await writeTestFile(ctx, OVERRIDES_FILE, overridesFor(s.office));
      await ctx.adb.forceStop(ctx.appId);
      const offsetMin = await deviceUtcOffsetMinutes(ctx.adb);
      const at0158 = nextLocalTime(await ctx.adb.deviceTime(), offsetMin, 1, 58);
      await setDeviceClock(ctx, cleanup, at0158);
      expectEqual((await ctx.adb.shell('date +%H:%M')).trim(), '01:58', "device-local time ('date +%H:%M') after setting the clock");
      cleanup.addAppStep('stop the session started at 01:58', () => ctx.commands.stop());
      const minutes0158 = await launchWithStopAt(s, {
        stopAt: '02:00',
        targetMs: nextLocalTime(at0158, offsetMin, 2, 0),
        offsetMin,
      });
      // 2 unless the cold start took more than a minute (then 1 is the correct value, and the check above accepted it).
      if (minutes0158 !== 2) ctx.log(`the launch at 01:58 took over a minute: the app computed ${minutes0158} minute(s)`);
      expectEqual((await ctx.commands.state()).enabled, true, 'state.enabled after the launch at 01:58');
    }),
  { timeoutMs: 12 * 60_000, requires: { root: true, gms: true } },
);

scenario(
  'F-03',
  '02:00 stop still happens after a process kill (restore) and after a reboot',
  (ctx) =>
    withCleanup(ctx, async (cleanup) => {
      // Battery-exempt like a production field-force install: Android 12+ lets only exempt apps restart the
      // foreground service from the background after a kill.
      const s = await setUp(ctx, cleanup, { at: START_PLACE, batteryExempt: true });
      const plan = await planStopAt(ctx, F03_STOP_MINUTES);
      await writeTestFile(ctx, OVERRIDES_FILE, overridesFor(s.office, { stopAt: plan.stopAt }));
      const minutes = await launchWithStopAt(s, plan);
      const start = await waitForAutoStart(s);
      const dueHostMs = recordedMs(start) + minutes * 60_000 - s.clockOffsetMs;

      // 1. kill -9 (what an OEM task killer or the low-memory killer does): restored by START_STICKY or the alarm.
      const killedPid = await requirePid(ctx);
      await ctx.adb.killHard(ctx.appId);
      const restoredPid = await waitForNewPid(ctx, killedPid, 4 * 60_000);
      await waitForRecord(
        s,
        'tracking_start (reason restore) after kill -9',
        (r) => r.event === 'tracking_start' && r.reason === 'restore',
        4 * 60_000,
      );
      ctx.log(`restored in pid ${restoredPid}`);

      // 2. reboot: restored by BOOT_COMPLETED (startOnBoot true).
      const remainingMs = dueHostMs - Date.now();
      check(
        remainingMs >= 5 * 60_000,
        `only ${Math.round(remainingMs / 1000)} s remain before the planned stop, too little for a reboot ` +
          `(the kill and restore took too long; raise F03_STOP_MINUTES)`,
      );
      await rebootHolding(ctx, START_PLACE);
      const boot = await waitForRecord(
        s,
        'tracking_start (reason boot) after the reboot',
        (r) => r.event === 'tracking_start' && r.reason === 'boot',
        5 * 60_000,
      );
      check(
        Date.now() < dueHostMs,
        `the boot restore (${boot.recorded_at}) finished after the planned stop; the reboot took too long to tell ` +
          'whether the stop time survived it (raise F03_STOP_MINUTES)',
      );

      // 3. The worker opens the app during the session: the running session keeps its stop time (§10 step 3).
      const startup = await launchApp(s);
      expectEqual(
        startup.stopAfterElapsedMinutes,
        minutes,
        'FF_APP.startup.stopAfterElapsedMinutes when the app is opened during a running session',
      );
      expectEqual(
        (await ctx.commands.state()).config.geolocation?.stopAfterElapsedMinutes,
        minutes,
        'plugin config geolocation.stopAfterElapsedMinutes after reopening the app',
      );

      // 4. The stop, measured from the session start.
      const stop = await waitForRecord(
        s,
        'tracking_stop (stop_after_elapsed) of the session',
        (r) => r.event === 'tracking_stop' && r.reason === 'stop_after_elapsed',
        Math.max(0, dueHostMs - Date.now()) + (STOP_LATE_S + 60) * 1000,
      );
      assertStopTiming(stop, start, minutes, plan.targetMs);
      const all = records(s);
      expectEqual(
        records(s, 'tracking_start').map((r) => r.reason).join(','),
        'start,restore,boot',
        'reasons of the tracking_start records of the session',
      );
      const stops = records(s, 'tracking_stop');
      check(
        stops.length === 1 && stops[0].uuid === stop.uuid,
        `expected exactly one tracking_stop (stop_after_elapsed); ${describeRecords(stops)}`,
      );
      assertions.inOrder(all, [
        { event: 'tracking_start', reason: 'start' },
        { event: 'tracking_start', reason: 'restore' },
        { event: 'tracking_start', reason: 'boot' },
        { event: 'tracking_stop', reason: 'stop_after_elapsed' },
      ]);
      await waitForTrackingServiceStopped(ctx, 'the elapsed stop');
      await ctx.crashes.assertNoFgsDidNotStartInTime();
    }),
  { timeoutMs: 20 * 60_000, requires: { root: true, gms: true } },
);

scenario(
  'F-04',
  'route replay: uploads batched within syncInterval, odometer about route length, travel time',
  (ctx) =>
    withCleanup(ctx, async (cleanup) => {
      const route = ROUTES.cityLoop3km;
      const routeM = assertions.routeLengthMeters(route);
      const s = await setUp(ctx, cleanup, { at: route[0] });
      await launchApp(s);
      await waitForAutoStart(s);

      const paceAt = await ctx.adb.deviceTime();
      await startMoving(ctx);
      const driveStarted = Date.now();
      await drive(ctx, route, TRIP_SPEED_MPS);
      const driveS = (Date.now() - driveStarted) / 1000;
      ctx.log(`drove ${routeM.toFixed(0)} m in ${driveS.toFixed(0)} s`);

      // The trip ends with the stationary transition (stopTimeout); that record reaches the server within
      // syncInterval, and an upload drains the whole queue, so every earlier trip record is there too.
      const { begin, end } = await poll(
        ctx,
        'the trip on the back office: motionchange (is_moving true), then motionchange (is_moving false)',
        () => {
          const movement = movementAfter(records(s), paceAt);
          return movement?.end ? { begin: movement.begin, end: movement.end } : undefined;
        },
        (STOP_TIMEOUT_MIN * 60 + TEST_SYNC_INTERVAL_S + TOLERANCE_S + 60) * 1000,
        () => `received: ${describeRecords(records(s))}`,
      );
      const trip = records(s).filter((r) => recordedMs(r) >= recordedMs(begin) && recordedMs(r) <= recordedMs(end));

      // The tracked route has no holes: consecutive fixes at most 150 m apart (distanceFilter 20 m at 15 m/s).
      const fixes = trip.filter((r) => (r.event === 'location' || r.event === 'motionchange') && r.coords);
      check(fixes.length >= 10, `only ${fixes.length} fixes in the trip; ${describeRecords(trip)}`);
      let largestStepM = 0;
      for (let i = 1; i < fixes.length; i++) {
        const a = fixes[i - 1].coords;
        const b = fixes[i].coords;
        if (!a || !b) continue;
        largestStepM = Math.max(
          largestStepM,
          assertions.haversineMeters({ lat: a.latitude, lon: a.longitude }, { lat: b.latitude, lon: b.longitude }),
        );
      }
      check(largestStepM <= 150, `the uploaded route has a ${largestStepM.toFixed(0)} m hole between consecutive fixes (max 150 m)`);

      // Distance and travel time as the back office computes them.
      const summary = assertions.travelSummary(trip);
      ctx.log(
        `trip: odometer ${summary.odometerM.toFixed(0)} m, path ${summary.pathM.toFixed(0)} m, moving ` +
          `${summary.movingS.toFixed(0)} s, span ${summary.spanS.toFixed(0)} s, ${fixes.length} fixes`,
      );
      assertions.approximately(summary.odometerM, routeM, { pct: 10 }, 'odometer (last - first) over the trip');
      assertions.approximately(summary.pathM, routeM, { pct: 10 }, 'path length of the uploaded coordinates');
      const movingMaxS = driveS + STOP_TIMEOUT_MIN * 60 + 60;
      check(
        summary.movingS >= driveS * 0.9 && summary.movingS <= movingMaxS,
        `moving time ${summary.movingS.toFixed(0)} s: expected between the drive time ${driveS.toFixed(0)} s (-10 %) ` +
          `and the drive time + stopTimeout + 60 s (${movingMaxS.toFixed(0)} s)`,
      );
      check(summary.spanS + 1 >= summary.movingS, `span ${summary.spanS} s shorter than moving time ${summary.movingS} s`);

      // Live location: each trip record reached the server within syncInterval (+ slack), in batches.
      const tripUuids = new Set(trip.filter((r) => r.event === 'location' || r.event === 'motionchange').map((r) => r.uuid));
      const stored = s.office.records({ since: s.since, unique: true }).filter((u) => tripUuids.has(u.record.uuid));
      const late = stored.filter((u) => uploadDelayS(s, u) > TEST_SYNC_INTERVAL_S + TOLERANCE_S);
      check(
        late.length === 0,
        `${late.length} trip record(s) reached the server more than ${TEST_SYNC_INTERVAL_S + TOLERANCE_S} s after ` +
          `recorded_at: ${late.slice(0, 5).map((u) => `${u.record.uuid} ${uploadDelayS(s, u).toFixed(0)} s`).join(', ')}`,
      );
      const requests = new Set(stored.map((u) => u.requestId));
      const tripSpanS = (recordedMs(end) - recordedMs(begin)) / 1000;
      const maxRequests = Math.ceil(tripSpanS / TEST_SYNC_INTERVAL_S) + 2;
      check(
        requests.size <= maxRequests,
        `the ${stored.length} trip records came in ${requests.size} requests; with syncInterval ${TEST_SYNC_INTERVAL_S} s ` +
          `over ${tripSpanS.toFixed(0)} s at most ${maxRequests} were expected (records are not batched)`,
      );
      const largestBatch = Math.max(...stored.map((u) => u.batchSize));
      check(largestBatch >= 5, `largest upload held ${largestBatch} records; expected batches of at least 5 (batchSync)`);
    }),
  { timeoutMs: 15 * 60_000, requires: { gms: true } },
);

scenario(
  'F-05',
  'stationary: no GPS, heartbeats on cadence with an older location.timestamp',
  (ctx) =>
    withCleanup(ctx, async (cleanup) => {
      const s = await setUp(ctx, cleanup, { at: START_PLACE });
      await launchApp(s);
      const uid = await appUid(ctx);

      // Moving: GPS on. This is the positive control of the dumpsys parser (without it "no GPS" would prove nothing).
      const paceAt = await ctx.adb.deviceTime();
      await startMoving(ctx);
      let movingDump = '';
      try {
        await poll(
          ctx,
          'a GPS request attributed to the app in dumpsys location while moving',
          async () => {
            movingDump = await ctx.adb.dumpsys('location');
            return scanGpsRequests(movingDump, ctx.appId, uid).lines.length > 0;
          },
          45_000,
        );
      } catch (error) {
        await ctx.artifacts.writeText('dumpsys-location-moving.txt', movingDump).catch(() => '');
        const scan = scanGpsRequests(movingDump, ctx.appId, uid);
        throw new Error(
          `positive control failed: while moving (state.isMoving true, desiredAccuracy high) 'dumpsys location' shows ` +
            `no gps request attributed to ${ctx.appId} (uid ${uid}; gps section found: ${scan.sectionFound}), so the ` +
            'GPS-off check cannot prove anything on this image (dump: dumpsys-location-moving.txt)',
          { cause: error },
        );
      }

      // Hold still: after stopTimeout the engine is STATIONARY, GPS goes off and the service stays.
      await poll(
        ctx,
        `state.isMoving false (stopTimeout ${STOP_TIMEOUT_MIN} min without movement)`,
        async () => !(await ctx.commands.state()).isMoving,
        (STOP_TIMEOUT_MIN * 60 + 90) * 1000,
      );
      try {
        await poll(
          ctx,
          'no GPS request from the app after the stationary transition',
          async () => {
            const scan = scanGpsRequests(await ctx.adb.dumpsys('location'), ctx.appId, uid);
            return scan.sectionFound && scan.lines.length === 0;
          },
          30_000,
        );
      } catch {
        await assertNoGps(ctx, uid, 'after the stationary transition');
      }
      check(
        await ctx.app.isForegroundServiceRunning(SERVICES.tracking),
        'the tracking foreground service stopped when the device became stationary (it must stay)',
      );

      // Two stationary heartbeats; GPS stays off through them.
      const stationaryHeartbeats = (): { mcFalse: WireRecord; heartbeats: WireRecord[] } | undefined => {
        const all = records(s);
        const mcFalse = movementAfter(all, paceAt)?.end;
        if (!mcFalse) return undefined;
        return { mcFalse, heartbeats: all.filter((r) => r.event === 'heartbeat' && recordedMs(r) > recordedMs(mcFalse)) };
      };
      for (let n = 1; n <= 2; n++) {
        await poll(
          ctx,
          `stationary heartbeat #${n} on the back office`,
          () => (stationaryHeartbeats()?.heartbeats.length ?? 0) >= n,
          HEARTBEAT_WAIT_MS + (n === 1 ? 60_000 : 0),
          () => `received: ${describeRecords(records(s))}`,
        );
        await assertNoGps(ctx, uid, `after stationary heartbeat ${n}`);
      }
      const view = stationaryHeartbeats();
      check(view, 'the stationary motionchange disappeared from the back office');
      const { mcFalse, heartbeats } = view;
      const gaps = assertions.heartbeatCadence(records(s), {
        minIntervalS: TEST_HEARTBEAT.minInterval,
        maxIntervalS: TEST_HEARTBEAT.maxInterval,
        toleranceS: TOLERANCE_S,
        since: mcFalse.recorded_at,
      });
      ctx.log(`stationary heartbeat gaps: ${gaps.map((g) => g.toFixed(0)).join(', ')} s`);
      check(gaps.length >= 2, `expected at least 2 heartbeat windows after the stationary motionchange, got ${gaps.length}`);

      // Each heartbeat is created now (recorded_at) but reports the last fix with its acquisition time (timestamp).
      let largestAgeS = 0;
      for (const hb of heartbeats) {
        check(hb.coords && hb.timestamp, `stationary heartbeat ${hb.uuid} has no location: ${JSON.stringify(hb)}`);
        const ageS = (recordedMs(hb) - Date.parse(hb.timestamp)) / 1000;
        largestAgeS = Math.max(largestAgeS, ageS);
        check(
          ageS >= 1,
          `heartbeat ${hb.uuid}: timestamp ${hb.timestamp} is not older than recorded_at ${hb.recorded_at}; a stationary ` +
            'heartbeat must carry the last fix with its acquisition time, not a new fix',
        );
        expectEqual(hb.is_moving, false, `heartbeat ${hb.uuid} is_moving`);
        const distanceM = assertions.haversineMeters({ lat: hb.coords.latitude, lon: hb.coords.longitude }, START_PLACE);
        check(distanceM <= 50, `heartbeat ${hb.uuid} is ${distanceM.toFixed(0)} m from the stationary position (max 50 m)`);
      }
      check(
        largestAgeS >= TEST_HEARTBEAT.minInterval - 10,
        `the oldest location.timestamp of the stationary heartbeats is only ${largestAgeS.toFixed(0)} s old; with GPS off ` +
          `it should be at least ${TEST_HEARTBEAT.minInterval - 10} s (the fix of the stationary transition). A fresher ` +
          'fix means GPS or another app delivered locations (see the dumpsys artifacts)',
      );
      const movedAgain = records(s).filter((r) => r.event === 'motionchange' && r.is_moving && recordedMs(r) > recordedMs(mcFalse));
      check(movedAgain.length === 0, `motionchange (is_moving true) while stationary: ${describeRecords(movedAgain)}`);
    }),
  { timeoutMs: 15 * 60_000, requires: { gms: true } },
);

scenario(
  'F-06',
  'online/offline audit on the server: heartbeat cadence, stop records tracking_stop',
  (ctx) =>
    withCleanup(ctx, async (cleanup) => {
      const s = await setUp(ctx, cleanup, { at: START_PLACE });
      await launchApp(s);
      const start1 = await waitForAutoStart(s);

      // On: heartbeats on cadence, the back office shows the device online.
      await poll(
        ctx,
        'two heartbeats while tracking is on',
        () => records(s, 'heartbeat').filter((r) => recordedMs(r) > recordedMs(start1)).length >= 2,
        2 * HEARTBEAT_WAIT_MS + 60_000,
        () => `received: ${describeRecords(records(s))}`,
      );
      const onStatus = trackingStatusAt(records(s), await ctx.adb.deviceTime());
      check(onStatus.online, `the back office shows the device offline while tracking is on: ${onStatus.why}`);

      // Off: stop() records tracking_stop at once; then nothing is sent, and the device shows offline (stopped).
      await ctx.commands.stop();
      const stop = await waitForRecord(s, 'tracking_stop (reason stop)', (r) => r.event === 'tracking_stop' && r.reason === 'stop', 60_000);
      await waitForTrackingServiceStopped(ctx, 'stop()');
      await sleep(OFFLINE_S * 1000, ctx.signal);
      const whileOff = records(s).filter((r) => recordedMs(r) > recordedMs(stop));
      check(whileOff.length === 0, `records arrived while tracking was off: ${describeRecords(whileOff)}`);
      const offStatus = trackingStatusAt(records(s), await ctx.adb.deviceTime());
      check(
        !offStatus.online && offStatus.why === 'tracking stopped (stop)',
        `after stop() the back office should show 'tracking stopped (stop)', shows ${offStatus.online ? 'online' : 'offline'}: ${offStatus.why}`,
      );

      // On again: the worker opens the app again (the page reloads and auto-starts).
      check(s.web, 'no WebView driver');
      await s.web.reload();
      await readStartup(ctx, s.web);
      const start2 = await waitForRecord(
        s,
        'tracking_start (reason start) after the page reloaded',
        (r) => r.event === 'tracking_start' && r.reason === 'start' && recordedMs(r) > recordedMs(stop),
        60_000,
      );
      await waitForRecord(
        s,
        'a heartbeat after tracking was turned on again',
        (r) => r.event === 'heartbeat' && recordedMs(r) > recordedMs(start2),
        HEARTBEAT_WAIT_MS + 60_000,
      );
      const againStatus = trackingStatusAt(records(s), await ctx.adb.deviceTime());
      check(againStatus.online, `the back office shows the device offline after tracking restarted: ${againStatus.why}`);

      // The audit trail: heartbeat cadence in both on-periods, and the only long gap is explained by the stop.
      const all = records(s);
      const period1 = all.filter((r) => recordedMs(r) >= recordedMs(start1) && recordedMs(r) <= recordedMs(stop));
      const period2 = all.filter((r) => recordedMs(r) >= recordedMs(start2));
      const cadence = { minIntervalS: TEST_HEARTBEAT.minInterval, maxIntervalS: TEST_HEARTBEAT.maxInterval, toleranceS: TOLERANCE_S };
      const gaps1 = assertions.heartbeatCadence(period1, cadence);
      const gaps2 = assertions.heartbeatCadence(period2, cadence);
      check(gaps1.length >= 2, `expected at least 2 heartbeat windows before stop(), got ${gaps1.length}`);
      check(gaps2.length >= 1, `expected at least 1 heartbeat window after the restart, got ${gaps2.length}`);
      const explained = assertions.gapsExplained(all, { maxGapS: MAX_SILENCE_S });
      check(
        explained.length === 1 &&
          explained[0].from.uuid === stop.uuid &&
          explained[0].to.uuid === start2.uuid &&
          explained[0].seconds >= OFFLINE_S,
        `expected exactly one gap longer than ${MAX_SILENCE_S} s, from tracking_stop ${stop.uuid} to tracking_start ` +
          `${start2.uuid} (>= ${OFFLINE_S} s); found: ${explained
            .map((g) => `${describeRecord(g.from)} -> ${describeRecord(g.to)} ${g.seconds.toFixed(0)} s`)
            .join('; ') || 'none'}`,
      );
    }),
  { timeoutMs: 15 * 60_000, requires: { gms: true } },
);

// ---------------------------------------------------------------------------------------------------------------------
// F-07 … F-12: the companion plugin (fake PremiseMonitor) receives the audit natively

scenario(
  'F-07',
  'premise ENTER: PremiseMonitor service runs, its audit endpoint receives enter and every later record/event',
  (ctx) =>
    withCleanup(ctx, async (cleanup) => {
      const s = await setUp(ctx, cleanup, { at: PLACES.hqEast400m, overrides: { premise: PREMISE } });
      await launchApp(s);
      await waitForMonitoringStarted(s);
      const { enterRecord } = await enterPremise(s);

      const status = await ctx.commands.premiseStatus();
      expectEqual(status.inside, true, 'premise.status inside after the ENTER');
      expectEqual(status.serviceRunning, true, 'premise.status serviceRunning after the ENTER');
      check(
        premiseEntries(s).some((e) => e.kind === 'premise' && e.type === 'service_started'),
        `no 'service_started' entry after the ENTER; ${describeEntries(premiseEntries(s))}`,
      );

      // The first heartbeat after the ENTER: audited as a record and as an event, and uploaded to the back office.
      const hb = recordOf(
        await waitForAuditedHeartbeat(
          s,
          'the first heartbeat after the ENTER',
          (_e, record) => recordedMs(record) > recordedMs(enterRecord),
          STOP_TIMEOUT_MIN * 60_000 + HEARTBEAT_WAIT_MS + 60_000,
        ),
      );
      // A heartbeat is a priority upload that takes the whole queue, so the server then has every earlier record.
      await waitForRecord(s, `heartbeat ${hb.uuid}`, (r) => r.uuid === hb.uuid, 60_000);

      // Everything the back office received from the ENTER to that heartbeat was also audited by PremiseMonitor.
      const afterEnter = records(s).filter(
        (r) => recordedMs(r) >= recordedMs(enterRecord) && recordedMs(r) <= recordedMs(hb),
      );
      check(
        afterEnter.some((r) => r.uuid === enterRecord.uuid),
        `the ENTER geofence record ${enterRecord.uuid} is not on the back office; ${describeRecords(records(s))}`,
      );
      const entries = premiseEntries(s);
      assertCompanionSawAll(afterEnter, entries, 'records from the ENTER to the first heartbeat');
      for (const entry of entriesAbout(entries, afterEnter.map((r) => r.uuid))) {
        expectEqual(entry.source, 'manifest', `audit entry ${entry.id} source (manifest-declared listener)`);
      }
      ctx.log(`PremiseMonitor audited all ${afterEnter.length} records from the ENTER to the heartbeat`);
    }),
  { timeoutMs: 12 * 60_000, requires: { gms: true } },
);

scenario(
  'F-08',
  'app backgrounded and activity destroyed: PremiseMonitor still receives events natively',
  (ctx) =>
    withCleanup(ctx, async (cleanup) => {
      const s = await setUp(ctx, cleanup, { at: PLACES.hqEast400m, overrides: { premise: PREMISE } });
      await launchApp(s);
      await waitForMonitoringStarted(s);
      const pid = await requirePid(ctx);
      check(
        await activityRecordExists(ctx),
        "positive control failed: 'dumpsys activity activities' shows no 'Hist #n: ActivityRecord' line of the app " +
          'while it is in the foreground, so the destroyed-activity check below cannot work on this image',
      );

      // HOME, then the debug hook finishes the activity: the activity (and its WebView, the JS layer) is destroyed and
      // the process stays. ("Don't keep activities" through `settings put global always_finish_activities 1` does not
      // reach the running activity manager: on the first CI run the activity stayed alive after HOME.)
      await ctx.adb.keyHome();
      const finished = await ctx.commands.finishActivities();
      check(finished >= 1, `finishActivities finished ${finished} activities; expected the app's MainActivity`);
      await poll(
        ctx,
        `the activity of ${ctx.appId} to be destroyed (HOME, then finishActivities)`,
        async () => !(await activityRecordExists(ctx)),
        30_000,
      );
      expectEqual(await ctx.app.pid(), pid, 'app pid after the activity was destroyed (the tracking service keeps the process)');
      check(
        await ctx.app.isForegroundServiceRunning(SERVICES.tracking),
        'the tracking foreground service stopped when the activity was destroyed',
      );
      expectEqual((await ctx.commands.state()).enabled, true, 'state.enabled after the activity was destroyed');

      // Records and events created with no JS alive: a motion change (debug command), then the next heartbeat.
      const destroyedAt = await ctx.adb.deviceTime();
      await ctx.commands.changePace(true);
      const motion = recordOf(
        await waitForPremiseEntry(
          s,
          'the motionchange (is_moving true) record entry after the activity was destroyed',
          (e) =>
            e.kind === 'record' &&
            e.record?.event === 'motionchange' &&
            e.record.is_moving &&
            recordedMs(e.record) >= destroyedAt - 1000,
          60_000,
        ),
      );
      const hb = recordOf(
        await waitForAuditedHeartbeat(
          s,
          'a heartbeat after the activity was destroyed',
          (_e, record) => recordedMs(record) > recordedMs(motion),
          STOP_TIMEOUT_MIN * 60_000 + HEARTBEAT_WAIT_MS + 60_000,
        ),
      );
      const entries = premiseEntries(s);
      assertCompanionSawAll([motion, hb], entries, 'records created after the activity was destroyed');
      for (const entry of entriesAbout(entries, [motion.uuid, hb.uuid])) {
        const label = `audit entry ${entry.id} (${entry.kind} ${entry.kind === 'event' ? entry.name : entry.record?.event})`;
        expectEqual(entry.pid, pid, `${label} pid (same process, not restarted)`);
        expectEqual(entry.source, 'manifest', `${label} source`);
        expectEqual(entry.js, true, `${label} js (a PremiseMonitor JS instance was loaded earlier in this process)`);
      }
      check(!(await activityRecordExists(ctx)), 'the activity was recreated during the check; native delivery is not proven');
      // Hard rule: a heartbeat goes to the native listeners and to the HTTP url.
      await waitForRecord(s, `heartbeat ${hb.uuid}`, (r) => r.uuid === hb.uuid, 60_000);
    }),
  { requires: { gms: true } },
);

scenario(
  'F-09',
  'kill -9 inside the premise: process restored, listener receives events before any JS',
  (ctx) =>
    withCleanup(ctx, async (cleanup) => {
      const s = await setUp(ctx, cleanup, { at: PLACES.hqEast400m, overrides: { premise: PREMISE }, batteryExempt: true });
      await launchApp(s);
      await waitForMonitoringStarted(s);
      await enterPremise(s);
      const killedPid = await requirePid(ctx);

      await ctx.adb.killHard(ctx.appId);
      const pid = await waitForNewPid(ctx, killedPid, 4 * 60_000);

      // The restored process has no activity and no WebView: its entries come from the manifest listener alone.
      const restoreEntry = await waitForPremiseEntry(
        s,
        `the tracking_start (restore) record entry from the restored process (pid ${pid})`,
        (e) => e.kind === 'record' && e.pid === pid && e.record?.event === 'tracking_start' && e.record.reason === 'restore',
        4 * 60_000,
      );
      await waitForPremiseEntry(
        s,
        `an event entry from the restored process (pid ${pid})`,
        (e) => e.kind === 'event' && e.pid === pid,
        3 * 60_000,
      );
      const beforeJs = premiseEntries(s).filter((e) => e.pid === pid);
      const withJs = beforeJs.filter((e) => e.js);
      check(
        withJs.length === 0,
        `entries of the restored process claim js: true before the app was opened: ${describeEntries(withJs)}`,
      );
      for (const entry of beforeJs.filter((e) => e.kind !== 'premise')) {
        expectEqual(entry.source, 'manifest', `audit entry ${entry.id} source`);
      }
      await waitForRecord(s, 'the restore tracking_start', (r) => r.uuid === recordOf(restoreEntry).uuid, 60_000);
      await poll(
        ctx,
        'PremiseMonitorService to be restarted in the restored process (monitoring and inside)',
        () => ctx.app.isForegroundServiceRunning(SERVICES.premise),
        90_000,
        () => `audit: ${describeEntries(premiseEntries(s))}`,
      );
      ctx.log(`${beforeJs.length} entries from pid ${pid} before any JS`);

      // Positive control: once the page loads PremiseMonitor's JS, entries of the same process say js: true. The
      // page's startMonitoring() for the same premise may write no entry, so this can take until the next heartbeat.
      await launchApp(s);
      expectEqual(await ctx.app.pid(), pid, 'app pid after opening the app (the restored process is reused)');
      await waitForPremiseEntry(
        s,
        `an entry with js: true from pid ${pid} after the app was opened`,
        (e) => e.pid === pid && e.js,
        HEARTBEAT_WAIT_MS + 60_000,
      );
      await ctx.crashes.assertNoFgsDidNotStartInTime();
    }),
  { timeoutMs: 15 * 60_000, requires: { root: true, gms: true } },
);

scenario(
  'F-10',
  'reboot inside the premise: listener receives tracking_start boot + heartbeats, PremiseMonitor service restored',
  (ctx) =>
    withCleanup(ctx, async (cleanup) => {
      const s = await setUp(ctx, cleanup, { at: PLACES.hqEast400m, overrides: { premise: PREMISE }, batteryExempt: true });
      await launchApp(s);
      await waitForMonitoringStarted(s);
      const { enterRecord } = await enterPremise(s);

      await rebootHolding(ctx, PLACES.hq);

      const bootEntry = await waitForPremiseEntry(
        s,
        'the tracking_start (boot) record entry after the reboot',
        (e) => e.kind === 'record' && e.record?.event === 'tracking_start' && e.record.reason === 'boot',
        5 * 60_000,
      );
      const boot = recordOf(bootEntry);
      expectEqual(bootEntry.js, false, 'js of the boot entry (no activity was opened after the reboot)');
      // pids restart at every boot, so a new process is told apart by boot_count (-1 = the device does not report it).
      if (boot.boot_count >= 0 && enterRecord.boot_count >= 0) {
        check(
          boot.boot_count > enterRecord.boot_count,
          `boot_count after the reboot (${boot.boot_count}) is not greater than before (${enterRecord.boot_count})`,
        );
      } else {
        ctx.log(`boot_count not reported (before ${enterRecord.boot_count}, after ${boot.boot_count})`);
      }
      const hb = recordOf(
        await waitForAuditedHeartbeat(
          s,
          'a heartbeat after the boot',
          (_e, record) => recordedMs(record) > recordedMs(boot),
          HEARTBEAT_WAIT_MS + 90_000,
        ),
      );

      // PremiseMonitor restarts its service in the new process because it is monitoring and inside.
      await poll(
        ctx,
        'PremiseMonitorService to be restored after the reboot',
        () => ctx.app.isForegroundServiceRunning(SERVICES.premise),
        2 * 60_000,
        () => `audit: ${describeEntries(premiseEntries(s))}`,
      );
      check(
        premiseEntries(s).some(
          (e) => e.kind === 'premise' && e.type === 'service_started' && Date.parse(e.at) >= Date.parse(bootEntry.at),
        ),
        `no 'service_started' entry after the boot entry (${bootEntry.at}); ${describeEntries(premiseEntries(s))}`,
      );
      const status = await ctx.commands.premiseStatus();
      expectEqual(status.monitoring, true, 'premise.status monitoring after the reboot');
      expectEqual(status.inside, true, 'premise.status inside after the reboot');
      expectEqual(status.serviceRunning, true, 'premise.status serviceRunning after the reboot');

      // The same records reached the plugin's own upload.
      await waitForRecord(s, 'the boot tracking_start', (r) => r.uuid === boot.uuid, 60_000);
      await waitForRecord(s, `heartbeat ${hb.uuid}`, (r) => r.uuid === hb.uuid, 60_000);
      await ctx.crashes.assertNoFgsDidNotStartInTime();
    }),
  { timeoutMs: 15 * 60_000, requires: { gms: true } },
);

scenario(
  'F-11',
  'premise EXIT: exit audit, PremiseMonitor service stops',
  (ctx) =>
    withCleanup(ctx, async (cleanup) => {
      const s = await setUp(ctx, cleanup, { at: PLACES.hqEast400m, overrides: { premise: PREMISE } });
      await launchApp(s);
      await waitForMonitoringStarted(s);
      const { enter } = await enterPremise(s);

      await startMoving(ctx);
      await drive(ctx, [PLACES.hq, PLACES.hqEast400m], APPROACH_SPEED_MPS);
      const exit = await waitForPremiseEntry(
        s,
        `PremiseMonitor 'exit' for '${PREMISE.id}' after driving out of the premise`,
        (e) => e.kind === 'premise' && e.type === 'exit' && e.premise_id === PREMISE.id,
        3 * 60_000,
      );
      check(
        isPremiseTransition(exit.location, 'EXIT'),
        `the 'exit' entry's location is not the EXIT geofence record of '${PREMISE_GEOFENCE_ID}': ${JSON.stringify(exit.location)}`,
      );
      check(Date.parse(exit.at) > Date.parse(enter.at), `'exit' (${exit.at}) is not after 'enter' (${enter.at})`);

      await poll(
        ctx,
        'PremiseMonitorService to stop after the EXIT',
        async () => !(await ctx.app.isForegroundServiceRunning(SERVICES.premise)),
        60_000,
      );
      await waitForPremiseEntry(
        s,
        "a 'service_stopped' entry after the exit",
        (e) => e.kind === 'premise' && e.type === 'service_stopped' && Date.parse(e.at) >= Date.parse(exit.at),
        60_000,
      );
      const status = await ctx.commands.premiseStatus();
      expectEqual(status.inside, false, 'premise.status inside after the EXIT');
      expectEqual(status.serviceRunning, false, 'premise.status serviceRunning after the EXIT');
      expectEqual(status.monitoring, true, 'premise.status monitoring after the EXIT (monitoring continues)');

      // Leaving the premise does not touch the tracking itself.
      expectEqual((await ctx.commands.state()).enabled, true, 'state.enabled after the EXIT');
      check(
        await ctx.app.isForegroundServiceRunning(SERVICES.tracking),
        'the tracking foreground service stopped together with PremiseMonitorService',
      );
    }),
  { requires: { gms: true } },
);

scenario(
  'F-12',
  'presence validation: a fix outside the radius while inside is flagged by PremiseMonitor',
  (ctx) =>
    withCleanup(ctx, async (cleanup) => {
      const s = await setUp(ctx, cleanup, { at: PLACES.hqEast400m, overrides: { premise: PREMISE } });
      await launchApp(s);
      await waitForMonitoringStarted(s);
      await enterPremise(s);

      // Two fixes inserted while inside (the device itself stays at the centre, so no geofence EXIT follows):
      // - 200 m east, accuracy 100 m: distance - accuracy = 100 m <= radius 150 m -> no violation;
      // - 400 m east, accuracy 10 m: distance - accuracy = 390 m > radius -> presence_violation.
      const withinAccuracy = assertions.offsetMeters(PREMISE_CENTRE, 0, 200);
      const outside = PLACES.hqEast400m;
      const withinUuid = await ctx.commands.insertLocation(fixAt(withinAccuracy, 100, 'F-12 200 m, accuracy 100 m'));
      const outsideUuid = await ctx.commands.insertLocation(fixAt(outside, 10, 'F-12 400 m, accuracy 10 m'));

      const violation = await waitForPremiseEntry(
        s,
        `a presence_violation for the fix ${outsideUuid} 400 m from the centre`,
        (e) => e.kind === 'premise' && e.type === 'presence_violation' && e.location?.uuid === outsideUuid,
        60_000,
      );
      // Entries are uploaded in creation order, so the earlier fix's record entry is there by now.
      await waitForPremiseEntry(s, `the record entry of the fix ${withinUuid}`, (e) => e.kind === 'record' && e.record?.uuid === withinUuid, 30_000);

      expectEqual(violation.premise_id, PREMISE.id, 'presence_violation premise_id');
      check(typeof violation.distance_m === 'number', `presence_violation has no distance_m: ${JSON.stringify(violation)}`);
      assertions.approximately(
        violation.distance_m,
        assertions.haversineMeters(PREMISE_CENTRE, outside),
        5,
        'presence_violation distance_m (distance of the fix from the premise centre)',
      );
      const violations = premiseEntries(s).filter((e) => e.kind === 'premise' && e.type === 'presence_violation');
      const forWithin = violations.filter((e) => e.location?.uuid === withinUuid);
      check(
        forWithin.length === 0,
        `a fix 200 m away with accuracy 100 m (distance - accuracy = 100 m, inside the 150 m radius) was flagged: ${describeEntries(forWithin)}`,
      );
      expectEqual(violations.filter((e) => e.location?.uuid === outsideUuid).length, 1, 'presence_violation entries for the outside fix');

      // One outlier fix is audited; it does not end the presence.
      const status = await ctx.commands.premiseStatus();
      expectEqual(status.inside, true, 'premise.status inside after the violation');
      expectEqual(status.serviceRunning, true, 'premise.status serviceRunning after the violation');
    }),
  { requires: { gms: true } },
);
