import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { after, describe, test } from 'node:test';
import { Adb, AdbError, interpolateRoute, persistedPermissionsGranted, ROUTES, assertions } from '../src/index.ts';
import { FakeAdb, re, type FakeRule } from './helpers/fake-adb.ts';

const fakes: FakeAdb[] = [];
after(() => fakes.forEach((fake) => fake.cleanup()));

function setup(rules: FakeRule[] = [], options: { noSerial?: boolean } = {}): { fake: FakeAdb; adb: Adb } {
  const fake = new FakeAdb(rules);
  fakes.push(fake);
  const serial = options.noSerial ? undefined : 'emulator-5554';
  return { fake, adb: new Adb({ serial, adbPath: fake.path, timeoutMs: 10_000 }) };
}

describe('Adb raw calls', () => {
  test('exec passes -s <serial> first and returns stdout, stderr and code', async () => {
    const { fake, adb } = setup([{ match: '^devices$', stdout: 'List\n', stderr: 'warn\n' }]);
    const result = await adb.exec(['devices']);
    assert.deepEqual(result, { stdout: 'List\n', stderr: 'warn\n', code: 0 });
    assert.equal(fake.calls()[0]!.serial, 'emulator-5554');
    assert.deepEqual(fake.calls()[0]!.args, ['devices']);
  });

  test('no serial: no -s argument', async () => {
    const { fake, adb } = setup([], { noSerial: true });
    await adb.exec(['version']);
    assert.equal(fake.calls()[0]!.serial, undefined);
  });

  test('shell trims trailing newlines and normalizes CRLF', async () => {
    const { adb } = setup([{ match: '^shell echo hi$', stdout: 'a\r\nb\r\n\r\n' }]);
    assert.equal(await adb.shell('echo hi'), 'a\nb');
  });

  test('a non-zero exit rejects with AdbError (code, stderr); allowFailure resolves', async () => {
    const { adb } = setup([{ match: '^shell false$', code: 3, stderr: 'boom' }]);
    await assert.rejects(adb.shell('false'), (error: unknown) => {
      assert.ok(error instanceof AdbError);
      assert.equal(error.code, 3);
      assert.equal(error.stderr, 'boom');
      assert.match(error.message, /exit 3.*boom/);
      return true;
    });
    const result = await adb.exec(['shell', 'false'], { allowFailure: true });
    assert.equal(result.code, 3);
  });

  test('a call that exceeds its timeout rejects with timedOut', async () => {
    const { adb } = setup([{ match: '^shell slow$', sleepMs: 3000 }]);
    await assert.rejects(adb.exec(['shell', 'slow'], { timeoutMs: 300, allowFailure: true }), (error: unknown) => {
      assert.ok(error instanceof AdbError);
      assert.equal(error.timedOut, true);
      assert.match(error.message, /timed out after 300 ms/);
      return true;
    });
  });

  test('a missing adb binary rejects with a clear error', async () => {
    const adb = new Adb({ adbPath: '/nonexistent/adb' });
    await assert.rejects(adb.exec(['devices']), /could not run \(ENOENT\)/);
  });

  test('stdin input reaches adb', async () => {
    const { fake, adb } = setup();
    await adb.exec(['shell', 'cat > x'], { input: 'hello' });
    assert.equal(fake.calls()[0]!.input, 'hello');
  });
});

