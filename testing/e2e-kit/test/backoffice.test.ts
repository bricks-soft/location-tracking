import assert from 'node:assert/strict';
import { Agent, request } from 'node:http';
import { after, before, beforeEach, describe, test } from 'node:test';
import { MockBackOffice, requestRecords, splitLocationsBody, type WireRecord } from '../src/index.ts';

function record(uuid: string, event: WireRecord['event'] = 'location', extra: Partial<WireRecord> = {}): WireRecord {
  return {
    uuid,
    event,
    timestamp: '2026-09-27T10:00:00.000Z',
    recorded_at: '2026-09-27T10:00:00.100Z',
    elapsed_realtime_ms: 1000,
    boot_count: 3,
    is_moving: false,
    odometer: 0,
    mock: false,
    coords: null,
    activity: { type: 'still', confidence: 100 },
    battery: { level: 0.5, is_charging: false },
    backend: 'gms',
    ...extra,
  };
}

const lines: string[] = [];
const office = new MockBackOffice({ port: 0, host: '127.0.0.1', log: (line) => lines.push(line) });

async function post(path: string, body: unknown, headers: Record<string, string> = {}): Promise<Response> {
  return fetch(office.hostUrl(path), {
    method: 'POST',
    headers: { 'content-type': 'application/json; charset=utf-8', ...headers },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  });
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(office.hostUrl(path));
  assert.equal(response.status, 200);
  return (await response.json()) as T;
}

before(() => office.start());
after(() => office.stop());
beforeEach(() => office.reset());

describe('splitLocationsBody', () => {
  test('every body shape', () => {
    const a = record('a');
    const b = record('b', 'heartbeat');
    assert.deepEqual(splitLocationsBody({ location: a, device_id: 'x' }), { records: [a], params: { device_id: 'x' } });
    assert.deepEqual(splitLocationsBody({ location: [a, b], e2e: true, tags: [] }), { records: [a, b], params: { e2e: true, tags: [] } });
    assert.deepEqual(splitLocationsBody({ data: a }), { records: [a], params: {} });
    assert.deepEqual(splitLocationsBody({ ...a, device_id: 'x' }), { records: [a], params: { device_id: 'x' } });
    assert.deepEqual(splitLocationsBody([a, b]), { records: [a, b], params: {} });
    assert.deepEqual(splitLocationsBody({ id: 'template-without-uuid' }), { records: [], params: { id: 'template-without-uuid' } });
    assert.deepEqual(splitLocationsBody('text'), { records: [], params: {} });
  });
});

