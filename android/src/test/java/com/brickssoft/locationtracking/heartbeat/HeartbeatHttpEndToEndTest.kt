package com.brickssoft.locationtracking.heartbeat

import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.data.LocationStore
import com.brickssoft.locationtracking.heartbeat.HeartbeatHarness.Companion.MIN_MS
import com.brickssoft.locationtracking.http.HttpSyncer
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Heartbeat → record sink → HTTP: the heartbeat reaches a real HTTP server within the window, with the last
 * known coords, `recorded_at` and `sent_at`; a failed upload stays queued and is delivered late on the next
 * heartbeat with its original `recorded_at`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HeartbeatHttpEndToEndTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Logger.sink = null
    }

    @Test
    fun `heartbeat is posted to the server with last known coords, recorded_at and sent_at`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val syncer = PriorityPostSyncer(server.url("/locations").toString(), backgroundScope)
        val h = HeartbeatHarness(backgroundScope, config = httpConfig(), syncer = syncer)
        syncer.bind(h.store, h.clock)
        h.scheduler.start()

        h.at(MIN_MS)
        h.fireListener()
        runCurrent()

        val heartbeat = h.events.ofType<TrackingEvent.Heartbeat>().single().record
        assertTrue(h.store.all.isEmpty()) // deleted after the 200
        assertEquals(1, server.requestCount)
        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/locations", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val location = JSONObject(request.body.readUtf8()).getJSONObject("location")
        assertEquals(heartbeat.uuid, location.getString("uuid"))
        assertEquals("heartbeat", location.getString("event"))
        assertEquals(h.lastKnown.latitude, location.getJSONObject("coords").getDouble("latitude"), 0.0)
        assertEquals(h.lastKnown.longitude, location.getJSONObject("coords").getDouble("longitude"), 0.0)
        assertEquals(Iso8601.format(h.lastKnown.time), location.getString("timestamp"))
        assertEquals(Iso8601.format(h.t0Wall + MIN_MS), location.getString("recorded_at"))
        assertEquals(Iso8601.format(h.t0Wall + MIN_MS), location.getString("sent_at"))
        val meta = location.getJSONObject("heartbeat")
        assertEquals("listener_with_backup", meta.getString("strategy"))
        assertEquals(180, meta.getInt("min_interval"))
        assertEquals(300, meta.getInt("max_interval"))
        assertEquals(Iso8601.format(h.t0Wall + 2 * MIN_MS), meta.getString("next_at"))
        assertEquals(false, meta.getBoolean("battery_exempt"))
        assertEquals(false, meta.getBoolean("device_idle"))
        assertEquals(0, h.scheduler.status().pendingHeartbeats)
        assertEquals(listOf(200), syncer.statuses)
    }

    @Test
    fun `failed heartbeat upload stays queued and is delivered late with its original recorded_at`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        val syncer = PriorityPostSyncer(server.url("/locations").toString(), backgroundScope)
        val h = HeartbeatHarness(backgroundScope, config = httpConfig(), syncer = syncer)
        syncer.bind(h.store, h.clock)
        h.scheduler.start()

        // Window 1: the upload fails; the heartbeat stays queued.
        h.at(MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.LISTENER_ALARM)
        runCurrent()
        val first = h.heartbeats().single()
        assertEquals(1, h.scheduler.status().pendingHeartbeats)
        assertEquals(1, h.store.attempts(first.uuid))
        val failed = JSONObject(takeRequest().body.readUtf8()).getJSONObject("location")
        assertEquals(first.uuid, failed.getString("uuid"))

        // Window 2: the next heartbeat drains the queue oldest first.
        h.at(2 * MIN_MS)
        h.scheduler.onAlarm(HeartbeatTrigger.BACKUP_ALARM)
        runCurrent()

        val late = JSONObject(takeRequest().body.readUtf8()).getJSONObject("location")
        assertEquals(first.uuid, late.getString("uuid"))
        assertEquals("heartbeat", late.getString("event"))
        assertEquals(Iso8601.format(first.recordedAt), late.getString("recorded_at"))
        assertEquals(Iso8601.format(h.t0Wall + 2 * MIN_MS), late.getString("sent_at"))
        val second = JSONObject(takeRequest().body.readUtf8()).getJSONObject("location")
        assertEquals("heartbeat", second.getString("event"))
        assertEquals(Iso8601.format(h.t0Wall + 2 * MIN_MS), second.getString("recorded_at"))
        assertTrue(h.store.all.isEmpty())
        assertEquals(0, h.scheduler.status().pendingHeartbeats)
        assertEquals(listOf(503, 200, 200), syncer.statuses)
        assertEquals(3, server.requestCount)
    }

    private fun httpConfig() = Config(http = HttpConfig(url = server.url("/locations").toString()))

    private fun takeRequest(): RecordedRequest =
        server.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("the server received no request")

    /**
     * Minimal stand-in for the real syncer: a priority record triggers an upload of the whole queue, oldest
     * first, one `{"location": record}` POST per record with `sent_at` = now; 2xx deletes, anything else stops
     * and leaves the rest queued.
     */
    private class PriorityPostSyncer(private val url: String, private val scope: CoroutineScope) : HttpSyncer {
        private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        private lateinit var store: LocationStore
        private lateinit var clock: Clock
        val statuses = mutableListOf<Int>()

        fun bind(store: LocationStore, clock: Clock) {
            this.store = store
            this.clock = clock
        }

        override fun start() = Unit

        override fun onRecordInserted(record: Record) {
            if (record.event.isPriority) scope.launch { drain() }
        }

        override suspend fun sync(): List<Record> = drain()

        private suspend fun drain(): List<Record> {
            val sent = mutableListOf<Record>()
            for (record in store.list()) {
                val body = JSONObject().put("location", RecordJson.toJson(record, clock.now())).toString()
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                val status = client.newCall(request).execute().use { it.code }
                statuses += status
                if (status !in 200..299) {
                    store.markAttempt(listOf(record.uuid), clock.now())
                    break
                }
                store.delete(listOf(record.uuid))
                sent += record
            }
            return sent
        }
    }
}
