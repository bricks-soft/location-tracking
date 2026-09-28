import assert, { AssertionError } from 'node:assert/strict';
import { describe, test } from 'node:test';
import { PLACES, ROUTES, assertions, interpolateRoute, type LatLon, type WireRecord } from '../src/index.ts';

const T0 = Date.UTC(2026, 8, 27, 8, 0, 0);
let seq = 0;

function rec(event: WireRecord['event'], atS: number, extra: Partial<WireRecord> = {}): WireRecord {
  seq += 1;
  return {
    uuid: `00000000-0000-4000-8000-${String(seq).padStart(12, '0')}`,
    event,
    timestamp: new Date(T0 + atS * 1000).toISOString(),
    recorded_at: new Date(T0 + atS * 1000).toISOString(),
    elapsed_realtime_ms: 1_000_000 + atS * 1000,
    boot_count: 7,
    is_moving: false,
    odometer: 0,
    mock: false,
    coords: null,
    activity: { type: 'still', confidence: 100 },
    battery: { level: 0.8, is_charging: false },
    backend: 'gms',
    ...extra,
  };
}

function coords(point: LatLon): WireRecord['coords'] {
  return {
    latitude: point.lat,
    longitude: point.lon,
    accuracy: 5,
    altitude: null,
    altitude_accuracy: null,
    speed: null,
    speed_accuracy: null,
    heading: null,
    heading_accuracy: null,
  };
}

describe('geometry', () => {
  test('haversineMeters on the fixture places', () => {
    assertions.approximately(assertions.haversineMeters(PLACES.hq, PLACES.hqEast400m), 400, 1, 'hq -> east 400 m');
    assertions.approximately(assertions.haversineMeters(PLACES.hq, PLACES.hqSouth1500m), 1500, 1, 'hq -> south');
    assert.equal(assertions.haversineMeters(PLACES.hq, PLACES.hq), 0);
  });

  test('routeLengthMeters on the fixture routes', () => {
    assertions.approximately(assertions.routeLengthMeters(ROUTES.cityLoop3km), 3000, 1, 'cityLoop3km');
    assertions.approximately(assertions.routeLengthMeters(ROUTES.approachHq), 1500, 1, 'approachHq');
    assertions.approximately(assertions.routeLengthMeters(ROUTES.leaveHq), 1500, 1, 'leaveHq');
    assert.equal(assertions.routeLengthMeters([]), 0);
  });

  test('offsetMeters moves north and east by the given meters', () => {
    const p = assertions.offsetMeters(PLACES.hq, 300, -400);
    assertions.approximately(assertions.haversineMeters(PLACES.hq, p), 500, 0.5);
    assertions.approximately(assertions.haversineMeters(PLACES.hq, { lat: p.lat, lon: PLACES.hq.lon }), 300, 0.5);
    assert.ok(p.lon < PLACES.hq.lon);
  });
});

