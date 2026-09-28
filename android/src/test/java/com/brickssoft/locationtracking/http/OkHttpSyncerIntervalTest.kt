package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.ConnectivityType
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeDeviceMonitor
import com.brickssoft.locationtracking.testing.FakeLocationStore
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.RecordingEventBus
import com.brickssoft.locationtracking.testing.testDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * `http.syncInterval` (round 2 §4) with MockWebServer and virtual time: [clock] follows the test scheduler, so a
 * `delay` in the syncer's timer and the wall clock advance together.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OkHttpSyncerIntervalTest {
    private val server = MockWebServer()
    private val store = FakeLocationStore()
    private val device = FakeDeviceMonitor()
    private val events = RecordingEventBus()
    private val log = FakeLogStore()
    private val scheduler = TestCoroutineScheduler()

    /** Wall time at virtual time 0; `clock.now()` = [t0] + virtual time. */
    private val t0 = Fixtures.RECORDED_AT
    private val clock = FakeClock(now = t0, scheduler = scheduler)
    private lateinit var configStore: FakeConfigStore
    private val workerScopes = CopyOnWriteArrayList<CoroutineScope>()

    @Before
    fun setUp() {
        // Unexpected extra requests get an immediate 404 instead of blocking the test.
        (server.dispatcher as QueueDispatcher).setFailFast(true)
        server.start()
        configStore = FakeConfigStore(
            Config(http = HttpConfig(url = server.url("/locations").toString(), syncInterval = INTERVAL_S)),
            RuntimeState(enabled = true),
        )
        Logger.sink = log
    }

    @After
    fun tearDown() {
        workerScopes.forEach { it.cancel() }
        server.shutdown()
        Logger.sink = null
    }

    /**
     * Runs [body] on [scheduler]. The syncers' scopes are cancelled when [body] returns: `runTest` then advances the
     * scheduler until idle, which would otherwise run a timer still armed (against a server with no queued
     * responses, a failing retry that arms the next retry, forever in virtual time).
     */
    private fun test(body: suspend TestScope.() -> Unit) = runTest(StandardTestDispatcher(scheduler)) {
        try {
            body()
        } finally {
            workerScopes.forEach { it.cancel() }
        }
    }

    private fun http(transform: (HttpConfig) -> HttpConfig) = configStore.update { it.copy(http = transform(it.http)) }

    private fun tracking(enabled: Boolean) = configStore.updateRuntime { it.copy(enabled = enabled) }

    /** A syncer whose worker and timer run on the test scheduler (see OkHttpSyncerTest.syncer). */
    private fun TestScope.syncer(): OkHttpSyncer {
        val workerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        workerScopes += workerScope
        return OkHttpSyncer(
            configStore, store, device, events, clock, OkHttpClient(), testDispatchers(testScheduler), workerScope,
        )
    }

    /** What DefaultRecordSink does: insert, then notify the syncer; then run what is due now. */
    private suspend fun TestScope.insert(syncer: OkHttpSyncer, record: Record) {
        store.insert(record)
        syncer.onRecordInserted(record)
        runCurrent()
    }

    /** A record created now (virtual time), unless [recordedAt] is given. */
    private fun rec(i: Int, event: RecordEvent = RecordEvent.LOCATION, recordedAt: Long = clock.now()) =
        Fixtures.record(uuid = "r$i", event = event, recordedAt = recordedAt)

    private fun ok() = MockResponse().setResponseCode(200).setBody("ok")

    /** The next upload fails: its first try and its 3 retries (ending [RETRIES_MS] after the first try) get 503. */
    private fun failNextUpload() = repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** Advances to the next scheduled task, if any: the virtual time must not move (no timer is armed). */
    private fun TestScope.assertNoTimerArmed() {
        val before = testScheduler.currentTime
        advanceUntilIdle()
        assertEquals("a syncInterval timer is still armed", before, testScheduler.currentTime)
    }

    private fun takeRequest(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("no request")

    /** The records (uuid to sent_at) of one request, batch or single. */
    private fun recordsOf(request: RecordedRequest): List<Pair<String, String>> {
        val body = JSONObject(request.body.clone().readUtf8())
        val array = body.optJSONArray("location")
        val objects = if (array == null) {
            listOf(body.getJSONObject("location"))
        } else {
            (0 until array.length()).map { array.getJSONObject(it) }
        }
        return objects.map { it.getString("uuid") to it.getString("sent_at") }
    }

    private fun takeUuids(count: Int): List<List<String>> =
        (1..count).map { recordsOf(takeRequest()).map { record -> record.first } }

    private fun iso(virtualMs: Long) = Iso8601.format(t0 + virtualMs)

    // ---- the interval

    @Test
    fun `normal records are not uploaded before the oldest is syncInterval old`() = test {
        http { it.copy(batchSync = true) }
        val syncer = syncer()

        insert(syncer, rec(1))
        advance(60_000)
        insert(syncer, rec(2))
        advance(INTERVAL_MS - 60_000 - 1)

        assertEquals(0, server.requestCount)
        assertEquals(listOf("r1", "r2"), store.all.map { it.uuid })
    }

    @Test
    fun `the timer uploads the whole queue at oldest plus syncInterval without a further record`() = test {
        http { it.copy(batchSync = true) }
        server.enqueue(ok())
        val syncer = syncer()

        insert(syncer, rec(1))
        assertEquals(1, log.lines.count { it.level == LogLevel.DEBUG && it.message.startsWith(TIMER_ARMED) })
        advance(30_000)
        insert(syncer, rec(2))
        assertEquals(1, log.lines.count { it.message.startsWith(TIMER_ARMED) }) // same oldest record: timer kept
        advance(INTERVAL_MS - 30_000 - 1)
        assertEquals(0, server.requestCount)

        advance(1)

        val records = recordsOf(takeRequest())
        assertEquals(listOf("r1", "r2"), records.map { it.first })
        assertEquals(iso(INTERVAL_MS), records.first().second) // sent exactly at recorded_at(r1) + syncInterval
        assertTrue(store.all.isEmpty())
        assertNoTimerArmed()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an insert after the oldest became due uploads at once`() = test {
        repeat(4) { server.enqueue(ok()) }
        val syncer = syncer()
        insert(syncer, rec(1, recordedAt = t0 - INTERVAL_MS)) // exactly syncInterval old

        assertEquals(listOf(listOf("r1")), takeUuids(1))

        insert(syncer, rec(2))
        assertEquals(1, server.requestCount)
        store.insert(rec(3, recordedAt = t0 - INTERVAL_MS - 1)) // e.g. queued by an earlier process
        insert(syncer, rec(4))

        // Non-batch: the pass drains the whole queue, oldest first, one record per request.
        assertEquals(listOf(listOf("r3"), listOf("r2"), listOf("r4")), takeUuids(3))
        assertNoTimerArmed()
    }

    @Test
    fun `autoSyncThreshold caps the queue size before the interval`() = test {
        http { it.copy(autoSyncThreshold = 3, batchSync = true) }
        server.enqueue(ok())
        val syncer = syncer()

        insert(syncer, rec(1))
        advance(1_000)
        insert(syncer, rec(2))
        advance(1_000)
        assertEquals(0, server.requestCount)

        insert(syncer, rec(3))

        assertEquals(listOf(listOf("r1", "r2", "r3")), takeUuids(1))
        assertNoTimerArmed()
    }

    @Test
    fun `a priority record uploads at once and takes the normal records with it`() = test {
        http { it.copy(batchSync = true) }
        server.enqueue(ok())
        val syncer = syncer()

        insert(syncer, rec(1))
        advance(10_000)
        insert(syncer, rec(2, RecordEvent.HEARTBEAT))

        assertEquals(listOf(listOf("r1", "r2")), takeUuids(1))
        assertTrue(store.all.isEmpty())
        assertNoTimerArmed()
    }

    @Test
    fun `every priority event uploads at once`() = test {
        val priority = listOf(
            RecordEvent.HEARTBEAT, RecordEvent.TRACKING_START, RecordEvent.TRACKING_STOP, RecordEvent.PROVIDERCHANGE,
        )
        priority.forEach { _ -> server.enqueue(ok()) }
        val syncer = syncer()

        priority.forEachIndexed { i, event -> insert(syncer, rec(i, event)) }

        assertEquals(priority.indices.map { listOf("r$it") }, takeUuids(priority.size))
    }

    @Test
    fun `a negative age after the wall clock was set back counts as due`() = test {
        server.enqueue(ok())
        val syncer = syncer()

        insert(syncer, rec(1, recordedAt = t0 + 3_600_000))

        assertEquals(listOf(listOf("r1")), takeUuids(1))
        assertNoTimerArmed()
    }

    @Test
    fun `a wall clock set back while a record waits still uploads it when the timer fires`() = test {
        server.enqueue(ok())
        val syncer = syncer()
        insert(syncer, rec(1))

        clock.nowMs -= 3_600_000 // the user sets the clock back one hour; the timer runs on the monotonic clock
        advance(INTERVAL_MS)

        assertEquals(listOf(listOf("r1")), takeUuids(1))
    }

    @Test
    fun `a new oldest record replaces the timer`() = test {
        http { it.copy(batchSync = true) }
        server.enqueue(ok())
        val syncer = syncer()
        insert(syncer, rec(1)) // due at t0 + 120 s
        advance(10_000)

        insert(syncer, rec(0, recordedAt = t0 - 100_000)) // due at t0 + 20 s
        advance(10_000 - 1)
        assertEquals(0, server.requestCount)
        advance(1)

        val records = recordsOf(takeRequest())
        assertEquals(listOf("r0", "r1"), records.map { it.first })
        assertEquals(iso(20_000), records.first().second)
        assertNoTimerArmed() // the timer for t0 + 120 s was replaced, not left behind
    }

    @Test
    fun `changing syncInterval re-evaluates the armed timer`() = test {
        server.enqueue(ok())
        val syncer = syncer()
        insert(syncer, rec(1))
        advance(10_000)

        http { it.copy(syncInterval = 60) }
        advance(50_000 - 1)
        assertEquals(0, server.requestCount)
        advance(1)

        assertEquals(iso(60_000), recordsOf(takeRequest()).single().second)
        assertNoTimerArmed()
    }

    @Test
    fun `switching syncInterval off uploads the waiting records`() = test {
        server.enqueue(ok())
        val syncer = syncer()
        insert(syncer, rec(1))
        advance(10_000)

        http { it.copy(syncInterval = 0) }
        runCurrent()

        assertEquals(iso(10_000), recordsOf(takeRequest()).single().second)
        assertNoTimerArmed()
    }

    @Test
    fun `switching autoSync off and on again re-arms the timer`() = test {
        server.enqueue(ok())
        val syncer = syncer()
        insert(syncer, rec(1))
        advance(10_000)

        http { it.copy(autoSync = false) }
        runCurrent()
        assertNoTimerArmed()

        http { it.copy(autoSync = true) }
        runCurrent()
        advance(INTERVAL_MS - 10_000 - 1)
        assertEquals(0, server.requestCount)
        advance(1)

        assertEquals(iso(INTERVAL_MS), recordsOf(takeRequest()).single().second)
    }

    @Test
    fun `lowering autoSyncThreshold to the queue size uploads at once`() = test {
        http { it.copy(batchSync = true) }
        server.enqueue(ok())
        val syncer = syncer()
        insert(syncer, rec(1))
        insert(syncer, rec(2))

        http { it.copy(autoSyncThreshold = 2) }
        runCurrent()

        assertEquals(listOf(listOf("r1", "r2")), takeUuids(1))
        assertNoTimerArmed()
    }

    // ---- timer lifecycle and tracking state

    @Test
    fun `tracking stopped cancels the timer and held records follow the syncInterval 0 rule`() = test {
        server.enqueue(ok())
        val syncer = syncer()
        insert(syncer, rec(1))
        advance(10_000)

        tracking(false)
        runCurrent()

        assertEquals(iso(10_000), recordsOf(takeRequest()).single().second)
        assertNoTimerArmed()
    }

    @Test
    fun `tracking stopped while offline cancels the timer and nothing is sent`() = test {
        val syncer = syncer()
        insert(syncer, rec(1))
        device.connectivityValue = Connectivity(connected = false, type = ConnectivityType.NONE)

        tracking(false)
        runCurrent()

        assertNoTimerArmed()
        assertEquals(0, server.requestCount)
        assertEquals(1, store.all.size)
    }

    @Test
    fun `the timer is cancelled when the queue empties`() = test {
        server.enqueue(ok())
        val syncer = syncer()
        insert(syncer, rec(1))

        assertEquals(listOf("r1"), syncer.sync().map { it.uuid })

        assertNoTimerArmed()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `while tracking is off normal records upload at once and no timer is armed`() = test {
        tracking(false)
        server.enqueue(ok())
        val syncer = syncer()

        insert(syncer, rec(1, RecordEvent.CURRENT_POSITION))

        assertEquals(listOf(listOf("r1")), takeUuids(1))
        assertNoTimerArmed()
        assertTrue(log.lines.none { it.message.contains(TIMER_ARMED) }) // never armed, not armed and then ended
    }

    @Test
    fun `tracking switched on arms the timer for records already queued`() = test {
        tracking(false)
        device.connectivityValue = Connectivity(connected = false, type = ConnectivityType.NONE)
        server.enqueue(ok())
        val syncer = syncer()
        insert(syncer, rec(1)) // offline: stays queued
        assertNoTimerArmed()

        tracking(true)
        runCurrent()
        // The network is back, but no ConnectivityChange arrives (e.g. the monitor missed it): the timer uploads.
        device.connectivityValue = Connectivity(connected = true, type = ConnectivityType.WIFI)
        advance(INTERVAL_MS - 1)
        assertEquals(0, server.requestCount)
        advance(1)

        assertEquals(iso(INTERVAL_MS), recordsOf(takeRequest()).single().second)
        assertNoTimerArmed()
    }

    @Test
    fun `syncer start uploads the whole queue when the oldest is due`() = test {
        http { it.copy(batchSync = true, maxBatchSize = 1) }
        repeat(2) { server.enqueue(ok()) }
        store.insert(rec(1, recordedAt = t0 - INTERVAL_MS - 5_000)) // queued by an earlier process
        store.insert(rec(2, recordedAt = t0 - 5_000))
        val syncer = syncer()

        syncer.start()
        runCurrent()

        assertEquals(listOf(listOf("r1"), listOf("r2")), takeUuids(2))
        assertNoTimerArmed()
    }

    @Test
    fun `syncer start arms the timer for records that are not due yet`() = test {
        server.enqueue(ok())
        store.insert(rec(1, recordedAt = t0 - 5_000)) // queued by an earlier process
        val syncer = syncer()

        syncer.start()
        runCurrent()
        advance(INTERVAL_MS - 5_000 - 1)
        assertEquals(0, server.requestCount)
        advance(1)

        assertEquals(iso(INTERVAL_MS - 5_000), recordsOf(takeRequest()).single().second)
    }

    // ---- failed uploads

    @Test
    fun `a failed upload is retried once per syncInterval by the timer, not on every insert`() = test {
        http { it.copy(batchSync = true) }
        failNextUpload()
        server.enqueue(ok())
        val syncer = syncer()
        insert(syncer, rec(1))

        advance(INTERVAL_MS + RETRIES_MS)
        assertEquals(List(4) { listOf("r1") }, takeUuids(4))
        assertEquals(1, store.attempts("r1"))

        advance(1_000)
        insert(syncer, rec(2))
        advance(INTERVAL_MS - 1_000 - 1)
        assertEquals(4, server.requestCount)
        advance(1)

        // The next interval starts when the last try failed (R2-Q18).
        val retry = recordsOf(takeRequest())
        assertEquals(listOf("r1", "r2"), retry.map { it.first })
        assertEquals(iso(2 * INTERVAL_MS + RETRIES_MS), retry.first().second)
        assertTrue(store.all.isEmpty())
        assertNoTimerArmed()
    }

    @Test
    fun `connectivity regained retries a failed upload at once`() = test {
        failNextUpload()
        server.enqueue(ok())
        val syncer = syncer()
        syncer.start()
        insert(syncer, rec(1))
        advance(INTERVAL_MS + RETRIES_MS)
        takeUuids(4)

        advance(5_000)
        events.emit(TrackingEvent.ConnectivityChange(Connectivity(connected = true, type = ConnectivityType.WIFI)))
        runCurrent()

        assertEquals(iso(INTERVAL_MS + RETRIES_MS + 5_000), recordsOf(takeRequest()).single().second)
        assertTrue(store.all.isEmpty())
        assertNoTimerArmed()
    }

    @Test
    fun `a priority record is not held back by a pending retry`() = test {
        http { it.copy(batchSync = true) }
        failNextUpload()
        server.enqueue(ok())
        val syncer = syncer()
        insert(syncer, rec(1))
        advance(INTERVAL_MS + RETRIES_MS)
        takeUuids(4)

        advance(5_000)
        insert(syncer, rec(2, RecordEvent.HEARTBEAT))

        assertEquals(listOf(listOf("r1", "r2")), takeUuids(1))
        assertTrue(store.all.isEmpty())
        assertNoTimerArmed()
    }

    @Test
    fun `the retry wait is measured on elapsed time, so a wall clock set forward does not shorten it`() = test {
        http { it.copy(batchSync = true) }
        failNextUpload()
        server.enqueue(ok())
        val syncer = syncer()
        insert(syncer, rec(1))
        advance(INTERVAL_MS + RETRIES_MS)
        takeUuids(4)

        clock.nowMs += 3_600_000 // the wall clock jumps one hour forward; elapsed realtime does not
        insert(syncer, rec(2))
        assertEquals(4, server.requestCount)

        advance(INTERVAL_MS - 1)
        assertEquals(4, server.requestCount)
        advance(1)

        assertEquals(listOf("r1", "r2"), recordsOf(takeRequest()).map { it.first })
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun `offline at the due time waits for connectivity`() = test {
        server.enqueue(ok())
        val syncer = syncer()
        syncer.start()
        insert(syncer, rec(1))

        device.connectivityValue = Connectivity(connected = false, type = ConnectivityType.NONE)
        advance(INTERVAL_MS)
        assertEquals(0, server.requestCount)
        assertNoTimerArmed()

        advanceTimeBy(60_000)
        device.connectivityValue = Connectivity(connected = true, type = ConnectivityType.WIFI)
        events.emit(TrackingEvent.ConnectivityChange(device.connectivityValue))
        runCurrent()

        assertEquals(listOf(listOf("r1")), takeUuids(1))
    }

    // ---- unchanged rules

    @Test
    fun `syncInterval 0 keeps the previous behavior`() = test {
        http { it.copy(syncInterval = 0) }
        server.enqueue(ok())
        val syncer = syncer()

        insert(syncer, rec(1))
        assertEquals(listOf(listOf("r1")), takeUuids(1))
        assertNoTimerArmed()

        http { it.copy(autoSyncThreshold = 2, batchSync = true) }
        server.enqueue(ok())
        insert(syncer, rec(2))
        assertNoTimerArmed()
        assertEquals(1, server.requestCount)
        insert(syncer, rec(3))

        assertEquals(listOf(listOf("r2", "r3")), takeUuids(1))
    }

    @Test
    fun `autoSync off ignores syncInterval and arms no timer`() = test {
        http { it.copy(autoSync = false) }
        val syncer = syncer()

        insert(syncer, rec(1, recordedAt = t0 - INTERVAL_MS))

        assertNoTimerArmed()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `on restricted cellular normal records wait for wifi, priority records go at once`() = test {
        http { it.copy(disableAutoSyncOnCellular = true) }
        device.connectivityValue = Connectivity(connected = true, type = ConnectivityType.CELLULAR)
        repeat(2) { server.enqueue(ok()) }
        val syncer = syncer()
        syncer.start()
        insert(syncer, rec(1))

        advance(INTERVAL_MS) // the timer fires on cellular: nothing is sent and no new timer is armed
        assertEquals(0, server.requestCount)
        assertNoTimerArmed()

        insert(syncer, rec(2, RecordEvent.HEARTBEAT))
        assertEquals(listOf(listOf("r2")), takeUuids(1))
        assertEquals(listOf("r1"), store.all.map { it.uuid })

        device.connectivityValue = Connectivity(connected = true, type = ConnectivityType.WIFI)
        events.emit(TrackingEvent.ConnectivityChange(device.connectivityValue))
        runCurrent()

        assertEquals(listOf(listOf("r1")), takeUuids(1))
    }

    @Test
    fun `batch mode drains the whole queue in maxBatchSize chunks when the interval is due`() = test {
        http { it.copy(batchSync = true, maxBatchSize = 2) }
        repeat(3) { server.enqueue(ok()) }
        val syncer = syncer()
        for (i in 1..5) {
            insert(syncer, rec(i))
            advance(1_000)
        }
        assertEquals(0, server.requestCount)

        advance(INTERVAL_MS - 5_000)

        assertEquals(listOf(listOf("r1", "r2"), listOf("r3", "r4"), listOf("r5")), takeUuids(3))
        assertTrue(store.all.isEmpty())
        assertNoTimerArmed()
    }

    @Test
    fun `manual sync uploads records that are not yet due`() = test {
        server.enqueue(ok())
        val syncer = syncer()
        insert(syncer, rec(1))

        assertEquals(listOf("r1"), syncer.sync().map { it.uuid })
    }

    private companion object {
        const val INTERVAL_S = 120
        const val INTERVAL_MS = INTERVAL_S * 1_000L

        /** How long a failing upload keeps retrying after its first try: 2 + 4 + 8 s (R2-Q18). */
        const val RETRIES_MS = 14_000L

        /** Start of the debug log line the syncer writes when it arms the timer. */
        const val TIMER_ARMED = "syncInterval check armed"
    }
}
