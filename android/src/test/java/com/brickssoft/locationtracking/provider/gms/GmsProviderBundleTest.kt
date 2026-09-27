package com.brickssoft.locationtracking.provider.gms

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.LocationListener
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import com.brickssoft.locationtracking.provider.ProviderBundle
import com.brickssoft.locationtracking.provider.ProviderBundles
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34])
class GmsProviderBundleTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val fused = mockk<FusedLocationProviderClient>()
    private val activity = mockk<ActivityRecognitionClient>()
    private val geofencing = mockk<GeofencingClient>()
    private val created = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `reflective Context constructor creates a GMS bundle`() {
        val bundle = Class.forName(ProviderBundles.GMS_BUNDLE)
            .getConstructor(Context::class.java)
            .newInstance(app) as ProviderBundle

        assertEquals(ProviderKind.GMS, bundle.kind)
        // Robolectric has no Google Play services; the real check must report false without throwing.
        assertFalse(bundle.isAvailable())
    }

    @Test
    fun `available only when GoogleApiAvailability reports SUCCESS`() {
        assertTrue(bundle(availability = { ConnectionResult.SUCCESS }).isAvailable())
        assertFalse(bundle(availability = { ConnectionResult.SERVICE_MISSING }).isAvailable())
        assertFalse(bundle(availability = { ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED }).isAvailable())
        assertFalse(bundle(availability = { throw IllegalStateException("bad manifest") }).isAvailable())
        val missingClass = NoClassDefFoundError("com/google/android/gms/common/GoogleApiAvailability")
        assertFalse(bundle(availability = { throw missingClass }).isAvailable())
    }

    @Test
    fun `backends and clients are created lazily and once`() {
        val bundle = bundle()
        assertTrue(created.isEmpty())

        val location = bundle.location()
        assertEquals(listOf("fused"), created.toList())
        assertSame(location, bundle.location())

        val activityBackend = bundle.activity()
        val geofenceBackend = bundle.geofence()
        assertSame(activityBackend, bundle.activity())
        assertSame(geofenceBackend, bundle.geofence())
        assertEquals(listOf("fused", "activity", "geofencing"), created.toList())

        assertEquals(ProviderKind.GMS, location.kind)
        assertEquals(ProviderKind.GMS, activityBackend.kind)
        assertEquals(ProviderKind.GMS, geofenceBackend.kind)
        assertTrue(activityBackend.isSupported)
        assertTrue(geofenceBackend.supportsDwell)
    }

    @Test
    fun `location updates flow end to end through the bundle`() = runTest {
        val callback = slot<LocationCallback>()
        val request = slot<LocationRequest>()
        every { fused.requestLocationUpdates(capture(request), capture(callback), any<Looper>()) } returns voidTask()
        every { fused.lastLocation } returns Tasks.forResult(androidLocation(latitude = 3.0))
        val received = CopyOnWriteArrayList<TrackedLocation>()
        val backend = bundle().location()

        val spec = LocationRequestSpec(DesiredAccuracy.LOW, 60_000, 30_000, 50f)
        backend.requestUpdates(spec, LocationListener { received += it })
        callback.captured.onLocationResult(LocationResult.create(listOf(androidLocation(latitude = 7.0))))

        assertEquals(Priority.PRIORITY_LOW_POWER, request.captured.priority)
        assertEquals(listOf(7.0), received.map { it.latitude })
        assertEquals(3.0, backend.getLastLocation()!!.latitude, 0.0)
    }

    private fun bundle(availability: (Context) -> Int = { ConnectionResult.SUCCESS }) = GmsProviderBundle(
        app,
        availabilityCheck = availability,
        fusedClient = {
            created += "fused"
            fused
        },
        activityClient = {
            created += "activity"
            activity
        },
        geofencingClient = {
            created += "geofencing"
            geofencing
        },
    )
}