describe('record assertions', () => {
  test('inOrder matches a subsequence by recorded_at and returns the matches', () => {
    const records = [
      rec('location', 30),
      rec('tracking_start', 0, { reason: 'start' }),
      rec('motionchange', 10, { is_moving: true }),
      rec('geofence', 40, { geofence: { identifier: 'hq', action: 'ENTER' } }),
      rec('tracking_stop', 50, { reason: 'stop' }),
    ];
    const matched = assertions.inOrder(records, [
      { event: 'tracking_start', reason: 'start' },
      { event: 'motionchange', is_moving: true },
      (r) => r.event === 'location',
      { event: 'geofence', identifier: 'hq', action: 'ENTER' },
      'tracking_stop',
    ]);
    assert.deepEqual(matched.map((r) => r.event), ['tracking_start', 'motionchange', 'location', 'geofence', 'tracking_stop']);
    assert.throws(
      () => assertions.inOrder(records, ['tracking_stop', 'tracking_start']),
      (error: unknown) => error instanceof AssertionError && /no record matching tracking_start/.test(error.message) && /records by recorded_at/.test(error.message),
    );
    assert.throws(() => assertions.inOrder(records, [{ event: 'geofence', action: 'EXIT' }]), AssertionError);
  });

  test('noDuplicates', () => {
    const a = rec('location', 0);
    assertions.noDuplicates([a, rec('location', 1)]);
    assert.throws(() => assertions.noDuplicates([a, rec('heartbeat', 5), a]), /1 uuid\(s\) occur more than once/);
  });

  test('heartbeatCadence: stationary heartbeats within [min - tol, max + tol]', () => {
    const records = [rec('tracking_start', 0, { reason: 'start' }), rec('motionchange', 5), rec('heartbeat', 70), rec('heartbeat', 135), rec('heartbeat', 200)];
    const gaps = assertions.heartbeatCadence(records, { minIntervalS: 60, maxIntervalS: 120 });
    assert.deepEqual(gaps, [65, 65, 65]);
    // too early
    assert.throws(
      () => assertions.heartbeatCadence([rec('motionchange', 0), rec('heartbeat', 20)], { minIntervalS: 60, maxIntervalS: 120 }),
      /heartbeat 1 came 20.0 s after record 0/,
    );
    // a silent period longer than max while tracking is on
    assert.throws(
      () => assertions.heartbeatCadence([rec('motionchange', 0), rec('location', 400)], { minIntervalS: 60, maxIntervalS: 120 }),
      /gap of 400.0 s .* while tracking was on/,
    );
  });

  test('heartbeatCadence: stops, boots, since and idle pacing', () => {
    const records = [
      rec('heartbeat', 0),
      rec('tracking_stop', 30, { reason: 'stop' }),
      rec('tracking_start', 3000, { reason: 'start' }),
      rec('heartbeat', 3065),
      rec('heartbeat', 5000, { boot_count: 8 }),
      rec('tracking_start', 5001, { reason: 'boot', boot_count: 8 }),
    ];
    // the stop -> start gap is not a violation; the heartbeat after the reboot is not measured
    assert.deepEqual(assertions.heartbeatCadence(records.slice(0, 4), { minIntervalS: 60, maxIntervalS: 120 }), [65]);
    assert.deepEqual(assertions.heartbeatCadence(records.slice(3, 5), { minIntervalS: 60, maxIntervalS: 120 }), []);
    const idle = [rec('heartbeat', 0), rec('heartbeat', 545), rec('heartbeat', 1090)];
    assert.deepEqual(assertions.heartbeatCadence(idle, { minIntervalS: 60, maxIntervalS: 120, idlePaced: true }), [545, 545]);
    assert.throws(() => assertions.heartbeatCadence(idle, { minIntervalS: 60, maxIntervalS: 120 }), /allowed 30..150 s/);
    const late = [rec('location', 0), rec('heartbeat', 30), rec('heartbeat', 95)];
    assert.deepEqual(assertions.heartbeatCadence(late, { minIntervalS: 60, maxIntervalS: 120, since: T0 + 30_000 }), [65]);
  });

  test('gapsExplained: stop, restore, boot, late stop, providerchange, idle pacing; unexplained fails', () => {
    const idleMeta = { strategy: 'idle_paced' as const, min_interval: 60, max_interval: 120, next_at: null, battery_exempt: false, device_idle: true };
    const records = [
      rec('heartbeat', 0),
      rec('tracking_stop', 60, { reason: 'stop' }),
      rec('tracking_start', 4000, { reason: 'start' }),
      rec('heartbeat', 4065),
      rec('tracking_start', 6000, { reason: 'restore' }),
      rec('tracking_start', 9000, { reason: 'boot', boot_count: 8 }),
      rec('tracking_stop', 12000, { reason: 'reboot', boot_count: 9 }),
      rec('tracking_start', 12001, { reason: 'start', boot_count: 9 }),
      rec('providerchange', 13000, { boot_count: 9 }),
      rec('heartbeat', 13545, { boot_count: 9, heartbeat: idleMeta }),
    ];
    const gaps = assertions.gapsExplained(records, { maxGapS: 180 });
    assert.deepEqual(
      gaps.map((g) => g.explanation),
      ['tracking was off', 'tracking_start:restore', 'tracking_start:boot', 'tracking_stop:reboot', 'providerchange', 'idle pacing'],
    );
    assert.throws(
      () => assertions.gapsExplained([rec('heartbeat', 0), rec('heartbeat', 1000)], { maxGapS: 180 }),
      /unexplained gap of 1000.0 s between records 0 and 1/,
    );
    assert.throws(
      () => assertions.gapsExplained([rec('heartbeat', 0), rec('heartbeat', 3000, { heartbeat: idleMeta })], { maxGapS: 180 }),
      /unexplained gap/,
    );
  });

  test('withinWindow and approximately', () => {
    assertions.withinWindow('2026-09-27T08:00:30.000Z', { from: T0, to: '2026-09-27T08:01:00.000Z' });
    assert.throws(() => assertions.withinWindow(T0 - 1, { from: T0, to: T0 + 10 }, 'sent_at'), /sent_at .* is outside .* \(1 ms outside\)/);
    assert.throws(() => assertions.withinWindow('garbage', { from: T0, to: T0 }), /not a time/);
    assertions.approximately(95, 100, { pct: 10 });
    assertions.approximately(100.5, 100, 1);
    assert.throws(() => assertions.approximately(80, 100, { pct: 10 }, 'odometer'), /odometer is 80, expected 100 ±10%/);
  });
});

