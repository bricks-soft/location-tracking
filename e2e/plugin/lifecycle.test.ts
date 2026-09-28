// Plugin suite A: lifecycle and foreground service (docs/e2e/architecture.md §9 A). Owned by unit 9.
//
// Every scenario drives the plugin's example app (`example/`, e2e mode: the page never changes plugin state on its
// own) through the debug receiver (§6), the WebView (JS API) and adb, and asserts what the mock back office receives.
// Times of records (`recorded_at`) are device wall-clock times, so every "after" boundary below is a device time
// (`adb.deviceTime()`), never a host time.
import assert from 'node:assert/strict';
import {
  E2eCommandError,
  PERMISSIONS,
  SERVICES,
  TEST_HEARTBEAT,
  WebViewDriver,
  assertions,
  scenario,
  sleep,
  waitUntil,
  type CapturedEvent,
  type MockBackOffice,
  type ScenarioContext,
  type StateJson,
  type WireRecord,
} from '@bricks-soft/e2e-kit';

// ---------------------------------------------------------------------------------------------------------------------
// Constants

/** Capacitor plugin name of the JS API. */
const PLUGIN = 'LocationTracking';

/** heartbeat.minInterval / maxInterval of the test config (seconds). */
const MIN_S = TEST_HEARTBEAT.minInterval;
const MAX_S = TEST_HEARTBEAT.maxInterval;

/** Slack for alarm delivery, uploads and adb latency (seconds). */
const TOLERANCE_S = 30;

/** A heartbeat window long enough that no heartbeat alarm fires while P-L07 updates the app. */
const QUIET_HEARTBEAT = { minInterval: 600, maxInterval: 900 } as const;

/**
 * Config patch without activity recognition: no activity PendingIntent can wake a killed process, so the heartbeat
 * alarm (P-L12) or the package-replaced broadcast (P-L07) is the only thing that can restore tracking.
 */
const NO_ACTIVITY_UPDATES = { activity: { disableMotionActivityUpdates: true } } as const;

/** The plugin's boot receiver (exported; handles BOOT_COMPLETED, MY_PACKAGE_REPLACED and both QUICKBOOT_POWERON). */
const BOOT_RECEIVER = 'com.brickssoft.locationtracking.service.BootReceiver';

/** The two QUICKBOOT_POWERON actions the boot receiver listens to. Any app may send them (not protected). */
const FAKE_BOOT_ACTIONS = ['android.intent.action.QUICKBOOT_POWERON', 'com.htc.intent.action.QUICKBOOT_POWERON'] as const;

/** JS events captured in P-L05. */
const CAPTURED_EVENTS = ['location', 'motionchange', 'heartbeat', 'enabledchange', 'providerchange'] as const;

// ---------------------------------------------------------------------------------------------------------------------
// Record helpers

/** Epoch ms of an ISO time; NaN when missing. */
function timeOf(iso: string | null | undefined): number {
  return iso ? Date.parse(iso) : Number.NaN;
}

function byRecordedAt(a: WireRecord, b: WireRecord): number {
  return timeOf(a.recorded_at) - timeOf(b.recorded_at);
}

function iso(ms: number): string {
  return Number.isFinite(ms) ? new Date(ms).toISOString() : String(ms);
}

/** `tracking_start(restore)`, `motionchange(is_moving=true)`, `heartbeat`, ... */
function label(record: WireRecord): string {
  if (record.reason !== undefined) return `${record.event}(${record.reason})`;
  if (record.event === 'motionchange') return `motionchange(is_moving=${record.is_moving})`;
  return record.event;
}

/** One line per record, for failure messages. */
function timeline(records: readonly WireRecord[], limit = 60): string {
  if (records.length === 0) return '  (none)';
  const shown = records.length > limit ? records.slice(records.length - limit) : records;
  const lines = shown.map(
    (r) => `  ${r.recorded_at}  ${label(r).padEnd(34)} uuid=${r.uuid.slice(0, 8)} boot_count=${r.boot_count}`,
  );
  if (shown.length < records.length) lines.unshift(`  ... ${records.length - shown.length} earlier record(s)`);
  return lines.join('\n');
}

/** Unique received records whose `recorded_at` (epoch ms) passes [keep], ordered by `recorded_at`. */
function receivedRecords(office: MockBackOffice, keep: (recordedAtMs: number) => boolean): WireRecord[] {
  return office
    .records({ unique: true })
    .map((stored) => stored.record)
    .filter((record) => keep(timeOf(record.recorded_at)))
    .sort(byRecordedAt);
}

/** Unique received records with `recorded_at >= afterDeviceMs`, ordered by `recorded_at`. */
function recordsAfter(office: MockBackOffice, afterDeviceMs: number): WireRecord[] {
  return receivedRecords(office, (at) => at >= afterDeviceMs);
}

/** Matcher for an audit record: `tracking_start` / `tracking_stop` with an optional reason. */
function audit(event: 'tracking_start' | 'tracking_stop', reason?: string): (record: WireRecord) => boolean {
  return (record) => record.event === event && (reason === undefined || record.reason === reason);
}

function isTrackingRecord(record: WireRecord): boolean {
  return record.event === 'tracking_start' || record.event === 'tracking_stop';
}

interface RecordWait {
  /** only records with `recorded_at >=` this device time (epoch ms) count */
  afterDeviceMs: number;
  timeoutMs: number;
  /** what is expected, for the failure message, e.g. "tracking_start with reason restore" */
  what: string;
}

/** Waits until the back office received a record matching [match]; fails with the records that did arrive. */
async function waitForRecord(
  ctx: ScenarioContext,
  office: MockBackOffice,
  match: (record: WireRecord) => boolean,
  wait: RecordWait,
): Promise<WireRecord> {
  try {
    return await office.waitFor((o) => recordsAfter(o, wait.afterDeviceMs).find(match), {
      timeoutMs: wait.timeoutMs,
      intervalMs: 1_000,
      message: wait.what,
      signal: ctx.signal,
    });
  } catch (error) {
    throw new Error(
      `expected ${wait.what} (recorded at or after ${iso(wait.afterDeviceMs)}) within ${wait.timeoutMs / 1000} s; ` +
        `it did not arrive. Records received since then:\n${timeline(recordsAfter(office, wait.afterDeviceMs))}`,
      { cause: error },
    );
  }
}

/** Waits for [count] heartbeat records after [afterDeviceMs]; each may take up to maxInterval + tolerance. */
async function waitForHeartbeats(
  ctx: ScenarioContext,
  office: MockBackOffice,
  afterDeviceMs: number,
  count: number,
  what: string,
): Promise<WireRecord[]> {
  const timeoutMs = count * (MAX_S + TOLERANCE_S) * 1000 + 60_000;
  try {
    return await office.waitFor(
      (o) => {
        const heartbeats = recordsAfter(o, afterDeviceMs).filter((r) => r.event === 'heartbeat');
        return heartbeats.length >= count ? heartbeats : undefined;
      },
      { timeoutMs, intervalMs: 2_000, message: what, signal: ctx.signal },
    );
  } catch (error) {
    throw new Error(
      `${what}: expected ${count} heartbeat record(s) recorded after ${iso(afterDeviceMs)} within ${timeoutMs / 1000} s ` +
        `(heartbeat ${MIN_S}/${MAX_S} s). Records received since then:\n${timeline(recordsAfter(office, afterDeviceMs))}`,
      { cause: error },
    );
  }
}

/** Asserts that exactly [expected] records match [match] in [records]. */
function assertCount(
  records: readonly WireRecord[],
  match: (record: WireRecord) => boolean,
  expected: number,
  what: string,
): void {
  const found = records.filter(match);
  assert.equal(
    found.length,
    expected,
    `${what}: expected ${expected} matching record(s), got ${found.length}. Records:\n${timeline(records)}`,
  );
}

// ---------------------------------------------------------------------------------------------------------------------
// Device helpers

async function deviceNow(ctx: ScenarioContext): Promise<number> {
  return ctx.adb.deviceTime();
}

/** Waits until the plugin's foreground service is running ([running] true) or gone. */
async function waitForService(ctx: ScenarioContext, running: boolean, what: string, timeoutMs = 30_000): Promise<void> {
  try {
    await waitUntil(async () => (await ctx.app.isForegroundServiceRunning(SERVICES.tracking)) === running, {
      timeoutMs,
      intervalMs: 1_000,
      message: what,
      signal: ctx.signal,
    });
  } catch (error) {
    throw new Error(
      `${what}: expected the tracking foreground service to be ${running ? 'running' : 'stopped'} within ` +
        `${timeoutMs / 1000} s (dumpsys activity services); it was not`,
      { cause: error },
    );
  }
}

