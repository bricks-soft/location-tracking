import assert from 'node:assert/strict';
import { after, describe, test } from 'node:test';
import { Adb, PluginCallError, WebViewDriver, findDevToolsSocket, pickAppTarget } from '../src/index.ts';
import { FakeAdb, type FakeRule } from './helpers/fake-adb.ts';
import { FakeDevTools, type FakeDevToolsOptions } from './helpers/fake-devtools.ts';

const APP = 'com.brickssoft.locationtracking.example';
const cleanups: Array<() => Promise<void> | void> = [];
after(async () => {
  for (const cleanup of cleanups) await cleanup();
});

const UNIX_WITHOUT = [
  'Num       RefCount Protocol Flags    Type St Inode Path',
  '0000000000000000: 00000002 00000000 00010000 0001 01 11111 @chrome_devtools_remote',
].join('\n');
const UNIX_WITH = `${UNIX_WITHOUT}\n0000000000000000: 00000002 00000000 00010000 0001 01 22222 @webview_devtools_remote_4321\n`;

async function setup(options: FakeDevToolsOptions & { socketLate?: boolean } = {}): Promise<{ fake: FakeAdb; devtools: FakeDevTools; adb: Adb }> {
  const devtools = new FakeDevTools(options);
  await devtools.start();
  const rules: FakeRule[] = [
    { match: `^shell pidof ${APP.replace(/\./g, '\\.')}$`, stdout: '4321\n' },
    ...(options.socketLate ? [{ match: 'cat /proc/net/unix', stdout: UNIX_WITHOUT, times: 2 }] : []),
    { match: 'cat /proc/net/unix', stdout: UNIX_WITH },
    { match: '^forward tcp:0 localabstract:webview_devtools_remote_4321$', stdout: `${devtools.port}\n` },
  ];
  const fake = new FakeAdb(rules);
  cleanups.push(() => fake.cleanup(), () => devtools.stop());
  return { fake, devtools, adb: new Adb({ adbPath: fake.path }) };
}

describe('WebViewDriver helpers', () => {
  test('findDevToolsSocket matches the pid exactly', () => {
    assert.equal(findDevToolsSocket(UNIX_WITH, 4321), 'webview_devtools_remote_4321');
    assert.equal(findDevToolsSocket(UNIX_WITH, 432), undefined);
    assert.equal(findDevToolsSocket(UNIX_WITHOUT, 4321), undefined);
  });

  test('pickAppTarget prefers the app page at localhost', () => {
    const targets = [
      { id: 'a', type: 'page', url: 'about:blank' },
      { id: 'b', type: 'service_worker', url: 'https://localhost/sw.js' },
      { id: 'c', type: 'page', url: 'https://example.com/' },
      { id: 'd', type: 'page', url: 'https://localhost/' },
    ];
    assert.equal(pickAppTarget(targets)?.id, 'd');
    assert.equal(pickAppTarget(targets.slice(0, 3))?.id, 'c');
    assert.equal(pickAppTarget(targets.slice(0, 2)), undefined);
  });
});