describe('travelSummary', () => {
  test('cityLoop3km replayed at 10 m/s: distance, travel time, moving time, odometer', () => {
    const points = interpolateRoute(ROUTES.cityLoop3km, 100); // 31 fixes, 100 m apart
    const records: WireRecord[] = [rec('tracking_start', 0, { reason: 'start', coords: coords(points[0]!) })];
    let odometer = 0;
    points.forEach((point, i) => {
      if (i > 0) odometer += assertions.haversineMeters(points[i - 1]!, point);
      records.push(rec(i === 0 ? 'motionchange' : 'location', 60 + i * 10, { is_moving: true, odometer, coords: coords(point) }));
    });
    const end = points[points.length - 1]!;
    // motionchange(false) after a 60 s stop timeout, then a stationary heartbeat
    records.push(rec('motionchange', 60 + 300 + 60, { is_moving: false, odometer, coords: coords(end) }));
    records.push(rec('heartbeat', 60 + 300 + 60 + 65, { odometer, coords: coords(end), timestamp: records[records.length - 1]!.timestamp }));
    const summary = assertions.travelSummary(records);
    assertions.approximately(summary.pathM, assertions.routeLengthMeters(ROUTES.cityLoop3km), { pct: 0.1 }, 'pathM');
    assertions.approximately(summary.odometerM, 3000, { pct: 0.1 }, 'odometerM');
    assert.equal(summary.travelS, 300);
    assert.equal(summary.movingS, 360);
    assert.equal(summary.spanS, 485);
    assert.equal(summary.fixes, 32);
    assert.equal(summary.segments, 30);
    assertions.approximately(summary.allFixesPathM, summary.pathM, 0.01, 'the stationary end fix is at the last moving fix');
  });

  test('chains break at tracking_stop/start and boot changes; odometer resets add nothing', () => {
    const a = { lat: 24.7, lon: 46.6 };
    const b = assertions.offsetMeters(a, 1000, 0);
    const c = assertions.offsetMeters(b, 1000, 0);
    const records = [
      rec('motionchange', 0, { is_moving: true, coords: coords(a), odometer: 0 }),
      rec('location', 100, { is_moving: true, coords: coords(b), odometer: 1000 }),
      rec('tracking_stop', 110, { reason: 'stop', odometer: 1000 }),
      rec('tracking_start', 200, { reason: 'start', odometer: 0 }),
      rec('location', 300, { is_moving: true, coords: coords(c), odometer: 500 }),
    ];
    const summary = assertions.travelSummary(records);
    assertions.approximately(summary.pathM, 1000, 0.5);
    assert.equal(summary.travelS, 100);
    assert.equal(summary.segments, 1);
    assert.equal(summary.odometerM, 1500);
    assert.equal(summary.movingS, 110);
    assertions.approximately(summary.allFixesPathM, 1000, 0.5);
    assert.deepEqual(assertions.travelSummary([]), {
      odometerM: 0,
      pathM: 0,
      allFixesPathM: 0,
      movingS: 0,
      travelS: 0,
      spanS: 0,
      fixes: 0,
      segments: 0,
    });
  });
});

describe('onlineStatus', () => {
  test('on with recent records, offline when silent, off after a stop, unknown before anything', () => {
    const records = [rec('tracking_start', 0, { reason: 'start' }), rec('heartbeat', 65), rec('tracking_stop', 600, { reason: 'stop' })];
    assert.deepEqual(
      (({ online, state, silenceS }) => ({ online, state, silenceS }))(assertions.onlineStatus(records, T0 + 100_000, 180)),
      { online: true, state: 'on', silenceS: 35 },
    );
    const silent = assertions.onlineStatus(records, T0 + 400_000, 180);
    assert.equal(silent.online, false);
    assert.equal(silent.state, 'on');
    assert.equal(silent.lastRecord?.event, 'heartbeat');
    assert.equal(assertions.onlineStatus(records, T0 + 601_000, 180).state, 'off');
    assert.equal(assertions.onlineStatus(records, T0 - 1, 180).state, 'unknown');
    assert.equal(assertions.onlineStatus([rec('current_position', 0)], T0 + 1000, 180).state, 'unknown');
    assert.equal(assertions.onlineStatus([rec('heartbeat', 0)], new Date(T0 + 1000).toISOString(), 180).online, true);
  });
});