describe('Adb device and package methods', () => {
  test('root: production builds resolve false; userdebug roots and checks id -u', async () => {
    const denied = setup([{ match: '^root$', stdout: 'adbd cannot run as root in production builds\n', code: 1 }]);
    assert.equal(await denied.adb.root(), false);
    const rooted = setup([
      { match: '^root$', stdout: 'restarting adbd as root\n' },
      { match: '^shell id -u$', stdout: '0\n' },
    ]);
    assert.equal(await rooted.adb.root(), true);
    assert.deepEqual(rooted.fake.commandLines(), ['root', 'wait-for-device', 'shell id -u']);
  });

  test('apiLevel reads ro.build.version.sdk once', async () => {
    const { fake, adb } = setup([{ match: 'getprop ro\\.build\\.version\\.sdk', stdout: '34\n' }]);
    assert.equal(await adb.apiLevel(), 34);
    assert.equal(await adb.apiLevel(), 34);
    assert.equal(fake.calls().length, 1);
  });

  test('waitForBoot polls sys.boot_completed / dev.bootcomplete and pm', async () => {
    const { fake, adb } = setup([
      { match: 'getprop sys\\.boot_completed', stdout: '\n\n', times: 1 },
      { match: 'getprop sys\\.boot_completed', stdout: '1\n1\n' },
      { match: '^shell pm path android$', stdout: 'package:/system/framework/framework-res.apk\n' },
    ]);
    await adb.waitForBoot(20_000);
    assert.deepEqual(fake.commandLines(), [
      'wait-for-device',
      'shell getprop sys.boot_completed; getprop dev.bootcomplete',
      'shell getprop sys.boot_completed; getprop dev.bootcomplete',
      'shell pm path android',
    ]);
  });

  test('reboot waits for a new boot id, the boot, and roots again', async () => {
    const { fake, adb } = setup([
      { match: '^shell id -u$', stdout: '0\n' },
      { match: 'boot_id', stdout: 'boot-a\n', times: 2 },
      { match: 'boot_id', stdout: 'boot-b\n' },
      { match: 'getprop sys\\.boot_completed', stdout: '1\n1\n' },
      { match: '^shell pm path android$', stdout: 'package:x\n' },
      { match: '^root$', stdout: 'restarting adbd as root\n' },
    ]);
    await adb.reboot({ timeoutMs: 30_000 });
    const lines = fake.commandLines();
    assert.equal(lines[0], 'shell id -u');
    assert.equal(lines[1], 'shell cat /proc/sys/kernel/random/boot_id');
    // the framework reboot (ShutdownThread), not a plain `adb reboot`
    assert.equal(lines[2], 'shell svc power reboot');
    assert.ok(!lines.includes('reboot'), lines.join('\n'));
    assert.ok(lines.indexOf('root') > lines.lastIndexOf('shell pm path android'), lines.join('\n'));
  });

  test('reboot falls back to adb reboot when svc power reboot is not available', async () => {
    const { fake, adb } = setup([
      { match: '^shell id -u$', stdout: '0\n' },
      { match: '^shell svc power reboot$', stderr: '/system/bin/sh: svc: not found\n', code: 127 },
      { match: 'boot_id', stdout: 'boot-a\n', times: 3 },
      { match: 'boot_id', stdout: 'boot-b\n' },
      { match: 'getprop sys\\.boot_completed', stdout: '1\n1\n' },
      { match: '^shell pm path android$', stdout: 'package:x\n' },
      { match: '^root$', stdout: 'restarting adbd as root\n' },
    ]);
    await adb.reboot({ timeoutMs: 30_000 });
    const lines = fake.commandLines();
    assert.equal(lines[2], 'shell svc power reboot');
    assert.ok(lines.includes('reboot'), lines.join('\n'));
  });

  test('install builds -r -g and requires Success', async () => {
    const { fake, adb } = setup([
      { match: '^install -r -g ok\\.apk$', stdout: 'Performing Streamed Install\nSuccess\n' },
      { match: '^install bad\\.apk$', stdout: 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\n' },
    ]);
    await adb.install('ok.apk', { replace: true, grant: true });
    await assert.rejects(adb.install('bad.apk'), /INSTALL_FAILED_UPDATE_INCOMPATIBLE/);
    assert.deepEqual(fake.calls()[0]!.args, ['install', '-r', '-g', 'ok.apk']);
  });

  test('clearData, forceStop, amKill, grant, revoke, setAppOp', async () => {
    const { fake, adb } = setup([{ match: 'pm clear', stdout: 'Success\n' }]);
    await adb.clearData('com.x');
    await adb.forceStop('com.x');
    await adb.amKill('com.x');
    await adb.grant('com.x', 'android.permission.ACCESS_FINE_LOCATION');
    await adb.revoke('com.x', 'android.permission.ACCESS_FINE_LOCATION');
    await adb.setAppOp('com.x', 'RUN_ANY_IN_BACKGROUND', 'ignore');
    assert.deepEqual(fake.commandLines(), [
      'shell pm clear com.x',
      'shell am force-stop com.x',
      'shell am kill com.x',
      'shell pm grant com.x android.permission.ACCESS_FINE_LOCATION',
      'shell pm revoke com.x android.permission.ACCESS_FINE_LOCATION',
      'shell appops set com.x RUN_ANY_IN_BACKGROUND ignore',
    ]);
  });

  test('startApp resolves the launcher activity and starts it with am start -W', async () => {
    const { fake, adb } = setup([
      { match: 'resolve-activity', stdout: 'priority=0 preferredOrder=0\ncom.x/.MainActivity\n' },
      { match: 'am start', stdout: 'Status: ok\nLaunchState: COLD\n' },
    ]);
    await adb.startApp('com.x');
    await adb.startApp('com.x');
    const lines = fake.commandLines();
    assert.equal(lines.filter((l) => l.includes('resolve-activity')).length, 1);
    assert.equal(lines[1], 'shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.x/.MainActivity');
  });

  test('stopApp uses am stop-app on API 34 and kill -9 below', async () => {
    const api34 = setup([{ match: 'ro\\.build\\.version\\.sdk', stdout: '34' }]);
    await api34.adb.stopApp('com.x');
    assert.ok(api34.fake.commandLines().includes('shell am stop-app com.x'));
    const api30 = setup([
      { match: 'ro\\.build\\.version\\.sdk', stdout: '30' },
      { match: '^shell ps -A -o PID,NAME$', stdout: '  PID NAME\n  321 com.x\n', times: 1 },
      { match: '^shell ps -A -o PID,NAME$', stdout: '  PID NAME\n' },
      { match: '^shell id -u$', stdout: '0' },
    ]);
    await api30.adb.stopApp('com.x');
    assert.ok(api30.fake.commandLines().includes('shell kill -9 321'));
  });

  test('killHard kills every process of the app (root), or via run-as without root', async () => {
    const ps = '  PID NAME\n  101 com.x\n  102 com.x:remote\n  103 com.x2\n  104 com.y\n';
    const after = '  PID NAME\n  103 com.x2\n  104 com.y\n';
    const rooted = setup([
      { match: '^shell ps -A -o PID,NAME$', stdout: ps, times: 1 },
      { match: '^shell ps -A -o PID,NAME$', stdout: after },
      { match: '^shell id -u$', stdout: '0\n' },
    ]);
    assert.deepEqual(await rooted.adb.killHard('com.x'), [101, 102]);
    assert.ok(rooted.fake.commandLines().includes('shell kill -9 101 102'));
    const shellUser = setup([
      { match: '^shell ps -A -o PID,NAME$', stdout: ps, times: 1 },
      { match: '^shell ps -A -o PID,NAME$', stdout: after },
      { match: '^shell id -u$', stdout: '2000\n' },
    ]);
    await shellUser.adb.killHard('com.x');
    assert.ok(shellUser.fake.commandLines().includes('shell run-as com.x kill -9 101 102'));
    const none = setup([{ match: '^shell ps', stdout: '  PID NAME\n' }]);
    assert.deepEqual(await none.adb.killHard('com.x'), []);
  });

  test('killHard: kill exiting 1 for an already gone pid is not an error; a surviving pid is', async () => {
    const ps = '  PID NAME\n  101 com.x\n  102 com.x:remote\n';
    const gone = setup([
      { match: '^shell ps -A -o PID,NAME$', stdout: ps, times: 1 },
      { match: '^shell ps -A -o PID,NAME$', stdout: '  PID NAME\n' },
      { match: '^shell id -u$', stdout: '0\n' },
      { match: '^shell kill -9', code: 1, stderr: 'kill: 102: No such process' },
    ]);
    assert.deepEqual(await gone.adb.killHard('com.x'), [101, 102]);
    const survivor = setup([
      { match: '^shell ps -A -o PID,NAME$', stdout: ps },
      { match: '^shell id -u$', stdout: '0\n' },
      { match: '^shell kill -9', code: 1, stderr: 'kill: 101: Operation not permitted' },
    ]);
    await assert.rejects(survivor.adb.killHard('com.x'), /did not kill com\.x: kill: 101: Operation not permitted/);
  });

  test('appUid reads pm list packages -U', async () => {
    const { adb } = setup([{ match: 'pm list packages -U com\\.x', stdout: 'package:com.x.other uid:10001\npackage:com.x uid:10123\n' }]);
    assert.equal(await adb.appUid('com.x'), 10123);
    const missing = setup([{ match: 'pm list packages', stdout: '' }]);
    await assert.rejects(missing.adb.appUid('com.x'), /no uid for com\.x/);
  });

  test('pidof: pid or null', async () => {
    const running = setup([{ match: 'pidof com\\.x', stdout: '4321\n' }]);
    assert.equal(await running.adb.pidof('com.x'), 4321);
    const stopped = setup([{ match: 'pidof com\\.x', code: 1 }]);
    assert.equal(await stopped.adb.pidof('com.x'), null);
  });

  test('broadcast builds -n, --include-stopped-packages, --receiver-foreground and typed extras', async () => {
    const { fake, adb } = setup([{ match: 'am broadcast', stdout: 'Broadcast completed: result=0' }]);
    await adb.broadcast('com.x.E2E', {
      component: 'com.x/.e2e.E2eCommandReceiver',
      foreground: true,
      extras: { s: 'a b', i: 5, l: 5_000_000_000, f: 1.5, z: true },
    });
    await adb.broadcast('android.intent.action.X', { includeStopped: false });
    const lines = fake.commandLines();
    assert.equal(
      lines[0],
      "shell am broadcast -a com.x.E2E -n com.x/.e2e.E2eCommandReceiver --include-stopped-packages --receiver-foreground " +
        "--es s 'a b' --ei i 5 --el l 5000000000 --ef f 1.5 --ez z true",
    );
    assert.equal(lines[1], 'shell am broadcast -a android.intent.action.X');
  });

  test('forward parses the local port; removeForward', async () => {
    const { fake, adb } = setup([{ match: '^forward tcp:0 localabstract:sock$', stdout: '41234\n' }]);
    assert.equal(await adb.forward('localabstract:sock'), 41234);
    await adb.removeForward(41234);
    assert.deepEqual(fake.calls()[1]!.args, ['forward', '--remove', 'tcp:41234']);
  });

  test('runAsWrite sends the text on stdin into run-as sh -c and verifies it; runAsRemove; runAsCat', async () => {
    const text = '{"e2e":true}';
    const { fake, adb } = setup([{ match: '^shell run-as com\\.x cat files/e2e/example\\.json$', stdout: text }]);
    await adb.runAsWrite('com.x', 'files/e2e/example.json', text);
    await adb.runAsRemove('com.x', 'files/e2e/example.json');
    const calls = fake.calls();
    assert.deepEqual(calls[0]!.args, ['shell', "run-as com.x sh -c 'mkdir -p files/e2e && cat > files/e2e/example.json'"]);
    assert.equal(calls[0]!.input, text);
    assert.deepEqual(calls[1]!.args, ['shell', 'run-as com.x cat files/e2e/example.json']);
    assert.deepEqual(calls[2]!.args, ['shell', 'run-as com.x rm -f files/e2e/example.json']);
    await assert.rejects(adb.runAsWrite('com.x', '../evil.json', 'x'), /unsupported app-relative path/);
    await assert.rejects(adb.runAsWrite('com.x', 'files/a b.json', 'x'), /unsupported app-relative path/);
  });

  test('runAsWrite fails when the read-back differs', async () => {
    const { adb } = setup([{ match: 'run-as com\\.x cat', stdout: '' }]);
    await assert.rejects(adb.runAsWrite('com.x', 'files/e2e/a.json', '{}'), /did not store the expected content/);
  });

  test('screenshot writes the exec-out bytes', async () => {
    const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0xff]);
    const { fake, adb } = setup([{ match: '^exec-out screencap -p$', stdoutBase64: png.toString('base64') }]);
    const file = join(fake.dir, 'shot.png');
    await adb.screenshot(file);
    assert.deepEqual(readFileSync(file), png);
  });
});