describe('WebViewDriver over a fake DevTools server', () => {
  test('connect retries until the socket exists, then evaluate / callPlugin / events / reload / close', async () => {
    const { fake, devtools, adb } = await setup({ socketLate: true, capacitorDelayMs: 300 });
    const driver = await WebViewDriver.connect(adb, APP, { timeoutMs: 15_000 });
    assert.equal(driver.pid, 4321);
    assert.equal(driver.pageUrl, 'https://localhost/');
    assert.equal(fake.commandLines().filter((l) => l.includes('/proc/net/unix')).length, 3);

    assert.equal(await driver.evaluate<number>('1 + 1'), 2);
    assert.deepEqual(await driver.evaluate('Promise.resolve({a: [1, 2]})'), { a: [1, 2] });
    assert.equal(await driver.evaluate('undefined'), undefined);
    await assert.rejects(driver.evaluate('(() => { throw new Error("page boom") })()'), /page evaluation failed: .*page boom/);

    assert.deepEqual(await driver.callPlugin('LocationTracking', 'getState'), { enabled: true, isMoving: false });
    assert.deepEqual(await driver.callPlugin('LocationTracking', 'echo', { x: 'a"b' }), { x: 'a"b' });
    assert.equal(await driver.callPlugin('LocationTracking', 'nothing'), undefined);
    await assert.rejects(driver.callPlugin('LocationTracking', 'start'), (error: unknown) => {
      assert.ok(error instanceof PluginCallError);
      assert.equal(error.code, 'NOT_READY');
      assert.match(error.message, /call ready\(\) first/);
      return true;
    });
    await assert.rejects(driver.callPlugin('Missing', 'x'), (error: unknown) => error instanceof PluginCallError && error.code === 'UNAVAILABLE');

    await driver.captureEvents('LocationTracking', ['heartbeat', 'location']);
    await driver.captureEvents('LocationTracking', ['heartbeat']); // idempotent
    devtools.emit('heartbeat', { n: 1 });
    devtools.emit('location', { n: 2 });
    const events = await driver.drainEvents();
    assert.deepEqual(
      events.map((e) => [e.plugin, e.name, e.payload]),
      [
        ['LocationTracking', 'heartbeat', { n: 1 }],
        ['LocationTracking', 'location', { n: 2 }],
      ],
    );
    assert.equal(typeof events[0]!.receivedAt, 'number');
    assert.deepEqual(await driver.drainEvents(), []);

    await driver.evaluate('window.kept = 1');
    await driver.reload();
    assert.equal(await driver.evaluate('typeof window.kept'), 'undefined');
    assert.ok(devtools.methods.includes('Page.reload'));
    assert.deepEqual(await driver.drainEvents(), []);

    await driver.close();
    await driver.close();
    assert.equal(driver.closed, true);
    const removes = fake.commandLines().filter((l) => l.startsWith('forward --remove'));
    assert.deepEqual(removes, [`forward --remove tcp:${devtools.port}`]);
    await assert.rejects(driver.evaluate('1'), /closed/);
  });

  test('the page id is used when webSocketDebuggerUrl is missing', async () => {
    const { adb } = await setup({ omitWsUrl: true });
    const driver = await WebViewDriver.connect(adb, APP, { timeoutMs: 10_000 });
    assert.equal(await driver.evaluate('2 * 3'), 6);
    await driver.close();
  });

  test('a dropped connection rejects pending calls and marks the driver closed', async () => {
    const { devtools, adb } = await setup();
    const driver = await WebViewDriver.connect(adb, APP, { timeoutMs: 10_000 });
    const pending = driver.evaluate('"__hang__"', { timeoutMs: 10_000 });
    await new Promise((resolve) => setTimeout(resolve, 100));
    devtools.dropConnections();
    await assert.rejects(pending, /connection closed/);
    assert.equal(driver.closed, true);
    await driver.close();
  });

  test('evaluate times out', async () => {
    const { adb } = await setup();
    const driver = await WebViewDriver.connect(adb, APP, { timeoutMs: 10_000 });
    await assert.rejects(driver.evaluate('"__hang__"', { timeoutMs: 200 }), /timed out after 200 ms/);
    await driver.close();
  });

  test('connect fails with a clear message when the app has no WebView socket', async () => {
    const fake = new FakeAdb([
      { match: 'pidof', stdout: '4321\n' },
      { match: 'cat /proc/net/unix', stdout: UNIX_WITHOUT },
    ]);
    cleanups.push(() => fake.cleanup());
    await assert.rejects(
      WebViewDriver.connect(new Adb({ adbPath: fake.path }), APP, { timeoutMs: 800 }),
      /timed out after 800 ms waiting for the WebView DevTools socket/,
    );
  });
});
