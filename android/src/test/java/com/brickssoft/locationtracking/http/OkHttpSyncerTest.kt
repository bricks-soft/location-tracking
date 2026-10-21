package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.AuthorizationConfig
import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.config.HttpMethod
import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.data.LocationStore
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.ConnectivityType
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.GeofenceHit
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.RecordJson
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeDeviceMonitor
import com.brickssoft.locationtracking.testing.FakeLocationStore
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import com.brickssoft.locationtracking.testing.RecordingEventBus
import com.brickssoft.locationtracking.testing.testDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OkHttpSyncerTest {
    private val server = MockWebServer()
    private val store = FakeLocationStore()
    private val device = FakeDeviceMonitor()
    private val events = RecordingEventBus()
    private val log = FakeLogStore()

    /** 2026-09-26T10:15:45.001Z: 14.545 s after the default recorded_at. */
    private val clock = FakeClock(now = Fixtures.RECORDED_AT + 14_545)
    private lateinit var configStore: FakeConfigStore
    private lateinit var url: String

    @Before
    fun setUp() {
        // Unexpected extra requests get an immediate 404 instead of blocking the test.
        (server.dispatcher as QueueDispatcher).setFailFast(true)
        server.start()
        url = server.url("/locations").toString()
        configStore = FakeConfigStore(Config(http = HttpConfig(url = url)))
        Logger.sink = log
    }

    @After
    fun tearDown() {
        workerScopes.forEach { it.cancel() }
        server.shutdown()
        Logger.sink = null
    }

    private fun http(transform: (HttpConfig) -> HttpConfig) =
        configStore.update { it.copy(http = transform(it.http)) }

    /** Scopes of the syncers' workers; cancelled after each test. */
    private val workerScopes = CopyOnWriteArrayList<CoroutineScope>()

    /**
     * A syncer whose worker runs on the test scheduler. Not `backgroundScope`: `advanceUntilIdle()` does not wait for
     * background work, and the worker never completes, so it cannot be a child of the test scope either.
     */
    private fun TestScope.syncer(
        store: LocationStore = this@OkHttpSyncerTest.store,
        client: OkHttpClient = OkHttpClient(),
        random: () -> Double = { 0.5 },
    ): OkHttpSyncer {
        val workerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        workerScopes += workerScope
        // random 0.5: the retry waits without jitter (exactly 2, 4 and 8 s; 30 s after the first failed upload).
        return OkHttpSyncer(
            configStore, store, device, events, clock, client, testDispatchers(testScheduler), workerScope,
            nextTryStore, uploadScheduler, random,
        )
    }

    private val nextTryStore = NextTryStore.inMemory()
    private val uploadScheduler = RecordingUploadScheduler()

    /** Records what the syncer asks of the upload job. */
    /** The upload job at once: the safety net of an automatic upload while tracking is off. */
    private val JOB_NOW = "schedule(0 s)"

    private class RecordingUploadScheduler : UploadScheduler {
        val calls = CopyOnWriteArrayList<String>()

        override fun schedule(delayMs: Long, unmetered: Boolean) {
            calls += "schedule(${delayMs / 1_000} s${if (unmetered) ", unmetered" else ""})"
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    /** Moves wall and elapsed time past every stored wait (the longest is 15 minutes plus 20 %). */
    private fun passStoredWait() = clock.advance(20 * 60_000L)

    /** What DefaultRecordSink does: insert, then notify the syncer. */
    private suspend fun OkHttpSyncer.insert(record: Record) {
        store.insert(record)
        onRecordInserted(record)
    }

    private fun rec(i: Int, event: RecordEvent = RecordEvent.LOCATION) =
        Fixtures.record(uuid = "r$i", event = event, recordedAt = Fixtures.RECORDED_AT - 10_000 + i)

    private fun ok(body: String = "ok") = MockResponse().setResponseCode(200).setBody(body)

    private fun takeRequest(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("no request")

    /** The uuids in a request body (single record or batch under "location"); does not consume the body. */
    private fun uuidsOf(request: RecordedRequest): List<String> {
        val body = JSONObject(request.body.clone().readUtf8())
        val array = body.optJSONArray("location") ?: return listOf(body.getJSONObject("location").getString("uuid"))
        return (0 until array.length()).map { array.getJSONObject(it).getString("uuid") }
    }

    private fun takeUuids(count: Int): List<List<String>> = (1..count).map { uuidsOf(takeRequest()) }

    private val httpEvents get() = events.ofType<TrackingEvent.Http>().map { it.result }

    // ---- wire format

    @Test
    fun `single record body matches the architecture example`() = runTest {
        http { it.copy(params = """{"device_id":"abc"}""", headers = mapOf("X-Custom" to "1")) }
        server.enqueue(ok("""{"saved":true}"""))
        val record = Fixtures.record(extras = """{"driver_id":7}""")

        syncer().insert(record)
        advanceUntilIdle()

        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/locations", request.path)
        assertJsonEquals(BodyBuilderTest.GOLDEN_BODY, JSONObject(request.body.readUtf8()))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("1", request.getHeader("X-Custom"))
        assertTrue(store.all.isEmpty())
        val result = httpEvents.single()
        assertTrue(result.success)
        assertEquals(200, result.status)
        assertEquals("""{"saved":true}""", result.responseText)
        assertEquals(listOf(record.uuid), result.uuids)
    }

    @Test
    fun `onRecordInserted does not block`() = runTest {
        server.enqueue(ok())
        val syncer = syncer()

        syncer.insert(rec(1))

        assertEquals(0, server.requestCount)
        advanceUntilIdle()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `headers are Content-Type, then http headers, then the bearer token`() = runTest {
        http {
            it.copy(
                method = HttpMethod.PUT,
                headers = mapOf("X-A" to "a", "X-B" to "b"),
                authorization = AuthorizationConfig(accessToken = "tok"),
            )
        }
        server.enqueue(ok())

        syncer().insert(rec(1))
        advanceUntilIdle()

        val request = takeRequest()
        assertEquals("PUT", request.method)
        val names = (0 until request.headers.size).map { request.headers.name(it) }
        assertEquals(listOf("Content-Type", "X-A", "X-B", "Authorization"), names.take(4))
        assertEquals("Bearer tok", request.getHeader("Authorization"))
    }

    @Test
    fun `an Authorization header in http headers wins over the bearer token`() = runTest {
        http {
            it.copy(
                headers = mapOf("Authorization" to "Basic abc"),
                authorization = AuthorizationConfig(accessToken = "tok"),
            )
        }
        server.enqueue(ok())

        syncer().insert(rec(1))
        advanceUntilIdle()

        assertEquals(listOf("Basic abc"), takeRequest().headers.values("Authorization"))
    }

    @Test
    fun `rootProperty dot merges the record and params into the root`() = runTest {
        http { it.copy(rootProperty = ".", params = """{"device_id":"abc"}""") }
        server.enqueue(ok())
        val record = rec(1)

        syncer().insert(record)
        advanceUntilIdle()

        val expected = RecordJson.toJson(record, clock.now()).put("device_id", "abc")
        assertJsonEquals(expected, JSONObject(takeRequest().body.readUtf8()))
    }

    @Test
    fun `batch sync drains in chunks of maxBatchSize, oldest first`() = runTest {
        http { it.copy(batchSync = true, maxBatchSize = 2) }
        repeat(3) { server.enqueue(ok()) }
        val syncer = syncer()
        listOf(5, 3, 1, 4, 2).forEach { store.insert(rec(it)) }

        syncer.onRecordInserted(rec(2))
        advanceUntilIdle()

        assertEquals(listOf(listOf("r1", "r2"), listOf("r3", "r4"), listOf("r5")), takeUuids(3))
        assertTrue(store.all.isEmpty())
        assertEquals(listOf(listOf("r1", "r2"), listOf("r3", "r4"), listOf("r5")), httpEvents.map { it.uuids })
    }

    @Test
    fun `non-batch sync sends one request per record, oldest first`() = runTest {
        repeat(3) { server.enqueue(ok()) }
        val syncer = syncer()
        listOf(3, 1, 2).forEach { store.insert(rec(it)) }

        syncer.onRecordInserted(rec(3))
        advanceUntilIdle()

        assertEquals(listOf(listOf("r1"), listOf("r2"), listOf("r3")), takeUuids(3))
        assertEquals(3, httpEvents.size)
    }

    @Test
    fun `sent_at is the send time and recorded_at is unchanged`() = runTest {
        server.enqueue(ok())
        val record = Fixtures.record(recordedAt = Fixtures.RECORDED_AT - 3_600_000)

        syncer().insert(record)
        advanceUntilIdle()

        val body = JSONObject(takeRequest().body.readUtf8()).getJSONObject("location")
        assertEquals(Iso8601.format(clock.now()), body.getString("sent_at"))
        assertEquals(Iso8601.format(record.recordedAt), body.getString("recorded_at"))
    }

    // ---- policy

    @Test
    fun `heartbeat uploads immediately despite the threshold and drains the queue`() = runTest {
        http { it.copy(autoSyncThreshold = 10) }
        repeat(2) { server.enqueue(ok()) }
        val syncer = syncer()

        syncer.insert(rec(1))
        advanceUntilIdle()
        assertEquals(0, server.requestCount)

        val heartbeat = rec(2, RecordEvent.HEARTBEAT).copy(location = null)
        syncer.insert(heartbeat)
        advanceUntilIdle()

        assertEquals(listOf(listOf("r1"), listOf("r2")), takeUuids(2))
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun `priority records upload even with autoSync off`() = runTest {
        http { it.copy(autoSync = false) }
        server.enqueue(ok())

        syncer().insert(rec(1, RecordEvent.TRACKING_START))
        advanceUntilIdle()

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `on cellular with disableAutoSyncOnCellular only priority records are sent`() = runTest {
        http { it.copy(disableAutoSyncOnCellular = true, autoSyncThreshold = 10) }
        device.connectivityValue = Connectivity(connected = true, type = ConnectivityType.CELLULAR)
        repeat(2) { server.enqueue(ok()) }
        val syncer = syncer()
        store.insert(rec(1))
        store.insert(rec(3))

        syncer.insert(rec(2, RecordEvent.HEARTBEAT))
        syncer.insert(rec(4, RecordEvent.PROVIDERCHANGE))
        advanceUntilIdle()

        assertEquals(listOf(listOf("r2"), listOf("r4")), takeUuids(2))
        assertEquals(listOf("r1", "r3"), store.all.map { it.uuid })
        assertEquals(0, store.attempts("r1"))
    }

    @Test
    fun `normal records wait for the threshold`() = runTest {
        http { it.copy(autoSyncThreshold = 3, batchSync = true) }
        server.enqueue(ok())
        val syncer = syncer()

        syncer.insert(rec(1))
        syncer.insert(rec(2))
        advanceUntilIdle()
        assertEquals(0, server.requestCount)

        syncer.insert(rec(3))
        advanceUntilIdle()

        assertEquals(listOf(listOf("r1", "r2", "r3")), takeUuids(1))
    }

    @Test
    fun `autoSync off leaves normal records queued`() = runTest {
        http { it.copy(autoSync = false) }

        syncer().insert(rec(1))
        advanceUntilIdle()

        assertEquals(0, server.requestCount)
        assertEquals(1, store.all.size)
    }

    @Test
    fun `restricted cellular defers normal records until wifi returns`() = runTest {
        http { it.copy(disableAutoSyncOnCellular = true) }
        device.connectivityValue = Connectivity(connected = true, type = ConnectivityType.CELLULAR)
        server.enqueue(ok())
        val syncer = syncer()
        syncer.start()

        syncer.insert(rec(1))
        advanceUntilIdle()
        assertEquals(0, server.requestCount)

        device.connectivityValue = Connectivity(connected = true, type = ConnectivityType.WIFI)
        events.emit(TrackingEvent.ConnectivityChange(device.connectivityValue))
        advanceUntilIdle()

        assertEquals(1, server.requestCount)
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun `no url means nothing is uploaded`() = runTest {
        http { it.copy(url = null) }
        val syncer = syncer()
        syncer.start()

        syncer.insert(rec(1, RecordEvent.HEARTBEAT))
        advanceUntilIdle()

        assertEquals(0, server.requestCount)
        assertEquals(1, store.all.size)
    }

    @Test
    fun `invalid url means nothing is uploaded`() = runTest {
        http { it.copy(url = "not a url") }

        syncer().insert(rec(1, RecordEvent.HEARTBEAT))
        advanceUntilIdle()

        assertEquals(0, server.requestCount)
        assertTrue(log.lines.any { it.level == LogLevel.ERROR && it.message.contains("http.url") })
    }

    @Test
    fun `offline defers the upload until connectivity returns`() = runTest {
        device.connectivityValue = Connectivity(connected = false, type = ConnectivityType.NONE)
        server.enqueue(ok())
        val syncer = syncer()
        syncer.start()

        syncer.insert(rec(1, RecordEvent.HEARTBEAT))
        advanceUntilIdle()
        assertEquals(0, server.requestCount)
        assertEquals(0, store.attempts("r1"))

        device.connectivityValue = Connectivity(connected = true, type = ConnectivityType.WIFI)
        events.emit(TrackingEvent.ConnectivityChange(device.connectivityValue))
        advanceUntilIdle()

        assertEquals(1, server.requestCount)
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun `a connected event is trusted when the monitor still reports offline`() = runTest {
        device.connectivityValue = Connectivity(connected = false, type = ConnectivityType.NONE)
        server.enqueue(ok())
        val syncer = syncer()
        syncer.start()
        store.insert(rec(1))
        advanceUntilIdle()

        events.emit(TrackingEvent.ConnectivityChange(Connectivity(connected = true, type = ConnectivityType.WIFI)))
        advanceUntilIdle()

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `disconnect events do not trigger uploads and start is idempotent`() = runTest {
        server.enqueue(ok())
        val syncer = syncer()
        syncer.start()
        syncer.start()
        advanceUntilIdle()
        assertEquals(1, events.subscriberCount)

        store.insert(rec(1))
        events.emit(TrackingEvent.ConnectivityChange(Connectivity(connected = false, type = ConnectivityType.NONE)))
        advanceUntilIdle()

        assertEquals(0, server.requestCount)
    }

    // ---- failures and retries

    @Test
    fun `server error keeps records, marks the attempt and stops draining`() = runTest {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500).setBody("down")) }
        val syncer = syncer()
        store.insert(rec(1))
        store.insert(rec(2))

        syncer.insert(rec(3))
        advanceUntilIdle()

        assertEquals(List(4) { listOf("r1") }, takeUuids(4)) // the first try and 3 retries
        assertEquals(listOf("r1", "r2", "r3"), store.all.map { it.uuid })
        assertEquals(1, store.attempts("r1"))
        assertEquals(clock.now(), store.lastAttemptAt("r1"))
        assertEquals(0, store.attempts("r2"))
        assertEquals(4, httpEvents.size)
        httpEvents.forEach { result ->
            assertFalse(result.success)
            assertEquals(500, result.status)
            assertEquals("down", result.responseText)
            assertEquals(listOf("r1"), result.uuids)
        }
    }

    @Test
    fun `a failed try is retried after 2, 4 and 8 s`() = runTest {
        val sentAt = CopyOnWriteArrayList<Long>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                sentAt += testScheduler.currentTime
                return MockResponse().setResponseCode(503)
            }
        }
        val syncer = syncer()
        val start = testScheduler.currentTime

        syncer.insert(rec(1))
        advanceUntilIdle()

        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L), sentAt.map { it - start })
        assertEquals(1, store.attempts("r1"))
    }

    @Test
    fun `no answer, 5xx and 429 are retried in the same upload`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(ok())
        val syncer = syncer()

        syncer.insert(rec(1))
        advanceUntilIdle()

        assertEquals(List(4) { listOf("r1") }, takeUuids(4))
        assertEquals(listOf(0, 502, 429, 200), httpEvents.map { it.status })
        assertTrue(store.all.isEmpty())
        assertEquals(0, store.attempts("r1"))
    }

    @Test
    fun `other 4xx answers are not retried`() = runTest {
        listOf(400, 404, 413).forEach { server.enqueue(MockResponse().setResponseCode(it)) }
        val syncer = syncer()

        for (i in 1..3) {
            syncer.insert(rec(i))
            advanceUntilIdle()
            passStoredWait()
        }

        assertEquals(3, server.requestCount) // one request per insert, each for the oldest record
        assertEquals(listOf(400, 404, 413), httpEvents.map { it.status })
        assertEquals(3, store.attempts("r1"))
    }

    @Test
    fun `failed upload is retried when connectivity returns, with the original recorded_at`() = runTest {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }
        server.enqueue(ok())
        val syncer = syncer()
        syncer.start()
        val heartbeat = rec(1, RecordEvent.HEARTBEAT)

        syncer.insert(heartbeat)
        advanceUntilIdle()
        assertEquals(1, store.attempts("r1"))
        val first = JSONObject(takeRequest().body.readUtf8()).getJSONObject("location")
        repeat(3) { takeRequest() }

        clock.advance(120_000)
        events.emit(TrackingEvent.ConnectivityChange(Connectivity(connected = true, type = ConnectivityType.WIFI)))
        advanceUntilIdle()

        val retry = JSONObject(takeRequest().body.readUtf8()).getJSONObject("location")
        assertEquals(first.getString("recorded_at"), retry.getString("recorded_at"))
        assertEquals(Iso8601.format(clock.now()), retry.getString("sent_at"))
        assertTrue(store.all.isEmpty())
        assertEquals(listOf(false, false, false, false, true), httpEvents.map { it.success })
    }

    @Test
    fun `a failed upload waits for the stored next-try time, not the next insert`() = runTest {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }
        repeat(3) { server.enqueue(ok()) }
        val syncer = syncer()

        syncer.insert(rec(1))
        advanceUntilIdle()
        repeat(4) { takeRequest() }
        assertEquals(listOf(JOB_NOW, "schedule(30 s)"), uploadScheduler.calls)

        clock.advance(29_000)
        syncer.insert(rec(2))
        advanceUntilIdle()
        assertEquals(4, server.requestCount)

        clock.advance(1_000)
        syncer.insert(rec(3))
        advanceUntilIdle()
        assertEquals(listOf(listOf("r1"), listOf("r2"), listOf("r3")), takeUuids(3))
        assertTrue(store.all.isEmpty())
        assertEquals(listOf(JOB_NOW, "schedule(30 s)"), uploadScheduler.calls.take(2))
        assertNull(nextTryStore.load())
    }

    @Test
    fun `the stored wait doubles from 30 s up to 15 minutes, with up to 20 % jitter`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(400)
        }
        val syncer = syncer(random = { 0.0 }) // the largest downward jitter: 80 % of each wait
        syncer.insert(rec(1))
        advanceUntilIdle()
        for (i in 2..8) {
            passStoredWait()
            syncer.insert(rec(i))
            advanceUntilIdle()
        }

        assertEquals(
            listOf(24, 48, 96, 192, 384, 720, 720, 720).map { "schedule($it s)" },
            uploadScheduler.calls.filter { it.startsWith("schedule") && it != JOB_NOW },
        )
        assertEquals(8, nextTryStore.load()!!.failures)
    }

    @Test
    fun `Retry-After of a 503 is honored up to http maxRetryAfter`() = runTest {
        http { it.copy(maxRetryAfter = 600) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(503).setHeader("Retry-After", "3600")
        }
        val syncer = syncer()

        syncer.insert(rec(1))
        advanceUntilIdle()

        // Retry-After asks for longer than the next in-upload retry: the upload ends after its first try.
        assertEquals(1, server.requestCount)
        assertEquals(listOf(JOB_NOW, "schedule(600 s)"), uploadScheduler.calls)
        clock.advance(599_000)
        syncer.insert(rec(2))
        advanceUntilIdle()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `Retry-After as an HTTP date and shorter than the backoff`() {
        val now = Iso8601.parse("2026-09-26T10:00:00.000Z")!!
        assertEquals(90_000L, UploadBackoff.retryAfterMs("Sat, 26 Sep 2026 10:01:30 GMT", now, 3600))
        assertEquals(0L, UploadBackoff.retryAfterMs("Sat, 26 Sep 2026 09:00:00 GMT", now, 3600))
        assertEquals(120_000L, UploadBackoff.retryAfterMs(" 120 ", now, 3600))
        assertEquals(60_000L, UploadBackoff.retryAfterMs("120", now, 60))
        assertNull(UploadBackoff.retryAfterMs("soon", now, 3600))
        assertNull(UploadBackoff.retryAfterMs(null, now, 3600))
        assertEquals(30_000L, UploadBackoff.serverWaitMs(1, 0.5, retryAfterMs = 5_000L, minimumMs = 0))
        assertEquals(90_000L, UploadBackoff.serverWaitMs(1, 0.5, retryAfterMs = 90_000L, minimumMs = 0))
        assertTrue(UploadBackoff.serverWaitMs(1, 0.9999999, retryAfterMs = null, minimumMs = 0) in 35_990L..36_000L)
        assertEquals(24_000L, UploadBackoff.serverWaitMs(1, 0.0, retryAfterMs = null, minimumMs = 0))
        assertEquals(120_000L, UploadBackoff.serverWaitMs(1, 0.0, retryAfterMs = null, minimumMs = 120_000L))
    }

    @Test
    fun `audit records and sync do not wait for the stored next-try time`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))
        repeat(2) { server.enqueue(ok()) }
        val syncer = syncer()
        syncer.insert(rec(1))
        advanceUntilIdle()
        assertEquals(1, server.requestCount)

        syncer.insert(rec(2, RecordEvent.HEARTBEAT))
        advanceUntilIdle()
        assertEquals(listOf(listOf("r1"), listOf("r1"), listOf("r2")), takeUuids(3))
        assertTrue(store.all.isEmpty())
        // A success ends the wait.
        assertNull(nextTryStore.load())
        server.enqueue(ok())
        syncer.insert(rec(3))
        advanceUntilIdle()
        assertEquals(listOf(listOf("r3")), takeUuids(1))
    }

    @Test
    fun `connectivity regained ends a server wait at once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(ok())
        val syncer = syncer()
        syncer.start()
        syncer.insert(rec(1))
        advanceUntilIdle()
        takeRequest()

        events.emit(TrackingEvent.ConnectivityChange(Connectivity(connected = true, type = ConnectivityType.WIFI)))
        advanceUntilIdle()

        assertEquals(listOf(listOf("r1")), takeUuids(1))
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun `the stored wait survives a new syncer, also across a reboot by the wall clock`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))
        syncer().insert(rec(1))
        advanceUntilIdle()
        assertEquals(1, server.requestCount)

        clock.reboot()
        val next = syncer()
        clock.nowMs += 10_000
        next.insert(rec(2))
        advanceUntilIdle()
        assertEquals(1, server.requestCount)

        repeat(3) { server.enqueue(ok()) }
        clock.nowMs += 20_000
        next.insert(rec(3))
        advanceUntilIdle()
        assertEquals(4, server.requestCount)
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun `offline with records due schedules the upload job without delay, and an empty queue cancels it`() = runTest {
        device.connectivityValue = Connectivity(connected = false, type = ConnectivityType.NONE)
        val syncer = syncer()
        syncer.insert(rec(1))
        advanceUntilIdle()
        assertEquals(listOf("schedule(0 s)"), uploadScheduler.calls)

        device.connectivityValue = Connectivity(connected = true, type = ConnectivityType.WIFI)
        server.enqueue(ok())
        syncer.runScheduledPass()
        assertEquals(listOf(listOf("r1")), takeUuids(1))
        assertEquals(listOf("schedule(0 s)", "cancel"), uploadScheduler.calls)
    }

    @Test
    fun `normal records on restricted cellular schedule the job for an unmetered network`() = runTest {
        http { it.copy(disableAutoSyncOnCellular = true) }
        device.connectivityValue = Connectivity(connected = true, type = ConnectivityType.CELLULAR)
        val syncer = syncer()
        syncer.insert(rec(1))
        advanceUntilIdle()
        assertEquals(0, server.requestCount)
        assertEquals(listOf("schedule(0 s, unmetered)"), uploadScheduler.calls)
    }

    @Test
    fun `tracking stopped schedules the upload job as a safety net`() = runTest {
        val syncer = syncer()
        syncer.onTrackingStopped()
        assertEquals(listOf("schedule(0 s)"), uploadScheduler.calls)
        http { it.copy(url = null) }
        syncer.onTrackingStopped()
        assertEquals(1, uploadScheduler.calls.size)
    }

    @Test
    fun `a pooled connection idle for more than 4 s is not reused, so a connection the server closed does not fail a try`() =
        runTest {
            // Seen on API 34 (P-R10): Node.js closes idle connections after 5 s, and the shared client repeats no
            // request after a connection failure, so the reused connection failed with "unexpected end of stream".
            server.enqueue(ok().setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
            server.enqueue(ok())
            val syncer = syncer(client = HttpSupport.sharedClient())
            syncer.insert(rec(1))
            advanceUntilIdle()
            Thread.sleep(200) // the server's close reaches the client socket
            clock.advance(5_000)
            syncer.insert(rec(2))
            advanceUntilIdle()

            assertEquals(listOf(200, 200), httpEvents.map { it.status })
            assertTrue(store.all.isEmpty())
        }

    @Test
    fun `with tracking off an automatic upload schedules the job first, so a frozen or killed process cannot strand records`() =
        runTest {
            // Seen on API 34 (P-R03): the cached app freezer froze the process during a retry wait.
            server.enqueue(ok())
            val syncer = syncer()
            syncer.insert(rec(1))
            advanceUntilIdle()
            assertEquals(listOf(JOB_NOW, "cancel"), uploadScheduler.calls)
        }

    @Test
    fun `no safety-net job while tracking is on, nor in the pass the job runs`() = runTest {
        configStore.updateRuntime { it.copy(enabled = true) }
        server.enqueue(ok())
        val syncer = syncer()
        syncer.insert(rec(1))
        advanceUntilIdle()
        assertEquals(listOf("cancel"), uploadScheduler.calls)

        configStore.updateRuntime { it.copy(enabled = false) }
        uploadScheduler.calls.clear()
        store.insert(rec(2))
        server.enqueue(ok())
        syncer.runScheduledPass()
        assertTrue(store.all.isEmpty())
        assertEquals(listOf("cancel"), uploadScheduler.calls)
    }

    @Test
    fun `the job runs another pass when records arrived during a successful one`() = runTest {
        val syncer = syncer()
        val inserted = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!inserted.getAndSet(true)) runBlocking { store.insert(rec(2)) }
                return ok()
            }
        }
        store.insert(rec(1))

        syncer.runScheduledPass()

        assertEquals(listOf(listOf("r1"), listOf("r2")), takeUuids(2))
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun `a 302 to a page that answers 200 keeps the records`() = runTest {
        // Recommendation 1: OkHttp would POST, follow the redirect with a GET and get 200, and the records were deleted.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/login").toString()))
        server.enqueue(ok("<html>login</html>"))
        val syncer = syncer(client = OkHttpClient()) // a default client: the syncer itself must not follow redirects

        syncer.insert(rec(1))
        advanceUntilIdle()

        assertEquals(1, server.requestCount)
        assertEquals("POST", takeRequest().method)
        assertEquals(listOf("r1"), store.all.map { it.uuid })
        assertEquals(1, store.attempts("r1"))
        assertEquals(listOf(302), httpEvents.map { it.status })
        assertTrue(HttpSupport.sharedClient().let { !it.followRedirects && !it.followSslRedirects && !it.retryOnConnectionFailure })
    }

    @Test
    fun `an upload answer is read up to 16 KB into the http event`() = runTest {
        server.enqueue(ok("x".repeat(40_000)))
        val syncer = syncer()

        syncer.insert(rec(1))
        advanceUntilIdle()

        assertEquals(16_384, httpEvents.single().responseText.length)
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun `HTTP 413 halves the batch for the rest of the pass and the next pass uses maxBatchSize again`() = runTest {
        http { it.copy(batchSync = true, maxBatchSize = 4) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (uuidsOf(request).size > 2) MockResponse().setResponseCode(413) else ok()
        }
        val syncer = syncer()
        for (i in 1..5) store.insert(rec(i))

        syncer.insert(rec(6))
        advanceUntilIdle()

        assertEquals(
            listOf(listOf("r1", "r2", "r3", "r4"), listOf("r1", "r2"), listOf("r3", "r4"), listOf("r5", "r6")),
            takeUuids(4),
        )
        assertTrue(store.all.isEmpty())
        assertEquals(0, store.attempts("r1"))

        for (i in 7..9) store.insert(rec(i))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = ok()
        }
        syncer.insert(rec(10))
        advanceUntilIdle()
        assertEquals(listOf(listOf("r7", "r8", "r9", "r10")), takeUuids(1))
    }

    @Test
    fun `HTTP 413 for a single record is a failure like other 4xx answers`() = runTest {
        server.enqueue(MockResponse().setResponseCode(413))
        val syncer = syncer()

        syncer.insert(rec(1))
        advanceUntilIdle()

        assertEquals(1, server.requestCount)
        assertEquals(1, store.attempts("r1"))
        assertEquals(listOf(JOB_NOW, "schedule(30 s)"), uploadScheduler.calls)
    }

    @Test
    fun `network error keeps records and reports status 0`() = runTest {
        server.shutdown()

        syncer().insert(rec(1))
        advanceUntilIdle()

        assertEquals(1, store.attempts("r1"))
        assertEquals(4, httpEvents.size)
        assertTrue(httpEvents.all { !it.success && it.status == 0 })
    }

    @Test
    fun `http timeout is a network error`() = runTest {
        http { it.copy(timeout = 200) }
        server.enqueue(ok().setHeadersDelay(1, TimeUnit.SECONDS))
        server.enqueue(ok())

        syncer().insert(rec(1))
        advanceUntilIdle()

        assertEquals(listOf(0, 200), httpEvents.map { it.status }) // the timeout is retried like any network error
        assertTrue(store.all.isEmpty())
    }

    // ---- manual sync

    @Test
    fun `sync without url throws NO_URL`() = runTest {
        http { it.copy(url = " ") }
        store.insert(rec(1))

        val error = runCatching { syncer().sync() }.exceptionOrNull() as TrackingException

        assertEquals(ErrorCode.NO_URL, error.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sync uploads the whole queue ignoring autoSync, threshold and cellular`() = runTest {
        http { it.copy(autoSync = false, autoSyncThreshold = 50, disableAutoSyncOnCellular = true) }
        device.connectivityValue = Connectivity(connected = true, type = ConnectivityType.CELLULAR)
        repeat(3) { server.enqueue(ok()) }
        listOf(2, 3, 1).forEach { store.insert(rec(it)) }

        val uploaded = syncer().sync()

        assertEquals(listOf("r1", "r2", "r3"), uploaded.map { it.uuid })
        assertEquals(3, server.requestCount)
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun `sync of an empty queue returns nothing`() = runTest {
        assertEquals(emptyList<Record>(), syncer().sync())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sync reports HTTP_ERROR and NETWORK_ERROR`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422))
        store.insert(rec(1))
        val syncer = syncer()

        val httpError = runCatching { syncer.sync() }.exceptionOrNull() as TrackingException
        assertEquals(ErrorCode.HTTP_ERROR, httpError.code)
        assertTrue(httpError.message!!.contains("422"))

        server.shutdown()
        val networkError = runCatching { syncer.sync() }.exceptionOrNull() as TrackingException
        assertEquals(ErrorCode.NETWORK_ERROR, networkError.code)
        assertEquals(2, store.attempts("r1"))
    }

    // ---- JWT

    private fun jwtDispatcher(refreshBody: String = """{"accessToken":"new","refreshToken":"r2","expires_in":3600}""") =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/refresh" -> MockResponse().setBody(refreshBody)
                request.getHeader("Authorization") == "Bearer new" -> ok("accepted")
                else -> MockResponse().setResponseCode(401).setBody("expired")
            }
        }

    private fun jwt(accessToken: String? = "old", expires: Long = -1) = AuthorizationConfig(
        accessToken = accessToken,
        refreshToken = "r1",
        refreshUrl = server.url("/refresh").toString(),
        refreshPayload = """{"refresh_token":"{refreshToken}"}""",
        expires = expires,
    )

    @Test
    fun `401 refreshes the token once and retries with the new bearer`() = runTest {
        http { it.copy(authorization = jwt()) }
        server.dispatcher = jwtDispatcher()

        syncer().insert(rec(1))
        advanceUntilIdle()

        val first = takeRequest()
        val refresh = takeRequest()
        val retry = takeRequest()
        assertEquals("Bearer old", first.getHeader("Authorization"))
        assertEquals("/refresh", refresh.path)
        assertJsonEquals("""{"refresh_token":"r1"}""", JSONObject(refresh.body.readUtf8()))
        assertEquals("Bearer new", retry.getHeader("Authorization"))
        assertEquals(listOf("r1"), uuidsOf(retry))
        assertTrue(store.all.isEmpty())

        val auth = configStore.config.value.http.authorization!!
        assertEquals("new", auth.accessToken)
        assertEquals("r2", auth.refreshToken)
        assertEquals(clock.now() + 3_600_000, auth.expires)
        assertTrue(events.ofType<TrackingEvent.Authorization>().single().success)
        // One http event per request: the rejected one, then the retry.
        assertEquals(listOf(401 to false, 200 to true), httpEvents.map { it.status to it.success })
        assertEquals("expired", httpEvents[0].responseText)
        assertEquals("accepted", httpEvents[1].responseText)
        assertEquals(listOf(listOf("r1"), listOf("r1")), httpEvents.map { it.uuids })
        assertEquals(0, store.attempts("r1"))
        val order = events.events.filter { it is TrackingEvent.Http || it is TrackingEvent.Authorization }
        assertEquals(
            listOf(TrackingEvent.Http::class, TrackingEvent.Authorization::class, TrackingEvent.Http::class),
            order.map { it::class },
        )
    }

    @Test
    fun `a retry after refresh that hits a network error reports its own http event`() = runTest {
        http { it.copy(authorization = jwt()) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/refresh" -> MockResponse().setBody("""{"accessToken":"new"}""")
                request.getHeader("Authorization") == "Bearer new" ->
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                else -> MockResponse().setResponseCode(401)
            }
        }

        syncer().insert(rec(1))
        advanceUntilIdle()

        // The network error is retried 3 times with the new token, without another refresh.
        assertEquals(listOf(401, 0, 0, 0, 0), httpEvents.map { it.status })
        assertEquals(1, store.attempts("r1"))
        assertEquals(1, events.ofType<TrackingEvent.Authorization>().size)
    }

    @Test
    fun `one upload refreshes the token at most once`() = runTest {
        http { it.copy(authorization = jwt()) }
        val newTokenRequests = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/refresh" -> MockResponse().setBody("""{"accessToken":"new"}""")
                request.getHeader("Authorization") == "Bearer new" && newTokenRequests.getAndIncrement() == 0 ->
                    MockResponse().setResponseCode(503)
                else -> MockResponse().setResponseCode(401)
            }
        }

        syncer().insert(rec(1))
        advanceUntilIdle()

        // 401, refresh, 503 with the new token, retry after 2 s: 401 again ends the upload.
        assertEquals(listOf(401, 503, 401), httpEvents.map { it.status })
        assertEquals(1, events.ofType<TrackingEvent.Authorization>().size)
        assertEquals(4, server.requestCount)
        assertEquals(1, store.attempts("r1"))
    }

    @Test
    fun `a token refresh before the first try is not repeated by the retries`() = runTest {
        http { it.copy(authorization = jwt(expires = clock.now())) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(if (request.path == "/refresh") 500 else 503)
        }

        syncer().insert(rec(1))
        advanceUntilIdle()

        // The token stays expired (the refresh failed), but only the first try refreshes.
        assertEquals(listOf(503, 503, 503, 503), httpEvents.map { it.status })
        assertEquals(1, events.ofType<TrackingEvent.Authorization>().size)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `failed refresh after 401 keeps the records`() = runTest {
        http { it.copy(authorization = jwt()) }
        server.dispatcher = jwtDispatcher(refreshBody = """{"nope":true}""")

        syncer().insert(rec(1))
        advanceUntilIdle()

        assertEquals(2, server.requestCount)
        assertEquals(1, store.attempts("r1"))
        assertFalse(events.ofType<TrackingEvent.Authorization>().single().success)
        val result = httpEvents.single()
        assertFalse(result.success)
        assertEquals(401, result.status)
    }

    @Test
    fun `a 401 whose refresh fails waits 15 minutes, and new tokens from the app end the wait at once`() = runTest {
        // Recommendation 4: without the wait, every new fix sent one rejected upload and one rejected refresh.
        http { it.copy(authorization = jwt()) }
        server.dispatcher = jwtDispatcher(refreshBody = """{"nope":true}""")
        val syncer = syncer()
        syncer.start()
        syncer.insert(rec(1))
        advanceUntilIdle()
        assertEquals(2, server.requestCount)
        assertEquals(NextTry.Reason.AUTHORIZATION, nextTryStore.load()!!.reason)
        assertEquals(listOf(JOB_NOW, "schedule(900 s)"), uploadScheduler.calls)

        clock.advance(14 * 60_000L)
        syncer.insert(rec(2))
        advanceUntilIdle()
        assertEquals(2, server.requestCount)

        http { it.copy(authorization = jwt(accessToken = "new")) }
        advanceUntilIdle()
        assertEquals("Bearer new", takeRequest().let { takeRequest(); takeRequest() }.getHeader("Authorization"))
        assertTrue(store.all.isEmpty())
        assertNull(nextTryStore.load())
    }

    @Test
    fun `a retry inside one upload keeps the token it started with and stops when the app switches users`() = runTest {
        // Recommendation 2: user A's records must never be sent with user B's token.
        http { it.copy(authorization = jwt(accessToken = "userA")) }
        val tokens = CopyOnWriteArrayList<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                tokens += request.getHeader("Authorization")
                if (tokens.size == 1) {
                    configStore.update {
                        it.copy(http = it.http.copy(authorization = jwt(accessToken = "userB").copy(refreshToken = "rB")))
                    }
                }
                return MockResponse().setResponseCode(if (tokens.size == 1) 503 else 200)
            }
        }
        val syncer = syncer()

        syncer.insert(rec(1))
        advanceTimeBy(OkHttpSyncer.CHANGED_RETRY_MS - 1)

        assertEquals(listOf("Bearer userA"), tokens.toList())
        assertEquals(listOf("r1"), store.all.map { it.uuid })
        assertEquals(0, store.attempts("r1"))
        assertEquals(listOf(503), httpEvents.map { it.status })
        assertNull(nextTryStore.load())
    }

    @Test
    fun `after an upload stopped for new tokens, a new pass 10 s later sends the records with the new token`() = runTest {
        // The owner's rule of 2026-10-21: the app changed the token during an upload and http.authorization is set.
        http { it.copy(authorization = jwt(accessToken = "userA")) }
        val tokens = CopyOnWriteArrayList<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                tokens += request.getHeader("Authorization")
                if (tokens.size == 1) http { it.copy(authorization = jwt(accessToken = "userB")) }
                return MockResponse().setResponseCode(if (tokens.size == 1) 503 else 200)
            }
        }
        val syncer = syncer()

        syncer.insert(rec(1))
        advanceTimeBy(OkHttpSyncer.CHANGED_RETRY_MS - 1)
        assertEquals(listOf("Bearer userA"), tokens.toList())

        advanceTimeBy(2)
        advanceUntilIdle()

        assertEquals(listOf("Bearer userA", "Bearer userB"), tokens.toList())
        assertTrue(store.all.isEmpty())
        assertEquals(listOf(503, 200), httpEvents.map { it.status })
        assertEquals(listOf(JOB_NOW, "schedule(10 s)", JOB_NOW, "cancel"), uploadScheduler.calls)
    }

    @Test
    fun `the upload job during the 10 s pause after new tokens uploads nothing, the pass at the end does`() = runTest {
        // Seen on API 34 (P-R11): with tracking off the safety-net job started at once and sent the records 2 s after
        // the stop.
        http { it.copy(authorization = jwt(accessToken = "userA")) }
        val tokens = CopyOnWriteArrayList<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                tokens += request.getHeader("Authorization")
                if (tokens.size == 1) http { it.copy(authorization = jwt(accessToken = "userB")) }
                return MockResponse().setResponseCode(if (tokens.size == 1) 503 else 200)
            }
        }
        val syncer = syncer()
        syncer.insert(rec(1))
        advanceTimeBy(3_000) // the 503, the 2 s wait, then the stop before the retry

        syncer.runScheduledPass()
        syncer.insert(rec(2))
        advanceTimeBy(5_000)
        assertEquals(listOf("Bearer userA"), tokens.toList())

        advanceUntilIdle()
        assertEquals("Bearer userB", tokens.drop(1).distinct().single())
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun `after an upload stopped because the app removed the authorization, no new pass starts`() = runTest {
        // README logout order, step 3: the records stay queued for the next trigger.
        http { it.copy(authorization = jwt(accessToken = "userA")) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                http { it.copy(authorization = null) }
                return MockResponse().setResponseCode(503)
            }
        }
        val syncer = syncer()

        syncer.insert(rec(1))
        advanceUntilIdle()

        assertEquals(1, server.requestCount)
        assertEquals(listOf("r1"), store.all.map { it.uuid })
        assertEquals(listOf(JOB_NOW), uploadScheduler.calls) // the safety net only, no new pass
        assertNull(nextTryStore.load())
    }

    @Test
    fun `sync reports an authorization change during the upload and keeps the records`() = runTest {
        http { it.copy(authorization = jwt(accessToken = "userA")) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                configStore.update { it.copy(http = it.http.copy(authorization = null)) }
                return MockResponse().setResponseCode(401)
            }
        }
        val syncer = syncer()
        store.insert(rec(1))

        val error = runCatching { syncer.sync() }.exceptionOrNull() as TrackingException

        assertEquals(ErrorCode.HTTP_ERROR, error.code)
        assertTrue(error.message!!, error.message!!.contains("authorization changed"))
        assertEquals(listOf("r1"), store.all.map { it.uuid })
        advanceUntilIdle()
        assertEquals(1, server.requestCount) // the authorization was removed: no new pass
    }

    @Test
    fun `sync stopped for new tokens is followed by an automatic pass 10 s later`() = runTest {
        http { it.copy(authorization = jwt(accessToken = "userA")) }
        val tokens = CopyOnWriteArrayList<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                tokens += request.getHeader("Authorization")
                if (tokens.size == 1) http { it.copy(authorization = jwt(accessToken = "userB")) }
                return MockResponse().setResponseCode(if (tokens.size == 1) 401 else 200)
            }
        }
        val syncer = syncer()
        store.insert(rec(1))

        assertTrue(runCatching { syncer.sync() }.exceptionOrNull()!!.message!!.contains("authorization changed"))
        assertEquals(listOf("schedule(10 s)"), uploadScheduler.calls)
        advanceUntilIdle()

        assertEquals(listOf("Bearer userA", "Bearer userB"), tokens.toList())
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun `401 without authorization config is a plain failure`() = runTest {
        server.dispatcher = jwtDispatcher()

        syncer().insert(rec(1))
        advanceUntilIdle()

        assertEquals(1, server.requestCount)
        assertEquals(401, httpEvents.single().status)
        assertTrue(events.ofType<TrackingEvent.Authorization>().isEmpty())
    }

    @Test
    fun `expiring token is refreshed before the request`() = runTest {
        http { it.copy(authorization = jwt(expires = clock.now() + 30_000)) }
        server.dispatcher = jwtDispatcher()

        syncer().insert(rec(1))
        advanceUntilIdle()

        assertEquals("/refresh", takeRequest().path)
        assertEquals("Bearer new", takeRequest().getHeader("Authorization"))
        assertEquals(2, server.requestCount)
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun `a rejected freshly refreshed token is not refreshed again`() = runTest {
        http { it.copy(authorization = jwt(expires = clock.now())) }
        server.dispatcher = jwtDispatcher(refreshBody = """{"accessToken":"still-bad"}""")

        syncer().insert(rec(1))
        advanceUntilIdle()

        assertEquals(listOf("/refresh", "/locations"), (1..2).map { takeRequest().path })
        assertEquals(2, server.requestCount)
        assertEquals(1, store.attempts("r1"))
    }

    // ---- templates

    @Test
    fun `templates shape location and geofence records`() = runTest {
        http {
            it.copy(
                batchSync = true,
                locationTemplate = """{"lat":<%= latitude %>,"ts":"<%= timestamp %>","sent":"<%= sent_at %>",""" +
                    """"reason":<%= reason %>,"extras":<%= extras %>}""",
                geofenceTemplate = """{"fence":"<%= geofence.identifier %>","action":"<%= geofence.action %>"}""",
            )
        }
        server.enqueue(ok())
        val syncer = syncer()
        store.insert(rec(1).copy(extras = """{"k":"v"}"""))
        val geofence = rec(2, RecordEvent.GEOFENCE).copy(geofence = GeofenceHit("home", GeofenceAction.DWELL, null))

        syncer.insert(geofence)
        advanceUntilIdle()

        assertJsonEquals(
            """{"location":[
                 {"lat":24.7136,"ts":"2026-09-26T10:15:30.123Z","sent":"2026-09-26T10:15:45.001Z","reason":null,
                  "extras":{"k":"v"}},
                 {"fence":"home","action":"DWELL"}]}""",
            JSONObject(takeRequest().body.readUtf8()),
        )
    }

    @Test
    fun `invalid template falls back to the default shape`() = runTest {
        http { it.copy(locationTemplate = """{"lat": <%= latitude %>""") }
        server.enqueue(ok())
        val record = rec(1)

        syncer().insert(record)
        advanceUntilIdle()

        assertJsonEquals(
            RecordJson.toJson(record, clock.now()),
            JSONObject(takeRequest().body.readUtf8()).getJSONObject("location"),
        )
        assertTrue(log.lines.any { it.level == LogLevel.ERROR })
    }

    // ---- single flight

    @Test
    fun `triggers during an upload coalesce into one more pass`() = runTest {
        val passes = AtomicInteger()
        val countingStore = object : LocationStore by store {
            override suspend fun count(events: Set<RecordEvent>?): Int {
                if (events != null) passes.incrementAndGet()
                return store.count(events)
            }
        }
        val syncer = syncer(countingStore)
        val firstRequest = AtomicBoolean(true)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (firstRequest.getAndSet(false)) repeat(5) { syncer.onRecordInserted(rec(1)) }
                return ok()
            }
        }

        syncer.insert(rec(1))
        advanceUntilIdle()

        assertEquals(1, server.requestCount)
        assertEquals(2, passes.get())
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun `concurrent syncs and triggers never upload a record twice`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val io = AppDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.IO)
            val syncer = OkHttpSyncer(configStore, store, device, events, clock, OkHttpClient(), io, scope)
            val received = CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    received += uuidsOf(request)
                    Thread.sleep(50)
                    return ok()
                }
            }
            (1..4).forEach { store.insert(rec(it)) }

            val syncs = List(3) { async(Dispatchers.IO) { syncer.sync() } }
            repeat(5) { syncer.onRecordInserted(rec(1)) }
            val uploaded = syncs.awaitAll().flatten()
            syncer.sync() // waits for any pass still holding the lock

            assertEquals(listOf("r1", "r2", "r3", "r4"), received.sorted())
            assertEquals(received.toSet().size, received.size)
            assertEquals(uploaded.map { it.uuid }.toSet().size, uploaded.size)
            assertTrue(store.all.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `http event payload lists every uploaded uuid of a batch`() = runTest {
        http { it.copy(batchSync = true, rootProperty = ".") }
        server.enqueue(ok("[]"))
        val syncer = syncer()
        store.insert(rec(1))

        syncer.insert(rec(2))
        advanceUntilIdle()

        val body = JSONArray(takeRequest().body.readUtf8())
        assertEquals(2, body.length())
        assertEquals(listOf("r1", "r2"), httpEvents.single().uuids)
        assertNull(store.all.firstOrNull())
    }

    @Test
    fun `sync propagates NO_URL for an invalid url`() = runTest {
        http { it.copy(url = "ftp://example.com") }
        try {
            syncer().sync()
            fail("expected NO_URL")
        } catch (e: TrackingException) {
            assertEquals(ErrorCode.NO_URL, e.code)
        }
    }

    // ---- review follow-ups

    @Test
    fun `a record the server rejects does not hold back priority records`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (uuidsOf(request).contains("r1")) MockResponse().setResponseCode(400) else ok()
        }
        val syncer = syncer()
        store.insert(rec(1))
        store.insert(rec(2))

        syncer.insert(rec(3, RecordEvent.HEARTBEAT))
        advanceUntilIdle()

        assertEquals(listOf(listOf("r1"), listOf("r3")), takeUuids(2))
        assertEquals(listOf("r1", "r2"), store.all.map { it.uuid })
        assertEquals(1, store.attempts("r1"))
        assertEquals(0, store.attempts("r2"))
        assertEquals(listOf(false, true), httpEvents.map { it.success })
    }

    @Test
    fun `a rejected batch mixing records is retried with its priority records only`() = runTest {
        http { it.copy(batchSync = true) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (uuidsOf(request).contains("r1")) MockResponse().setResponseCode(422) else ok()
        }
        val syncer = syncer()
        store.insert(rec(1))

        syncer.insert(rec(2, RecordEvent.HEARTBEAT))
        advanceUntilIdle()

        assertEquals(listOf(listOf("r1", "r2"), listOf("r2")), takeUuids(2))
        assertEquals(listOf("r1"), store.all.map { it.uuid })
    }

    @Test
    fun `a rejected priority record is not re-sent in the same pass`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))
        val syncer = syncer()

        syncer.insert(rec(1, RecordEvent.HEARTBEAT))
        advanceUntilIdle()

        assertEquals(1, server.requestCount)
        assertEquals(1, store.attempts("r1"))
    }

    @Test
    fun `network errors do not trigger the priority fallback`() = runTest {
        server.shutdown()
        val syncer = syncer()
        store.insert(rec(1))

        syncer.insert(rec(2, RecordEvent.HEARTBEAT))
        advanceUntilIdle()

        assertEquals(List(4) { listOf("r1") }, httpEvents.map { it.uuids }) // the first try and 3 retries, all r1
        assertEquals(1, store.attempts("r1"))
        assertEquals(0, store.attempts("r2"))
    }

    @Test
    fun `a record that cannot be serialized is an internal failure`() = runTest {
        val broken = rec(1).copy(odometer = Double.NaN)
        store.insert(broken)
        val syncer = syncer()

        val error = runCatching { syncer.sync() }.exceptionOrNull() as TrackingException

        assertEquals(ErrorCode.INTERNAL, error.code)
        assertEquals(0, server.requestCount)
        assertEquals(1, store.attempts("r1"))
        val result = httpEvents.single()
        assertFalse(result.success)
        assertEquals(0, result.status)
        assertTrue(log.lines.any { it.level == LogLevel.ERROR })
    }

    @Test
    fun `a disconnect event clears a pending connected hint`() = runTest {
        device.connectivityValue = Connectivity(connected = false, type = ConnectivityType.NONE)
        val syncer = syncer()
        syncer.start()
        store.insert(rec(1))
        advanceUntilIdle()

        events.emit(TrackingEvent.ConnectivityChange(Connectivity(connected = true, type = ConnectivityType.WIFI)))
        events.emit(TrackingEvent.ConnectivityChange(Connectivity(connected = false, type = ConnectivityType.NONE)))
        advanceUntilIdle()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a hint is consumed by the next pass even when it uploads nothing`() = runTest {
        http { it.copy(url = null) }
        device.connectivityValue = Connectivity(connected = false, type = ConnectivityType.NONE)
        val syncer = syncer()
        syncer.start()
        events.emit(TrackingEvent.ConnectivityChange(Connectivity(connected = true, type = ConnectivityType.WIFI)))
        advanceUntilIdle()

        http { it.copy(url = url) }
        syncer.insert(rec(1))
        advanceUntilIdle()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancelling a sync cancels the call promptly and marks no attempt`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val io = AppDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.IO)
            val syncer = OkHttpSyncer(configStore, store, device, events, clock, OkHttpClient(), io, scope)
            server.enqueue(ok().setHeadersDelay(2, TimeUnit.SECONDS))
            store.insert(rec(1))

            val job = scope.launch { syncer.sync() }
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            val started = System.nanoTime()
            job.cancel()
            job.join()

            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
            assertEquals(listOf("r1"), store.all.map { it.uuid })
            assertEquals(0, store.attempts("r1"))
            assertTrue(httpEvents.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
