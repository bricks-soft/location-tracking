package com.brickssoft.locationtracking.provider.gms

import android.location.Location
import android.os.Looper
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.LocationListener
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GmsLocationBackendTest {
    private val client = mockk<FusedLocationProviderClient>()
    private val requests = CopyOnWriteArrayList<Pair<LocationRequest, LocationCallback>>()
    private val removed = CopyOnWriteArrayList<LocationCallback>()
    private lateinit var backend: GmsLocationBackend

    @Before
    fun setUp() {
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>())
        } answers {
            requests += firstArg<LocationRequest>() to secondArg<LocationCallback>()
            voidTask()
        }
        every { client.removeLocationUpdates(any<LocationCallback>()) } answers {
            removed += firstArg<LocationCallback>()
            voidTask()
        }
        backend = GmsLocationBackend(client)
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `kind is GMS`() {
        assertEquals(ProviderKind.GMS, backend.kind)
    }

    @Test
    fun `request maps every accuracy to its GMS priority`() {
        val expected = mapOf(
            DesiredAccuracy.HIGH to Priority.PRIORITY_HIGH_ACCURACY,
            DesiredAccuracy.BALANCED to Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            DesiredAccuracy.LOW to Priority.PRIORITY_LOW_POWER,
            DesiredAccuracy.PASSIVE to Priority.PRIORITY_PASSIVE,
        )
        for ((accuracy, priority) in expected) {
            val listener = LocationListener { }
            backend.requestUpdates(LocationRequestSpec(accuracy, 10_000, 5_000, 25f), listener)

            val request = requests.last().first
            assertEquals(accuracy.name, priority, request.priority)
            // GMS normalizes the interval of passive requests to "never actively compute".
            val interval = if (accuracy == DesiredAccuracy.PASSIVE) Long.MAX_VALUE else 10_000L
            assertEquals(accuracy.name, interval, request.intervalMillis)
            assertEquals(5_000L, request.minUpdateIntervalMillis)
            assertEquals(25f, request.minUpdateDistanceMeters, 0f)
        }
        assertEquals(4, requests.size)
    }

    @Test
    fun `request clamps fastest interval and distance`() {
        val request = GmsLocationBackend.buildLocationRequest(
            LocationRequestSpec(
                DesiredAccuracy.HIGH,
                intervalMs = 1_000,
                fastestIntervalMs = 5_000,
                distanceFilterM = -3f,
            ),
        )

        assertEquals(1_000L, request.intervalMillis)
        assertEquals(1_000L, request.minUpdateIntervalMillis)
        assertEquals(0f, request.minUpdateDistanceMeters, 0f)
    }

    @Test
    fun `updates use the main looper`() {
        val looper = slot<Looper>()
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), capture(looper))
        } returns voidTask()

        backend.requestUpdates(spec(), LocationListener { })

        assertSame(Looper.getMainLooper(), looper.captured)
    }

    @Test
    fun `each listener gets its own callback and receives only its fixes`() {
        val first = RecordingListener()
        val second = RecordingListener()

        backend.requestUpdates(spec(), first)
        backend.requestUpdates(spec(DesiredAccuracy.BALANCED), second)

        assertEquals(2, requests.size)
        val (_, firstCallback) = requests[0]
        val (_, secondCallback) = requests[1]
        assertNotSame(firstCallback, secondCallback)

        val batch = listOf(androidLocation(latitude = 1.0), androidLocation(latitude = 2.0))
        firstCallback.onLocationResult(LocationResult.create(batch))
        secondCallback.onLocationResult(LocationResult.create(listOf(androidLocation(latitude = 3.0))))

        assertEquals(listOf(listOf(1.0, 2.0)), first.batches.map { batch -> batch.map { it.latitude } })
        assertEquals(listOf(listOf(3.0)), second.batches.map { batch -> batch.map { it.latitude } })
        assertEquals("fused", first.batches.single().first().provider)
    }

    @Test
    fun `same listener replaces its request by reusing its callback`() {
        val listener = RecordingListener()

        backend.requestUpdates(spec(DesiredAccuracy.BALANCED), listener)
        backend.requestUpdates(spec(DesiredAccuracy.HIGH), listener)

        assertEquals(2, requests.size)
        assertSame(requests[0].second, requests[1].second)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, requests[1].first.priority)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `remove unregisters only that listener and stops its deliveries`() {
        val first = RecordingListener()
        val second = RecordingListener()
        backend.requestUpdates(spec(), first)
        backend.requestUpdates(spec(), second)
        val firstCallback = requests[0].second
        val secondCallback = requests[1].second

        backend.removeUpdates(first)

        assertEquals(listOf(firstCallback), removed.toList())
        firstCallback.onLocationResult(LocationResult.create(listOf(androidLocation())))
        secondCallback.onLocationResult(LocationResult.create(listOf(androidLocation())))
        assertTrue(first.batches.isEmpty())
        assertEquals(1, second.batches.size)
    }

    @Test
    fun `removing an unknown listener or removing twice does nothing`() {
        val listener = RecordingListener()
        backend.removeUpdates(listener)
        backend.requestUpdates(spec(), listener)
        backend.removeUpdates(listener)
        backend.removeUpdates(listener)

        assertEquals(1, removed.size)
    }

    @Test
    fun `listener can register again after removal with a new callback`() {
        val listener = RecordingListener()
        backend.requestUpdates(spec(), listener)
        backend.removeUpdates(listener)
        backend.requestUpdates(spec(), listener)

        assertNotSame(requests[0].second, requests[1].second)
        requests[1].second.onLocationResult(LocationResult.create(listOf(androidLocation())))
        assertEquals(1, listener.batches.size)
    }

    @Test
    fun `a throwing listener does not break the callback`() {
        backend.requestUpdates(spec(), LocationListener { throw IllegalStateException("listener bug") })

        requests.single().second.onLocationResult(LocationResult.create(listOf(androidLocation())))
    }

    @Test
    fun `synchronous SecurityException is logged, not thrown, and nothing is registered`() {
        every { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) } throws
            SecurityException("no location permission")
        val sink = RecordingLogSink()
        Logger.sink = sink
        val listener = RecordingListener()

        backend.requestUpdates(spec(), listener)

        val error = sink.at(LogLevel.ERROR).single()
        assertTrue(error.message, error.message.startsWith("PERMISSION_DENIED"))
        backend.removeUpdates(listener)
        verify(exactly = 0) { client.removeLocationUpdates(any<LocationCallback>()) }
    }

    @Test
    fun `failed replacement keeps the previous registration`() {
        val listener = RecordingListener()
        backend.requestUpdates(spec(), listener)
        val callback = requests.single().second
        every { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) } throws
            IllegalStateException("GoogleApiClient not connected")

        backend.requestUpdates(spec(DesiredAccuracy.LOW), listener)
        callback.onLocationResult(LocationResult.create(listOf(androidLocation())))
        backend.removeUpdates(listener)

        assertEquals(1, listener.batches.size)
        assertEquals(listOf(callback), removed.toList())
    }

    @Test
    fun `asynchronous request failure is only logged and the listener stays removable`() {
        every { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) } returns
            Tasks.forException(ApiException(Status(CommonStatusCodes.API_NOT_CONNECTED)))
        val sink = RecordingLogSink()
        Logger.sink = sink
        val listener = RecordingListener()

        backend.requestUpdates(spec(), listener)
        backend.removeUpdates(listener)

        assertTrue(sink.lines.any { it.message == "requestLocationUpdates failed" })
        assertEquals(1, removed.size)
    }

    @Test
    fun `getLastLocation maps the cached fix`() = runTest {
        every { client.lastLocation } returns Tasks.forResult(androidLocation(latitude = 10.0, accuracy = 7f))

        val location = backend.getLastLocation()

        assertEquals(10.0, location!!.latitude, 0.0)
        assertEquals(7f, location.accuracy, 0f)
    }

    @Test
    fun `getLastLocation returns null when GMS has none`() = runTest {
        every { client.lastLocation } returns Tasks.forResult(null)

        assertNull(backend.getLastLocation())
    }

    @Test
    fun `getLastLocation never throws, so a heartbeat can always be recorded`() = runTest {
        val sink = RecordingLogSink()
        Logger.sink = sink

        every { client.lastLocation } returns Tasks.forException(SecurityException("denied"))
        assertNull(backend.getLastLocation())
        every { client.lastLocation } returns
            Tasks.forException(ApiException(Status(CommonStatusCodes.API_NOT_CONNECTED)))
        assertNull(backend.getLastLocation())
        every { client.lastLocation } returns Tasks.forCanceled()
        assertNull(backend.getLastLocation())
        every { client.lastLocation } throws SecurityException("sync denied")
        assertNull(backend.getLastLocation())

        assertTrue(sink.at(LogLevel.WARN).first().message.startsWith("PERMISSION_DENIED"))
        assertEquals(4, sink.at(LogLevel.WARN).size)
    }

    @Test
    fun `getCurrentLocation builds a fresh request with the timeout as duration`() = runTest {
        val request = slot<CurrentLocationRequest>()
        every { client.getCurrentLocation(capture(request), any<CancellationToken>()) } returns
            Tasks.forResult(androidLocation(latitude = 5.0))

        val location = backend.getCurrentLocation(DesiredAccuracy.BALANCED, 12_000)

        assertEquals(5.0, location!!.latitude, 0.0)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, request.captured.priority)
        assertEquals(12_000L, request.captured.durationMillis)
        assertEquals(0L, request.captured.maxUpdateAgeMillis)
    }

    @Test
    fun `passive getCurrentLocation accepts a cached fix`() = runTest {
        val request = slot<CurrentLocationRequest>()
        every { client.getCurrentLocation(capture(request), any<CancellationToken>()) } returns
            Tasks.forResult(androidLocation())

        backend.getCurrentLocation(DesiredAccuracy.PASSIVE, 12_000)

        assertTrue(request.captured.maxUpdateAgeMillis > 0)
    }

    @Test
    fun `getCurrentLocation maps every accuracy`() = runTest {
        val requests = CopyOnWriteArrayList<CurrentLocationRequest>()
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any<CancellationToken>()) } answers {
            requests += firstArg<CurrentLocationRequest>()
            Tasks.forResult(androidLocation())
        }

        for (accuracy in DesiredAccuracy.entries) backend.getCurrentLocation(accuracy, 1_000)

        assertEquals(
            listOf(
                Priority.PRIORITY_HIGH_ACCURACY,
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                Priority.PRIORITY_LOW_POWER,
                Priority.PRIORITY_PASSIVE,
            ),
            requests.map { it.priority },
        )
    }

    @Test
    fun `getCurrentLocation returns null when GMS reports no fix`() = runTest {
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any<CancellationToken>()) } returns
            Tasks.forResult(null)

        assertNull(backend.getCurrentLocation(DesiredAccuracy.HIGH, 1_000))
    }

    @Test
    fun `getCurrentLocation times out with null and cancels the GMS request`() = runTest {
        val token = slot<CancellationToken>()
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), capture(token)) } returns
            TaskCompletionSource<Location>().task

        val result = async { backend.getCurrentLocation(DesiredAccuracy.HIGH, 30_000) }
        runCurrent()
        assertTrue(!token.captured.isCancellationRequested)
        advanceUntilIdle()

        assertNull(result.await())
        assertEquals(30_000L, testScheduler.currentTime)
        assertTrue(token.captured.isCancellationRequested)
    }

    @Test
    fun `getCurrentLocation returns null when GMS cancels the task`() = runTest {
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any<CancellationToken>()) } returns
            Tasks.forCanceled()

        assertNull(backend.getCurrentLocation(DesiredAccuracy.HIGH, 1_000))
    }

    @Test
    fun `cancelling the caller cancels the GMS request`() = runTest {
        val token = slot<CancellationToken>()
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), capture(token)) } returns
            TaskCompletionSource<Location>().task

        val result = async { backend.getCurrentLocation(DesiredAccuracy.HIGH, 30_000) }
        runCurrent()
        result.cancel()
        advanceUntilIdle()

        assertTrue(result.isCancelled)
        assertTrue(token.captured.isCancellationRequested)
    }

    @Test
    fun `getCurrentLocation with a non-positive timeout returns null without asking GMS`() = runTest {
        assertNull(backend.getCurrentLocation(DesiredAccuracy.HIGH, 0))
        verify(exactly = 0) { client.getCurrentLocation(any<CurrentLocationRequest>(), any<CancellationToken>()) }
    }

    @Test
    fun `getCurrentLocation maps failures`() = runTest {
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any<CancellationToken>()) } returns
            Tasks.forException(SecurityException("denied"))
        val denied = runCatching { backend.getCurrentLocation(DesiredAccuracy.HIGH, 1_000) }.exceptionOrNull()
        assertEquals(ErrorCode.PERMISSION_DENIED, (denied as TrackingException).code)

        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any<CancellationToken>()) } returns
            Tasks.forException(ApiException(Status(CommonStatusCodes.API_NOT_CONNECTED)))
        val unavailable = runCatching { backend.getCurrentLocation(DesiredAccuracy.HIGH, 1_000) }.exceptionOrNull()
        assertEquals(ErrorCode.UNAVAILABLE, (unavailable as TrackingException).code)

        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any<CancellationToken>()) } throws
            SecurityException("sync denied")
        val syncDenied = runCatching { backend.getCurrentLocation(DesiredAccuracy.HIGH, 1_000) }.exceptionOrNull()
        assertEquals(ErrorCode.PERMISSION_DENIED, (syncDenied as TrackingException).code)

        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any<CancellationToken>()) } throws
            IllegalStateException("not connected")
        val notConnected = runCatching { backend.getCurrentLocation(DesiredAccuracy.HIGH, 1_000) }.exceptionOrNull()
        assertEquals(ErrorCode.UNAVAILABLE, (notConnected as TrackingException).code)
    }

    private fun spec(accuracy: DesiredAccuracy = DesiredAccuracy.HIGH) = LocationRequestSpec(accuracy, 1_000, 500, 10f)

    private class RecordingListener : LocationListener {
        val batches = CopyOnWriteArrayList<List<TrackedLocation>>()

        override fun onLocations(locations: List<TrackedLocation>) {
            batches += locations
        }
    }
}
