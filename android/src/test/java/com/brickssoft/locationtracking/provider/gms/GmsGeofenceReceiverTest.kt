package com.brickssoft.locationtracking.provider.gms

import android.app.Application
import android.content.Intent
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import com.brickssoft.locationtracking.testing.FakeGeofenceManager
import com.google.android.gms.common.internal.safeparcel.SafeParcelable
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GmsGeofenceReceiverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `one transition per triggering geofence with the triggering location`() {
        val event = event(Geofence.GEOFENCE_TRANSITION_ENTER, listOf("home", "block"), androidLocation(latitude = 1.5))

        val transitions = GmsGeofenceReceiver.parseGeofencingEvent(event)

        assertEquals(listOf("home", "block"), transitions.map { it.id })
        assertTrue(transitions.all { it.action == GeofenceAction.ENTER })
        assertEquals(1.5, transitions.first().location!!.latitude, 0.0)
        assertEquals("fused", transitions.first().location!!.provider)
    }

    @Test
    fun `maps exit and dwell`() {
        val exit = GmsGeofenceReceiver.parseGeofencingEvent(event(Geofence.GEOFENCE_TRANSITION_EXIT, listOf("a")))
        val dwell = GmsGeofenceReceiver.parseGeofencingEvent(event(Geofence.GEOFENCE_TRANSITION_DWELL, listOf("a")))

        assertEquals(GeofenceAction.EXIT, exit.single().action)
        assertEquals(GeofenceAction.DWELL, dwell.single().action)
    }

    @Test
    fun `missing triggering location gives a null location`() {
        val event = event(Geofence.GEOFENCE_TRANSITION_EXIT, listOf("a"))

        val transitions = GmsGeofenceReceiver.parseGeofencingEvent(event)

        assertNull(transitions.single().location)
    }

    @Test
    fun `error events are logged and produce nothing`() {
        val sink = RecordingLogSink()
        Logger.sink = sink
        val event = mockk<GeofencingEvent> {
            every { hasError() } returns true
            every { errorCode } returns GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE
        }

        assertTrue(GmsGeofenceReceiver.parseGeofencingEvent(event).isEmpty())
        val error = sink.at(LogLevel.ERROR).single()
        assertTrue(error.message, error.message.contains("GEOFENCE_NOT_AVAILABLE"))
    }

    @Test
    fun `null event and unknown transition produce nothing`() {
        assertTrue(GmsGeofenceReceiver.parseGeofencingEvent(null).isEmpty())
        assertTrue(GmsGeofenceReceiver.parseGeofencingEvent(event(-1, listOf("a"))).isEmpty())
        assertTrue(GmsGeofenceReceiver.parseGeofenceTransitions(Intent("unrelated")).isEmpty())
        assertTrue(GmsGeofenceReceiver.parseGeofenceTransitions(null).isEmpty())
    }

    @Test
    fun `parses a real GMS geofencing intent`() {
        val location = androidLocation(latitude = 2.5)
        val intent = geofencingIntent(Geofence.GEOFENCE_TRANSITION_DWELL, listOf("home", "office"), location)

        val transitions = GmsGeofenceReceiver.parseGeofenceTransitions(intent)

        assertEquals(
            listOf("home" to GeofenceAction.DWELL, "office" to GeofenceAction.DWELL),
            transitions.map { it.id to it.action },
        )
        assertEquals(2.5, transitions.first().location!!.latitude, 0.0)
    }

    @Test
    fun `parses a real GMS error intent`() {
        val intent = Intent().putExtra(EXTRA_ERROR, GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE)

        assertTrue(GmsGeofenceReceiver.parseGeofenceTransitions(intent).isEmpty())
    }

    @Test
    fun `onReceive delivers the transitions to the geofence sink`() {
        val scope = TestScope(StandardTestDispatcher())
        val manager = FakeGeofenceManager()
        val receiver = GmsGeofenceReceiver { ReceiverTarget(scope, manager) }

        receiver.onReceive(app, geofencingIntent(Geofence.GEOFENCE_TRANSITION_EXIT, listOf("home"), null))
        scope.advanceUntilIdle()

        assertEquals(listOf(OsGeofenceTransition("home", GeofenceAction.EXIT, null)), manager.transitions.toList())
    }

    @Test
    fun `onReceive ignores intents without transitions`() {
        var resolved = false
        val receiver = GmsGeofenceReceiver {
            resolved = true
            error("must not be called")
        }

        receiver.onReceive(app, Intent().putExtra(EXTRA_ERROR, GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE))

        assertTrue(!resolved)
    }

    private fun event(transition: Int, ids: List<String>, location: Location? = null): GeofencingEvent {
        val geofences = ids.map { id -> mockk<Geofence> { every { requestId } returns id } }
        return mockk {
            every { hasError() } returns false
            every { geofenceTransition } returns transition
            every { triggeringGeofences } returns geofences
            every { triggeringLocation } returns location
        }
    }

    /** Builds the intent exactly as GMS delivers it: geofences as parcelled bytes, plus transition and location. */
    private fun geofencingIntent(transition: Int, ids: List<String>, location: Location?): Intent {
        val bytes = ids.map { id ->
            val geofence = Geofence.Builder()
                .setRequestId(id)
                .setCircularRegion(24.7, 46.6, 100f)
                .setTransitionTypes(transition)
                .setLoiteringDelay(0)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build()
            SafeParcelableSerializer.serializeToBytes(geofence as SafeParcelable)
        }
        return Intent()
            .putExtra(EXTRA_TRANSITION, transition)
            .putExtra(EXTRA_GEOFENCE_LIST, ArrayList(bytes))
            .also { if (location != null) it.putExtra(EXTRA_TRIGGERING_LOCATION, location) }
    }

    private companion object {
        const val EXTRA_TRANSITION = "com.google.android.location.intent.extra.transition"
        const val EXTRA_GEOFENCE_LIST = "com.google.android.location.intent.extra.geofence_list"
        const val EXTRA_TRIGGERING_LOCATION = "com.google.android.location.intent.extra.triggering_location"
        const val EXTRA_ERROR = "gms_error_code"
    }
}
