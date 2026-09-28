// Plugin suite C: permissions, providers and geofences (docs/e2e/architecture.md §9 C, unit 11).
//
// Every scenario drives the plugin's example app through the debug command receiver (§6) and reads the audit trail
// that reached the mock back office (§7). The page runs in e2e mode and never calls a state-changing plugin method on
// its own, so the `ready` command that the scenarios send after a relaunch stands in for the app's startup code.
//
// Android and plugin facts the scenarios rely on (from AOSP and from the plugin sources):
// - `pm revoke` of a granted runtime permission kills every process of the app ("permissions revoked"). The system
//   restarts the sticky tracking service about a second later; that restart, a heartbeat alarm or the next `ready()`
//   then restores tracking (`tracking_start: restore`) or ends it (`tracking_stop` with a reason).
// - Android 12+ lets an app start a foreground service from the background only with an exemption. The revoke
//   scenarios make the app battery-optimization exempt (one of those exemptions), so an automatic restore can succeed.
//   Android 14+ also requires, at `startForeground`, a location permission that is usable in that moment for a
//   service of type location; "Allow only while using the app" is not usable from the background.
// - The emulator reports `adb emu geo fix` positions only while a client requests GPS. Scenarios that need a steady
//   stream of fixes (geofences, backends, mock locations) turn stop detection off and call `changePace(true)`, so the
//   plugin keeps its high-accuracy request for the whole scenario.
// - The plugin's location processor rejects a fix whose implied speed from the previous accepted fix is above
//   80 m/s (`filter.maxImpliedSpeed`). Positions therefore change only along continuous routes (EmulatedPosition),
//   never by a jump.
// - Geofences are registered with the OS only while tracking is on; circle transitions come from the OS (GMS or
//   LocationManager proximity alerts), never from the plugin's own fixes.
import assert from 'node:assert/strict';
import {
  E2eCommandError,
  PERMISSIONS,
  PLACES,
  PREMISES,
  ROUTES,
  TEST_HEARTBEAT,
  assertions,
  scenario,
  sleep,
  waitUntil,
  type GeofenceJson,
  type LatLon,
  type MockBackOffice,
  type ScenarioContext,
  type StateJson,
  type WireRecord,
} from '@bricks-soft/e2e-kit';

// ---------------------------------------------------------------------------------------------------------------------
// Timing and geometry constants

/** Upload of a record that the plugin creates right away (priority records go out at once; syncInterval is 0). */
const RECORD_TIMEOUT_MS = 60_000;
/** The first motionchange after start/restore: `locationTimeout` 30 s + 5 s grace + upload. */
const INITIAL_FIX_TIMEOUT_MS = 90_000;
/** How long Android may take to kill the process after `pm revoke`. */
const KILL_TIMEOUT_MS = 20_000;
/** How long a scenario lets Android and the plugin restore (or end) tracking on their own after the kill. */
const AUTO_RESTORE_WINDOW_MS = 30_000;
/** Pause before counting records, so a duplicate that is created or uploaded late is still counted. */
const SETTLE_MS = 10_000;
/** A geofence transition after the device crossed the boundary (OS detection + broadcast + upload). */
const GEOFENCE_TIMEOUT_MS = 180_000;
/** `tracking_start: boot` after `adb reboot` returned (BOOT_COMPLETED delivery + service start + upload). */
const BOOT_RECORD_TIMEOUT_MS = 240_000;
/** Time location services stay off in P-P06. */
const LOCATION_OFF_MS = 15_000;
/** Loitering delay of the DWELL geofence in P-P10. */
const LOITERING_DELAY_MS = 30_000;
/** How often a held position is sent again as a geo fix. */
const HOLD_INTERVAL_MS = 2_000;
/** Ground speed of replayed routes: 10 m/s (36 km/h, a car in town). */
const ROUTE_SPEED_MPS = 10;
/** Ground speed of the long route in P-P04. */
const DRIVE_SPEED_MPS = 12;
/** Accuracy of test-provider fixes (below `filter.odometerAccuracyThreshold` 20 m). */
const TEST_PROVIDER_ACCURACY_M = 5;
/** Platform provider the mock-location scenario overrides. */
const GPS = 'gps';
/** A recorded fix counts as "at" an emulated position within this distance. */
const PLACEMENT_TOLERANCE_M = 100;
/** A recorded fix counts as "on" a replayed route within this distance. */
const ROUTE_TOLERANCE_M = 50;
/** Slack for the position of a geofence transition's triggering fix relative to the boundary. */
const GEOFENCE_POSITION_TOLERANCE_M = 50;
/** Slack for comparing record times with the computed moment the route crossed a boundary. */
const TIMING_TOLERANCE_MS = 5_000;

// ---------------------------------------------------------------------------------------------------------------------
// Scenarios

scenario(
  'P-P01',
  'revoke fine location (keep coarse): process killed, relaunch, providerchange accuracy approximate',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ permissions: 'all', batteryExempt: true, launch: true });
    const position = new EmulatedPosition(ctx, PLACES.hq);
    try {
      await position.place();
      const config = await ctx.testConfig();
      const session = await startTracking(ctx, office, config, PLACES.hq);
      const backend = session.state.backend;
      await ctx.adb.keyHome(); // the user leaves the app to change the permission in Settings
      const pid = await runningPid(ctx);
      const revoked = await mark(ctx);
      try {
        await ctx.adb.revoke(ctx.appId, PERMISSIONS.fine);
        await expectKill(ctx, pid, 'revoking ACCESS_FINE_LOCATION');
        await ctx.app.launch();
        const state = await ctx.commands.ready(config);
        expectTrackingOn(office, revoked, state, 'ready() after the relaunch (coarse location is still granted)');

        const change = await awaitRecord(
          ctx,
          office,
          revoked,
          'a providerchange with provider.accuracy "approximate"',
          (r) => r.event === 'providerchange' && r.provider?.accuracy === 'approximate',
          RECORD_TIMEOUT_MS,
        );
        await awaitRecord(ctx, office, revoked, 'tracking_start with reason "restore"', isStart('restore'), RECORD_TIMEOUT_MS);
        await sleep(SETTLE_MS, ctx.signal);

        const after = receivedSince(office, revoked);
        const where = `\nrecords created since the revoke:\n${timeline(after)}`;
        assert.equal(change.provider?.enabled, true, `providerchange.provider.enabled${where}`);
        // Background location is still granted, and it counts on top of coarse location.
        assert.equal(change.provider?.permission, 'always', `providerchange.provider.permission${where}`);
        assert.equal(change.provider?.backend, backend, `providerchange.provider.backend${where}`);
        assert.equal(change.backend, backend, `providerchange.backend${where}`);
        expectCount(after, isEvent('providerchange'), 1, 'providerchange record (one change: precise -> approximate)');
        expectCount(after, isStart('restore'), 1, 'tracking_start (restore) record');
        expectCount(after, isEvent('tracking_stop'), 0, 'tracking_stop records (approximate location is a location permission)');
      } finally {
        await bestEffort(ctx, 'grant ACCESS_FINE_LOCATION again', () => ctx.adb.grant(ctx.appId, PERMISSIONS.fine));
      }
    } finally {
      await position.stop();
    }
  },
  { requires: { api: 31 } },
);

