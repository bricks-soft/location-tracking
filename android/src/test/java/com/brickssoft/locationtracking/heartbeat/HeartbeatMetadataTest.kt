package com.brickssoft.locationtracking.heartbeat

import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.HeartbeatConfig
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.heartbeat.HeartbeatHarness.Companion.IDLE_SPACING_MS
import com.brickssoft.locationtracking.heartbeat.HeartbeatHarness.Companion.MIN_MS
import com.brickssoft.locationtracking.model.EventJson
import com.brickssoft.locationtracking.model.HeartbeatMeta
import com.brickssoft.locationtracking.model.HeartbeatStatus
import com.brickssoft.locationtracking.model.HeartbeatStrategy
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.RecordJson
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round 2 §5: every heartbeat record carries [HeartbeatMeta] that agrees with `getHeartbeatStatus()` at the same
 * moment (scenario P-H10), and the owner's timestamp rule: `recorded_at` is the creation time, `timestamp` the time the
 * last known fix was acquired (P-H01, F-05).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HeartbeatMetadataTest {
    @After
    fun tearDown() {
        Logger.sink = null
    }

    // ---- metadata per strategy

    @Test
    fun `battery-exempt - strategy exact, next_at one minInterval after recorded_at`() = runTest {
        val h = HeartbeatHarness(backgroundScope, exact = true)
        h.device.ignoringBatteryOptimizations = true
        h.scheduler.start()

        h.at(MIN_MS)
        h.fireIntentAlarm()

        val heartbeat = h.heartbeats().single()
        val expected = HeartbeatMeta(
            strategy = HeartbeatStrategy.EXACT,
            minInterval = 180,
            maxInterval = 300,
            nextAt = heartbeat.recordedAt + MIN_MS,
            batteryExempt = true,
            deviceIdle = false,
        )
        assertEquals(expected, heartbeat.heartbeat)
        assertAgreesWithStatus(heartbeat, h.scheduler.status())
        // next_at is what AlarmManager holds.
        assertEquals(h.t(2 * MIN_MS), h.scheduled().single().triggerAtMs)
    }

    @Test
    fun `battery-exempt in deep idle - strategy exact with device_idle true`() = runTest {
        val h = HeartbeatHarness(backgroundScope, exact = true, idle = true)
        h.device.ignoringBatteryOptimizations = true
        h.scheduler.start()

        h.at(MIN_MS)
        h.fireIntentAlarm()

        val heartbeat = h.heartbeats().single()
        val meta = heartbeat.heartbeat!!
        assertEquals(HeartbeatStrategy.EXACT, meta.strategy)
        assertTrue(meta.batteryExempt)
        assertTrue(meta.deviceIdle)
        assertEquals(heartbeat.recordedAt + MIN_MS, meta.nextAt)
        assertAgreesWithStatus(heartbeat, h.scheduler.status())
    }

    @Test
    fun `not exempt - strategy listener_with_backup, next_at one minInterval after recorded_at`() = runTest {
        val h = HeartbeatHarness(backgroundScope)
        h.scheduler.start()

        h.at(MIN_MS)
        h.fireListener()
        runCurrent()

        val heartbeat = h.heartbeats().single()
        val expected = HeartbeatMeta(
            strategy = HeartbeatStrategy.LISTENER_WITH_BACKUP,
            minInterval = 180,
            maxInterval = 300,
            nextAt = heartbeat.recordedAt + MIN_MS,
            batteryExempt = false,
            deviceIdle = false,
        )
        assertEquals(expected, heartbeat.heartbeat)
        assertAgreesWithStatus(heartbeat, h.scheduler.status())
        assertEquals(h.t(2 * MIN_MS), h.listenerAlarm()!!.triggerAtMs)
        assertEquals(h.t(2 * MIN_MS), h.intentAlarm()!!.triggerAtMs)
    }

    @Test
    fun `idle-paced - next_at is the backup time, 9 min after the previous backup fire`() = runTest {
        val h = HeartbeatHarness(backgroundScope, idle = true)
        h.scheduler.start()

        // Deep idle defers the listener; the allow-while-idle backup fires and creates the heartbeat.
        h.at(MIN_MS)
        h.fireIntentAlarm()

        val first = h.heartbeats().single()
        val expected = HeartbeatMeta(
            strategy = HeartbeatStrategy.IDLE_PACED,
            minInterval = 180,
            maxInterval = 300,
            // max(due = recorded_at + 180 s, last backup fire (now) + 9 min)
            nextAt = first.recordedAt + IDLE_SPACING_MS,
            batteryExempt = false,
            deviceIdle = true,
        )
        assertEquals(expected, first.heartbeat)
        assertAgreesWithStatus(first, h.scheduler.status())
        assertEquals(h.t(MIN_MS) + IDLE_SPACING_MS, h.intentAlarm()!!.triggerAtMs)

        // The paced backup creates the next heartbeat at next_at, and paces the one after it the same way.
        h.at(MIN_MS + IDLE_SPACING_MS)
        h.fireIntentAlarm()
        val second = h.heartbeats().last()
        assertEquals(first.heartbeat!!.nextAt, second.recordedAt)
        assertEquals(second.recordedAt + IDLE_SPACING_MS, second.heartbeat!!.nextAt)
        assertAgreesWithStatus(second, h.scheduler.status())
    }

    @Test
    fun `idle-paced without a recent backup fire - next_at is one minInterval after recorded_at`() = runTest {
        val h = HeartbeatHarness(backgroundScope, idle = true)
        h.scheduler.start()

        // The listener fires (the device is in a maintenance window); no backup alarm has fired in this boot.
        h.at(MIN_MS)
        h.fireListener()
        runCurrent()

        val heartbeat = h.heartbeats().single()
        val meta = heartbeat.heartbeat!!
        assertEquals(HeartbeatStrategy.IDLE_PACED, meta.strategy)
        assertEquals(heartbeat.recordedAt + MIN_MS, meta.nextAt)
        assertTrue(meta.deviceIdle)
        assertAgreesWithStatus(heartbeat, h.scheduler.status())
    }

    @Test
    fun `a refused exact alarm - the metadata reports the fallback that was armed`() = runTest {
        val h = HeartbeatHarness(backgroundScope, exact = true, alarmsOverride = { real -> RefusingExactAlarms(real) })
        h.device.ignoringBatteryOptimizations = true
        h.scheduler.start()

        h.at(MIN_MS)
        h.fireListener()
        runCurrent()

        val heartbeat = h.heartbeats().single()
        val meta = heartbeat.heartbeat!!
        assertEquals(HeartbeatStrategy.LISTENER_WITH_BACKUP, meta.strategy)
        assertEquals(heartbeat.recordedAt + MIN_MS, meta.nextAt)
        assertTrue(meta.batteryExempt)
        assertAgreesWithStatus(heartbeat, h.scheduler.status())
    }

    @Test
    fun `min and max interval are the config values when the heartbeat is created`() = runTest {
        val config = Config(heartbeat = HeartbeatConfig(minInterval = 240, maxInterval = 420))
        val h = HeartbeatHarness(backgroundScope, config = config)
        h.scheduler.start()

        h.at(240_000L)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        val first = h.heartbeats().single()
        assertEquals(240, first.heartbeat!!.minInterval)
        assertEquals(420, first.heartbeat!!.maxInterval)
        assertEquals(first.recordedAt + 240_000L, first.heartbeat!!.nextAt)

        h.configStore.update { it.copy(heartbeat = HeartbeatConfig(minInterval = 120, maxInterval = 200)) }
        runCurrent()
        h.at(240_000L + 120_000L)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        val second = h.heartbeats().last()
        assertEquals(120, second.heartbeat!!.minInterval)
        assertEquals(200, second.heartbeat!!.maxInterval)
        assertEquals(second.recordedAt + 120_000L, second.heartbeat!!.nextAt)
        assertAgreesWithStatus(second, h.scheduler.status())
    }

    @Test
    fun `every heartbeat carries metadata and other records carry none`() = runTest {
        val h = HeartbeatHarness(backgroundScope)
        h.scheduler.start()

        for (i in 1..5) {
            h.at(i * MIN_MS)
            h.scheduler.onAlarm(if (i % 2 == 0) HeartbeatTrigger.BACKUP_ALARM else HeartbeatTrigger.LISTENER_ALARM)
            assertAgreesWithStatus(h.heartbeats().last(), h.scheduler.status())
        }
        h.at(5 * MIN_MS + 10_000L)
        h.submit(RecordEvent.LOCATION)

        assertEquals(5, h.heartbeats().size)
        assertTrue(h.heartbeats().all { it.heartbeat != null })
        assertNull(h.store.all.single { it.event == RecordEvent.LOCATION }.heartbeat)
        // The Heartbeat event carries the same record, metadata included.
        assertEquals(h.heartbeats(), h.events.ofType<TrackingEvent.Heartbeat>().map { it.record })
    }

    @Test
    fun `the metadata is on the wire and in the heartbeat event payload`() = runTest {
        val h = HeartbeatHarness(backgroundScope)
        h.scheduler.start()
        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        val heartbeat = h.heartbeats().single()

        val expected = JSONObject()
            .put("strategy", "listener_with_backup")
            .put("min_interval", 180)
            .put("max_interval", 300)
            .put("next_at", Iso8601.format(heartbeat.recordedAt + MIN_MS))
            .put("battery_exempt", false)
            .put("device_idle", false)
        assertJsonEquals(expected, RecordJson.toJson(heartbeat, sentAt = h.clock.now()).getJSONObject("heartbeat"))
        val payload = EventJson.payload(h.events.ofType<TrackingEvent.Heartbeat>().single())
        assertJsonEquals(expected, payload.getJSONObject("location").getJSONObject("heartbeat"))
        // A round trip through the queue's JSON keeps it.
        assertEquals(heartbeat.heartbeat, RecordJson.fromJson(RecordJson.toJson(heartbeat)).heartbeat)
    }

    @Test
    fun `status in a new process reports the real due time, not an alarm left 20 s early`() = runTest {
        val first = HeartbeatHarness(backgroundScope)
        first.scheduler.start()
        first.at(20_000L)
        first.submit()
        // The record moved the due time by 20 s: the alarms stay at 180 s, the heartbeat is due at 200 s.
        assertEquals(first.t(MIN_MS), first.listenerAlarm()!!.triggerAtMs)
        assertEquals(first.t0Wall + 200_000L, first.scheduler.status().nextHeartbeatAt)
        first.killProcess()

        // A new process in the same boot, before start(): it reports the same time as the old process did.
        val second = HeartbeatHarness(backgroundScope, runtime = first.configStore.runtime.value, clock = first.clock)
        val status = second.scheduler.status()

        assertEquals(HeartbeatStrategy.LISTENER_WITH_BACKUP, status.strategy)
        assertEquals(first.t0Wall + 200_000L, status.nextHeartbeatAt)
    }

    // ---- timestamps (owner's rule)

    @Test
    fun `stationary for hours - recorded_at is the creation time, timestamp stays the time of the last fix`() = runTest {
        val clock = FakeClock()
        // The phone stopped moving 2 h before this process started; the engine kept the fix it stopped at.
        val fixTime = clock.now() - 2 * 3_600_000L
        val parked = Fixtures.location(latitude = 24.7, longitude = 46.6, time = fixTime)
        val runtime = RuntimeState(enabled = true, isMoving = false, lastLocation = parked)
        val h = HeartbeatHarness(backgroundScope, runtime = runtime, clock = clock)
        h.scheduler.start()

        // One hour of heartbeats while nothing else is recorded.
        for (i in 1..20) {
            h.at(i * MIN_MS)
            h.fireListener()
            runCurrent()
        }

        val heartbeats = h.heartbeats()
        assertEquals(20, heartbeats.size)
        heartbeats.forEachIndexed { index, heartbeat ->
            val createdAt = h.t0Wall + (index + 1) * MIN_MS
            assertEquals(createdAt, heartbeat.recordedAt)
            assertEquals(parked, heartbeat.location)
            assertEquals(fixTime, heartbeat.location!!.time)
            assertFalse(heartbeat.isMoving)
            val wire = RecordJson.toJson(heartbeat, sentAt = createdAt)
            assertEquals(Iso8601.format(createdAt), wire.getString("recorded_at"))
            assertEquals(Iso8601.format(fixTime), wire.getString("timestamp"))
            assertEquals(parked.latitude, wire.getJSONObject("coords").getDouble("latitude"), 0.0)
        }
        // The last heartbeat was created 3 h after the fix it carries.
        assertEquals(3 * 3_600_000L, heartbeats.last().recordedAt - fixTime)
        assertEquals(0, h.backend.lastLocationCalls)
    }

    @Test
    fun `a location from the backend keeps its own acquisition time`() = runTest {
        val h = HeartbeatHarness(backgroundScope)
        h.scheduler.start()

        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        h.at(2 * MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)

        val (first, second) = h.heartbeats()
        assertEquals(h.t0Wall + MIN_MS, first.recordedAt)
        assertEquals(h.t0Wall + 2 * MIN_MS, second.recordedAt)
        // The backend's fix was acquired 60 s before t0; neither heartbeat moves it to "now".
        assertEquals(h.lastKnown.time, first.location!!.time)
        assertEquals(h.lastKnown.time, second.location!!.time)
        // The first heartbeat stored it in runtime.lastLocation, so the backend was asked once.
        assertEquals(1, h.backend.lastLocationCalls)
    }

    /** P-H10: the record's metadata equals `getHeartbeatStatus()` read at the same moment. */
    private fun assertAgreesWithStatus(heartbeat: Record, status: HeartbeatStatus) {
        val meta = heartbeat.heartbeat!!
        assertEquals(status.strategy, meta.strategy)
        assertEquals(status.minInterval, meta.minInterval)
        assertEquals(status.maxInterval, meta.maxInterval)
        assertEquals(status.nextHeartbeatAt, meta.nextAt)
        assertEquals(status.isIgnoringBatteryOptimizations, meta.batteryExempt)
        assertEquals(status.isDeviceIdleMode, meta.deviceIdle)
        assertEquals(status.lastHeartbeatAt, heartbeat.recordedAt)
    }
}