describe('Adb location, power, connectivity, time', () => {
  test('geoFix sends longitude first; emu KO rejects', async () => {
    const { fake, adb } = setup([
      { match: '^emu geo fix 46\\.6753000 24\\.7136000$', stdout: 'OK\n' },
      { match: '^emu geo fix 1\\.0000000 2\\.0000000 600 7$', stdout: 'OK\n' },
      { match: '^emu', stdout: 'KO: bad command\n' },
    ]);
    await adb.geoFix(24.7136, 46.6753);
    await adb.geoFix(2, 1, { altitude: 600, satellites: 7 });
    await assert.rejects(adb.geoFix(0, 0), /KO: bad command/);
    assert.deepEqual(fake.calls()[0]!.args, ['emu', 'geo', 'fix', '46.6753000', '24.7136000']);
    await assert.rejects(adb.geoFix(Number.NaN, 1), /finite/);
  });

  test('interpolateRoute: points every step meters including both ends', () => {
    const points = interpolateRoute(ROUTES.cityLoop3km, 100);
    assert.equal(points.length, 31);
    assert.deepEqual(points[0], ROUTES.cityLoop3km[0]);
    assert.ok(assertions.haversineMeters(points[30]!, ROUTES.cityLoop3km[4]!) < 0.01);
    for (let i = 1; i < points.length; i++) {
      const d = assertions.haversineMeters(points[i - 1]!, points[i]!);
      assert.ok(Math.abs(d - 100) < 1, `step ${i} is ${d} m`);
    }
    const uneven = interpolateRoute([{ lat: 0, lon: 0 }, { lat: 0, lon: 0.0005 }], 20);
    assert.ok(assertions.haversineMeters(uneven[uneven.length - 1]!, { lat: 0, lon: 0.0005 }) < 0.01);
    assert.throws(() => interpolateRoute(ROUTES.leaveHq, 0), /positive/);
  });

  test('playRoute sends the interpolated fixes in order on a fixed schedule', async () => {
    const { fake, adb } = setup([{ match: '^emu geo fix', stdout: 'OK\n' }]);
    const start = { lat: 24.7136, lon: 46.6753 };
    const end = assertions.offsetMeters(start, 20, 0);
    const began = Date.now();
    await adb.playRoute([start, end], { speedMps: 50, intervalMs: 100 });
    const fixes = fake.calls().map((c) => c.args);
    assert.equal(fixes.length, 5); // 0, 5, 10, 15, 20 m
    assert.deepEqual(fixes[0], ['emu', 'geo', 'fix', '46.6753000', '24.7136000']);
    assert.equal(fixes[4]![3], end.lon.toFixed(7));
    assert.equal(fixes[4]![4], end.lat.toFixed(7));
    assert.ok(Date.now() - began >= 400);
  });

  test('test providers: API 31+ commands, a clear error below', async () => {
    const api31 = setup([{ match: 'ro\\.build\\.version\\.sdk', stdout: '31' }]);
    await api31.adb.addTestProvider('gps');
    await api31.adb.setTestLocation('gps', 24.7136, 46.6753, 5);
    await api31.adb.removeTestProvider('gps');
    const lines = api31.fake.commandLines().filter((l) => l.includes('cmd location'));
    assert.deepEqual(lines, [
      'shell cmd location providers add-test-provider gps',
      'shell cmd location providers set-test-provider-enabled gps true',
      'shell cmd location providers set-test-provider-location gps --location 24.7136000,46.6753000 --accuracy 5',
      'shell cmd location providers remove-test-provider gps',
    ]);
    const api30 = setup([{ match: 'ro\\.build\\.version\\.sdk', stdout: '30' }]);
    await assert.rejects(api30.adb.addTestProvider(), /needs API 31 or newer \(device API 30\)/);
    await api30.adb.removeTestProvider();
    assert.ok(!api30.fake.commandLines().some((l) => l.includes('remove-test-provider')));
  });

  test('setLocationEnabled uses cmd location, falls back to location_mode', async () => {
    const modern = setup();
    await modern.adb.setLocationEnabled(false);
    assert.deepEqual(modern.fake.commandLines(), ['shell cmd location set-location-enabled false']);
    const old = setup([{ match: 'cmd location', code: 255, stdout: 'Unknown command: set-location-enabled' }]);
    await old.adb.setLocationEnabled(true);
    assert.deepEqual(old.fake.commandLines(), ['shell cmd location set-location-enabled true', 'shell settings put secure location_mode 3']);
    const check = setup([{ match: 'is-location-enabled', stdout: 'true\n' }]);
    assert.equal(await check.adb.isLocationEnabled(), true);
  });

  test('airplane mode: cmd connectivity, else settings + broadcast', async () => {
    const modern = setup();
    await modern.adb.setAirplaneMode(true);
    assert.deepEqual(modern.fake.commandLines(), ['shell cmd connectivity airplane-mode enable']);
    const old = setup([{ match: 'cmd connectivity', code: 255, stderr: 'Unknown command' }]);
    await old.adb.setAirplaneMode(false);
    assert.deepEqual(old.fake.commandLines(), [
      'shell cmd connectivity airplane-mode disable',
      'shell settings put global airplane_mode_on 0',
      'shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false',
    ]);
  });

  test('battery, wifi, data, auto time, font scale, keys', async () => {
    const { fake, adb } = setup();
    await adb.batteryUnplug();
    await adb.batterySetLevel(15);
    await adb.batteryReset();
    await adb.setWifi(false);
    await adb.setData(true);
    await adb.setAutoTime(false);
    await adb.setFontScale(1.3);
    await adb.keyHome();
    await adb.screenOff();
    await adb.screenOn();
    assert.deepEqual(fake.commandLines(), [
      'shell dumpsys battery unplug',
      'shell dumpsys battery set level 15',
      'shell dumpsys battery reset',
      'shell svc wifi disable',
      'shell svc data enable',
      'shell settings put global auto_time 0',
      'shell settings put global auto_time_zone 0',
      'shell settings put system font_scale 1.3',
      'shell input keyevent KEYCODE_HOME',
      'shell input keyevent KEYCODE_SLEEP',
      'shell input keyevent KEYCODE_WAKEUP',
      'shell wm dismiss-keyguard',
    ]);
    await assert.rejects(adb.batterySetLevel(101), /0\.\.100/);
  });

  test('setTime uses the toybox date format in UTC and checks the result (root)', async () => {
    const target = Date.UTC(2026, 8, 27, 1, 58, 0);
    const { fake, adb } = setup([
      { match: '^shell id -u$', stdout: '0\n' },
      { match: '^shell date -u ', stdout: '' },
      { match: '^shell date \\+%s%N$', stdout: `${target}000123\n` },
    ]);
    await adb.setTime(target + 200);
    assert.ok(fake.commandLines().includes('shell date -u 092701582026.00'));
    const noRoot = setup([{ match: '^shell id -u$', stdout: '2000\n' }]);
    await assert.rejects(noRoot.adb.setTime(target), /needs adb root/);
  });

  test('deviceTime: nanoseconds, or seconds where %N is unsupported', async () => {
    const ns = setup([{ match: 'date \\+%s%N', stdout: '1790000000123456789\n' }]);
    assert.equal(await ns.adb.deviceTime(), 1790000000123);
    const s = setup([
      { match: 'date \\+%s%N', stdout: '1790000000N\n' },
      { match: 'date \\+%s$', stdout: '1790000000\n' },
    ]);
    assert.equal(await s.adb.deviceTime(), 1790000000000);
  });

  test('setTimezone accepts GMT when the property stays empty', async () => {
    const { adb } = setup([{ match: 'getprop persist\\.sys\\.timezone', stdout: '\n' }]);
    await adb.setTimezone('GMT');
  });

  test('setTimezone uses cmd alarm and checks persist.sys.timezone', async () => {
    const { fake, adb } = setup([{ match: 'getprop persist\\.sys\\.timezone', stdout: 'Asia/Riyadh\n' }]);
    await adb.setTimezone('Asia/Riyadh');
    assert.equal(fake.commandLines()[0], 'shell cmd alarm set-timezone Asia/Riyadh');
    const failing = setup([
      { match: 'getprop persist\\.sys\\.timezone', stdout: 'UTC\n' },
      { match: '^shell id -u$', stdout: '2000\n' },
    ]);
    await assert.rejects(failing.adb.setTimezone('Asia/Riyadh'), /persist\.sys\.timezone is 'UTC'/);
  });
});