scenario('P-P02', 'revoke all location: restore records tracking_stop reason permission_denied', async (ctx) => {
  const office = await ctx.backOffice();
  // Battery-exempt: on Android 12-13 the restarted service may then enter the foreground from the background and reach
  // the plugin's permission check instead of failing the background-start rule first.
  await ctx.app.prepare({ permissions: 'all', batteryExempt: true, launch: true });
  const position = new EmulatedPosition(ctx, PLACES.hq);
  const revokedPermissions = locationPermissions(ctx.device.api);
  try {
    await position.place();
    const config = await ctx.testConfig();
    await startTracking(ctx, office, config, PLACES.hq);
    await ctx.adb.keyHome();
    const pid = await runningPid(ctx);
    const revoked = await mark(ctx);
    try {
      // One shell call with fine and coarse back to back, so no foreground location permission is left when the system
      // restarts the sticky service (about 1 s after the first revoke kills the process); a restore that still sees
      // one of them would resume tracking. Background location alone never counts (it needs a foreground grant).
      // `&&` makes a failed revoke fail the call instead of continuing with a partial revoke.
      await ctx.adb.shell(revokedPermissions.map((permission) => `pm revoke ${ctx.appId} ${permission}`).join(' && '));
      await expectKill(ctx, pid, `revoking ${revokedPermissions.join(', ')}`);

      let restoredBy = 'the automatic restore (sticky service restart or heartbeat alarm)';
      let stop = await recordWithin(ctx, office, revoked, isEvent('tracking_stop'), AUTO_RESTORE_WINDOW_MS);
      if (!stop) {
        restoredBy = `ready() sent after ${AUTO_RESTORE_WINDOW_MS / 1000} s without an automatic restore`;
        const state = await ctx.commands.ready(config);
        assert.equal(state.enabled, false, 'ready() without any location permission must end tracking (state.enabled)');
        stop = await awaitRecord(ctx, office, revoked, 'tracking_stop', isEvent('tracking_stop'), RECORD_TIMEOUT_MS);
      }
      ctx.log(`tracking_stop reason "${stop.reason}" was recorded by ${restoredBy}`);
      await sleep(SETTLE_MS, ctx.signal);

      const after = receivedSince(office, revoked);
      const [only] = expectCount(after, isEvent('tracking_stop'), 1, 'tracking_stop record after the revoke');
      assert.equal(
        only.reason,
        'permission_denied',
        `tracking_stop after the user revoked every location permission must have reason "permission_denied" ` +
          `(docs/wire-format.md, tracking_stop reasons); it has "${only.reason}". "service_start_failed" here means ` +
          `the system restarted the service and startForeground() failed (Android 14+ refuses a location-type ` +
          `foreground service without location permission) and the plugin reported that failure instead of the ` +
          `missing permission.\nrecords created since the revoke:\n${timeline(after)}`,
      );
      expectCount(
        after,
        isEvent('tracking_start'),
        0,
        'tracking_start records (tracking must not resume without location permission; a "restore" here before the ' +
          'tracking_stop means the service restarted between the fine and coarse revokes, while one was still granted)',
      );
      const state = await ctx.commands.state();
      assert.equal(state.enabled, false, 'state.enabled after tracking_stop');
      await waitUntil(async () => ((await ctx.app.isForegroundServiceRunning()) ? undefined : true), {
        timeoutMs: 15_000,
        message: 'the tracking foreground service must stop after tracking_stop',
        signal: ctx.signal,
      });
    } finally {
      // Same order as revoked: foreground permissions first, because a background grant depends on them.
      for (const permission of revokedPermissions) {
        await bestEffort(ctx, `grant ${permission} again`, () => ctx.adb.grant(ctx.appId, permission));
      }
    }
  } finally {
    await position.stop();
  }
});

scenario(
  'P-P03',
  'revoke background location: providerchange permission when_in_use, background behavior',
  async (ctx) => {
    // Observed and asserted background behavior with "Allow only while using the app" (when_in_use):
    // 1. After the revoke kills the process in the background, the automatic restore either resumes tracking
    //    (`tracking_start: restore`) or, on Android 14+, is refused (`tracking_stop: service_start_failed`), because a
    //    location-type foreground service cannot start from the background with a while-in-use grant. Android 10-13
    //    restores it (the app is battery-optimization exempt). `permission_denied` would be wrong: while-in-use is a
    //    location permission.
    // 2. When tracking resumed, the permission level reaches the back office as exactly one providerchange with
    //    permission "when_in_use". When the restart was refused, the plugin notices the new level only while tracking
    //    is off, and docs/wire-format.md says such a change is saved without a record: the audit then shows
    //    `tracking_stop: service_start_failed` and the next `tracking_start: start`, but no providerchange.
    // 3. GMS geofencing needs background location on Android 10+: addGeofence is rejected with PERMISSION_DENIED and
    //    nothing is stored.
    // 4. The foreground service keeps running with the app in the background. It receives fixes there only if it was
    //    started while the app was visible (Android 11+ gives a service started from the background no while-in-use
    //    location access); Android 10 gives fixes either way.
    const office = await ctx.backOffice();
    await ctx.app.prepare({ permissions: 'all', batteryExempt: true, launch: true });
    const api = ctx.device.api;
    const position = new EmulatedPosition(ctx, PLACES.hq);
    try {
      await position.place();
      const config = await ctx.testConfig({ patch: keepMoving() });
      const session = await startTracking(ctx, office, config, PLACES.hq);
      const backend = session.state.backend;
      await ctx.adb.keyHome();
      const pid = await runningPid(ctx);
      const revoked = await mark(ctx);
      try {
        await ctx.adb.revoke(ctx.appId, PERMISSIONS.background);
        await expectKill(ctx, pid, 'revoking ACCESS_BACKGROUND_LOCATION');

        // 1. Android and the plugin on their own, with the app in the background.
        const automatic = await recordWithin(
          ctx,
          office,
          revoked,
          (r) => r.event === 'tracking_start' || r.event === 'tracking_stop',
          AUTO_RESTORE_WINDOW_MS,
        );
        ctx.log(
          `API ${api}, app in the background after the revoke: ${
            automatic ? `${automatic.event} (${automatic.reason})` : `no automatic restore within ${AUTO_RESTORE_WINDOW_MS / 1000} s`
          }`,
        );

        // 2. The user opens the app; its startup code calls ready(), and start() if tracking had ended.
        await ctx.app.launch();
        const visible = await mark(ctx);
        let state = await ctx.commands.ready(config);
        let refused = false;
        if (!state.enabled) {
          const stop = await awaitRecord(
            ctx,
            office,
            revoked,
            'the tracking_stop that ended tracking after the revoke',
            isEvent('tracking_stop'),
            RECORD_TIMEOUT_MS,
          );
          const where = `\nrecords created since the revoke:\n${timeline(receivedSince(office, revoked))}`;
          assert.equal(
            stop.reason,
            'service_start_failed',
            `a refused restart after revoking only background location must be recorded as "service_start_failed" ` +
              `(the while-in-use grant is still a location permission)${where}`,
          );
          assert.ok(
            api >= 34,
            `Android API ${api} lets a battery-exempt app restart its location service from the background, but the ` +
              `restart was refused${where}`,
          );
          refused = true;
          state = await ctx.commands.start();
          assert.equal(state.enabled, true, 'start() from the visible app after the refused restart (state.enabled)');
        }
        const resumed = await awaitRecord(
          ctx,
          office,
          revoked,
          refused ? 'tracking_start with reason "start"' : 'tracking_start with reason "restore"',
          isStart(refused ? 'start' : 'restore'),
          RECORD_TIMEOUT_MS,
        );

        // 3. The permission level in the audit trail.
        const isWhenInUse = (r: WireRecord) => r.event === 'providerchange' && r.provider?.permission === 'when_in_use';
        if (!refused) {
          const change = await awaitRecord(
            ctx,
            office,
            revoked,
            'a providerchange with provider.permission "when_in_use"',
            isWhenInUse,
            RECORD_TIMEOUT_MS,
          );
          assert.equal(change.provider?.enabled, true, 'providerchange.provider.enabled');
          assert.equal(change.provider?.accuracy, 'precise', 'providerchange.provider.accuracy');
          assert.equal(change.provider?.backend, backend, 'providerchange.provider.backend');
        }
        await sleep(SETTLE_MS, ctx.signal);
        const after = receivedSince(office, revoked);
        expectCount(after, isEvent('tracking_start'), 1, 'tracking_start record after the revoke');
        expectCount(after, isEvent('tracking_stop'), refused ? 1 : 0, 'tracking_stop records after the revoke');
        const changes = after.filter(isWhenInUse);
        if (refused) {
          assert.ok(changes.length <= 1, `at most one providerchange (when_in_use); records:\n${timeline(after)}`);
          ctx.log(
            changes.length === 0
              ? 'documented: the restart was refused, so the change to when_in_use was seen while tracking was off and saved without a providerchange record'
              : 'the change to when_in_use was recorded although the restart was refused',
          );
        } else {
          expectCount(after, isWhenInUse, 1, 'providerchange record with permission "when_in_use"');
        }

        // 4. GMS geofencing without background location.
        if (backend === 'gms') await expectGeofenceRefusedWithoutBackground(ctx);
        else ctx.log(`backend ${backend}: proximity alerts need only fine location; geofence refusal not checked`);

        // 5. Fixes with the app in the background.
        const startedVisible = recordedMs(resumed) >= visible.device;
        const expectFixes = api < 30 || startedVisible;
        await ctx.adb.keyHome();
        const backgrounded = await mark(ctx);
        await ctx.commands.changePace(true);
        await position.moveTo(PLACES.hqEast400m, ROUTE_SPEED_MPS);
        await sleep(SETTLE_MS, ctx.signal);
        const inBackground = receivedSince(office, backgrounded);
        const fixes = inBackground.filter(isEvent('location'));
        const serviceRunning = await ctx.app.isForegroundServiceRunning();
        ctx.log(
          `documented: API ${api}, when_in_use, service started ${startedVisible ? 'while the app was visible' : 'from the background'}: ` +
            `${fixes.length} location records while the app was in the background; foreground service running: ${serviceRunning}`,
        );
        assert.ok(serviceRunning, 'the tracking foreground service must keep running with the app in the background');
        if (expectFixes) {
          assert.ok(
            fixes.length >= 3,
            `expected at least 3 location records while the app was in the background (the service was started ` +
              `${startedVisible ? 'while the app was visible' : 'on Android 10'}, so it keeps while-in-use location ` +
              `access); found ${fixes.length}:\n${timeline(inBackground)}`,
          );
          expectOnRoute(fixes, [PLACES.hq, PLACES.hqEast400m], 'location records in the background');
        }
      } finally {
        await bestEffort(ctx, 'grant ACCESS_BACKGROUND_LOCATION again', () =>
          ctx.adb.grant(ctx.appId, PERMISSIONS.background),
        );
      }
    } finally {
      await position.stop();
    }
  },
  { requires: { api: 29 }, timeoutMs: 12 * 60_000 },
);

