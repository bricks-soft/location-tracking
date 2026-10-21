// Plugin suite D: round-3 behavior (docs/DECISIONS.md, "Round 3"; docs/e2e/architecture.md §9 D).
//
// Same setup as the other plugin suites: the plugin's example app in e2e mode, driven through the debug receiver,
// the WebView (JS API and events) and adb, checked against what the mock back office receives. Record times
// (`recorded_at`) are device wall-clock times, so every "after" boundary is a device time (`adb.deviceTime()`).
// Items that need a real phone are manual procedures in docs/e2e-runbook.md §8.
import assert from 'node:assert/strict';
import {
  PERMISSIONS,
  PLACES,
  SERVICES,
  TEST_HEARTBEAT,
  scenario,
  sleep,
  waitUntil,
  type MockBackOffice,
  type RequestLogEntry,
  type ScenarioContext,
  type StateJson,
  type WebViewDriver,
  type WireRecord,
} from '@bricks-soft/e2e-kit';

// ---------------------------------------------------------------------------------------------------------------------
// Constants

const PLUGIN = 'LocationTracking';

/** `JobUploadScheduler.UPLOAD_JOB_ID` (0x4C540001). */
const UPLOAD_JOB_ID = 1_280_573_441;

/** Slack for alarm delivery, uploads and adb latency (seconds). */
const TOLERANCE_S = 30;

/** Runtime permissions with "while in use" location only (no ACCESS_BACKGROUND_LOCATION). */
const WHILE_IN_USE = [PERMISSIONS.coarse, PERMISSIONS.fine, PERMISSIONS.activity, PERMISSIONS.notifications] as const;

/** Texts of the resume notification in P-R01 (found on screen with uiautomator). */
const RESUME = { title: 'E2E tracking paused', text: 'E2E tap to resume tracking' } as const;

/** No activity PendingIntent can wake a killed process: the heartbeat alarm is the only restore path. */
const NO_ACTIVITY_UPDATES = { activity: { disableMotionActivityUpdates: true } } as const;

// ---------------------------------------------------------------------------------------------------------------------
// Record helpers

function timeOf(iso: string | null | undefined): number {
  return iso ? Date.parse(iso) : Number.NaN;
}

function iso(ms: number): string {
  return Number.isFinite(ms) ? new Date(ms).toISOString() : String(ms);
}

function label(record: WireRecord): string {
  if (record.reason !== undefined) return `${record.event}(${record.reason})`;
  if (record.event === 'motionchange') return `motionchange(is_moving=${record.is_moving})`;
  return record.event;
}

function timeline(records: readonly WireRecord[], limit = 50): string {
  if (records.length === 0) return '  (none)';
  const shown = records.slice(-limit);
  const lines = shown.map((r) => `  ${r.recorded_at}  ${label(r).padEnd(34)} uuid=${r.uuid.slice(0, 8)}`);
  if (shown.length < records.length) lines.unshift(`  ... ${records.length - shown.length} earlier record(s)`);
  return lines.join('\n');
}

/** Unique received records with `recorded_at >= afterDeviceMs`, ordered by `recorded_at`. */
function recordsAfter(office: MockBackOffice, afterDeviceMs: number): WireRecord[] {
  return office
    .records({ unique: true })
    .map((stored) => stored.record)
    .filter((record) => timeOf(record.recorded_at) >= afterDeviceMs)
    .sort((a, b) => timeOf(a.recorded_at) - timeOf(b.recorded_at));
}

function audit(event: 'tracking_start' | 'tracking_stop', reason?: string): (record: WireRecord) => boolean {
  return (record) => record.event === event && (reason === undefined || record.reason === reason);
}

async function waitForRecord(
  ctx: ScenarioContext,
  office: MockBackOffice,
  match: (record: WireRecord) => boolean,
  afterDeviceMs: number,
  timeoutMs: number,
  what: string,
): Promise<WireRecord> {
  try {
    return await office.waitFor((o) => recordsAfter(o, afterDeviceMs).find(match), {
      timeoutMs,
      intervalMs: 1_000,
      message: what,
      signal: ctx.signal,
    });
  } catch (error) {
    throw new Error(
      `expected ${what} (recorded at or after ${iso(afterDeviceMs)}) within ${timeoutMs / 1000} s. Records since then:\n` +
        timeline(recordsAfter(office, afterDeviceMs)),
      { cause: error },
    );
  }
}

/** `/locations` requests received since [sinceHost] (host ms), oldest first. */
function uploads(office: MockBackOffice, sinceHost: number): RequestLogEntry[] {
  return office.requests({ path: '/locations', since: sinceHost }).sort((a, b) => a.id - b.id);
}

/** Records in one logged request body (single or batch). */
function bodyRecords(entry: RequestLogEntry): WireRecord[] {
  try {
    const body = JSON.parse(entry.body) as Record<string, unknown>;
    const value = body['location'];
    if (Array.isArray(value)) return value as WireRecord[];
    return value && typeof value === 'object' ? [value as WireRecord] : [];
  } catch {
    return [];
  }
}

function requestLog(entries: readonly RequestLogEntry[]): string {
  if (entries.length === 0) return '  (no requests)';
  return entries
    .map((e) => {
      const records = bodyRecords(e);
      const auth = e.headers['authorization'] ?? '-';
      return `  #${e.id} ${iso(e.receivedAt)} ${e.method} ${e.path} -> ${e.status}${e.fault ? ` (${e.fault})` : ''} ` +
        `records=${records.length} [${records.map((r) => r.uuid.slice(0, 8)).join(',')}] auth=${auth}`;
    })
    .join('\n');
}

// ---------------------------------------------------------------------------------------------------------------------
// Device helpers

async function waitForService(ctx: ScenarioContext, running: boolean, what: string, timeoutMs = 30_000): Promise<void> {
  await waitUntil(async () => (await ctx.app.isForegroundServiceRunning(SERVICES.tracking)) === running, {
    timeoutMs,
    intervalMs: 1_000,
    message: `${what}: the tracking foreground service ${running ? 'running' : 'stopped'}`,
    signal: ctx.signal,
  });
}

/** `ready(config)` on a cleared app, then `start()`; returns the device time before the start and its record. */
async function readyAndStart(
  ctx: ScenarioContext,
  office: MockBackOffice,
  config: Record<string, unknown>,
): Promise<{ startDevice: number; startRecord: WireRecord }> {
  const ready = await ctx.commands.ready(config);
  assert.equal(ready.enabled, false, `ready() after a clean install reported enabled=true: ${JSON.stringify(ready)}`);
  const startDevice = await ctx.adb.deviceTime();
  const started = await ctx.commands.start();
  assert.equal(started.enabled, true, `start() resolved with enabled=false: ${JSON.stringify(started)}`);
  const startRecord = await waitForRecord(ctx, office, audit('tracking_start', 'start'), startDevice, 30_000, 'tracking_start(start)');
  await waitForService(ctx, true, 'after start()');
  return { startDevice, startRecord };
}

async function defaultNetworkState(ctx: ScenarioContext): Promise<'none' | 'up' | 'unknown'> {
  const out = await ctx.adb.dumpsys('connectivity');
  const m = /Active default network:\s*(\S+)/.exec(out);
  if (!m) return 'unknown';
  return m[1] === 'none' ? 'none' : 'up';
}

/** Wi-Fi and mobile data off (`svc wifi disable`, `svc data disable`) until no default network is left. */
async function goOffline(ctx: ScenarioContext): Promise<void> {
  ctx.onTeardown(async () => {
    await ctx.adb.setWifi(true);
    await ctx.adb.setData(true);
  });
  await ctx.adb.setWifi(false);
  await ctx.adb.setData(false);
  await waitUntil(async () => (await defaultNetworkState(ctx)) !== 'up', {
    timeoutMs: 20_000,
    intervalMs: 1_000,
    message: 'no default network with Wi-Fi and data off',
    signal: ctx.signal,
  });
}

