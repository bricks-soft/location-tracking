package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.integration.FullStackTestBase
import com.brickssoft.locationtracking.integration.Wire
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The stationary GPS-off mode (architecture round 2, §3) with the real component graph of
 * [com.brickssoft.locationtracking.integration.FullStackProcess]: the real geofence manager routes the region's EXIT
 * to the real engine (as `Components` does), and the real record sink, heartbeat scheduler and syncer upload what
 * the back office sees.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class StationaryGpsOffFullStackTest : FullStackTestBase() {
    @Test
    fun `GPS off while stationary, heartbeats with the anchor fix, and the region EXIT turns GPS on`() = runTest {
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        val origin = start(p)
        val backend = p.providers.geofenceBackend

        // STATIONARY after start: the region around the initial fix, passive updates only.
        val region = backend.registered.getValue(Constants.STATIONARY_REGION_ID)
        assertEquals(origin.latitude, region.latitude, 1e-12)
        assertEquals(origin.longitude, region.longitude, 1e-12)
        assertEquals(150f, region.radius)
        assertTrue(region.onExit && !region.onEntry && !region.onDwell && !region.initialTriggerEntry)
        assertEquals(DesiredAccuracy.PASSIVE, p.providers.locationBackend.active.values.single().accuracy)
        assertTrue(p.geofenceStore.all().isEmpty()) // not a user geofence

        // Another app's fix arrives through the passive request: not recorded.
        advance(60_000)
        emit(p, gps.fix(10.0, speed = 0f))
        assertEquals(listOf("tracking_start", "motionchange"), server.receivedEvents())

        // The heartbeat: recorded now, with the anchor fix and the time it was acquired.
        advance(MIN_MS - 60_000)
        alarms.fireListener()
        runCurrent()
        val heartbeat = heartbeatsReceived().single()
        assertEquals(origin.latitude, Wire.latitude(heartbeat), 1e-9)
        assertEquals(Iso8601.format(origin.time), heartbeat.getString("timestamp"))
        assertTrue(Wire.recordedAt(heartbeat) > origin.time)
        assertFalse(heartbeat.getBoolean("is_moving"))

        // The OS reports the region's EXIT; the geofence receivers hand it to the geofence manager.
        advance(30_000)
        val exitFix = gps.fix(200.0, accuracy = 20f)
        p.geofences.onGeofenceTransitions(
            listOf(OsGeofenceTransition(Constants.STATIONARY_REGION_ID, GeofenceAction.EXIT, exitFix)),
        )
        runCurrent()

        val moving = server.received().last()
        assertEquals("motionchange", moving.getString("event"))
        assertTrue(moving.getBoolean("is_moving"))
        assertEquals(exitFix.latitude, Wire.latitude(moving), 1e-9)
        assertTrue(server.receivedEvents().none { it == "geofence" })
        assertNull(backend.registered[Constants.STATIONARY_REGION_ID])
        assertEquals(DesiredAccuracy.HIGH, p.providers.locationBackend.active.values.single().accuracy)
        // The leg from the start position to the exit fix is travelled distance.
        assertEquals(200.0, p.configStore.runtime.value.odometer, 200.0 * 0.05)

        // No motion for stopTimeout: STATIONARY again, the region around the last fix, GPS off.
        advance(5 * MINUTE)
        val stopped = server.received().last()
        assertEquals("motionchange", stopped.getString("event"))
        assertFalse(stopped.getBoolean("is_moving"))
        val again = backend.registered.getValue(Constants.STATIONARY_REGION_ID)
        assertEquals(exitFix.latitude, again.latitude, 1e-12)
        assertEquals(DesiredAccuracy.PASSIVE, p.providers.locationBackend.active.values.single().accuracy)

        p.engine.stop()
        runCurrent()
        assertNull(backend.registered[Constants.STATIONARY_REGION_ID])
        assertEquals("tracking_stop", server.receivedEvents().last())
        assertHealthy(p)
    }

    @Test
    fun `a passive fix far outside the radius turns GPS on and records the motionchange`() = runTest {
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        start(p)

        advance(2 * MINUTE)
        val far = gps.fix(120.0, accuracy = 10f)
        emit(p, far)

        val moving = server.received().last()
        assertEquals("motionchange", moving.getString("event"))
        assertTrue(moving.getBoolean("is_moving"))
        assertEquals(far.latitude, Wire.latitude(moving), 1e-9)
        assertNull(p.providers.geofenceBackend.registered[Constants.STATIONARY_REGION_ID])
        assertEquals(DesiredAccuracy.HIGH, p.providers.locationBackend.active.values.single().accuracy)
        assertHealthy(p)
    }
}
