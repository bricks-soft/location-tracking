package com.brickssoft.locationtracking.heartbeat

import android.os.PowerManager
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.heartbeat.HeartbeatHarness.Companion.MIN_MS
import com.brickssoft.locationtracking.model.HeartbeatStrategy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Arming, firing and stopping on every supported SDK (alarm, PendingIntent and receiver APIs differ by level). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 33, 34, 35])
class HeartbeatSdkCompatTest {
    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `listener with backup arms, fires and stops`() = runTest {
        val h = HeartbeatHarness(backgroundScope)
        h.scheduler.start()
        runCurrent()

        assertEquals(h.t(MIN_MS), h.listenerAlarm()!!.triggerAtMs)
        assertEquals(h.t(MIN_MS), h.intentAlarm()!!.triggerAtMs)
        assertTrue(registeredForIdle(h))

        h.at(MIN_MS)
        h.fireListener()
        runCurrent()
        assertEquals(1, h.heartbeats().size)

        h.scheduler.stop()
        assertTrue(h.scheduled().isEmpty())
        assertFalse(registeredForIdle(h))
    }

    @Test
    fun `exact arms one alarm and fires`() = runTest {
        val h = HeartbeatHarness(backgroundScope, exact = true)
        h.scheduler.start()

        val alarm = h.scheduled().single()
        assertEquals(HeartbeatStrategy.EXACT, h.scheduler.status().strategy)

        h.at(MIN_MS)
        h.fireIntentAlarm(alarm)
        assertEquals(1, h.heartbeats().size)
        assertEquals(h.t(2 * MIN_MS), h.scheduled().single().triggerAtMs)
    }

    private fun registeredForIdle(h: HeartbeatHarness): Boolean =
        shadowOf(h.app).registeredReceivers.any { it.intentFilter.hasAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) }
}