async function goOnline(ctx: ScenarioContext): Promise<void> {
  await ctx.adb.setWifi(true);
  await ctx.adb.setData(true);
  await waitUntil(async () => (await defaultNetworkState(ctx)) === 'up', {
    timeoutMs: 60_000,
    intervalMs: 1_000,
    message: 'a default network after Wi-Fi and data on',
    signal: ctx.signal,
  });
}

/** Inserts [count] normal records (`insertLocation`); returns their uuids. */
async function insertRecords(ctx: ScenarioContext, count: number, tag: string): Promise<string[]> {
  const uuids: string[] = [];
  for (let i = 0; i < count; i++) {
    uuids.push(
      await ctx.commands.insertLocation({
        coords: { latitude: PLACES.hq.lat + i * 0.0001, longitude: PLACES.hq.lon, accuracy: 8 },
        extras: { tag, index: i },
      }),
    );
  }
  return uuids;
}

/** The `dumpsys jobscheduler <app>` block of the upload job, or undefined when no such job is scheduled. */
async function uploadJob(ctx: ScenarioContext): Promise<string | undefined> {
  const dump = await ctx.adb.shell(`dumpsys jobscheduler ${ctx.appId}`);
  const lines = dump.split('\n');
  const start = lines.findIndex((line) => line.includes(`/${UPLOAD_JOB_ID}`) && /JOB #|JobStatus/.test(line));
  if (start < 0) return undefined;
  const block = [lines[start]!];
  const indent = lines[start]!.length - lines[start]!.trimStart().length;
  for (const line of lines.slice(start + 1)) {
    const lineIndent = line.length - line.trimStart().length;
    if (line.trim() !== '' && lineIndent <= indent) break;
    block.push(line);
  }
  return block.join('\n');
}

/** `Minimum latency: +30s100ms` / `Earliest run time: ...` of a job block, as ms; undefined when absent. */
function jobDelayMs(block: string): number | undefined {
  const m = /Minimum latency:\s*\+?((?:\d+d)?(?:\d+h)?(?:\d+m(?!s))?(?:\d+s)?(?:\d+ms)?)/.exec(block);
  if (!m || !m[1]) return undefined;
  let ms = 0;
  for (const [, value, unit] of m[1].matchAll(/(\d+)(d|h|ms|m|s)/g)) {
    const n = Number(value);
    ms += unit === 'd' ? n * 86_400_000 : unit === 'h' ? n * 3_600_000 : unit === 'm' ? n * 60_000 : unit === 's' ? n * 1000 : n;
  }
  return ms;
}

/** `Flags:` of the JobInfo dump is hex; `JobInfo.FLAG_EXPEDITED` is 0x10 (API 34: "Flags: 10", "Priority: 500 [MAX]"). */
function isExpedited(block: string): boolean {
  const flags = /^\s*Flags:\s*([0-9a-fA-F]+)\s*$/m.exec(block)?.[1];
  return flags !== undefined && (Number.parseInt(flags, 16) & 0x10) !== 0;
}

/** Center of the first uiautomator node whose text or content-desc contains [text], or undefined. */
function nodeCenter(xml: string, text: string): { x: number; y: number } | undefined {
  for (const node of xml.matchAll(/<node\b[^>]*>/g)) {
    const tag = node[0];
    const value = /\btext="([^"]*)"/.exec(tag)?.[1] ?? '';
    const desc = /\bcontent-desc="([^"]*)"/.exec(tag)?.[1] ?? '';
    if (!value.includes(text) && !desc.includes(text)) continue;
    const b = /\bbounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(tag);
    if (!b) continue;
    return { x: Math.round((Number(b[1]) + Number(b[3])) / 2), y: Math.round((Number(b[2]) + Number(b[4])) / 2) };
  }
  return undefined;
}

/** Opens the notification shade and taps the notification that shows [text] (uiautomator dump + input tap). */
async function tapNotification(ctx: ScenarioContext, text: string): Promise<void> {
  await ctx.adb.screenOn();
  await ctx.adb.shell('wm dismiss-keyguard').catch(() => '');
  const center = await waitUntil(
    async () => {
      await ctx.adb.shell('cmd statusbar expand-notifications');
      await sleep(1_500);
      await ctx.adb.shell('uiautomator dump /sdcard/e2e-ui.xml');
      return nodeCenter(await ctx.adb.shell('cat /sdcard/e2e-ui.xml'), text);
    },
    { timeoutMs: 30_000, intervalMs: 2_000, message: `a notification showing '${text}' in the shade`, signal: ctx.signal },
  );
  await ctx.adb.shell(`input tap ${center.x} ${center.y}`);
  ctx.log(`tapped the notification '${text}' at ${center.x},${center.y}`);
  await sleep(1_000);
  await ctx.adb.shell('cmd statusbar collapse').catch(() => '');
}

/** True if `dumpsys notification` lists a notification of the app on [channel]. */
async function hasNotification(ctx: ScenarioContext, channel: string): Promise<boolean> {
  const dump = await ctx.adb.dumpsys('notification', ['--noredact']).catch(() => '');
  return dump
    .split('NotificationRecord(')
    .some((record) => record.includes(`pkg=${ctx.appId}`) && record.includes(channel));
}

/** Plugin log lines (tags LT.*) since [since], for failure messages and diagnostics. */
async function pluginLog(ctx: ScenarioContext, since: Date, pattern?: RegExp, limit = 40): Promise<string> {
  try {
    const text = await ctx.adb.logcat.dump({ since, buffers: ['main'] });
    const lines = text.split('\n').filter((line) => /\sLT\.[A-Za-z]+\s*:/.test(line) && (!pattern || pattern.test(line)));
    return lines.slice(-limit).join('\n') || '(no matching plugin log lines)';
  } catch (error) {
    return `(logcat unavailable: ${String(error)})`;
  }
}

/** The app's WebView with the plugin's [events] captured. */
async function captureEvents(ctx: ScenarioContext, events: readonly string[]): Promise<WebViewDriver> {
  const web = await ctx.app.webView();
  await web.captureEvents(PLUGIN, events);
  return web;
}

/** The wake locks held now: the `Wake Locks: size=N` section of `dumpsys power` (the output also has a history). */
function heldWakeLocks(power: string): string[] {
  const lines = power.split('\n');
  const start = lines.findIndex((line) => /^\s*Wake Locks: size=/.test(line));
  if (start < 0) return [];
  const held: string[] = [];
  for (const line of lines.slice(start + 1)) {
    if (line.trim() === '') break;
    held.push(line.trim());
  }
  return held;
}

/** Pending alarms of the app in `dumpsys alarm` (one line per alarm). */
async function appAlarms(ctx: ScenarioContext): Promise<string[]> {
  const dump = await ctx.adb.dumpsys('alarm');
  return dump.split('\n').filter((line) => line.includes(ctx.appId) && /(RTC|ELAPSED)/.test(line));
}

/** The `device_id` the plugin should report: the app's `Settings.Secure.ANDROID_ID` (SSAID of its signing key). */
async function expectedDeviceId(ctx: ScenarioContext): Promise<{ shell: string; ssaid: string | undefined }> {
  const shell = (await ctx.adb.shell('settings get secure android_id')).trim();
  let ssaid: string | undefined;
  if (await ctx.adb.isRoot()) {
    const xml = await ctx.adb
      .shell('abx2xml /data/system/users/0/settings_ssaid.xml - 2>/dev/null || cat /data/system/users/0/settings_ssaid.xml')
      .catch(() => '');
    const entry = xml.split('<setting').find((s) => s.includes(`package="${ctx.appId}"`));
    ssaid = entry ? /\bvalue="([0-9a-f]+)"/.exec(entry)?.[1] : undefined;
  }
  return { shell, ssaid };
}

// =====================================================================================================================
// Scenarios