scenario(
  'P-P04',
  'revoke ACTIVITY_RECOGNITION: tracking continues with distance-based motion',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ permissions: 'all', batteryExempt: true, launch: true });
    // ROUTES.approachHq then ROUTES.leaveHq: 3000 m straight north through HQ, long enough for any stationary-exit
    // mechanism to notice the movement. The replay is aborted once the assertions have what they need.
    const start = ROUTES.approachHq[0];
    const route: LatLon[] = [...ROUTES.approachHq.slice(1), ...ROUTES.leaveHq.slice(1)];
    const position = new EmulatedPosition(ctx, start);
    try {
      await position.place();
      // Default test config: stop detection on, so the plugin itself must detect the movement.
      const config = await ctx.testConfig();
      await startTracking(ctx, office, config, start);
      await ctx.adb.keyHome();
      const pid = await runningPid(ctx);
      const revoked = await mark(ctx);
      try {
        await ctx.adb.revoke(ctx.appId, PERMISSIONS.activity);
        await expectKill(ctx, pid, 'revoking ACTIVITY_RECOGNITION');
        await ctx.app.launch();
        const state = await ctx.commands.ready(config);
        expectTrackingOn(office, revoked, state, 'ready() after the relaunch (only activity recognition was revoked)');
        const restore = await awaitRecord(
          ctx,
          office,
          revoked,
          'tracking_start with reason "restore"',
          isStart('restore'),
          RECORD_TIMEOUT_MS,
        );
        const anchor = await awaitRecord(
          ctx,
          office,
          revoked,
          'the motionchange (is_moving false) of the restored session',
          (r) => r.event === 'motionchange' && !r.is_moving && recordedMs(r) >= recordedMs(restore),
          INITIAL_FIX_TIMEOUT_MS,
        );
        const stationaryRadius = Number(state.config['geolocation']?.['stationaryRadius'] ?? 25);

        await ctx.adb.keyHome(); // the worker drives with the app in the background
        // The plugin has GPS off while stationary. The emulator has no network location and produces a fix only while
        // some client asks the GPS provider; `otherAppLocation` plays that other app (on a phone, network location and
        // other apps do this), so the plugin's passive updates see the drive.
        await ctx.commands.otherAppLocation(true);
        const moved = await mark(ctx);
        const driveMs = routeDurationMs([start, ...route], DRIVE_SPEED_MPS);
        const drive = position.moveAlong(route, DRIVE_SPEED_MPS);
        drive.catch(() => undefined); // awaited below; this only prevents an unhandled rejection if a wait fails first
        const motion = await awaitRecord(
          ctx,
          office,
          moved,
          'a motionchange with is_moving true (motion detected from the distance travelled, without activity recognition)',
          (r) => r.event === 'motionchange' && r.is_moving,
          driveMs + 60_000,
        );
        const fixes = await awaitRecords(
          ctx,
          office,
          moved,
          'location records after the motionchange (is_moving true)',
          (r) => r.event === 'location' && recordedMs(r) >= recordedMs(motion),
          3,
          driveMs + 60_000,
        );
        // Enough evidence: stop the rest of the replay instead of idling until the route ends.
        await position.stop();
        await drive.catch(() => undefined);

        const from = coordsOf(anchor);
        const to = coordsOf(motion);
        assert.ok(from && to, `both motionchange records need coords:\n${timeline([anchor, motion])}`);
        const distance = distanceMeters(from, to);
        assert.ok(
          distance > stationaryRadius,
          `the motionchange (is_moving true) must be triggered by a fix outside the stationary radius ` +
            `(${stationaryRadius} m) around the stationary position; it is ${distance.toFixed(1)} m away:\n` +
            timeline([anchor, motion]),
        );
        expectOnRoute(fixes, [start, ...route], 'location records while driving');
        await sleep(SETTLE_MS, ctx.signal);
        const after = receivedSince(office, revoked);
        expectCount(after, isEvent('tracking_start'), 1, 'tracking_start record after the revoke');
        expectCount(after, isEvent('tracking_stop'), 0, 'tracking_stop records (tracking must continue without activity recognition)');
      } finally {
        await bestEffort(ctx, "stop the other app's GPS request", () => ctx.commands.otherAppLocation(false));
        await bestEffort(ctx, 'grant ACTIVITY_RECOGNITION again', () => ctx.adb.grant(ctx.appId, PERMISSIONS.activity));
      }
    } finally {
      await position.stop();
    }
  },
  { requires: { api: 29 }, timeoutMs: 12 * 60_000 },
);

scenario(
  'P-P05',
  'revoke POST_NOTIFICATIONS while tracking: tracking continues',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ permissions: 'all', batteryExempt: true, launch: true });
    const position = new EmulatedPosition(ctx, PLACES.hq);
    try {
      await position.place();
      const config = await ctx.testConfig();
      await startTracking(ctx, office, config, PLACES.hq);
      await ctx.adb.keyHome();
      const pid = await runningPid(ctx);
      const revoked = await mark(ctx);
      try {
        await ctx.adb.revoke(ctx.appId, PERMISSIONS.notifications);
        // AOSP kills the app for every revoked runtime permission; if a release does not, tracking must simply go on
        // in the same process.
        const killed = await pidChanged(ctx, pid, KILL_TIMEOUT_MS);
        ctx.log(killed ? 'Android killed the process after the revoke' : 'the process survived the revoke');
        if (killed) {
          await ctx.app.launch();
          const state = await ctx.commands.ready(config);
          expectTrackingOn(office, revoked, state, 'ready() after the relaunch (only notifications were revoked)');
          await awaitRecord(ctx, office, revoked, 'tracking_start with reason "restore"', isStart('restore'), RECORD_TIMEOUT_MS);
          await ctx.adb.keyHome();
        }
        await waitUntil(async () => ((await ctx.app.isForegroundServiceRunning()) ? true : undefined), {
          timeoutMs: 30_000,
          message: 'the tracking foreground service must run without the notification permission',
          signal: ctx.signal,
        });
        const heartbeat = await awaitRecord(
          ctx,
          office,
          revoked,
          'a heartbeat after the revoke (tracking continues in the background)',
          isEvent('heartbeat'),
          (TEST_HEARTBEAT.maxInterval + 60) * 1000,
        );
        ctx.log(`heartbeat ${heartbeat.uuid} recorded at ${heartbeat.recorded_at}`);
        await sleep(SETTLE_MS, ctx.signal);

        const after = receivedSince(office, revoked);
        expectCount(after, isEvent('tracking_stop'), 0, 'tracking_stop records (notifications are not needed for tracking)');
        const starts = expectCount(after, isEvent('tracking_start'), killed ? 1 : 0, 'tracking_start records after the revoke');
        if (killed) assert.equal(starts[0]?.reason, 'restore', `tracking_start reason:\n${timeline(after)}`);
        const state = await ctx.commands.state();
        assert.equal(state.enabled, true, 'state.enabled after the revoke');
      } finally {
        await bestEffort(ctx, 'grant POST_NOTIFICATIONS again', () => ctx.adb.grant(ctx.appId, PERMISSIONS.notifications));
      }
    } finally {
      await position.stop();
    }
  },
  { requires: { api: 33 } },
);

