import assert from 'node:assert/strict';
import { after, describe, test } from 'node:test';
import { APP_IDS, Adb, AppUnderTest, PERMISSIONS, SERVICES, parseRuntimePermissions, parseServiceRecords, permissionsForApi, readEnv } from '../src/index.ts';
import { FakeAdb, re, type FakeRule } from './helpers/fake-adb.ts';
import { FakeDevTools } from './helpers/fake-devtools.ts';

const cleanups: Array<() => Promise<void> | void> = [];
after(async () => {
  for (const cleanup of cleanups) await cleanup();
});

function deviceRules(appId: string, api = 34): FakeRule[] {
  return [
    { match: 'getprop ro\\.build\\.version\\.sdk', stdout: `${api}\n` },
    { match: '^shell id -u$', stdout: '2000\n' },
    { match: `^shell pm path ${re(appId)}$`, stdout: 'package:/data/app/base.apk\n' },
    { match: `^shell pm clear ${re(appId)}$`, stdout: 'Success\n' },
    { match: `^shell run-as ${re(appId)} cat files/e2e/example\\.json$`, stdout: '{"e2e":true}' },
    { match: `^shell run-as ${re(appId)} cat files/e2e/ff-overrides\\.json$`, stdout: '{"stopAt":"03:00","autoStart":false}' },
    { match: `^shell dumpsys package ${re(appId)}$`, stdout: PACKAGE_DUMP },
  ];
}

const PACKAGE_DUMP = [
  'Packages:',
  '  Package [x] (abc):',
  '    install permissions:',
  '      android.permission.INTERNET: granted=true',
  '    User 0: ceDataInode=1 installed=true',
  '      runtime permissions:',
  `        ${PERMISSIONS.fine}: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED ]`,
  '        android.permission.CAMERA: granted=false, flags=[ ]',
  `        ${PERMISSIONS.notifications}: granted=false, flags=[ ]`,
  '      disabledComponents:',
  '        com.x.Foo',
].join('\n');

function envWith(apk?: string) {
  return { ...readEnv({}), ...(apk ? { apk } : {}) };
}

function indexOf(lines: string[], pattern: RegExp): number {
  const index = lines.findIndex((line) => pattern.test(line));
  assert.ok(index >= 0, `no call matching ${pattern}:\n${lines.join('\n')}`);
  return index;
}