scenario(
  'P-R01',
  'Android 11-13, "while in use" only: boot and kill restores record service_start_failed and the resume notification; a tap resumes',
  async (ctx) => {
    // 5a (docs/DECISIONS.md R3.2): Android starts a background location service without background permission but
    // gives it no location below Android 14, so the plugin refuses such restores and offers the resume notification.
    const office = await ctx.backOffice();
    await ctx.app.prepare({ permissions: WHILE_IN_USE, batteryExempt: true, launch: true });
    const config = await ctx.testConfig({
      startOnBoot: true,
      patch: { ...NO_ACTIVITY_UPDATES, notification: { resume: { enabled: true, ...RESUME } } },
    });
    await readyAndStart(ctx, office, config);

    // 1. Reboot: BOOT_COMPLETED restores in the background.
    const beforeBoot = await ctx.adb.deviceTime();
    await ctx.adb.reboot();
    const bootStop = await waitForRecord(
      ctx, office, audit('tracking_stop', 'service_start_failed'), beforeBoot, 180_000,
      'tracking_stop(service_start_failed) after the reboot',
    );
    const afterBoot = recordsAfter(office, beforeBoot);
    assert.equal(afterBoot.filter(audit('tracking_start')).length, 0, `no tracking_start after the refused boot restore:\n${timeline(afterBoot)}`);
    assert.equal(await ctx.app.isForegroundServiceRunning(SERVICES.tracking), false, 'the service must not run after the refused restore');
    await waitUntil(() => hasNotification(ctx, 'location_tracking_resume'), {
      timeoutMs: 15_000, intervalMs: 1_000, message: 'the resume notification after the refused boot restore', signal: ctx.signal,
    });
    ctx.log(`boot: ${label(bootStop)} at ${bootStop.recorded_at}; resume notification posted`);

    // 2. A tap resumes the session.
    const beforeTap = await ctx.adb.deviceTime();
    await tapNotification(ctx, RESUME.text);
    await waitForRecord(ctx, office, audit('tracking_start', 'resume_notification'), beforeTap, 30_000, 'tracking_start(resume_notification) after the tap');
    await waitForService(ctx, true, 'after the tap on the resume notification');
    const state = await ctx.commands.state();
    assert.equal(state.enabled, true, `getState() after the tap: ${JSON.stringify(state)}`);

    // 3. Kill the process: the sticky restart or the next heartbeat alarm restores in the background and is refused.
    await ctx.adb.keyHome();
    const beforeKill = await ctx.adb.deviceTime();
    const since = new Date();
    if (await ctx.adb.isRoot()) await ctx.adb.killHard(ctx.appId);
    else await ctx.adb.amKill(ctx.appId);
    const killStop = await waitForRecord(
      ctx, office, audit('tracking_stop', 'service_start_failed'), beforeKill, (TEST_HEARTBEAT.maxInterval + TOLERANCE_S) * 1000 + 60_000,
      'tracking_stop(service_start_failed) after the kill (sticky restart or heartbeat alarm)',
    );
    const afterKill = recordsAfter(office, beforeKill);
    assert.equal(afterKill.filter((r) => r.event === 'heartbeat' || audit('tracking_start')(r)).length, 0,
      `no heartbeat or tracking_start after the kill:\n${timeline(afterKill)}`);
    await waitUntil(() => hasNotification(ctx, 'location_tracking_resume'), {
      timeoutMs: 15_000, intervalMs: 1_000, message: 'the resume notification after the refused restore', signal: ctx.signal,
    });
    ctx.log(`kill: ${label(killStop)} at ${killStop.recorded_at}; restore path: ${await pluginLog(ctx, since, /restore|refus|background/i, 6)}`);

    const beforeTap2 = await ctx.adb.deviceTime();
    await tapNotification(ctx, RESUME.text);
    await waitForRecord(ctx, office, audit('tracking_start', 'resume_notification'), beforeTap2, 30_000, 'tracking_start(resume_notification) after the second tap');
  },
  { requires: { root: true, api: { min: 30, max: 33 } }, timeoutMs: 15 * 60_000 },
);

scenario(
  'P-R02',
  'Android 10, "while in use" only: a boot restore still starts tracking and gets locations',
  async (ctx) => {
    // 5a keeps Android 10 allowed: there a location service started in the background still gets location.
    const office = await ctx.backOffice();
    await ctx.app.prepare({ permissions: WHILE_IN_USE, launch: true });
    const config = await ctx.testConfig({ startOnBoot: true });
    await ctx.adb.geoFix(PLACES.hq.lat, PLACES.hq.lon);
    await readyAndStart(ctx, office, config);

    const beforeBoot = await ctx.adb.deviceTime();
    await ctx.adb.reboot();
    await waitForRecord(ctx, office, audit('tracking_start', 'boot'), beforeBoot, 180_000, 'tracking_start(boot) after the reboot');
    const after = recordsAfter(office, beforeBoot);
    assert.equal(after.filter(audit('tracking_stop')).length, 0, `no tracking_stop after the boot restore on Android 10:\n${timeline(after)}`);
    await waitForService(ctx, true, 'after the boot restore', 60_000);

    // Movement after the boot: location records with the new position prove the service gets location.
    const beforeMove = await ctx.adb.deviceTime();
    await ctx.commands.changePace(true);
    const route = [PLACES.hq, PLACES.hqNorth1500m];
    const feeding = ctx.adb.playRoute(route, { speedMps: 15, intervalMs: 1_000, signal: ctx.signal }).catch(() => undefined);
    const moved = await waitForRecord(
      ctx, office,
      (r) => (r.event === 'location' || r.event === 'motionchange') && r.coords !== null && r.coords.latitude > PLACES.hq.lat + 0.001,
      beforeMove, 120_000, 'a location record north of HQ after the boot restore',
    );
    await feeding;
    ctx.log(`after the boot: ${label(moved)} at ${moved.coords?.latitude.toFixed(5)},${moved.coords?.longitude.toFixed(5)}`);
  },
  { requires: { api: { min: 29, max: 29 } }, timeoutMs: 12 * 60_000 },
);

scenario(
  'P-R03',
  'upload job: records queued offline with tracking off upload after the process died; expedited at once, delayed after a failure',
  async (ctx) => {
    // 10a-10c. The app is never opened after the kill: only the job can start the process and upload.
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: false });
    const api = await ctx.adb.apiLevel();
    const config = await ctx.testConfig({});
    const ready = await ctx.commands.ready(config);
    assert.equal(ready.enabled, false);

    // Part 1: offline, tracking off → a job that runs as soon as a network is there (expedited on Android 12+).
    await goOffline(ctx);
    const queued = await insertRecords(ctx, 3, 'job-offline');
    const job = await waitUntil(() => uploadJob(ctx), {
      timeoutMs: 15_000, intervalMs: 1_000, message: `the upload job ${UPLOAD_JOB_ID} in dumpsys jobscheduler`, signal: ctx.signal,
    });
    ctx.log(`job while offline:\n${job}`);
    assert.match(job, /CONNECTIVITY|connectivity|NET/, `the upload job must require a network:\n${job}`);
    if (api >= 31) assert.ok(isExpedited(job), `on API ${api} the job without a delay must be expedited:\n${job}`);
    assert.ok((jobDelayMs(job) ?? 0) === 0, `the offline job has no delay:\n${job}`);

    await ctx.adb.amKill(ctx.appId);
    await ctx.app.waitForNoProcess({ timeoutMs: 15_000, message: 'the app process after am kill', signal: ctx.signal });
    const onlineHost = Date.now();
    await goOnline(ctx);
    await office.waitFor((o) => queued.every((uuid) => o.records({ uuid }).length > 0), {
      timeoutMs: 120_000, intervalMs: 1_000, message: 'the 3 records queued offline, uploaded by the job', signal: ctx.signal,
    });
    const delivered = Math.max(...queued.map((uuid) => office.records({ uuid })[0]!.receivedAt));
    ctx.log(`the job uploaded the queued records ${((delivered - onlineHost) / 1000).toFixed(1)} s after the network returned`);
    await waitUntil(async () => (await uploadJob(ctx)) === undefined, {
      timeoutMs: 30_000, intervalMs: 1_000, message: 'no upload job left once the queue is empty', signal: ctx.signal,
    });

    // Part 2: a failed upload stores a next-try time (30 s ± 20 %) and schedules a regular job for it.
    office.setFault({ path: '/locations', status: 503, count: -1 });
    const failingSince = Date.now();
    const [failing] = await insertRecords(ctx, 1, 'job-after-failure');
    await office.waitFor((o) => uploads(o, failingSince).length >= 4 ? true : undefined, {
      timeoutMs: 60_000, intervalMs: 500, message: 'the 4 tries of the failing upload', signal: ctx.signal,
    });
    const lastTry = uploads(office, failingSince).at(-1)!.receivedAt;
    const delayed = await waitUntil(async () => {
      const block = await uploadJob(ctx);
      return block && (jobDelayMs(block) ?? 0) > 0 ? block : undefined;
    }, { timeoutMs: 15_000, intervalMs: 1_000, message: 'the upload job with a delay after the failed upload', signal: ctx.signal });
    ctx.log(`job after the failure:\n${delayed}`);
    const delay = jobDelayMs(delayed)!;
    assert.ok(delay >= 20_000 && delay <= 40_000, `the job delay must be about the 30 s next-try wait (24-36 s), got ${delay} ms:\n${delayed}`);
    assert.ok(!isExpedited(delayed), `a job with a delay is a regular job:\n${delayed}`);

    office.clearFaults();
    await ctx.adb.amKill(ctx.appId);
    await ctx.app.waitForNoProcess({ timeoutMs: 15_000, message: 'the app process after am kill', signal: ctx.signal });
    const stored = await office.waitFor((o) => o.records({ uuid: failing! })[0], {
      timeoutMs: 180_000, intervalMs: 1_000, message: 'the failed record, uploaded by the job at the next-try time', signal: ctx.signal,
    });
    const after = (stored.receivedAt - lastTry) / 1000;
    ctx.log(`the job uploaded the failed record ${after.toFixed(1)} s after the last failed try (job delay ${delay} ms)`);
    assert.ok(after * 1000 >= delay - 2_000, `the job ran before its next-try time: ${after.toFixed(1)} s after the last try, delay ${delay} ms`);
  },
  { timeoutMs: 10 * 60_000 },
);

