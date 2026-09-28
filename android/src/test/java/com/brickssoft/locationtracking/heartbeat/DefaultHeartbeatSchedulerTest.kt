package com.brickssoft.locationtracking.heartbeat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.HeartbeatConfig
import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.config.TrackingMode
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.heartbeat.HeartbeatHarness.Companion.IDLE_SPACING_MS
import com.brickssoft.locationtracking.heartbeat.HeartbeatHarness.Companion.MIN_MS
import com.brickssoft.locationtracking.http.HttpSyncer
import com.brickssoft.locationtracking.model.GeofenceHit
import com.brickssoft.locationtracking.model.HeartbeatStrategy
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.RecordJson
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeDeviceMonitor
import com.brickssoft.locationtracking.testing.FakeHttpSyncer
import com.brickssoft.locationtracking.testing.FakeProviderFactory
import com.brickssoft.locationtracking.testing.Fixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowPowerManager

/** End-to-end heartbeat scenarios: scheduler + real DefaultRecordSink + Robolectric AlarmManager. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultHeartbeatSchedulerTest {
    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun TestScope.harness(
        exact: Boolean = false,
        idle: Boolean = false,
        config: Config = Config(),
        runtime: RuntimeState = RuntimeState(enabled = true),
        alarmsOverride: ((HeartbeatAlarms) -> HeartbeatAlarms)? = null,
        providersOverride: ((FakeProviderFactory) -> ProviderFactory)? = null,
        factoryOverride: ((RecordFactory) -> RecordFactory)? = null,
        deviceOverride: ((FakeDeviceMonitor) -> DeviceMonitor)? = null,
    ) = HeartbeatHarness(
        backgroundScope,
        exact,
        idle,
        config,
        runtime,
        alarmsOverride = alarmsOverride,
        providersOverride = providersOverride,
        factoryOverride = factoryOverride,
        deviceOverride = deviceOverride,
    )

    private fun assertListenerWithBackupAt(h: HeartbeatHarness, dueElapsed: Long, backupElapsed: Long = dueElapsed) {
        assertEquals(2, h.scheduled().size)
        val listener = h.listenerAlarm()!!
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, listener.getType())
        assertEquals(dueElapsed, listener.triggerAtMs)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, listener.windowLengthMs)
        assertEquals("lt-heartbeat", listener.tag)
        val backup = h.intentAlarm()!!
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, backup.getType())
        assertEquals(backupElapsed, backup.triggerAtMs)
        assertTrue(backup.isAllowWhileIdle)
        assertEquals(HeartbeatTrigger.BACKUP_ALARM, h.triggerOf(backup))
    }

    // ---- scenario 1: no record for minInterval -> one heartbeat with the last known location

    @Test
    fun `no record for 180 s creates one heartbeat with the last known location and re-arms`() = runTest {
        val h = harness()
        h.scheduler.start()
        runCurrent()

        assertListenerWithBackupAt(h, h.t(MIN_MS))
        assertEquals(HeartbeatStrategy.LISTENER_WITH_BACKUP, h.scheduler.status().strategy)

        h.at(MIN_MS)
        h.fireListener()
        runCurrent()

        val heartbeats = h.heartbeats()
        assertEquals(1, heartbeats.size)
        val heartbeat = heartbeats.single()
        assertEquals(h.lastKnown, heartbeat.location)
        assertEquals(h.clock.now(), heartbeat.recordedAt)
        assertEquals(listOf(TrackingEvent.Heartbeat(heartbeat)), h.events.events)
        assertEquals(listOf(heartbeat), (h.syncer as FakeHttpSyncer).inserted)
        assertEquals(listOf("heartbeat"), h.device.checkReasons)
        assertEquals(heartbeat.recordedAt, h.configStore.runtime.value.lastHeartbeatAt)
        // Re-armed one interval after the heartbeat.
        assertListenerWithBackupAt(h, h.t(2 * MIN_MS))
    }

    @Test
    fun `runtime last location wins over the backend`() = runTest {
        val remembered = Fixtures.location(latitude = 1.5, longitude = 2.5)
        val h = harness(runtime = RuntimeState(enabled = true, lastLocation = remembered))
        h.scheduler.start()

        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)

        assertEquals(remembered, h.heartbeats().single().location)
        assertEquals(0, h.backend.lastLocationCalls)
    }

    @Test
    fun `heartbeat without any known location has null coords`() = runTest {
        val h = harness()
        h.backend.lastLocation = null
        h.scheduler.start()

        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)

        val heartbeat = h.heartbeats().single()
        assertNull(heartbeat.location)
        val json = RecordJson.toJson(heartbeat)
        assertTrue(json.isNull("coords"))
        assertTrue(json.isNull("timestamp"))
    }

    @Test
    fun `window starts from the persisted last record`() = runTest {
        val clock = FakeClock()
        val runtime = RuntimeState(
            enabled = true,
            lastRecordAt = clock.now() - 100_000L,
            lastRecordElapsed = clock.elapsedRealtime() - 100_000L,
            lastRecordBootCount = clock.bootCount(),
        )
        val h = HeartbeatHarness(backgroundScope, runtime = runtime, clock = clock)

        h.scheduler.start()

        assertListenerWithBackupAt(h, h.t(80_000L))
        assertEquals(h.t0Wall + 80_000L, h.scheduler.status().nextHeartbeatAt)
    }

    @Test
    fun `after a reboot the window is derived from the wall clock`() = runTest {
        val clock = FakeClock()
        val runtime = RuntimeState(
            enabled = true,
            lastRecordAt = clock.now() - 100_000L,
            lastRecordElapsed = 999_999_999L, // elapsed of the previous boot
            lastRecordBootCount = clock.bootCount() - 1,
        )
        val h = HeartbeatHarness(backgroundScope, runtime = runtime, clock = clock)

        h.scheduler.start()

        assertListenerWithBackupAt(h, h.t(80_000L))
    }

    @Test
    fun `starting a new session long after the last record does not fire at once`() = runTest {
        val clock = FakeClock()
        val runtime = RuntimeState(
            enabled = true,
            lastRecordAt = clock.now() - 5 * 3_600_000L, // tracking_stop of the previous session
            lastRecordElapsed = clock.elapsedRealtime() - 5 * 3_600_000L,
            lastRecordBootCount = clock.bootCount(),
            trackingStartedAt = clock.now(),
        )
        val h = HeartbeatHarness(backgroundScope, runtime = runtime, clock = clock)

        h.scheduler.start()

        assertListenerWithBackupAt(h, h.t(MIN_MS))
        h.scheduler.onAlarm(HeartbeatTrigger.BACKUP_ALARM)
        assertTrue(h.heartbeats().isEmpty())
    }

    // ---- scenario 2: a record inside the window restarts it

    @Test
    fun `a record at 100 s moves the heartbeat to 280 s and an early alarm does nothing`() = runTest {
        val h = harness()
        h.scheduler.start()
        assertListenerWithBackupAt(h, h.t(MIN_MS))

        h.at(100_000L)
        h.submit(RecordEvent.LOCATION)
        assertListenerWithBackupAt(h, h.t(280_000L))

        // The old 180 s alarm was already in flight: it must not create a heartbeat.
        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        h.scheduler.onAlarm(HeartbeatTrigger.BACKUP_ALARM)
        assertTrue(h.heartbeats().isEmpty())
        assertListenerWithBackupAt(h, h.t(280_000L))

        h.at(280_000L)
        h.fireListener()
        runCurrent()

        assertEquals(1, h.heartbeats().size)
        assertEquals(h.clock.now(), h.heartbeats().single().recordedAt)
        assertListenerWithBackupAt(h, h.t(280_000L + MIN_MS))
    }

    @Test
    fun `every record event restarts the window, including audit records`() = runTest {
        val h = harness()
        h.scheduler.start()

        h.at(60_000L)
        h.submit(RecordEvent.TRACKING_START, location = null)
        assertListenerWithBackupAt(h, h.t(60_000L + MIN_MS))

        h.at(120_000L)
        h.submit(RecordEvent.MOTIONCHANGE)
        assertListenerWithBackupAt(h, h.t(120_000L + MIN_MS))
    }

    @Test
    fun `an unchanged window is not re-armed`() = runTest {
        var counting: CountingAlarms? = null
        val h = harness(alarmsOverride = { real -> CountingAlarms(real).also { counting = it } })
        h.scheduler.start()
        val afterStart = counting!!.sets

        h.scheduler.start()
        h.scheduler.onRecordRecorded(Fixtures.record()) // runtime unchanged -> same window
        runCurrent() // the config observer's first emission

        assertEquals(afterStart, counting!!.sets)
    }

    // ---- scenario 3: upload failure keeps the heartbeat queued with recorded_at, sent_at added on upload

    @Test
    fun `failed upload keeps the heartbeat queued and the wire keeps recorded_at`() = runTest {
        val h = harness(config = Config(http = HttpConfig(url = "https://example.test/locations")))
        h.scheduler.start()

        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)

        // FakeHttpSyncer never deletes: the upload "failed" and the record stays queued.
        val heartbeat = h.heartbeats().single()
        assertEquals(listOf(heartbeat), (h.syncer as FakeHttpSyncer).inserted)
        assertEquals(1, h.scheduler.status().pendingHeartbeats)

        val sentAt = heartbeat.recordedAt + 42 * 60_000L
        val json = RecordJson.toJson(heartbeat, sentAt = sentAt)
        assertEquals("heartbeat", json.getString("event"))
        assertEquals(Iso8601.format(heartbeat.recordedAt), json.getString("recorded_at"))
        assertEquals(Iso8601.format(sentAt), json.getString("sent_at"))
        assertEquals(h.lastKnown.latitude, json.getJSONObject("coords").getDouble("latitude"), 0.0)
        assertEquals(h.lastKnown.longitude, json.getJSONObject("coords").getDouble("longitude"), 0.0)
        assertEquals(Iso8601.format(h.lastKnown.time), json.getString("timestamp"))

        // The next window still produces its own heartbeat; both stay queued.
        h.at(2 * MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        assertEquals(2, h.scheduler.status().pendingHeartbeats)
    }

    // ---- scenario 4: not exempt + idle -> idle paced, backups at least 9 min apart

    @Test
    fun `idle and not exempt paces the backup at least 9 min after the previous backup fire`() = runTest {
        val h = harness(exact = false, idle = true)
        h.scheduler.start()
        assertListenerWithBackupAt(h, h.t(MIN_MS))
        assertEquals(HeartbeatStrategy.IDLE_PACED, h.scheduler.status().strategy)

        // Deep idle defers the listener; the allow-while-idle backup fires.
        h.at(MIN_MS)
        h.fireIntentAlarm()
        assertEquals(1, h.heartbeats().size)

        val lastBackupFire = h.t(MIN_MS)
        assertListenerWithBackupAt(h, dueElapsed = h.t(2 * MIN_MS), backupElapsed = lastBackupFire + IDLE_SPACING_MS)
        val status = h.scheduler.status()
        assertEquals(HeartbeatStrategy.IDLE_PACED, status.strategy)
        assertTrue(status.isDeviceIdleMode)
        assertFalse(status.canScheduleExactAlarms)
        assertEquals(h.clock.now() + IDLE_SPACING_MS, status.nextHeartbeatAt)

        // A record keeps the pacing: the backup never moves closer than 9 min to the last backup fire.
        h.at(MIN_MS + 30_000L)
        h.submit()
        assertTrue(h.intentAlarm()!!.triggerAtMs >= lastBackupFire + IDLE_SPACING_MS)
        assertEquals(h.t(MIN_MS + 30_000L + MIN_MS), h.listenerAlarm()!!.triggerAtMs)
    }

    @Test
    fun `leaving idle drops the pacing and entering idle re-arms`() = runTest {
        val h = harness(idle = true)
        h.scheduler.start()
        h.at(MIN_MS)
        h.fireIntentAlarm()
        assertEquals(h.t(MIN_MS) + IDLE_SPACING_MS, h.intentAlarm()!!.triggerAtMs)

        h.device.deviceIdleMode = false
        h.app.sendBroadcast(Intent(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()

        assertListenerWithBackupAt(h, h.t(2 * MIN_MS))
        assertEquals(HeartbeatStrategy.LISTENER_WITH_BACKUP, h.scheduler.status().strategy)

        h.device.deviceIdleMode = true
        h.app.sendBroadcast(Intent(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(HeartbeatStrategy.IDLE_PACED, h.scheduler.status().strategy)
        assertEquals(h.t(MIN_MS) + IDLE_SPACING_MS, h.intentAlarm()!!.triggerAtMs)
    }

    @Test
    fun `the last backup fire survives a process restart`() = runTest {
        val first = harness(idle = true)
        first.scheduler.start()
        first.at(MIN_MS)
        first.fireIntentAlarm()

        // A new process: same prefs, same boot, runtime persisted by the config store.
        first.killProcess()
        val second = HeartbeatHarness(
            backgroundScope,
            idle = true,
            runtime = first.configStore.runtime.value,
            clock = first.clock,
        )
        second.scheduler.start()

        assertEquals(first.t(MIN_MS) + IDLE_SPACING_MS, second.intentAlarm()!!.triggerAtMs)
    }

    @Test
    fun `a backup fire from a previous boot does not pace`() = runTest {
        val first = harness(idle = true)
        first.scheduler.start()
        first.at(MIN_MS)
        first.fireIntentAlarm()
        first.reboot(elapsedAfterBoot = first.clock.elapsedRealtime() + 60_000L)

        val second = HeartbeatHarness(
            backgroundScope,
            idle = true,
            runtime = first.configStore.runtime.value,
            clock = first.clock,
        )
        second.scheduler.start()

        val backup = second.intentAlarm()!!
        assertEquals(second.listenerAlarm()!!.triggerAtMs, backup.triggerAtMs)
    }

    // ---- scenario 5: exempt -> one exact allow-while-idle alarm; SecurityException -> fallback

    @Test
    fun `exempt uses a single exact allow-while-idle alarm`() = runTest {
        val h = harness(exact = true, idle = true)
        h.scheduler.start()

        val alarm = h.scheduled().single()
        assertNull(h.listenerOf(alarm))
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.getType())
        assertEquals(h.t(MIN_MS), alarm.triggerAtMs)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
        assertTrue(alarm.isAllowWhileIdle)
        assertEquals(HeartbeatTrigger.EXACT_ALARM, h.triggerOf(alarm))
        val status = h.scheduler.status()
        assertEquals(HeartbeatStrategy.EXACT, status.strategy)
        assertTrue(status.canScheduleExactAlarms)

        h.at(MIN_MS)
        h.fireIntentAlarm(alarm)

        assertEquals(1, h.heartbeats().size)
        val next = h.scheduled().single()
        assertEquals(h.t(2 * MIN_MS), next.triggerAtMs)
        assertEquals(HeartbeatTrigger.EXACT_ALARM, h.triggerOf(next))
    }

    @Test
    fun `heartbeat PendingIntent is explicit, immutable and uses the heartbeat request code`() = runTest {
        val h = harness(exact = true)
        h.scheduler.start()

        val pending: PendingIntent = h.operationOf(h.scheduled().single())!!
        val shadow = shadowOf(pending)
        assertTrue(shadow.isBroadcast)
        assertTrue(shadow.isImmutable)
        assertEquals(Constants.RC_HEARTBEAT, shadow.requestCode)
        assertEquals(Constants.ACTION_HEARTBEAT, shadow.savedIntent.action)
        assertEquals(HeartbeatAlarmReceiver::class.java.name, shadow.savedIntent.component?.className)
    }

    @Test
    fun `SecurityException from the exact alarm falls back to listener with backup`() = runTest {
        val h = harness(exact = true, alarmsOverride = { real -> RefusingExactAlarms(real) })
        h.scheduler.start()

        assertListenerWithBackupAt(h, h.t(MIN_MS))
        assertEquals(HeartbeatStrategy.LISTENER_WITH_BACKUP, h.scheduler.status().strategy)

        h.at(MIN_MS)
        h.fireListener()
        runCurrent()
        assertEquals(1, h.heartbeats().size)
        assertListenerWithBackupAt(h, h.t(2 * MIN_MS))
    }

    @Test
    fun `SecurityException while idle falls back to idle pacing`() = runTest {
        val h = harness(exact = true, idle = true, alarmsOverride = { real -> RefusingExactAlarms(real) })
        h.scheduler.start()

        assertEquals(HeartbeatStrategy.IDLE_PACED, h.scheduler.status().strategy)
        assertEquals(2, h.scheduled().size)
    }

    @Test
    fun `exemption granted later switches to exact on the next re-arm`() = runTest {
        val h = harness(exact = false)
        h.scheduler.start()
        assertEquals(2, h.scheduled().size)

        h.device.exactAlarms = true
        h.at(10_000L)
        h.submit()

        val alarm = h.scheduled().single()
        assertEquals(HeartbeatTrigger.EXACT_ALARM, h.triggerOf(alarm))
        assertEquals(h.t(10_000L + MIN_MS), alarm.triggerAtMs)
    }

    // ---- scenario 6: disabled -> nothing; stop cancels all; listener + backup -> one heartbeat

    @Test
    fun `heartbeat disabled arms nothing`() = runTest {
        val h = harness(config = Config(heartbeat = HeartbeatConfig(enabled = false)))
        h.scheduler.start()
        runCurrent()

        assertTrue(h.scheduled().isEmpty())
        val status = h.scheduler.status()
        assertEquals(HeartbeatStrategy.DISABLED, status.strategy)
        assertFalse(status.enabled)
        assertNull(status.nextHeartbeatAt)

        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.BACKUP_ALARM)
        assertTrue(h.heartbeats().isEmpty())
    }

    @Test
    fun `tracking disabled arms nothing`() = runTest {
        val h = harness(runtime = RuntimeState(enabled = false))
        h.scheduler.start()
        runCurrent()

        assertTrue(h.scheduled().isEmpty())
        assertEquals(HeartbeatStrategy.DISABLED, h.scheduler.status().strategy)
        assertTrue(h.scheduler.status().enabled)

        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        assertTrue(h.heartbeats().isEmpty())
    }

    @Test
    fun `geofences mode keeps the heartbeat`() = runTest {
        val h = harness(runtime = RuntimeState(enabled = true, trackingMode = TrackingMode.GEOFENCES))
        h.scheduler.start()

        assertListenerWithBackupAt(h, h.t(MIN_MS))
    }

    @Test
    fun `config changes are observed while started`() = runTest {
        val h = harness()
        h.scheduler.start()
        runCurrent()
        assertEquals(2, h.scheduled().size)

        h.configStore.update { it.copy(heartbeat = it.heartbeat.copy(enabled = false)) }
        runCurrent()
        assertTrue(h.scheduled().isEmpty())

        h.configStore.update { it.copy(heartbeat = HeartbeatConfig(enabled = true, minInterval = 240, maxInterval = 300)) }
        runCurrent()
        assertListenerWithBackupAt(h, h.t(240_000L))

        h.configStore.updateRuntime { it.copy(enabled = false) }
        runCurrent()
        assertTrue(h.scheduled().isEmpty())

        h.configStore.updateRuntime { it.copy(enabled = true) }
        runCurrent()
        assertEquals(2, h.scheduled().size)
    }

    @Test
    fun `stop cancels every alarm, unregisters the idle receiver and stops observing`() = runTest {
        val h = harness()
        h.scheduler.start()
        runCurrent()
        assertEquals(2, h.scheduled().size)
        assertTrue(idleReceiverRegistered(h))

        h.scheduler.stop()
        runCurrent()

        assertTrue(h.scheduled().isEmpty())
        assertFalse(idleReceiverRegistered(h))
        assertEquals(HeartbeatStrategy.DISABLED, h.scheduler.status().strategy)
        assertNull(h.scheduler.status().nextHeartbeatAt)

        // Neither config changes, records nor stray alarms re-arm or create heartbeats after stop.
        h.configStore.update { it.copy(heartbeat = HeartbeatConfig(minInterval = 200)) }
        runCurrent()
        h.submit()
        h.at(10 * MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.BACKUP_ALARM)
        assertTrue(h.scheduled().isEmpty())
        assertTrue(h.heartbeats().isEmpty())

        // start() again resumes.
        h.scheduler.start()
        assertEquals(2, h.scheduled().size)
    }

    @Test
    fun `stop cancels an exact alarm armed by a previous process`() = runTest {
        val first = harness(exact = true)
        first.scheduler.start()
        assertEquals(1, first.scheduled().size)
        first.killProcess()

        val second = HeartbeatHarness(backgroundScope, exact = true, clock = first.clock)
        second.scheduler.stop()

        assertTrue(first.scheduled().isEmpty())
    }

    @Test
    fun `listener and backup firing for the same window create one heartbeat`() = runTest {
        val h = harness()
        h.scheduler.start()
        val listener = h.listenerAlarm()!!
        val backup = h.intentAlarm()!!

        h.at(MIN_MS)
        h.fireListener(listener)
        backupFiresConcurrently(h, backup)
        runCurrent()

        assertEquals(1, h.heartbeats().size)
        assertEquals(1, h.events.ofType<TrackingEvent.Heartbeat>().size)
        assertListenerWithBackupAt(h, h.t(2 * MIN_MS))
    }

    @Test
    fun `backup first then listener also create one heartbeat`() = runTest {
        val h = harness()
        h.scheduler.start()

        h.at(MIN_MS + 2_000L)
        h.scheduler.onAlarm(HeartbeatTrigger.BACKUP_ALARM)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)

        assertEquals(1, h.heartbeats().size)
    }

    @Test
    fun `a record created during the provider check supersedes the heartbeat`() = runTest {
        var duringCheck: (suspend () -> Unit)? = null
        val h = harness(deviceOverride = { real -> HookedDeviceMonitor(real) { duringCheck?.invoke() } })
        duringCheck = { h.submit(RecordEvent.PROVIDERCHANGE, location = null) }
        h.scheduler.start()

        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)

        assertTrue(h.heartbeats().isEmpty())
        assertEquals(listOf(RecordEvent.PROVIDERCHANGE), h.store.all.map { it.event })
        assertListenerWithBackupAt(h, h.t(2 * MIN_MS))
    }

    @Test
    fun `a record submitted while the heartbeat record is built supersedes the heartbeat`() = runTest {
        var duringCreate: (() -> Unit)? = null
        val h = harness(factoryOverride = { real -> HookedHeartbeatFactory(real) { duringCreate?.invoke() } })
        // Another thread's record: the sink updates runtime, then restarts the window.
        duringCreate = {
            h.configStore.updateRuntime {
                it.copy(
                    lastRecordAt = h.clock.now(),
                    lastRecordElapsed = h.clock.elapsedRealtime(),
                    lastRecordBootCount = h.clock.bootCount(),
                )
            }
            h.scheduler.onRecordRecorded(Fixtures.record())
        }
        h.scheduler.start()

        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)

        assertTrue(h.heartbeats().isEmpty())
        assertTrue(h.events.ofType<TrackingEvent.Heartbeat>().isEmpty())
        assertListenerWithBackupAt(h, h.t(2 * MIN_MS))
    }

    @Test
    fun `stop during the provider check suppresses the heartbeat`() = runTest {
        var duringCheck: (suspend () -> Unit)? = null
        val h = harness(deviceOverride = { real -> HookedDeviceMonitor(real) { duringCheck?.invoke() } })
        duringCheck = { h.scheduler.stop() }
        h.scheduler.start()

        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)

        assertTrue(h.heartbeats().isEmpty())
        assertTrue(h.scheduled().isEmpty())
    }

    @Test
    fun `a record re-arms with two set calls and no cancel`() = runTest {
        val counting = arrayOfNulls<CountingAlarms>(1)
        val h = harness(alarmsOverride = { real -> CountingAlarms(real).also { counting[0] = it } })
        h.scheduler.start()
        val alarms = counting[0]!!
        val setsBefore = alarms.sets
        val cancelsBefore = alarms.cancels

        h.at(30_000L)
        h.submit()

        assertEquals(setsBefore + 2, alarms.sets)
        assertEquals(cancelsBefore, alarms.cancels)
        assertListenerWithBackupAt(h, h.t(30_000L + MIN_MS))
    }

    @Test
    fun `losing the exemption replaces the exact alarm with listener and backup`() = runTest {
        val h = harness(exact = true)
        h.scheduler.start()
        assertEquals(HeartbeatTrigger.EXACT_ALARM, h.triggerOf(h.scheduled().single()))

        h.device.exactAlarms = false
        h.at(10_000L)
        h.submit()

        assertListenerWithBackupAt(h, h.t(10_000L + MIN_MS))
    }

    @Test
    fun `status after a reboot ignores the schedule of the previous boot`() = runTest {
        val first = harness(exact = true)
        first.scheduler.start()
        first.at(10 * 60_000L)
        first.reboot()

        val second = HeartbeatHarness(backgroundScope, exact = true, clock = first.clock)
        val status = second.scheduler.status()

        // Not the stale "due now" of the previous boot, but what start() would arm.
        assertEquals(HeartbeatStrategy.EXACT, status.strategy)
        assertEquals(first.clock.now() + MIN_MS, status.nextHeartbeatAt)
    }

    // ---- other behavior

    @Test
    fun `an alarm in a never-started process creates the heartbeat and re-arms`() = runTest {
        val clock = FakeClock()
        val runtime = RuntimeState(
            enabled = true,
            lastRecordAt = clock.now() - 200_000L,
            lastRecordElapsed = clock.elapsedRealtime() - 200_000L,
            lastRecordBootCount = clock.bootCount(),
        )
        val h = HeartbeatHarness(backgroundScope, runtime = runtime, clock = clock)

        h.scheduler.onAlarm(HeartbeatTrigger.BACKUP_ALARM)

        assertEquals(1, h.heartbeats().size)
        assertListenerWithBackupAt(h, h.t(MIN_MS))
        // Later records keep the window moving.
        h.at(60_000L)
        h.submit()
        assertListenerWithBackupAt(h, h.t(60_000L + MIN_MS))
    }

    @Test
    fun `records in a never-started process arm nothing`() = runTest {
        val h = harness()

        h.submit()

        assertTrue(h.scheduled().isEmpty())
    }

    @Test
    fun `an alarm while tracking is off cancels leftovers`() = runTest {
        val first = harness(exact = true)
        first.scheduler.start()
        assertEquals(1, first.scheduled().size)
        first.killProcess()

        val second = HeartbeatHarness(
            backgroundScope,
            exact = true,
            runtime = RuntimeState(enabled = false),
            clock = first.clock,
        )
        second.scheduler.onAlarm(HeartbeatTrigger.EXACT_ALARM)

        assertTrue(first.scheduled().isEmpty())
        assertTrue(second.heartbeats().isEmpty())
    }

    @Test
    fun `a failing record factory does not loop and retries one interval later`() = runTest {
        var failing: FailingRecordFactory? = null
        val h = harness(factoryOverride = { real -> FailingRecordFactory(real).also { failing = it } })
        h.scheduler.start()

        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)

        assertTrue(h.heartbeats().isEmpty())
        assertListenerWithBackupAt(h, h.t(2 * MIN_MS))
        h.scheduler.onAlarm(HeartbeatTrigger.BACKUP_ALARM) // same instant: not due again
        assertTrue(h.heartbeats().isEmpty())

        failing!!.fail = false
        h.at(2 * MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        assertEquals(1, h.heartbeats().size)
    }

    @Test
    fun `concurrent alarms are serialized and create one heartbeat`() = runTest {
        // getLastLocation suspends for 1 s, so the second alarm arrives while the first is mid-heartbeat.
        val h = harness(providersOverride = { real -> SlowLastLocationProviders(real) })
        h.scheduler.start()

        h.at(MIN_MS)
        backgroundScope.launch { h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM) }
        backgroundScope.launch { h.scheduler.onAlarm(HeartbeatTrigger.BACKUP_ALARM) }
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(1, h.heartbeats().size)
        assertListenerWithBackupAt(h, h.t(2 * MIN_MS))
    }

    @Test
    fun `onAlarm holds a partial wake lock and releases it`() = runTest {
        ShadowPowerManager.clearWakeLocks()
        var heldDuringSubmit = false
        val syncer = object : HttpSyncer by FakeHttpSyncer() {
            override fun onRecordInserted(record: Record) {
                heldDuringSubmit = ShadowPowerManager.getLatestWakeLock()?.isHeld == true
            }
        }
        val h = HeartbeatHarness(backgroundScope, syncer = syncer)
        h.scheduler.start()

        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)

        assertTrue(heldDuringSubmit)
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(wakeLock)
        assertFalse(wakeLock.isHeld)
        assertEquals("LocationTracking:heartbeat", shadowOf(wakeLock).tag)
    }

    @Test
    fun `status mirrors device flags and counts only heartbeats`() = runTest {
        val h = harness()
        h.device.ignoringBatteryOptimizations = true
        h.device.powerSaveMode = true
        h.scheduler.start()
        h.submit()
        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)

        val status = h.scheduler.status()

        assertTrue(status.enabled)
        assertEquals(180, status.minInterval)
        assertEquals(300, status.maxInterval)
        assertEquals(h.clock.now(), status.lastRecordAt)
        assertEquals(h.clock.now(), status.lastHeartbeatAt)
        assertEquals(h.clock.now() + MIN_MS, status.nextHeartbeatAt)
        assertEquals(HeartbeatStrategy.LISTENER_WITH_BACKUP, status.strategy)
        assertFalse(status.canScheduleExactAlarms)
        assertTrue(status.isIgnoringBatteryOptimizations)
        assertFalse(status.isDeviceIdleMode)
        assertTrue(status.isPowerSaveMode)
        assertEquals(1, status.pendingHeartbeats)
    }

    @Test
    fun `status before start reports what a previous process armed`() = runTest {
        val first = harness(exact = true)
        first.scheduler.start()
        first.killProcess()

        val second = HeartbeatHarness(backgroundScope, exact = true, clock = first.clock)
        val status = second.scheduler.status()

        assertEquals(HeartbeatStrategy.EXACT, status.strategy)
        assertEquals(first.t0Wall + MIN_MS, status.nextHeartbeatAt)
    }

    @Test
    fun `only hb_ keys are written to the shared prefs`() = runTest {
        val h = harness(idle = true)
        h.scheduler.start()
        h.at(MIN_MS)
        h.fireIntentAlarm()

        val prefs = h.app.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        assertTrue(prefs.all.isNotEmpty())
        assertTrue(prefs.all.keys.all { it.startsWith("hb_") })
    }

    private fun idleReceiverRegistered(h: HeartbeatHarness): Boolean =
        shadowOf(h.app).registeredReceivers.any { it.intentFilter.hasAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) }

    private fun TestScope.backupFiresConcurrently(h: HeartbeatHarness, backup: ShadowAlarmManager.ScheduledAlarm) {
        val trigger = h.triggerOf(backup)
        backgroundScope.launch { h.scheduler.onAlarm(trigger) }
    }

    /** Device monitor that runs [hook] inside `checkProviderState`. */
    private class HookedDeviceMonitor(
        private val real: FakeDeviceMonitor,
        private val hook: suspend () -> Unit,
    ) : DeviceMonitor by real {
        override suspend fun checkProviderState(reason: String) {
            real.checkProviderState(reason)
            hook()
        }
    }

    /** Record factory that throws while [fail] is set. */
    private class FailingRecordFactory(private val real: RecordFactory) : RecordFactory by real {
        @Volatile
        var fail = true

        override fun create(
            event: RecordEvent,
            location: TrackedLocation?,
            extras: String?,
            geofence: GeofenceHit?,
            provider: ProviderState?,
            reason: String?,
        ): Record {
            check(!fail) { "factory failure" }
            return real.create(event, location, extras, geofence, provider, reason)
        }
    }

    /** Record factory that runs [hook] before it builds a heartbeat record. */
    private class HookedHeartbeatFactory(private val real: RecordFactory, private val hook: () -> Unit) : RecordFactory by real {
        override fun create(
            event: RecordEvent,
            location: TrackedLocation?,
            extras: String?,
            geofence: GeofenceHit?,
            provider: ProviderState?,
            reason: String?,
        ): Record {
            if (event == RecordEvent.HEARTBEAT) hook()
            return real.create(event, location, extras, geofence, provider, reason)
        }
    }

    /** Providers whose last-location lookup takes 1 s. */
    private class SlowLastLocationProviders(private val real: FakeProviderFactory) : ProviderFactory by real {
        private val slow = object : LocationBackend by real.locationBackend {
            override suspend fun getLastLocation(): TrackedLocation? {
                delay(1_000L)
                return real.locationBackend.getLastLocation()
            }
        }

        override fun location(): LocationBackend = slow
    }
}
