import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { Adb, E2eCommandError, E2eCommands, findResponse, newRequestId } from '../src/index.ts';
import { FakeAdb, type FakeRule } from './helpers/fake-adb.ts';

const APP = 'com.brickssoft.locationtracking.example';
const fakes: FakeAdb[] = [];
after(() => fakes.forEach((fake) => fake.cleanup()));

/** Captures the request id and cmd from the broadcast. */
const BROADCAST: FakeRule = {
  match: 'am broadcast .* --es id (?<id>[A-Za-z0-9._-]+) --es cmd (?<cmd>\\S+) --es json64 (?<json64>\\S+)',
  stdout: 'Broadcasting: Intent { act=x }\nBroadcast completed: result=0\n',
};

/** Old lines that must never be taken as the answer: another id with the same cmd, other tags, noise. */
const OLD_LINES =
  '--------- beginning of main\n' +
  '2026-09-27 10:00:00.000  1111  1111 I LT-E2E  : {"id":"e2e-old-1","cmd":"start","ok":false,"code":"OLD","message":"old"}\n' +
  '2026-09-27 10:00:01.000  1111  1111 I LT-E2E  : not json\n';

function setup(rules: FakeRule[]): { fake: FakeAdb; commands: E2eCommands } {
  const fake = new FakeAdb(rules);
  fakes.push(fake);
  const commands = new E2eCommands(new Adb({ serial: 'emulator-5554', adbPath: fake.path }), APP);
  commands.pollIntervalMs = 50;
  return { fake, commands };
}

function answerLine(json: string): string {
  return `2026-09-27 10:00:05.123  4321  4321 I LT-E2E  : ${json}\n`;
}

function logcatRules(answer: string): FakeRule[] {
  return [
    { match: '^logcat ', requires: ['id'], stdout: OLD_LINES + answerLine(answer) },
    { match: '^logcat ', stdout: OLD_LINES },
  ];
}

test('request ids match the receiver pattern and are unique', () => {
  const ids = new Set(Array.from({ length: 100 }, () => newRequestId()));
  assert.equal(ids.size, 100);
  for (const id of ids) assert.match(id, /^[A-Za-z0-9._-]{1,64}$/);
});

test('send: explicit background broadcast with base64 JSON args, resolves with result; old lines ignored', async () => {
  const { fake, commands } = setup([
    BROADCAST,
    ...logcatRules('{"id":"{{id}}","cmd":"{{cmd}}","ok":true,"result":{"enabled":true,"isMoving":false}}'),
  ]);
  const state = await commands.send<{ enabled: boolean }>('setConfig', { config: { heartbeat: { minInterval: 60 } } });
  assert.deepEqual(state, { enabled: true, isMoving: false });
  const broadcast = fake.calls().find((call) => call.args[1]?.startsWith('am broadcast'))!;
  const command = broadcast.args[1]!;
  assert.match(
    command,
    new RegExp(
      `^am broadcast -a ${APP.replace(/\./g, '\\.')}\\.E2E -n ${APP.replace(/\./g, '\\.')}/\\.e2e\\.E2eCommandReceiver ` +
        '--include-stopped-packages --es id \\S+ --es cmd setConfig --es json64 \\S+$',
    ),
  );
  assert.doesNotMatch(command, /--receiver-foreground/);
  const json64 = fake.vars()['json64']!;
  assert.deepEqual(JSON.parse(Buffer.from(json64, 'base64').toString('utf8')), { config: { heartbeat: { minInterval: 60 } } });
  const logcat = fake.calls().find((call) => call.args[0] === 'logcat')!;
  const id = fake.vars()['id']!;
  assert.deepEqual(logcat.args, ['logcat', '-d', '-v', 'threadtime', '-v', 'UTC', '-v', 'year', '-b', 'main', '-e', id, 'LT-E2E:I', '*:S']);
});