scenario(
  'P-R04',
  'Task Manager stop (am stop-app): the next heartbeat alarm writes one tracking_stop user_stopped, no heartbeat; ready() restores',
  async (ctx) => {
    // 6a/6b. `am stop-app` stops the app like the Android 13+ Task Manager: alarms stay scheduled.
    const office = await ctx.backOffice();
    await ctx.app.prepare({ batteryExempt: true, launch: true });
    const config = await ctx.testConfig({ patch: NO_ACTIVITY_UPDATES });
    await readyAndStart(ctx, office, config);
    await ctx.adb.keyHome();

    const beforeStop = await ctx.adb.deviceTime();
    await ctx.adb.stopApp(ctx.appId);
    await ctx.app.waitForNoProcess({ timeoutMs: 15_000, message: 'the app process after am stop-app', signal: ctx.signal });
    const alarmsAfterStop = await appAlarms(ctx);
    ctx.log(`alarms after am stop-app: ${alarmsAfterStop.length}`);
    assert.ok(alarmsAfterStop.length > 0, 'am stop-app keeps the heartbeat alarms (otherwise nothing would start the process)');

    const stop = await waitForRecord(
      ctx, office, audit('tracking_stop', 'user_stopped'), beforeStop, (TEST_HEARTBEAT.maxInterval + TOLERANCE_S) * 1000 + 30_000,
      'tracking_stop(user_stopped) once the heartbeat alarm started the process',
    );
    assert.ok(stop.stopped_at, `tracking_stop(user_stopped) must carry stopped_at:\n  ${JSON.stringify(stop)}`);
    const stoppedAt = timeOf(stop.stopped_at);
    assert.ok(stoppedAt >= beforeStop - 2_000 && stoppedAt <= timeOf(stop.recorded_at),
      `stopped_at ${stop.stopped_at} must be the stop time (after ${iso(beforeStop)}, before recorded_at ${stop.recorded_at})`);

    // No heartbeat afterwards, the alarms are cancelled, the service does not run.
    await waitUntil(async () => (await appAlarms(ctx)).length === 0, {
      timeoutMs: 30_000, intervalMs: 2_000, message: 'the heartbeat alarms cancelled after the user stop', signal: ctx.signal,
    });
    await sleep((TEST_HEARTBEAT.maxInterval + 10) * 1000, ctx.signal);
    const after = recordsAfter(office, beforeStop);
    assert.equal(after.filter(audit('tracking_stop', 'user_stopped')).length, 1, `exactly one tracking_stop(user_stopped):\n${timeline(after)}`);
    assert.equal(after.filter((r) => r.event === 'heartbeat').length, 0, `no heartbeat after the user stop:\n${timeline(after)}`);
    assert.equal(after.filter(audit('tracking_start')).length, 0, `no tracking_start before the app is opened:\n${timeline(after)}`);
    assert.equal(await ctx.app.isForegroundServiceRunning(SERVICES.tracking), false, 'the service must not run after the user stop');

    // Opening the app restores the session.
    const beforeOpen = await ctx.adb.deviceTime();
    await ctx.app.launch();
    const state = await ctx.commands.ready(undefined, false);
    assert.equal(state.enabled, true, `ready() after the user stop restores tracking: ${JSON.stringify(state)}`);
    await waitForRecord(ctx, office, audit('tracking_start', 'restore'), beforeOpen, 30_000, 'tracking_start(restore) after ready()');
    const stopReason = (state as StateJson & { lastStopReason?: string }).lastStopReason;
    ctx.log(`getState().lastStopReason after the restore: ${String(stopReason)}`);
  },
  { requires: { api: 34 }, timeoutMs: 12 * 60_000 },
);

scenario(
  'P-R05',
  'update from the previous version: queued records upload with the tokens; database and logs move to no_backup; tokens encrypted',
  async (ctx) => {
    // 7a and 8a migration. E2E_OLD_APK: the example app built from the previous version (master before round 3).
    const oldApk = process.env['E2E_OLD_APK'];
    if (!oldApk) {
      ctx.t.skip('E2E_OLD_APK is not set (the example APK of the previous version, installed before E2E_APK)');
      return;
    }
    if (!ctx.env.apk) {
      ctx.t.skip('E2E_APK is not set (the APK installed over E2E_OLD_APK)');
      return;
    }
    const office = await ctx.backOffice();
    await ctx.adb.uninstall(ctx.appId).catch(() => undefined);
    await ctx.adb.install(oldApk, { replace: true, grant: true });
    await ctx.app.prepare({ clearData: true, launch: false });
    ctx.onTeardown(async () => {
      // Leave the current version installed for the next scenarios.
      await ctx.adb.install(ctx.env.apk!, { replace: true, grant: true }).catch(() => undefined);
    });

    const config = await ctx.testConfig({ jwt: true });
    await ctx.commands.ready(config);
    await goOffline(ctx);
    const queued = await insertRecords(ctx, 3, 'migration');
    await sleep(3_000, ctx.signal);
    const files = async (dir: string) => (await ctx.adb.runAsList(ctx.appId, dir).catch(() => [] as string[]));
    const before = { databases: await files('databases'), files: await files('files'), noBackup: await files('no_backup') };
    const prefsBefore = await ctx.adb.runAsCat(ctx.appId, 'shared_prefs/location_tracking_prefs.xml');
    ctx.log(`previous version: databases=${before.databases.join(',')} files=${before.files.join(',')} no_backup=${before.noBackup.join(',')}`);
    assert.ok(before.databases.some((f) => f.startsWith('location_tracking.db')), `the previous version keeps its database in databases/: ${before.databases}`);
    assert.ok(prefsBefore.includes('e2e-initial'), 'the previous version stores the token in the prefs file (plain text)');

    // Install this version over it, then let the app run (ready() as on an app start).
    await ctx.adb.install(ctx.env.apk, { replace: true, grant: true });
    await goOnline(ctx);
    const sinceHost = Date.now();
    await ctx.commands.ready(undefined, false);
    await office.waitFor((o) => queued.every((uuid) => o.records({ uuid }).length > 0), {
      timeoutMs: 120_000, intervalMs: 1_000, message: 'the records queued by the previous version, uploaded after the update', signal: ctx.signal,
    });
    for (const uuid of queued) {
      assert.equal(office.records({ uuid })[0]!.authorization, 'Bearer e2e-initial', `record ${uuid} must be sent with the token of the previous version`);
    }

    const after = { databases: await files('databases'), files: await files('files'), noBackup: await files('no_backup') };
    const prefsAfter = await ctx.adb.runAsCat(ctx.appId, 'shared_prefs/location_tracking_prefs.xml');
    ctx.log(`this version: databases=${after.databases.join(',')} files=${after.files.join(',')} no_backup=${after.noBackup.join(',')}`);
    assert.ok(after.noBackup.includes('location_tracking.db'), `the database is in no_backup/: ${after.noBackup}`);
    assert.ok(!after.databases.some((f) => f.startsWith('location_tracking.db')), `no database file left in databases/: ${after.databases}`);
    assert.ok(after.noBackup.includes('location-tracking-logs'), `the log folder is in no_backup/: ${after.noBackup}`);
    assert.ok(!after.files.includes('location-tracking-logs'), `no log folder left in files/: ${after.files}`);
    assert.ok(after.noBackup.includes('location_tracking_secrets.bin'), `the encrypted tokens file exists: ${after.noBackup}`);
    for (const token of ['e2e-initial', 'e2e-refresh']) {
      assert.ok(!prefsAfter.includes(token), `the prefs file must not hold the token '${token}' any more`);
    }

    // A new upload still carries the token.
    const since = Date.now();
    const [next] = await insertRecords(ctx, 1, 'after-migration');
    const stored = await office.waitFor((o) => o.records({ uuid: next!, since })[0], { timeoutMs: 30_000, message: 'a record after the update', signal: ctx.signal });
    assert.equal(stored.authorization, 'Bearer e2e-initial', 'uploads after the update use the migrated token');
    ctx.log(`migrated in ${((Date.now() - sinceHost) / 1000).toFixed(0)} s`);
  },
  { timeoutMs: 10 * 60_000 },
);

