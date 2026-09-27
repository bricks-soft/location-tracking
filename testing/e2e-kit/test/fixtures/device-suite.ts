// Fixture for test/scenario.test.ts: a non-dry run against the fake adb (E2E_ADB), which reports a non-root API 34
// emulator with Google Play services and no crashes.
import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import { scenario, sleep } from '../../src/index.ts';

scenario(
  'P-H04',
  'long scenario is skipped without E2E_INCLUDE_LONG',
  async () => {
    throw new Error('the body of P-H04 ran');
  },
  { requires: { long: true } },
);

scenario(
  'P-L03',
  'root scenario is skipped on a non-root device',
  async () => {
    throw new Error('the body of P-L03 ran');
  },
  { requires: { root: true } },
);

scenario('P-L02', 'passes with the shared back office and testConfig', async (ctx) => {
  assert.equal(ctx.appId, 'com.brickssoft.locationtracking.example');
  assert.deepEqual(ctx.device, { api: 34, root: false, gms: true, emulator: true, model: 'sdk_gphone64_x86_64', abi: 'x86_64' });
  const office = await ctx.backOffice();
  const config = (await ctx.testConfig({ patch: { persistence: { extras: { worker: 7 } } } })) as Record<string, any>;
  assert.deepEqual(config['persistence'].extras, { scenario: 'P-L02', worker: 7 });
  assert.equal(config['http'].url, office.url('/locations'));
  const health = await fetch(office.hostUrl('/__health'));
  assert.equal(health.status, 200);
  ctx.log('back office answered');
});

scenario('P-L01', 'fails and collects artifacts', async (ctx) => {
  await (await ctx.backOffice()).waitFor(() => true);
  throw new Error('boom from P-L01');
});

scenario(
  'P-L04',
  'times out; artifacts and teardowns still run',
  async (ctx) => {
    ctx.onTeardown(() => writeFileSync(`${ctx.env.artifactsDir}/P-L04-teardown.txt`, 'second'));
    ctx.onTeardown(() => writeFileSync(`${ctx.env.artifactsDir}/P-L04-teardown.txt`, 'first'));
    // A cooperative body: the kit aborts ctx.signal at the timeout, which ends this sleep.
    await sleep(60_000, ctx.signal);
  },
  { timeoutMs: 1500 },
);

scenario('P-L05', 'a failing teardown fails a passing scenario', async (ctx) => {
  ctx.onTeardown(() => {
    throw new Error('teardown boom');
  });
});