scenario(
  'P-P06',
  'location services off/on: providerchange enabled=false/true, geofences re-registered',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ permissions: 'all', launch: true });
    const outside = PLACES.hqEast400m;
    const position = new EmulatedPosition(ctx, outside);
    const fence = circle('p06-hq', { scenario: 'P-P06' });
    try {
      await position.place();
      const config = await ctx.testConfig({ patch: keepMoving() });
      const session = await startTracking(ctx, office, config, outside);
      const backend = session.state.backend;
      await ensureMoving(ctx, office, session.since);
      await addCircle(ctx, fence);
      await sleep(SETTLE_MS, ctx.signal); // registered while outside: no transition

      const off = await mark(ctx);
      await ctx.adb.setLocationEnabled(false);
      const disabled = await awaitRecord(
        ctx,
        office,
        off,
        'a providerchange with provider.enabled false',
        (r) => r.event === 'providerchange' && r.provider?.enabled === false,
        RECORD_TIMEOUT_MS,
      );
      assert.equal(disabled.provider?.gps, false, 'provider.gps with location services off');
      assert.equal(disabled.provider?.network, false, 'provider.network with location services off');
      assert.equal(disabled.provider?.permission, 'always', 'provider.permission with location services off');
      assert.equal(disabled.provider?.accuracy, 'precise', 'provider.accuracy with location services off');
      assert.equal(disabled.provider?.backend, backend, 'provider.backend with location services off');
      // GMS drops every geofence of the app while location services are off.
      await sleep(LOCATION_OFF_MS, ctx.signal);

      const on = await mark(ctx);
      await ctx.adb.setLocationEnabled(true);
      await awaitRecord(
        ctx,
        office,
        on,
        'a providerchange with provider.enabled true',
        (r) => r.event === 'providerchange' && r.provider?.enabled === true,
        RECORD_TIMEOUT_MS,
      );
      // The engine registers the stored geofences again right after this providerchange; the device is still outside.
      await sleep(SETTLE_MS, ctx.signal);

      const entering = await mark(ctx);
      await position.moveTo(PLACES.hq, ROUTE_SPEED_MPS);
      const enter = await awaitRecord(
        ctx,
        office,
        on,
        `geofence ENTER of "${fence.identifier}" after location services were switched back on`,
        isTransition(fence.identifier, 'ENTER'),
        GEOFENCE_TIMEOUT_MS,
      );
      await sleep(SETTLE_MS, ctx.signal);

      const after = receivedSince(office, off);
      const changes = after.filter(isEvent('providerchange'));
      const whileOff = changes.filter((r) => recordedMs(r) < on.device);
      const afterOn = changes.filter((r) => recordedMs(r) >= on.device);
      assert.equal(whileOff.length, 1, `one providerchange for switching location off; records:\n${timeline(after)}`);
      // A second record after switching on is allowed when the network provider comes up after the debounce (1 s).
      assert.ok(
        afterOn.length >= 1 && afterOn.length <= 2 && afterOn.every((r) => r.provider?.enabled === true),
        `one or two providerchange records with provider.enabled true after switching location on; records:\n${timeline(after)}`,
      );
      assert.equal(afterOn.at(-1)?.provider?.gps, true, `provider.gps after switching location on:\n${timeline(afterOn)}`);

      const transitions = after.filter(isTransition(fence.identifier));
      assert.deepEqual(
        transitions.map((r) => r.geofence?.action),
        ['ENTER'],
        `geofence transitions of "${fence.identifier}" since location was switched off:\n${timeline(transitions)}`,
      );
      expectAfterCrossing(enter, entering, outside, PLACES.hq, fence, ROUTE_SPEED_MPS, 'ENTER');
      expectTriggerPosition(enter, fence, true);
    } finally {
      await bestEffort(ctx, 'switch location services on', () => ctx.adb.setLocationEnabled(true));
      await position.stop();
      await bestEffort(ctx, `remove geofence ${fence.identifier}`, () => ctx.commands.removeGeofence(fence.identifier));
    }
  },
  { timeoutMs: 12 * 60_000 },
);

scenario(
  'P-P07',
  'locationProvider auto -> android -> gms at runtime: backend changes in records',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ permissions: 'all', launch: true });
    const position = new EmulatedPosition(ctx, PLACES.hq);
    try {
      await position.place();
      const config = await ctx.testConfig({ locationProvider: 'auto', patch: keepMoving() });
      const session = await startTracking(ctx, office, config, PLACES.hq);
      assert.equal(session.state.backend, 'gms', "locationProvider 'auto' must select gms when Google Play services are present");
      assert.equal(session.started.backend, 'gms', 'tracking_start.backend');
      await ensureMoving(ctx, office, session.since);

      const toAndroid = await mark(ctx);
      let state = await ctx.commands.setConfig({ locationProvider: 'android' });
      assert.equal(state.backend, 'android', "state.backend after setConfig({locationProvider: 'android'})");
      assert.equal(state.config['locationProvider'], 'android', 'state.config.locationProvider');
      const androidChange = await awaitRecord(
        ctx,
        office,
        toAndroid,
        'a providerchange with provider.backend "android"',
        (r) => r.event === 'providerchange' && r.provider?.backend === 'android',
        RECORD_TIMEOUT_MS,
      );
      await position.moveTo(PLACES.hqEast400m, ROUTE_SPEED_MPS);
      const androidFixes = await awaitRecords(
        ctx,
        office,
        toAndroid,
        'location records from the android backend',
        (r) => r.event === 'location' && r.backend === 'android',
        3,
        RECORD_TIMEOUT_MS,
      );
      expectOnRoute(androidFixes, [PLACES.hq, PLACES.hqEast400m], 'android location records');

      const toGms = await mark(ctx);
      state = await ctx.commands.setConfig({ locationProvider: 'gms' });
      assert.equal(state.backend, 'gms', "state.backend after setConfig({locationProvider: 'gms'})");
      const gmsChange = await awaitRecord(
        ctx,
        office,
        toGms,
        'a providerchange with provider.backend "gms"',
        (r) => r.event === 'providerchange' && r.provider?.backend === 'gms',
        RECORD_TIMEOUT_MS,
      );
      await position.moveTo(PLACES.hq, ROUTE_SPEED_MPS);
      const gmsFixes = await awaitRecords(
        ctx,
        office,
        toGms,
        'location records from the gms backend after switching back',
        (r) => r.event === 'location' && r.backend === 'gms',
        3,
        RECORD_TIMEOUT_MS,
      );
      expectOnRoute(gmsFixes, [PLACES.hqEast400m, PLACES.hq], 'gms location records');
      await sleep(SETTLE_MS, ctx.signal);

      const all = receivedSince(office, session.since);
      // The window between a setConfig call and its providerchange is skipped: a record created inside it may carry
      // either backend.
      const expectedBackend = (r: WireRecord): WireRecord['backend'] | undefined => {
        const t = recordedMs(r);
        if (t < toAndroid.device) return 'gms';
        if (t < recordedMs(androidChange)) return undefined;
        if (t < toGms.device) return 'android';
        if (t < recordedMs(gmsChange)) return undefined;
        return 'gms';
      };
      const wrong = all.filter((r) => {
        const expected = expectedBackend(r);
        return expected !== undefined && r.backend !== expected;
      });
      assert.equal(
        wrong.length,
        0,
        `records whose backend is not the one active when they were created:\n${timeline(wrong)}\nall records:\n${timeline(all)}`,
      );
      assert.equal(androidChange.backend, 'android', 'providerchange (android).backend');
      assert.equal(gmsChange.backend, 'gms', 'providerchange (gms).backend');
      assert.deepEqual(
        all.filter(isEvent('providerchange')).map((r) => r.provider?.backend),
        ['android', 'gms'],
        `one providerchange per backend switch:\n${timeline(all.filter(isEvent('providerchange')))}`,
      );
    } finally {
      await position.stop();
    }
  },
  { requires: { gms: true } },
);

scenario(
  'P-P08',
  'image without Google Play services: backend android, tracking works',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ permissions: 'all', launch: true });
    const position = new EmulatedPosition(ctx, PLACES.hq);
    try {
      await position.place();
      const config = await ctx.testConfig({ locationProvider: 'auto', patch: keepMoving() });
      const session = await startTracking(ctx, office, config, PLACES.hq);
      assert.equal(
        session.state.backend,
        'android',
        "locationProvider 'auto' must select the android backend when Google Play services are missing",
      );
      await ensureMoving(ctx, office, session.since);
      const moved = await mark(ctx);
      await position.moveTo(PLACES.hqEast400m, ROUTE_SPEED_MPS);
      const fixes = await awaitRecords(
        ctx,
        office,
        moved,
        'location records while moving',
        isEvent('location'),
        3,
        RECORD_TIMEOUT_MS,
      );
      expectOnRoute(fixes, [PLACES.hq, PLACES.hqEast400m], 'location records');

      const state = await ctx.commands.setConfig({ locationProvider: 'gms' });
      assert.equal(state.backend, 'android', "locationProvider 'gms' must fall back to android without Google Play services");
      const stopped = await ctx.commands.stop();
      assert.equal(stopped.enabled, false, 'stop() (state.enabled)');
      await awaitRecord(ctx, office, session.since, 'tracking_stop with reason "stop"', isStop('stop'), RECORD_TIMEOUT_MS);
      await sleep(SETTLE_MS, ctx.signal);

      const all = receivedSince(office, session.since);
      const notAndroid = all.filter((r) => r.backend !== 'android');
      assert.equal(notAndroid.length, 0, `records with a backend other than android:\n${timeline(notAndroid)}`);
      expectCount(all, isEvent('providerchange'), 0, 'providerchange records (the backend never changed)');
      expectCount(all, isStart('start'), 1, 'tracking_start (start) record');
    } finally {
      await position.stop();
    }
  },
  { requires: { gms: false } },
);