scenario(
  'P-R06',
  'backup restored onto a new installation: tracking stays off, tokens gone, the rest of the config kept',
  async (ctx) => {
    // 7b. bmgr with the local transport: back up, uninstall, install, restore.
    if (!ctx.env.apk) {
      ctx.t.skip('E2E_APK is not set (the APK installed again after the uninstall)');
      return;
    }
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true });
    const config = await ctx.testConfig({ jwt: true, heartbeat: { minInterval: 600, maxInterval: 900 } });
    await readyAndStart(ctx, office, config);

    const transport = (await ctx.adb.shell('bmgr list transports')).split('\n').find((l) => l.trim().startsWith('*'))?.replace('*', '').trim();
    ctx.onTeardown(async () => {
      if (transport) await ctx.adb.shell(`bmgr transport ${transport}`).catch(() => '');
    });
    await ctx.adb.shell('bmgr enable true');
    await ctx.adb.shell('bmgr transport com.android.localtransport/.LocalTransport');
    const backup = await ctx.adb.shell(`bmgr backupnow ${ctx.appId}`);
    ctx.log(`bmgr backupnow: ${backup.trim().split('\n').slice(-2).join(' | ')}`);
    assert.match(backup, /Success|success/, `bmgr backupnow must succeed:\n${backup}`);

    await ctx.app.closeWebView();
    await ctx.adb.uninstall(ctx.appId);
    await ctx.adb.install(ctx.env.apk, { replace: true, grant: true });
    const sets = await ctx.adb.shell('bmgr list sets');
    const token = /^\s*([0-9a-f]+)\s*:/m.exec(sets)?.[1];
    assert.ok(token, `bmgr list sets must list the local backup set:\n${sets}`);
    const beforeRestore = await ctx.adb.deviceTime();
    const restore = await ctx.adb.shell(`bmgr restore ${token} ${ctx.appId}`);
    ctx.log(`bmgr restore: ${restore.trim().split('\n').slice(-2).join(' | ')}`);

    const prefs = await waitUntil(
      async () => {
        // The config is JSON inside the XML file; org.json writes '/' as '\/'.
        const text = (await ctx.adb.runAsCat(ctx.appId, 'shared_prefs/location_tracking_prefs.xml').catch(() => '')).replace(/\\\//g, '/');
        return text.includes(office.url('/locations')) ? text : undefined;
      },
      { timeoutMs: 30_000, intervalMs: 1_000, message: 'the restored prefs file with the old http.url', signal: ctx.signal },
    );
    assert.ok(!prefs.includes('e2e-initial') && !prefs.includes('e2e-refresh'), 'the restored prefs file holds no token');

    // Nothing starts by itself.
    await sleep(20_000, ctx.signal);
    const meanwhile = recordsAfter(office, beforeRestore);
    assert.equal(meanwhile.filter(audit('tracking_start')).length, 0, `no tracking_start after the restore:\n${timeline(meanwhile)}`);
    assert.equal(await ctx.app.isForegroundServiceRunning(SERVICES.tracking), false, 'the service must not run after the restore');

    const state = await ctx.commands.state();
    const http = state.config['http'] as Record<string, any>;
    ctx.log(`state after the restore: enabled=${state.enabled} url=${http?.['url']} heartbeat=${JSON.stringify(state.config['heartbeat'])} ` +
      `authorization=${JSON.stringify(http?.['authorization'] ?? null)}`);
    assert.equal(state.enabled, false, 'a restored config never turns tracking on');
    assert.equal(http?.['url'], office.url('/locations'), 'http.url is kept');
    assert.equal((state.config['heartbeat'] as Record<string, unknown>)['minInterval'], 600, 'heartbeat.minInterval is kept');
    const auth = http?.['authorization'] as Record<string, unknown> | null | undefined;
    assert.ok(!auth || (!auth['accessToken'] && !auth['refreshToken']), `the tokens are removed: ${JSON.stringify(auth)}`);
  },
  { timeoutMs: 10 * 60_000 },
);

