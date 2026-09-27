package com.brickssoft.locationtracking.integration

import android.content.Intent
import android.os.PowerManager
import com.brickssoft.locationtracking.bridge.BridgeServices
import com.brickssoft.locationtracking.bridge.PluginHandlers
import com.brickssoft.locationtracking.config.SharedPrefsConfigStore
import com.brickssoft.locationtracking.config.TrackingMode
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.device.DefaultDeviceInfoProvider
import com.brickssoft.locationtracking.device.DeviceInfoProvider
import com.brickssoft.locationtracking.heartbeat.HeartbeatTrigger
import com.brickssoft.locationtracking.integration.IngestServer.Companion.REFRESH
import com.brickssoft.locationtracking.logging.LogStore
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.HeartbeatStrategy
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.position.CurrentPositionOptions
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import com.brickssoft.locationtracking.settings.DeviceSettings
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakeDeviceSettings
import com.brickssoft.locationtracking.testing.FakeGeofenceBackend
import com.brickssoft.locationtracking.testing.FakeProviderFactory
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNetwork
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real components wired together (see [FullStackProcess]) against a real HTTP server ([IngestServer], okhttp
 * MockWebServer): only the location / activity / geofence backends, the foreground service and the permission prompts
 * are simulated. Time is virtual: the process scope runs on the test scheduler and [FakeClock] follows it; heartbeat
 * alarms are read from and fired through Robolectric's AlarmManager.
 *
 * Every assertion on uploads parses what the server actually received.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class FullStackIntegrationTest : FullStackTestBase() {
    // ------------------------------------------------------------------ a

    @Test
    fun `a - ready then start uploads tracking_start and the initial motionchange in the wire format`() = runTest {
        val p = newProcess()
        val t0 = clock.now()
        val extras = JSONObject().put("driver_id", 7)
        val state = p.engine.ready(config(sections = mapOf("persistence" to JSONObject().put("extras", extras))), reset = true)
        assertEquals(server.url(), state.config.http.url)

        val origin = start(p)

        assertEquals(listOf("tracking_start", "motionchange"), server.receivedEvents())
        server.received().forEach { assertJsonEquals(extras, it.getJSONObject("extras")) }
        assertTrue(server.uploads.all { it.accepted && !it.isBatch && it.method == "POST" })
        assertTrue(server.uploads.all { it.headers["Content-Type"] == "application/json; charset=utf-8" })
        val (trackingStart, motion) = server.received()
        assertEquals("start", trackingStart.getString("reason"))
        assertEquals(origin.latitude, Wire.latitude(trackingStart), 1e-9) // the last known location
        assertFalse(motion.getBoolean("is_moving"))
        assertEquals(origin.latitude, Wire.latitude(motion), 1e-9)
        assertEquals(origin.longitude, Wire.longitude(motion), 1e-9)
        assertEquals(Iso8601.format(origin.time), motion.getString("timestamp"))
        assertEquals(t0, Wire.recordedAt(motion))
        assertEquals(t0, Wire.sentAt(motion))
        assertEquals("gms", motion.getString("backend"))
        assertEquals(0.81, motion.getJSONObject("battery").getDouble("level"), 1e-6)
        assertFalse(motion.getJSONObject("battery").getBoolean("is_charging"))
        assertEquals(clock.bootCount(), motion.getInt("boot_count"))
        assertEquals(clock.elapsedRealtime(), motion.getLong("elapsed_realtime_ms"))

        // Queue drained; JS-facing events; runtime state.
        assertEquals(0, p.locationStore.count())
        assertEquals(listOf(true), p.eventsOf<TrackingEvent.EnabledChange>().map { it.enabled })
        assertEquals(listOf(false), p.eventsOf<TrackingEvent.MotionChange>().map { it.isMoving })
        val http = p.eventsOf<TrackingEvent.Http>().map { it.result }
        assertTrue(http.all { it.success && it.status == 200 })
        assertEquals(uuids(server.received()), http.flatMap { it.uuids })
        val runtime = p.configStore.runtime.value
        assertTrue(runtime.enabled)
        assertEquals(TrackingMode.LOCATION, runtime.trackingMode)
        assertEquals(t0, runtime.trackingStartedAt)
        assertEquals(t0, runtime.lastRecordAt)
        assertEquals(1, p.service.startCalls)
        assertEquals(DesiredAccuracy.BALANCED, p.providers.locationBackend.active.values.single().accuracy)
        assertEquals(listOf(10_000L), p.providers.activityBackend.startCalls)
        assertHealthy(p)
    }

    // ------------------------------------------------------------------ b, c

    @Test
    fun `b and c - movement records motionchange and elastic-filtered locations, then the stop timeout ends it`() =
        runTest {
            val p = newProcess()
            p.engine.ready(config(), reset = true)
            start(p)
            val atStart = server.received().size

            advance(10_000)
            emit(p, gps.fix(100.0)) // leaves the 25 m stationary radius
            // 13.4 m/s: elastic distance 10 m * round(13.4 / 5) = 30 m, so every second 20 m step is recorded.
            for (north in listOf(120.0, 140.0, 160.0, 180.0, 200.0)) {
                advance(2_000)
                emit(p, gps.fix(north))
            }
            // 2 m/s: the plain 10 m distance filter, so every 15 m step is recorded.
            for (north in listOf(215.0, 230.0)) {
                advance(7_500)
                emit(p, gps.fix(north, speed = 2f))
            }

            val moving = server.received().drop(atStart)
            assertEquals(listOf("motionchange", "location", "location", "location", "location"), moving.map { it.getString("event") })
            assertTrue(moving.all { it.getBoolean("is_moving") })
            val expected = listOf(100.0, 140.0, 180.0, 215.0, 230.0).map { gps.latitudeAt(it) }
            expected.zip(moving.map(Wire::latitude)).forEach { (e, a) -> assertEquals(e, a, 1e-9) }
            assertTrue(server.uploads.all { it.accepted })
            assertEquals(0, p.locationStore.count())
            assertEquals(DesiredAccuracy.HIGH, p.providers.locationBackend.active.values.single().accuracy)

            // Odometer: the 230 m path from the start position (within 5 %), increasing on every record.
            val odometer = p.configStore.runtime.value.odometer
            assertEquals(230.0, odometer, 230.0 * 0.05)
            val onRecords = moving.map { it.getDouble("odometer") }
            assertEquals(onRecords.sorted(), onRecords)
            assertEquals(odometer, onRecords.last(), 1e-6)
            assertEquals(180.0, onRecords[2], 180.0 * 0.05)

            // c: the last evidence of motion was the 215 m fix (7.5 s ago); stopTimeout is 5 min.
            advance(5 * MINUTE - 7_500 - 1)
            assertEquals("location", server.receivedEvents().last())
            assertTrue(p.configStore.runtime.value.isMoving)
            advance(1)
            val stopped = server.received().last()
            assertEquals("motionchange", stopped.getString("event"))
            assertFalse(stopped.getBoolean("is_moving"))
            assertEquals(gps.latitudeAt(230.0), Wire.latitude(stopped), 1e-9)
            assertFalse(p.configStore.runtime.value.isMoving)
            assertEquals(listOf(false, true, false), p.eventsOf<TrackingEvent.MotionChange>().map { it.isMoving })
            assertEquals(DesiredAccuracy.BALANCED, p.providers.locationBackend.active.values.single().accuracy)
            assertEquals(0, p.locationStore.count())
            assertHealthy(p)
        }

    // ------------------------------------------------------------------ d

    @Test
    fun `d - the heartbeat is posted minInterval after the last record with the last known coords`() = runTest {
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        val t0 = clock.elapsedRealtime()
        val w0 = clock.now()
        start(p)

        // The window runs from the last record (the initial motionchange): listener alarm + allow-while-idle backup.
        assertEquals(t0 + MIN_MS, alarms.listener()!!.triggerAtMs)
        assertEquals(t0 + MIN_MS, alarms.intent()!!.triggerAtMs)
        val armed = p.heartbeat.status()
        assertEquals(HeartbeatStrategy.LISTENER_WITH_BACKUP, armed.strategy)
        assertEquals(w0 + MIN_MS, armed.nextHeartbeatAt)

        // Parked: a jittery fix is not recorded but becomes the last known location.
        advance(60_000)
        val parked = gps.fix(5.0, speed = 0f)
        emit(p, parked)
        assertEquals(listOf("tracking_start", "motionchange"), server.receivedEvents())

        // An early delivery creates nothing and re-arms for the due time.
        advance(110_000)
        alarms.fireListener()
        runCurrent()
        assertTrue(heartbeatsReceived().isEmpty())
        assertEquals(t0 + MIN_MS, alarms.listener()!!.triggerAtMs)

        advance(10_000)
        alarms.fireListener()
        runCurrent()
        val heartbeat = heartbeatsReceived().single()
        assertEquals(1, server.uploads.count { "heartbeat" in it.events })
        assertEquals(parked.latitude, Wire.latitude(heartbeat), 1e-9)
        assertEquals(Iso8601.format(parked.time), heartbeat.getString("timestamp"))
        assertEquals(w0 + MIN_MS, Wire.recordedAt(heartbeat))
        assertEquals(w0 + MIN_MS, Wire.sentAt(heartbeat))
        assertFalse(heartbeat.getBoolean("is_moving"))
        assertEquals(heartbeat.getString("uuid"), p.eventsOf<TrackingEvent.Heartbeat>().single().record.uuid)
        assertEquals(0, p.locationStore.count())

        // The next one is due minInterval after this heartbeat.
        assertEquals(t0 + 2 * MIN_MS, alarms.listener()!!.triggerAtMs)
        val after = p.heartbeat.status()
        assertEquals(w0 + MIN_MS, after.lastHeartbeatAt)
        assertEquals(w0 + 2 * MIN_MS, after.nextHeartbeatAt)
        assertEquals(0, after.pendingHeartbeats)

        // A location record 100 s after the heartbeat pushes the next one to 280 s after it.
        val t1 = t0 + MIN_MS
        advance(99_000)
        emit(p, gps.fix(100.0))
        advance(1_000)
        val moved = gps.fix(140.0)
        emit(p, moved)
        assertEquals(listOf("motionchange", "location"), server.receivedEvents().takeLast(2))
        assertEquals(t1 + 100_000 + MIN_MS, alarms.listener()!!.triggerAtMs)
        assertEquals(t1 + 100_000 + MIN_MS, alarms.intent()!!.triggerAtMs)

        // A stale backup delivery at the old due time (t + 180 s) creates nothing.
        advance(80_000)
        p.deliverHeartbeatIntent(alarms.intent()!!)
        runCurrent()
        assertEquals(1, heartbeatsReceived().size)

        advance(100_000)
        alarms.fireListener()
        runCurrent()
        val second = heartbeatsReceived().last()
        assertEquals(2, heartbeatsReceived().size)
        assertEquals(moved.latitude, Wire.latitude(second), 1e-9)
        assertEquals(w0 + MIN_MS + 100_000 + MIN_MS, Wire.recordedAt(second))
        assertTrue(second.getBoolean("is_moving"))
        assertHealthy(p)
    }

    // ------------------------------------------------------------------ e

    @Test
    fun `e - a failed heartbeat stays queued and is re-sent with its recorded_at and a later sent_at`() = runTest {
        val heartbeatFailures = AtomicInteger(1)
        server.respond = { _, _, body ->
            if (IngestServer.contains(body, "heartbeat") && heartbeatFailures.getAndDecrement() > 0) {
                IngestServer.status(500)
            } else {
                IngestServer.ok()
            }
        }
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        val w0 = clock.now()
        start(p)

        advance(MIN_MS)
        alarms.fireListener()
        runCurrent()
        val failed = server.uploads.last()
        assertEquals(500, failed.status)
        assertEquals(listOf("heartbeat"), failed.events)
        val heartbeat = failed.records.single()
        assertEquals(1, p.locationStore.count(setOf(RecordEvent.HEARTBEAT)))
        assertEquals(1, p.heartbeat.status().pendingHeartbeats)
        val failure = p.eventsOf<TrackingEvent.Http>().last().result
        assertFalse(failure.success)
        assertEquals(500, failure.status)

        // Connectivity returns 45 s later (the monitor's default-network callback): the queue is retried.
        advance(45_000)
        env.reconnectWifi(ShadowNetwork.newInstance(7))
        runCurrent()
        val retry = server.uploads.last()
        assertEquals(200, retry.status)
        val resent = retry.records.single()
        assertEquals(heartbeat.getString("uuid"), resent.getString("uuid"))
        assertEquals(heartbeat.getString("recorded_at"), resent.getString("recorded_at"))
        assertEquals(w0 + MIN_MS, Wire.recordedAt(resent))
        assertEquals(w0 + MIN_MS + 45_000, Wire.sentAt(resent))
        assertEquals(0, p.locationStore.count(setOf(RecordEvent.HEARTBEAT)))
        assertEquals(0, p.heartbeat.status().pendingHeartbeats)
        assertEquals(
            listOf(false, true),
            p.eventsOf<TrackingEvent.ConnectivityChange>().map { it.connectivity.connected },
        )

        // The next heartbeat fails too; the one after it delivers both, oldest first.
        heartbeatFailures.set(1)
        advance(MIN_MS - 45_000)
        alarms.fireListener()
        runCurrent()
        assertEquals(500, server.uploads.last().status)
        val late = server.uploads.last().records.single()
        assertEquals(1, p.heartbeat.status().pendingHeartbeats)

        advance(MIN_MS)
        alarms.fireListener()
        runCurrent()
        val (lateAgain, newest) = server.uploads.takeLast(2).map { it.records.single() }
        assertTrue(server.uploads.takeLast(2).all { it.accepted })
        assertEquals(late.getString("uuid"), lateAgain.getString("uuid"))
        assertEquals(w0 + 2 * MIN_MS, Wire.recordedAt(lateAgain))
        assertEquals(w0 + 3 * MIN_MS, Wire.sentAt(lateAgain))
        assertEquals(w0 + 3 * MIN_MS, Wire.recordedAt(newest))
        assertEquals(0, p.heartbeat.status().pendingHeartbeats)
        assertEquals(0, p.locationStore.count())
        assertHealthy(p)
    }

    // ------------------------------------------------------------------ f

    @Test
    fun `f - with autoSyncThreshold normal records wait while heartbeat and audit records upload at once`() = runTest {
        val p = newProcess()
        p.engine.ready(config(mapOf("autoSyncThreshold" to 10)), reset = true)
        start(p)
        assertEquals("tracking_start", server.acceptedEvents().first())

        // Normal records stay queued below the threshold.
        advance(10_000)
        emit(p, gps.fix(100.0))
        for (north in listOf(140.0, 180.0)) {
            advance(3_000)
            emit(p, gps.fix(north))
        }
        val waiting = p.locationStore.list().map { it.uuid }
        assertTrue(waiting.size >= 3)
        assertTrue(server.received().none { it.getString("uuid") in waiting })

        // providerchange (GPS switched off) goes out at once and drains the queue in order.
        advance(10_000)
        setLocation(enabled = true, gps = false, network = true)
        assertEquals(waiting, uuids(server.accepted()).takeLast(waiting.size + 1).dropLast(1))
        assertEquals("providerchange", server.acceptedEvents().last())
        assertEquals(0, p.locationStore.count())

        // More normal records wait; the heartbeat takes them along.
        for (north in listOf(220.0, 260.0)) {
            advance(3_000)
            emit(p, gps.fix(north))
        }
        assertEquals(2, p.locationStore.count())
        val beforeHeartbeat = server.uploads.size
        advance(MIN_MS)
        alarms.fireListener()
        runCurrent()
        assertEquals(listOf("location", "location", "heartbeat"), server.uploads.drop(beforeHeartbeat).flatMap { it.events })
        assertEquals(0, p.locationStore.count())

        // And tracking_stop.
        advance(3_000)
        emit(p, gps.fix(300.0))
        assertEquals(1, p.locationStore.count())
        val beforeStop = server.uploads.size
        p.engine.stop()
        runCurrent()
        assertEquals(listOf("location", "tracking_stop"), server.uploads.drop(beforeStop).flatMap { it.events })
        assertEquals(0, p.locationStore.count())
        assertTrue(server.uploads.all { it.accepted })
        assertHealthy(p)
    }

    // ------------------------------------------------------------------ g

    @Test
    fun `g - switching GPS off posts a providerchange record with provider gps false`() = runTest {
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        val origin = start(p)
        advance(5_000) // the monitor's first check stores the initial provider state silently
        assertTrue(p.configStore.runtime.value.providerState!!.gps)
        assertTrue(server.receivedEvents().none { it == "providerchange" })

        setLocation(enabled = true, gps = false, network = true)

        val change = server.received().last()
        assertEquals("providerchange", change.getString("event"))
        assertJsonEquals(
            JSONObject("""{"enabled":true,"gps":false,"network":true,"permission":"always","accuracy":"precise","backend":"gms"}"""),
            change.getJSONObject("provider"),
        )
        assertEquals(origin.latitude, Wire.latitude(change), 1e-9)
        assertFalse(p.eventsOf<TrackingEvent.ProviderChange>().single().state.gps)
        assertFalse(p.configStore.runtime.value.providerState!!.gps)

        // Back on: one more record; a repeated broadcast without a change adds nothing.
        setLocation(enabled = true, gps = true, network = true)
        setLocation(enabled = true, gps = true, network = true)
        val providerRecords = server.received().filter { it.getString("event") == "providerchange" }
        assertEquals(listOf(false, true), providerRecords.map { it.getJSONObject("provider").getBoolean("gps") })
        assertEquals(0, p.locationStore.count())
        assertHealthy(p)
    }

    // ------------------------------------------------------------------ h

    @Test
    fun `h - a geofence added while tracking is registered with the OS and its transitions are posted`() = runTest {
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        start(p)

        val home = Fixtures.circle(
            identifier = "home",
            latitude = gps.latitudeAt(300.0),
            longitude = Fixtures.LNG,
            radius = 150f,
            extras = """{"site":"HQ"}""",
        )
        p.geofences.add(listOf(home))
        runCurrent()
        val os = p.providers.geofenceBackend.registered.getValue("home")
        assertEquals(150f, os.radius)
        assertEquals(home.latitude, os.latitude, 1e-12)
        assertTrue(os.onEntry && os.onExit && os.initialTriggerEntry)
        assertEquals(listOf("home"), p.eventsOf<TrackingEvent.GeofencesChange>().single().on.map { it.identifier })
        assertEquals(listOf("home"), p.geofenceStore.all().map { it.identifier })

        advance(30_000)
        val inside = gps.fix(290.0)
        p.geofences.onGeofenceTransitions(listOf(OsGeofenceTransition("home", GeofenceAction.ENTER, inside)))
        runCurrent()
        val enter = server.received().last()
        assertEquals("geofence", enter.getString("event"))
        assertJsonEquals(
            JSONObject("""{"identifier":"home","action":"ENTER","extras":{"site":"HQ"}}"""),
            enter.getJSONObject("geofence"),
        )
        assertEquals(inside.latitude, Wire.latitude(enter), 1e-9)
        val event = p.eventsOf<TrackingEvent.Geofence>().single()
        assertEquals("home" to GeofenceAction.ENTER, event.identifier to event.action)
        assertEquals(enter.getString("uuid"), event.record.uuid)

        // Location services off and on again (GMS / HMS drop every geofence): the engine registers them again.
        val addsBefore = p.providers.geofenceBackend.addCalls.size
        advance(10_000)
        setLocation(enabled = false, gps = false, network = false)
        setLocation(enabled = true, gps = true, network = true)
        assertEquals(listOf("providerchange", "providerchange"), server.receivedEvents().takeLast(2))
        assertTrue(p.providers.geofenceBackend.addCalls.size > addsBefore)
        assertTrue("home" in p.providers.geofenceBackend.registered)

        advance(30_000)
        val outside = gps.fix(500.0)
        p.geofences.onGeofenceTransitions(listOf(OsGeofenceTransition("home", GeofenceAction.EXIT, outside)))
        runCurrent()
        val exit = server.received().last()
        assertEquals("EXIT", exit.getJSONObject("geofence").getString("action"))
        assertEquals(outside.latitude, Wire.latitude(exit), 1e-9)
        assertEquals(0, p.locationStore.count())
        assertHealthy(p)
    }

    // ------------------------------------------------------------------ i

    @Test
    fun `i - stop posts tracking_stop, cancels the heartbeat alarms and no heartbeat follows`() = runTest {
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        start(p)
        val staleListener = HeartbeatAlarmsProbe.listenerOf(alarms.listener()!!)!!
        val staleIntent = alarms.intent()!!

        advance(30_000)
        val here = gps.fix(3.0, speed = 0f)
        emit(p, here)
        p.engine.stop()
        runCurrent()

        val stop = server.received().last()
        assertEquals("tracking_stop", stop.getString("event"))
        assertEquals("stop", stop.getString("reason"))
        assertEquals(here.latitude, Wire.latitude(stop), 1e-9)
        assertFalse(p.configStore.runtime.value.enabled)
        assertEquals(listOf(true, false), p.eventsOf<TrackingEvent.EnabledChange>().map { it.enabled })
        assertEquals(1, p.service.stopCalls)
        assertFalse(p.providers.locationBackend.isRequesting)
        assertFalse(p.providers.activityBackend.running)
        assertTrue(alarms.scheduled().isEmpty())
        val status = p.heartbeat.status()
        assertEquals(HeartbeatStrategy.DISABLED, status.strategy)
        assertNull(status.nextHeartbeatAt)

        // Ten silent minutes and late deliveries of both old alarms: still no heartbeat.
        advance(10 * MINUTE)
        staleListener.onAlarm()
        p.deliverHeartbeatIntent(staleIntent)
        runCurrent()
        assertTrue(heartbeatsReceived().isEmpty())
        assertTrue(p.eventsOf<TrackingEvent.Heartbeat>().isEmpty())
        assertTrue(alarms.scheduled().isEmpty())
        assertEquals(0, p.locationStore.count())
        assertEquals(1, p.service.startCalls) // the late alarm did not restore tracking
        assertHealthy(p)
    }

    // ------------------------------------------------------------------ l

    @Test
    fun `l - a 401 refreshes the JWT, the upload is retried with the new token and the token is persisted`() = runTest {
        server.respond = { path, headers, _ ->
            when {
                path == REFRESH -> IngestServer.ok("""{"access_token":"fresh-token","refresh_token":"refresh-2","expires_in":3600}""")
                headers["Authorization"] == "Bearer fresh-token" -> IngestServer.ok()
                else -> IngestServer.status(401)
            }
        }
        val authorization = JSONObject()
            .put("accessToken", "stale-token")
            .put("refreshToken", "refresh-1")
            .put("refreshUrl", server.url(REFRESH))
            .put("refreshPayload", JSONObject().put("grant_type", "refresh_token").put("refresh_token", "{refreshToken}"))
        val p = newProcess()
        p.engine.ready(config(mapOf("authorization" to authorization)), reset = true)

        start(p)

        val refresh = server.requests.single { it.path == REFRESH }
        assertEquals("POST", refresh.method)
        assertEquals("application/json; charset=utf-8", refresh.headers["Content-Type"])
        assertJsonEquals(JSONObject("""{"grant_type":"refresh_token","refresh_token":"refresh-1"}"""), refresh.json)
        val uploads = server.uploads
        assertEquals(401, uploads[0].status)
        assertEquals("Bearer stale-token", uploads[0].headers["Authorization"])
        assertEquals(200, uploads[1].status)
        assertEquals("Bearer fresh-token", uploads[1].headers["Authorization"])
        assertEquals(uploads[0].records.single().getString("uuid"), uploads[1].records.single().getString("uuid"))
        assertTrue(uploads.drop(1).all { it.accepted && it.headers["Authorization"] == "Bearer fresh-token" })
        assertEquals(listOf("tracking_start", "motionchange"), server.acceptedEvents())
        assertEquals(0, p.locationStore.count())

        val expires = clock.now() + 3_600_000
        val stored = p.configStore.config.value.http.authorization!!
        assertEquals(Triple("fresh-token", "refresh-2", expires), Triple(stored.accessToken, stored.refreshToken, stored.expires))
        val reloaded = SharedPrefsConfigStore(app, clock).config.value.http.authorization!!
        assertEquals(Triple("fresh-token", "refresh-2", expires), Triple(reloaded.accessToken, reloaded.refreshToken, reloaded.expires))
        val authEvent = p.eventsOf<TrackingEvent.Authorization>().single()
        assertTrue(authEvent.success)
        assertEquals(200, authEvent.status)
        assertEquals(listOf(401, 200, 200), p.eventsOf<TrackingEvent.Http>().map { it.result.status })
        assertHealthy(p)
    }

    // ------------------------------------------------------------------ m

    @Test
    fun `m - batchSync uploads arrays of at most maxBatchSize records, oldest first`() = runTest {
        val serverUp = AtomicBoolean(false)
        server.respond = { _, _, _ -> if (serverUp.get()) IngestServer.ok() else IngestServer.status(503) }
        val p = newProcess()
        p.engine.ready(config(mapOf("batchSync" to true, "maxBatchSize" to 2)), reset = true)
        start(p)
        advance(10_000)
        emit(p, gps.fix(100.0))
        for (north in listOf(140.0, 180.0, 220.0)) {
            advance(3_000)
            emit(p, gps.fix(north))
        }
        val queued = p.locationStore.list()
        assertEquals(
            listOf("tracking_start", "motionchange", "motionchange", "location", "location", "location"),
            queued.map { it.event.wire },
        )
        assertTrue(server.uploads.isNotEmpty())
        assertTrue(server.uploads.none { it.accepted })
        assertTrue(server.uploads.all { it.isBatch && it.records.size in 1..2 })

        // Back online: the queue drains in batches of two, oldest first.
        serverUp.set(true)
        advance(30_000)
        env.reconnectWifi(ShadowNetwork.newInstance(9))
        runCurrent()
        val batches = server.uploads.filter { it.accepted }
        assertEquals(listOf(2, 2, 2), batches.map { it.records.size })
        assertTrue(batches.all { it.isBatch })
        assertEquals(queued.map { it.uuid }, uuids(batches.flatMap { it.records }))
        val recordedAt = batches.flatMap { it.records }.map(Wire::recordedAt)
        assertEquals(recordedAt.sorted(), recordedAt)
        assertEquals(queued.map { it.recordedAt }, recordedAt)
        assertTrue(batches.flatMap { it.records }.all { Wire.sentAt(it) == clock.now() })
        assertEquals(0, p.locationStore.count())
        assertHealthy(p)
    }

    // ------------------------------------------------------------------ edge cases

    @Test
    fun `a second session in the same process counts its path from its own start position`() = runTest {
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        start(p)
        advance(10_000)
        emit(p, gps.fix(100.0))
        advance(3_000)
        emit(p, gps.fix(140.0))
        advance(3_000)
        p.engine.stop()
        runCurrent()
        val first = p.configStore.runtime.value.odometer
        assertEquals(140.0, first, 140.0 * 0.05)

        // Ten minutes later, a new session 1 km away: the gap is not travelled while tracking, the new path is.
        advance(10 * MINUTE)
        val t1 = clock.elapsedRealtime()
        start(p, gps.fix(1_000.0, speed = 0f))
        assertEquals(t1 + MIN_MS, alarms.listener()!!.triggerAtMs)
        advance(10_000)
        emit(p, gps.fix(1_100.0))
        advance(3_000)
        emit(p, gps.fix(1_140.0))

        val second = p.configStore.runtime.value.odometer - first
        assertEquals(140.0, second, 140.0 * 0.05)
        val reasons = server.received().filter { it.getString("event").startsWith("tracking_") }.map { it.getString("reason") }
        assertEquals(listOf("start", "stop", "start"), reasons)
        assertHealthy(p)
    }

    @Test
    fun `battery-exempt apps get one exact PendingIntent alarm, delivered through the receiver path`() = runTest {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        val t0 = clock.elapsedRealtime()
        start(p)

        assertNull(alarms.listener())
        val exact = alarms.intent()!!
        assertEquals(t0 + MIN_MS, exact.triggerAtMs)
        assertEquals(HeartbeatTrigger.EXACT_ALARM, HeartbeatAlarmsProbe.triggerOf(exact))
        val status = p.heartbeat.status()
        assertEquals(HeartbeatStrategy.EXACT, status.strategy)
        assertTrue(status.canScheduleExactAlarms)

        advance(MIN_MS)
        p.deliverHeartbeatIntent(exact)
        runCurrent()
        assertEquals(1, heartbeatsReceived().size)
        assertEquals(t0 + 2 * MIN_MS, alarms.intent()!!.triggerAtMs)
        assertNull(alarms.listener())
        assertEquals(1, p.service.startCalls) // the service runs: no restore
        assertHealthy(p)
    }

    @Test
    fun `in deep idle without an exemption the backup alarm is paced 9 minutes after the previous one`() = runTest {
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        val t0 = clock.elapsedRealtime()
        val w0 = clock.now()
        start(p)
        advance(MIN_MS)
        p.deliverHeartbeatIntent(alarms.intent()!!) // the allow-while-idle backup delivers the first heartbeat
        runCurrent()
        assertEquals(1, heartbeatsReceived().size)

        shadowOf(app.getSystemService(PowerManager::class.java)).setIsDeviceIdleMode(true)
        app.sendBroadcast(Intent(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))
        env.idleMainLooper()
        runCurrent()

        val status = p.heartbeat.status()
        assertEquals(HeartbeatStrategy.IDLE_PACED, status.strategy)
        assertTrue(status.isDeviceIdleMode)
        assertEquals(t0 + 2 * MIN_MS, alarms.listener()!!.triggerAtMs) // deferred by Doze on a real device
        assertEquals(t0 + MIN_MS + 9 * MINUTE, alarms.intent()!!.triggerAtMs)
        assertEquals(w0 + MIN_MS + 9 * MINUTE, status.nextHeartbeatAt)

        advance(9 * MINUTE)
        p.deliverHeartbeatIntent(alarms.intent()!!)
        runCurrent()
        assertEquals(2, heartbeatsReceived().size)
        assertEquals(w0 + MIN_MS + 9 * MINUTE, Wire.recordedAt(heartbeatsReceived().last()))
        assertHealthy(p)
    }

    @Test
    fun `startGeofences keeps the heartbeat and posts geofence records without continuous location`() = runTest {
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        val home = Fixtures.circle(identifier = "home", latitude = gps.latitudeAt(300.0), longitude = Fixtures.LNG)
        p.geofences.add(listOf(home))
        runCurrent()
        assertTrue(p.providers.geofenceBackend.registered.isEmpty()) // stored only while tracking is off
        val here = gps.fix(0.0, speed = 0f)
        p.providers.locationBackend.lastLocation = here

        p.engine.startGeofences()
        runCurrent()

        assertEquals(listOf("tracking_start"), server.receivedEvents())
        assertEquals("start_geofences", server.received().single().getString("reason"))
        assertEquals(here.latitude, Wire.latitude(server.received().single()), 1e-9)
        assertEquals(setOf("home"), p.providers.geofenceBackend.registered.keys)
        assertFalse(p.providers.locationBackend.isRequesting)
        assertTrue(p.providers.activityBackend.startCalls.isEmpty())
        assertEquals(TrackingMode.GEOFENCES, p.configStore.runtime.value.trackingMode)

        advance(60_000)
        val entered = gps.fix(280.0)
        p.geofences.onGeofenceTransitions(listOf(OsGeofenceTransition("home", GeofenceAction.ENTER, entered)))
        runCurrent()
        assertEquals("geofence", server.receivedEvents().last())

        advance(MIN_MS)
        alarms.fireListener()
        runCurrent()
        val heartbeat = heartbeatsReceived().single()
        assertEquals(entered.latitude, Wire.latitude(heartbeat), 1e-9)

        p.engine.stop()
        runCurrent()
        assertEquals("tracking_stop", server.receivedEvents().last())
        assertTrue(p.providers.geofenceBackend.registered.isEmpty())
        assertEquals(listOf("home"), p.geofenceStore.all().map { it.identifier })
        assertEquals(0, p.locationStore.count())
        assertHealthy(p)
    }

    @Test
    fun `without any known location the audit records and the heartbeat carry null coords and timestamp`() = runTest {
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        p.engine.start()
        runCurrent()
        advance(MIN_MS)
        alarms.fireListener()
        runCurrent()

        assertEquals(listOf("tracking_start", "motionchange", "heartbeat"), server.receivedEvents())
        for (record in server.received()) {
            assertTrue(record.isNull("coords"))
            assertTrue(record.isNull("timestamp"))
            assertFalse(record.getBoolean("mock"))
        }
        assertHealthy(p)
    }

    @Test
    fun `a persisted getCurrentPosition is uploaded and restarts the heartbeat window`() = runTest {
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        val t0 = clock.elapsedRealtime()
        start(p)
        advance(100_000)

        val position = async { p.positions.getCurrentPosition(CurrentPositionOptions(samples = 1, timeoutMs = 10_000)) }
        runCurrent()
        val fix = gps.fix(8.0, speed = 0f)
        emit(p, fix)
        val record = position.await()
        runCurrent()

        val uploaded = server.received().last()
        assertEquals("current_position", uploaded.getString("event"))
        assertEquals(record.uuid, uploaded.getString("uuid"))
        assertEquals(fix.latitude, Wire.latitude(uploaded), 1e-9)
        assertEquals(t0 + 100_000 + MIN_MS, alarms.listener()!!.triggerAtMs)
        assertEquals(1, p.providers.locationBackend.active.size) // the sampling request is gone again
        assertHealthy(p)
    }

    @Test
    fun `on cellular with disableAutoSyncOnCellular only priority records go out until Wi-Fi returns`() = runTest {
        env.setCellular()
        val p = newProcess()
        p.engine.ready(config(mapOf("disableAutoSyncOnCellular" to true)), reset = true)
        start(p)
        assertEquals(listOf("tracking_start"), server.acceptedEvents())

        advance(10_000)
        emit(p, gps.fix(100.0))
        advance(3_000)
        emit(p, gps.fix(140.0))
        assertEquals(listOf("tracking_start"), server.acceptedEvents())
        val waiting = p.locationStore.list().map { it.uuid }
        assertEquals(3, waiting.size) // initial motionchange, motionchange (moving), location

        advance(MIN_MS)
        alarms.fireListener()
        runCurrent()
        assertEquals(listOf("tracking_start", "heartbeat"), server.acceptedEvents())
        assertEquals(waiting, p.locationStore.list().map { it.uuid })

        advance(20_000)
        env.setWifi()
        env.reconnectWifi(ShadowNetwork.newInstance(11))
        runCurrent()
        assertEquals(waiting, uuids(server.accepted()).drop(2))
        assertEquals(0, p.locationStore.count())
        assertHealthy(p)
    }

    @Test
    fun `a polygon geofence drives continuous location in geofences mode and its transitions are posted`() = runTest {
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        // A 200 m square centred 500 m north; its padded enclosing circle has a radius of about 156 m.
        val zone = Fixtures.polygon(identifier = "zone", vertices = Fixtures.square(gps.latitudeAt(500.0), Fixtures.LNG, 100.0))
        p.geofences.add(listOf(zone))
        p.providers.locationBackend.lastLocation = gps.fix(0.0, speed = 0f)
        p.engine.startGeofences()
        runCurrent()
        val circle = p.providers.geofenceBackend.registered.getValue("zone")
        assertEquals(gps.latitudeAt(500.0), circle.latitude, 1e-6)
        assertTrue(circle.radius > 141f && circle.onEntry && circle.onExit)
        assertFalse(p.providers.locationBackend.isRequesting)

        // Approaching, after the OS's initial-trigger window: the enclosing circle is entered.
        advance(3 * MINUTE)
        p.geofences.onGeofenceTransitions(listOf(OsGeofenceTransition("zone", GeofenceAction.ENTER, gps.fix(380.0))))
        runCurrent()
        assertTrue(p.geofences.needsContinuousLocation.value)
        assertEquals(DesiredAccuracy.HIGH, p.providers.locationBackend.active.values.single().accuracy)
        assertTrue(server.receivedEvents().none { it == "geofence" })

        advance(5_000)
        emit(p, gps.fix(480.0))
        val enter = server.received().last()
        assertEquals("geofence", enter.getString("event"))
        assertEquals("zone" to "ENTER", enter.getJSONObject("geofence").let { it.getString("identifier") to it.getString("action") })
        assertEquals(gps.latitudeAt(480.0), Wire.latitude(enter), 1e-9)

        advance(20_000)
        emit(p, gps.fix(700.0))
        assertEquals("EXIT", server.received().last().getJSONObject("geofence").getString("action"))
        p.geofences.onGeofenceTransitions(listOf(OsGeofenceTransition("zone", GeofenceAction.EXIT, gps.fix(720.0))))
        runCurrent()
        assertFalse(p.geofences.needsContinuousLocation.value)
        assertFalse(p.providers.locationBackend.isRequesting)
        val actions = server.received()
            .filter { it.getString("event") == "geofence" }
            .map { it.getJSONObject("geofence").getString("action") }
        assertEquals(listOf("ENTER", "EXIT"), actions)
        assertHealthy(p)
    }

    @Test
    fun `stopOnStationary ends tracking with tracking_stop stop_on_stationary after the stop timeout`() = runTest {
        val p = newProcess()
        p.engine.ready(config(sections = mapOf("geolocation" to JSONObject().put("stopOnStationary", true))), reset = true)
        start(p)
        advance(10_000)
        emit(p, gps.fix(100.0))
        advance(3_000)
        emit(p, gps.fix(140.0))

        advance(5 * MINUTE)

        assertEquals(
            listOf("tracking_start", "motionchange", "motionchange", "location", "motionchange", "tracking_stop"),
            server.receivedEvents(),
        )
        assertEquals("stop_on_stationary", server.received().last().getString("reason"))
        assertFalse(p.configStore.runtime.value.enabled)
        assertTrue(alarms.scheduled().isEmpty())
        assertFalse(p.providers.locationBackend.isRequesting)
        assertHealthy(p)
    }

    @Test
    fun `stopAfterElapsedMinutes ends tracking with tracking_stop stop_after_elapsed`() = runTest {
        val p = newProcess()
        p.engine.ready(config(sections = mapOf("geolocation" to JSONObject().put("stopAfterElapsedMinutes", 2))), reset = true)
        start(p)

        advance(2 * MINUTE - 1)
        assertTrue(p.configStore.runtime.value.enabled)
        advance(1)

        assertEquals("tracking_stop", server.receivedEvents().last())
        assertEquals("stop_after_elapsed", server.received().last().getString("reason"))
        assertFalse(p.configStore.runtime.value.enabled)
        assertTrue(alarms.scheduled().isEmpty())
        assertHealthy(p)
    }

    @Test
    fun `setConfig while tracking reschedules the heartbeat and redirects uploads`() = runTest {
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        val t0 = clock.elapsedRealtime()
        start(p)
        assertEquals(t0 + MIN_MS, alarms.listener()!!.triggerAtMs)

        advance(30_000)
        val newUrl = server.url("/v2/locations")
        p.engine.setConfig(
            JSONObject()
                .put("heartbeat", JSONObject().put("minInterval", 120).put("maxInterval", 200))
                .put("http", JSONObject().put("url", newUrl)),
        )
        runCurrent()
        assertEquals(t0 + 120_000, alarms.listener()!!.triggerAtMs)
        assertEquals(120, p.heartbeat.status().minInterval)

        advance(90_000)
        alarms.fireListener()
        runCurrent()
        val last = server.requests.last()
        assertEquals("/v2/locations", last.path)
        assertEquals(listOf("heartbeat"), last.events)
        assertEquals(t0 + 240_000, alarms.listener()!!.triggerAtMs)
        assertHealthy(p)
    }

    @Test
    fun `removing the task stops tracking with reason terminate unless stopOnTerminate is false`() = runTest {
        val p = newProcess()
        p.engine.ready(config(sections = mapOf("app" to JSONObject().put("stopOnTerminate", false))), reset = true)
        start(p)

        p.engine.onTerminate()
        runCurrent()
        assertTrue(p.configStore.runtime.value.enabled)
        assertEquals(listOf("tracking_start", "motionchange"), server.receivedEvents())

        p.engine.setConfig(JSONObject().put("app", JSONObject().put("stopOnTerminate", true)))
        p.engine.onTerminate()
        runCurrent()
        assertEquals("tracking_stop", server.receivedEvents().last())
        assertEquals("terminate", server.received().last().getString("reason"))
        assertFalse(p.configStore.runtime.value.enabled)
        assertTrue(alarms.scheduled().isEmpty())
        assertHealthy(p)
    }

    @Test
    fun `after the CPU slept past the stop timeout, the heartbeat alarm wakes the engine to record it`() = runTest {
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        start(p)
        advance(10_000)
        emit(p, gps.fix(100.0))
        advance(3_000)
        emit(p, gps.fix(140.0))
        assertTrue(p.configStore.runtime.value.isMoving)

        // Deep sleep: the clocks move 6 minutes, coroutine delays do not (they only run while the CPU is awake).
        clock.advance(6 * MINUTE)
        runCurrent()
        assertTrue(p.configStore.runtime.value.isMoving)

        // The heartbeat alarm wakes the device.
        alarms.fireListener()
        runCurrent()

        assertEquals(listOf("heartbeat", "motionchange"), server.receivedEvents().takeLast(2))
        val stopped = server.received().last()
        assertFalse(stopped.getBoolean("is_moving"))
        assertEquals(gps.latitudeAt(140.0), Wire.latitude(stopped), 1e-9)
        assertFalse(p.configStore.runtime.value.isMoving)
        assertHealthy(p)
    }

    @Test
    fun `activity recognition moves the device and its activity and mock flag reach the wire`() = runTest {
        val p = newProcess()
        p.engine.ready(config(), reset = true)
        start(p)

        advance(30_000)
        p.engine.onActivitySamples(listOf(ActivitySample(ActivityType.STILL, 10), ActivitySample(ActivityType.IN_VEHICLE, 90)))
        runCurrent()
        val moving = server.received().last()
        assertEquals("motionchange", moving.getString("event"))
        assertTrue(moving.getBoolean("is_moving"))
        assertJsonEquals(JSONObject("""{"type":"in_vehicle","confidence":90}"""), moving.getJSONObject("activity"))
        assertEquals(ActivitySample(ActivityType.IN_VEHICLE, 90), p.eventsOf<TrackingEvent.ActivityChange>().single().activity)

        advance(3_000)
        emit(p, gps.fix(60.0).copy(isMock = true))
        val mocked = server.received().last()
        assertEquals("location", mocked.getString("event"))
        assertTrue(mocked.getBoolean("mock"))
        assertEquals("in_vehicle", mocked.getJSONObject("activity").getString("type"))

        // Confidently still, then the stop timeout (from the last evidence of motion, the 60 m fix).
        p.engine.onActivitySamples(listOf(ActivitySample(ActivityType.STILL, 95)))
        runCurrent()
        advance(5 * MINUTE)
        val stopped = server.received().last()
        assertEquals("motionchange", stopped.getString("event"))
        assertFalse(stopped.getBoolean("is_moving"))
        assertEquals("still", stopped.getJSONObject("activity").getString("type"))
        assertHealthy(p)
    }

    @Test
    fun `a backend without native dwell gets a synthesized DWELL after loiteringDelay`() = runTest {
        val providers = FakeProviderFactory(ProviderKind.ANDROID, geofenceBackend = FakeGeofenceBackend(supportsDwell = false))
        val p = newProcess(providers = providers)
        p.engine.ready(config(), reset = true)
        start(p)
        val home = Fixtures.circle(identifier = "home", radius = 200f, notifyOnDwell = true, loiteringDelay = 60_000L)
        p.geofences.add(listOf(home))
        runCurrent()
        val os = providers.geofenceBackend.registered.getValue("home")
        assertFalse(os.onDwell)
        assertTrue(os.onEntry && os.onExit)

        advance(10_000)
        p.geofences.onGeofenceTransitions(listOf(OsGeofenceTransition("home", GeofenceAction.ENTER, gps.fix(10.0))))
        runCurrent()
        advance(60_000 - 1)
        assertEquals(listOf("ENTER"), geofenceActions())
        advance(1)
        assertEquals(listOf("ENTER", "DWELL"), geofenceActions())
        assertEquals("android", server.received().last().getString("backend"))
        assertHealthy(p)
    }

    // ------------------------------------------------------------------ the JS API

    /** [BridgeServices] over one [FullStackProcess]: what `ComponentServices` is over Components. */
    private class ProcessServices(private val p: FullStackProcess, override val logStore: LogStore) : BridgeServices {
        override val engine get() = p.engine
        override val positions get() = p.positions
        override val locationStore get() = p.locationStore
        override val recordFactory get() = p.recordFactory
        override val syncer get() = p.syncer
        override val geofences get() = p.geofences
        override val heartbeat get() = p.heartbeat
        override val device get() = p.device
        override val deviceInfo: DeviceInfoProvider = DefaultDeviceInfoProvider(p.app, lazy { p.providers })
        override val deviceSettings: DeviceSettings = FakeDeviceSettings()
        override val permissions get() = p.permissions
        override val odometer get() = p.odometer
        override val configStore get() = p.configStore
        override val events get() = p.events
        override val dispatchers get() = p.dispatchers
    }

    private suspend fun assertRejects(code: ErrorCode, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: TrackingException) {
            assertEquals(e.message, code, e.code)
            return
        }
        throw AssertionError("expected a $code rejection")
    }

    @Test
    fun `the plugin handlers drive the real components with JS-shaped options and events`() = runTest {
        val serverUp = AtomicBoolean(false)
        server.respond = { _, _, _ -> if (serverUp.get()) IngestServer.ok() else IngestServer.status(503) }
        val p = newProcess()
        val js = PluginHandlers(ProcessServices(p, logs), readyFlag = AtomicBoolean(false))
        val forwarded = mutableListOf<Pair<String, JSONObject>>()
        js.forwardEvents { name, payload -> forwarded += name to payload() }
        val w0 = clock.now()

        assertRejects(ErrorCode.NOT_READY) { js.start() }
        val ready = js.ready(JSONObject().put("config", config()))
        assertFalse(ready.getBoolean("enabled"))
        assertEquals(server.url(), ready.getJSONObject("config").getJSONObject("http").getString("url"))
        assertEquals(180, ready.getJSONObject("config").getJSONObject("heartbeat").getInt("minInterval"))

        val origin = gps.fix(0.0, speed = 0f)
        p.providers.locationBackend.lastLocation = origin
        p.providers.locationBackend.currentLocation = origin
        val started = js.start()
        runCurrent()
        assertTrue(started.getBoolean("enabled"))
        assertEquals("location", started.getString("trackingMode"))
        assertEquals("gms", started.getString("backend"))

        // The server is down: the records are queued and visible to JS (without sent_at).
        assertEquals(2, js.getCount().getInt("count"))
        val queued = js.getLocations(JSONObject()).getJSONArray("locations")
        assertEquals(listOf("tracking_start", "motionchange"), (0 until queued.length()).map { queued.getJSONObject(it).getString("event") })
        assertFalse(queued.getJSONObject(0).has("sent_at"))
        val status = js.getHeartbeatStatus()
        assertEquals("listener_with_backup", status.getString("strategy"))
        assertEquals(Iso8601.format(w0 + MIN_MS), status.getString("nextHeartbeatAt"))
        assertEquals(Iso8601.format(w0), status.getString("lastRecordAt"))
        assertEquals(0, status.getInt("pendingHeartbeats"))
        assertRejects(ErrorCode.HTTP_ERROR) { js.sync() }

        val inserted = js.insertLocation(
            JSONObject().put(
                "location",
                JSONObject()
                    .put("coords", JSONObject().put("latitude", gps.latitudeAt(50.0)).put("longitude", Fixtures.LNG).put("accuracy", 7))
                    .put("extras", JSONObject().put("source", "manual")),
            ),
        ).getString("uuid")
        runCurrent()
        assertEquals(3, js.getCount().getInt("count"))

        serverUp.set(true)
        val synced = js.sync().getJSONArray("locations")
        assertEquals(3, synced.length())
        assertEquals(inserted, synced.getJSONObject(2).getString("uuid"))
        assertEquals(0, js.getCount().getInt("count"))
        val manual = server.accepted().single { it.getString("uuid") == inserted }
        assertEquals("location", manual.getString("event"))
        assertEquals("manual", manual.getJSONObject("extras").getString("source"))

        assertEquals(1000.0, js.setOdometer(JSONObject().put("odometer", 1000)).getDouble("odometer"), 0.0)
        assertEquals(1000.0, js.getState().getDouble("odometer"), 0.0)

        js.addGeofence(
            JSONObject().put(
                "geofence",
                JSONObject().put("identifier", "home").put("latitude", Fixtures.LAT).put("longitude", Fixtures.LNG)
                    .put("radius", 200).put("notifyOnDwell", true).put("extras", JSONObject().put("a", 1)),
            ),
        )
        runCurrent()
        assertTrue(js.geofenceExists(JSONObject().put("identifier", "home")).getBoolean("exists"))
        assertEquals("home", js.getGeofences().getJSONArray("geofences").getJSONObject(0).getString("identifier"))
        assertTrue(p.providers.geofenceBackend.registered.getValue("home").onDwell)
        assertJsonEquals(
            JSONObject("""{"enabled":true,"gps":true,"network":true,"permission":"always","accuracy":"precise","backend":"gms"}"""),
            js.getProviderState(),
        )

        val stopped = js.stop()
        runCurrent()
        assertFalse(stopped.getBoolean("enabled"))
        assertEquals("tracking_stop", server.acceptedEvents().last())

        // Every event of the real components reached the JS forwarder with a payload built by EventJson.
        val names = forwarded.map { it.first }.toSet()
        assertTrue(names.containsAll(setOf("enabledchange", "motionchange", "location", "http", "geofenceschange")))
        val motion = forwarded.first { it.first == "motionchange" }.second
        assertFalse(motion.getBoolean("isMoving"))
        assertEquals(origin.latitude, motion.getJSONObject("location").getJSONObject("coords").getDouble("latitude"), 1e-9)
        assertEquals(listOf(true, false), forwarded.filter { it.first == "enabledchange" }.map { it.second.getBoolean("enabled") })
        assertHealthy(p)
    }
}