describe('AppUnderTest.prepare', () => {
  test('plugin app: neutral device, pm clear, permissions, exemption, example.json before the launch', async () => {
    const appId = APP_IDS.plugin;
    const devtools = new FakeDevTools();
    await devtools.start();
    const fake = new FakeAdb([
      ...deviceRules(appId),
      { match: 'resolve-activity', stdout: `${appId}/.MainActivity\n` },
      { match: 'am start', stdout: 'Status: ok\n' },
      { match: `pidof ${re(appId)}`, stdout: '4321\n' },
      { match: 'cat /proc/net/unix', stdout: '00: 00000002 0 10000 0001 01 1 @webview_devtools_remote_4321\n' },
      { match: '^forward tcp:0 ', stdout: `${devtools.port}\n` },
    ]);
    cleanups.push(() => fake.cleanup(), () => devtools.stop());
    const app = new AppUnderTest(new Adb({ adbPath: fake.path }), appId, envWith());
    await app.prepare({ launch: true, batteryExempt: true });
    const lines = fake.commandLines();

    // force-stop first, then the neutral device
    const firstStop = indexOf(lines, /^shell am force-stop /);
    assert.equal(firstStop, 0, lines.join('\n'));
    for (const pattern of [
      /^shell input keyevent KEYCODE_WAKEUP$/,
      /^shell dumpsys deviceidle unforce$/,
      /^shell dumpsys battery reset$/,
      /^shell cmd connectivity airplane-mode disable$/,
      /^shell svc wifi enable$/,
      /^shell svc data enable$/,
      /^shell cmd location set-location-enabled true$/,
      /^shell settings put global auto_time 1$/,
      /^shell settings put system font_scale 1$/,
      /^shell cmd location providers remove-test-provider gps$/,
      /^shell getprop debug\.e2ekit\.deep_idle_enabled$/,
    ]) {
      assert.ok(indexOf(lines, pattern) < indexOf(lines, /^shell pm clear /), String(pattern));
    }
    const forceStop = indexOf(lines, new RegExp(`^shell am force-stop ${re(appId)}$`));
    const clear = indexOf(lines, new RegExp(`^shell pm clear ${re(appId)}$`));
    const appops = indexOf(lines, new RegExp(`^shell appops reset ${re(appId)}$`));
    const grants = lines.filter((line) => line.startsWith(`shell pm grant ${appId} `)).map((line) => line.split(' ').pop());
    assert.deepEqual(grants, [
      PERMISSIONS.coarse,
      PERMISSIONS.fine,
      PERMISSIONS.background,
      PERMISSIONS.activity,
      PERMISSIONS.notifications,
      'android.permission.CAMERA',
    ]);
    const firstGrant = indexOf(lines, /^shell pm grant /);
    const exempt = indexOf(lines, new RegExp(`^shell dumpsys deviceidle whitelist \\+${re(appId)}$`));
    const write = indexOf(lines, /run-as .* sh -c 'mkdir -p files\/e2e && cat > files\/e2e\/example\.json'/);
    const start = indexOf(lines, /^shell am start -W /);
    assert.ok(forceStop < clear && clear < appops && appops < firstGrant && firstGrant < exempt && exempt < write && write < start, lines.join('\n'));
    const writeCall = fake.calls().find((call) => /cat > files\/e2e\/example\.json/.test(call.args.join(' ')))!;
    assert.equal(writeCall.input, '{"e2e":true}');
    // launched: the WebView driver is connected and reused
    const driver = await app.webView();
    assert.equal(await driver.evaluate('40 + 2'), 42);
    assert.equal(await app.webView(), driver);
    await app.dispose();
    assert.equal(driver.closed, true);
  });

  test('other app, clearData false: exact permissions (revokes), test files written and removed, no example.json', async () => {
    const appId = APP_IDS.fieldForce;
    const fake = new FakeAdb(deviceRules(appId, 33));
    cleanups.push(() => fake.cleanup());
    const app = new AppUnderTest(new Adb({ adbPath: fake.path }), appId, envWith());
    await app.prepare({
      clearData: false,
      permissions: [PERMISSIONS.fine, PERMISSIONS.coarse],
      testFiles: { 'ff-overrides.json': { stopAt: '03:00', autoStart: false }, 'example.json': null },
    });
    const lines = fake.commandLines();
    assert.ok(!lines.some((line) => line.includes('pm clear')));
    assert.deepEqual(
      lines.filter((line) => / pm (revoke|grant) /.test(line)),
      [
        `shell pm revoke ${appId} ${PERMISSIONS.notifications}`,
        `shell pm revoke ${appId} ${PERMISSIONS.activity}`,
        `shell pm revoke ${appId} ${PERMISSIONS.background}`,
        `shell pm grant ${appId} ${PERMISSIONS.coarse}`,
        `shell pm grant ${appId} ${PERMISSIONS.fine}`,
      ],
    );
    assert.ok(lines.includes(`shell dumpsys deviceidle whitelist -${appId}`));
    assert.ok(lines.some((line) => /cat > files\/e2e\/ff-overrides\.json/.test(line)));
    assert.ok(lines.includes(`shell run-as ${appId} rm -f files/e2e/example.json`));
    assert.ok(!lines.some((line) => /cat > files\/e2e\/example\.json/.test(line)));
    assert.ok(!lines.some((line) => line.includes('am start')));
  });

  test('plugin app: testFiles example.json null suppresses the default file', async () => {
    const appId = APP_IDS.plugin;
    const fake = new FakeAdb(deviceRules(appId));
    cleanups.push(() => fake.cleanup());
    await new AppUnderTest(new Adb({ adbPath: fake.path }), appId, envWith()).prepare({ testFiles: { 'example.json': null }, permissions: 'none' });
    const lines = fake.commandLines();
    assert.ok(!lines.some((line) => line.includes('example.json')));
    assert.ok(!lines.some((line) => line.includes('pm grant')));
  });

  test('a missing app is installed from E2E_APK, or fails with a clear message', async () => {
    const appId = 'com.missing.app';
    const rules: FakeRule[] = [
      { match: `^shell pm path ${re(appId)}$`, code: 1 },
      { match: '^install -r app\\.apk$', stdout: 'Success\n' },
      ...deviceRules(appId),
    ];
    const fake = new FakeAdb(rules);
    cleanups.push(() => fake.cleanup());
    await assert.rejects(
      new AppUnderTest(new Adb({ adbPath: fake.path }), appId, envWith()).prepare(),
      /com\.missing\.app is not installed; install it or set E2E_APK/,
    );
    await new AppUnderTest(new Adb({ adbPath: fake.path }), appId, envWith('app.apk')).prepare({ permissions: 'none' });
    assert.ok(fake.commandLines().includes('install -r app.apk'));
    await assert.rejects(new AppUnderTest(new Adb({ adbPath: fake.path }), appId, envWith()).reinstall(), /needs E2E_APK/);
  });

  test('with root, a device clock more than 30 s off the host is reset', async () => {
    const appId = APP_IDS.fieldForce;
    const fake = new FakeAdb([
      { match: '^shell id -u$', stdout: '0\n' },
      { match: 'date \\+%s%N', stdout: `${Date.now() - 3_600_000}000000\n`, times: 1 },
      { match: 'date \\+%s%N', stdout: `${Date.now()}000000\n` },
      ...deviceRules(appId),
    ]);
    cleanups.push(() => fake.cleanup());
    await new AppUnderTest(new Adb({ adbPath: fake.path }), appId, envWith()).prepare({ permissions: 'none' });
    assert.ok(fake.commandLines().some((line) => /^shell date -u \d{12}\.\d{2}$/.test(line)));
  });
});