scenario(
  'P-R07',
  'encrypted tokens: kept across restart and reboot; a damaged secrets file gives an authorization error and holds normal records until new tokens',
  async (ctx) => {
    // 8a on the emulator's Keystore (the real-phone check is a follow-up, docs/e2e-runbook.md).
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: false });
    await ctx.commands.ready(await ctx.testConfig({ jwt: true }));
    const noBackup = await ctx.adb.runAsList(ctx.appId, 'no_backup');
    assert.ok(noBackup.includes('location_tracking_secrets.bin'), `no_backup/ must hold the encrypted tokens: ${noBackup}`);
    const prefs = await ctx.adb.runAsCat(ctx.appId, 'shared_prefs/location_tracking_prefs.xml');
    assert.ok(!prefs.includes('e2e-initial'), 'the prefs file holds no token');

    const uploadWith = async (what: string): Promise<string | undefined> => {
      const since = Date.now();
      const [uuid] = await insertRecords(ctx, 1, what);
      return (await office.waitFor((o) => o.records({ uuid: uuid!, since })[0], { timeoutMs: 30_000, message: what, signal: ctx.signal })).authorization;
    };
    await ctx.adb.forceStop(ctx.appId);
    await ctx.commands.ready(undefined, false);
    assert.equal(await uploadWith('after a restart'), 'Bearer e2e-initial', 'the token survives a process restart');
    await ctx.adb.reboot();
    await ctx.commands.ready(undefined, false);
    assert.equal(await uploadWith('after a reboot'), 'Bearer e2e-initial', 'the token survives a reboot');

    // Damage the file: the tokens read as unset, an authorization event reports it, normal records wait.
    await ctx.adb.forceStop(ctx.appId);
    await ctx.adb.runAsWrite(ctx.appId, 'no_backup/location_tracking_secrets.bin', 'not an encrypted file');
    await ctx.app.launch();
    await ctx.commands.ready(undefined, false);
    const since = Date.now();
    const held = await insertRecords(ctx, 1, 'damaged-secrets');
    // The first try stores an authorization wait; sync() tries again while the listener is on the page. The probe
    // reconnects each time: after a reboot Android may recreate the activity (and its WebView) once.
    const authEvent = await waitUntil(
      async () => {
        const web = await captureEvents(ctx, ['authorization']);
        await ctx.commands.sync().catch(() => undefined);
        await sleep(1_000);
        return (await web.drainEvents()).find((e) => e.name === 'authorization');
      },
      { timeoutMs: 60_000, intervalMs: 2_000, message: 'an authorization event after the damaged secrets file', signal: ctx.signal },
    );
    ctx.log(`authorization event: ${JSON.stringify(authEvent.payload)}`);
    assert.equal(authEvent.payload.success, false, 'the authorization event reports a failure');
    assert.ok(authEvent.payload.error, `the authorization event carries an error: ${JSON.stringify(authEvent.payload)}`);
    await sleep(10_000, ctx.signal);
    assert.equal(office.records({ uuid: held[0]!, since }).length, 0, 'the normal record waits while the tokens cannot be read');

    // New tokens end the wait.
    const auth = (config: Record<string, any>) => config['http']['authorization'];
    const fresh = await ctx.testConfig({ jwt: true });
    auth(fresh)['accessToken'] = 'e2e-new-access';
    auth(fresh)['refreshToken'] = 'e2e-new-refresh';
    await ctx.commands.setConfig({ http: { authorization: auth(fresh) } });
    const stored = await office.waitFor((o) => o.records({ uuid: held[0]!, since })[0], {
      timeoutMs: 30_000, message: 'the held record after new tokens', signal: ctx.signal,
    });
    assert.equal(stored.authorization, 'Bearer e2e-new-access', 'the held record goes out with the new token');
  },
  { timeoutMs: 12 * 60_000 },
);

scenario(
  'P-R08',
  "platform backend ('android') with gps and network: network fixes indoors, GPS outdoors with network fixes dropped, the more accurate current position",
  async (ctx) => {
    // 12a/12b with test providers (API 31+): the host decides which provider delivers.
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true });
    for (const provider of ['gps', 'network']) {
      await ctx.adb.addTestProvider(provider);
      ctx.onTeardown(() => ctx.adb.removeTestProvider(provider));
    }
    const indoor = { lat: 24.70011, lon: 46.6753 };
    const far = { lat: 24.6, lon: 46.5 }; // network fixes 15 km away: dropped while GPS is recent
    let feed: { gps?: { lat: number; lon: number }; network?: { lat: number; lon: number } } = { network: indoor };
    const abort = new AbortController();
    const feeder = (async () => {
      let step = 0;
      while (!abort.signal.aborted && !ctx.signal.aborted) {
        step += 1;
        if (feed.network) await ctx.adb.setTestLocation('network', feed.network.lat, feed.network.lon, 40).catch(() => undefined);
        if (feed.gps) await ctx.adb.setTestLocation('gps', feed.gps.lat + step * 0.0002, feed.gps.lon, 5).catch(() => undefined);
        await sleep(1_000).catch(() => undefined);
      }
    })();
    ctx.onTeardown(async () => {
      abort.abort();
      await feeder;
    });

    const config = await ctx.testConfig({ locationProvider: 'android' });
    const { startDevice } = await readyAndStart(ctx, office, config);
    await ctx.commands.changePace(true);
    const near = (r: WireRecord, p: { lat: number; lon: number }, m: number) =>
      r.coords !== null && Math.abs(r.coords.latitude - p.lat) * 111_000 < m && Math.abs(r.coords.longitude - p.lon) * 101_000 < m;

    // Indoors: only the network provider delivers.
    const indoorRecord = await waitForRecord(ctx, office, (r) => r.backend === 'android' && near(r, indoor, 200), startDevice, 90_000, 'a record with the network fix (indoors)');
    ctx.log(`indoors: ${label(indoorRecord)} accuracy ${indoorRecord.coords?.accuracy}`);

    // Outdoors: GPS fixes (moving north), network fixes far away are dropped.
    const outdoorStart = await ctx.adb.deviceTime();
    feed = { gps: PLACES.hqNorth1500m, network: far };
    await waitForRecord(ctx, office, (r) => near(r, PLACES.hqNorth1500m, 2_000) && (r.coords?.accuracy ?? 99) <= 10, outdoorStart, 90_000, 'a record with a GPS fix');
    await sleep(20_000, ctx.signal);
    const outdoor = recordsAfter(office, outdoorStart + 5_000);
    const dropped = outdoor.filter((r) => near(r, far, 2_000));
    assert.equal(dropped.length, 0, `network fixes must be dropped while GPS is recent:\n${timeline(dropped)}`);

    // getCurrentPosition: both providers answer; the more accurate fix wins.
    const web = await ctx.app.webView();
    await web.callPlugin(PLUGIN, 'ready', { reset: false }); // the JS API needs its own ready(); the debug commands do not set it
    const position = await web.callPlugin<{ coords: { accuracy: number; latitude: number } }>(PLUGIN, 'getCurrentPosition', {
      persist: false, timeout: 30_000, maximumAge: 0, desiredAccuracy: 'high',
    }, { timeoutMs: 45_000 });
    ctx.log(`getCurrentPosition: accuracy ${position.coords.accuracy} at ${position.coords.latitude.toFixed(5)}`);
    assert.ok(position.coords.accuracy <= 10, `getCurrentPosition must return the GPS fix (5 m), got accuracy ${position.coords.accuracy}`);
  },
  { requires: { api: 31 }, timeoutMs: 10 * 60_000 },
);

scenario(
  'P-R09',
  'deep Doze, battery-exempt: each heartbeat is uploaded at once and the heartbeat-upload wake lock is released after the upload',
  async (ctx) => {
    // 11d. The back office answers 4 s late, so the wake lock is visible in dumpsys power during the upload.
    const office = await ctx.backOffice();
    await ctx.app.prepare({ batteryExempt: true, launch: true });
    await ctx.adb.geoFix(PLACES.hq.lat, PLACES.hq.lon);
    const { startDevice } = await readyAndStart(ctx, office, await ctx.testConfig({ patch: NO_ACTIVITY_UPDATES }));
    await waitForRecord(ctx, office, (r) => r.event === 'motionchange', startDevice, 75_000, 'the initial motionchange');
    office.setFault({ path: '/locations', delayMs: 4_000, count: -1 });
    await ctx.adb.keyHome();
    await ctx.adb.screenOff();
    await ctx.adb.deviceIdle.forceIdle();
    ctx.onTeardown(() => ctx.adb.deviceIdle.unforce());
    const idleSince = await ctx.adb.deviceTime();
    const sinceHost = Date.now();

    let heldSeen = 0;
    let wakeLockLines = '';
    const heartbeats = await waitUntil(
      async () => {
        const lines = heldWakeLocks(await ctx.adb.dumpsys('power')).filter((l) => l.includes('LocationTracking:heartbeat-upload'));
        if (lines.length > 0) {
          heldSeen += 1;
          wakeLockLines = lines.join(' | ');
        }
        const hb = office.records({ event: 'heartbeat', since: sinceHost });
        return hb.length >= 3 ? hb : undefined;
      },
      { timeoutMs: 3 * (TEST_HEARTBEAT.maxInterval + TOLERANCE_S) * 1000, intervalMs: 500, message: '3 heartbeats in deep Doze', signal: ctx.signal },
    );
    for (const hb of heartbeats) {
      const late = hb.receivedAt - timeOf(hb.record.recorded_at);
      ctx.log(`heartbeat ${hb.record.recorded_at} reached the back office ${(late / 1000).toFixed(1)} s later`);
      assert.ok(late <= 15_000, `a heartbeat in deep Doze must reach the back office within a few seconds; it took ${late} ms`);
    }
    assert.ok(heldSeen > 0, 'dumpsys power must list the LocationTracking:heartbeat-upload wake lock during an upload');
    ctx.log(`wake lock seen in ${heldSeen} dumpsys power read(s): ${wakeLockLines.trim()}`);
    await sleep(6_000, ctx.signal);
    const held = heldWakeLocks(await ctx.adb.dumpsys('power'));
    assert.ok(!held.some((l) => l.includes('LocationTracking:heartbeat-upload')), `the heartbeat-upload wake lock is released after the upload:\n${held.join('\n')}`);
    assert.ok(recordsAfter(office, idleSince).some((r) => r.event === 'heartbeat'));
  },
  { timeoutMs: 12 * 60_000 },
);

