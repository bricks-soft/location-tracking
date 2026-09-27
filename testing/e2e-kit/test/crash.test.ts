import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { Adb, CrashScanner, appProcessFromComm, findFgsDidNotStartLines, parseCrashReports, parseLogcatLine } from '../src/index.ts';
import { FakeAdb } from './helpers/fake-adb.ts';

const APP = 'com.brickssoft.locationtracking.example';
const fakes: FakeAdb[] = [];
after(() => fakes.forEach((fake) => fake.cleanup()));

const OLD_CRASH = [
  '--------- beginning of crash',
  `2026-09-27 09:00:00.000  1000  1000 E AndroidRuntime: FATAL EXCEPTION: main`,
  `2026-09-27 09:00:00.000  1000  1000 E AndroidRuntime: Process: ${APP}, PID: 1000`,
  `2026-09-27 09:00:00.000  1000  1000 E AndroidRuntime: java.lang.IllegalStateException: old crash`,
  `2026-09-27 09:00:00.000  1000  1000 E AndroidRuntime: \tat com.x.Old.run(Old.kt:1)`,
].join('\n');

const FGS_CRASH = [
  `2026-09-27 10:00:00.100  2000  2000 E AndroidRuntime: FATAL EXCEPTION: main`,
  `2026-09-27 10:00:00.100  2000  2000 E AndroidRuntime: Process: ${APP}, PID: 2000`,
  `2026-09-27 10:00:00.100  2000  2000 E AndroidRuntime: android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException: Context.startForegroundService() did not then call Service.startForeground(): ServiceRecord{a1 u0 ${APP}/com.brickssoft.locationtracking.service.LocationTrackingService}`,
  `2026-09-27 10:00:00.100  2000  2000 E AndroidRuntime: \tat android.app.ActivityThread.generateForegroundServiceDidNotStartInTimeException(ActivityThread.java:2104)`,
  `2026-09-27 10:00:00.100   555   600 I ActivityManager: Process ${APP} (pid 2000) has died`,
  `2026-09-27 10:00:00.100  2000  2000 E AndroidRuntime: Caused by: android.app.StackTrace: Last startServiceCommon() call for this service was made here`,
].join('\n');

const OTHER_APP_CRASH = [
  `2026-09-27 10:00:01.000  3000  3000 E AndroidRuntime: FATAL EXCEPTION: main`,
  `2026-09-27 10:00:01.000  3000  3000 E AndroidRuntime: Process: com.other.app, PID: 3000`,
  `2026-09-27 10:00:01.000  3000  3000 E AndroidRuntime: java.lang.NullPointerException: other`,
].join('\n');

const ANR = [
  `2026-09-27 10:00:02.000   555   570 E ActivityManager: ANR in ${APP} (${APP}/.MainActivity)`,
  `2026-09-27 10:00:02.000   555   570 E ActivityManager: PID: 2100`,
  `2026-09-27 10:00:02.000   555   570 E ActivityManager: Reason: Input dispatching timed out`,
].join('\n');

const NATIVE = [
  `2026-09-27 10:00:03.000  2200  2210 F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 2210 (worker), pid 2200 (${APP}:remote)`,
  `2026-09-27 10:00:03.100  2300  2300 F DEBUG   : pid: 2200, tid: 2210, name: worker  >>> ${APP}:remote <<<`,
].join('\n');

const SYSTEM_FGS = `2026-09-27 10:00:04.000   555   580 W ActivityManager: Bringing down service while still waiting for start foreground: ServiceRecord{b2 u0 ${APP}/com.brickssoft.locationtracking.service.LocationTrackingService} Context.startForegroundService() did not then call Service.startForeground()`;

test('parseLogcatLine reads threadtime lines with year, UTC and padded tags', () => {
  const line = parseLogcatLine('2026-09-27 10:00:05.123  4321  4330 I LT-E2E  : {"id":"x"}')!;
  assert.equal(line.pid, 4321);
  assert.equal(line.tid, 4330);
  assert.equal(line.level, 'I');
  assert.equal(line.tag, 'LT-E2E');
  assert.equal(line.message, '{"id":"x"}');
  assert.equal(line.epochMs, Date.UTC(2026, 8, 27, 10, 0, 5, 123));
  assert.equal(parseLogcatLine('09-27 10:00:05.123  1  2 W Tag: msg')!.epochMs, undefined);
  assert.equal(parseLogcatLine('--------- beginning of main'), undefined);
  const zoned = parseLogcatLine('2026-09-27 10:15:30.123 +0300  4321  4330 I Tag: m')!;
  assert.equal(zoned.epochMs, Date.UTC(2026, 8, 27, 7, 15, 30, 123));
  assert.equal(zoned.pid, 4321);
  assert.equal(parseLogcatLine('2026-09-27 10:15:30.123 -0130  1  2 I Tag: m')!.epochMs, Date.UTC(2026, 8, 27, 11, 45, 30, 123));
});