/** `ready(config)` through the debug receiver after `prepare()` cleared the app data: tracking must be off. */
async function readyClean(ctx: ScenarioContext, config: Record<string, unknown>): Promise<void> {
  const ready = await ctx.commands.ready(config);
  assert.equal(ready.enabled, false, `ready() after a clean install reported enabled=true: ${JSON.stringify(ready)}`);
}

/** Applies [config] with the debug receiver and starts tracking; returns the `tracking_start(start)` record. */
async function readyAndStart(
  ctx: ScenarioContext,
  office: MockBackOffice,
  config: Record<string, unknown>,
): Promise<{ startDevice: number; startRecord: WireRecord }> {
  await readyClean(ctx, config);
  const startDevice = await deviceNow(ctx);
  const started = await ctx.commands.start();
  assert.equal(started.enabled, true, `start() resolved with enabled=false: ${JSON.stringify(started)}`);
  const startRecord = await waitForRecord(ctx, office, audit('tracking_start', 'start'), {
    afterDeviceMs: startDevice,
    timeoutMs: 30_000,
    what: 'tracking_start with reason start after start()',
  });
  await waitForService(ctx, true, 'after start()');
  return { startDevice, startRecord };
}

/** Plugin log lines (tags LT.*) of the main buffer since [since], for failure messages. */
async function pluginLog(ctx: ScenarioContext, since: Date, limit = 40): Promise<string> {
  try {
    const text = await ctx.adb.logcat.dump({ since, buffers: ['main'] });
    const lines = text.split('\n').filter((line) => /\sLT\.[A-Za-z]+\s*:/.test(line) || line.includes('LT-E2E'));
    return lines.slice(-limit).join('\n') || '(no plugin log lines)';
  } catch (error) {
    return `(logcat unavailable: ${String(error)})`;
  }
}

/** `runtime permissions` state of [permission] from `dumpsys package`; undefined when the line is missing. */
async function runtimePermissionGranted(ctx: ScenarioContext, permission: string): Promise<boolean | undefined> {
  const dump = await ctx.adb.shell(`dumpsys package ${ctx.appId}`);
  const pattern = new RegExp(`${permission.replace(/\./g, '\\.')}: granted=(true|false)`, 'g');
  const states = [...dump.matchAll(pattern)].map((match) => match[1] === 'true');
  if (states.length === 0) return undefined;
  return states.some((granted) => granted);
}

// ---------------------------------------------------------------------------------------------------------------------
// Raw debug commands (device-side sequences) and logcat parsing.
// KIT REQUEST: E2eCommands has no way to put two commands (or a command and `am force-stop`) into one `adb shell`
// invocation, and its responses carry no device timestamp. P-L02 and P-L13 need both, so these local helpers build the
// §6 broadcast line themselves and read the LT-E2E response lines from logcat.

let rawSequence = 0;

/** A §6 request id (`[A-Za-z0-9._-]{1,64}`). */
function rawCommandId(prefix: string): string {
  rawSequence += 1;
  return `${prefix}-${Date.now().toString(36)}-${rawSequence}`;
}

/** The `am broadcast` line of one §6 debug command, for use inside an `adb shell` command string. */
function e2eBroadcastLine(appId: string, id: string, cmd: string, args: Record<string, unknown>): string {
  const json64 = Buffer.from(JSON.stringify(args), 'utf8').toString('base64');
  return (
    `am broadcast -a ${appId}.E2E -n ${appId}/.e2e.E2eCommandReceiver --include-stopped-packages ` +
    `--es id ${id} --es cmd ${cmd} --es json64 ${json64}`
  );
}

interface LogLine {
  /** device time of the line, epoch ms (all lines of one run use the same clock and format) */
  timeMs: number;
  pid: number;
  level: string;
  tag: string;
  message: string;
}

/** `[YYYY-]MM-DD HH:MM:SS.mmm[uuu] [zone] pid tid L tag: message` (threadtime, with or without year / UTC). */
const THREADTIME_LINE =
  /^(?:(\d{4})-)?(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})\d*(?:\s+(?:[+-]\d{4}|UTC|GMT))?\s+(\d+)\s+\d+\s+([VDIWEFA])\s+(.*?)\s*: (.*)$/;
/** `seconds.mmm pid tid L tag: message` (`-v epoch`). */
const EPOCH_LINE = /^\s*(\d+)\.(\d{3})\d*\s+(\d+)\s+\d+\s+([VDIWEFA])\s+(.*?)\s*: (.*)$/;

/** Parses logcat text; lines in other formats are skipped. */
function parseLogcat(text: string): LogLine[] {
  const year = new Date().getUTCFullYear();
  const lines: LogLine[] = [];
  for (const raw of text.split('\n')) {
    const line = raw.replace(/\r$/, '');
    const t = THREADTIME_LINE.exec(line);
    if (t) {
      const timeMs = Date.UTC(
        t[1] ? Number(t[1]) : year,
        Number(t[2]) - 1,
        Number(t[3]),
        Number(t[4]),
        Number(t[5]),
        Number(t[6]),
        Number(t[7]),
      );
      lines.push({ timeMs, pid: Number(t[8]), level: t[9] ?? '', tag: t[10] ?? '', message: t[11] ?? '' });
      continue;
    }
    const e = EPOCH_LINE.exec(line);
    if (e) {
      lines.push({
        timeMs: Number(e[1]) * 1000 + Number(e[2]),
        pid: Number(e[3]),
        level: e[4] ?? '',
        tag: e[5] ?? '',
        message: e[6] ?? '',
      });
    }
  }
  return lines;
}

interface RawResponse {
  /** device time of the LT-E2E line */
  timeMs: number;
  ok: boolean;
  code?: string;
  message?: string;
  result?: unknown;
}

/** The LT-E2E response line of request [id] logged since [since], if there is one. */
async function rawResponseNow(ctx: ScenarioContext, since: Date, id: string): Promise<RawResponse | undefined> {
  const text = await ctx.adb.logcat.dump({ since, buffers: ['main'], filters: ['LT-E2E:I', '*:S'] });
  for (const line of parseLogcat(text)) {
    if (line.tag !== 'LT-E2E') continue;
    let parsed: unknown;
    try {
      parsed = JSON.parse(line.message);
    } catch {
      continue;
    }
    if (typeof parsed !== 'object' || parsed === null) continue;
    const response = parsed as { id?: unknown; ok?: unknown; code?: unknown; message?: unknown; result?: unknown };
    if (response.id !== id) continue;
    return {
      timeMs: line.timeMs,
      ok: response.ok === true,
      code: typeof response.code === 'string' ? response.code : undefined,
      message: typeof response.message === 'string' ? response.message : undefined,
      result: response.result,
    };
  }
  return undefined;
}

function describeResponse(response: RawResponse | undefined): string {
  if (!response) return 'no response (process killed before it answered)';
  if (response.ok) return 'ok';
  return `failed ${response.code ?? '?'}: ${response.message ?? ''}`;
}

// ---------------------------------------------------------------------------------------------------------------------
// JS event helpers (P-L05)

/** uuid of the record an event carries (`location`: the record; `motionchange` / `heartbeat`: `payload.location`). */
function eventUuid(event: CapturedEvent): string | undefined {
  const payload = event.payload as { uuid?: unknown; location?: { uuid?: unknown } | null } | null | undefined;
  if (!payload) return undefined;
  if (typeof payload.uuid === 'string') return payload.uuid;
  if (payload.location && typeof payload.location.uuid === 'string') return payload.location.uuid;
  return undefined;
}