describe('MockBackOffice over HTTP', () => {
  test('start binds a free port; url() is the emulator view', () => {
    assert.equal(office.running, true);
    assert.ok(office.port > 0);
    assert.equal(office.url(), `http://10.0.2.2:${office.port}/locations`);
    assert.equal(office.hostUrl('/__health'), `http://127.0.0.1:${office.port}/__health`);
  });

  test('POST /locations stores single, batch, rootProperty and "." bodies with metadata', async () => {
    const response = await post('/locations', { location: record('s1'), e2e: true }, { authorization: 'Bearer e2e-initial' });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { ok: true });
    await post('/locations', { location: [record('b1'), record('b2', 'heartbeat')], device: { model: 'sdk' } });
    await post('/locations', { data: record('c1') });
    await post('/locations', { ...record('d1'), device_id: 'abc' });
    await post('/locations', [record('e1'), record('e2')]);
    const stored = office.records();
    assert.deepEqual(
      stored.map((s) => [s.record.uuid, s.index, s.batchSize]),
      [
        ['s1', 0, 1],
        ['b1', 0, 2],
        ['b2', 1, 2],
        ['c1', 0, 1],
        ['d1', 0, 1],
        ['e1', 0, 2],
        ['e2', 1, 2],
      ],
    );
    assert.deepEqual(stored[0]!.params, { e2e: true });
    assert.equal(stored[0]!.authorization, 'Bearer e2e-initial');
    assert.equal(stored[1]!.authorization, undefined);
    assert.deepEqual(stored[2]!.params, { device: { model: 'sdk' } });
    assert.deepEqual(stored[4]!.params, { device_id: 'abc' });
    assert.equal('device_id' in stored[4]!.record, false);
    assert.equal(stored[1]!.requestId, stored[2]!.requestId);
    assert.equal(stored[0]!.path, '/locations');
  });

  test('invalid JSON answers 400 and stores nothing', async () => {
    const response = await post('/locations', '{not json');
    assert.equal(response.status, 400);
    assert.equal(office.records().length, 0);
    assert.equal(office.requests({ path: '/locations' })[0]!.status, 400);
  });

  test('record filters: event list, since, uuid, unique', async () => {
    await post('/locations', { location: [record('a'), record('b', 'heartbeat'), record('a')] });
    const midpoint = Date.now() + 1;
    await new Promise((resolve) => setTimeout(resolve, 5));
    await post('/locations', { location: record('c', 'tracking_start', { reason: 'start' }) });
    assert.equal(office.records({ event: 'heartbeat' }).length, 1);
    assert.equal(office.records({ event: ['heartbeat', 'tracking_start'] }).length, 2);
    assert.equal(office.records({ unique: true }).length, 3);
    assert.equal(office.records({ uuid: 'a' }).length, 2);
    assert.deepEqual(office.records({ since: midpoint }).map((s) => s.record.uuid), ['c']);
    const viaHttp = await getJson<Array<{ record: WireRecord }>>(`/__records?event=heartbeat,tracking_start&unique=1`);
    assert.deepEqual(viaHttp.map((s) => s.record.uuid), ['b', 'c']);
    assert.deepEqual((await getJson<unknown[]>(`/__records?since=${midpoint}`)).length, 1);
    assert.deepEqual((await getJson<unknown[]>(`/__records?uuid=a`)).length, 2);
  });

  test('faults: status for count requests (FIFO), nothing stored, then normal', async () => {
    office.setFault({ path: '/locations', status: 500, count: 1 });
    const viaHttp = await post('/__faults', { path: '/locations', status: 401 });
    assert.deepEqual(await viaHttp.json(), { ok: true, faults: 2 });
    assert.equal((await post('/locations', { location: record('x') })).status, 500);
    assert.equal((await post('/locations', { location: record('x') })).status, 401);
    assert.equal((await post('/locations', { location: record('x') })).status, 200);
    assert.equal(office.records().length, 1);
    assert.deepEqual(
      office.requests({ path: '/locations' }).map((r) => [r.status, r.fault]),
      [
        [500, 'status'],
        [401, 'status'],
        [200, undefined],
      ],
    );
  });

  test('faults: count -1 lasts until cleared; DELETE /__faults clears', async () => {
    office.setFault({ path: '/locations', status: 503, count: -1 });
    for (let i = 0; i < 3; i++) assert.equal((await post('/locations', { location: record(`x${i}`) })).status, 503);
    const cleared = await fetch(office.hostUrl('/__faults'), { method: 'DELETE' });
    assert.equal(cleared.status, 200);
    assert.equal((await post('/locations', { location: record('y') })).status, 200);
  });

  test('faults: drop closes the connection without an answer', async () => {
    office.setFault({ path: '/locations', drop: true });
    await assert.rejects(post('/locations', { location: record('x') }));
    assert.equal(office.records().length, 0);
    const [entry] = office.requests({ path: '/locations' });
    assert.equal(entry!.status, 0);
    assert.equal(entry!.fault, 'drop');
    assert.equal((await post('/locations', { location: record('x') })).status, 200);
  });

  test('faults: delay only answers normally after the delay; delay + status', async () => {
    office.setFault({ path: '/locations', delayMs: 300 });
    let started = Date.now();
    assert.equal((await post('/locations', { location: record('slow') })).status, 200);
    assert.ok(Date.now() - started >= 290);
    assert.equal(office.records().length, 1);
    assert.equal(office.requests({ path: '/locations' })[0]!.fault, 'delay');
    office.setFault({ path: '/locations', delayMs: 200, status: 502 });
    started = Date.now();
    assert.equal((await post('/locations', { location: record('slow2') })).status, 502);
    assert.ok(Date.now() - started >= 190);
  });

  test('faults are validated and never apply to control endpoints', async () => {
    assert.throws(() => office.setFault({ path: '/__records' }), /control endpoints/);
    assert.throws(() => office.setFault({ path: 'locations' }), /start with/);
    assert.throws(() => office.setFault({ path: '/locations', count: 0 }), /count/);
    assert.equal((await post('/__faults', { path: '/__reset' })).status, 400);
    assert.equal((await post('/__faults', 'nope')).status, 400);
  });

  test('POST /auth/refresh counts refreshes; reset restarts the counter', async () => {
    assert.deepEqual(await (await post('/auth/refresh', { refresh_token: 'e2e-refresh' })).json(), {
      accessToken: 'e2e-access-1',
      refreshToken: 'e2e-refresh-1',
      expires_in: 3600,
    });
    assert.equal(((await (await post('/auth/refresh', '')).json()) as { accessToken: string }).accessToken, 'e2e-access-2');
    assert.equal(office.refreshes, 2);
    assert.equal((await post('/__reset', {})).status, 200);
    assert.equal(((await (await post('/auth/refresh', {})).json()) as { accessToken: string }).accessToken, 'e2e-access-1');
  });

  test('POST /premise-audit stores entries; /__premise filters by kind, type and event', async () => {
    const entries = [
      { id: '1', kind: 'premise', at: 'a', pid: 1, js: false, source: 'manifest', type: 'enter', premise_id: 'hq' },
      { id: '2', kind: 'record', at: 'b', pid: 1, js: false, source: 'manifest', record: record('r', 'heartbeat') },
      { id: '3', kind: 'event', at: 'c', pid: 1, js: true, source: 'manifest', name: 'heartbeat', payload: {} },
    ];
    const response = await post('/premise-audit', { device_id: 'dev-1', entries });
    assert.deepEqual(await response.json(), { ok: true, accepted: 3 });
    assert.equal(office.premiseRecords().length, 3);
    assert.equal(office.premiseRecords()[0]!.deviceId, 'dev-1');
    assert.equal(office.premiseRecords({ kind: 'premise', type: 'enter' }).length, 1);
    assert.deepEqual(office.premiseRecords({ event: 'heartbeat' }).map((s) => s.entry.id), ['2', '3']);
    assert.deepEqual((await getJson<Array<{ entry: { id: string } }>>('/__premise?kind=event')).map((s) => s.entry.id), ['3']);
    assert.deepEqual((await getJson<unknown[]>('/__premise?type=enter')).length, 1);
    assert.equal((await post('/premise-audit', { entries: 'x' })).status, 400);
  });

  test('POST /logs stores a size summary of multipart uploads', async () => {
    const form = new FormData();
    form.append('file', new Blob(['x'.repeat(100)]), 'log.gz');
    const response = await fetch(office.hostUrl('/logs'), { method: 'POST', body: form });
    assert.equal(response.status, 200);
    assert.equal(office.logUploads().length, 1);
    assert.ok(office.logUploads()[0]!.bytes > 100);
    assert.match(office.requests({ path: '/logs' })[0]!.body, /^<multipart \d+ bytes>$/);
  });

  test('/__requests, /__health, /__reset, 404 and the log', async () => {
    await post('/locations', { location: record('h1') });
    const health = await getJson<{ ok: boolean; records: number; premise: number; uptimeMs: number }>('/__health');
    assert.equal(health.ok, true);
    assert.equal(health.records, 1);
    assert.equal(health.premise, 0);
    assert.ok(health.uptimeMs >= 0);
    const requests = await getJson<Array<{ path: string; method: string; headers: Record<string, string> }>>('/__requests?path=/locations');
    assert.equal(requests.length, 1);
    assert.equal(requests[0]!.method, 'POST');
    assert.match(requests[0]!.headers['content-type']!, /application\/json/);
    assert.equal((await fetch(office.hostUrl('/nope'))).status, 404);
    assert.equal((await fetch(office.hostUrl('/locations'))).status, 404); // GET is not an upload
    assert.match(office.logText(), /POST \/locations -> 200 \(1 record\(s\) \[location\]\)/);
    assert.ok(lines.some((line) => /GET \/nope -> 404/.test(line)));
    await post('/__reset', {});
    assert.equal(office.records().length, 0);
    assert.deepEqual(office.requests().map((r) => r.path), ['/__reset']);
  });

  test('waitFor treats 0 as not yet; waitForRecords lists received records on timeout; requestRecords', async () => {
    const counting = office.waitFor((o) => o.records({ event: 'heartbeat' }).length, { timeoutMs: 5000, intervalMs: 50 });
    setTimeout(() => void post('/locations', { location: [record('hb1', 'heartbeat'), record('hb2', 'heartbeat')] }), 150);
    assert.equal(await counting, 2);
    const two = await office.waitForRecords({ event: 'heartbeat' }, { count: 2, timeoutMs: 1000, intervalMs: 50 });
    assert.deepEqual(two.map((s) => s.record.uuid), ['hb1', 'hb2']);
    await assert.rejects(
      office.waitForRecords({ event: 'geofence' }, { timeoutMs: 200, intervalMs: 50 }),
      /1 record\(s\) matching \{"event":"geofence"\}[\s\S]*records received \(by recorded_at\):[\s\S]*heartbeat/,
    );
    const [entry] = office.requests({ path: '/locations' });
    assert.deepEqual(requestRecords(entry!).map((r) => r.uuid), ['hb1', 'hb2']);
    assert.deepEqual(requestRecords({ ...entry!, body: 'not json' }), []);
  });

  test('waitFor resolves when a record arrives and times out otherwise', async () => {
    const waiting = office.waitFor((o) => o.records({ event: 'heartbeat' })[0], { timeoutMs: 5000, intervalMs: 50 });
    setTimeout(() => void post('/locations', { location: record('hb', 'heartbeat') }), 100);
    assert.equal((await waiting).record.uuid, 'hb');
    await assert.rejects(office.waitFor((o) => o.records({ event: 'geofence' }).length > 0, { timeoutMs: 200, intervalMs: 50 }), /timed out after 200 ms/);
  });
});

describe('MockBackOffice lifecycle', () => {
  test('stop closes keep-alive connections; a taken port gives a clear error', async () => {
    const first = new MockBackOffice({ port: 0, host: '127.0.0.1' });
    await first.start();
    await first.start(); // idempotent
    await new Promise<void>((resolve, reject) => {
      const req = request(first.hostUrl('/__health'), { agent: new Agent({ keepAlive: true }) }, (res) => {
        res.resume();
        res.on('end', resolve);
      });
      req.on('error', reject);
      req.end();
    });
    const second = new MockBackOffice({ port: first.port, host: '127.0.0.1' });
    await assert.rejects(second.start(), /port \d+ on 127\.0\.0\.1 is in use/);
    const started = Date.now();
    await first.stop();
    assert.ok(Date.now() - started < 2000);
    assert.equal(first.running, false);
    await first.stop();
  });
});
