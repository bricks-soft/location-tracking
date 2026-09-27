import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { FakeAdb } from './helpers/fake-adb.ts';
import {
  APP_IDS,
  CATALOGUE,
  appIdFor,
  catalogueOf,
  describeRequirements,
  readEnv,
  unmetRequirement,
  type DeviceProfile,
} from '../src/index.ts';

function runFixture(name: string, env: Record<string, string>) {
  const file = fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url));
  const childEnv: NodeJS.ProcessEnv = { ...process.env, ...env };
  // A nested runner that inherits NODE_TEST_CONTEXT reports to its parent in a binary format instead of TAP.
  delete childEnv['NODE_TEST_CONTEXT'];
  return spawnSync(process.execPath, ['--test', '--test-reporter=tap', file], { encoding: 'utf8', env: childEnv, timeout: 120_000 });
}

test('dry run registers and lists scenarios without running them', () => {
  const run = runFixture('dry-run-suite.ts', { E2E_DRY_RUN: '1' });
  assert.equal(run.status, 0, run.stdout + run.stderr);
  assert.match(run.stdout, /DRY-RUN P-L01 \| rapid start\/stop loop \| requires: - \| timeout 600s/);
  assert.match(run.stdout, /DRY-RUN P-H04 \| deep Doze spacing \| requires: root, api>=29, long \| timeout 2700s/);
  assert.match(run.stdout, /# skipped 2/);
  assert.match(run.stdout, /# fail 0/);
  assert.doesNotMatch(run.stdout + run.stderr, /ran during a dry run/);
});

test('an id outside the catalogue fails at registration', () => {
  const run = runFixture('unknown-id-suite.ts', { E2E_DRY_RUN: '1' });
  assert.notEqual(run.status, 0);
  assert.match(run.stdout + run.stderr, /unknown scenario id 'P-L99'/);
});

test('catalogue ids are unique and complete', () => {
  const ids = CATALOGUE.map((e) => e.id);
  assert.equal(new Set(ids).size, ids.length);
  assert.equal(catalogueOf('plugin-lifecycle').length, 13);
  assert.equal(catalogueOf('plugin-heartbeat').length, 10);
  assert.equal(catalogueOf('plugin-permissions').length, 11);
  assert.equal(catalogueOf('field-force').length, 12);
  assert.equal(catalogueOf('manual').length, 8);
});

test('environment defaults and app id selection', () => {
  const env = readEnv({ ANDROID_HOME: '/sdk' });
  assert.equal(env.backendPort, 8787);
  assert.equal(env.dryRun, false);
  assert.equal(env.adbPath, '/sdk/platform-tools/adb');
  assert.equal(appIdFor('P-L01', env), APP_IDS.plugin);
  assert.equal(appIdFor('F-01', env), APP_IDS.fieldForce);
  const custom = readEnv({ E2E_APP_ID: 'com.bricks.app', E2E_BACKEND_PORT: '9000', E2E_DRY_RUN: '1' });
  assert.equal(appIdFor('F-01', custom), 'com.bricks.app');
  assert.equal(custom.backendPort, 9000);
  assert.equal(custom.dryRun, true);
});

test('requirements are described and checked', () => {
  const device: DeviceProfile = { api: 30, root: false, gms: true, emulator: true, model: 'sdk', abi: 'x86_64' };
  assert.equal(describeRequirements(undefined), '-');
  assert.equal(describeRequirements({ gms: false, api: { max: 33 } }), 'api<=33, no-gms');
  assert.equal(unmetRequirement({ api: 29, gms: true }, device), undefined);
  assert.match(unmetRequirement({ root: true }, device) ?? '', /adb root/);
  assert.match(unmetRequirement({ api: 31 }, device) ?? '', /API >= 31/);
  assert.match(unmetRequirement({ gms: false }, device) ?? '', /without Google Play/);
});

test('a device run: requirement skips, shared back office, artifacts on failure, run log, clean exit', () => {
  const fake = new FakeAdb([
    { match: '^shell getprop sys\\.boot_completed', stdout: '1\n1\n' },
    { match: '^shell pm path android$', stdout: 'package:/system/framework/framework-res.apk\n' },
    { match: '^root$', stdout: 'adbd cannot run as root in production builds\n', code: 1 },
    {
      match: '^shell getprop$',
      stdout: '[ro.build.version.sdk]: [34]\n[ro.kernel.qemu]: [1]\n[ro.product.model]: [sdk_gphone64_x86_64]\n[ro.product.cpu.abi]: [x86_64]\n',
    },
    { match: '^shell pm list packages -e com\\.google\\.android\\.gms$', stdout: 'package:com.google.android.gms\n' },
    { match: '^shell id -u$', stdout: '2000\n' },
    { match: '^shell date \\+%s%N$', stdout: `${Date.now()}000000\n` },
  ]);
  const artifacts = mkdtempSync(join(tmpdir(), 'e2e-artifacts-'));
  try {
    const port = String(20_000 + Math.floor(Math.random() * 20_000));
    const run = runFixture('device-suite.ts', { E2E_ADB: fake.path, E2E_ARTIFACTS_DIR: artifacts, E2E_BACKEND_PORT: port, E2E_INCLUDE_LONG: '' });
    const output = run.stdout + run.stderr;
    assert.equal(run.error, undefined, 'the test process must exit by itself');
    assert.notEqual(run.status, 0, output);
    assert.match(run.stdout, /ok \d+ - P-H04 .*# SKIP long scenario: set E2E_INCLUDE_LONG=1/);
    assert.match(run.stdout, /ok \d+ - P-L03 .*# SKIP needs adb root/);
    assert.match(run.stdout, /\nok \d+ - P-L02 passes/);
    assert.match(run.stdout, /not ok \d+ - P-L01 fails and collects artifacts/);
    assert.match(output, /boom from P-L01/);
    assert.match(readFileSync(join(artifacts, 'P-L01', 'reason.txt'), 'utf8'), /boom from P-L01/);
    for (const name of ['logcat.txt', 'crash.txt', 'dumpsys-location.txt', 'dumpsys-alarm.txt', 'backoffice.log', 'records.json']) {
      assert.ok(existsSync(join(artifacts, 'P-L01', name)), `${name} collected`);
    }
    assert.ok(!existsSync(join(artifacts, 'P-L02')), 'no artifacts for a passing scenario');
    // the kit's own timeout: artifacts collected, teardowns run in reverse order, the process still exits
    assert.match(run.stdout, /not ok \d+ - P-L04 times out/);
    assert.match(output, /scenario P-L04 timed out after 2 s/);
    assert.match(readFileSync(join(artifacts, 'P-L04', 'reason.txt'), 'utf8'), /timed out/);
    assert.equal(readFileSync(join(artifacts, 'P-L04-teardown.txt'), 'utf8'), 'second');
    // a failing teardown fails a scenario whose body passed
    assert.match(run.stdout, /not ok \d+ - P-L05 a failing teardown/);
    assert.match(output, /teardown boom/);
    const runLog = readFileSync(join(artifacts, '_run', 'backoffice.log'), 'utf8');
    assert.match(runLog, /mock back office started on port \d+ by device-suite\.ts/);
    assert.match(runLog, /\[P-L02\] \S+ #\d+ GET \/__health -> 200/);
    // P-L01 marked the crash scanner and collected artifacts through adb
    const lines = fake.commandLines();
    assert.ok(lines.includes('logcat -d -v threadtime -v UTC -v year -b crash -b main -b system'));
    assert.ok(lines.includes('exec-out screencap -p'));
  } finally {
    fake.cleanup();
    rmSync(artifacts, { recursive: true, force: true });
  }
});