test('appProcessFromComm maps 15-character kernel names back to the app', () => {
  assert.equal(appProcessFromComm('racking.example', APP), APP);
  assert.equal(appProcessFromComm('example:service', APP), `${APP}:service`);
  assert.equal(appProcessFromComm(`${APP}:remote`, APP), `${APP}:remote`);
  assert.equal(appProcessFromComm('ng.other.example', APP), undefined);
  assert.equal(appProcessFromComm('example', APP), undefined);
});

test('a native crash seen only through libc (truncated name) is reported, keyed by pid across line rotation', async () => {
  const libcOnly = `2026-09-27 10:00:03.000  2200  2210 F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 2210 (worker), pid 2200 (racking.example)`;
  const [report] = parseCrashReports(libcOnly, APP);
  assert.equal(report?.process, APP);
  assert.equal(report?.exception, 'signal 6 (SIGABRT)');
  const debugOnly = `2026-09-27 10:00:03.100  2300  2300 F DEBUG   : pid: 2200, tid: 2210, name: worker  >>> ${APP} <<<`;
  const { crashes } = scanner([`${libcOnly}\n${debugOnly}`, debugOnly]);
  await crashes.mark();
  assert.deepEqual(await crashes.crashes(), [], 'the same native crash after the libc line rotated out');
});

test('parseCrashReports finds the app crash, ANR and native crash, not other apps', () => {
  const reports = parseCrashReports([FGS_CRASH, OTHER_APP_CRASH, ANR, NATIVE].join('\n'), APP);
  assert.equal(reports.length, 3);
  const [crash, anr, native] = reports;
  assert.equal(crash!.kind, 'crash');
  assert.equal(crash!.pid, 2000);
  assert.equal(crash!.exception, 'android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException');
  assert.match(crash!.text, /Caused by: android.app.StackTrace/);
  assert.doesNotMatch(crash!.text, /has died/);
  assert.equal(anr!.kind, 'anr');
  assert.equal(anr!.pid, 2100);
  assert.equal(anr!.exception, 'Input dispatching timed out');
  assert.equal(native!.kind, 'native');
  assert.equal(native!.process, `${APP}:remote`);
  assert.equal(native!.pid, 2200);
  assert.equal(native!.exception, 'signal 11 (SIGSEGV)');
});

test('findFgsDidNotStartLines: lines naming the app or inside its crash', () => {
  const lines = findFgsDidNotStartLines([FGS_CRASH, SYSTEM_FGS, OTHER_APP_CRASH].join('\n'), APP);
  assert.equal(lines.length, 3);
  assert.equal(findFgsDidNotStartLines(SYSTEM_FGS.replaceAll(APP, 'com.other'), APP).length, 0);
});

function scanner(dumps: string[]): { fake: FakeAdb; crashes: CrashScanner } {
  const fake = new FakeAdb([
    { match: 'date \\+%s%N', stdout: '1790000000000000000\n' },
    ...dumps.map((stdout, i) => ({ match: '^logcat -d', stdout, ...(i < dumps.length - 1 ? { times: 1 } : {}) })),
  ]);
  fakes.push(fake);
  return { fake, crashes: new CrashScanner(new Adb({ adbPath: fake.path }), APP) };
}

test('CrashScanner reports only what appeared after the mark', async () => {
  const { fake, crashes } = scanner([OLD_CRASH, `${OLD_CRASH}\n${FGS_CRASH}\n${SYSTEM_FGS}`]);
  await crashes.mark();
  assert.equal(crashes.markedAt, 1790000000000);
  const found = await crashes.crashes();
  assert.equal(found.length, 1);
  assert.equal(found[0]!.pid, 2000);
  await assert.rejects(crashes.assertNoCrash(), /crashed 1 time\(s\).*ForegroundServiceDidNotStartInTimeException/s);
  await assert.rejects(crashes.assertNoFgsDidNotStartInTime(), /did not start in time/);
  const dump = fake.calls().find((c) => c.args[0] === 'logcat')!;
  assert.deepEqual(dump.args, ['logcat', '-d', '-v', 'threadtime', '-v', 'UTC', '-v', 'year', '-b', 'crash', '-b', 'main', '-b', 'system']);
});

test('CrashScanner: a crash present at the mark does not fail later scans', async () => {
  const { crashes } = scanner([`${OLD_CRASH}\n${SYSTEM_FGS}`, `${OLD_CRASH}\n${SYSTEM_FGS}\n2026-09-27 10:05:00.000  1  1 I Foo: bar`]);
  await crashes.mark();
  assert.deepEqual(await crashes.crashes(), []);
  await crashes.assertNoCrash();
  await crashes.assertNoFgsDidNotStartInTime();
});

test('CrashScanner: a FGS line without a crash still fails assertNoFgsDidNotStartInTime', async () => {
  const { crashes } = scanner(['', SYSTEM_FGS]);
  await crashes.mark();
  await crashes.assertNoCrash();
  await assert.rejects(crashes.assertNoFgsDidNotStartInTime(), /Bringing down service/);
});