/** `name:uuid` keys that occur more than once among record-carrying events. */
function duplicateEventKeys(events: readonly CapturedEvent[]): string[] {
  const counts = new Map<string, number>();
  for (const event of events) {
    const uuid = eventUuid(event);
    if (uuid === undefined) continue;
    const key = `${event.name}:${uuid}`;
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return [...counts.entries()].filter(([, count]) => count > 1).map(([key, count]) => `${key} x${count}`);
}

function countEvents(events: readonly CapturedEvent[], name: string, uuid: string): number {
  return events.filter((event) => event.name === name && eventUuid(event) === uuid).length;
}

function describeEvents(events: readonly CapturedEvent[]): string {
  if (events.length === 0) return '  (none)';
  return events
    .map((event) => `  ${iso(event.receivedAt)}  ${event.name} uuid=${(eventUuid(event) ?? '-').slice(0, 8)}`)
    .join('\n');
}

/**
 * `changePace(true)` then at once `changePace(false)`: two motionchange records, well inside the 1-minute stop timeout
 * of the test config, so no automatic transition interferes. Returns [moving, stationary].
 */
async function paceUpAndDown(
  ctx: ScenarioContext,
  office: MockBackOffice,
  what: string,
): Promise<[WireRecord, WireRecord]> {
  const upDevice = await deviceNow(ctx);
  await ctx.commands.changePace(true);
  const moving = await waitForRecord(ctx, office, (r) => r.event === 'motionchange' && r.is_moving, {
    afterDeviceMs: upDevice,
    timeoutMs: 30_000,
    what: `motionchange(is_moving=true) after changePace(true) ${what}`,
  });
  await ctx.commands.changePace(false);
  const still = await waitForRecord(ctx, office, (r) => r.event === 'motionchange' && !r.is_moving, {
    afterDeviceMs: timeOf(moving.recorded_at) + 1,
    timeoutMs: 30_000,
    what: `motionchange(is_moving=false) after changePace(false) ${what}`,
  });
  return [moving, still];
}

/** Drains [driver]'s captured events into [sink] until [done] holds. */
async function collectEventsUntil(
  ctx: ScenarioContext,
  driver: WebViewDriver,
  sink: CapturedEvent[],
  done: (events: readonly CapturedEvent[]) => boolean,
  timeoutMs: number,
  what: string,
): Promise<void> {
  try {
    await waitUntil(
      async () => {
        sink.push(...(await driver.drainEvents()));
        return done(sink);
      },
      { timeoutMs, intervalMs: 1_000, message: what, signal: ctx.signal },
    );
  } catch (error) {
    throw new Error(`${what}: not captured within ${timeoutMs / 1000} s. Captured JS events:\n${describeEvents(sink)}`, {
      cause: error,
    });
  }
}

/**
 * Connects to the page of a recreated activity: a WebView page without [marker] (set on the old page) that has the
 * plugin proxy. `AppUnderTest.webView()` reconnects only when the pid changes, and an activity recreation keeps the pid.
 */
async function connectRecreatedPage(ctx: ScenarioContext, marker: string, timeoutMs: number): Promise<WebViewDriver> {
  let lastError: unknown;
  try {
    return await waitUntil(
      async () => {
        let driver: WebViewDriver | undefined;
        try {
          driver = await WebViewDriver.connect(ctx.adb, ctx.appId, { timeoutMs: 5_000 });
          const fresh = await driver.evaluate<boolean>(
            `typeof window.${marker} === 'undefined' && !!(window.Capacitor && window.Capacitor.Plugins && ` +
              `window.Capacitor.Plugins.${PLUGIN})`,
          );
          if (fresh) return driver;
        } catch (error) {
          lastError = error;
        }
        if (driver) await driver.close().catch(() => undefined);
        return undefined;
      },
      { timeoutMs, intervalMs: 1_000, message: 'recreated WebView page', signal: ctx.signal },
    );
  } catch (error) {
    throw new Error(
      `the activity was not recreated within ${timeoutMs / 1000} s after the font scale change: no WebView page ` +
        `without window.${marker} (last connection error: ${String(lastError)})`,
      { cause: error },
    );
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// P-L01

const P_L01_PAIRS = 50;

scenario(
  'P-L01',
  'start/stop 50x in a rapid loop: no foreground-service crash, audit pairs complete',
  async (ctx) => {
    // The activity is in the foreground, so Android allows every start from the debug receiver.
    await ctx.app.prepare({ launch: true });
    const office = await ctx.backOffice();
    const config = await ctx.testConfig();
    await readyClean(ctx, config);

    const loopDevice = await deviceNow(ctx);
    for (let pair = 1; pair <= P_L01_PAIRS; pair++) {
      const started = await ctx.commands.start();
      assert.equal(started.enabled, true, `pair ${pair}: start() resolved with enabled=false: ${JSON.stringify(started)}`);
      const stopped = await ctx.commands.stop();
      assert.equal(stopped.enabled, false, `pair ${pair}: stop() resolved with enabled=true: ${JSON.stringify(stopped)}`);
    }
    ctx.log(`${P_L01_PAIRS} start/stop pairs sent`);

    // Every start and every stop is a priority record: all 100 are uploaded at once.
    const expected = 2 * P_L01_PAIRS;
    try {
      await office.waitFor((o) => recordsAfter(o, loopDevice).filter(isTrackingRecord).length >= expected, {
        timeoutMs: 120_000,
        intervalMs: 2_000,
        message: `${expected} tracking records`,
        signal: ctx.signal,
      });
    } catch (error) {
      const got = recordsAfter(office, loopDevice).filter(isTrackingRecord);
      throw new Error(
        `expected ${expected} tracking_start/tracking_stop records within 120 s, got ${got.length}:\n${timeline(got)}`,
        { cause: error },
      );
    }
    // Late extra records (for example a second tracking_stop) would arrive shortly after; give them time.
    await sleep(5_000, ctx.signal);

    const trail = recordsAfter(office, loopDevice).filter(isTrackingRecord);
    assert.equal(trail.length, expected, `expected exactly ${expected} tracking records, got ${trail.length}:\n${timeline(trail)}`);
    for (let index = 0; index < trail.length; index++) {
      const record = trail[index]!;
      const pair = Math.floor(index / 2) + 1;
      const want = index % 2 === 0 ? 'tracking_start(start)' : 'tracking_stop(stop)';
      if (label(record) !== want) {
        const context = trail.slice(Math.max(0, index - 3), index + 4);
        assert.fail(
          `record ${index + 1} of the audit trail (pair ${pair}) is ${label(record)} at ${record.recorded_at}; ` +
            `expected ${want}. Starts and stops must alternate. Around it:\n${timeline(context)}`,
        );
      }
    }
    // Each start precedes its stop in time (recorded_at strictly increasing through the trail).
    for (let index = 1; index < trail.length; index++) {
      const previous = trail[index - 1]!;
      const record = trail[index]!;
      assert.ok(
        timeOf(record.recorded_at) > timeOf(previous.recorded_at),
        `records ${index} and ${index + 1} have the same or decreasing recorded_at: ` +
          `${label(previous)} ${previous.recorded_at} then ${label(record)} ${record.recorded_at}`,
      );
    }
    // No record was uploaded twice (not even a retried one), across everything the server received.
    assertions.noDuplicates(office.records().map((stored) => stored.record));

    await waitForService(ctx, false, 'after the last stop()');
    const state = await ctx.commands.state();
    assert.equal(state.enabled, false, `state after the loop reports enabled=true: ${JSON.stringify(state)}`);
    await ctx.crashes.assertNoFgsDidNotStartInTime();
  },
);

// ---------------------------------------------------------------------------------------------------------------------
// P-L02

/**
 * Seconds between launching the `start` broadcast (in the background of the device shell) and `am force-stop`.
 * `am` is a shell wrapper around `cmd activity`; how long it takes to deliver a broadcast varies per image, so the
 * values are spread from "before the start arrives" through "while start() runs and the service is being created" to
 * "after start() completed". Each round logs which of these it hit (see [classifyP02]); the invariants hold for all.
 */
const P_L02_FORCE_STOP_AFTER_S = ['0.02', '0.05', '0.08', '0.12', '0.2', '0.35', '0.6', '1'] as const;

/**
 * What a P-L02 round hit, from the start command's response line and the plugin's "foreground service start sent"
 * line (LT.ServiceController, right after startForegroundService; the system's `am_create_service` event, used first,
 * was not in the logcat of any CI image).
 */
function classifyP02(response: RawResponse | undefined, startSent: boolean): string {
  if (response?.ok) return 'force-stop after start() completed';
  if (response) return 'start delivered after the force-stop (new background process)';
  if (startSent) return 'force-stop while start() ran, after the foreground service start was sent';
  return 'force-stop before the start sent the foreground service start';
}

scenario(
  'P-L02',
  'start, then force-stop while the service is starting: no crash, clean state on relaunch',
  async (ctx) => {
    await ctx.app.prepare({ launch: true });
    const office = await ctx.backOffice();
    const config = await ctx.testConfig();
    await readyClean(ctx, config);
    const outcomes: string[] = [];

    for (const [index, delayS] of P_L02_FORCE_STOP_AFTER_S.entries()) {
      const round = index + 1;
      const roundDevice = await deviceNow(ctx);
      const startId = rawCommandId(`l02r${round}`);
      // One device-side sequence, so the host's adb latency does not blur the timing: the start broadcast runs in the
      // background of the shell, `am force-stop` follows after delayS; `wait` returns when the broadcast completed.
      const shellOutput = await ctx.adb.shell(
        `${e2eBroadcastLine(ctx.appId, startId, 'start', {})} >/dev/null 2>&1 & sleep ${delayS}; ` +
          `am force-stop ${ctx.appId}; wait`,
        { timeoutMs: 90_000 },
      );
      if (shellOutput.trim()) ctx.log(`round ${round} shell output: ${shellOutput.trim()}`);
      const roundLogSince = new Date(roundDevice - 1_000);
      const startResponse = await rawResponseNow(ctx, roundLogSince, startId);
      const startSent = parseLogcat(await ctx.adb.logcat.dump({ since: roundLogSince, buffers: ['main'] })).some(
        (l) => l.tag === 'LT.ServiceController' && l.message.includes('foreground service start sent'),
      );
      // A start broadcast still queued at the force-stop may have started a new (background) process afterwards.
      const pidBeforeLaunch = await ctx.app.pid();

      // The user opens the app again; the app calls ready() at startup (through the JS API).
      await ctx.app.launch();
      const relaunchDevice = await deviceNow(ctx);
      const driver = await ctx.app.webView();
      const state = await driver.callPlugin<StateJson>(PLUGIN, 'ready', { config, reset: true });
      // Upload whatever the killed process left queued, so the server has the whole audit trail of the round.
      await ctx.commands.sync();

      if (state.enabled) {
        await waitForService(ctx, true, `round ${round} (force-stop after ${delayS} s): ready() returned enabled=true`);
        if (pidBeforeLaunch === null) {
          // No process survived: tracking was persisted as enabled, so ready() must have restored it.
          await waitForRecord(ctx, office, audit('tracking_start', 'restore'), {
            afterDeviceMs: relaunchDevice,
            timeoutMs: 30_000,
            what: `round ${round}: tracking_start with reason restore after ready() returned enabled=true`,
          });
        }
        await sleep(3_000, ctx.signal);
        // At most one start after the relaunch, and only a restore (a process that already tracks is not restored).
        const startsAfter = recordsAfter(office, relaunchDevice).filter((r) => r.event === 'tracking_start');
        assert.ok(
          startsAfter.length <= 1 && startsAfter.every((r) => r.reason === 'restore'),
          `round ${round}: expected at most one tracking_start(restore) after the relaunch, got:\n${timeline(startsAfter)}`,
        );
      } else {
        await waitForService(ctx, false, `round ${round} (force-stop after ${delayS} s): ready() returned enabled=false`);
        await sleep(3_000, ctx.signal);
        assertCount(
          recordsAfter(office, relaunchDevice),
          (r) => r.event === 'tracking_start',
          0,
          `round ${round}: ready() returned enabled=false, so no tracking_start may follow the relaunch`,
        );
      }

      // The audit trail agrees with the state: its last tracking record is a tracking_start exactly when enabled.
      const trail = recordsAfter(office, roundDevice).filter(isTrackingRecord);
      const last = trail[trail.length - 1];
      if (last) {
        assert.equal(
          last.event === 'tracking_start',
          state.enabled,
          `round ${round}: the last tracking record is ${label(last)} but ready() returned enabled=${state.enabled}. ` +
            `The server would show the wrong online state. Trail of the round:\n${timeline(trail)}`,
        );
      } else {
        assert.equal(state.enabled, false, `round ${round}: enabled=true without any tracking_start record`);
      }
      outcomes.push(
        `round ${round}: force-stop after ${delayS} s -> ${classifyP02(startResponse, startSent)}; ` +
          `start command: ${describeResponse(startResponse)}, process before relaunch: ${pidBeforeLaunch ?? 'none'}, ` +
          `trail [${trail.map(label).join(', ')}], enabled after ready()=${state.enabled}`,
      );

      // Next round starts from "tracking off".
      if (state.enabled) {
        const stopped = await ctx.commands.stop();
        assert.equal(stopped.enabled, false, `round ${round}: stop() resolved with enabled=true`);
        await waitForService(ctx, false, `round ${round}: after the clean-up stop()`);
      }
    }
    for (const outcome of outcomes) ctx.log(outcome);
    await ctx.crashes.assertNoFgsDidNotStartInTime();
  },
  { timeoutMs: 15 * 60_000 },
);

// ---------------------------------------------------------------------------------------------------------------------
// P-L03

scenario(
  'P-L03',
  'kill -9 while tracking: START_STICKY/alarm restore records tracking_start reason restore',
  async (ctx) => {
    // Battery-exempt: Android 12+ lets an exempt app start its foreground service from the background, which both
    // restore paths (START_STICKY restart, heartbeat alarm) need.
    await ctx.app.prepare({ launch: true, batteryExempt: true });
    const office = await ctx.backOffice();
    const config = await ctx.testConfig();
    await readyAndStart(ctx, office, config);
    await ctx.adb.keyHome();
    await sleep(3_000, ctx.signal);

    const pidBefore = await ctx.app.pid();
    assert.notEqual(pidBefore, null, 'the app has no process while tracking');
    const killDevice = await deviceNow(ctx);
    await ctx.adb.killHard(ctx.appId);
    ctx.log(`kill -9 of pid ${pidBefore}`);

    let pidAfter: number;
    try {
      pidAfter = await waitUntil(
        async () => {
          const pid = await ctx.app.pid();
          return pid !== null && pid !== pidBefore ? pid : undefined;
        },
        { timeoutMs: (MAX_S + TOLERANCE_S + 60) * 1000, intervalMs: 1_000, signal: ctx.signal },
      );
    } catch (error) {
      throw new Error(
        `no new process of ${ctx.appId} within ${MAX_S + TOLERANCE_S + 60} s after kill -9: neither START_STICKY nor ` +
          `the heartbeat alarm restored it. Plugin log:\n${await pluginLog(ctx, new Date(killDevice - 1_000))}`,
        { cause: error },
      );
    }
    const restore = await waitForRecord(ctx, office, audit('tracking_start', 'restore'), {
      afterDeviceMs: killDevice,
      timeoutMs: (MAX_S + TOLERANCE_S + 60) * 1000,
      what: 'tracking_start with reason restore after kill -9',
    });
    const lastBefore = receivedRecords(office, (at) => at <= killDevice).at(-1);
    if (lastBefore) {
      const afterLastS = (timeOf(restore.recorded_at) - timeOf(lastBefore.recorded_at)) / 1000;
      const path = afterLastS < MIN_S - 1 ? 'START_STICKY restart' : 'heartbeat alarm (or START_STICKY after it)';
      ctx.log(`restored in pid ${pidAfter} ${afterLastS.toFixed(1)} s after the last record before the kill: ${path}`);
    }
    await waitForService(ctx, true, 'after the restore');

    // Heartbeats resume on the normal cadence.
    await waitForHeartbeats(ctx, office, timeOf(restore.recorded_at), 2, 'heartbeats after the restore');
    const sinceRestore = recordsAfter(office, timeOf(restore.recorded_at));
    assertions.heartbeatCadence(sinceRestore, { minIntervalS: MIN_S, maxIntervalS: MAX_S, toleranceS: TOLERANCE_S });

    const afterKill = recordsAfter(office, killDevice);
    assertCount(afterKill, (r) => r.event === 'tracking_start', 1, 'tracking_start records after kill -9 (one restore)');
    assertCount(afterKill, (r) => r.event === 'tracking_stop', 0, 'tracking_stop records after kill -9');
    const state = await ctx.commands.state();
    assert.equal(state.enabled, true, `state after the restore reports enabled=false: ${JSON.stringify(state)}`);
    await ctx.crashes.assertNoFgsDidNotStartInTime();
  },
  { requires: { root: true }, timeoutMs: 15 * 60_000 },
);

// ---------------------------------------------------------------------------------------------------------------------
// P-L04

scenario(
  'P-L04',
  'force-stop while tracking, relaunch: ready() restores tracking',
  async (ctx) => {
    await ctx.app.prepare({ launch: true });
    const office = await ctx.backOffice();
    const config = await ctx.testConfig();
    const { startDevice } = await readyAndStart(ctx, office, config);
    await sleep(3_000, ctx.signal);

    const pidBefore = await ctx.app.pid();
    await ctx.adb.forceStop(ctx.appId);
    // The stopped process is gone (a broadcast may already have started a new one, see below).
    await waitUntil(async () => (await ctx.app.pid()) !== pidBefore, {
      timeoutMs: 15_000,
      intervalMs: 250,
      message: `process ${pidBefore} still running after am force-stop`,
      signal: ctx.signal,
    });
    // Taken once the process is gone: a record created during the force-stop itself is older than this boundary.
    const forceStopDevice = await deviceNow(ctx);

    // Force-stop cancels the app's alarms, so no heartbeat alarm restarts tracking. A Play services activity update
    // that was already on its way can still start the process (CI emulator, API 34: 0.3 s after the force stop); in
    // that process the plugin must not restart tracking. Past the time the next heartbeat was due: no tracking service
    // and nothing recorded (the audit trail shows a gap that only the restore explains).
    const quietUntil = forceStopDevice + (MIN_S + 20) * 1000;
    let cameBack: number | null = null;
    while ((await deviceNow(ctx)) < quietUntil) {
      const pid = await ctx.app.pid();
      if (pid !== null && cameBack === null) cameBack = pid;
      assert.equal(
        await ctx.app.isForegroundServiceRunning(SERVICES.tracking),
        false,
        `the tracking service runs after am force-stop, before the app was opened again (pid ${pid})`,
      );
      await sleep(3_000, ctx.signal);
    }
    if (cameBack !== null) {
      const lines = (await pluginLog(ctx, new Date(forceStopDevice - 1_000)))
        .split('\n')
        .filter((line) => /force stop|restor/i.test(line));
      ctx.log(`the process came back (pid ${cameBack}) after am force-stop:\n${lines.join('\n') || '(no plugin log lines)'}`);
    }
    const whileStopped = recordsAfter(office, forceStopDevice);
    assert.equal(
      whileStopped.length,
      0,
      `records were created after am force-stop, before the app was opened again:\n${timeline(whileStopped)}`,
    );

    // The user opens the app; the app calls ready() at startup.
    await ctx.app.launch();
    const relaunchDevice = await deviceNow(ctx);
    const driver = await ctx.app.webView();
    const state = await driver.callPlugin<StateJson>(PLUGIN, 'ready', { config, reset: true });
    assert.equal(state.enabled, true, `ready() after the relaunch returned enabled=false: ${JSON.stringify(state)}`);
    await waitForRecord(ctx, office, audit('tracking_start', 'restore'), {
      afterDeviceMs: relaunchDevice,
      timeoutMs: 30_000,
      what: 'tracking_start with reason restore after ready()',
    });
    await waitForService(ctx, true, 'after ready() restored tracking');
    await sleep(5_000, ctx.signal);

    const afterStop = recordsAfter(office, forceStopDevice);
    assertCount(afterStop, (r) => r.event === 'tracking_start', 1, 'tracking_start records after the force-stop');
    assertCount(afterStop, (r) => r.event === 'tracking_stop', 0, 'tracking_stop records after the force-stop');
    assertions.gapsExplained(recordsAfter(office, startDevice), { maxGapS: MAX_S + TOLERANCE_S });
    await ctx.crashes.assertNoFgsDidNotStartInTime();
  },
);

// ---------------------------------------------------------------------------------------------------------------------
// P-L05

/** Global set on the page before the font scale change; a recreated activity loads a page without it. */
const P_L05_MARKER = '__ltE2eP05OldPage';

scenario(
  'P-L05',
  'activity recreation (font scale): tracking keeps running, no duplicate JS events',
  async (ctx) => {
    await ctx.app.prepare({ launch: true });
    const office = await ctx.backOffice();
    // stopOnTerminate true: an activity recreation is not a termination, so even this setting must keep tracking.
    const config = await ctx.testConfig({ stopOnTerminate: true });
    const drivers: WebViewDriver[] = [];
    try {
      // Like the real app: the page subscribes, then calls ready() and start() through the JS API.
      const oldPage = await ctx.app.webView();
      await oldPage.captureEvents(PLUGIN, CAPTURED_EVENTS);
      await oldPage.callPlugin<StateJson>(PLUGIN, 'ready', { config, reset: true });
      const startDevice = await deviceNow(ctx);
      const started = await oldPage.callPlugin<StateJson>(PLUGIN, 'start');
      assert.equal(started.enabled, true, `JS start() resolved with enabled=false: ${JSON.stringify(started)}`);
      await waitForRecord(ctx, office, audit('tracking_start', 'start'), {
        afterDeviceMs: startDevice,
        timeoutMs: 30_000,
        what: 'tracking_start with reason start after JS start()',
      });
      await waitForService(ctx, true, 'after JS start()');
      const pidBefore = await ctx.app.pid();

      // Baseline on the old page: one motionchange event per record.
      const oldEvents: CapturedEvent[] = [];
      const [movingOld, stillOld] = await paceUpAndDown(ctx, office, 'before the recreation');
      await collectEventsUntil(
        ctx,
        oldPage,
        oldEvents,
        (events) =>
          countEvents(events, 'motionchange', movingOld.uuid) >= 1 && countEvents(events, 'motionchange', stillOld.uuid) >= 1,
        30_000,
        `motionchange events ${movingOld.uuid} and ${stillOld.uuid} on the page before the recreation`,
      );
      await sleep(2_000, ctx.signal);
      oldEvents.push(...(await oldPage.drainEvents()));
      assert.deepEqual(duplicateEventKeys(oldEvents), [], `duplicate JS events before the recreation:\n${describeEvents(oldEvents)}`);

      // Font scale change: Capacitor's activity does not handle fontScale itself, so Android recreates it (new
      // activity, new WebView, page loaded again) in the same process.
      await oldPage.evaluate(`window.${P_L05_MARKER} = true`);
      const recreateDevice = await deviceNow(ctx);
      await ctx.adb.setFontScale(1.3);
      const newPage = await connectRecreatedPage(ctx, P_L05_MARKER, 45_000);
      drivers.push(newPage);
      await newPage.captureEvents(PLUGIN, CAPTURED_EVENTS);
      // The new page calls ready() again, as the app does on every page load; tracking must still be on.
      const readyAgain = await newPage.callPlugin<StateJson>(PLUGIN, 'ready', { config, reset: true });
      assert.equal(readyAgain.enabled, true, `ready() on the recreated page returned enabled=false: ${JSON.stringify(readyAgain)}`);

      const newEvents: CapturedEvent[] = [];
      const captureDevice = await deviceNow(ctx);
      const [moving, still] = await paceUpAndDown(ctx, office, 'on the recreated page');
      const heartbeat = await waitForRecord(ctx, office, (r) => r.event === 'heartbeat', {
        afterDeviceMs: timeOf(still.recorded_at) + 1,
        timeoutMs: (MAX_S + TOLERANCE_S + 30) * 1000,
        what: 'a heartbeat after the recreation',
      });
      await collectEventsUntil(
        ctx,
        newPage,
        newEvents,
        (events) =>
          countEvents(events, 'motionchange', moving.uuid) >= 1 &&
          countEvents(events, 'motionchange', still.uuid) >= 1 &&
          countEvents(events, 'heartbeat', heartbeat.uuid) >= 1,
        60_000,
        `motionchange events ${moving.uuid}, ${still.uuid} and heartbeat event ${heartbeat.uuid} on the recreated page`,
      );
      // A duplicate delivery would arrive right after the first one; wait, then take everything. Records created in
      // the last 3 s before the final drain are left out of the per-record check below (their events may be in flight).
      await sleep(5_000, ctx.signal);
      const checkUntilDevice = (await deviceNow(ctx)) - 3_000;
      newEvents.push(...(await newPage.drainEvents()));

      assert.deepEqual(
        duplicateEventKeys(newEvents),
        [],
        `the recreated page received duplicate JS events (same event name and record uuid):\n${describeEvents(newEvents)}`,
      );
      // Every motionchange and heartbeat record created while the new page listened reached it exactly once.
      const listened = recordsAfter(office, captureDevice).filter(
        (r) =>
          (r.event === 'motionchange' || r.event === 'heartbeat') && timeOf(r.recorded_at) <= checkUntilDevice,
      );
      for (const record of listened) {
        const name = record.event === 'heartbeat' ? 'heartbeat' : 'motionchange';
        assert.equal(
          countEvents(newEvents, name, record.uuid),
          1,
          `record ${label(record)} ${record.uuid} (recorded ${record.recorded_at}) reached the recreated page's ` +
            `'${name}' listener ${countEvents(newEvents, name, record.uuid)} times; expected once. Events:\n` +
            describeEvents(newEvents),
        );
      }
      const disabled = newEvents.filter(
        (e) => e.name === 'enabledchange' && (e.payload as { enabled?: unknown } | null)?.enabled === false,
      );
      assert.equal(
        disabled.length,
        0,
        `enabledchange(enabled=false) events after the recreation (tracking must not stop):\n${describeEvents(disabled)}`,
      );

      // Tracking itself was never interrupted.
      assert.equal(await ctx.app.pid(), pidBefore, 'the process changed during an activity recreation');
      await waitForService(ctx, true, 'after the recreation');
      const sinceStart = recordsAfter(office, startDevice);
      assertCount(sinceStart, (r) => r.event === 'tracking_stop', 0, 'tracking_stop records since start()');
      assertCount(sinceStart, (r) => r.event === 'tracking_start', 1, 'tracking_start records since start() (no restore)');
      ctx.log(`activity recreated at ${iso(recreateDevice)}; ${newEvents.length} JS events on the new page`);
    } finally {
      for (const driver of drivers) await driver.close().catch(() => undefined);
      await ctx.adb.setFontScale(1).catch((error: unknown) => ctx.log(`font scale reset failed: ${String(error)}`));
    }
  },
);

// ---------------------------------------------------------------------------------------------------------------------
// P-L06

scenario(
  'P-L06',
  'POST_NOTIFICATIONS denied: service runs, records flow',
  async (ctx) => {
    // Every runtime permission except POST_NOTIFICATIONS (pm clear revoked it; it is not granted again).
    await ctx.app.prepare({
      launch: true,
      permissions: [PERMISSIONS.fine, PERMISSIONS.coarse, PERMISSIONS.background, PERMISSIONS.activity],
    });
    // Precondition. A permission missing from `dumpsys package` (undefined) is not granted either.
    const notificationsGranted = await runtimePermissionGranted(ctx, PERMISSIONS.notifications);
    assert.notEqual(
      notificationsGranted,
      true,
      `precondition: ${PERMISSIONS.notifications} must not be granted, but dumpsys package reports granted=true`,
    );
    const office = await ctx.backOffice();
    const config = await ctx.testConfig();
    const { startDevice } = await readyAndStart(ctx, office, config);
    const pid = await ctx.app.pid();

    // In the background the foreground service is what keeps tracking alive; its notification is not shown.
    await ctx.adb.keyHome();
    await waitForHeartbeats(ctx, office, startDevice, 2, 'heartbeats with POST_NOTIFICATIONS denied');
    const records = recordsAfter(office, startDevice);
    assertions.heartbeatCadence(records, { minIntervalS: MIN_S, maxIntervalS: MAX_S, toleranceS: TOLERANCE_S });
    assertCount(records, (r) => r.event === 'tracking_stop', 0, 'tracking_stop records with POST_NOTIFICATIONS denied');
    await waitForService(ctx, true, 'with POST_NOTIFICATIONS denied, in the background');
    assert.equal(await ctx.app.pid(), pid, 'the process was restarted while tracking with POST_NOTIFICATIONS denied');

    // Diagnostic only: whether Android lists a notification record for the app (hidden without the permission).
    const notifications = await ctx.adb.dumpsys('notification', ['--noredact']).catch(() => '');
    const appLines = notifications.split('\n').filter((line) => line.includes('NotificationRecord') && line.includes(ctx.appId));
    ctx.log(`dumpsys notification: ${appLines.length} NotificationRecord line(s) of ${ctx.appId}`);
    await ctx.crashes.assertNoFgsDidNotStartInTime();
  },
  { requires: { api: 33 }, timeoutMs: 12 * 60_000 },
);

// ---------------------------------------------------------------------------------------------------------------------
// P-L07

scenario(
  'P-L07',
  'app update (install -r) with startOnBoot true/false: tracking_start / tracking_stop package_replaced',
  async (ctx) => {
    if (!ctx.env.apk) {
      ctx.t.skip('E2E_APK is not set: P-L07 updates the app in place with adb install -r of that APK');
      return;
    }
    const office = await ctx.backOffice();
    for (const startOnBoot of [true, false]) {
      const phase = `startOnBoot ${startOnBoot}`;
      await ctx.app.prepare({ launch: true });
      // No heartbeat alarm and no activity update may restore tracking before MY_PACKAGE_REPLACED does.
      const config = await ctx.testConfig({ startOnBoot, heartbeat: QUIET_HEARTBEAT, patch: NO_ACTIVITY_UPDATES });
      await readyAndStart(ctx, office, config);
      const pidBefore = await ctx.app.pid();

      const updateDevice = await deviceNow(ctx);
      await ctx.app.reinstall();
      ctx.log(`${phase}: install -r done`);

      if (startOnBoot) {
        await waitForRecord(ctx, office, audit('tracking_start', 'package_replaced'), {
          afterDeviceMs: updateDevice,
          timeoutMs: 120_000,
          what: `${phase}: tracking_start with reason package_replaced after the update`,
        });
        await waitForService(ctx, true, `${phase}: after the update`);
        await sleep(5_000, ctx.signal);
        const after = recordsAfter(office, updateDevice);
        assertCount(after, (r) => r.event === 'tracking_start', 1, `${phase}: tracking_start records after the update`);
        assertCount(after, (r) => r.event === 'tracking_stop', 0, `${phase}: tracking_stop records after the update`);
        const state = await ctx.commands.state();
        assert.equal(state.enabled, true, `${phase}: state after the update reports enabled=false`);
        assert.notEqual(await ctx.app.pid(), pidBefore, `${phase}: the process survived install -r (no update happened?)`);
      } else {
        await waitForRecord(ctx, office, audit('tracking_stop', 'package_replaced'), {
          afterDeviceMs: updateDevice,
          timeoutMs: 120_000,
          what: `${phase}: tracking_stop with reason package_replaced after the update`,
        });
        await sleep(10_000, ctx.signal);
        const after = recordsAfter(office, updateDevice);
        assertCount(after, (r) => r.event === 'tracking_start', 0, `${phase}: tracking_start records after the update`);
        assertCount(after, (r) => r.event === 'tracking_stop', 1, `${phase}: tracking_stop records after the update`);
        const state = await ctx.commands.state();
        assert.equal(state.enabled, false, `${phase}: state after the update reports enabled=true`);
        await waitForService(ctx, false, `${phase}: after the update`);
      }
    }
    await ctx.crashes.assertNoFgsDidNotStartInTime();
  },
  // Two phases, each with a clean install, an APK install and up to 120 s for the package-replaced record.
  { timeoutMs: 15 * 60_000 },
);

// ---------------------------------------------------------------------------------------------------------------------
// P-L08 / P-L09

scenario(
  'P-L08',
  'reboot with startOnBoot true: tracking_start reason boot',
  async (ctx) => {
    await ctx.app.prepare({ launch: true });
    const office = await ctx.backOffice();
    const config = await ctx.testConfig({ startOnBoot: true });
    const { startDevice, startRecord } = await readyAndStart(ctx, office, config);

    const rebootDevice = await deviceNow(ctx);
    await ctx.adb.reboot({ timeoutMs: 8 * 60_000 });
    ctx.log('device rebooted');

    const boot = await waitForRecord(ctx, office, audit('tracking_start', 'boot'), {
      afterDeviceMs: rebootDevice,
      timeoutMs: 4 * 60_000,
      what: 'tracking_start with reason boot after the reboot',
    });
    if (startRecord.boot_count >= 0 && boot.boot_count >= 0) {
      assert.ok(
        boot.boot_count > startRecord.boot_count,
        `boot_count did not increase across the reboot: ${startRecord.boot_count} before, ${boot.boot_count} after`,
      );
    }
    await waitForService(ctx, true, 'after the boot restore', 60_000);

    await waitForHeartbeats(ctx, office, timeOf(boot.recorded_at), 2, 'heartbeats after the boot restore');
    assertions.heartbeatCadence(recordsAfter(office, timeOf(boot.recorded_at)), {
      minIntervalS: MIN_S,
      maxIntervalS: MAX_S,
      toleranceS: TOLERANCE_S,
    });
    const afterReboot = recordsAfter(office, rebootDevice);
    assertCount(afterReboot, (r) => r.event === 'tracking_start', 1, 'tracking_start records after the reboot');
    assertCount(afterReboot, (r) => r.event === 'tracking_stop', 0, 'tracking_stop records after the reboot');
    assertions.gapsExplained(recordsAfter(office, startDevice), { maxGapS: MAX_S + TOLERANCE_S });
    const state = await ctx.commands.state();
    assert.equal(state.enabled, true, `state after the boot restore reports enabled=false: ${JSON.stringify(state)}`);
    await ctx.crashes.assertNoFgsDidNotStartInTime();
  },
  { timeoutMs: 20 * 60_000 },
);

scenario(
  'P-L09',
  'reboot with startOnBoot false: tracking_stop reason reboot',
  async (ctx) => {
    await ctx.app.prepare({ launch: true });
    const office = await ctx.backOffice();
    const config = await ctx.testConfig({ startOnBoot: false });
    const { startRecord } = await readyAndStart(ctx, office, config);

    const rebootDevice = await deviceNow(ctx);
    await ctx.adb.reboot({ timeoutMs: 8 * 60_000 });
    ctx.log('device rebooted');

    const stop = await waitForRecord(ctx, office, audit('tracking_stop', 'reboot'), {
      afterDeviceMs: rebootDevice,
      timeoutMs: 4 * 60_000,
      what: 'tracking_stop with reason reboot after the reboot (startOnBoot false)',
    });
    if (startRecord.boot_count >= 0 && stop.boot_count >= 0) {
      assert.ok(
        stop.boot_count > startRecord.boot_count,
        `boot_count did not increase across the reboot: ${startRecord.boot_count} before, ${stop.boot_count} after`,
      );
    }
    // Nothing restarts tracking afterwards.
    await sleep(20_000, ctx.signal);
    const afterReboot = recordsAfter(office, rebootDevice);
    assertCount(afterReboot, (r) => r.event === 'tracking_start', 0, 'tracking_start records after the reboot');
    assertCount(afterReboot, (r) => r.event === 'tracking_stop', 1, 'tracking_stop records after the reboot');
    const state = await ctx.commands.state();
    assert.equal(state.enabled, false, `state after the reboot reports enabled=true: ${JSON.stringify(state)}`);
    await waitForService(ctx, false, 'after the reboot with startOnBoot false');
  },
  { timeoutMs: 15 * 60_000 },
);

// ---------------------------------------------------------------------------------------------------------------------
// P-L10

/** How long nothing may happen after the fake boot broadcasts. */
const P_L10_QUIET_MS = 20_000;

scenario(
  'P-L10',
  'fake QUICKBOOT_POWERON broadcast without a real boot is ignored',
  async (ctx) => {
    const office = await ctx.backOffice();
    const component = `${ctx.appId}/${BOOT_RECEIVER}`;
    const sendFakeBoots = async (phase: string): Promise<void> => {
      for (const action of FAKE_BOOT_ACTIONS) {
        const output = await ctx.adb.broadcast(action, { component });
        ctx.log(`${phase}: ${action} -> ${output.trim().split('\n').at(-1) ?? ''}`);
      }
    };

    for (const startOnBoot of [true, false]) {
      const phase = `startOnBoot ${startOnBoot}`;
      await ctx.app.prepare({ launch: true });
      const config = await ctx.testConfig({ startOnBoot });
      await readyAndStart(ctx, office, config);
      const pid = await ctx.app.pid();

      // 1. While tracking runs in this process: no second tracking_start, no stop, the service keeps running.
      const liveDevice = await deviceNow(ctx);
      await sendFakeBoots(`${phase}, live process`);
      await sleep(P_L10_QUIET_MS, ctx.signal);
      const afterLive = recordsAfter(office, liveDevice).filter(isTrackingRecord);
      assert.equal(
        afterLive.length,
        0,
        `${phase}: a fake QUICKBOOT_POWERON in the tracking process created tracking records:\n${timeline(afterLive)}`,
      );
      assert.equal(await ctx.app.pid(), pid, `${phase}: the process changed after the fake boot broadcast`);
      await waitForService(ctx, true, `${phase}: after the fake boot broadcast in the live process`);

      // 2. In a new process (tracking enabled, process gone): the broadcast is the first thing the process sees.
      //    Settings.Global.BOOT_COUNT did not change, so it must not be treated as a boot: no tracking_start(boot),
      //    no tracking_stop(reboot), no service start, enabled stays true.
      await ctx.adb.forceStop(ctx.appId);
      await ctx.app.waitForNoProcess({ timeoutMs: 15_000, signal: ctx.signal });
      const coldDevice = await deviceNow(ctx);
      await sendFakeBoots(`${phase}, new process`);
      await sleep(P_L10_QUIET_MS, ctx.signal);
      const afterCold = recordsAfter(office, coldDevice);
      assert.equal(
        afterCold.length,
        0,
        `${phase}: a fake QUICKBOOT_POWERON in a new process created records (expected none; tracking_start(boot) ` +
          `or tracking_stop(reboot) means the boot-count gate is missing):\n${timeline(afterCold)}`,
      );
      await waitForService(ctx, false, `${phase}: after the fake boot broadcast in a new process`, 10_000);
      const state = await ctx.commands.state();
      assert.equal(
        state.enabled,
        true,
        `${phase}: the fake boot broadcast changed the persisted enabled flag to false: ${JSON.stringify(state)}`,
      );
      assert.ok(
        state.lastRecordAt === null || timeOf(state.lastRecordAt) < coldDevice,
        `${phase}: the device created a record at ${state.lastRecordAt} after the fake boot broadcast`,
      );
    }
  },
);

// ---------------------------------------------------------------------------------------------------------------------
// P-L11

scenario(
  'P-L11',
  'background start on Android 12+ via the debug receiver: service_start_failed unless temp-allowlisted',
  async (ctx) => {
    // Never launched in this scenario: the process only runs for the debug broadcasts (background), and the app is
    // not battery-exempt (prepare removes the exemption).
    await ctx.app.prepare({ launch: false });
    const office = await ctx.backOffice();
    const config = await ctx.testConfig();
    await ctx.commands.ready(config);

    // 1. Refused: Android 12+ does not allow a foreground-service start from the background without an exemption.
    const refusedDevice = await deviceNow(ctx);
    let refusal: E2eCommandError | undefined;
    let resolved: StateJson | undefined;
    try {
      resolved = await ctx.commands.start();
    } catch (error) {
      if (!(error instanceof E2eCommandError)) throw error;
      refusal = error;
    }
    if (refusal) {
      ctx.log(`background start() rejected: ${refusal.code}: ${refusal.message}`);
      // docs/architecture.md: start() rejects with PERMISSION_DENIED when the service cannot be started.
      assert.equal(refusal.code, 'PERMISSION_DENIED', `background start() rejected with ${refusal.code}: ${refusal.message}`);
      const stop = await waitForRecord(ctx, office, (r) => r.event === 'tracking_stop', {
        afterDeviceMs: refusedDevice,
        timeoutMs: 60_000,
        what: 'tracking_stop after the rejected background start()',
      });
      assert.ok(
        stop.reason === 'service_start_failed' || stop.reason === 'permission_denied',
        `the rejected background start recorded tracking_stop with reason ${stop.reason}; ` +
          `expected service_start_failed (or permission_denied, the documented reason when start() cannot start the service)`,
      );
    } else {
      ctx.log(`background start() resolved (enabled=${resolved?.enabled}); expecting an asynchronous service failure`);
      await waitForRecord(ctx, office, audit('tracking_stop', 'service_start_failed'), {
        afterDeviceMs: refusedDevice,
        timeoutMs: 60_000,
        what:
          'tracking_stop with reason service_start_failed: start() resolved from the background without an exemption, ' +
          'so the service must fail to enter the foreground (if Android allowed it, the precondition does not hold)',
      });
    }
    await sleep(3_000, ctx.signal);
    const refusedTrail = recordsAfter(office, refusedDevice).filter(isTrackingRecord);
    assert.equal(
      refusedTrail.at(-1)?.event,
      'tracking_stop',
      `after the refused start the last tracking record must be tracking_stop:\n${timeline(refusedTrail)}`,
    );
    const refusedState = await ctx.commands.state();
    assert.equal(refusedState.enabled, false, `state after the refused start reports enabled=true`);
    await waitForService(ctx, false, 'after the refused background start');

    // 2. Temporarily allowlisted (what a high-priority push or similar exemption gives): the start succeeds.
    await ctx.adb.deviceIdle.tempWhitelist(ctx.appId, 120_000);
    const allowedDevice = await deviceNow(ctx);
    const started = await ctx.commands.start();
    assert.equal(started.enabled, true, `start() after deviceidle tempwhitelist resolved with enabled=false`);
    await waitForRecord(ctx, office, audit('tracking_start', 'start'), {
      afterDeviceMs: allowedDevice,
      timeoutMs: 30_000,
      what: 'tracking_start with reason start after deviceidle tempwhitelist',
    });
    await waitForService(ctx, true, 'after the temp-allowlisted start');
    await sleep(10_000, ctx.signal);
    assertCount(
      recordsAfter(office, allowedDevice),
      (r) => r.event === 'tracking_stop',
      0,
      'tracking_stop records after the temp-allowlisted start',
    );
    await waitForService(ctx, true, '10 s after the temp-allowlisted start');
    await ctx.crashes.assertNoFgsDidNotStartInTime();
  },
  { requires: { api: 31 } },
);

// ---------------------------------------------------------------------------------------------------------------------
// P-L12

scenario(
  'P-L12',
  'heartbeat alarm restores a killed process',
  async (ctx) => {
    // Battery-exempt: exact heartbeat alarm, and Android 12+ allows the alarm receiver to start the service.
    await ctx.app.prepare({ launch: true, batteryExempt: true });
    const office = await ctx.backOffice();
    const config = await ctx.testConfig({ patch: NO_ACTIVITY_UPDATES });
    const { startDevice } = await readyAndStart(ctx, office, config);
    await ctx.adb.keyHome();

    const status = await ctx.commands.heartbeatStatus();
    ctx.log(`heartbeat strategy ${status.strategy}, battery exempt ${status.isIgnoringBatteryOptimizations}`);
    assert.ok(
      status.strategy === 'exact' || status.strategy === 'listener_with_backup',
      `heartbeat strategy is ${status.strategy}; P-L12 needs a PendingIntent alarm (exact or backup)`,
    );
    const first = await waitForRecord(ctx, office, (r) => r.event === 'heartbeat', {
      afterDeviceMs: startDevice,
      timeoutMs: (MAX_S + TOLERANCE_S + 30) * 1000,
      what: 'the first heartbeat before the kill',
    });

    // The window anchor is the device's own last record (a record may not have reached the server yet).
    const beforeKill = await ctx.commands.heartbeatStatus();
    const pidBefore = await ctx.app.pid();
    const killDevice = await deviceNow(ctx);
    // `am stopservice` (root) first, then kill -9 in the same shell. The service is no longer "started" when the
    // process dies, so Android schedules no START_STICKY restart (stopping it after the kill would race that restart);
    // the heartbeat PendingIntent alarm is then the only thing that can restore tracking.
    const killOutput = await ctx.adb.shell(
      `am stopservice -n ${ctx.appId}/${SERVICES.tracking}; for p in $(pidof ${ctx.appId}); do kill -9 $p; done`,
    );
    ctx.log(
      `am stopservice + kill -9 of pid ${pidBefore}: ${killOutput.trim().replace(/\n/g, ' | ')}; ` +
        `next heartbeat was due at ${beforeKill.nextHeartbeatAt}`,
    );
    const serverLast = receivedRecords(office, (at) => at <= killDevice).at(-1) ?? first;
    const anchorMs = Math.max(timeOf(serverLast.recorded_at), timeOf(beforeKill.lastRecordAt) || 0);
    const dueDevice = anchorMs + MIN_S * 1000;

    // Until shortly before the heartbeat is due nothing may bring the process back.
    while ((await deviceNow(ctx)) < dueDevice - 8_000) {
      const pid = await ctx.app.pid();
      if (pid !== null) {
        assert.fail(
          `the process came back (pid ${pid}) ${((await deviceNow(ctx)) - killDevice) / 1000} s after the kill, before ` +
            `the heartbeat alarm was due at ${iso(dueDevice)}: something other than the alarm restored it. Plugin ` +
            `log:\n${await pluginLog(ctx, new Date(killDevice - 1_000))}`,
        );
      }
      await sleep(2_000, ctx.signal);
    }

    // The alarm fires: the receiver first creates the due heartbeat, then restores tracking (docs/heartbeat.md).
    const restore = await waitForRecord(ctx, office, audit('tracking_start', 'restore'), {
      afterDeviceMs: killDevice,
      timeoutMs: (MAX_S + TOLERANCE_S + 60) * 1000,
      what: 'tracking_start with reason restore from the heartbeat alarm',
    });
    await sleep(3_000, ctx.signal);
    const afterKill = recordsAfter(office, killDevice);
    const [alarmHeartbeat, restored] = afterKill;
    assert.ok(
      alarmHeartbeat !== undefined &&
        restored !== undefined &&
        alarmHeartbeat.event === 'heartbeat' &&
        restored.uuid === restore.uuid,
      `after the kill the first records must be the alarm's heartbeat, then tracking_start(restore). Got:\n` +
        timeline(afterKill),
    );
    const heartbeatGapS = (timeOf(alarmHeartbeat.recorded_at) - anchorMs) / 1000;
    assert.ok(
      heartbeatGapS >= MIN_S - 1.5 && heartbeatGapS <= MAX_S + TOLERANCE_S + 30,
      `the alarm's heartbeat came ${heartbeatGapS.toFixed(1)} s after the last record before the kill; expected ` +
        `${MIN_S - 1.5}..${MAX_S + TOLERANCE_S + 30} s (minInterval ${MIN_S}, maxInterval ${MAX_S})`,
    );
    const restoreDelayS = (timeOf(restore.recorded_at) - timeOf(alarmHeartbeat.recorded_at)) / 1000;
    assert.ok(restoreDelayS <= 20, `tracking_start(restore) came ${restoreDelayS.toFixed(1)} s after the alarm's heartbeat`);
    await waitForService(ctx, true, 'after the alarm restore');
    const pidAfter = await ctx.app.pid();
    assert.ok(pidAfter !== null && pidAfter !== pidBefore, `expected a new process after the restore, got pid ${pidAfter}`);

    // Heartbeats resume in the restored process.
    await waitForRecord(ctx, office, (r) => r.event === 'heartbeat', {
      afterDeviceMs: timeOf(restore.recorded_at) + 1,
      timeoutMs: (MAX_S + TOLERANCE_S + 30) * 1000,
      what: 'a heartbeat after the alarm restore',
    });
    const final = recordsAfter(office, killDevice);
    assertCount(final, (r) => r.event === 'tracking_start', 1, 'tracking_start records after the kill');
    assertCount(final, (r) => r.event === 'tracking_stop', 0, 'tracking_stop records after the kill');
    await ctx.crashes.assertNoFgsDidNotStartInTime();
  },
  { requires: { root: true }, timeoutMs: 15 * 60_000 },
);

// ---------------------------------------------------------------------------------------------------------------------
// P-L13

const P_L13_BLOCK_MS = 4_000;
/** The plugin's start() is called this long after the block began, so the rest of the block delays the service. */
const P_L13_START_AFTER_MS = 100;
const P_L13_ATTEMPTS = 3;

scenario(
  'P-L13',
  'busy main thread (4000 ms) around a cold service start: no ForegroundServiceDidNotStartInTimeException',
  async (ctx) => {
    await ctx.app.prepare({ launch: true });
    const office = await ctx.backOffice();
    const config = await ctx.testConfig();
    const loopDevice = await deviceNow(ctx);
    await readyClean(ctx, config);

    // The debug command blocks the main thread and calls start() from a background thread during the block. Android
    // posts the service creation to the blocked main thread, so onCreate and startForeground() wait for the rest of
    // the block while Android's startForeground deadline already runs: the busy main thread behind
    // ForegroundServiceDidNotStartInTimeException, on every attempt. (The first version raced a block sent from the
    // shell against the start and never hit the few-ms window; its signal, the `am_create_service` event, was not in
    // the logcat of any CI image either.) The plugin's own lines show the timing: "foreground service start sent"
    // (LT.ServiceController, engine thread, where the deadline starts) and "created" (LT.Service, main thread).
    for (let attempt = 1; attempt <= P_L13_ATTEMPTS; attempt++) {
      await waitForService(ctx, false, `attempt ${attempt}: before the start`);
      const logSince = new Date((await deviceNow(ctx)) - 1_000);
      const result = await ctx.commands.startDuringMainThreadBlock(P_L13_BLOCK_MS, P_L13_START_AFTER_MS);
      assert.equal(result.state.enabled, true, `attempt ${attempt}: start() during the block: ${JSON.stringify(result)}`);
      await waitForService(ctx, true, `attempt ${attempt}: after the start (block ${P_L13_BLOCK_MS} ms)`);

      const lines = parseLogcat(await ctx.adb.logcat.dump({ since: logSince, buffers: ['main', 'events'] }));
      const sent = lines.find((l) => l.tag === 'LT.ServiceController' && l.message.includes('foreground service start sent'));
      const created = sent
        ? lines.find((l) => l.tag === 'LT.Service' && l.message.includes('created') && l.timeMs >= sent.timeMs)
        : undefined;
      if (!sent || !created) {
        assert.fail(
          `attempt ${attempt}: the plugin lines "foreground service start sent" and "created" were not both found:\n` +
            (await pluginLog(ctx, logSince)),
        );
      }
      const foreground = lines.find(
        (l) => l.tag === 'am_foreground_service_start' && l.message.includes('LocationTrackingService') && l.timeMs >= created.timeMs,
      );
      const waitedMs = created.timeMs - sent.timeMs;
      ctx.log(
        `attempt ${attempt}: start() called ${result.startCalledAfterMs} ms into the ${P_L13_BLOCK_MS} ms block; ` +
          `service created ${waitedMs} ms after the start was sent` +
          (foreground ? `; startForeground ${foreground.timeMs - created.timeMs} ms after onCreate (am_foreground_service_start)` : ''),
      );
      // The main thread was blocked from before the start was sent until the block's end: the creation waited for it.
      const expectedWaitMs = P_L13_BLOCK_MS - result.startCalledAfterMs;
      assert.ok(
        waitedMs >= expectedWaitMs / 2,
        `attempt ${attempt}: the service was created ${waitedMs} ms after the start was sent; the blocked main thread ` +
          `should have delayed it by about ${expectedWaitMs} ms, so this attempt did not test a busy main thread`,
      );

      const stopped = await ctx.commands.stop();
      assert.equal(stopped.enabled, false, `attempt ${attempt}: stop() resolved with enabled=true`);
      await waitForService(ctx, false, `attempt ${attempt}: after stop()`);
    }

    // The point of the scenario: a start delayed by a busy main thread did not crash the app.
    await ctx.crashes.assertNoFgsDidNotStartInTime();
    await office.waitFor((o) => recordsAfter(o, loopDevice).filter(isTrackingRecord).length >= 2 * P_L13_ATTEMPTS, {
      timeoutMs: 60_000,
      intervalMs: 1_000,
      signal: ctx.signal,
    }).catch(() => undefined);
    const trail = recordsAfter(office, loopDevice).filter(isTrackingRecord);
    assertCount(trail, audit('tracking_start', 'start'), P_L13_ATTEMPTS, 'tracking_start(start) records (one per attempt)');
    assertCount(trail, audit('tracking_stop', 'stop'), P_L13_ATTEMPTS, 'tracking_stop(stop) records (one per attempt)');
    assertCount(trail, (r) => r.reason === 'service_start_failed', 0, 'records with reason service_start_failed');
  },
);
