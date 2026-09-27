package com.brickssoft.locationtracking.bridge

import android.app.Activity
import android.content.Intent
import com.brickssoft.locationtracking.config.TrackingMode
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.logging.LogQuery
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.model.BatteryOptimizationStatus
import com.brickssoft.locationtracking.model.BatteryOptimizationStatusJson
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.DeviceInfoJson
import com.brickssoft.locationtracking.model.EventJson
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.GeofenceJson
import com.brickssoft.locationtracking.model.HeartbeatStatusJson
import com.brickssoft.locationtracking.model.HeartbeatStrategy
import com.brickssoft.locationtracking.model.HttpResult
import com.brickssoft.locationtracking.model.PermissionLevel
import com.brickssoft.locationtracking.model.PowerManagerInfo
import com.brickssoft.locationtracking.model.PowerManagerInfoJson
import com.brickssoft.locationtracking.model.ProviderStateJson
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.RecordJson
import com.brickssoft.locationtracking.model.SensorsJson
import com.brickssoft.locationtracking.config.BackgroundPermissionRationale
import com.brickssoft.locationtracking.permission.PermissionHost
import com.brickssoft.locationtracking.permission.PermissionManager
import com.brickssoft.locationtracking.permission.PermissionState
import com.brickssoft.locationtracking.permission.PermissionType
import com.brickssoft.locationtracking.position.CurrentPositionOptions
import com.brickssoft.locationtracking.position.PositionService
import com.brickssoft.locationtracking.position.WatchPositionOptions
import com.brickssoft.locationtracking.settings.DeviceSettings
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.FakeOdometer
import com.brickssoft.locationtracking.testing.FakePermissionManager
import com.brickssoft.locationtracking.testing.FakePositionService
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import com.brickssoft.locationtracking.testing.testDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PluginHandlersTest {
    private val scheduler = TestCoroutineScheduler()
    private val services = TestServices(dispatchers = testDispatchers(scheduler))
    private val readyFlag = AtomicBoolean(false)
    private var activity: Activity? = null
    private val handlers = PluginHandlers(services, { activity }, readyFlag)

    private val host = object : PermissionHost {
        override val activity: Activity? get() = this@PluginHandlersTest.activity

        override fun requestAliases(aliases: List<String>, onDone: () -> Unit) = onDone()
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun test(block: suspend () -> Unit) = runTest(scheduler) { block() }

    private suspend fun makeReady() {
        handlers.ready(JSONObject())
    }

    private suspend fun assertRejects(code: ErrorCode, block: suspend () -> Any?): TrackingException {
        try {
            block()
        } catch (e: TrackingException) {
            assertEquals(e.message, code, e.code)
            return e
        }
        fail("expected TrackingException $code")
        throw AssertionError()
    }

    private fun json(text: String) = JSONObject(text)

    private fun op(block: suspend () -> Any?): suspend () -> Any? = block

    /** One representative invocation per JS method (except addListener / removeAllListeners). */
    private val invocations: Map<String, suspend () -> Any?> = linkedMapOf(
        "ready" to op { handlers.ready(JSONObject()) },
        "setConfig" to op { handlers.setConfig(json("""{"config":{}}""")) },
        "reset" to op { handlers.reset(JSONObject()) },
        "getState" to op { handlers.getState() },
        "start" to op { handlers.start() },
        "startGeofences" to op { handlers.startGeofences() },
        "stop" to op { handlers.stop() },
        "changePace" to op { handlers.changePace(json("""{"isMoving":true}""")) },
        "getCurrentPosition" to op { handlers.getCurrentPosition(JSONObject()) },
        "watchPosition" to op { handlers.watchPosition("w-1", JSONObject(), RecordingWatchTarget()) },
        "clearWatch" to op { handlers.clearWatch(json("""{"id":"w-1"}""")) },
        "getOdometer" to op { handlers.getOdometer() },
        "setOdometer" to op { handlers.setOdometer(json("""{"odometer":1}""")) },
        "resetOdometer" to op { handlers.resetOdometer() },
        "getLocations" to op { handlers.getLocations(JSONObject()) },
        "getCount" to op { handlers.getCount() },
        "insertLocation" to op {
            handlers.insertLocation(json("""{"location":{"coords":{"latitude":1,"longitude":2}}}"""))
        },
        "destroyLocations" to op { handlers.destroyLocations() },
        "destroyLocation" to op { handlers.destroyLocation(json("""{"uuid":"x"}""")) },
        "sync" to op { handlers.sync() },
        "addGeofence" to op { handlers.addGeofence(json("""{"geofence":{"identifier":"a","latitude":1,"longitude":2,"radius":50}}""")) },
        "addGeofences" to op { handlers.addGeofences(json("""{"geofences":[]}""")) },
        "removeGeofence" to op { handlers.removeGeofence(json("""{"identifier":"a"}""")) },
        "removeGeofences" to op { handlers.removeGeofences(JSONObject()) },
        "getGeofences" to op { handlers.getGeofences() },
        "getGeofence" to op { handlers.getGeofence(json("""{"identifier":"a"}""")) },
        "geofenceExists" to op { handlers.geofenceExists(json("""{"identifier":"a"}""")) },
        "getHeartbeatStatus" to op { handlers.getHeartbeatStatus() },
        "getProviderState" to op { handlers.getProviderState() },
        "isPowerSaveMode" to op { handlers.isPowerSaveMode() },
        "getBatteryOptimizationStatus" to op { handlers.getBatteryOptimizationStatus() },
        "openBatteryOptimizationSettings" to op { handlers.openBatteryOptimizationSettings() },
        "getPowerManagerInfo" to op { handlers.getPowerManagerInfo() },
        "openPowerManagerSettings" to op { handlers.openPowerManagerSettings() },
        "openLocationSettings" to op { handlers.openLocationSettings() },
        "openAppSettings" to op { handlers.openAppSettings() },
        "getDeviceInfo" to op { handlers.getDeviceInfo() },
        "getSensors" to op { handlers.getSensors() },
        "checkPermissions" to op { handlers.checkPermissions() },
        "requestPermissions" to op { handlers.requestPermissions(JSONObject(), host) },
        "log" to op { handlers.log(json("""{"level":"info","message":"m"}""")) },
        "getLog" to op { handlers.getLog(JSONObject()) },
        "destroyLog" to op { handlers.destroyLog() },
        "uploadLog" to op { handlers.uploadLog(json("""{"url":"https://example.com/log"}""")) },
        "emailLog" to op { handlers.emailLog(json("""{"email":"a@b.c"}""")) },
    )

    // ---- NOT_READY rule

    @Test
    fun `invocation table covers every JS method`() {
        assertEquals(PluginMethodsTest.JS_METHODS - PluginMethodsTest.BASE_METHODS, invocations.keys)
    }

    @Test
    fun `every method except the allow-list rejects NOT_READY before ready`() = test {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for ((name, invoke) in invocations) {
            if (name == "ready") continue
            if (name in PluginHandlers.ALLOWED_BEFORE_READY) {
                try {
                    invoke()
                } catch (e: TrackingException) {
                    assertTrue("$name must not be gated: ${e.code}", e.code != ErrorCode.NOT_READY)
                }
            } else {
                val e = assertRejects(ErrorCode.NOT_READY) { invoke() }
                assertTrue(e.message!!.contains(name))
            }
        }
        assertTrue("no gated call reached the engine", services.engine.calls.isEmpty())
        assertTrue(services.fakePositions.watches.isEmpty())
        assertEquals(0, handlers.watches.size)
        assertFalse(handlers.isReady)
    }

    @Test
    fun `allow-list matches the contract`() {
        assertEquals(
            setOf(
                "ready", "getState", "checkPermissions", "requestPermissions", "getDeviceInfo", "getSensors", "log",
                "getLog", "getProviderState", "openBatteryOptimizationSettings", "openPowerManagerSettings",
                "openLocationSettings", "openAppSettings",
            ),
            PluginHandlers.ALLOWED_BEFORE_READY,
        )
    }

    @Test
    fun `every method works after ready`() = test {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        makeReady()
        for ((name, invoke) in invocations) {
            try {
                invoke()
            } catch (e: TrackingException) {
                fail("$name failed after ready: ${e.code} ${e.message}")
            }
        }
    }

    @Test
    fun `a failed ready keeps the gate closed`() = test {
        services.engine.failWith = TrackingException(ErrorCode.IO_ERROR, "disk")

        assertRejects(ErrorCode.IO_ERROR) { handlers.ready(JSONObject()) }

        assertFalse(handlers.isReady)
        assertFalse(readyFlag.get())
        assertRejects(ErrorCode.NOT_READY) { handlers.start() }
    }

    @Test
    fun `the default ready flag is shared by every instance in the process`() = test {
        val first = PluginHandlers(TestServices(dispatchers = testDispatchers(scheduler)))
        val second = PluginHandlers(TestServices(dispatchers = testDispatchers(scheduler)))
        val wasReady = PluginHandlers.processReady.get()
        try {
            PluginHandlers.processReady.set(false)
            first.ready(JSONObject())
            assertTrue(second.isReady)
        } finally {
            PluginHandlers.processReady.set(wasReady)
        }
    }

    // ---- lifecycle and config

    @Test
    fun `ready passes config and reset flag and returns the state`() = test {
        val state = handlers.ready(json("""{"config":{"heartbeat":{"minInterval":240}},"reset":false}"""))

        assertTrue(handlers.isReady)
        assertEquals(listOf("ready(reset=false)", "updateRuntime"), services.configStore.calls.toList())
        assertEquals(240, services.configStore.config.value.heartbeat.minInterval)
        assertEquals(false, state.getBoolean("enabled"))
        assertEquals("location", state.getString("trackingMode"))
        assertTrue(state.has("config"))
    }

    @Test
    fun `ready resets by default and rejects wrongly typed options`() = test {
        handlers.ready(JSONObject())
        assertEquals("ready(reset=true)", services.configStore.calls.first())

        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.ready(json("""{"reset":"no"}""")) }
        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.ready(json("""{"config":[1]}""")) }
    }

    @Test
    fun `setConfig and reset go to the engine`() = test {
        makeReady()

        handlers.setConfig(json("""{"config":{"geolocation":{"distanceFilter":50}}}"""))
        assertEquals(50.0, services.configStore.config.value.geolocation.distanceFilter, 0.0)

        handlers.reset(json("""{"config":{"geolocation":{"stopTimeout":9}}}"""))
        assertEquals(10.0, services.configStore.config.value.geolocation.distanceFilter, 0.0)
        assertEquals(9, services.configStore.config.value.geolocation.stopTimeout)
        assertEquals(listOf("ready", "setConfig", "reset"), services.engine.calls.toList())

        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.setConfig(JSONObject()) }
    }

    @Test
    fun `start, startGeofences and stop return the new state`() = test {
        makeReady()

        val started = handlers.start()
        assertTrue(started.getBoolean("enabled"))
        assertEquals("location", started.getString("trackingMode"))

        val geofences = handlers.startGeofences()
        assertEquals(TrackingMode.GEOFENCES.wire, geofences.getString("trackingMode"))

        assertFalse(handlers.stop().getBoolean("enabled"))
        assertFalse(handlers.getState().getBoolean("enabled"))
    }

    @Test
    fun `engine errors keep their code`() = test {
        makeReady()
        services.engine.failWith = TrackingException(ErrorCode.PERMISSION_DENIED, "no location permission")

        val e = assertRejects(ErrorCode.PERMISSION_DENIED) { handlers.start() }

        assertEquals("no location permission", e.message)
        assertEquals(Rejection("no location permission", ErrorCode.PERMISSION_DENIED), Rejection.of(e))
    }

    @Test
    fun `changePace requires a boolean`() = test {
        makeReady()

        assertNull(handlers.changePace(json("""{"isMoving":true}""")))

        assertEquals(listOf(true), services.engine.paceChanges.toList())
        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.changePace(JSONObject()) }
        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.changePace(json("""{"isMoving":"yes"}""")) }
    }

    // ---- positions

    @Test
    fun `getCurrentPosition parses options and returns the record without sent_at`() = test {
        makeReady()
        val record = Fixtures.record(event = RecordEvent.CURRENT_POSITION)
        services.fakePositions.currentResult = record

        val result = handlers.getCurrentPosition(
            json(
                """{"samples":5,"timeout":10000,"maximumAge":2000,"desiredAccuracy":"balanced",
                   "persist":false,"extras":{"trip":7}}""",
            ),
        )

        assertEquals(
            CurrentPositionOptions(5, 10_000L, 2_000L, DesiredAccuracy.BALANCED, false, """{"trip":7}"""),
            services.fakePositions.currentCalls.single(),
        )
        assertJsonEquals(RecordJson.toJson(record), result)
        assertFalse(result.has("sent_at"))
    }

    @Test
    fun `getCurrentPosition uses defaults and maps errors`() = test {
        makeReady()

        handlers.getCurrentPosition(JSONObject())
        assertEquals(CurrentPositionOptions(), services.fakePositions.currentCalls.single())

        services.fakePositions.currentError = TrackingException(ErrorCode.TIMEOUT, "no fix")
        assertRejects(ErrorCode.TIMEOUT) { handlers.getCurrentPosition(JSONObject()) }
        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.getCurrentPosition(json("""{"desiredAccuracy":"best"}""")) }
        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.getCurrentPosition(json("""{"samples":0}""")) }
    }

    @Test
    fun `watchPosition delivers locations and errors until clearWatch`() = test {
        makeReady()
        val target = RecordingWatchTarget()
        val record = Fixtures.record(event = RecordEvent.WATCH_POSITION)

        handlers.watchPosition("cb-1", json("""{"interval":5000,"persist":true}"""), target)

        val (options, callback) = services.fakePositions.watches.getValue("cb-1")
        assertEquals(WatchPositionOptions(5_000L, DesiredAccuracy.HIGH, true, null), options)
        assertTrue("cb-1" in handlers.watches)

        services.fakePositions.emitWatch("cb-1", record)
        services.fakePositions.emitWatch("cb-1", null, TrackingException(ErrorCode.LOCATION_DISABLED, "gps off"))

        assertEquals(1, target.delivered.size)
        assertJsonEquals(RecordJson.toJson(record), target.delivered.single())
        assertEquals(listOf(ErrorCode.LOCATION_DISABLED to "gps off"), target.failures.toList())

        assertNull(handlers.clearWatch(json("""{"id":"cb-1"}""")))

        assertEquals(1, target.releases)
        assertTrue(services.fakePositions.watches.isEmpty())
        assertEquals(0, handlers.watches.size)
        callback(record, null) // a late delivery after clearWatch is dropped
        assertEquals(1, target.delivered.size)
    }

    @Test
    fun `clearWatch of an unknown id resolves and requires an id`() = test {
        makeReady()

        assertNull(handlers.clearWatch(json("""{"id":"nope"}""")))
        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.clearWatch(JSONObject()) }
    }

    @Test
    fun `clearWatch stops the position watch before releasing, and releases even if stopping fails`() = test {
        val order = mutableListOf<String>()
        val positions = object : PositionService by FakePositionService() {
            override fun clearWatch(id: String): Boolean {
                order += "positions"
                throw TrackingException(ErrorCode.UNAVAILABLE, "provider gone")
            }
        }
        val h = PluginHandlers(TestServices(positions = positions, dispatchers = testDispatchers(scheduler)), readyFlag = readyFlag)
        readyFlag.set(true)
        val target = object : WatchTarget by RecordingWatchTarget() {
            override fun release() {
                order += "target"
            }
        }
        h.watches.add("cb-1", target)

        assertRejects(ErrorCode.UNAVAILABLE) { h.clearWatch(json("""{"id":"cb-1"}""")) }

        assertEquals(listOf("positions", "target"), order)
        assertEquals(0, h.watches.size)
    }

    @Test
    fun `watchPosition rejects bad options without registering`() = test {
        makeReady()
        val target = RecordingWatchTarget()

        assertRejects(ErrorCode.INVALID_ARGUMENT) {
            handlers.watchPosition("cb-1", json("""{"interval":-1}"""), target)
        }

        assertEquals(0, handlers.watches.size)
        assertTrue(services.fakePositions.watches.isEmpty())
    }

    @Test
    fun `watchPosition releases the target when the position service throws`() = test {
        val failing = object : PositionService by FakePositionService() {
            override fun watchPosition(
                id: String,
                o: WatchPositionOptions,
                callback: (com.brickssoft.locationtracking.model.Record?, TrackingException?) -> Unit,
            ) = throw TrackingException(ErrorCode.PERMISSION_DENIED, "denied")
        }
        val h = PluginHandlers(TestServices(positions = failing, dispatchers = testDispatchers(scheduler)), readyFlag = readyFlag)
        readyFlag.set(true)
        val target = RecordingWatchTarget()

        assertRejects(ErrorCode.PERMISSION_DENIED) { h.watchPosition("cb-1", JSONObject(), target) }

        assertEquals(1, target.releases)
        assertEquals(0, h.watches.size)
    }

    @Test
    fun `a second watch with the same id replaces and releases the first`() = test {
        makeReady()
        val first = RecordingWatchTarget()
        val second = RecordingWatchTarget()

        handlers.watchPosition("cb-1", JSONObject(), first)
        handlers.watchPosition("cb-1", JSONObject(), second)
        services.fakePositions.emitWatch("cb-1", Fixtures.record())

        assertEquals(1, first.releases)
        assertTrue(first.delivered.isEmpty())
        assertEquals(1, second.delivered.size)
    }

    @Test
    fun `releaseAllWatches releases every call and clears the position service`() = test {
        makeReady()
        val a = RecordingWatchTarget()
        val b = RecordingWatchTarget()
        handlers.watchPosition("a", JSONObject(), a)
        handlers.watchPosition("b", JSONObject(), b)

        handlers.releaseAllWatches()

        assertEquals(1, a.releases)
        assertEquals(1, b.releases)
        assertTrue(services.fakePositions.watches.isEmpty())
        assertEquals(0, handlers.watches.size)
    }

    // ---- odometer

    @Test
    fun `odometer get, set and reset`() = test {
        val h = PluginHandlers(
            TestServices(odometer = FakeOdometer(12.5), dispatchers = testDispatchers(scheduler)),
            readyFlag = AtomicBoolean(true),
        )

        assertJsonEquals("""{"odometer":12.5}""", h.getOdometer())
        assertJsonEquals("""{"odometer":1000}""", h.setOdometer(json("""{"odometer":1000}""")))
        assertJsonEquals("""{"odometer":0}""", h.resetOdometer())
        assertRejects(ErrorCode.INVALID_ARGUMENT) { h.setOdometer(json("""{"odometer":-1}""")) }
        assertRejects(ErrorCode.INVALID_ARGUMENT) { h.setOdometer(JSONObject()) }
    }

    // ---- records

    @Test
    fun `getLocations, getCount, destroyLocation and destroyLocations`() = test {
        makeReady()
        val records = (1..3).map { Fixtures.record(uuid = "u$it", recordedAt = Fixtures.RECORDED_AT + it) }
        records.forEach { services.locationStore.insert(it) }

        val all = handlers.getLocations(JSONObject())
        assertJsonEquals(JSONObject().put("locations", RecordJson.toJsonArray(records)), all)
        assertEquals(2, handlers.getLocations(json("""{"limit":2}""")).getJSONArray("locations").length())
        assertJsonEquals("""{"count":3}""", handlers.getCount())

        assertJsonEquals("""{"deleted":true}""", handlers.destroyLocation(json("""{"uuid":"u1"}""")))
        assertJsonEquals("""{"deleted":false}""", handlers.destroyLocation(json("""{"uuid":"u1"}""")))
        assertJsonEquals("""{"count":2}""", handlers.destroyLocations())
        assertJsonEquals("""{"count":0}""", handlers.getCount())

        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.getLocations(json("""{"limit":-5}""")) }
        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.destroyLocation(JSONObject()) }
    }

    @Test
    fun `insertLocation queues the record for upload without emitting events`() = test {
        makeReady()

        val result = handlers.insertLocation(
            json(
                """{"location":{"coords":{"latitude":24.7,"longitude":46.6,"accuracy":8},
                   "timestamp":"2026-09-26T10:00:00.000Z","event":"location","is_moving":true,"extras":{"k":"v"}}}""",
            ),
        )

        val record = services.recordFactory.created.single()
        assertJsonEquals(JSONObject().put("uuid", record.uuid), result)
        assertEquals(24.7, record.location!!.latitude, 0.0)
        assertTrue(record.isMoving)
        assertEquals(listOf(record), services.locationStore.all)
        assertEquals(listOf(record), services.syncer.inserted.toList())
        assertTrue(services.events.events.isEmpty())
        assertTrue(services.heartbeat.recorded.isEmpty())
    }

    @Test
    fun `insertLocation validates its input`() = test {
        makeReady()
        val bad = listOf(
            """{}""",
            """{"location":{}}""",
            """{"location":{"coords":{"latitude":91,"longitude":0}}}""",
            """{"location":{"coords":{"latitude":0,"longitude":"x"}}}""",
            """{"location":{"coords":{"latitude":0,"longitude":0},"timestamp":"yesterday"}}""",
            """{"location":{"coords":{"latitude":0,"longitude":0},"event":"teleport"}}""",
            """{"location":{"coords":{"latitude":0,"longitude":0,"accuracy":-1}}}""",
        )
        for (text in bad) assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.insertLocation(json(text)) }

        assertTrue(services.locationStore.all.isEmpty())
        assertTrue(services.syncer.inserted.isEmpty())
    }

    @Test
    fun `sync returns the uploaded records and maps errors`() = test {
        makeReady()
        val uploaded = listOf(Fixtures.record(uuid = "a"), Fixtures.record(uuid = "b", event = RecordEvent.HEARTBEAT))
        services.syncer.syncResult = uploaded

        assertJsonEquals(JSONObject().put("locations", RecordJson.toJsonArray(uploaded)), handlers.sync())

        services.syncer.syncError = TrackingException(ErrorCode.NO_URL, "http.url is not set")
        assertRejects(ErrorCode.NO_URL) { handlers.sync() }
    }

    // ---- geofences

    @Test
    fun `geofences add, query and remove`() = test {
        makeReady()

        handlers.addGeofence(
            json("""{"geofence":{"identifier":"home","latitude":24.7,"longitude":46.6,"radius":150,"extras":{"k":1}}}"""),
        )
        handlers.addGeofences(
            json(
                """{"geofences":[
                    {"identifier":"work","latitude":24.8,"longitude":46.7,"radius":80,"notifyOnDwell":true},
                    {"identifier":"zone","vertices":[[24.0,46.0],[24.0,46.1],[24.1,46.1]]}]}""",
            ),
        )

        val home = services.geofences.get("home")!!
        assertEquals(150f, home.radius, 0f)
        assertEquals("""{"k":1}""", home.extras)
        assertTrue(services.geofences.get("work")!!.notifyOnDwell)
        assertTrue(services.geofences.get("zone")!!.isPolygon)

        val list = handlers.getGeofences()
        assertJsonEquals(JSONObject().put("geofences", GeofenceJson.toJsonArray(services.geofences.list())), list)
        assertEquals(3, list.getJSONArray("geofences").length())
        assertJsonEquals(
            JSONObject().put("geofence", GeofenceJson.toJson(home)),
            handlers.getGeofence(json("""{"identifier":"home"}""")),
        )
        assertTrue(handlers.getGeofence(json("""{"identifier":"none"}""")).isNull("geofence"))
        assertJsonEquals("""{"exists":true}""", handlers.geofenceExists(json("""{"identifier":"work"}""")))
        assertJsonEquals("""{"exists":false}""", handlers.geofenceExists(json("""{"identifier":"none"}""")))

        handlers.removeGeofence(json("""{"identifier":"home"}"""))
        handlers.removeGeofences(json("""{"identifiers":["zone"]}"""))
        assertEquals(listOf("work"), services.geofences.list().map { it.identifier })

        handlers.removeGeofences(JSONObject())
        assertTrue(services.geofences.list().isEmpty())
    }

    @Test
    fun `removeGeofences with an empty list removes nothing, with null removes all`() = test {
        makeReady()
        services.geofences.add(listOf(Fixtures.circle("a"), Fixtures.circle("b")))

        handlers.removeGeofences(json("""{"identifiers":[]}"""))
        assertEquals(listOf("a", "b"), services.geofences.list().map { it.identifier })

        handlers.removeGeofences(json("""{"identifiers":null}"""))
        assertTrue(services.geofences.list().isEmpty())

        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.removeGeofences(json("""{"identifiers":[1]}""")) }
    }

    @Test
    fun `invalid geofences are rejected and manager errors keep their code`() = test {
        makeReady()

        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.addGeofence(JSONObject()) }
        assertRejects(ErrorCode.INVALID_ARGUMENT) {
            handlers.addGeofence(json("""{"geofence":{"identifier":"a","latitude":1,"longitude":2,"radius":0}}"""))
        }
        assertRejects(ErrorCode.INVALID_ARGUMENT) {
            handlers.addGeofences(json("""{"geofences":[{"identifier":"a","vertices":[[1,2],[3,4]]}]}"""))
        }
        assertTrue(services.geofences.list().isEmpty())

        services.geofences.addError = TrackingException(ErrorCode.TOO_MANY_GEOFENCES, "max 100")
        assertRejects(ErrorCode.TOO_MANY_GEOFENCES) {
            handlers.addGeofence(json("""{"geofence":{"identifier":"a","latitude":1,"longitude":2,"radius":5}}"""))
        }
    }

    // ---- heartbeat and device

    @Test
    fun `heartbeat status shape`() = test {
        makeReady()
        services.heartbeat.statusValue = services.heartbeat.statusValue.copy(
            lastRecordAt = Fixtures.RECORDED_AT,
            nextHeartbeatAt = Fixtures.RECORDED_AT + 180_000,
            strategy = HeartbeatStrategy.IDLE_PACED,
            pendingHeartbeats = 2,
        )

        val status = handlers.getHeartbeatStatus()

        assertJsonEquals(HeartbeatStatusJson.toJson(services.heartbeat.statusValue), status)
        assertEquals("idle_paced", status.getString("strategy"))
        assertEquals("2026-09-26T10:15:30.456Z", status.getString("lastRecordAt"))
    }

    @Test
    fun `device state shapes`() = test {
        makeReady()
        services.device.providerStateValue = Fixtures.providerState(gps = false, permission = PermissionLevel.WHEN_IN_USE)
        services.device.powerSaveMode = true
        services.device.ignoringBatteryOptimizations = true
        services.device.deviceIdleMode = true
        services.fakeSettings.powerManagerInfoValue = PowerManagerInfo("Xiaomi", true)

        assertJsonEquals(ProviderStateJson.toJson(services.device.providerStateValue), handlers.getProviderState())
        assertJsonEquals("""{"isPowerSaveMode":true}""", handlers.isPowerSaveMode())
        assertJsonEquals(
            BatteryOptimizationStatusJson.toJson(BatteryOptimizationStatus(true, false, true)),
            handlers.getBatteryOptimizationStatus(),
        )
        assertJsonEquals(PowerManagerInfoJson.toJson(PowerManagerInfo("Xiaomi", true)), handlers.getPowerManagerInfo())
        assertJsonEquals(DeviceInfoJson.toJson(services.deviceInfo.info), handlers.getDeviceInfo())
        assertJsonEquals(SensorsJson.toJson(services.deviceInfo.sensorsValue), handlers.getSensors())
    }

    @Test
    fun `settings screens get the live activity and report opened`() = test {
        val seen = mutableListOf<Activity?>()
        val settings = object : DeviceSettings {
            override fun openBatteryOptimizationSettings(activity: Activity?) = seen.add(activity)

            override fun powerManagerInfo() = PowerManagerInfo("x", false)

            override fun openPowerManagerSettings(activity: Activity?) = seen.add(activity).let { false }

            override fun openLocationSettings(activity: Activity?) = seen.add(activity)

            override fun openAppSettings(activity: Activity?) = seen.add(activity)
        }
        val live = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity = live
        val h = PluginHandlers(TestServices(deviceSettings = settings, dispatchers = testDispatchers(scheduler)), { activity }, readyFlag)

        assertJsonEquals("""{"opened":true}""", h.openBatteryOptimizationSettings())
        assertJsonEquals("""{"opened":false}""", h.openPowerManagerSettings())
        assertJsonEquals("""{"opened":true}""", h.openLocationSettings())
        live.finish()
        assertJsonEquals("""{"opened":true}""", h.openAppSettings())

        assertEquals(listOf(live, live, live, null), seen)
    }

    @Test
    fun `settings use the fake and report its result`() = test {
        services.fakeSettings.openResult = false

        assertJsonEquals("""{"opened":false}""", handlers.openLocationSettings())
        assertJsonEquals("""{"opened":false}""", handlers.openAppSettings())

        assertEquals(listOf("location", "app"), services.fakeSettings.opened.toList())
    }

    // ---- permissions

    @Test
    fun `checkPermissions returns every alias`() = test {
        services.fakePermissions.set(PermissionType.BACKGROUND_LOCATION, PermissionState.PROMPT)
        services.fakePermissions.set(PermissionType.NOTIFICATIONS, PermissionState.DENIED)

        assertJsonEquals(
            """{"location":"granted","backgroundLocation":"prompt","activityRecognition":"granted","notifications":"denied"}""",
            handlers.checkPermissions(),
        )
    }

    @Test
    fun `requestPermissions defaults to all four in contract order`() = test {
        services.fakePermissions.denyAll()
        services.fakePermissions.grantOnRequest = PermissionState.GRANTED

        val result = handlers.requestPermissions(JSONObject(), host)

        assertEquals(
            listOf(
                PermissionType.LOCATION,
                PermissionType.NOTIFICATIONS,
                PermissionType.ACTIVITY_RECOGNITION,
                PermissionType.BACKGROUND_LOCATION,
            ),
            services.fakePermissions.requests.single(),
        )
        assertJsonEquals(
            """{"location":"granted","backgroundLocation":"granted","activityRecognition":"granted","notifications":"granted"}""",
            result,
        )
    }

    @Test
    fun `requestPermissions with a subset keeps canonical order and reports denials`() = test {
        services.fakePermissions.denyAll()
        services.fakePermissions.grantOnRequest = PermissionState.PROMPT_WITH_RATIONALE

        val result = handlers.requestPermissions(json("""{"permissions":["backgroundLocation","location","location"]}"""), host)

        assertEquals(
            listOf(PermissionType.LOCATION, PermissionType.BACKGROUND_LOCATION),
            services.fakePermissions.requests.single(),
        )
        assertEquals("prompt-with-rationale", result.getString("location"))
        assertEquals("denied", result.getString("notifications"))
    }

    @Test
    fun `requestPermissions with an empty list only reports and rejects unknown types`() = test {
        val result = handlers.requestPermissions(json("""{"permissions":[]}"""), host)

        assertTrue(services.fakePermissions.requests.isEmpty())
        assertEquals("granted", result.getString("location"))
        assertRejects(ErrorCode.INVALID_ARGUMENT) {
            handlers.requestPermissions(json("""{"permissions":["camera"]}"""), host)
        }
    }

    /** A PermissionManager whose requests complete only when the test calls the captured `onDone`. */
    private class ManualPermissions(private val fake: FakePermissionManager = FakePermissionManager()) :
        PermissionManager by fake {
        val requested = mutableListOf<List<PermissionType>>()
        val pending = ArrayDeque<(Map<PermissionType, PermissionState>) -> Unit>()

        override fun request(
            host: PermissionHost,
            types: List<PermissionType>,
            rationale: BackgroundPermissionRationale,
            onDone: (Map<PermissionType, PermissionState>) -> Unit,
        ) {
            requested += types
            pending += onDone
        }
    }

    @Test
    fun `overlapping permission requests run one after the other`() = runTest(scheduler) {
        val manager = ManualPermissions()
        val h = PluginHandlers(TestServices(permissions = manager, dispatchers = testDispatchers(scheduler)), readyFlag = readyFlag)

        val first = async { h.requestPermissions(json("""{"permissions":["location"]}"""), host) }
        val second = async { h.requestPermissions(json("""{"permissions":["notifications"]}"""), host) }
        runCurrent()

        assertEquals("the second request waits", listOf(listOf(PermissionType.LOCATION)), manager.requested)
        manager.pending.removeFirst()(mapOf(PermissionType.LOCATION to PermissionState.DENIED))
        runCurrent()
        assertEquals("denied", first.await().getString("location"))
        assertEquals(listOf(PermissionType.NOTIFICATIONS), manager.requested.last())

        manager.pending.removeFirst()(mapOf(PermissionType.NOTIFICATIONS to PermissionState.PROMPT))
        assertEquals("prompt", second.await().getString("notifications"))
    }

    @Test
    fun `a cancelled permission request frees the next one and ignores a late result`() = runTest(scheduler) {
        val manager = ManualPermissions()
        val h = PluginHandlers(TestServices(permissions = manager, dispatchers = testDispatchers(scheduler)), readyFlag = readyFlag)

        val abandoned = launch { h.requestPermissions(JSONObject(), host) }
        runCurrent()
        abandoned.cancel()
        runCurrent()
        manager.pending.removeFirst()(emptyMap()) // late onDone after cancellation: ignored

        val next = async { h.requestPermissions(json("""{"permissions":["location"]}"""), host) }
        runCurrent()
        manager.pending.removeFirst()(emptyMap())

        assertEquals("granted", next.await().getString("location"))
        assertEquals(2, manager.requested.size)
    }

    // ---- log

    @Test
    fun `log writes through the Logger`() = test {
        val sink = FakeLogStore()
        Logger.sink = sink

        assertNull(handlers.log(json("""{"level":"warn","message":"from js"}""")))

        assertEquals(FakeLogStore.Line(LogLevel.WARN, PluginHandlers.JS_TAG, "from js", null), sink.lines.single())
        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.log(json("""{"level":"off","message":"x"}""")) }
        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.log(json("""{"level":"info"}""")) }
    }

    @Test
    fun `getLog parses the query`() = test {
        services.logStore.readResult = "line 1\nline 2"

        val result = handlers.getLog(json("""{"start":1000,"end":2000,"level":"error","limit":10,"order":"desc"}"""))

        assertJsonEquals(JSONObject().put("log", "line 1\nline 2"), result)
        assertEquals(LogQuery(1000L, 2000L, LogLevel.ERROR, 10, false), services.logStore.queries.single())
        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.getLog(json("""{"order":"random"}""")) }
    }

    @Test
    fun `destroyLog and uploadLog`() = test {
        makeReady()

        assertNull(handlers.destroyLog())
        assertEquals(1, services.logStore.destroyCalls)

        services.logStore.uploadResult = HttpResult(false, 503, "busy", emptyList())
        val result = handlers.uploadLog(
            json("""{"url":"https://example.com/logs","headers":{"X-Key":"k","X-N":2},"params":{"device":"d"}}"""),
        )

        assertJsonEquals("""{"success":false,"status":503}""", result)
        val upload = services.logStore.uploads.single()
        assertEquals("https://example.com/logs", upload.url)
        assertEquals(mapOf("X-Key" to "k", "X-N" to "2"), upload.headers)
        assertJsonEquals("""{"device":"d"}""", JSONObject(upload.paramsJson!!))
        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.uploadLog(json("""{"url":"ftp://x"}""")) }
        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.uploadLog(JSONObject()) }
    }

    @Test
    fun `emailLog needs an activity`() = test {
        makeReady()

        assertRejects(ErrorCode.NO_ACTIVITY) { handlers.emailLog(json("""{"email":"ops@example.com"}""")) }

        assertTrue("no log is prepared without an activity", services.logStore.emails.isEmpty())
    }

    @Test
    fun `emailLog opens a chooser for the prepared intent`() = test {
        makeReady()
        val live = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity = live

        assertNull(handlers.emailLog(json("""{"email":"ops@example.com","subject":"Trip log"}""")))

        assertEquals(listOf("ops@example.com" to "Trip log"), services.logStore.emails.toList())
        val started = shadowOf(live).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        @Suppress("DEPRECATION")
        val target = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_SEND, target!!.action)
        assertRejects(ErrorCode.INVALID_ARGUMENT) { handlers.emailLog(json("""{"email":" "}""")) }
    }

    // ---- events and lifecycle

    @Test
    fun `bus events are forwarded with their JS name and payload`() {
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val subscription = handlers.forwardEvents { name, payload -> sent += name to payload() }
        val record = Fixtures.record()
        val events = listOf(
            TrackingEvent.Location(record),
            TrackingEvent.MotionChange(true, record),
            TrackingEvent.ActivityChange(ActivitySample(ActivityType.WALKING, 80)),
            TrackingEvent.Heartbeat(record.copy(event = RecordEvent.HEARTBEAT)),
            TrackingEvent.Geofence("home", GeofenceAction.ENTER, record, null),
            TrackingEvent.EnabledChange(false),
        )

        events.forEach { services.events.emit(it) }

        assertEquals(events.map { EventJson.name(it) }, sent.map { it.first })
        events.zip(sent).forEach { (event, pair) -> assertJsonEquals(EventJson.payload(event), pair.second) }
        assertFalse(sent.first().second.has("sent_at"))

        subscription.cancel()
        services.events.emit(TrackingEvent.EnabledChange(true))
        assertEquals(events.size, sent.size)
        assertEquals(0, services.events.subscriberCount)
    }

    @Test
    fun `a failing listener does not break the emitter`() {
        handlers.forwardEvents { _, _ -> throw IllegalStateException("webview gone") }

        services.events.emit(TrackingEvent.EnabledChange(true))

        assertEquals(1, services.events.events.size)
    }

    @Test
    fun `the payload is only built on demand`() {
        val names = mutableListOf<String>()
        val builders = mutableListOf<() -> JSONObject>()
        handlers.forwardEvents { name, payload ->
            names += name
            builders += payload
        }

        services.events.emit(TrackingEvent.Heartbeat(Fixtures.record(event = RecordEvent.HEARTBEAT)))

        assertEquals(listOf(EventJson.HEARTBEAT), names)
        assertEquals("heartbeat", builders.single()().getJSONObject("location").getString("event"))
    }

    @Test
    fun `onResume re-checks the provider state`() = test {
        handlers.onResume()

        assertEquals(listOf(PluginHandlers.RESUME_REASON), services.device.checkReasons.toList())
    }

    @Test
    fun `stub-compatible constructor exists`() {
        val constructor = PluginHandlers::class.java.constructors.firstOrNull {
            it.parameterTypes.contentEquals(arrayOf(com.brickssoft.locationtracking.core.Components::class.java))
        }
        assertNotNull(constructor)
    }

    @Test
    fun `injected registry is used`() {
        val registry = WatchRegistry()
        val h = PluginHandlers(services, watches = registry)
        assertSame(registry, h.watches)
    }
}