scenario(
  'P-P09',
  'mock locations: mock:true; rejectMockLocations drops them',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ permissions: 'all', launch: true });
    const start = PLACES.hqEast400m;
    const position = new EmulatedPosition(ctx, start);
    try {
      await position.place();
      // The product backend (auto: gms on Google APIs images): the mock flag must survive the fused provider too.
      const config = await ctx.testConfig({ patch: keepMoving() });
      const session = await startTracking(ctx, office, config, start);
      const moving = await ensureMoving(ctx, office, session.since);
      assert.equal(session.initial.mock, false, `the emulator GPS fix is not a mock location:\n${timeline([session.initial])}`);
      assert.equal(moving.mock, false, `the emulator GPS fix is not a mock location:\n${timeline([moving])}`);

      // Mock route A continues from the real position (no jump for maxImpliedSpeed), route B continues from A.
      const endA = assertions.offsetMeters(start, 300, 0);
      const endB = assertions.offsetMeters(endA, 300, 0);
      const endC = assertions.offsetMeters(endA, 0, 300);
      const onA = (p: LatLon) => distanceToSegmentMeters(p, start, endA) <= ROUTE_TOLERANCE_M && distanceMeters(p, start) > 40;
      const onB = (p: LatLon) => distanceToSegmentMeters(p, endA, endB) <= ROUTE_TOLERANCE_M && distanceMeters(p, endA) > 40;

      // Phase A: mock fixes are kept, with mock: true.
      await position.pause();
      const phaseA = await mark(ctx);
      await ctx.adb.addTestProvider(GPS);
      await playTestProviderRoute(ctx, GPS, start, endA, ROUTE_SPEED_MPS);
      const mocks = await awaitRecords(
        ctx,
        office,
        phaseA,
        'location records with mock true from the test provider',
        (r) => r.event === 'location' && r.mock === true,
        3,
        RECORD_TIMEOUT_MS,
      );
      expectOnRoute(mocks, [start, endA], 'mock location records');
      const atMockPositions = receivedSince(office, phaseA).filter((r) => {
        const p = coordsOf(r);
        return p !== null && onA(p);
      });
      const unflagged = atMockPositions.filter((r) => !r.mock);
      assert.equal(unflagged.length, 0, `records at test-provider positions must have mock true:\n${timeline(unflagged)}`);

      // Phase B: with rejectMockLocations the mock fixes are dropped.
      const state = await ctx.commands.setConfig({ geolocation: { filter: { rejectMockLocations: true } } });
      assert.equal(
        state.config['geolocation']?.['filter']?.['rejectMockLocations'],
        true,
        'state.config.geolocation.filter.rejectMockLocations after setConfig',
      );
      const phaseB = await mark(ctx);
      await playTestProviderRoute(ctx, GPS, endA, endB, ROUTE_SPEED_MPS);
      await sleep(SETTLE_MS, ctx.signal);
      const phaseC = await mark(ctx);
      const duringB = receivedSince(office, phaseB).filter((r) => recordedMs(r) < phaseC.device);
      expectCount(
        duringB,
        (r) => r.event === 'location' || r.event === 'motionchange',
        0,
        'location or motionchange records while only mock fixes arrived with rejectMockLocations on',
      );
      const leaked = duringB.filter((r) => {
        const p = coordsOf(r);
        return p !== null && onB(p);
      });
      assert.equal(leaked.length, 0, `records carrying a dropped mock position:\n${timeline(leaked)}`);

      // Phase C: real fixes are still accepted. The emulator GPS continues where route A ended; while the test provider
      // overrides "gps" no fix of that jump reaches the app.
      await position.relocate(endA);
      await ctx.adb.removeTestProvider(GPS);
      await position.moveTo(endC, ROUTE_SPEED_MPS);
      const real = await awaitRecords(
        ctx,
        office,
        phaseC,
        'location records with mock false after the test provider was removed',
        (r) => r.event === 'location' && r.mock === false,
        3,
        RECORD_TIMEOUT_MS,
      );
      expectOnRoute(real, [endA, endC], 'real location records');
      const flagged = receivedSince(office, phaseC).filter((r) => r.event === 'location' && r.mock);
      assert.equal(flagged.length, 0, `location records with mock true after the test provider was removed:\n${timeline(flagged)}`);
    } finally {
      await bestEffort(ctx, `remove test provider ${GPS}`, () => ctx.adb.removeTestProvider(GPS));
      await position.stop();
    }
  },
  // `cmd location providers add-test-provider` / `set-test-provider-location` exist from Android 12 (API 31).
  { requires: { api: 31 } },
);

scenario(
  'P-P10',
  'circular geofence ENTER / EXIT / DWELL via geo fix',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ permissions: 'all', launch: true });
    const outside = PLACES.hqEast400m;
    const center = PLACES.hq;
    const position = new EmulatedPosition(ctx, outside);
    // Radius 150 m (the HQ premise; GMS recommends at least 100 m), loitering delay 30 s: DWELL within a minute.
    const fence = circle('p10-hq', { scenario: 'P-P10', premise: PREMISES.hq.id }, LOITERING_DELAY_MS);
    try {
      await position.place();
      const config = await ctx.testConfig({ patch: keepMoving() });
      const session = await startTracking(ctx, office, config, outside);
      await ensureMoving(ctx, office, session.since);
      const added = await mark(ctx);
      await addCircle(ctx, fence);
      await sleep(SETTLE_MS, ctx.signal); // outside: no transition

      const entering = await mark(ctx);
      await position.moveTo(center, ROUTE_SPEED_MPS); // then stays at the centre
      const enter = await awaitRecord(ctx, office, added, 'geofence ENTER', isTransition(fence.identifier, 'ENTER'), GEOFENCE_TIMEOUT_MS);
      const dwell = await awaitRecord(
        ctx,
        office,
        added,
        'geofence DWELL',
        isTransition(fence.identifier, 'DWELL'),
        LOITERING_DELAY_MS + GEOFENCE_TIMEOUT_MS,
      );
      const leaving = await mark(ctx);
      await position.moveTo(outside, ROUTE_SPEED_MPS);
      const exit = await awaitRecord(ctx, office, added, 'geofence EXIT', isTransition(fence.identifier, 'EXIT'), GEOFENCE_TIMEOUT_MS);
      await sleep(SETTLE_MS, ctx.signal);

      const transitions = receivedSince(office, added).filter(isTransition(fence.identifier));
      assert.deepEqual(
        transitions.map((r) => r.geofence?.action),
        ['ENTER', 'DWELL', 'EXIT'],
        `geofence transitions of "${fence.identifier}", in order, each once:\n${timeline(transitions)}`,
      );
      const enteredAt = expectAfterCrossing(enter, entering, outside, center, fence, ROUTE_SPEED_MPS, 'ENTER');
      assert.ok(
        recordedMs(dwell) >= enteredAt + LOITERING_DELAY_MS - TIMING_TOLERANCE_MS,
        `DWELL must come at least loiteringDelay (${LOITERING_DELAY_MS / 1000} s) after the device entered at ` +
          `${new Date(enteredAt).toISOString()}:\n${timeline(transitions)}`,
      );
      expectAfterCrossing(exit, leaving, center, outside, fence, ROUTE_SPEED_MPS, 'EXIT');
      expectTriggerPosition(enter, fence, true);
      expectTriggerPosition(exit, fence, false);
      for (const record of transitions) {
        assert.deepEqual(record.geofence?.extras, fence.extras, `geofence.extras of ${record.geofence?.action}`);
      }
    } finally {
      await position.stop();
      await bestEffort(ctx, `remove geofence ${fence.identifier}`, () => ctx.commands.removeGeofence(fence.identifier));
    }
  },
  { timeoutMs: 12 * 60_000 },
);