scenario(
  'P-R10',
  'upload rules against the back office: 503 Retry-After, 429, 413 for big batches, 302 not followed, at most 16 KB of an answer',
  async (ctx) => {
    // 1a, 1b, 11a-11c, 13a. Tracking off: only inserted (normal) records, so the stored wait decides every upload.
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true });
    await ctx.commands.ready(await ctx.testConfig({}));
    const web = await captureEvents(ctx, ['http']);

    // a) 503 with Retry-After: 120 → no automatic upload of normal records for 120 s.
    office.setFault({ path: '/locations', status: 503, headers: { 'Retry-After': '120' }, count: 1 });
    let since = Date.now();
    const [first] = await insertRecords(ctx, 1, 'retry-after');
    await office.waitFor((o) => uploads(o, since).length >= 1 ? true : undefined, { timeoutMs: 20_000, message: 'the 503 answer', signal: ctx.signal });
    await sleep(3_000, ctx.signal);
    const answered = uploads(office, since)[0]!.receivedAt;
    assert.equal(uploads(office, since).length, 1, `a Retry-After longer than the next retry ends the upload:\n${requestLog(uploads(office, since))}`);
    await sleep(10_000, ctx.signal);
    await insertRecords(ctx, 1, 'retry-after-2');
    await sleep(40_000, ctx.signal);
    await insertRecords(ctx, 1, 'retry-after-3');
    const next = await office.waitFor((o) => uploads(o, answered + 1).find((r) => r.status === 200), {
      timeoutMs: 180_000, intervalMs: 1_000, message: 'the upload after the Retry-After wait', signal: ctx.signal,
    });
    const waited = (next.receivedAt - answered) / 1000;
    ctx.log(`503 Retry-After 120: next upload ${waited.toFixed(1)} s later\n${requestLog(uploads(office, since))}`);
    assert.ok(waited >= 119, `no automatic upload of normal records before Retry-After (120 s); came after ${waited.toFixed(1)} s`);
    await office.waitFor((o) => o.records({ uuid: first! }).length > 0, { timeoutMs: 60_000, message: 'the first record', signal: ctx.signal });
    await sleep(5_000, ctx.signal);

    // b) 429 four times: 4 tries in one upload, then a stored wait of 30 s ± 20 %.
    office.setFault({ path: '/locations', status: 429, count: 4 });
    since = Date.now();
    await insertRecords(ctx, 1, 'too-many');
    await office.waitFor((o) => uploads(o, since).length >= 4 ? true : undefined, { timeoutMs: 60_000, message: 'four 429 answers', signal: ctx.signal });
    const last429 = uploads(office, since)[3]!.receivedAt;
    await sleep(5_000, ctx.signal);
    await insertRecords(ctx, 1, 'too-many-2');
    const after429 = await office.waitFor((o) => uploads(o, last429 + 1)[0], { timeoutMs: 120_000, intervalMs: 1_000, message: 'the upload after 429', signal: ctx.signal });
    const gap429 = (after429.receivedAt - last429) / 1000;
    ctx.log(`429 x4: next upload ${gap429.toFixed(1)} s after the 4th try`);
    assert.ok(gap429 >= 23, `normal records wait the stored 30 s (± 20 %) after the failed upload; next came after ${gap429.toFixed(1)} s`);
    await sleep(5_000, ctx.signal);

    // c) 413 for bodies with 5 or more records: the batch is halved.
    await ctx.commands.setConfig({ http: { batchSync: true, maxBatchSize: 8, autoSyncThreshold: 8 } });
    office.setFault({ path: '/locations', status: 413, minRecords: 5, count: -1 });
    since = Date.now();
    const batch = await insertRecords(ctx, 8, 'big-batch');
    await office.waitFor((o) => batch.every((uuid) => o.records({ uuid }).length > 0) ? true : undefined, {
      timeoutMs: 60_000, intervalMs: 1_000, message: 'the 8 records in smaller batches after 413', signal: ctx.signal,
    });
    const batchLog = uploads(office, since);
    ctx.log(`413:\n${requestLog(batchLog)}`);
    assert.equal(batchLog[0]!.status, 413);
    assert.ok(batchLog.filter((r) => r.status === 200).every((r) => bodyRecords(r).length <= 4), `after a 413 the batches have at most 4 records:\n${requestLog(batchLog)}`);
    office.clearFaults();
    await ctx.commands.setConfig({ http: { batchSync: false, autoSyncThreshold: 0 } });

    // d) 302: not followed, the record stays queued (and is sent again later).
    office.setFault({ path: '/locations', status: 302, headers: { Location: office.url('/elsewhere') }, count: 1 });
    since = Date.now();
    const [redirected] = await insertRecords(ctx, 1, 'redirect');
    await office.waitFor((o) => uploads(o, since).length >= 1 ? true : undefined, { timeoutMs: 20_000, message: 'the 302 answer', signal: ctx.signal });
    await sleep(5_000, ctx.signal);
    assert.equal(office.requests({ path: '/elsewhere' }).length, 0, 'the redirect must not be followed');
    assert.equal(office.records({ uuid: redirected! }).length, 0, 'the redirected record is not stored');
    const resent = await office.waitFor((o) => o.records({ uuid: redirected! })[0], {
      timeoutMs: 120_000, intervalMs: 1_000, message: 'the redirected record sent again after the wait', signal: ctx.signal,
    });
    ctx.log(`302: the record stayed queued and went out ${((resent.receivedAt - since) / 1000).toFixed(1)} s later`);

    // e) A 20 KB answer: the http event carries at most 16 KB of it.
    await web.drainEvents();
    office.setFault({ path: '/locations', status: 500, body: 'x'.repeat(20_000), count: 1 });
    await insertRecords(ctx, 1, 'big-answer');
    const big = await waitUntil(
      async () => (await web.drainEvents()).find((e) => e.name === 'http' && e.payload.status === 500),
      { timeoutMs: 30_000, intervalMs: 1_000, message: 'the http event of the 20 KB answer', signal: ctx.signal },
    );
    const length = String(big.payload.responseText ?? '').length;
    ctx.log(`20 KB answer: responseText ${length} characters`);
    assert.ok(length > 0 && length <= 16_384, `the http event carries at most 16 KB of the answer, got ${length} characters`);
  },
  { timeoutMs: 15 * 60_000 },
);

