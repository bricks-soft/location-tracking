package com.brickssoft.locationtracking.api

import com.brickssoft.locationtracking.bridge.BridgeServices
import com.brickssoft.locationtracking.bridge.FakeDeviceInfoProvider
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.integration.FullStackProcess
import com.brickssoft.locationtracking.integration.FullStackTestBase
import com.brickssoft.locationtracking.logging.LogStore
import com.brickssoft.locationtracking.model.EventJson
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import com.brickssoft.locationtracking.testing.FakeDeviceSettings
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The companion API over the real component graph ([FullStackProcess]): a listener connected to the process's record
 * hooks and event bus receives every record type exactly once, with the JSON the server received (minus `sent_at`),
 * each record before the events that carry it, and every event the bus emitted. The session is driven through the
 * [LocationTrackingNative] facade, as a companion plugin would.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class NativeFullStackTest : FullStackTestBase() {
    private val base = LocationTrackingNative.LISTENER_META_DATA

    @Before
    fun setUpNative() {
        NativeListeners.resetForTests()
        ListenerLog.clear()
    }

    @After
    fun tearDownNative() {
        LocationTrackingNative.backendOverride = null
        NativeListeners.resetForTests()
    }

    /** Runs a facade call on the process scope and returns its result. */
    private fun <T> TestScope.native(call: (NativeCallback<T>) -> Unit): T {
        val probe = CallbackProbe<T>()
        call(probe)
        runCurrent()
        return probe.await().getOrThrow()
    }

    @Test
    fun `every record type and every event reaches the listener, records first`() = runTest {
        val p = newProcess()
        LocationTrackingNative.backendOverride = { NativeBackend(ProcessServices(p, logs), p.scope) }
        NativeListeners.connect(app, p.recordHooks, p.events, listOf(base to RecordingListener()))

        native<JSONObject> { LocationTrackingNative.ready(app, config(), true, it) }
        located(p, gps.fix(0.0, speed = 0f))
        native<JSONObject> { LocationTrackingNative.start(app, it) } // tracking_start, motionchange
        runCurrent()

        advance(MIN_MS)
        alarms.fireListener() // heartbeat
        runCurrent()

        val hq = JSONObject()
            .put("identifier", "premise:hq")
            .put("latitude", gps.latitudeAt(300.0))
            .put("longitude", Fixtures.LNG)
            .put("radius", 150)
        native<Unit> { LocationTrackingNative.addGeofence(app, hq, it) }
        advance(10_000)
        p.geofences.onGeofenceTransitions(listOf(OsGeofenceTransition("premise:hq", GeofenceAction.ENTER, gps.fix(290.0))))
        runCurrent() // geofence

        advance(10_000)
        emit(p, gps.fix(100.0)) // motionchange (moving)
        for (north in listOf(140.0, 180.0, 220.0, 260.0)) {
            advance(3_000)
            emit(p, gps.fix(north)) // location
        }

        advance(10_000)
        setLocation(enabled = true, gps = false, network = true) // providerchange

        val inserted = native<String> {
            LocationTrackingNative.insertLocation(
                app,
                JSONObject().put("coords", JSONObject().put("latitude", gps.latitudeAt(50.0)).put("longitude", Fixtures.LNG)),
                it,
            )
        }
        runCurrent()

        native<JSONObject> { LocationTrackingNative.stop(app, it) } // tracking_stop
        runCurrent()
        assertTrue(NativeThread.awaitIdle())

        val calls = ListenerLog.of(RecordingListener.NAME)
        val records = calls.filter { it.kind == "record" }
        assertEquals(
            setOf("tracking_start", "motionchange", "heartbeat", "geofence", "location", "providerchange", "tracking_stop"),
            records.map { it.name }.toSet(),
        )
        assertTrue(records.any { it.json.getString("uuid") == inserted })
        assertEquals("each record once", records.size, records.map { it.json.getString("uuid") }.toSet().size)

        // The same JSON the server received, without sent_at; nothing the server received is missing.
        val uploaded = server.received().associateBy { it.getString("uuid") }
        assertEquals(uploaded.keys, records.map { it.json.getString("uuid") }.toSet())
        for (call in records) {
            val onServer = JSONObject(uploaded.getValue(call.json.getString("uuid")).toString())
            onServer.remove("sent_at")
            assertJsonEquals(onServer, call.json)
        }

        // Every bus event, in emission order, with the JS name and payload.
        val events = calls.filter { it.kind == "event" }
        assertEquals(p.emitted.map { EventJson.name(it) }, events.map { it.name })
        for ((event, call) in p.emitted.zip(events)) assertJsonEquals(EventJson.payload(event), call.json)
        assertTrue(p.emitted.any { it is TrackingEvent.Geofence } && p.emitted.any { it is TrackingEvent.Heartbeat })

        // A record's onRecord precedes every event that carries it.
        val recordIndex = calls.withIndex().filter { it.value.kind == "record" }
            .associate { it.value.json.getString("uuid") to it.index }
        for ((index, call) in calls.withIndex()) {
            if (call.kind != "event") continue
            val carried = call.json.optJSONObject("location")?.optString("uuid")
                ?: call.json.optString("uuid").takeIf { call.name == "location" }
                ?: continue
            val at = recordIndex[carried] ?: continue
            assertTrue("${call.name} of $carried came before its record", at < index)
        }
        assertTrue(calls.all { it.thread == LocationTrackingNative.THREAD_NAME })
        assertHealthy(p)
    }

    /** [BridgeServices] over one [FullStackProcess] (the facade's handlers run on the real components). */
    private class ProcessServices(private val p: FullStackProcess, override val logStore: LogStore) : BridgeServices {
        override val engine get() = p.engine
        override val positions get() = p.positions
        override val locationStore get() = p.locationStore
        override val recordFactory get() = p.recordFactory
        override val syncer get() = p.syncer
        override val geofences get() = p.geofences
        override val heartbeat get() = p.heartbeat
        override val device get() = p.device
        override val deviceInfo = FakeDeviceInfoProvider()
        override val deviceSettings = FakeDeviceSettings()
        override val permissions get() = p.permissions
        override val odometer get() = p.odometer
        override val configStore get() = p.configStore
        override val events get() = p.events
        override val dispatchers get() = p.dispatchers
        override val recordHooks get() = p.recordHooks
    }
}