scenario(
  'P-P11',
  'geofences re-registered after reboot',
  async (ctx) => {
    const office = await ctx.backOffice();
    await ctx.app.prepare({ permissions: 'all', launch: true });
    const outside = PLACES.hqEast400m;
    const position = new EmulatedPosition(ctx, outside);
    const fence = circle('p11-hq', { scenario: 'P-P11' });
    try {
      await position.place();
      const config = await ctx.testConfig({ startOnBoot: true, patch: keepMoving() });
      const session = await startTracking(ctx, office, config, outside);
      await addCircle(ctx, fence);
      const bootCountBefore = session.started.boot_count;

      await position.pause();
      const rebooting = await mark(ctx);
      await ctx.adb.reboot({ timeoutMs: 5 * 60_000 });
      await position.place(); // keep the emulated GPS outside the geofence after the boot
      const boot = await awaitRecord(
        ctx,
        office,
        rebooting,
        'tracking_start with reason "boot" (app.startOnBoot true)',
        isStart('boot'),
        BOOT_RECORD_TIMEOUT_MS,
      );
      if (bootCountBefore >= 0 && boot.boot_count >= 0) {
        assert.ok(boot.boot_count > bootCountBefore, `boot_count after the reboot (${boot.boot_count}) > before (${bootCountBefore})`);
      }
      const stored = await ctx.commands.getGeofences();
      assert.ok(
        stored.some((g) => g.identifier === fence.identifier),
        `getGeofences() after the reboot must list "${fence.identifier}"; got ${JSON.stringify(stored.map((g) => g.identifier))}`,
      );
      const booted: Mark = { host: rebooting.host, device: recordedMs(boot) };
      await ensureMoving(ctx, office, booted);
      await sleep(SETTLE_MS, ctx.signal); // outside, GPS on: no transition

      const entering = await mark(ctx);
      await position.moveTo(PLACES.hq, ROUTE_SPEED_MPS);
      const enter = await awaitRecord(
        ctx,
        office,
        booted,
        `geofence ENTER of "${fence.identifier}" after the reboot`,
        isTransition(fence.identifier, 'ENTER'),
        GEOFENCE_TIMEOUT_MS,
      );
      await sleep(SETTLE_MS, ctx.signal);

      assert.equal(enter.boot_count, boot.boot_count, 'boot_count of the ENTER record = boot_count of tracking_start (boot)');
      const transitions = receivedSince(office, rebooting).filter(isTransition(fence.identifier));
      assert.deepEqual(
        transitions.map((r) => r.geofence?.action),
        ['ENTER'],
        `geofence transitions of "${fence.identifier}" since the reboot:\n${timeline(transitions)}`,
      );
      expectAfterCrossing(enter, entering, outside, PLACES.hq, fence, ROUTE_SPEED_MPS, 'ENTER');
    } finally {
      await position.stop();
      await bestEffort(ctx, `remove geofence ${fence.identifier}`, () => ctx.commands.removeGeofence(fence.identifier));
    }
  },
  { timeoutMs: 15 * 60_000 },
);

// ---------------------------------------------------------------------------------------------------------------------
// Local helpers (candidates for the kit; listed in the unit report)

/** A moment on both clocks: host `Date.now()` (back office `receivedAt`) and device wall clock (`recorded_at`). */
interface Mark {
  host: number;
  device: number;
}

async function mark(ctx: ScenarioContext): Promise<Mark> {
  const host = Date.now();
  const device = await ctx.adb.deviceTime();
  return { host, device };
}

function recordedMs(record: WireRecord): number {
  return Date.parse(record.recorded_at);
}

/**
 * Records received at or after [since].host and created at or after [since].device, each uuid once, ordered by
 * `recorded_at`. Filtering on both clocks drops records created earlier but uploaded later.
 */
function receivedSince(office: MockBackOffice, since: Mark): WireRecord[] {
  return office
    .records({ since: since.host, unique: true })
    .map((stored) => stored.record)
    .filter((record) => recordedMs(record) >= since.device)
    .sort((a, b) => recordedMs(a) - recordedMs(b));
}

/** Polls the back office until [count] records created since [since] match; the timeout error lists what arrived. */
async function awaitRecords(
  ctx: ScenarioContext,
  office: MockBackOffice,
  since: Mark,
  what: string,
  match: (record: WireRecord) => boolean,
  count: number,
  timeoutMs: number,
): Promise<WireRecord[]> {
  try {
    return await office.waitFor(
      (o) => {
        const found = receivedSince(o, since).filter(match);
        return found.length >= count ? found : undefined;
      },
      { timeoutMs, intervalMs: 1_000, message: what, signal: ctx.signal },
    );
  } catch (error) {
    if (ctx.signal.aborted) throw error;
    const arrived = receivedSince(office, since);
    const cause = error instanceof Error ? error.message : String(error);
    throw new Error(
      `expected ${count === 1 ? '' : `${count} records: `}${what} within ${Math.round(timeoutMs / 1000)} s; ` +
        `${arrived.filter(match).length} matched (wait ended with: ${cause}). Records created since ` +
        `${new Date(since.device).toISOString()} (device clock):\n${timeline(arrived)}`,
      { cause: error },
    );
  }
}

async function awaitRecord(
  ctx: ScenarioContext,
  office: MockBackOffice,
  since: Mark,
  what: string,
  match: (record: WireRecord) => boolean,
  timeoutMs: number,
): Promise<WireRecord> {
  const [first] = await awaitRecords(ctx, office, since, what, match, 1, timeoutMs);
  return first!;
}

/** The first matching record created since [since] within [windowMs], or undefined (never throws on timeout). */
async function recordWithin(
  ctx: ScenarioContext,
  office: MockBackOffice,
  since: Mark,
  match: (record: WireRecord) => boolean,
  windowMs: number,
): Promise<WireRecord | undefined> {
  const deadline = Date.now() + windowMs;
  for (;;) {
    const found = receivedSince(office, since).find(match);
    if (found) return found;
    if (Date.now() >= deadline) return undefined;
    await sleep(1_000, ctx.signal);
  }
}

/** Asserts that exactly [expected] of [records] match; returns the matches. */
function expectCount(
  records: readonly WireRecord[],
  match: (record: WireRecord) => boolean,
  expected: number,
  what: string,
): WireRecord[] {
  const found = records.filter(match);
  assert.equal(found.length, expected, `expected ${expected} ${what}, found ${found.length}; records:\n${timeline(records)}`);
  return found;
}

/** Fails with the audit trail since [since] when [state] says tracking is off. */
function expectTrackingOn(office: MockBackOffice, since: Mark, state: StateJson, what: string): void {
  if (state.enabled) return;
  assert.fail(`${what}: expected tracking to be on, state.enabled is false; records since then:\n${timeline(receivedSince(office, since))}`);
}

function isEvent(event: WireRecord['event']): (record: WireRecord) => boolean {
  return (record) => record.event === event;
}

function isStart(reason?: string): (record: WireRecord) => boolean {
  return (record) => record.event === 'tracking_start' && (reason === undefined || record.reason === reason);
}

function isStop(reason?: string): (record: WireRecord) => boolean {
  return (record) => record.event === 'tracking_stop' && (reason === undefined || record.reason === reason);
}

function isTransition(identifier: string, action?: 'ENTER' | 'EXIT' | 'DWELL'): (record: WireRecord) => boolean {
  return (record) =>
    record.event === 'geofence' &&
    record.geofence?.identifier === identifier &&
    (action === undefined || record.geofence.action === action);
}

function describeRecord(record: WireRecord): string {
  const parts: string[] = [record.recorded_at, record.event];
  if (record.reason !== undefined) parts.push(`reason=${record.reason}`);
  if (record.event === 'motionchange') parts.push(`is_moving=${record.is_moving}`);
  if (record.geofence) parts.push(`geofence=${record.geofence.identifier}:${record.geofence.action}`);
  if (record.provider) {
    const p = record.provider;
    parts.push(
      `provider={enabled:${p.enabled},gps:${p.gps},network:${p.network},permission:${p.permission},accuracy:${p.accuracy},backend:${p.backend}}`,
    );
  }
  parts.push(`backend=${record.backend}`, `mock=${record.mock}`);
  parts.push(
    record.coords
      ? `@${record.coords.latitude.toFixed(5)},${record.coords.longitude.toFixed(5)}±${Math.round(record.coords.accuracy)}m`
      : '@null',
  );
  parts.push(`boot=${record.boot_count}`, `uuid=${record.uuid}`);
  return parts.join(' ');
}

/**
 * One record per line. Above [limit] records it shows the first quarter and the last three quarters of [limit] (the
 * audit records that explain a failure are usually near the start, the latest state at the end).
 */
function timeline(records: readonly WireRecord[], limit = 60): string {
  if (records.length === 0) return '  (none)';
  const line = (record: WireRecord) => `  ${describeRecord(record)}`;
  if (records.length <= limit) return records.map(line).join('\n');
  const head = Math.floor(limit / 4);
  const tail = limit - head;
  return [
    ...records.slice(0, head).map(line),
    `  ... ${records.length - limit} records not shown ...`,
    ...records.slice(-tail).map(line),
  ].join('\n');
}

async function bestEffort(ctx: ScenarioContext, what: string, action: () => Promise<unknown>): Promise<void> {
  try {
    await action();
  } catch (error) {
    ctx.log(`cleanup step "${what}" failed: ${error instanceof Error ? error.message : String(error)}`);
  }
}