scenario(
  'P-R11',
  'token races: new tokens during a delayed answer stop the upload and a new one follows 10 s later; a late refresh does not overwrite them',
  async (ctx) => {
    // 2a with the owner's rule of 2026-10-21 (R3-Q8), and 2b.
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true });
    const config = await ctx.testConfig({ jwt: true });
    await ctx.commands.ready(config);
    const authorization = (access: string, refresh: string) => ({
      ...((config['http'] as Record<string, any>)['authorization'] as Record<string, unknown>),
      accessToken: access,
      refreshToken: refresh,
    });

    // 2a: the first answer comes 8 s late (503); meanwhile the app sets user B's token.
    office.setFault({ path: '/locations', status: 503, delayMs: 8_000, count: 1 });
    let since = Date.now();
    const [record] = await insertRecords(ctx, 1, 'token-race');
    await office.waitFor((o) => uploads(o, since).length >= 1 ? true : undefined, { timeoutMs: 20_000, message: 'the delayed request', signal: ctx.signal });
    await ctx.commands.setConfig({ http: { authorization: authorization('e2e-userB', 'e2e-refresh-B') } });
    const stored = await office.waitFor((o) => o.records({ uuid: record! })[0], {
      timeoutMs: 60_000, intervalMs: 500, message: 'the record sent again with the new token', signal: ctx.signal,
    });
    const log = uploads(office, since);
    ctx.log(`2a:\n${requestLog(log)}`);
    assert.deepEqual(log.map((r) => r.headers['authorization']), ['Bearer e2e-initial', 'Bearer e2e-userB'],
      `one request with the old token, then one with the new token (no retry with the old one):\n${requestLog(log)}`);
    const stoppedAt = log[0]!.receivedAt + 8_000;
    const gap = (log[1]!.receivedAt - stoppedAt) / 1000;
    assert.ok(gap >= 8 && gap <= 20, `the new upload follows about 10 s after the stop; it came ${gap.toFixed(1)} s later`);
    assert.equal(stored.authorization, 'Bearer e2e-userB');

    // 2b: a 401 starts a refresh whose answer comes 8 s late; meanwhile the app sets user C's tokens.
    office.setFault({ path: '/locations', status: 401, count: 1 });
    office.setFault({ path: '/auth/refresh', delayMs: 8_000, count: 1 });
    since = Date.now();
    const [record2] = await insertRecords(ctx, 1, 'refresh-race');
    await office.waitFor((o) => o.requests({ path: '/auth/refresh', since }).length >= 1 ? true : undefined, {
      timeoutMs: 20_000, message: 'the delayed refresh', signal: ctx.signal,
    });
    await ctx.commands.setConfig({ http: { authorization: authorization('e2e-userC', 'e2e-refresh-C') } });
    const stored2 = await office.waitFor((o) => o.records({ uuid: record2! })[0], {
      timeoutMs: 60_000, intervalMs: 500, message: 'the record sent with the app token after the late refresh', signal: ctx.signal,
    });
    await sleep(2_000, ctx.signal);
    const log2 = uploads(office, since);
    ctx.log(`2b:\n${requestLog(log2)}`);
    assert.equal(stored2.authorization, 'Bearer e2e-userC', "the app's new token is used, not the refreshed one");
    assert.ok(!log2.some((r) => r.headers['authorization'] === 'Bearer e2e-access-1'), `the refreshed token is never used:\n${requestLog(log2)}`);
    const state = await ctx.commands.state();
    const auth = (state.config['http'] as Record<string, any>)['authorization'];
    ctx.log(`state tokens after 2b: ${JSON.stringify({ accessToken: auth?.accessToken, refreshToken: auth?.refreshToken })}`);
    assert.equal(auth?.accessToken, '***serC', 'getState() keeps the app token (masked)');
    assert.equal(auth?.refreshToken, '***sh-C', 'getState() keeps the app refresh token (masked)');
  },
  { timeoutMs: 8 * 60_000 },
);

scenario(
  'P-R12',
  'storage full: a record the plugin cannot store gives a storagefull event; insertLocation() rejects instead',
  async (ctx) => {
    // 16g, best effort: fills /data with one file (root), leaving almost nothing for the app. A record the plugin
    // creates (here the motionchange of changePace) goes through the record sink, which emits the event;
    // insertLocation() rejects the call instead.
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true });
    await ctx.adb.geoFix(PLACES.hq.lat, PLACES.hq.lon);
    const { startDevice } = await readyAndStart(ctx, office, await ctx.testConfig({}));
    await waitForRecord(ctx, office, (r) => r.event === 'motionchange', startDevice, 75_000, 'the initial motionchange');
    const fill = '/data/local/tmp/e2e-fill';
    ctx.onTeardown(async () => {
      await ctx.adb.shell(`rm -f ${fill}`).catch(() => '');
    });
    const df = await ctx.adb.shell('df -k /data | tail -1');
    const freeKb = Number(df.trim().split(/\s+/)[3]);
    assert.ok(Number.isFinite(freeKb) && freeKb > 0, `df /data: ${df}`);
    // dd ends with "No space left on device" (exit 1) once /data is full, which is the state wanted here.
    await ctx.adb.shell(`fallocate -l ${Math.max(0, freeKb - 512)}k ${fill} || dd if=/dev/zero of=${fill} bs=1m count=${Math.floor(freeKb / 1024)} || true`);
    ctx.log(`filled /data: ${(await ctx.adb.shell('df -k /data | tail -1')).trim()}`);

    const rejected = await ctx.commands.insertLocation({ coords: { latitude: PLACES.hq.lat, longitude: PLACES.hq.lon, accuracy: 8 } }).then(
      () => undefined,
      (error: unknown) => String(error),
    );
    ctx.log(`insertLocation with a full disk: ${rejected ?? 'resolved'}`);
    let moving = false;
    const event = await waitUntil(
      async () => {
        const web = await captureEvents(ctx, ['storagefull']);
        moving = !moving;
        await ctx.commands.changePace(moving).catch(() => undefined);
        await sleep(1_000);
        return (await web.drainEvents()).find((e) => e.name === 'storagefull');
      },
      { timeoutMs: 60_000, intervalMs: 2_000, message: 'a storagefull event for a motionchange', signal: ctx.signal },
    );
    ctx.log(`storagefull: ${JSON.stringify(event.payload)}`);
    assert.ok(event.payload.uuid, 'storagefull names the record');
    assert.equal(event.payload.event, 'motionchange');
    assert.ok(rejected?.includes('IO_ERROR'), `insertLocation() rejects with IO_ERROR when the disk is full: ${rejected}`);
  },
  { requires: { root: true }, timeoutMs: 6 * 60_000 },
);

scenario(
  'P-R13',
  'every uploaded record carries device.device_id, the app\'s ANDROID_ID',
  async (ctx) => {
    // A1 (R3-Q7). Every kind of record the plugin creates in a short session.
    const office = await ctx.backOffice();
    await ctx.app.prepare({ launch: true });
    await ctx.adb.geoFix(PLACES.hq.lat, PLACES.hq.lon);
    const { startDevice } = await readyAndStart(ctx, office, await ctx.testConfig({}));
    await waitForRecord(ctx, office, (r) => r.event === 'motionchange', startDevice, 75_000, 'the initial motionchange');
    await insertRecords(ctx, 1, 'device-id');
    await waitForRecord(ctx, office, (r) => r.event === 'heartbeat', startDevice, (TEST_HEARTBEAT.maxInterval + TOLERANCE_S) * 1000, 'a heartbeat');
    await ctx.commands.stop();
    await waitForRecord(ctx, office, audit('tracking_stop', 'stop'), startDevice, 30_000, 'tracking_stop(stop)');

    const expected = await expectedDeviceId(ctx);
    const records = recordsAfter(office, startDevice);
    const ids = new Set(records.map((r) => r.device?.device_id));
    ctx.log(`device_id values: ${[...ids].join(', ')}; settings get secure android_id: ${expected.shell}; SSAID of the app: ${expected.ssaid ?? 'n/a'}`);
    const events = new Set(records.map((r) => r.event));
    for (const event of ['tracking_start', 'motionchange', 'location', 'heartbeat', 'tracking_stop'] as const) {
      assert.ok(events.has(event), `the session must include a ${event} record:\n${timeline(records)}`);
    }
    for (const r of records) {
      assert.ok(r.device?.device_id, `${label(r)} ${r.uuid} has no device.device_id: ${JSON.stringify(r.device)}`);
      assert.ok(!('android_id' in (r.device ?? {})), `${label(r)} still has device.android_id`);
    }
    assert.equal(ids.size, 1, `one device_id on every record, got ${[...ids].join(', ')}`);
    const id = [...ids][0];
    assert.ok(id === expected.ssaid || id === expected.shell,
      `device_id ${id} must be the app's ANDROID_ID (SSAID ${expected.ssaid ?? 'n/a'}, settings get secure android_id ${expected.shell})`);
    const start = records.find(audit('tracking_start'))!;
    assert.ok(start.device?.['model'], 'tracking_start keeps its phone readings next to device_id');
  },
  { timeoutMs: 8 * 60_000 },
);