test('sendDetailed returns the device time of the response line', async () => {
  const { commands } = setup([BROADCAST, ...logcatRules('{"id":"{{id}}","cmd":"{{cmd}}","ok":true,"result":1}')]);
  const detailed = await commands.sendDetailed<number>('state');
  assert.equal(detailed.result, 1);
  assert.match(detailed.id, /^e2e-/);
  assert.equal(detailed.line?.epochMs, Date.UTC(2026, 8, 27, 10, 0, 5, 123));
});

test('broadcastLine + waitForResponse: one shell call can carry the command', async () => {
  const { fake, commands } = setup([BROADCAST, ...logcatRules('{"id":"{{id}}","cmd":"{{cmd}}","ok":true,"result":{"enabled":true}}')]);
  const prepared = commands.broadcastLine('start', {}, { foreground: true });
  assert.match(prepared.command, new RegExp(`--receiver-foreground --es id ${prepared.id} --es cmd start --es json64 e30=$`));
  await commands.adb.shell(`${prepared.command} & am force-stop ${APP}; wait`);
  const response = await commands.waitForResponse<{ enabled: boolean }>(prepared);
  assert.deepEqual(response.result, { enabled: true });
  assert.equal(fake.vars()['id'], prepared.id);
});

test('send: foreground option adds --receiver-foreground', async () => {
  const { fake, commands } = setup([BROADCAST, ...logcatRules('{"id":"{{id}}","cmd":"{{cmd}}","ok":true,"result":null}')]);
  await commands.start({ foreground: true });
  assert.match(fake.calls().find((c) => c.args[1]?.startsWith('am broadcast'))!.args[1]!, /--receiver-foreground/);
});

test('send: ok:false rejects with E2eCommandError carrying the code', async () => {
  const { commands } = setup([
    BROADCAST,
    ...logcatRules('{"id":"{{id}}","cmd":"{{cmd}}","ok":false,"code":"PERMISSION_DENIED","message":"no location permission"}'),
  ]);
  await assert.rejects(commands.start(), (error: unknown) => {
    assert.ok(error instanceof E2eCommandError);
    assert.equal(error.code, 'PERMISSION_DENIED');
    assert.equal(error.cmd, 'start');
    assert.match(error.message, /no location permission/);
    return true;
  });
});

test('send: resultFile holding the full response is read with run-as cat and removed', async () => {
  const records = Array.from({ length: 3 }, (_, i) => ({ uuid: `u${i}`, event: 'location' }));
  const { fake, commands } = setup([
    BROADCAST,
    ...logcatRules('{"id":"{{id}}","cmd":"{{cmd}}","ok":true,"resultFile":"files/e2e/{{id}}.json"}'),
    {
      match: `^shell run-as ${APP.replace(/\./g, '\\.')} cat files/e2e/{{id}}\\.json$`,
      stdout: `{"id":"{{id}}","cmd":"sync","ok":true,"result":${JSON.stringify(records)}}`,
    },
  ]);
  const uploaded = await commands.sync();
  assert.deepEqual(uploaded, records);
  const id = fake.vars()['id']!;
  assert.ok(fake.commandLines().includes(`shell run-as ${APP} rm -f files/e2e/${id}.json`));
});

test('send: resultFile holding only the result', async () => {
  const { commands } = setup([
    BROADCAST,
    ...logcatRules('{"id":"{{id}}","cmd":"{{cmd}}","ok":true,"resultFile":"files/e2e/{{id}}.json"}'),
    { match: 'run-as .* cat ', stdout: '[{"identifier":"hq","radius":150}]' },
  ]);
  assert.deepEqual(await commands.getGeofences(), [{ identifier: 'hq', radius: 150 }]);
});

test('send: an unreadable resultFile rejects with BAD_RESULT_FILE', async () => {
  const { commands } = setup([
    BROADCAST,
    ...logcatRules('{"id":"{{id}}","cmd":"{{cmd}}","ok":true,"resultFile":"files/e2e/{{id}}.json"}'),
    { match: 'run-as .* cat ', code: 1, stderr: 'No such file or directory' },
  ]);
  await assert.rejects(commands.state(), (error: unknown) => error instanceof E2eCommandError && error.code === 'BAD_RESULT_FILE');
});

