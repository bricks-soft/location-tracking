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
    private fun TestScope.syncer(store: LocationStore = this@OkHttpSyncerTest.store): OkHttpSyncer {
        val workerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        workerScopes += workerScope
        return OkHttpSyncer(
            configStore, store, device, events, clock, OkHttpClient(), testDispatchers(testScheduler), workerScope,
        )
    }

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
        server.enqueue(MockResponse().setResponseCode(500).setBody("down"))
        val syncer = syncer()
        store.insert(rec(1))
        store.insert(rec(2))

        syncer.insert(rec(3))
        advanceUntilIdle()

        assertEquals(1, server.requestCount)
        assertEquals(listOf("r1", "r2", "r3"), store.all.map { it.uuid })
        assertEquals(1, store.attempts("r1"))
        assertEquals(clock.now(), store.lastAttemptAt("r1"))
        assertEquals(0, store.attempts("r2"))
        val result = httpEvents.single()
        assertFalse(result.success)
        assertEquals(500, result.status)
        assertEquals("down", result.responseText)
        assertEquals(listOf("r1"), result.uuids)
    }

    @Test
    fun `failed upload is retried when connectivity returns, with the original recorded_at`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(ok())
        val syncer = syncer()
        syncer.start()
        val heartbeat = rec(1, RecordEvent.HEARTBEAT)

        syncer.insert(heartbeat)
        advanceUntilIdle()
        assertEquals(1, store.attempts("r1"))
        val first = JSONObject(takeRequest().body.readUtf8()).getJSONObject("location")

        clock.advance(120_000)
        events.emit(TrackingEvent.ConnectivityChange(Connectivity(connected = true, type = ConnectivityType.WIFI)))
        advanceUntilIdle()

        val retry = JSONObject(takeRequest().body.readUtf8()).getJSONObject("location")
        assertEquals(first.getString("recorded_at"), retry.getString("recorded_at"))
        assertEquals(Iso8601.format(clock.now()), retry.getString("sent_at"))
        assertTrue(store.all.isEmpty())
        assertEquals(listOf(false, true), httpEvents.map { it.success })
    }

    @Test
    fun `failed upload is retried on the next insert`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        repeat(2) { server.enqueue(ok()) }
        val syncer = syncer()

        syncer.insert(rec(1))
        advanceUntilIdle()
        syncer.insert(rec(2))
        advanceUntilIdle()

        takeRequest()
        assertEquals(listOf(listOf("r1"), listOf("r2")), takeUuids(2))
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun `network error keeps records and reports status 0`() = runTest {
        server.shutdown()

        syncer().insert(rec(1))
        advanceUntilIdle()

        assertEquals(1, store.attempts("r1"))
        val result = httpEvents.single()
        assertFalse(result.success)
        assertEquals(0, result.status)
    }

    @Test
    fun `http timeout is a network error`() = runTest {
        http { it.copy(timeout = 200) }
        server.enqueue(ok().setHeadersDelay(1, TimeUnit.SECONDS))

        syncer().insert(rec(1))
        advanceUntilIdle()

        val result = httpEvents.single()
        assertFalse(result.success)
        assertEquals(0, result.status)
        assertEquals(1, store.attempts("r1"))
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

        assertEquals(listOf(401, 0), httpEvents.map { it.status })
        assertEquals(1, store.attempts("r1"))
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
                if (uuidsOf(request).contains("r1")) MockResponse().setResponseCode(413) else ok()
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

        assertEquals(1, httpEvents.size)
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