describe('DeviceIdle and Logcat', () => {
  test('deviceidle commands', async () => {
    const { fake, adb } = setup([
      { match: 'enabled deep', stdout: '1\n' },
      { match: 'force-idle', stdout: 'Now forced in to deep idle mode\n' },
      { match: 'step deep', stdout: 'Stepped to deep: IDLE_PENDING\n' },
      { match: 'get deep', stdout: 'IDLE\n' },
      { match: 'whitelist \\+com\\.x', stdout: 'Added: com.x\n' },
    ]);
    await adb.deviceIdle.forceIdle();
    assert.equal(await adb.deviceIdle.step(), 'IDLE_PENDING');
    assert.equal(await adb.deviceIdle.state(), 'IDLE');
    await adb.deviceIdle.whitelistAdd('com.x');
    await adb.deviceIdle.whitelistRemove('com.x');
    await adb.deviceIdle.tempWhitelist('com.x', 60_000);
    await adb.deviceIdle.unforce();
    assert.deepEqual(fake.commandLines(), [
      'shell dumpsys deviceidle enabled deep',
      'shell dumpsys deviceidle force-idle',
      'shell dumpsys deviceidle step deep',
      'shell dumpsys deviceidle get deep',
      'shell dumpsys deviceidle whitelist +com.x',
      'shell dumpsys deviceidle whitelist -com.x',
      'shell dumpsys deviceidle tempwhitelist -d 60000 com.x',
      'shell dumpsys deviceidle unforce',
    ]);
    const disabled = setup([{ match: 'force-idle', stdout: 'Unable to go deep idle; not enabled\n' }]);
    await assert.rejects(disabled.adb.deviceIdle.forceIdle(), /not enabled/);
  });

  test('forceIdle enables deep idle where it is disabled (emulators); restoreDeepIdle disables it again', async () => {
    const { fake, adb } = setup([
      { match: 'enabled deep', stdout: '0\n' },
      { match: 'enable deep', stdout: 'Deep idle mode enabled\n' },
      { match: 'force-idle', stdout: 'Now forced in to deep idle mode\n' },
    ]);
    await adb.deviceIdle.forceIdle();
    await adb.deviceIdle.restoreDeepIdle();
    await adb.deviceIdle.restoreDeepIdle(); // nothing left to restore in this process
    const lines = fake.commandLines();
    assert.deepEqual(lines.slice(0, 4), [
      'shell dumpsys deviceidle enabled deep',
      'shell dumpsys deviceidle enable deep',
      'shell setprop debug.e2ekit.deep_idle_enabled 1',
      'shell dumpsys deviceidle force-idle',
    ]);
    assert.equal(lines.filter((l) => l === 'shell dumpsys deviceidle disable deep').length, 1);
    assert.ok(lines.includes('shell setprop debug.e2ekit.deep_idle_enabled 0'));
    // another process: the device property says the kit enabled it
    const other = setup([{ match: 'getprop debug\\.e2ekit\\.deep_idle_enabled', stdout: '1\n' }]);
    await other.adb.deviceIdle.restoreDeepIdle();
    assert.ok(other.fake.commandLines().includes('shell dumpsys deviceidle disable deep'));
  });

  test('battery status and route loop', async () => {
    const { fake, adb } = setup([{ match: '^emu', stdout: 'OK\n' }]);
    await adb.batterySetStatus('discharging');
    await adb.batterySetStatus(2);
    await assert.rejects(adb.batterySetStatus(9), /1\.\.5/);
    const controller = new AbortController();
    setTimeout(() => controller.abort(), 350);
    const start = { lat: 24.7136, lon: 46.6753 };
    await adb.playRouteLoop([start, assertions.offsetMeters(start, 10, 0)], { speedMps: 100, intervalMs: 50, signal: controller.signal });
    const lines = fake.commandLines();
    assert.deepEqual(lines.slice(0, 2), ['shell dumpsys battery set status 3', 'shell dumpsys battery set status 2']);
    assert.ok(lines.filter((l) => l.startsWith('emu geo fix')).length > 2, 'the route was replayed more than once');
  });

  test('logcat dump, crash buffer and clear arguments', async () => {
    const { fake, adb } = setup([{ match: '^logcat -b all -c$', code: 1, stderr: 'failed to clear the kernel log' }]);
    await adb.logcat.dump({ buffers: ['main'], since: new Date(1790000000123), filters: ['LT-E2E:I', '*:S'] });
    await adb.logcat.crashBuffer();
    await adb.logcat.clear();
    assert.deepEqual(fake.commandLines(), [
      `logcat -d -v threadtime -v UTC -v year -b main -T 1790000000.123 LT-E2E:I *:S`,
      'logcat -d -v threadtime -v UTC -v year -b crash',
      'logcat -b all -c',
      'logcat -b main -b system -b crash -b events -c',
    ]);
  });

  test('re() escapes regex characters for fake rules', () => {
    assert.equal(new RegExp(re('a.b(c)')).test('a.b(c)'), true);
  });
});