test('send: no answer within the timeout rejects with NO_RESPONSE; old lines never match', async () => {
  const { commands } = setup([BROADCAST, { match: '^logcat ', stdout: OLD_LINES }]);
  const started = Date.now();
  await assert.rejects(commands.send('state', {}, { timeoutMs: 600 }), (error: unknown) => {
    assert.ok(error instanceof E2eCommandError);
    assert.equal(error.code, 'NO_RESPONSE');
    assert.match(error.message, /Broadcast completed/);
    return true;
  });
  assert.ok(Date.now() - started >= 600);
});

test('send: an adb failure of the broadcast rejects at once', async () => {
  const { commands } = setup([
    { match: 'am broadcast', code: 255, stderr: 'error: device offline' },
    { match: '^logcat ', stdout: '' },
  ]);
  await assert.rejects(commands.send('state', {}, { timeoutMs: 5000 }), /device offline/);
});

test('typed helpers send the documented args', async () => {
  const { fake, commands } = setup([
    BROADCAST,
    ...logcatRules('{"id":"{{id}}","cmd":"{{cmd}}","ok":true,"result":{"uuid":"abc"}}'),
  ]);
  const cases: Array<[() => Promise<unknown>, string, unknown]> = [
    [() => commands.ready({ a: 1 }), 'ready', { config: { a: 1 }, reset: true }],
    [() => commands.ready(undefined, false), 'ready', { reset: false }],
    [() => commands.changePace(true), 'changePace', { isMoving: true }],
    [() => commands.insertLocation({ latitude: 1 }), 'insertLocation', { location: { latitude: 1 } }],
    [() => commands.addGeofence({ identifier: 'g', latitude: 1, longitude: 2, radius: 100 }), 'addGeofence', { geofence: { identifier: 'g', latitude: 1, longitude: 2, radius: 100 } }],
    [() => commands.removeGeofence('g'), 'removeGeofence', { identifier: 'g' }],
    [() => commands.blockMainThread(4000), 'blockMainThread', { ms: 4000, delayMs: 0 }],
    [() => commands.startDuringMainThreadBlock(4000), 'startDuringMainThreadBlock', { ms: 4000, startAfterMs: 100 }],
    [() => commands.premiseStart({ id: 'hq', latitude: 1, longitude: 2, radius: 150 }, 'http://10.0.2.2:8787/premise-audit'), 'premise.start', { premise: { id: 'hq', latitude: 1, longitude: 2, radius: 150 }, auditUrl: 'http://10.0.2.2:8787/premise-audit' }],
    [() => commands.premiseAuditLog(10), 'premise.auditLog', { limit: 10 }],
    [() => commands.premiseStatus(), 'premise.status', {}],
  ];
  for (const [call, cmd, args] of cases) {
    fake.setRules([BROADCAST, ...logcatRules('{"id":"{{id}}","cmd":"{{cmd}}","ok":true,"result":{"uuid":"abc"}}')]);
    await call();
    const vars = fake.vars();
    assert.equal(vars['cmd'], cmd);
    assert.deepEqual(JSON.parse(Buffer.from(vars['json64']!, 'base64').toString('utf8')), args, cmd);
  }
  fake.setRules([BROADCAST, ...logcatRules('{"id":"{{id}}","cmd":"{{cmd}}","ok":true,"result":{"uuid":"abc"}}')]);
  assert.equal(await commands.insertLocation({}), 'abc');
});

test('findResponse accepts raw message lines and ignores other tags', () => {
  const id = 'e2e-1';
  assert.equal(findResponse('2026-09-27 10:00:00.000  1  1 I OTHER: {"id":"e2e-1","ok":true}', id), undefined);
  assert.deepEqual(findResponse('{"id":"e2e-1","cmd":"state","ok":true,"result":1}', id), { id, cmd: 'state', ok: true, result: 1 });
  assert.equal(findResponse('{"id":"e2e-10","cmd":"state","ok":true,"result":1}', id), undefined);
});
