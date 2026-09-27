package com.brickssoft.locationtracking.provider.android

import android.app.Application
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.LocationManager.GPS_PROVIDER
import android.location.LocationManager.NETWORK_PROVIDER
import android.location.LocationManager.PASSIVE_PROVIDER
import android.location.LocationRequest
import android.os.Looper
import android.os.SystemClock
import androidx.core.location.LocationRequestCompat
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.LocationListener
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocationManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** Drives [AndroidLocationBackend] through the [com.brickssoft.locationtracking.provider.LocationBackend] contract. */
@RunWith(RobolectricTestRunner::class)
class AndroidLocationBackendTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val lm = app.getSystemService(LocationManager::class.java)
    private val shadowLm: ShadowLocationManager = shadowOf(lm)
    private val backend = AndroidLocationBackend(app)

    private class RecordingListener : LocationListener {
        val batches = CopyOnWriteArrayList<List<TrackedLocation>>()

        override fun onLocations(locations: List<TrackedLocation>) {
            batches += locations
        }
    }

    @Before
    fun setUp() {
        shadowLm.setLocationEnabled(true)
        shadowLm.setProviderEnabled(GPS_PROVIDER, true)
        shadowLm.setProviderEnabled(NETWORK_PROVIDER, true)
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun spec(
        accuracy: DesiredAccuracy,
        interval: Long = 10_000,
        fastest: Long = 5_000,
        distance: Float = 25f,
    ) = LocationRequestSpec(accuracy, interval, fastest, distance)

    private fun fix(provider: String, lat: Double = 24.7136, ageMs: Long = 0) = Location(provider).apply {
        latitude = lat
        longitude = 46.6753
        accuracy = 5f
        time = 1_790_417_730_123L - ageMs
        elapsedRealtimeNanos = (SystemClock.elapsedRealtime() - ageMs) * 1_000_000
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun listenersOn(provider: String) = shadowLm.getLocationUpdateListeners(provider).size

    @Test
    fun `kind is android`() {
        assertEquals(ProviderKind.ANDROID, backend.kind)
    }

    @Test
    fun `high accuracy requests gps with interval, min interval and distance`() {
        backend.requestUpdates(spec(DesiredAccuracy.HIGH), RecordingListener())

        val requests: List<LocationRequest> = shadowLm.getLocationRequests(GPS_PROVIDER)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals(10_000L, request.intervalMillis)
        assertEquals(5_000L, request.minUpdateIntervalMillis)
        assertEquals(25f, request.minUpdateDistanceMeters)
        assertEquals(LocationRequestCompat.QUALITY_HIGH_ACCURACY, request.quality)
        assertEquals(0, listenersOn(NETWORK_PROVIDER))
    }

    @Test
    fun `balanced and low accuracy request network`() {
        backend.requestUpdates(spec(DesiredAccuracy.BALANCED), RecordingListener())
        backend.requestUpdates(spec(DesiredAccuracy.LOW), RecordingListener())

        val qualities = shadowLm.getLocationRequests(NETWORK_PROVIDER).map { it.quality }
        assertEquals(
            listOf(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY, LocationRequestCompat.QUALITY_LOW_POWER),
            qualities,
        )
        assertEquals(0, listenersOn(GPS_PROVIDER))
    }

    @Test
    fun `passive accuracy requests the passive provider`() {
        backend.requestUpdates(spec(DesiredAccuracy.PASSIVE), RecordingListener())

        assertEquals(1, listenersOn(PASSIVE_PROVIDER))
        assertEquals(0, listenersOn(GPS_PROVIDER) + listenersOn(NETWORK_PROVIDER))
    }

    @Test
    fun `falls back to whichever provider is enabled`() {
        shadowLm.setProviderEnabled(GPS_PROVIDER, false)
        backend.requestUpdates(spec(DesiredAccuracy.HIGH), RecordingListener())
        assertEquals(1, listenersOn(NETWORK_PROVIDER))

        shadowLm.setProviderEnabled(GPS_PROVIDER, true)
        shadowLm.setProviderEnabled(NETWORK_PROVIDER, false)
        backend.requestUpdates(spec(DesiredAccuracy.BALANCED), RecordingListener())
        assertEquals(1, listenersOn(GPS_PROVIDER))
    }

    @Test
    fun `with no provider enabled the preferred one is requested`() {
        shadowLm.setProviderEnabled(GPS_PROVIDER, false)
        shadowLm.setProviderEnabled(NETWORK_PROVIDER, false)

        backend.requestUpdates(spec(DesiredAccuracy.HIGH), RecordingListener())

        assertEquals(1, listenersOn(GPS_PROVIDER))
    }

    @Test
    fun `invalid spec values are clamped`() {
        val request = AndroidLocationBackend.requestFor(
            LocationRequestSpec(
                DesiredAccuracy.HIGH, intervalMs = 1_000, fastestIntervalMs = 5_000, distanceFilterM = -3f,
            ),
        )

        assertEquals(1_000L, request.intervalMillis)
        assertEquals(1_000L, request.minUpdateIntervalMillis)
        assertEquals(0f, request.minUpdateDistanceMeters)
        val nan = AndroidLocationBackend.requestFor(LocationRequestSpec(DesiredAccuracy.LOW, -1, -1, Float.NaN))
        assertEquals(0L, nan.intervalMillis)
        assertEquals(0f, nan.minUpdateDistanceMeters)
    }

    @Test
    fun `delivers fixes on the main looper as TrackedLocation`() {
        val listener = RecordingListener()
        backend.requestUpdates(spec(DesiredAccuracy.HIGH), listener)

        shadowLm.simulateLocation(GPS_PROVIDER, fix(GPS_PROVIDER, lat = 1.5))
        idleMain()

        val delivered = listener.batches.flatten()
        assertEquals(1, delivered.size)
        assertEquals(1.5, delivered.single().latitude, 0.0)
        assertEquals(46.6753, delivered.single().longitude, 0.0)
        assertEquals(GPS_PROVIDER, delivered.single().provider)
    }

    @Test
    fun `multiple listeners are independent and the same listener replaces its request`() {
        val a = RecordingListener()
        val b = RecordingListener()
        backend.requestUpdates(spec(DesiredAccuracy.HIGH), a)
        backend.requestUpdates(spec(DesiredAccuracy.HIGH), b)
        assertEquals(2, listenersOn(GPS_PROVIDER))

        backend.requestUpdates(spec(DesiredAccuracy.BALANCED), a)

        assertEquals(1, listenersOn(GPS_PROVIDER))
        assertEquals(1, listenersOn(NETWORK_PROVIDER))
        shadowLm.simulateLocation(GPS_PROVIDER, fix(GPS_PROVIDER))
        idleMain()
        assertEquals(0, a.batches.size)
        assertEquals(1, b.batches.size)
        shadowLm.simulateLocation(NETWORK_PROVIDER, fix(NETWORK_PROVIDER))
        idleMain()
        assertEquals(1, a.batches.size)
        assertEquals(1, b.batches.size)
    }

    @Test
    fun `removeUpdates stops delivery and unknown listeners are ignored`() {
        val listener = RecordingListener()
        backend.requestUpdates(spec(DesiredAccuracy.HIGH), listener)

        backend.removeUpdates(listener)
        backend.removeUpdates(RecordingListener())
        shadowLm.simulateLocation(GPS_PROVIDER, fix(GPS_PROVIDER))
        idleMain()

        assertEquals(0, listenersOn(GPS_PROVIDER))
        assertTrue(listener.batches.isEmpty())
    }

    @Test
    fun `fixes queued before removal are dropped`() {
        val listener = RecordingListener()
        backend.requestUpdates(spec(DesiredAccuracy.HIGH), listener)
        shadowLm.simulateLocation(GPS_PROVIDER, fix(GPS_PROVIDER))

        backend.removeUpdates(listener)
        idleMain()

        assertTrue(listener.batches.isEmpty())
    }

    @Test
    fun `a failing listener does not break delivery`() {
        val good = RecordingListener()
        backend.requestUpdates(spec(DesiredAccuracy.HIGH)) { throw IllegalStateException("boom") }
        backend.requestUpdates(spec(DesiredAccuracy.HIGH), good)

        shadowLm.simulateLocation(GPS_PROVIDER, fix(GPS_PROVIDER))
        idleMain()

        assertEquals(1, good.batches.size)
    }

    @Test
    fun `moves to the preferred provider when it becomes enabled`() {
        shadowLm.setProviderEnabled(GPS_PROVIDER, false)
        val listener = RecordingListener()
        backend.requestUpdates(spec(DesiredAccuracy.HIGH), listener)
        assertEquals(1, listenersOn(NETWORK_PROVIDER))

        shadowLm.setProviderEnabled(GPS_PROVIDER, true)
        app.sendBroadcast(Intent(LocationManager.PROVIDERS_CHANGED_ACTION))
        idleMain()

        assertEquals(1, listenersOn(GPS_PROVIDER))
        assertEquals(0, listenersOn(NETWORK_PROVIDER))
        shadowLm.simulateLocation(GPS_PROVIDER, fix(GPS_PROVIDER))
        idleMain()
        assertEquals(1, listener.batches.size)
    }

    @Test
    fun `moves away from a provider that becomes disabled`() {
        val listener = RecordingListener()
        backend.requestUpdates(spec(DesiredAccuracy.HIGH), listener)

        shadowLm.setProviderEnabled(GPS_PROVIDER, false)
        app.sendBroadcast(Intent(LocationManager.PROVIDERS_CHANGED_ACTION))
        idleMain()

        assertEquals(0, listenersOn(GPS_PROVIDER))
        assertEquals(1, listenersOn(NETWORK_PROVIDER))
    }

    @Test
    fun `provider changes after the last removal are ignored`() {
        shadowLm.setProviderEnabled(GPS_PROVIDER, false)
        val listener = RecordingListener()
        backend.requestUpdates(spec(DesiredAccuracy.HIGH), listener)
        backend.removeUpdates(listener)

        shadowLm.setProviderEnabled(GPS_PROVIDER, true)
        app.sendBroadcast(Intent(LocationManager.PROVIDERS_CHANGED_ACTION))
        idleMain()

        assertEquals(0, listenersOn(GPS_PROVIDER) + listenersOn(NETWORK_PROVIDER))
    }

    @Test
    fun `getLastLocation returns the freshest fix across enabled providers`() = runTest {
        shadowLm.setLastKnownLocation(GPS_PROVIDER, fix(GPS_PROVIDER, lat = 1.0, ageMs = 60_000))
        shadowLm.setLastKnownLocation(NETWORK_PROVIDER, fix(NETWORK_PROVIDER, lat = 2.0, ageMs = 5_000))

        val last = backend.getLastLocation()

        assertEquals(2.0, last!!.latitude, 0.0)
        assertEquals(NETWORK_PROVIDER, last.provider)
    }

    @Test
    fun `getLastLocation is null without any fix`() = runTest {
        assertNull(backend.getLastLocation())
    }

    @Test
    fun `getCurrentLocation returns the next fix of the preferred provider`() = runTest {
        val result = async { backend.getCurrentLocation(DesiredAccuracy.HIGH, 10_000) }
        runCurrent()

        shadowLm.simulateLocation(GPS_PROVIDER, fix(GPS_PROVIDER, lat = 3.0))
        idleMain()

        assertEquals(3.0, result.await()!!.latitude, 0.0)
        assertEquals(0, listenersOn(GPS_PROVIDER))
    }

    @Test
    @Config(sdk = [29])
    fun `getCurrentLocation works through the pre-R compat path`() = runTest {
        val result = async { backend.getCurrentLocation(DesiredAccuracy.BALANCED, 10_000) }
        runCurrent()
        assertEquals(1, listenersOn(NETWORK_PROVIDER))

        shadowLm.simulateLocation(NETWORK_PROVIDER, fix(NETWORK_PROVIDER, lat = 4.0))
        idleMain()

        assertEquals(4.0, result.await()!!.latitude, 0.0)
    }

    @Test
    @Config(sdk = [29])
    fun `requests work through the pre-S compat path`() {
        val listener = RecordingListener()
        backend.requestUpdates(spec(DesiredAccuracy.HIGH, interval = 7_000, distance = 12f), listener)

        val request = shadowLm.getLegacyLocationRequests(GPS_PROVIDER).single()
        assertEquals(7_000L, request.intervalMillis)
        assertEquals(12f, request.minUpdateDistanceMeters)
        shadowLm.simulateLocation(GPS_PROVIDER, fix(GPS_PROVIDER))
        idleMain()
        assertEquals(1, listener.batches.size)
    }

    @Test
    fun `getCurrentLocation returns null on timeout and cancels the request`() = runTest {
        val result = backend.getCurrentLocation(DesiredAccuracy.HIGH, 5_000)
        idleMain()

        assertNull(result)
        assertEquals(0, listenersOn(GPS_PROVIDER))
    }

    @Test
    fun `getCurrentLocation returns null when no provider is enabled`() = runTest {
        shadowLm.setProviderEnabled(GPS_PROVIDER, false)
        shadowLm.setProviderEnabled(NETWORK_PROVIDER, false)

        assertNull(backend.getCurrentLocation(DesiredAccuracy.HIGH, 60_000))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `getCurrentLocation treats passive as low power`() = runTest {
        val result = async { backend.getCurrentLocation(DesiredAccuracy.PASSIVE, 10_000) }
        runCurrent()
        assertEquals(1, listenersOn(NETWORK_PROVIDER))

        shadowLm.simulateLocation(NETWORK_PROVIDER, fix(NETWORK_PROVIDER))
        idleMain()

        assertNotNull(result.await())
    }

    // ---- permission failures (the shadow does not enforce permissions, so these use a mocked LocationManager)

    private fun deniedManager(deniedProviders: Set<String>): LocationManager = mockk(relaxed = true) {
        every { hasProvider(any()) } answers { firstArg<String>() in setOf(GPS_PROVIDER, NETWORK_PROVIDER) }
        every { isProviderEnabled(any()) } returns true
        every { getProviders(true) } returns listOf(GPS_PROVIDER, NETWORK_PROVIDER)
        every { getLastKnownLocation(any()) } answers {
            if (firstArg<String>() in deniedProviders) throw SecurityException("denied") else null
        }
        every {
            requestLocationUpdates(
                any<String>(), any<LocationRequest>(), any<Executor>(), any<android.location.LocationListener>(),
            )
        } answers {
            if (firstArg<String>() in deniedProviders) throw SecurityException("denied")
        }
        every {
            getCurrentLocation(any<String>(), any<android.os.CancellationSignal>(), any<Executor>(), any())
        } answers {
            if (firstArg<String>() in deniedProviders) throw SecurityException("denied")
        }
    }

    private inline fun assertPermissionDenied(block: () -> Unit) {
        try {
            block()
            fail("expected PERMISSION_DENIED")
        } catch (e: TrackingException) {
            assertEquals(ErrorCode.PERMISSION_DENIED, e.code)
        }
    }

    @Test
    fun `SecurityException maps to PERMISSION_DENIED`() = runTest {
        val denied = AndroidLocationBackend(app, deniedManager(setOf(GPS_PROVIDER, NETWORK_PROVIDER)))

        assertPermissionDenied { denied.requestUpdates(spec(DesiredAccuracy.HIGH), RecordingListener()) }
        assertPermissionDenied { denied.getLastLocation() }
        assertPermissionDenied { denied.getCurrentLocation(DesiredAccuracy.HIGH, 1_000) }
    }

    @Test
    fun `a provider that refuses is skipped when another one accepts`() = runTest {
        val coarseOnly = deniedManager(setOf(GPS_PROVIDER))
        val backend = AndroidLocationBackend(app, coarseOnly)

        backend.requestUpdates(spec(DesiredAccuracy.HIGH), RecordingListener())
        assertNull(backend.getLastLocation())

        verify {
            coarseOnly.requestLocationUpdates(
                NETWORK_PROVIDER, any<LocationRequest>(), any<Executor>(), any<android.location.LocationListener>(),
            )
        }
    }

    @Test
    fun `without a LocationManager everything is a no-op`() = runTest {
        val none = AndroidLocationBackend(app, locationManager = null)

        none.requestUpdates(spec(DesiredAccuracy.HIGH), RecordingListener())
        none.removeUpdates(RecordingListener())
        assertNull(none.getLastLocation())
        assertNull(none.getCurrentLocation(DesiredAccuracy.HIGH, 1_000))
    }
}
