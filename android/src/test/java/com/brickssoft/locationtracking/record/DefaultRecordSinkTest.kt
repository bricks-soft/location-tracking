package com.brickssoft.locationtracking.record

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.data.LocationStore
import com.brickssoft.locationtracking.heartbeat.HeartbeatScheduler
import com.brickssoft.locationtracking.http.HttpSyncer
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeHeartbeatScheduler
import com.brickssoft.locationtracking.testing.FakeHttpSyncer
import com.brickssoft.locationtracking.testing.FakeLocationStore
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.RecordingEventBus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class DefaultRecordSinkTest {
    private val calls = CopyOnWriteArrayList<String>()
    private val store = FakeLocationStore()
    private val configStore = FakeConfigStore()
    private val heartbeat = FakeHeartbeatScheduler()
    private val syncer = FakeHttpSyncer()
    private val events = RecordingEventBus()

    // Delegating wrappers that log the order in which the sink calls its collaborators.
    private val loggingStore = object : LocationStore by store {
        override suspend fun insert(record: Record) {
            calls += "insert"
            store.insert(record)
        }
    }
    private val loggingConfig = object : ConfigStore by configStore {
        override fun updateRuntime(transform: (RuntimeState) -> RuntimeState): RuntimeState {
            calls += "updateRuntime"
            return configStore.updateRuntime(transform)
        }
    }
    private val loggingHeartbeat = object : HeartbeatScheduler by heartbeat {
        override fun onRecordRecorded(record: Record) {
            calls += "heartbeat"
            heartbeat.onRecordRecorded(record)
        }
    }
    private val loggingSyncer = object : HttpSyncer by syncer {
        override fun onRecordInserted(record: Record) {
            calls += "sync"
            syncer.onRecordInserted(record)
        }
    }

    private val sink = DefaultRecordSink(loggingStore, loggingConfig, loggingHeartbeat, loggingSyncer, events).also {
        events.subscribe { event -> calls += "emit:${event::class.simpleName}" }
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `location record runs every step in order`() = runTest {
        val record = Fixtures.record(event = RecordEvent.LOCATION)

        val result = sink.submit(record)

        assertSame(record, result)
        assertEquals(listOf("insert", "updateRuntime", "heartbeat", "emit:Location", "sync"), calls)
        assertEquals(listOf(record), store.all)
        assertEquals(listOf(record), heartbeat.recorded)
        assertEquals(listOf(record), syncer.inserted)
    }

    @Test
    fun `runtime is updated from the record`() = runTest {
        val record = Fixtures.record(recordedAt = 5_000L, elapsedRealtimeMs = 7_000L, bootCount = 3)

        sink.submit(record)

        val runtime = configStore.runtime.value
        assertEquals(5_000L, runtime.lastRecordAt)
        assertEquals(7_000L, runtime.lastRecordElapsed)
        assertEquals(3, runtime.lastRecordBootCount)
        assertEquals(record.location, runtime.lastLocation)
        assertNull(runtime.lastHeartbeatAt)
    }

    @Test
    fun `heartbeat without location keeps the last location and sets lastHeartbeatAt`() = runTest {
        val previous = Fixtures.location(latitude = 1.0)
        configStore.runtimeFlow.value = RuntimeState(lastLocation = previous)
        val record = Fixtures.record(event = RecordEvent.HEARTBEAT, location = null, recordedAt = 9_000L)

        sink.submit(record)

        assertEquals(previous, configStore.runtime.value.lastLocation)
        assertEquals(9_000L, configStore.runtime.value.lastHeartbeatAt)
        assertEquals(listOf("insert", "updateRuntime", "heartbeat", "emit:Heartbeat", "sync"), calls)
    }

    @Test
    fun `motionchange emits location then motionchange`() = runTest {
        val record = Fixtures.record(event = RecordEvent.MOTIONCHANGE, isMoving = false)

        sink.submit(record)

        assertEquals(
            listOf(TrackingEvent.Location(record), TrackingEvent.MotionChange(false, record)),
            events.events,
        )
    }

    @Test
    fun `positions emit location events`() = runTest {
        sink.submit(Fixtures.record(uuid = "a", event = RecordEvent.CURRENT_POSITION))
        sink.submit(Fixtures.record(uuid = "b", event = RecordEvent.WATCH_POSITION))

        assertEquals(listOf("a", "b"), events.ofType<TrackingEvent.Location>().map { it.record.uuid })
    }

    @Test
    fun `producer-emitted events are not emitted by the sink`() = runTest {
        for (event in listOf(
            RecordEvent.GEOFENCE,
            RecordEvent.TRACKING_START,
            RecordEvent.TRACKING_STOP,
            RecordEvent.PROVIDERCHANGE,
        )) {
            sink.submit(Fixtures.record(uuid = event.wire, event = event))
        }

        assertTrue(events.events.isEmpty())
        assertEquals(4, store.all.size)
        assertEquals(4, syncer.inserted.size)
    }

    @Test
    fun `insert failure still updates runtime and emits but skips sync`() = runTest {
        store.failInsertWith = IOException("disk full")
        val record = Fixtures.record()

        sink.submit(record)

        assertEquals(listOf("insert", "updateRuntime", "heartbeat", "emit:Location"), calls)
        assertTrue(syncer.inserted.isEmpty())
        assertEquals(record.recordedAt, configStore.runtime.value.lastRecordAt)
    }
}