// ---- tracking

/** Stop detection off: after changePace(true) the plugin stays MOVING and keeps its high-accuracy GPS request. */
function keepMoving(): Record<string, unknown> {
  return { activity: { disableStopDetection: true } };
}

/**
 * Location permissions of [api] in revoke order: fine and coarse first and adjacent (the window in which a restarted
 * service could still see a foreground grant stays as short as possible), then background (API 29+).
 */
function locationPermissions(api: number): string[] {
  const foreground = [PERMISSIONS.fine, PERMISSIONS.coarse];
  return api >= 29 ? [...foreground, PERMISSIONS.background] : foreground;
}

interface TrackingSession {
  since: Mark;
  state: StateJson;
  /** `tracking_start` (reason `start`) */
  started: WireRecord;
  /** the initial `motionchange` (is_moving false) */
  initial: WireRecord;
}

/**
 * `ready(config)` + `start()` through the debug receiver, then waits for `tracking_start` (start) and the initial
 * motionchange, and checks that the initial fix is the emulated position [at] (fails early when the emulator GPS does
 * not deliver fixes, instead of timing out later).
 */
async function startTracking(
  ctx: ScenarioContext,
  office: MockBackOffice,
  config: Record<string, unknown>,
  at: LatLon,
): Promise<TrackingSession> {
  const since = await mark(ctx);
  const ready = await ctx.commands.ready(config);
  assert.equal(ready.enabled, false, 'ready() right after prepare() (cleared data) must report tracking off');
  const state = await ctx.commands.start();
  assert.equal(state.enabled, true, 'start() must turn tracking on (state.enabled)');
  assert.equal(state.trackingMode, 'location', 'start() tracking mode');
  const started = await awaitRecord(ctx, office, since, 'tracking_start with reason "start"', isStart('start'), RECORD_TIMEOUT_MS);
  const initial = await awaitRecord(
    ctx,
    office,
    since,
    'the initial motionchange with is_moving false',
    (r) => r.event === 'motionchange' && !r.is_moving,
    INITIAL_FIX_TIMEOUT_MS,
  );
  assert.equal(started.backend, state.backend, 'tracking_start.backend = state.backend');
  const where = coordsOf(initial);
  assert.ok(
    where !== null && distanceMeters(where, at) <= PLACEMENT_TOLERANCE_M,
    `the initial motionchange must carry the emulated GPS position ${formatPoint(at)} (within ${PLACEMENT_TOLERANCE_M} m); ` +
      `got ${describeRecord(initial)}. The emulator did not deliver the geo fix to the app.`,
  );
  return { since, state, started, initial };
}

/** changePace(true) and the motionchange (is_moving true) created since [since] (also one the plugin made itself). */
async function ensureMoving(ctx: ScenarioContext, office: MockBackOffice, since: Mark): Promise<WireRecord> {
  await ctx.commands.changePace(true);
  const state = await ctx.commands.state();
  assert.equal(state.isMoving, true, 'state.isMoving after changePace(true)');
  return awaitRecord(
    ctx,
    office,
    since,
    'a motionchange with is_moving true',
    (r) => r.event === 'motionchange' && r.is_moving,
    RECORD_TIMEOUT_MS,
  );
}

async function runningPid(ctx: ScenarioContext): Promise<number> {
  const pid = await ctx.app.pid();
  assert.ok(pid !== null, `expected a running process of ${ctx.appId} while tracking`);
  return pid;
}

/** True when the app's main process is no longer [before] within [timeoutMs] (gone or restarted). */
async function pidChanged(ctx: ScenarioContext, before: number, timeoutMs: number): Promise<boolean> {
  try {
    await waitUntil(async () => ((await ctx.app.pid()) !== before ? true : undefined), {
      timeoutMs,
      intervalMs: 250,
      message: `process ${before} of ${ctx.appId} to end`,
      signal: ctx.signal,
    });
    return true;
  } catch (error) {
    if (ctx.signal.aborted) throw error;
    return false;
  }
}

async function expectKill(ctx: ScenarioContext, before: number, cause: string): Promise<void> {
  if (await pidChanged(ctx, before, KILL_TIMEOUT_MS)) return;
  assert.fail(
    `expected Android to kill process ${before} of ${ctx.appId} within ${KILL_TIMEOUT_MS / 1000} s after ${cause}; it still runs`,
  );
}

// ---- geofences

/** A circle on the HQ premise (centre PLACES.hq, radius 150 m) with ENTER and EXIT, and DWELL when [dwellAfterMs]. */
function circle(identifier: string, extras: Record<string, unknown>, dwellAfterMs?: number): GeofenceJson {
  const fence: GeofenceJson = {
    identifier,
    latitude: PREMISES.hq.latitude,
    longitude: PREMISES.hq.longitude,
    radius: PREMISES.hq.radius,
    notifyOnEntry: true,
    notifyOnExit: true,
    notifyOnDwell: dwellAfterMs !== undefined,
    extras,
  };
  if (dwellAfterMs !== undefined) fence.loiteringDelay = dwellAfterMs;
  return fence;
}

/** Centre and radius of a circular geofence. */
function circleOf(fence: GeofenceJson): { center: LatLon; radius: number } {
  assert.ok(
    fence.latitude !== undefined && fence.longitude !== undefined && fence.radius !== undefined,
    `geofence "${fence.identifier}" is not a circle`,
  );
  return { center: { lat: fence.latitude, lon: fence.longitude }, radius: fence.radius };
}

/** addGeofence + getGeofences; explains the usual emulator cause of an UNAVAILABLE rejection. */
async function addCircle(ctx: ScenarioContext, fence: GeofenceJson): Promise<void> {
  try {
    await ctx.commands.addGeofence(fence);
  } catch (error) {
    if (error instanceof E2eCommandError && error.code === 'UNAVAILABLE') {
      throw new Error(
        `addGeofence("${fence.identifier}") was rejected with UNAVAILABLE. On the gms backend this is ` +
          `GEOFENCE_NOT_AVAILABLE (1000): location services are off or "Google Location Accuracy" (network location ` +
          `consent) is off on the emulator image. ${error.message}`,
        { cause: error },
      );
    }
    throw error;
  }
  const stored = await ctx.commands.getGeofences();
  assert.ok(
    stored.some((g) => g.identifier === fence.identifier),
    `getGeofences() must list "${fence.identifier}" after addGeofence; got ${JSON.stringify(stored.map((g) => g.identifier))}`,
  );
}

/** P-P03: with only while-in-use location, GMS refuses geofences (GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION). */
async function expectGeofenceRefusedWithoutBackground(ctx: ScenarioContext): Promise<void> {
  const fence = circle('p03-needs-background', { scenario: 'P-P03' });
  try {
    await ctx.commands.addGeofence(fence);
  } catch (error) {
    if (!(error instanceof E2eCommandError)) throw error;
    assert.equal(
      error.code,
      'PERMISSION_DENIED',
      `addGeofence without background location on the gms backend must be rejected with PERMISSION_DENIED ` +
        `(GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION); got ${error.code}: ${error.message}`,
    );
    const stored = await ctx.commands.getGeofences();
    assert.ok(
      !stored.some((g) => g.identifier === fence.identifier),
      `a geofence the OS refused must not be stored; getGeofences() lists ${JSON.stringify(stored.map((g) => g.identifier))}`,
    );
    ctx.log('documented: with when_in_use, addGeofence on the gms backend is rejected with PERMISSION_DENIED and not stored');
    return;
  }
  await bestEffort(ctx, `remove geofence ${fence.identifier}`, () => ctx.commands.removeGeofence(fence.identifier));
  assert.fail(
    'addGeofence succeeded with only while-in-use location on the gms backend; GMS geofencing requires ' +
      'ACCESS_BACKGROUND_LOCATION on Android 10+, so the premise geofence would never fire in the background',
  );
}

/**
 * Asserts that [record] was created no earlier than the moment the straight replay [from] -> [to] (marked [started]
 * just before the replay began, at [speedMps]) crossed the boundary of [fence]; returns that moment (device clock).
 * The replay is never faster than its nominal speed, so the computed moment is a lower bound.
 */
function expectAfterCrossing(
  record: WireRecord,
  started: Mark,
  from: LatLon,
  to: LatLon,
  fence: GeofenceJson,
  speedMps: number,
  what: string,
): number {
  const { center, radius } = circleOf(fence);
  const offset = boundaryCrossingMs(from, to, center, radius, speedMps);
  assert.ok(offset !== undefined, `the route ${formatPoint(from)} -> ${formatPoint(to)} does not cross the geofence boundary`);
  const crossedAt = started.device + offset;
  assert.ok(
    recordedMs(record) >= crossedAt - TIMING_TOLERANCE_MS,
    `${what} was recorded at ${record.recorded_at}, before the device crossed the boundary at ` +
      `${new Date(crossedAt).toISOString()} (tolerance ${TIMING_TOLERANCE_MS / 1000} s):\n${timeline([record])}`,
  );
  return crossedAt;
}

