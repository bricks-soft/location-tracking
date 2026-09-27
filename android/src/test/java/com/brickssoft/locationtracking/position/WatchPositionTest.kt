package com.brickssoft.locationtracking.position

import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.permission.PermissionState
import com.brickssoft.locationtracking.permission.PermissionType
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.LocationListener
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import com.brickssoft.locationtracking.record.RecordSink
import com.brickssoft.locationtracking.testing.FakeLocationBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class WatchPositionTest {
    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun TestScope.harness(
        config: Config = Config(),
        sink: RecordSink? = null,
        backendOverride: LocationBackend? = null,
    ) = PositionHarness(testScheduler, backgroundScope, config, sink, backendOverride)

    private fun PositionHarness.listenerOf(index: Int = 0): LocationListener = backend.requests[index].second

    @Test
    fun `each fix becomes a watch_position record delivered to the callback`() = runTest {
        val h = harness()
        val got = Deliveries()

        h.service.watchPosition(
            "w1",
            WatchPositionOptions(intervalMs = 4_000, desiredAccuracy = DesiredAccuracy.BALANCED, extras = """{"a":1}"""),
            got,
        )
        assertEquals(LocationRequestSpec(DesiredAccuracy.BALANCED, 4_000L, 2_000L, 0f), h.backend.active.values.single())

        h.backend.emit(h.fix(5f), h.fix(6f))
        runCurrent()
        h.backend.emit(h.fix(7f))
        runCurrent()

        assertEquals(listOf(5f, 6f, 7f), got.records.map { it.location!!.accuracy })
        assertTrue(got.errors.isEmpty())
        assertTrue(got.records.all { it.event == RecordEvent.WATCH_POSITION && it.extras == """{"a":1}""" })
        assertTrue("persist defaults to false", h.sink.records.isEmpty())
    }

    @Test
    fun `default options request high accuracy every second`() = runTest {
        val h = harness()

        h.service.watchPosition("w1", WatchPositionOptions(), Deliveries())

        assertEquals(LocationRequestSpec(DesiredAccuracy.HIGH, 1_000L, 500L, 0f), h.backend.active.values.single())
    }

    @Test
    fun `persist submits each record to the sink before the callback`() = runTest {
        val h = harness()
        val got = Deliveries()

        h.service.watchPosition("w1", WatchPositionOptions(persist = true), got)
        h.backend.emit(h.fix(5f), h.fix(9f))
        runCurrent()

        assertEquals(2, h.sink.records.size)
        assertEquals(h.sink.records.toList(), got.records)
        assertTrue(h.sink.records.all { it.event == RecordEvent.WATCH_POSITION })
    }

    @Test
    fun `slow persistence keeps the fix order`() = runTest {
        val sink = DelayingSink(delayMs = 100)
        val h = harness(sink = sink)
        val got = Deliveries()

        h.service.watchPosition("w1", WatchPositionOptions(persist = true), got)
        h.backend.emit(h.fix(1f))
        runCurrent()
        h.backend.emit(h.fix(2f), h.fix(3f))
        runCurrent()
        assertTrue(got.all.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf(1f, 2f, 3f), got.records.map { it.location!!.accuracy })
        assertEquals(sink.records.toList(), got.records)
    }

    @Test
    fun `missing permission reports PERMISSION_DENIED and registers nothing`() = runTest {
        val h = harness()
        h.permissions.set(PermissionType.LOCATION, PermissionState.DENIED)
        val got = Deliveries()

        h.service.watchPosition("w1", WatchPositionOptions(), got)

        assertEquals(1, got.all.size)
        assertNull(got.all.single().first)
        assertEquals(ErrorCode.PERMISSION_DENIED, got.errors.single().code)
        assertTrue(h.backend.requests.isEmpty())
        assertFalse(h.service.clearWatch("w1"))
    }

    @Test
    fun `replacing a watch id clears the previous watch first`() = runTest {
        val h = harness()
        val first = Deliveries()
        val second = Deliveries()

        h.service.watchPosition("w1", WatchPositionOptions(intervalMs = 1_000), first)
        val firstListener = h.listenerOf(0)
        h.service.watchPosition("w1", WatchPositionOptions(intervalMs = 5_000), second)

        assertEquals(listOf(firstListener), h.backend.removed.toList())
        assertEquals(5_000L, h.backend.active.values.single().intervalMs)
        h.backend.emit(h.fix(4f))
        runCurrent()
        assertTrue(first.all.isEmpty())
        assertEquals(1, second.records.size)
    }

    @Test
    fun `replacing a watch without permission clears it and reports the error`() = runTest {
        val h = harness()
        val first = Deliveries()
        val second = Deliveries()
        h.service.watchPosition("w1", WatchPositionOptions(), first)

        h.permissions.set(PermissionType.LOCATION, PermissionState.DENIED)
        h.service.watchPosition("w1", WatchPositionOptions(), second)

        assertFalse(h.backend.isRequesting)
        assertEquals(ErrorCode.PERMISSION_DENIED, second.errors.single().code)
        assertFalse(h.service.clearWatch("w1"))
    }

    @Test
    fun `clearWatch removes the backend listener and stops deliveries`() = runTest {
        val h = harness()
        val got = Deliveries()
        h.service.watchPosition("w1", WatchPositionOptions(), got)
        val listener = h.listenerOf()
        h.backend.emit(h.fix(5f))
        runCurrent()

        assertTrue(h.service.clearWatch("w1"))

        assertEquals(listOf(listener), h.backend.removed.toList())
        assertFalse(h.backend.isRequesting)
        listener.onLocations(listOf(h.fix(6f))) // a late fix from the backend
        runCurrent()
        assertEquals(1, got.all.size)
        assertFalse(h.service.clearWatch("w1"))
    }

    @Test
    fun `clearWatch of an unknown id returns false`() = runTest {
        val h = harness()

        assertFalse(h.service.clearWatch("nope"))
        assertTrue(h.backend.removed.isEmpty())
    }

    @Test
    fun `fixes queued before clearWatch are dropped`() = runTest {
        val h = harness()
        val got = Deliveries()
        h.service.watchPosition("w1", WatchPositionOptions(), got)

        h.backend.emit(h.fix(5f), h.fix(6f))
        h.service.clearWatch("w1")
        runCurrent()

        assertTrue(got.all.isEmpty())
    }

    @Test
    fun `a record being persisted during clearWatch is stored but not delivered`() = runTest {
        val sink = DelayingSink(delayMs = 100)
        val h = harness(sink = sink)
        val got = Deliveries()
        h.service.watchPosition("w1", WatchPositionOptions(persist = true), got)
        h.backend.emit(h.fix(5f))
        runCurrent()

        h.service.clearWatch("w1")
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, sink.records.size)
        assertTrue(got.all.isEmpty())
    }

    @Test
    fun `concurrent watches are independent`() = runTest {
        val h = harness()
        val a = Deliveries()
        val b = Deliveries()
        h.service.watchPosition("a", WatchPositionOptions(intervalMs = 1_000, persist = true), a)
        h.service.watchPosition("b", WatchPositionOptions(intervalMs = 10_000, extras = """{"w":"b"}"""), b)
        assertEquals(2, h.backend.active.size)

        h.backend.emit(h.fix(5f))
        runCurrent()
        assertEquals(1, a.records.size)
        assertEquals(1, b.records.size)
        assertEquals(listOf(a.records.single()), h.sink.records.toList())
        assertEquals("""{"w":"b"}""", b.records.single().extras)

        assertTrue(h.service.clearWatch("a"))
        h.backend.emit(h.fix(6f))
        runCurrent()

        assertEquals(1, a.records.size)
        assertEquals(2, b.records.size)
        assertEquals(10_000L, h.backend.active.values.single().intervalMs)
    }

    @Test
    fun `clearAllWatches removes every listener`() = runTest {
        val h = harness()
        val deliveries = List(3) { Deliveries() }
        deliveries.forEachIndexed { i, d -> h.service.watchPosition("w$i", WatchPositionOptions(), d) }
        assertEquals(3, h.backend.active.size)

        h.service.clearAllWatches()

        assertFalse(h.backend.isRequesting)
        assertEquals(h.backend.requests.map { it.second }.toSet(), h.backend.removed.toSet())
        h.backend.requests.forEach { it.second.onLocations(listOf(h.fix(5f))) }
        runCurrent()
        assertTrue(deliveries.all { it.all.isEmpty() })
        assertFalse(h.service.clearWatch("w0"))
    }

    @Test
    fun `mock fixes are skipped when mocks are rejected`() = runTest {
        val h = harness(PositionHarness.REJECT_MOCK)
        val got = Deliveries()
        h.service.watchPosition("w1", WatchPositionOptions(persist = true), got)

        h.backend.emit(h.fix(5f, isMock = true), h.fix(6f))
        runCurrent()

        assertEquals(listOf(6f), got.records.map { it.location!!.accuracy })
        assertEquals(1, h.sink.records.size)
    }

    @Test
    fun `mock rule follows config changes`() = runTest {
        val h = harness()
        val got = Deliveries()
        h.service.watchPosition("w1", WatchPositionOptions(), got)

        h.backend.emit(h.fix(5f, isMock = true))
        runCurrent()
        h.configStore.configFlow.value = PositionHarness.REJECT_MOCK
        h.backend.emit(h.fix(6f, isMock = true))
        runCurrent()

        assertEquals(listOf(5f), got.records.map { it.location!!.accuracy })
    }

    @Test
    fun `a throwing callback does not stop later deliveries`() = runTest {
        val h = harness()
        var calls = 0
        h.service.watchPosition("w1", WatchPositionOptions()) { _, _ ->
            calls++
            if (calls == 1) throw IllegalStateException("bridge gone")
        }

        h.backend.emit(h.fix(5f))
        runCurrent()
        h.backend.emit(h.fix(6f))
        runCurrent()

        assertEquals(2, calls)
    }

    @Test
    fun `record failures are reported as INTERNAL errors and the watch continues`() = runTest {
        val failingSink = object : RecordSink {
            var calls = 0

            override suspend fun submit(record: Record): Record {
                calls++
                if (calls == 1) throw IllegalStateException("disk full")
                return record
            }
        }
        val h = harness(sink = failingSink)
        val got = Deliveries()
        h.service.watchPosition("w1", WatchPositionOptions(persist = true), got)

        h.backend.emit(h.fix(5f), h.fix(6f))
        runCurrent()

        assertEquals(2, got.all.size)
        assertEquals(ErrorCode.INTERNAL, got.all[0].second!!.code)
        assertEquals(6f, got.all[1].first!!.location!!.accuracy)
    }

    @Test
    fun `a stray cancellation from the sink is reported and the watch continues`() = runTest {
        val cancellingSink = object : RecordSink {
            var calls = 0

            override suspend fun submit(record: Record): Record {
                calls++
                if (calls == 1) throw CancellationException("stray")
                return record
            }
        }
        val h = harness(sink = cancellingSink)
        val got = Deliveries()
        h.service.watchPosition("w1", WatchPositionOptions(persist = true), got)

        h.backend.emit(h.fix(5f), h.fix(6f))
        runCurrent()

        assertEquals(ErrorCode.INTERNAL, got.all[0].second!!.code)
        assertEquals(6f, got.all[1].first!!.location!!.accuracy)
    }

    @Test
    fun `the callback can clear its own watch`() = runTest {
        val h = harness()
        val got = Deliveries()
        h.service.watchPosition("w1", WatchPositionOptions()) { record, error ->
            got(record, error)
            h.service.clearWatch("w1")
        }

        h.backend.emit(h.fix(5f), h.fix(6f))
        runCurrent()

        assertEquals(1, got.all.size)
        assertFalse(h.backend.isRequesting)
    }

    @Test
    fun `a backlog behind a slow sink drops the oldest fixes`() = runTest {
        val sink = DelayingSink(delayMs = 100)
        val h = harness(sink = sink)
        val got = Deliveries()
        h.service.watchPosition("w1", WatchPositionOptions(persist = true), got)
        h.backend.emit(h.fix(1f))
        runCurrent() // the consumer is now busy persisting fix 1

        (2..70).forEach { h.backend.emit(h.fix(it.toFloat())) }
        advanceTimeBy(100_000)
        runCurrent()

        val delivered = got.records.map { it.location!!.accuracy.toInt() }
        assertEquals(listOf(1) + (7..70).toList(), delivered)
    }

    @Test
    fun `backend refusal is reported and nothing stays registered`() = runTest {
        val fake = FakeLocationBackend()
        val refusing = object : LocationBackend by fake {
            override fun requestUpdates(spec: LocationRequestSpec, listener: LocationListener) {
                throw SecurityException("no permission")
            }
        }
        val h = harness(backendOverride = refusing)
        val got = Deliveries()

        h.service.watchPosition("w1", WatchPositionOptions(), got)

        assertEquals(ErrorCode.PERMISSION_DENIED, got.errors.single().code)
        assertFalse(h.service.clearWatch("w1"))
    }

    @Test
    fun `listener is removed from the backend it was registered with`() = runTest {
        val h = harness()
        h.service.watchPosition("w1", WatchPositionOptions(), Deliveries())
        val listener = h.listenerOf()
        val other = FakeLocationBackend(ProviderKind.HMS)
        h.backendOverride = other // provider reselection: the factory now hands out another backend

        assertTrue(h.service.clearWatch("w1"))

        assertEquals(listOf(listener), h.backend.removed.toList())
        assertFalse(h.backend.isRequesting)
        assertTrue(other.removed.isEmpty())
    }

    @Test
    fun `registry stays consistent under concurrent watch and clear calls`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val h = PositionHarness(TestCoroutineScheduler(), scope)
            val threads = (0 until 8).map { t ->
                thread {
                    repeat(250) { i ->
                        val id = "w${(t + i) % 10}"
                        h.service.watchPosition(id, WatchPositionOptions()) { _, _ -> }
                        if (i % 3 == 0) h.service.clearWatch(id)
                        if (i % 50 == 0) h.backend.emit(h.fix(5f))
                    }
                }
            }
            threads.forEach { it.join() }
            assertTrue(h.backend.active.size <= 10)

            h.service.clearAllWatches()

            assertFalse(h.backend.isRequesting)
            assertEquals(h.backend.requests.size, h.backend.removed.size)
        } finally {
            scope.cancel()
        }
    }
}
