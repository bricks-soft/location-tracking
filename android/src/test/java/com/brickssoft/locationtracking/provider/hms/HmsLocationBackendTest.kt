package com.brickssoft.locationtracking.provider.hms

import android.os.Bundle
import android.os.Looper
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.LocationListener
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import com.huawei.hmf.tasks.TaskCompletionSource
import com.huawei.hms.location.FusedLocationProviderClient
import com.huawei.hms.location.HWLocation
import com.huawei.hms.location.LocationCallback
import com.huawei.hms.location.LocationRequest
import com.huawei.hms.location.LocationResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HmsLocationBackendTest {
    private val client = mockk<FusedLocationProviderClient>()
    private val requests = CopyOnWriteArrayList<Pair<LocationRequest, LocationCallback>>()
    private val removed = CopyOnWriteArrayList<LocationCallback>()
    private val looper: Looper = Looper.getMainLooper()
    private val loopers = CopyOnWriteArrayList<Looper>()
    private var clientCreations = 0
    private val backend = HmsLocationBackend({
        clientCreations++
        client
    })

    private val spec = LocationRequestSpec(DesiredAccuracy.HIGH, 1_000, 500, 10f)

    @Before
    fun setUp() {
        every { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) } answers {
            requests += firstArg<LocationRequest>() to secondArg<LocationCallback>()
            loopers += thirdArg<Looper>()
            Hms.done(null)
        }
        every { client.removeLocationUpdates(any<LocationCallback>()) } answers {
            removed += firstArg<LocationCallback>()
            Hms.done(null)
        }
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    // ---- request mapping

    @Test
    fun `every accuracy maps to its HMS priority`() {
        val expected = mapOf(
            DesiredAccuracy.HIGH to LocationRequest.PRIORITY_HIGH_ACCURACY,
            DesiredAccuracy.BALANCED to LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY,
            DesiredAccuracy.LOW to LocationRequest.PRIORITY_LOW_POWER,
            DesiredAccuracy.PASSIVE to LocationRequest.PRIORITY_NO_POWER,
        )
        assertEquals(DesiredAccuracy.entries.toSet(), expected.keys)
        for ((accuracy, priority) in expected) {
            backend.requestUpdates(LocationRequestSpec(accuracy, 5_000, 2_000, 25f), LocationListener { })
            val request = requests.last().first
            assertEquals(accuracy.name, priority, request.priority)
            assertEquals(5_000L, request.interval)
            assertEquals(2_000L, request.fastestInterval)
            assertEquals(25f, request.smallestDisplacement, 0f)
            assertEquals(LocationRequest.COORDINATE_TYPE_WGS84, request.coordinateType)
            assertEquals(Int.MAX_VALUE, request.numUpdates)
        }
        assertTrue(loopers.all { it === looper })
    }

    @Test
    fun `request values are clamped`() {
        val tooFast = buildLocationRequest(LocationRequestSpec(DesiredAccuracy.BALANCED, 1_000, 5_000, 10f))
        assertEquals(1_000L, tooFast.fastestInterval)

        val negative = buildLocationRequest(LocationRequestSpec(DesiredAccuracy.LOW, -1, -1, -3f))
        assertEquals(0L, negative.interval)
        assertEquals(0L, negative.fastestInterval)
        assertEquals(0f, negative.smallestDisplacement, 0f)
    }

    @Test
    fun `one-shot request asks for a single update`() {
        for (accuracy in DesiredAccuracy.entries) {
            val request = buildOneShotRequest(accuracy)
            assertEquals(hmsPriority(accuracy), request.priority)
            assertEquals(1, request.numUpdates)
            assertEquals(HmsLocationBackend.ONE_SHOT_INTERVAL_MS, request.interval)
            assertEquals(LocationRequest.COORDINATE_TYPE_WGS84, request.coordinateType)
        }
    }

    @Test
    fun `client is created lazily and once`() = runTest {
        assertEquals(ProviderKind.HMS, backend.kind)
        assertEquals(0, clientCreations)
        every { client.lastLocation } returns Hms.done(null)

        backend.requestUpdates(spec, LocationListener { })
        backend.getLastLocation()

        assertEquals(1, clientCreations)
    }

    // ---- listeners

    @Test
    fun `each listener gets its own callback and only its own fixes`() {
        val first = RecordingListener()
        val second = RecordingListener()
        backend.requestUpdates(spec, first)
        backend.requestUpdates(spec.copy(accuracy = DesiredAccuracy.BALANCED), second)

        assertEquals(2, requests.size)
        assertEquals(2, backend.activeListenerCount)
        val (firstCallback, secondCallback) = requests.map { it.second }
        firstCallback.onLocationResult(Hms.result(Hms.location(latitude = 1.0), Hms.location(latitude = 2.0)))
        secondCallback.onLocationResult(Hms.result(Hms.location(latitude = 3.0)))

        assertEquals(listOf(listOf(1.0, 2.0)), first.batches.map { batch -> batch.map { it.latitude } })
        assertEquals(listOf(listOf(3.0)), second.batches.map { batch -> batch.map { it.latitude } })
    }

    @Test
    fun `requesting again with the same listener replaces its request`() {
        val listener = RecordingListener()
        backend.requestUpdates(spec, listener)
        backend.requestUpdates(spec.copy(intervalMs = 10_000, fastestIntervalMs = 5_000), listener)

        assertEquals(2, requests.size)
        val (oldCallback, newCallback) = requests.map { it.second }
        assertEquals(listOf(oldCallback), removed)
        assertEquals(10_000L, requests.last().first.interval)
        assertEquals(1, backend.activeListenerCount)

        oldCallback.onLocationResult(Hms.result(Hms.location(latitude = 1.0)))
        newCallback.onLocationResult(Hms.result(Hms.location(latitude = 2.0)))

        assertEquals(listOf(2.0), listener.batches.flatten().map { it.latitude })
    }

    @Test
    fun `removeUpdates removes only that listener's callback`() {
        val first = RecordingListener()
        val second = RecordingListener()
        backend.requestUpdates(spec, first)
        backend.requestUpdates(spec, second)

        backend.removeUpdates(first)
        backend.removeUpdates(first)
        backend.removeUpdates(RecordingListener())

        assertEquals(listOf(requests[0].second), removed)
        assertEquals(1, backend.activeListenerCount)
        requests[0].second.onLocationResult(Hms.result(Hms.location()))
        requests[1].second.onLocationResult(Hms.result(Hms.location()))
        assertTrue(first.batches.isEmpty())
        assertEquals(1, second.batches.size)
    }

    @Test
    fun `empty or null results are not delivered`() {
        val listener = RecordingListener()
        backend.requestUpdates(spec, listener)
        val callback = requests.single().second

        callback.onLocationResult(null)
        callback.onLocationResult(Hms.result())

        assertTrue(listener.batches.isEmpty())
    }

    @Test
    fun `a failing listener does not break the callback`() {
        backend.requestUpdates(spec) { throw IllegalStateException("listener bug") }

        requests.single().second.onLocationResult(Hms.result(Hms.location()))

        assertEquals(1, backend.activeListenerCount)
    }

    @Test
    fun `synchronous request failure is logged and the listener forgotten`() {
        every { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) } throws
            SecurityException("no permission")
        val listener = RecordingListener()

        backend.requestUpdates(spec, listener)

        assertEquals(0, backend.activeListenerCount)
        backend.removeUpdates(listener)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `asynchronous request failure forgets the listener`() {
        val pending = TaskCompletionSource<Void>()
        every { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) } returns
            pending.task
        backend.requestUpdates(spec, RecordingListener())
        assertEquals(1, backend.activeListenerCount)

        pending.setException(Hms.apiException(HmsStatusCodes.PERMISSION_DENIED))

        assertEquals(0, backend.activeListenerCount)
    }

    @Test
    fun `a late failure of a replaced request keeps the new one`() {
        val firstTask = TaskCompletionSource<Void>()
        every { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) } returns
            firstTask.task andThen Hms.done(null)
        val listener = RecordingListener()
        backend.requestUpdates(spec, listener)
        backend.requestUpdates(spec, listener)

        firstTask.setException(Hms.apiException(HmsStatusCodes.PERMISSION_DENIED))

        assertEquals(1, backend.activeListenerCount)
    }

    @Test
    fun `a concurrent removeUpdates waits until the request is registered`() {
        val events = CopyOnWriteArrayList<String>()
        val listener = RecordingListener()
        lateinit var remover: Thread
        every { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) } answers {
            remover = Thread { backend.removeUpdates(listener) }.apply { start() }
            Thread.sleep(100) // give the remover every chance to overtake the registration
            events += "registered"
            Hms.done(null)
        }
        every { client.removeLocationUpdates(any<LocationCallback>()) } answers {
            events += "removed"
            Hms.done(null)
        }

        backend.requestUpdates(spec, listener)
        remover.join(5_000)

        assertEquals(listOf("registered", "removed"), events)
        assertEquals(0, backend.activeListenerCount)
    }

    // ---- last location

    @Test
    fun `getLastLocation maps the fix`() = runTest {
        val location = Hms.location(latitude = 24.5, longitude = 46.5, accuracy = 7f, time = 1_000L).apply {
            altitude = 612.3
            speed = 13.4f
            bearing = 271.5f
            verticalAccuracyMeters = 3f
        }
        every { client.lastLocation } returns Hms.done(location)

        val fix = backend.getLastLocation()!!

        assertEquals(TrackedLocation.from(location), fix)
        assertEquals(24.5, fix.latitude, 0.0)
        assertEquals(612.3, fix.altitude!!, 0.0)
        assertEquals(3f, fix.altitudeAccuracy)
        assertFalse(fix.isMock)
    }

    @Test
    fun `getLastLocation returns null without a fix`() = runTest {
        every { client.lastLocation } returns Hms.done(null)

        assertNull(backend.getLastLocation())
    }

    @Test
    fun `getLastLocation maps errors`() = runTest {
        every { client.lastLocation } throws SecurityException("denied")
        Hms.expectTrackingError(ErrorCode.PERMISSION_DENIED) { backend.getLastLocation() }

        every { client.lastLocation } returns Hms.failed(Hms.apiException(HmsStatusCodes.PERMISSION_DENIED))
        Hms.expectTrackingError(ErrorCode.PERMISSION_DENIED) { backend.getLastLocation() }

        every { client.lastLocation } returns Hms.failed(SecurityException("denied"))
        Hms.expectTrackingError(ErrorCode.PERMISSION_DENIED) { backend.getLastLocation() }

        every { client.lastLocation } returns Hms.failed(Hms.apiException(907135003))
        Hms.expectTrackingError(ErrorCode.UNAVAILABLE) { backend.getLastLocation() }
    }

    // ---- current location

    @Test
    fun `getCurrentLocation returns the fix and removes its callback`() = runTest {
        val result = async { backend.getCurrentLocation(DesiredAccuracy.BALANCED, 30_000) }
        runCurrent()
        val (request, callback) = requests.single()
        assertEquals(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY, request.priority)
        assertEquals(1, request.numUpdates)
        assertFalse(result.isCompleted)

        callback.onLocationResult(Hms.result(Hms.location(latitude = 1.0), Hms.location(latitude = 2.0)))

        assertEquals(2.0, result.await()!!.latitude, 0.0)
        assertEquals(listOf(callback), removed)
        assertEquals(0, backend.activeListenerCount)
    }

    @Test
    fun `getCurrentLocation times out with null and removes its callback`() = runTest {
        val result = backend.getCurrentLocation(DesiredAccuracy.HIGH, 5_000)

        assertNull(result)
        assertEquals(5_000L, currentTime)
        assertEquals(listOf(requests.single().second), removed)
    }

    @Test
    fun `getCurrentLocation times out while the request itself hangs`() = runTest {
        every { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) } answers {
            requests += firstArg<LocationRequest>() to secondArg<LocationCallback>()
            TaskCompletionSource<Void>().task
        }

        assertNull(backend.getCurrentLocation(DesiredAccuracy.HIGH, 1_000))
        assertEquals(listOf(requests.single().second), removed)
    }

    @Test
    fun `getCurrentLocation with a zero timeout returns null without requesting`() = runTest {
        assertNull(backend.getCurrentLocation(DesiredAccuracy.HIGH, 0))
        assertTrue(requests.isEmpty())
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `getCurrentLocation maps a failed request and still removes its callback`() = runTest {
        every { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) } answers {
            requests += firstArg<LocationRequest>() to secondArg<LocationCallback>()
            Hms.failed(Hms.apiException(HmsStatusCodes.PERMISSION_DENIED))
        }

        Hms.expectTrackingError(ErrorCode.PERMISSION_DENIED) { backend.getCurrentLocation(DesiredAccuracy.HIGH, 10_000) }

        assertEquals(listOf(requests.single().second), removed)
    }

    @Test
    fun `getCurrentLocation maps a synchronous SecurityException`() = runTest {
        every { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) } throws
            SecurityException("denied")

        Hms.expectTrackingError(ErrorCode.PERMISSION_DENIED) { backend.getCurrentLocation(DesiredAccuracy.HIGH, 10_000) }
        assertEquals(1, removed.size)
    }

    @Test
    fun `cancelling getCurrentLocation removes its callback`() = runTest {
        val result = async { backend.getCurrentLocation(DesiredAccuracy.HIGH, 30_000) }
        runCurrent()

        result.cancel()
        runCurrent()

        assertTrue(result.isCancelled)
        assertEquals(listOf(requests.single().second), removed)
    }

    @Test
    fun `current location requests do not disturb continuous listeners`() = runTest {
        val listener = RecordingListener()
        backend.requestUpdates(spec, listener)
        val result = async { backend.getCurrentLocation(DesiredAccuracy.HIGH, 30_000) }
        runCurrent()
        requests[1].second.onLocationResult(Hms.result(Hms.location(latitude = 9.0)))
        assertEquals(9.0, result.await()!!.latitude, 0.0)

        requests[0].second.onLocationResult(Hms.result(Hms.location(latitude = 1.0)))

        assertEquals(1, backend.activeListenerCount)
        assertEquals(listOf(1.0), listener.batches.flatten().map { it.latitude })
    }

    // ---- conversion

    @Test
    fun `HMS extras mark mock fixes and fill vertical accuracy`() {
        val location = Hms.location().apply {
            extras = Bundle().apply {
                putBoolean(FusedLocationProviderClient.KEY_MOCK_LOCATION, true)
                putFloat(FusedLocationProviderClient.KEY_VERTICAL_ACCURACY, 4.5f)
            }
        }

        val fix = toTrackedLocation(location)

        assertTrue(fix.isMock)
        assertEquals(4.5f, fix.altitudeAccuracy)
    }

    @Test
    fun `platform vertical accuracy wins over the HMS extra`() {
        val location = Hms.location().apply {
            verticalAccuracyMeters = 2f
            extras = Bundle().apply { putDouble(FusedLocationProviderClient.KEY_VERTICAL_ACCURACY, 9.0) }
        }

        assertEquals(2f, toTrackedLocation(location).altitudeAccuracy)
    }

    @Test
    fun `a location without extras converts like TrackedLocation from`() {
        val location = Hms.location()

        assertEquals(TrackedLocation.from(location), toTrackedLocation(location))
    }

    @Test
    fun `a real HMS LocationResult is delivered`() {
        val listener = RecordingListener()
        backend.requestUpdates(spec, listener)
        val hw = HWLocation().apply {
            latitude = 24.7
            longitude = 46.6
            accuracy = 8f
            time = 1_790_417_730_000L
            provider = "fused"
        }

        requests.single().second.onLocationResult(LocationResult.create(listOf(hw)))

        val fix = listener.batches.single().single()
        assertEquals(24.7, fix.latitude, 1e-9)
        assertEquals(46.6, fix.longitude, 1e-9)
        assertEquals(8f, fix.accuracy)
        assertEquals(1_790_417_730_000L, fix.time)
    }

    private class RecordingListener : LocationListener {
        val batches = CopyOnWriteArrayList<List<TrackedLocation>>()

        override fun onLocations(locations: List<TrackedLocation>) {
            batches += locations
        }
    }
}
