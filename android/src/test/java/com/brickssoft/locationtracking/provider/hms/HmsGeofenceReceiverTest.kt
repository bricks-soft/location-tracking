package com.brickssoft.locationtracking.provider.hms

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Parcelable
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.provider.OsGeofence
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import com.brickssoft.locationtracking.testing.FakeGeofenceManager
import com.huawei.hms.location.Geofence
import com.huawei.hms.location.GeofenceData
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HmsGeofenceReceiverTest {
    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `conversions map to actions`() {
        assertEquals(GeofenceAction.ENTER, HmsGeofenceReceiver.geofenceAction(Geofence.ENTER_GEOFENCE_CONVERSION))
        assertEquals(GeofenceAction.EXIT, HmsGeofenceReceiver.geofenceAction(Geofence.EXIT_GEOFENCE_CONVERSION))
        assertEquals(GeofenceAction.DWELL, HmsGeofenceReceiver.geofenceAction(Geofence.DWELL_GEOFENCE_CONVERSION))
        assertNull(HmsGeofenceReceiver.geofenceAction(-1))
        assertNull(HmsGeofenceReceiver.geofenceAction(3))
    }

    @Test
    fun `parses a real HMS geofence event`() {
        val location = Hms.location(latitude = 24.71, longitude = 46.67, accuracy = 12f)
        val intent = geofenceIntent(Geofence.ENTER_GEOFENCE_CONVERSION, listOf("home", "office", "home"), location)

        val transitions = HmsGeofenceReceiver.parseGeofenceTransitions(intent)

        val fix = toTrackedLocation(location)
        assertEquals(
            listOf(
                OsGeofenceTransition("home", GeofenceAction.ENTER, fix),
                OsGeofenceTransition("office", GeofenceAction.ENTER, fix),
            ),
            transitions,
        )
    }

    @Test
    fun `EXIT and DWELL events without a location`() {
        val exit = HmsGeofenceReceiver.parseGeofenceTransitions(
            geofenceIntent(Geofence.EXIT_GEOFENCE_CONVERSION, listOf("a"), null),
        )
        val dwell = HmsGeofenceReceiver.parseGeofenceTransitions(
            geofenceIntent(Geofence.DWELL_GEOFENCE_CONVERSION, listOf("b"), null),
        )

        assertEquals(listOf(OsGeofenceTransition("a", GeofenceAction.EXIT, null)), exit)
        assertEquals(listOf(OsGeofenceTransition("b", GeofenceAction.DWELL, null)), dwell)
    }

    @Test
    fun `an error event yields nothing`() {
        val intent = geofenceIntent(Geofence.ENTER_GEOFENCE_CONVERSION, listOf("a"), null)
            .putExtra(GeofenceData.KEY_ERROR_CODE, 10200)

        assertTrue(HmsGeofenceReceiver.parseGeofenceTransitions(intent).isEmpty())
    }

    @Test
    fun `unknown conversions and foreign intents yield nothing`() {
        assertTrue(HmsGeofenceReceiver.parseGeofenceTransitions(geofenceIntent(99, listOf("a"), null)).isEmpty())
        assertTrue(HmsGeofenceReceiver.parseGeofenceTransitions(Intent()).isEmpty())
        assertTrue(HmsGeofenceReceiver.parseGeofenceTransitions(null).isEmpty())
    }

    @Test
    fun `delivery hands transitions to the geofence manager and finishes the broadcast`() = runTest {
        val manager = FakeGeofenceManager()
        val pending = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
        val transitions = listOf(OsGeofenceTransition("home", GeofenceAction.EXIT, null))

        deliverAsync(pending, this, "test", "transitions") { manager.onGeofenceTransitions(transitions) }
        advanceUntilIdle()

        assertEquals(transitions, manager.transitions)
        verify(exactly = 1) { pending.finish() }
    }

    /** An intent shaped like the ones HMS 6.12 sends (see `GeofenceData.getDataFromIntent`). */
    private fun geofenceIntent(conversion: Int, ids: List<String>, location: android.location.Location?): Intent {
        val fences = ids.map { id ->
            buildHmsGeofence(OsGeofence(id, 24.7, 46.6, 100f, true, true, false, 0, true)) as Parcelable
        }
        return Intent()
            .putExtra(GeofenceData.KEY_TRANSITION, conversion)
            .putParcelableArrayListExtra(GeofenceData.KEY_GEOFENCE_LIST, ArrayList(fences))
            .apply { if (location != null) putExtra(GeofenceData.KEY_LOCATION, location) }
    }
}