describe('AppUnderTest process and service helpers', () => {
  test('waitForProcess / waitForNoProcess', async () => {
    const appId = APP_IDS.plugin;
    const fake = new FakeAdb([
      { match: 'pidof', code: 1, times: 2 },
      { match: 'pidof', stdout: '777\n', times: 1 },
      { match: 'pidof', code: 1 },
    ]);
    cleanups.push(() => fake.cleanup());
    const app = new AppUnderTest(new Adb({ adbPath: fake.path }), appId, envWith());
    assert.equal(await app.waitForProcess({ intervalMs: 20 }), 777);
    await app.waitForNoProcess({ intervalMs: 20 });
    fake.setRules([{ match: 'pidof', stdout: '777\n' }]);
    await assert.rejects(app.waitForNoProcess({ timeoutMs: 200, intervalMs: 20 }), /to have no process/);
  });

  test('isForegroundServiceRunning parses dumpsys activity services', async () => {
    const appId = APP_IDS.fieldForce;
    const dump = [
      'ACTIVITY MANAGER SERVICES (dumpsys activity services)',
      '  User 0 active services:',
      `  * ServiceRecord{a1 u0 ${appId}/${SERVICES.tracking}}`,
      `    intent={cmp=${appId}/${SERVICES.tracking}}`,
      '    isForeground=true foregroundId=7 foregroundNoti=Notification(...)',
      `  * ServiceRecord{b2 u0 ${appId}/${SERVICES.premise}}`,
      '    isForeground=false foregroundId=0',
      `  * ServiceRecord{c3 u0 ${appId}/.e2e.Helper}`,
      '    isForeground=true',
      '',
      'Connection bindings to services:',
    ].join('\n');
    assert.deepEqual(parseServiceRecords(dump), [
      { component: `${appId}/${SERVICES.tracking}`, isForeground: true },
      { component: `${appId}/${SERVICES.premise}`, isForeground: false },
      { component: `${appId}/${appId}.e2e.Helper`, isForeground: true },
    ]);
    const fake = new FakeAdb([{ match: 'dumpsys activity services', stdout: dump }]);
    cleanups.push(() => fake.cleanup());
    const app = new AppUnderTest(new Adb({ adbPath: fake.path }), appId, envWith());
    assert.equal(await app.isForegroundServiceRunning(), true);
    assert.equal(await app.isForegroundServiceRunning(SERVICES.premise), false);
    assert.equal(await app.isForegroundServiceRunning(`${appId}.e2e.Helper`), true);
  });

  test('parseRuntimePermissions reads the runtime permissions section only', () => {
    assert.deepEqual(parseRuntimePermissions(PACKAGE_DUMP), [PERMISSIONS.fine, 'android.permission.CAMERA', PERMISSIONS.notifications]);
    assert.deepEqual(parseRuntimePermissions('nothing'), []);
  });

  test('hasActivity reads dumpsys activity activities', async () => {
    const appId = APP_IDS.plugin;
    const fake = new FakeAdb([
      { match: 'dumpsys activity activities', stdout: `  * Task{1 #12 type=standard A=10123:${appId}}\n    * ActivityRecord{ab1 u0 ${appId}/.MainActivity t12}\n`, times: 1 },
      { match: 'dumpsys activity activities', stdout: `    * ActivityRecord{cd2 u0 ${appId}.other/.Main t13}\n` },
    ]);
    cleanups.push(() => fake.cleanup());
    const app = new AppUnderTest(new Adb({ adbPath: fake.path }), appId, envWith());
    assert.equal(await app.hasActivity(), true);
    assert.equal(await app.hasActivity(), false);
  });

  test('permissionsForApi', () => {
    assert.deepEqual(permissionsForApi(28), [PERMISSIONS.coarse, PERMISSIONS.fine]);
    assert.equal(permissionsForApi(29).length, 4);
    assert.equal(permissionsForApi(33).length, 5);
  });

  test('test file names are validated', async () => {
    const app = new AppUnderTest(new Adb({ adbPath: '/nonexistent' }), APP_IDS.plugin, envWith());
    await assert.rejects(app.writeTestFile('../x.json', {}), /plain file name/);
    await assert.rejects(app.removeTestFile('a/b.json'), /plain file name/);
  });
});
