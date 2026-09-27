package com.brickssoft.locationtracking.provider.gms

import android.app.Application
import android.app.PendingIntent
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.OsGeofence
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.tasks.Tasks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class GmsGeofenceBackendTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val client = mockk<GeofencingClient>()
    private val added = CopyOnWriteArrayList<Pair<GeofencingRequest, PendingIntent>>()
    private lateinit var backend: GmsGeofenceBackend

    @Before
    fun setUp() {
        every { client.addGeofences(any(), any()) } answers {
            added += firstArg<GeofencingRequest>() to secondArg<PendingIntent>()
            voidTask()
        }
        every { client.removeGeofences(any<List<String>>()) } returns voidTask()
        every { client.removeGeofences(any<PendingIntent>()) } returns voidTask()
        backend = GmsGeofenceBackend(app, client)
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `is a GMS backend that supports dwell`() {
        assertEquals(ProviderKind.GMS, backend.kind)
        assertTrue(backend.supportsDwell)
    }

    @Test
    fun `add maps regions to GMS geofences`() = runTest {
        backend.add(
            listOf(
                region("home", onEntry = true, onExit = true, onDwell = false, loiteringDelayMs = 30_000),
                region(
                    "work",
                    latitude = 24.8,
                    longitude = 46.7,
                    radius = 250f,
                    onEntry = false,
                    onExit = false,
                    onDwell = true,
                ),
            ),
        )

        val request = added.single().first
        assertEquals(
            GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL,
            request.initialTrigger,
        )
        val (home, work) = request.geofences
        assertEquals("home", home.requestId)
        assertEquals(24.7136, home.latitude, 1e-9)
        assertEquals(46.6753, home.longitude, 1e-9)
        assertEquals(100f, home.radius, 0f)
        assertEquals(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT, home.transitionTypes)
        assertEquals(30_000, home.loiteringDelay)
        assertEquals(Geofence.NEVER_EXPIRE, home.expirationTime)
        assertEquals("work", work.requestId)
        assertEquals(250f, work.radius, 0f)
        assertEquals(Geofence.GEOFENCE_TRANSITION_DWELL, work.transitionTypes)
        assertEquals(60_000, work.loiteringDelay)
    }

    @Test
    fun `add uses an explicit mutable broadcast to the geofence receiver`() = runTest {
        backend.add(listOf(region("home")))

        val pi = shadowOf(added.single().second)
        assertTrue(pi.isBroadcast)
        assertEquals(Constants.RC_GMS_GEOFENCE, pi.requestCode)
        assertEquals(GmsGeofenceReceiver::class.java.name, pi.savedIntent.component!!.className)
        assertEquals(Constants.ACTION_GEOFENCE, pi.savedIntent.action)
        assertTrue(pi.flags and PendingIntent.FLAG_MUTABLE != 0)
    }

    @Test
    fun `initialTriggerEntry false registers without an initial trigger`() = runTest {
        backend.add(listOf(region("home", initialTriggerEntry = false)))

        assertEquals(0, added.single().first.initialTrigger)
    }

    @Test
    fun `mixed initial triggers are split into one request each`() = runTest {
        backend.add(listOf(region("a"), region("b", initialTriggerEntry = false), region("c")))

        assertEquals(2, added.size)
        val byTrigger = added.associate { (request, _) ->
            request.initialTrigger to request.geofences.map { it.requestId }
        }
        val inside = GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL
        assertEquals(listOf("a", "c"), byTrigger[inside])
        assertEquals(listOf("b"), byTrigger[0])
    }

    @Test
    fun `a failing later request rolls back the regions registered by this call`() = runTest {
        var calls = 0
        every { client.addGeofences(any(), any()) } answers {
            if (++calls == 1) {
                voidTask()
            } else {
                Tasks.forException<Void>(ApiException(Status(GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES)))
            }
        }

        val error = addErrors(listOf(region("a"), region("b", initialTriggerEntry = false), region("c")))

        assertEquals(ErrorCode.TOO_MANY_GEOFENCES, error.code)
        verify(exactly = 1) { client.removeGeofences(listOf("a", "c")) }
    }

    @Test
    fun `a task cancelled by GMS becomes UNAVAILABLE`() = runTest {
        every { client.addGeofences(any(), any()) } returns Tasks.forCanceled()

        assertEquals(ErrorCode.UNAVAILABLE, addError(region("home")).code)
    }

    @Test
    fun `regions watching no transition are skipped`() = runTest {
        backend.add(listOf(region("none", onEntry = false, onExit = false, onDwell = false), region("home")))

        assertEquals(listOf("home"), added.single().first.geofences.map { it.requestId })
        assertNull(GmsGeofenceBackend.toGeofence(region("none", onEntry = false, onExit = false, onDwell = false)))
    }

    @Test
    fun `adding nothing does not call GMS`() = runTest {
        backend.add(emptyList())
        backend.add(listOf(region("none", onEntry = false, onExit = false, onDwell = false)))

        verify(exactly = 0) { client.addGeofences(any(), any()) }
    }

    @Test
    fun `negative loitering delay is clamped`() {
        val geofence = GmsGeofenceBackend.toGeofence(region("x", onDwell = true, loiteringDelayMs = -1))!!

        assertEquals(0, geofence.loiteringDelay)
    }

    @Test
    fun `invalid regions become INVALID_ARGUMENT`() = runTest {
        assertEquals(ErrorCode.INVALID_ARGUMENT, addError(region("bad", latitude = 91.0)).code)
        assertEquals(ErrorCode.INVALID_ARGUMENT, addError(region("bad", radius = 0f)).code)
        assertEquals(ErrorCode.INVALID_ARGUMENT, addError(region("x".repeat(101))).code)
        verify(exactly = 0) { client.addGeofences(any(), any()) }
    }

    @Test
    fun `GMS status codes map to tracking errors`() = runTest {
        val expected = mapOf(
            GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE to ErrorCode.UNAVAILABLE,
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES to ErrorCode.TOO_MANY_GEOFENCES,
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS to ErrorCode.TOO_MANY_GEOFENCES,
            GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION to ErrorCode.PERMISSION_DENIED,
            CommonStatusCodes.API_NOT_CONNECTED to ErrorCode.UNAVAILABLE,
        )
        for ((status, code) in expected) {
            every { client.addGeofences(any(), any()) } returns Tasks.forException(ApiException(Status(status)))
            val error = addError(region("home"))
            assertEquals("status $status", code, error.code)
            assertTrue(error.message!!, error.message!!.contains(status.toString()))
        }
    }

    @Test
    fun `SecurityException becomes PERMISSION_DENIED whether thrown or delivered`() = runTest {
        every { client.addGeofences(any(), any()) } returns
            Tasks.forException(SecurityException("no background location"))
        assertEquals(ErrorCode.PERMISSION_DENIED, addError(region("home")).code)

        every { client.addGeofences(any(), any()) } throws SecurityException("no background location")
        assertEquals(ErrorCode.PERMISSION_DENIED, addError(region("home")).code)
    }

    @Test
    fun `remove removes by id and ignores an empty list`() = runTest {
        backend.remove(listOf("home", "work"))
        backend.remove(emptyList())

        verify(exactly = 1) { client.removeGeofences(listOf("home", "work")) }
        verify(exactly = 1) { client.removeGeofences(any<List<String>>()) }
    }

    @Test
    fun `removeAll without a registered PendingIntent does not call GMS`() = runTest {
        backend.removeAll()

        verify(exactly = 0) { client.removeGeofences(any<PendingIntent>()) }
    }

    @Test
    fun `removeAll removes by the same PendingIntent used to add`() = runTest {
        val removedIntent = slot<PendingIntent>()
        every { client.removeGeofences(capture(removedIntent)) } returns voidTask()
        backend.add(listOf(region("home")))

        backend.removeAll()

        assertSame(added.single().second, removedIntent.captured)
    }

    @Test
    fun `remove failures are mapped`() = runTest {
        every { client.removeGeofences(any<List<String>>()) } returns
            Tasks.forException(ApiException(Status(GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE)))
        every { client.removeGeofences(any<PendingIntent>()) } throws SecurityException("denied")
        backend.add(listOf(region("home")))

        val byId = runCatching { backend.remove(listOf("home")) }.exceptionOrNull() as TrackingException
        val all = runCatching { backend.removeAll() }.exceptionOrNull() as TrackingException

        assertEquals(ErrorCode.UNAVAILABLE, byId.code)
        assertEquals(ErrorCode.PERMISSION_DENIED, all.code)
    }

    private suspend fun addError(region: OsGeofence): TrackingException = addErrors(listOf(region))

    private suspend fun addErrors(regions: List<OsGeofence>): TrackingException =
        runCatching { backend.add(regions) }.exceptionOrNull() as TrackingException

    private fun region(
        id: String,
        latitude: Double = 24.7136,
        longitude: Double = 46.6753,
        radius: Float = 100f,
        onEntry: Boolean = true,
        onExit: Boolean = true,
        onDwell: Boolean = false,
        loiteringDelayMs: Int = 60_000,
        initialTriggerEntry: Boolean = true,
    ) = OsGeofence(id, latitude, longitude, radius, onEntry, onExit, onDwell, loiteringDelayMs, initialTriggerEntry)
}
