package com.brickssoft.locationtracking.position

import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.GeolocationConfig
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.permission.PermissionState
import com.brickssoft.locationtracking.permission.PermissionType
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.LocationListener
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import com.brickssoft.locationtracking.testing.FakeLocationBackend
import com.brickssoft.locationtracking.testing.Fixtures
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class GetCurrentPositionTest {
    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun TestScope.harness(config: Config = Config(), backendOverride: LocationBackend? = null) =
        PositionHarness(testScheduler, backgroundScope, config, backendOverride = backendOverride)

    /** Starts getCurrentPosition and runs it until it waits for fixes. */
    private fun TestScope.request(h: PositionHarness, o: CurrentPositionOptions = CurrentPositionOptions()): Deferred<Result<Record>> =
        async { runCatching { h.service.getCurrentPosition(o) } }.also { runCurrent() }

    private fun Result<Record>.errorCode(): ErrorCode? = (exceptionOrNull() as? TrackingException)?.code

    private fun assertListenerRemoved(backend: FakeLocationBackend) {
        assertFalse("listener still registered", backend.isRequesting)
        assertEquals(backend.requests.map { it.second }, backend.removed.toList())
    }

    // ---- preconditions

    @Test
    fun `permission denied rejects without touching the backend`() = runTest {
        val h = harness()
        h.permissions.set(PermissionType.LOCATION, PermissionState.DENIED)

        val result = runCatching { h.service.getCurrentPosition(CurrentPositionOptions(maximumAgeMs = 60_000)) }

        assertEquals(ErrorCode.PERMISSION_DENIED, result.errorCode())
        assertTrue(h.backend.requests.isEmpty())
        assertEquals(0, h.backend.lastLocationCalls)
        assertTrue(h.sink.records.isEmpty())
    }

    @Test
    fun `disabled location services reject with LOCATION_DISABLED`() = runTest {
        val h = harness()
        h.device.providerStateValue = Fixtures.providerState(enabled = false)
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = h.fix(5f))

        val result = runCatching { h.service.getCurrentPosition(CurrentPositionOptions(maximumAgeMs = 60_000)) }

        assertEquals(ErrorCode.LOCATION_DISABLED, result.errorCode())
        assertTrue(h.backend.requests.isEmpty())
        assertTrue(h.sink.records.isEmpty())
    }

    // ---- maximumAge short-circuit

    @Test
    fun `fresh runtime location is returned without sampling`() = runTest {
        val h = harness()
        val known = h.fix(accuracy = 40f, ageMs = 5_000)
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = known)

        val record = h.service.getCurrentPosition(CurrentPositionOptions(maximumAgeMs = 10_000))

        assertSame(known, record.location)
        assertEquals(RecordEvent.CURRENT_POSITION, record.event)
        assertTrue(h.backend.requests.isEmpty())
        assertEquals(listOf(record), h.sink.records)
    }

    @Test
    fun `age equal to maximumAge still counts as fresh`() = runTest {
        val h = harness()
        val known = h.fix(accuracy = 40f, ageMs = 10_000)
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = known)

        val record = h.service.getCurrentPosition(CurrentPositionOptions(maximumAgeMs = 10_000))

        assertSame(known, record.location)
        assertTrue(h.backend.requests.isEmpty())
    }

    @Test
    fun `fresher backend last location wins over the runtime one`() = runTest {
        val h = harness()
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = h.fix(accuracy = 5f, ageMs = 8_000))
        val backendFix = h.fix(accuracy = 30f, ageMs = 1_000)
        h.backend.lastLocation = backendFix

        val record = h.service.getCurrentPosition(CurrentPositionOptions(maximumAgeMs = 10_000))

        assertSame(backendFix, record.location)
        assertEquals(1, h.backend.lastLocationCalls)
        assertTrue(h.backend.requests.isEmpty())
    }

    @Test
    fun `fresher runtime location wins over the backend one`() = runTest {
        val h = harness()
        val runtimeFix = h.fix(accuracy = 30f, ageMs = 1_000)
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = runtimeFix)
        h.backend.lastLocation = h.fix(accuracy = 5f, ageMs = 8_000)

        val record = h.service.getCurrentPosition(CurrentPositionOptions(maximumAgeMs = 10_000))

        assertSame(runtimeFix, record.location)
    }

    @Test
    fun `stale known location falls back to sampling`() = runTest {
        val h = harness()
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = h.fix(accuracy = 5f, ageMs = 60_000))
        h.backend.lastLocation = h.fix(accuracy = 5f, ageMs = 30_000)

        val pending = request(h, CurrentPositionOptions(samples = 1, maximumAgeMs = 10_000))
        assertEquals(1, h.backend.requests.size)
        val live = h.fix(accuracy = 15f)
        h.backend.emit(live)
        runCurrent()

        assertSame(live, pending.await().getOrThrow().location)
        assertListenerRemoved(h.backend)
    }

    @Test
    fun `maximumAge 0 never uses a known location`() = runTest {
        val h = harness()
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = h.fix(accuracy = 5f, ageMs = 0))
        h.backend.lastLocation = h.fix(accuracy = 5f, ageMs = 0)

        val pending = request(h, CurrentPositionOptions(samples = 1, maximumAgeMs = 0))

        assertEquals(0, h.backend.lastLocationCalls)
        assertEquals(1, h.backend.requests.size)
        h.backend.emit(h.fix(accuracy = 25f))
        runCurrent()
        assertEquals(25f, pending.await().getOrThrow().location!!.accuracy)
    }

    @Test
    fun `fresh mock location is skipped when mocks are rejected`() = runTest {
        val h = harness(PositionHarness.REJECT_MOCK)
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = h.fix(accuracy = 5f, ageMs = 1_000, isMock = true))
        val olderReal = h.fix(accuracy = 20f, ageMs = 4_000)
        h.backend.lastLocation = olderReal

        val record = h.service.getCurrentPosition(CurrentPositionOptions(maximumAgeMs = 10_000))

        assertSame(olderReal, record.location)
    }

    @Test
    fun `only mock known locations lead to sampling when mocks are rejected`() = runTest {
        val h = harness(PositionHarness.REJECT_MOCK)
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = h.fix(accuracy = 5f, ageMs = 1_000, isMock = true))

        val pending = request(h, CurrentPositionOptions(samples = 1, maximumAgeMs = 10_000))

        assertEquals(1, h.backend.requests.size)
        h.backend.emit(h.fix(accuracy = 12f))
        runCurrent()
        assertFalse(pending.await().getOrThrow().location!!.isMock)
    }

    @Test
    fun `fresh mock location is used when mocks are allowed`() = runTest {
        val h = harness()
        val mock = h.fix(accuracy = 5f, ageMs = 1_000, isMock = true)
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = mock)

        val record = h.service.getCurrentPosition(CurrentPositionOptions(maximumAgeMs = 10_000))

        assertSame(mock, record.location)
        assertTrue(h.backend.requests.isEmpty())
    }

    @Test
    fun `failing backend last-location lookup is ignored`() = runTest {
        val fake = FakeLocationBackend()
        val failing = object : LocationBackend by fake {
            override suspend fun getLastLocation(): TrackedLocation? = throw IllegalStateException("boom")
        }
        val h = harness(backendOverride = failing)
        val known = h.fix(accuracy = 40f, ageMs = 2_000)
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = known)

        val record = h.service.getCurrentPosition(CurrentPositionOptions(maximumAgeMs = 10_000))

        assertSame(known, record.location)
    }

    @Test
    fun `hanging backend last-location lookup is bounded`() = runTest {
        val fake = FakeLocationBackend()
        val hanging = object : LocationBackend by fake {
            override suspend fun getLastLocation(): TrackedLocation? = awaitCancellation()
        }
        val h = harness(backendOverride = hanging)
        val known = h.fix(accuracy = 40f, ageMs = 2_000)
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = known)

        val pending = request(h, CurrentPositionOptions(maximumAgeMs = 60_000))
        assertFalse(pending.isCompleted)
        advanceTimeBy(DefaultPositionService.LAST_LOCATION_TIMEOUT_MS + 1)
        runCurrent()

        assertSame(known, pending.await().getOrThrow().location)
    }

    @Test
    fun `elapsed-realtime age catches a wall clock that was set back`() = runTest {
        val h = harness()
        // Wall time says 1 s old, but the fix was computed 60 s ago on the monotonic clock.
        val known = h.fix(accuracy = 5f, ageMs = 1_000).copy(elapsedRealtimeNanos = (h.clock.elapsedRealtime() - 60_000) * 1_000_000)
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = known)

        val pending = request(h, CurrentPositionOptions(samples = 1, maximumAgeMs = 10_000))

        assertEquals(1, h.backend.requests.size)
        h.backend.emit(h.fix(accuracy = 20f))
        runCurrent()
        assertEquals(20f, pending.await().getOrThrow().location!!.accuracy)
    }

    @Test
    fun `an elapsed timestamp from before a reboot does not make a fresh fix stale`() = runTest {
        val h = harness()
        // Elapsed timestamp ahead of the current elapsed clock (previous boot): the wall-clock age decides.
        val known = h.fix(accuracy = 5f, ageMs = 1_000).copy(elapsedRealtimeNanos = (h.clock.elapsedRealtime() + 5_000) * 1_000_000)
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = known)

        val record = h.service.getCurrentPosition(CurrentPositionOptions(maximumAgeMs = 10_000))

        assertSame(known, record.location)
    }

    @Test
    fun `backend cancelling its own last-location lookup is treated as no location`() = runTest {
        val fake = FakeLocationBackend()
        val cancelling = object : LocationBackend by fake {
            override suspend fun getLastLocation(): TrackedLocation? = throw CancellationException("task cancelled")
        }
        val h = harness(backendOverride = cancelling)
        val known = h.fix(accuracy = 40f, ageMs = 2_000)
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = known)

        val record = h.service.getCurrentPosition(CurrentPositionOptions(maximumAgeMs = 10_000))

        assertSame(known, record.location)
    }

    @Test
    fun `the timeout also covers the last-location lookup`() = runTest {
        val fake = FakeLocationBackend()
        val hanging = object : LocationBackend by fake {
            override suspend fun getLastLocation(): TrackedLocation? = awaitCancellation()
        }
        val h = harness(backendOverride = hanging)

        val pending = request(h, CurrentPositionOptions(maximumAgeMs = 60_000, timeoutMs = 8_000))
        advanceTimeBy(DefaultPositionService.LAST_LOCATION_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals("sampling starts after the bounded lookup", 1, fake.requests.size)
        advanceTimeBy(8_000 - DefaultPositionService.LAST_LOCATION_TIMEOUT_MS - 2)
        runCurrent()
        assertFalse(pending.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        assertEquals(ErrorCode.TIMEOUT, pending.await().errorCode())
        assertFalse(fake.isRequesting)
    }

    @Test
    fun `a lookup that uses up the whole timeout skips sampling`() = runTest {
        val fake = FakeLocationBackend()
        val hanging = object : LocationBackend by fake {
            override suspend fun getLastLocation(): TrackedLocation? = awaitCancellation()
        }
        val h = harness(backendOverride = hanging)

        val pending = request(h, CurrentPositionOptions(maximumAgeMs = 60_000, timeoutMs = 3_000))
        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(ErrorCode.TIMEOUT, pending.await().errorCode())
        assertTrue(fake.requests.isEmpty())
    }

    // ---- sampling

    @Test
    fun `historical fixes delivered after the request are ignored`() = runTest {
        val h = harness()
        val pending = request(h, CurrentPositionOptions(samples = 1))
        val nowElapsedNanos = h.clock.elapsedRealtime() * 1_000_000

        h.backend.emit(h.fix(accuracy = 3f).copy(elapsedRealtimeNanos = nowElapsedNanos - 600_000L * 1_000_000))
        runCurrent()
        assertFalse(pending.isCompleted)
        h.backend.emit(h.fix(accuracy = 30f).copy(elapsedRealtimeNanos = nowElapsedNanos - 500L * 1_000_000))
        runCurrent()

        assertEquals(30f, pending.await().getOrThrow().location!!.accuracy)
    }

    @Test
    fun `best of N samples is returned and the temporary listener is removed`() = runTest {
        val h = harness()
        val pending = request(h, CurrentPositionOptions(samples = 3, desiredAccuracy = DesiredAccuracy.BALANCED))

        assertEquals(
            listOf(LocationRequestSpec(DesiredAccuracy.BALANCED, 1_000L, 500L, 0f)),
            h.backend.requests.map { it.first },
        )
        h.backend.emit(h.fix(accuracy = 30f))
        runCurrent()
        h.backend.emit(h.fix(accuracy = 12f))
        runCurrent()
        assertFalse(pending.isCompleted)
        h.backend.emit(h.fix(accuracy = 20f))
        runCurrent()

        assertEquals(12f, pending.await().getOrThrow().location!!.accuracy)
        assertListenerRemoved(h.backend)
    }

    @Test
    fun `a batch of fixes counts every fix as a sample`() = runTest {
        val h = harness()
        val pending = request(h, CurrentPositionOptions(samples = 3))

        h.backend.emit(h.fix(accuracy = 40f), h.fix(accuracy = 15f), h.fix(accuracy = 25f))
        runCurrent()

        assertEquals(15f, pending.await().getOrThrow().location!!.accuracy)
        assertListenerRemoved(h.backend)
    }

    @Test
    fun `an accurate first fix stops sampling early`() = runTest {
        val h = harness()
        val pending = request(h, CurrentPositionOptions(samples = 5))

        h.backend.emit(h.fix(accuracy = 8f))
        runCurrent()

        assertTrue(pending.isCompleted)
        assertEquals(8f, pending.await().getOrThrow().location!!.accuracy)
        assertListenerRemoved(h.backend)
    }

    @Test
    fun `an accurate later fix stops sampling early`() = runTest {
        val h = harness()
        val pending = request(h, CurrentPositionOptions(samples = 5))

        h.backend.emit(h.fix(accuracy = 25f))
        runCurrent()
        assertFalse(pending.isCompleted)
        h.backend.emit(h.fix(accuracy = 10f))
        runCurrent()

        assertEquals(10f, pending.await().getOrThrow().location!!.accuracy)
        assertListenerRemoved(h.backend)
    }

    @Test
    fun `timeout without samples rejects with TIMEOUT`() = runTest {
        val h = harness(Config(geolocation = GeolocationConfig(locationTimeout = 7_000)))
        val pending = request(h)

        advanceTimeBy(6_999)
        runCurrent()
        assertFalse(pending.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        assertEquals(ErrorCode.TIMEOUT, pending.await().errorCode())
        assertListenerRemoved(h.backend)
        assertTrue(h.sink.records.isEmpty())
    }

    @Test
    fun `timeout option overrides the configured locationTimeout`() = runTest {
        val h = harness(Config(geolocation = GeolocationConfig(locationTimeout = 60_000)))
        val pending = request(h, CurrentPositionOptions(timeoutMs = 2_000))

        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(ErrorCode.TIMEOUT, pending.await().errorCode())
        assertListenerRemoved(h.backend)
    }

    @Test
    fun `timeout with one sample returns that sample`() = runTest {
        val h = harness()
        val pending = request(h, CurrentPositionOptions(samples = 3, timeoutMs = 5_000))

        advanceTimeBy(1_000)
        val only = h.fix(accuracy = 35f)
        h.backend.emit(only)
        runCurrent()
        assertFalse(pending.isCompleted)
        advanceTimeBy(4_001)
        runCurrent()

        val record = pending.await().getOrThrow()
        assertSame(only, record.location)
        assertEquals(listOf(record), h.sink.records)
        assertListenerRemoved(h.backend)
    }

    @Test
    fun `non-positive timeout without a known location rejects immediately`() = runTest {
        val h = harness()

        val result = runCatching { h.service.getCurrentPosition(CurrentPositionOptions(timeoutMs = 0)) }

        assertEquals(ErrorCode.TIMEOUT, result.errorCode())
        assertTrue(h.backend.requests.isEmpty())
    }

    @Test
    fun `samples below 1 behave like 1`() = runTest {
        val h = harness()
        val pending = request(h, CurrentPositionOptions(samples = 0))

        h.backend.emit(h.fix(accuracy = 50f))
        runCurrent()

        assertEquals(50f, pending.await().getOrThrow().location!!.accuracy)
    }

    @Test
    fun `mock fixes are ignored while sampling when mocks are rejected`() = runTest {
        val h = harness(PositionHarness.REJECT_MOCK)
        val pending = request(h, CurrentPositionOptions(samples = 2))

        h.backend.emit(h.fix(accuracy = 3f, isMock = true), h.fix(accuracy = 30f), h.fix(accuracy = 1f, isMock = true))
        runCurrent()
        assertFalse(pending.isCompleted)
        h.backend.emit(h.fix(accuracy = 20f))
        runCurrent()

        val location = pending.await().getOrThrow().location!!
        assertEquals(20f, location.accuracy)
        assertFalse(location.isMock)
    }

    @Test
    fun `only mock fixes time out when mocks are rejected`() = runTest {
        val h = harness(PositionHarness.REJECT_MOCK)
        val pending = request(h, CurrentPositionOptions(timeoutMs = 3_000))

        h.backend.emit(h.fix(accuracy = 3f, isMock = true))
        runCurrent()
        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(ErrorCode.TIMEOUT, pending.await().errorCode())
    }

    @Test
    fun `mock fixes are kept when mocks are allowed`() = runTest {
        val h = harness()
        val pending = request(h, CurrentPositionOptions(samples = 1))

        h.backend.emit(h.fix(accuracy = 30f, isMock = true))
        runCurrent()

        assertTrue(pending.await().getOrThrow().location!!.isMock)
    }

    @Test
    fun `cancelling the caller removes the temporary listener`() = runTest {
        val h = harness()
        val job = launch { h.service.getCurrentPosition(CurrentPositionOptions()) }
        runCurrent()
        assertTrue(h.backend.isRequesting)

        job.cancel()
        runCurrent()

        assertListenerRemoved(h.backend)
    }

    @Test
    fun `backend security exception maps to PERMISSION_DENIED and cleans up`() = runTest {
        val fake = FakeLocationBackend()
        val refusing = object : LocationBackend by fake {
            override fun requestUpdates(spec: LocationRequestSpec, listener: LocationListener) {
                throw SecurityException("no permission")
            }
        }
        val h = harness(backendOverride = refusing)

        val result = runCatching { h.service.getCurrentPosition(CurrentPositionOptions()) }

        assertEquals(ErrorCode.PERMISSION_DENIED, result.errorCode())
        assertEquals(1, fake.removed.size)
    }

    @Test
    fun `other backend failures map to UNAVAILABLE`() = runTest {
        val fake = FakeLocationBackend()
        val broken = object : LocationBackend by fake {
            override fun requestUpdates(spec: LocationRequestSpec, listener: LocationListener) {
                throw IllegalStateException("client gone")
            }
        }
        val h = harness(backendOverride = broken)

        val result = runCatching { h.service.getCurrentPosition(CurrentPositionOptions()) }

        assertEquals(ErrorCode.UNAVAILABLE, result.errorCode())
    }

    // ---- record + persistence

    @Test
    fun `persist submits the current_position record to the sink`() = runTest {
        val h = harness()
        val pending = request(h, CurrentPositionOptions(samples = 1, extras = """{"trip":7}"""))
        h.backend.emit(h.fix(accuracy = 20f))
        runCurrent()

        val record = pending.await().getOrThrow()

        assertEquals(RecordEvent.CURRENT_POSITION, record.event)
        assertEquals("""{"trip":7}""", record.extras)
        assertEquals(listOf(record), h.sink.records)
        assertEquals(listOf(record), h.recordFactory.created)
    }

    @Test
    fun `persist false returns the record without submitting it`() = runTest {
        val h = harness()
        val known = h.fix(accuracy = 20f, ageMs = 1_000)
        h.configStore.runtimeFlow.value = RuntimeState(lastLocation = known)

        val record = h.service.getCurrentPosition(
            CurrentPositionOptions(maximumAgeMs = 5_000, persist = false, extras = """{"a":1}"""),
        )

        assertEquals(RecordEvent.CURRENT_POSITION, record.event)
        assertSame(known, record.location)
        assertEquals("""{"a":1}""", record.extras)
        assertTrue(h.sink.records.isEmpty())
        assertEquals(listOf(record), h.recordFactory.created)
    }

    @Test
    fun `no record is created on failure`() = runTest {
        val h = harness()
        val pending = request(h, CurrentPositionOptions(timeoutMs = 1_000))
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(ErrorCode.TIMEOUT, pending.await().errorCode())
        assertTrue(h.recordFactory.created.isEmpty())
        assertNull(pending.await().getOrNull())
    }
}
