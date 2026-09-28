package com.brickssoft.locationtracking.heartbeat

import android.os.PowerManager
import com.brickssoft.locationtracking.config.HeartbeatConfig
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.heartbeat.HeartbeatHarness.Companion.MIN_MS
import com.brickssoft.locationtracking.http.HttpSyncer
import com.brickssoft.locationtracking.model.HeartbeatStrategy
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.testing.FakeHttpSyncer
import com.brickssoft.locationtracking.testing.FakeProviderFactory
import com.brickssoft.locationtracking.testing.Fixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager

/**
 * The heartbeat's cost (round 2 §5): AlarmManager traffic while records flow (a moving phone records about every
 * 5 s), the re-arm threshold and its early alarms, the wake lock, and backend last-location requests.
 *
 * Measured before this change (the scheduler re-armed on every record), 60 records 5 s apart cost 120 set calls with
 * `listener_with_backup` and 60 with `exact`; now 20 and 10. The counts are printed so the test report shows them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HeartbeatCostTest {
    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `listener with backup - AlarmManager set calls for 60 records 5 s apart`() = runTest {
        val sets = setCallsForRecords(exact = false, records = 60, spacingMs = 5_000L)
        println("HeartbeatCost listener_with_backup: $sets set calls for 60 records 5 s apart")
        assertEquals(20, sets)
    }

    @Test
    fun `exact - AlarmManager set calls for 60 records 5 s apart`() = runTest {
        val sets = setCallsForRecords(exact = true, records = 60, spacingMs = 5_000L)
        println("HeartbeatCost exact: $sets set calls for 60 records 5 s apart")
        assertEquals(10, sets)
    }

    @Test
    fun `a refused exact alarm is not retried on every record`() = runTest {
        var counting: CountingAlarms? = null
        val h = HeartbeatHarness(
            backgroundScope,
            exact = true,
            alarmsOverride = { real -> CountingAlarms(RefusingExactAlarms(real)).also { counting = it } },
        )
        h.scheduler.start()
        val before = counting!!.sets

        for (i in 1..60) {
            h.at(i * 5_000L)
            h.submit(RecordEvent.LOCATION)
        }

        // 10 re-arms, each: one refused exact attempt + listener + backup.
        assertEquals(30, counting!!.sets - before)
        assertEquals(HeartbeatStrategy.LISTENER_WITH_BACKUP, h.scheduler.status().strategy)
        assertEquals(h.t(300_000L + MIN_MS), h.listenerAlarm()!!.triggerAtMs)
    }

    // ---- re-arm threshold

    @Test
    fun `a due time moved by less than 30 s keeps the alarms, 30 s re-arms`() = runTest {
        var counting: CountingAlarms? = null
        val h = HeartbeatHarness(backgroundScope, alarmsOverride = { real -> CountingAlarms(real).also { counting = it } })
        h.scheduler.start()
        val afterStart = counting!!.sets

        h.at(29_999L)
        h.submit()
        assertEquals(afterStart, counting!!.sets)
        assertEquals(h.t(MIN_MS), h.listenerAlarm()!!.triggerAtMs)
        assertEquals(h.t(MIN_MS), h.intentAlarm()!!.triggerAtMs)

        h.at(30_000L)
        h.submit()
        assertEquals(afterStart + 2, counting!!.sets)
        assertEquals(h.t(30_000L + MIN_MS), h.listenerAlarm()!!.triggerAtMs)
        assertEquals(h.t(30_000L + MIN_MS), h.intentAlarm()!!.triggerAtMs)
    }

    @Test
    fun `records every 5 s keep the armed alarm at least 150 s ahead`() = runTest {
        val h = HeartbeatHarness(backgroundScope)
        h.scheduler.start()

        for (i in 1..120) {
            h.at(i * 5_000L)
            h.submit()
            assertTrue(h.listenerAlarm()!!.triggerAtMs - h.clock.elapsedRealtime() >= MIN_MS - 30_000L)
        }
        assertTrue(h.heartbeats().isEmpty())
    }

    @Test
    fun `an alarm left in place fires early, creates no heartbeat and arms the real due time`() = runTest {
        val h = HeartbeatHarness(backgroundScope)
        h.scheduler.start()
        for (s in listOf(5L, 10L, 15L, 20L, 25L)) {
            h.at(s * 1_000L)
            h.submit()
        }
        // The alarms stay at 180 s; the heartbeat is due 180 s after the last record (25 s), at 205 s.
        assertEquals(h.t(MIN_MS), h.listenerAlarm()!!.triggerAtMs)
        assertEquals(h.t0Wall + 205_000L, h.scheduler.status().nextHeartbeatAt)

        // Both alarms of the window fire at 180 s.
        h.at(MIN_MS)
        h.fireListener()
        runCurrent()
        h.fireIntentAlarm()

        assertTrue(h.heartbeats().isEmpty())
        assertEquals(h.t(205_000L), h.listenerAlarm()!!.triggerAtMs)
        assertEquals(h.t(205_000L), h.intentAlarm()!!.triggerAtMs)
        assertEquals(h.t0Wall + 205_000L, h.scheduler.status().nextHeartbeatAt)

        h.at(205_000L)
        h.fireListener()
        runCurrent()

        // Exactly minInterval after the last record, as without the threshold.
        val heartbeat = h.heartbeats().single()
        assertEquals(h.t0Wall + 205_000L, heartbeat.recordedAt)
        assertEquals(h.t(205_000L + MIN_MS), h.listenerAlarm()!!.triggerAtMs)
    }

    @Test
    fun `an early exact alarm re-arms without a heartbeat`() = runTest {
        val h = HeartbeatHarness(backgroundScope, exact = true)
        h.scheduler.start()
        h.at(20_000L)
        h.submit()
        assertEquals(h.t(MIN_MS), h.scheduled().single().triggerAtMs)

        h.at(MIN_MS)
        h.fireIntentAlarm()

        assertTrue(h.heartbeats().isEmpty())
        assertEquals(h.t(20_000L + MIN_MS), h.scheduled().single().triggerAtMs)
        h.at(20_000L + MIN_MS)
        h.fireIntentAlarm()
        assertEquals(h.t0Wall + 20_000L + MIN_MS, h.heartbeats().single().recordedAt)
    }

    @Test
    fun `a shorter minInterval re-arms at once, even by 10 s`() = runTest {
        val h = HeartbeatHarness(backgroundScope)
        h.scheduler.start()
        runCurrent()

        h.configStore.update { it.copy(heartbeat = HeartbeatConfig(minInterval = 170, maxInterval = 300)) }
        runCurrent()

        assertEquals(h.t(170_000L), h.listenerAlarm()!!.triggerAtMs)
        assertEquals(h.t(170_000L), h.intentAlarm()!!.triggerAtMs)
    }

    @Test
    fun `idle-paced - any move of the backup re-arms it, so it never fires early`() = runTest {
        var counting: CountingAlarms? = null
        val h = HeartbeatHarness(
            backgroundScope,
            idle = true,
            alarmsOverride = { real -> CountingAlarms(real).also { counting = it } },
        )
        h.scheduler.start()
        // A listener heartbeat: no backup has fired in this boot, so the backup is at the due time.
        h.at(MIN_MS)
        h.fireListener()
        runCurrent()
        assertEquals(h.t(2 * MIN_MS), h.intentAlarm()!!.triggerAtMs)
        val before = counting!!.sets

        h.at(MIN_MS + 10_000L)
        h.submit()

        assertEquals(before + 2, counting!!.sets)
        assertEquals(h.t(2 * MIN_MS + 10_000L), h.intentAlarm()!!.triggerAtMs)
        assertEquals(h.t(2 * MIN_MS + 10_000L), h.listenerAlarm()!!.triggerAtMs)
    }

    // ---- wake lock

    @Test
    fun `the listener path holds one wake lock from the alarm through the submit`() = runTest {
        ShadowPowerManager.clearWakeLocks()
        var duringSubmit: PowerManager.WakeLock? = null
        val syncer = object : HttpSyncer by FakeHttpSyncer() {
            override fun onRecordInserted(record: Record) {
                duringSubmit = ShadowPowerManager.getLatestWakeLock()
            }
        }
        val h = HeartbeatHarness(backgroundScope, syncer = syncer)
        h.scheduler.start()

        h.at(MIN_MS)
        h.fireListener()
        val fromListener = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(fromListener)
        assertTrue(fromListener.isHeld)
        runCurrent()

        assertEquals(1, h.heartbeats().size)
        // No second wake lock was created for the handling, and the listener's one is released.
        assertSame(fromListener, duringSubmit)
        assertSame(fromListener, ShadowPowerManager.getLatestWakeLock())
        assertFalse(fromListener.isHeld)
        assertEquals(1, shadowOf(fromListener).timesHeld)
    }

    // ---- backend last location

    @Test
    fun `the backend is not asked again within 10 min after it had no location`() = runTest {
        val h = HeartbeatHarness(backgroundScope)
        h.backend.lastLocation = null
        h.scheduler.start()

        // 180 s: asked, nothing.
        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        assertEquals(1, h.backend.lastLocationCalls)
        // 360 s, 540 s, 720 s: less than 10 min after the miss, not asked.
        for (i in 2..4) {
            h.at(i * MIN_MS)
            h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        }
        assertEquals(1, h.backend.lastLocationCalls)
        assertEquals(4, h.heartbeats().size)
        assertTrue(h.heartbeats().all { it.location == null })

        // 900 s: 12 min after the miss, asked again; this time the backend has a fix.
        h.backend.lastLocation = h.lastKnown
        h.at(5 * MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        assertEquals(2, h.backend.lastLocationCalls)
        assertEquals(h.lastKnown, h.heartbeats().last().location)

        // The sink keeps it in runtime.lastLocation: the backend is not needed any more.
        h.at(6 * MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        assertEquals(2, h.backend.lastLocationCalls)
        assertEquals(h.lastKnown, h.heartbeats().last().location)
    }

    @Test
    fun `a timed-out request is not remembered - the next heartbeat asks again`() = runTest {
        var hanging: HangingLastLocationProviders? = null
        val h = HeartbeatHarness(
            backgroundScope,
            providersOverride = { real -> HangingLastLocationProviders(real).also { hanging = it } },
        )
        h.scheduler.start()

        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        assertNull(h.heartbeats().single().location)
        assertEquals(1, hanging!!.calls)

        // The backend answers again: the next heartbeat asks and gets the fix.
        hanging!!.hang = false
        h.at(2 * MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        assertEquals(2, hanging!!.calls)
        assertEquals(h.lastKnown, h.heartbeats().last().location)
    }

    @Test
    fun `a fix that reaches runtime after a miss is used at once`() = runTest {
        val h = HeartbeatHarness(backgroundScope)
        h.backend.lastLocation = null
        h.scheduler.start()
        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        assertNull(h.heartbeats().single().location)

        // The engine refreshes runtime.lastLocation from a fix that creates no record (stationary).
        val passive = Fixtures.location(latitude = 1.25, longitude = 2.5, time = h.clock.now() - 5_000L)
        h.configStore.updateRuntime { it.copy(lastLocation = passive) }
        h.at(2 * MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)

        assertEquals(passive, h.heartbeats().last().location)
        assertEquals(1, h.backend.lastLocationCalls)
    }

    /** Starts the scheduler, submits [records] records [spacingMs] apart, and returns the set calls they caused. */
    private suspend fun TestScope.setCallsForRecords(exact: Boolean, records: Int, spacingMs: Long): Int {
        var counting: CountingAlarms? = null
        val h = HeartbeatHarness(
            backgroundScope,
            exact = exact,
            alarmsOverride = { real -> CountingAlarms(real).also { counting = it } },
        )
        h.scheduler.start()
        runCurrent()
        val before = counting!!.sets
        for (i in 1..records) {
            h.at(i * spacingMs)
            h.submit(RecordEvent.LOCATION)
        }
        return counting!!.sets - before
    }

    /** Providers whose last-location request does not answer within the heartbeat's 3 s timeout while [hang]. */
    private class HangingLastLocationProviders(private val real: FakeProviderFactory) : ProviderFactory by real {
        @Volatile
        var hang = true

        @Volatile
        var calls = 0

        private val backend = object : LocationBackend by real.locationBackend {
            override suspend fun getLastLocation(): TrackedLocation? {
                calls++
                if (hang) delay(10_000L)
                return real.locationBackend.getLastLocation()
            }
        }

        override fun location(): LocationBackend = backend
    }
}