/** The triggering fix of an ENTER lies inside [fence], that of an EXIT outside (within its accuracy + slack). */
function expectTriggerPosition(record: WireRecord, fence: GeofenceJson, inside: boolean): void {
  if (!record.coords) return; // no fix known: nothing to compare
  const { center, radius } = circleOf(fence);
  const distance = distanceMeters({ lat: record.coords.latitude, lon: record.coords.longitude }, center);
  const slack = record.coords.accuracy + GEOFENCE_POSITION_TOLERANCE_M;
  const ok = inside ? distance <= radius + slack : distance >= radius - slack;
  assert.ok(
    ok,
    `${record.geofence?.action} fix is ${distance.toFixed(1)} m from the centre; expected ${inside ? '<=' : '>='} ` +
      `${radius} m ${inside ? '+' : '-'} ${slack.toFixed(1)} m:\n${timeline([record])}`,
  );
}

/** Milliseconds after leaving [from] at which the straight route to [to] first changes side of the circle. */
function boundaryCrossingMs(from: LatLon, to: LatLon, center: LatLon, radius: number, speedMps: number): number | undefined {
  const length = distanceMeters(from, to);
  const startsInside = distanceMeters(from, center) <= radius;
  const steps = Math.max(1, Math.ceil(length)); // 1 m resolution
  for (let i = 1; i <= steps; i += 1) {
    const f = i / steps;
    if ((distanceMeters(interpolate(from, to, f), center) <= radius) !== startsInside) {
      return ((f * length) / speedMps) * 1000;
    }
  }
  return undefined;
}

// ---- positions

/**
 * The emulator's GPS position. Positions change only along continuous routes (the plugin rejects fixes with an
 * implied speed above 80 m/s), and a held position is re-sent every HOLD_INTERVAL_MS so fixes keep flowing while the
 * plugin requests GPS. [stop] must run in a `finally`, so no replay outlives its scenario.
 */
class EmulatedPosition {
  readonly ctx: ScenarioContext;
  current: LatLon;
  private holding: { controller: AbortController; loop: Promise<void> } | undefined;
  /** Aborts the current replay; set before the replay awaits anything, so stop() always reaches it. */
  private moving: AbortController | undefined;
  /** The current replay, settled (never rejects); stop() waits for it, including its in-flight geo fix. */
  private replay: Promise<void> = Promise.resolve();
  /** Set by [stop]: no replay or hold starts afterwards (a replay that ends during stop() must not hold again). */
  private stopped = false;

  constructor(ctx: ScenarioContext, start: LatLon) {
    this.ctx = ctx;
    this.current = start;
  }

  /** Sends the current position and keeps re-sending it. */
  async place(): Promise<void> {
    await this.pause();
    await this.ctx.adb.geoFix(this.current.lat, this.current.lon);
    this.startHolding();
  }

  /** Replays the straight route to [target], then holds it. */
  async moveTo(target: LatLon, speedMps: number): Promise<void> {
    await this.moveAlong([target], speedMps);
  }

  /** Replays current -> [points] at [speedMps] (one fix per second), then holds the last point. */
  async moveAlong(points: readonly LatLon[], speedMps: number): Promise<void> {
    if (this.stopped) throw new Error('EmulatedPosition.moveAlong after stop()');
    const controller = new AbortController();
    this.moving?.abort();
    this.moving = controller;
    const run = (async () => {
      await this.pause();
      if (controller.signal.aborted) throw new Error('EmulatedPosition: replay aborted by stop()');
      await this.ctx.adb.playRoute([this.current, ...points], {
        speedMps,
        intervalMs: 1_000,
        signal: AbortSignal.any([controller.signal, this.ctx.signal]),
      });
    })();
    this.replay = run.then(
      () => undefined,
      () => undefined,
    );
    try {
      await run;
    } finally {
      if (this.moving === controller) this.moving = undefined;
    }
    this.current = points[points.length - 1] ?? this.current;
    this.startHolding();
  }

  /**
   * Sets the emulator position without a route. Only while a test provider overrides "gps": no fix of the jump
   * reaches the app then.
   */
  async relocate(point: LatLon): Promise<void> {
    await this.pause();
    await this.ctx.adb.geoFix(point.lat, point.lon);
    this.current = point;
  }

  /** Stops re-sending the held position (the emulator keeps its last position). */
  async pause(): Promise<void> {
    const holding = this.holding;
    this.holding = undefined;
    if (!holding) return;
    holding.controller.abort();
    await holding.loop;
  }

  /** Aborts a running replay, waits until it has ended, and stops holding. Idempotent. */
  async stop(): Promise<void> {
    this.stopped = true;
    this.moving?.abort();
    this.moving = undefined;
    await this.replay;
    await this.pause();
  }

  private startHolding(): void {
    if (this.stopped) return;
    const controller = new AbortController();
    const signal = AbortSignal.any([controller.signal, this.ctx.signal]);
    const point = this.current;
    const loop = (async () => {
      while (!signal.aborted) {
        try {
          await sleep(HOLD_INTERVAL_MS, signal);
        } catch {
          return;
        }
        try {
          await this.ctx.adb.geoFix(point.lat, point.lon);
        } catch (error) {
          this.ctx.log(`geo fix ${formatPoint(point)} failed: ${error instanceof Error ? error.message : String(error)}`);
        }
      }
    })();
    this.holding = { controller, loop };
  }
}

/** Feeds a straight route to a test provider, one fix per second at [speedMps]. */
async function playTestProviderRoute(
  ctx: ScenarioContext,
  provider: string,
  from: LatLon,
  to: LatLon,
  speedMps: number,
): Promise<void> {
  const steps = Math.max(1, Math.round(distanceMeters(from, to) / speedMps));
  for (let i = 0; i <= steps; i += 1) {
    const point = interpolate(from, to, i / steps);
    await ctx.adb.setTestLocation(provider, point.lat, point.lon, TEST_PROVIDER_ACCURACY_M);
    if (i < steps) await sleep(1_000, ctx.signal);
  }
}

/** Asserts that every record lies within ROUTE_TOLERANCE_M of the polyline [route]. */
function expectOnRoute(records: readonly WireRecord[], route: readonly LatLon[], what: string): void {
  const off = records.filter((record) => {
    const p = coordsOf(record);
    return p === null || distanceToRouteMeters(p, route) > ROUTE_TOLERANCE_M;
  });
  assert.equal(
    off.length,
    0,
    `${what} must lie within ${ROUTE_TOLERANCE_M} m of the replayed route ${route.map(formatPoint).join(' -> ')}:\n${timeline(off)}`,
  );
}

function routeDurationMs(points: readonly LatLon[], speedMps: number): number {
  return (assertions.routeLengthMeters(points) / speedMps) * 1000;
}

function coordsOf(record: WireRecord): LatLon | null {
  return record.coords ? { lat: record.coords.latitude, lon: record.coords.longitude } : null;
}

function formatPoint(point: LatLon): string {
  return `${point.lat.toFixed(5)},${point.lon.toFixed(5)}`;
}

function distanceMeters(a: LatLon, b: LatLon): number {
  return assertions.haversineMeters(a, b);
}

function interpolate(from: LatLon, to: LatLon, fraction: number): LatLon {
  return { lat: from.lat + (to.lat - from.lat) * fraction, lon: from.lon + (to.lon - from.lon) * fraction };
}

/** Distance from [p] to the segment [a]-[b] (equirectangular projection; exact enough for a few kilometres). */
function distanceToSegmentMeters(p: LatLon, a: LatLon, b: LatLon): number {
  const metersPerDegree = (Math.PI / 180) * 6_371_008.8;
  const k = Math.cos((((a.lat + b.lat) / 2) * Math.PI) / 180);
  const bx = (b.lon - a.lon) * metersPerDegree * k;
  const by = (b.lat - a.lat) * metersPerDegree;
  const px = (p.lon - a.lon) * metersPerDegree * k;
  const py = (p.lat - a.lat) * metersPerDegree;
  const lengthSquared = bx * bx + by * by;
  const t = lengthSquared === 0 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by) / lengthSquared));
  return Math.hypot(px - t * bx, py - t * by);
}

function distanceToRouteMeters(p: LatLon, route: readonly LatLon[]): number {
  if (route.length === 1) return distanceMeters(p, route[0]!);
  let best = Number.POSITIVE_INFINITY;
  for (let i = 1; i < route.length; i += 1) best = Math.min(best, distanceToSegmentMeters(p, route[i - 1]!, route[i]!));
  return best;
}
