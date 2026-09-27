import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
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
  return spawnSync(process.execPath, ['--test', '--test-reporter=tap', file], { encoding: 'utf8', env: childEnv });
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
