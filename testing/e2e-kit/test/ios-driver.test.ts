import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { IosDriver, PluginCallError, Simctl, SimctlError, isAlive, parseLaunchPid, parseRuntime } from '../src/index.ts';

const xcrun = fileURLToPath(new URL('./helpers/fake-xcrun.mjs', import.meta.url));

function fake() {
  const data = mkdtempSync(join(tmpdir(), 'fake-sim-'));
  process.env['FAKE_SIM_DATA'] = data;
  const simctl = new Simctl({ device: 'FAKE-UDID', xcrun, timeoutMs: 20_000 });
  const driver = new IosDriver({ simctl, appId: 'com.brickssoft.locationtracking.example', logDir: join(data, 'logs') });
  return { data, simctl, driver, calls: () => readFileSync(join(data, 'calls.log'), 'utf8').trim().split('\n') };
}

test('parseRuntime and parseLaunchPid read simctl output', () => {
  const list = JSON.stringify({ devices: { 'com.apple.CoreSimulator.SimRuntime.iOS-16-4': [{ udid: 'A' }], 'com.apple.CoreSimulator.SimRuntime.iOS-26-0': [{ udid: 'B' }] } });
  assert.deepEqual(parseRuntime(list, 'A'), { identifier: 'com.apple.CoreSimulator.SimRuntime.iOS-16-4', version: '16.4', major: 16 });
  assert.equal(parseRuntime(list, 'B')?.major, 26);
  assert.equal(parseRuntime(list, 'C'), undefined);
  assert.equal(parseLaunchPid('com.brickssoft.locationtracking.example: 4242\n'), 4242);
  assert.equal(parseLaunchPid('An error was encountered'), undefined);
});

test('Simctl reports a failing command with its arguments and output', async () => {
  const { simctl, data } = fake();
  try {
    await assert.rejects(simctl.setLocation({ lat: 91, lon: 0 }), (error: unknown) => error instanceof SimctlError && error.message.includes('simctl location FAKE-UDID set 91.0000000,0.0000000 exited with 3') && error.message.includes('Invalid coordinate'));
    assert.equal((await simctl.runtime()).major, 17);
  } finally {
    rmSync(data, { recursive: true, force: true });
  }
});

test('IosDriver launches the scenario host, calls it through the file channel and kills it', async () => {
  const { driver, data, calls } = fake();
  try {
    await driver.reset({ location: 'always', position: { lat: 24.7136, lon: 46.6753 } });
    const pid = await driver.launch();
    assert.equal(await driver.pid(), pid);
    await driver.captureEvents(['enabledchange']);
    assert.deepEqual(await driver.call('start'), { enabled: true });
    assert.equal((await driver.call<{ enabled: boolean }>('getState')).enabled, true);
    const events = await driver.drainEvents();
    assert.deepEqual(events.map((event) => [event.plugin, event.name, event.payload.enabled]), [['LocationTracking', 'enabledchange', true]]);
    await assert.rejects(driver.call('startGeofences'), (error: unknown) => error instanceof PluginCallError && error.code === 'UNAVAILABLE');
    assert.equal(existsSync(join(data, 'Library/Application Support/E2E/command.json')), false, 'an answered command is removed, so a relaunched host cannot run it again');
    await driver.background();
    await driver.kill();
    assert.equal(isAlive(pid), false);
    assert.equal(await driver.pid(), null);
    const log = calls();
    assert.ok(log.includes('simctl privacy FAKE-UDID grant location-always com.brickssoft.locationtracking.example'), log.join('\n'));
    assert.ok(log.includes('simctl location FAKE-UDID set 24.7136000,46.6753000'), log.join('\n'));
    assert.ok(log.includes('simctl launch --terminate-running-process FAKE-UDID com.brickssoft.locationtracking.example --e2e-scenario'), log.join('\n'));
    assert.ok(log.includes('simctl launch FAKE-UDID com.apple.Preferences'), log.join('\n'));
  } finally {
    const pid = await driver.pid().catch(() => null);
    if (pid) process.kill(pid, 'SIGKILL');
    rmSync(data, { recursive: true, force: true });
  }
});
