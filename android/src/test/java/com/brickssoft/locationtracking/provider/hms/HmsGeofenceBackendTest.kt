package com.brickssoft.locationtracking.provider.hms

import android.app.Application
import android.app.PendingIntent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.OsGeofence
import com.huawei.hms.location.Geofence
import com.huawei.hms.location.GeofenceErrorCodes
import com.huawei.hms.location.GeofenceRequest
import com.huawei.hms.location.GeofenceService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class HmsGeofenceBackendTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val service = mockk<GeofenceService>()
    private val created = CopyOnWriteArrayList<Pair<GeofenceRequest, PendingIntent>>()
    private val deletedIds = CopyOnWriteArrayList<List<String>>()
    private val deletedIntents = CopyOnWriteArrayList<PendingIntent>()
    private val backend = HmsGeofenceBackend(app) { service }

    @Before
    fun setUp() {
        every { service.createGeofenceList(any(), any()) } answers {
            created += firstArg<GeofenceRequest>() to secondArg<PendingIntent>()
            Hms.done(null)
        }
        every { service.deleteGeofenceList(any<List<String>>()) } answers {
            deletedIds += firstArg<List<String>>()
            Hms.done(null)
        }
        every { service.deleteGeofenceList(any<PendingIntent>()) } answers {
            deletedIntents += firstArg<PendingIntent>()
            Hms.done(null)
        }
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `supports dwell`() {
        assertEquals(ProviderKind.HMS, backend.kind)
        assertTrue(backend.supportsDwell)
    }

    // ---- mapping

    @Test
    fun `transition flags map to HMS conversions`() {
        assertEquals(Geofence.ENTER_GEOFENCE_CONVERSION, hmsConversions(region(onEntry = true)))
        assertEquals(Geofence.EXIT_GEOFENCE_CONVERSION, hmsConversions(region(onEntry = false, onExit = true)))
        assertEquals(Geofence.DWELL_GEOFENCE_CONVERSION, hmsConversions(region(onEntry = false, onDwell = true)))
        assertEquals(
            Geofence.ENTER_GEOFENCE_CONVERSION or Geofence.EXIT_GEOFENCE_CONVERSION or
                Geofence.DWELL_GEOFENCE_CONVERSION,
            hmsConversions(region(onEntry = true, onExit = true, onDwell = true)),
        )
        assertEquals(0, hmsConversions(region(onEntry = false)))
    }

    @Test
    fun `initialTriggerEntry maps to ENTER and DWELL init conversions`() {
        assertEquals(
            GeofenceRequest.ENTER_INIT_CONVERSION or GeofenceRequest.DWELL_INIT_CONVERSION,
            hmsInitConversions(region(initialTriggerEntry = true)),
        )
        assertEquals(0, hmsInitConversions(region(initialTriggerEntry = false)))
    }

    @Test
    fun `builds a never-expiring round geofence`() {
        val fence = buildHmsGeofence(
            region(id = "office", radius = 150f, onEntry = true, onExit = true, onDwell = true, loiteringDelayMs = 60_000),
        )

        assertEquals("office", fence.uniqueId)
        val text = fence.toString()
        assertTrue(text, text.contains("conversions=7"))
        assertTrue(text, text.contains("radius=150.0"))
        assertTrue(text, text.contains("dwellDelayTime=60000"))
        assertTrue(text, text.contains("validDuration=${Geofence.GEOFENCE_NEVER_EXPIRE}"))
    }

    @Test
    fun `negative loitering delay is clamped so dwell geofences stay valid`() {
        val text = buildHmsGeofence(region(onDwell = true, loiteringDelayMs = -5)).toString()

        assertTrue(text, text.contains("dwellDelayTime=0"))
    }

    // ---- add

    @Test
    fun `add registers all regions in one WGS84 request with the geofence PendingIntent`() = runTest {
        backend.add(listOf(region(id = "a"), region(id = "b", onExit = true), region(id = "c", onDwell = true)))

        val (request, pendingIntent) = created.single()
        assertEquals(listOf("a", "b", "c"), request.geofences.map { it.uniqueId })
        assertEquals(
            GeofenceRequest.ENTER_INIT_CONVERSION or GeofenceRequest.DWELL_INIT_CONVERSION,
            request.initConversions,
        )
        assertEquals(GeofenceRequest.COORDINATE_TYPE_WGS_84, request.coordinateType)
        assertGeofencePendingIntent(pendingIntent)
    }

    @Test
    @Config(sdk = [30])
    fun `geofence PendingIntent is not flagged mutable below API 31`() = runTest {
        backend.add(listOf(region(id = "a")))

        assertGeofencePendingIntent(created.single().second)
    }

    @Test
    fun `regions are grouped by initialTriggerEntry`() = runTest {
        backend.add(
            listOf(
                region(id = "a", initialTriggerEntry = true),
                region(id = "b", initialTriggerEntry = false),
                region(id = "c", initialTriggerEntry = true),
            ),
        )

        assertEquals(2, created.size)
        val byInit = created.associate { (request, _) -> request.initConversions to request.geofences.map { it.uniqueId } }
        assertEquals(
            mapOf(
                (GeofenceRequest.ENTER_INIT_CONVERSION or GeofenceRequest.DWELL_INIT_CONVERSION) to listOf("a", "c"),
                0 to listOf("b"),
            ),
            byInit,
        )
    }

    @Test
    fun `duplicate ids keep the last region`() = runTest {
        backend.add(listOf(region(id = "a", radius = 100f), region(id = "a", radius = 300f)))

        val fence = created.single().first.geofences.single()
        assertEquals("a", fence.uniqueId)
        assertTrue(fence.toString().contains("radius=300.0"))
    }

    @Test
    fun `regions without transitions are skipped`() = runTest {
        backend.add(listOf(region(id = "silent", onEntry = false), region(id = "loud")))

        assertEquals(listOf("loud"), created.single().first.geofences.map { it.uniqueId })
    }

    @Test
    fun `add with nothing to register makes no call`() = runTest {
        backend.add(emptyList())
        backend.add(listOf(region(onEntry = false)))

        verify(exactly = 0) { service.createGeofenceList(any(), any()) }
    }

    // ---- remove

    @Test
    fun `remove deletes by distinct ids`() = runTest {
        backend.remove(listOf("a", "b", "a"))
        backend.remove(emptyList())

        assertEquals(listOf(listOf("a", "b")), deletedIds)
    }

    @Test
    fun `removeAll deletes by the geofence PendingIntent`() = runTest {
        backend.add(listOf(region(id = "a")))

        backend.removeAll()

        assertSame(created.single().second, deletedIntents.single())
    }

    // ---- errors

    @Test
    fun `add maps HMS errors`() = runTest {
        every { service.createGeofenceList(any(), any()) } returns
            Hms.failed(Hms.apiException(GeofenceErrorCodes.GEOFENCE_NUMBER_OVER_LIMIT))
        Hms.expectTrackingError(ErrorCode.TOO_MANY_GEOFENCES) { backend.add(listOf(region())) }

        every { service.createGeofenceList(any(), any()) } returns
            Hms.failed(Hms.apiException(GeofenceErrorCodes.GEOFENCE_PENDINGINTENT_OVER_LIMIT))
        Hms.expectTrackingError(ErrorCode.TOO_MANY_GEOFENCES) { backend.add(listOf(region())) }

        every { service.createGeofenceList(any(), any()) } returns
            Hms.failed(Hms.apiException(GeofenceErrorCodes.GEOFENCE_INSUFFICIENT_PERMISSION))
        Hms.expectTrackingError(ErrorCode.PERMISSION_DENIED) { backend.add(listOf(region())) }

        every { service.createGeofenceList(any(), any()) } throws SecurityException("denied")
        Hms.expectTrackingError(ErrorCode.PERMISSION_DENIED) { backend.add(listOf(region())) }

        every { service.createGeofenceList(any(), any()) } returns
            Hms.failed(Hms.apiException(GeofenceErrorCodes.GEOFENCE_UNAVAILABLE))
        Hms.expectTrackingError(ErrorCode.LOCATION_DISABLED) { backend.add(listOf(region())) }

        every { service.createGeofenceList(any(), any()) } returns
            Hms.failed(Hms.apiException(GeofenceErrorCodes.GEOFENCE_REQUEST_TOO_OFTEN))
        Hms.expectTrackingError(ErrorCode.UNAVAILABLE) { backend.add(listOf(region())) }
        assertTrue(deletedIds.isEmpty())
    }

    @Test
    fun `a failed later request rolls back the earlier ones of the same call`() = runTest {
        every { service.createGeofenceList(any(), any()) } answers {
            created += firstArg<GeofenceRequest>() to secondArg<PendingIntent>()
            Hms.done(null)
        } andThenAnswer {
            Hms.failed(Hms.apiException(GeofenceErrorCodes.GEOFENCE_NUMBER_OVER_LIMIT))
        }

        Hms.expectTrackingError(ErrorCode.TOO_MANY_GEOFENCES) {
            backend.add(
                listOf(
                    region(id = "a", initialTriggerEntry = true),
                    region(id = "b", initialTriggerEntry = false),
                    region(id = "c", initialTriggerEntry = true),
                ),
            )
        }

        assertEquals(listOf("a", "c"), created.single().first.geofences.map { it.uniqueId })
        assertEquals(listOf(listOf("a", "c")), deletedIds)
    }

    @Test
    fun `an invalid region is reported as INVALID_ARGUMENT`() = runTest {
        Hms.expectTrackingError(ErrorCode.INVALID_ARGUMENT) { backend.add(listOf(region(id = ""))) }

        verify(exactly = 0) { service.createGeofenceList(any(), any()) }
    }

    @Test
    fun `remove and removeAll map HMS errors`() = runTest {
        every { service.deleteGeofenceList(any<List<String>>()) } returns Hms.failed(Hms.apiException(907135003))
        Hms.expectTrackingError(ErrorCode.UNAVAILABLE) { backend.remove(listOf("a")) }

        every { service.deleteGeofenceList(any<PendingIntent>()) } throws SecurityException("denied")
        Hms.expectTrackingError(ErrorCode.PERMISSION_DENIED) { backend.removeAll() }
    }

    @Test
    fun `service is created lazily`() = runTest {
        var creations = 0
        val lazyBackend = HmsGeofenceBackend(app) {
            creations++
            service
        }
        assertEquals(0, creations)

        lazyBackend.add(listOf(region()))
        lazyBackend.remove(listOf("a"))
        lazyBackend.removeAll()

        assertEquals(1, creations)
    }

    private fun assertGeofencePendingIntent(pendingIntent: PendingIntent) {
        val shadow = shadowOf(pendingIntent)
        assertTrue(shadow.isBroadcast)
        assertEquals(Constants.RC_HMS_GEOFENCE, shadow.requestCode)
        assertEquals(HmsGeofenceReceiver::class.java.name, shadow.savedIntent.component?.className)
        assertEquals(Constants.ACTION_GEOFENCE, shadow.savedIntent.action)
        val mutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        assertEquals(mutable, shadow.flags and PendingIntent.FLAG_MUTABLE != 0)
    }

    private fun region(
        id: String = "home",
        radius: Float = 200f,
        onEntry: Boolean = true,
        onExit: Boolean = false,
        onDwell: Boolean = false,
        loiteringDelayMs: Int = 30_000,
        initialTriggerEntry: Boolean = true,
    ) = OsGeofence(id, 24.7136, 46.6753, radius, onEntry, onExit, onDwell, loiteringDelayMs, initialTriggerEntry)
}