test('persistedPermissionsGranted reads the app section of the runtime-permission XML', () => {
  const xml = [
    '<runtime-permissions version="10">',
    '<pkg name="com.other"><perm name="android.permission.ACCESS_FINE_LOCATION" granted="true" flags="0" /></pkg>',
    '<pkg name="com.app">',
    '<perm flags="0" granted="true" name="android.permission.ACCESS_FINE_LOCATION" />',
    '<perm name="android.permission.ACCESS_BACKGROUND_LOCATION" granted="false" flags="0" />',
    '</pkg>',
    '</runtime-permissions>',
  ].join('\n');
  assert.equal(persistedPermissionsGranted(xml, 'com.app', ['android.permission.ACCESS_FINE_LOCATION']), true);
  assert.equal(
    persistedPermissionsGranted(xml, 'com.app', ['android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_BACKGROUND_LOCATION']),
    false,
  );
  assert.equal(persistedPermissionsGranted(xml, 'com.missing', ['android.permission.ACCESS_FINE_LOCATION']), false);
});

test('waitForPersistedPermissions polls the persisted file until the grants appear (root)', async () => {
  const granted = '<pkg name="com.app"><perm name="android.permission.ACCESS_FINE_LOCATION" granted="true" flags="0" /></pkg>';
  const { fake, adb } = setup([
    { match: '^shell id -u$', stdout: '0\n' },
    { match: 'getprop ro\\.build\\.version\\.sdk', stdout: '34\n' },
    { match: 'runtime-permissions\\.xml', stdout: '<pkg name="com.app"></pkg>', times: 2 },
    { match: 'runtime-permissions\\.xml', stdout: granted },
  ]);
  const ok = await adb.waitForPersistedPermissions('com.app', ['android.permission.ACCESS_FINE_LOCATION'], { intervalMs: 10 });
  assert.equal(ok, true);
  const reads = fake.commandLines().filter((line) => line.includes('runtime-permissions.xml'));
  assert.equal(reads.length, 3);
  assert.match(reads[0]!, /apexdata\/com\.android\.permission\/runtime-permissions\.xml/);
});

test('waitForPersistedPermissions returns false without root and does not read the file', async () => {
  const { fake, adb } = setup([{ match: '^shell id -u$', stdout: '2000\n' }]);
  assert.equal(await adb.waitForPersistedPermissions('com.app', ['android.permission.ACCESS_FINE_LOCATION']), false);
  assert.ok(!fake.commandLines().some((line) => line.includes('runtime-permissions')));
});
