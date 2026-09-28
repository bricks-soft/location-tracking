package com.brickssoft.locationtracking.api

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.bridge.BridgeServices
import com.brickssoft.locationtracking.bridge.PluginHandlers
import com.brickssoft.locationtracking.bridge.TestServices
import com.brickssoft.locationtracking.config.ConfigJson
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.heartbeat.HeartbeatScheduler
import com.brickssoft.locationtracking.model.GeofenceJson
import com.brickssoft.locationtracking.model.HeartbeatStatus
import com.brickssoft.locationtracking.model.HeartbeatStatusJson
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.RecordJson
import com.brickssoft.locationtracking.testing.FakeHeartbeatScheduler
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class LocationTrackingNativeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val logs = FakeLogStore()
    private val services = TestServices()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val hooked = CopyOnWriteArrayList<Record>()

    @Before
    fun setUp() {
        Logger.sink = logs
        PluginHandlers.processReady.set(false)
        services.recordHooks.add { hooked += it }
        useServices(services)
    }

    @After
    fun tearDown() {
        LocationTrackingNative.backendOverride = null
        NativeListeners.resetForTests()
        scope.cancel()
        Components.reset()
        PluginHandlers.processReady.set(false)
        Logger.sink = null
    }

    private fun useServices(s: BridgeServices, on: CoroutineScope = scope) {
        LocationTrackingNative.backendOverride = { NativeBackend(s, on) }
    }

    private fun <T> run(call: (NativeCallback<T>) -> Unit): Result<T> {
        val probe = CallbackProbe<T>()
        call(probe)
        val result = probe.await()
        assertEquals(LocationTrackingNative.THREAD_NAME, probe.thread)
        return result
    }

    private fun <T> Result<T>.code(): ErrorCode {
        val error = exceptionOrNull()
        assertTrue("expected a TrackingException, got $error", error is TrackingException)
        return (error as TrackingException).code
    }

    private fun state(): JSONObject = ConfigJson.stateToJson(services.engine.state())

    // ---------------------------------------------------------------- lifecycle and config

    @Test
    fun `ready returns the State JSON and does not set the JS bridge's ready flag`() {
        val config = JSONObject("""{"heartbeat":{"minInterval":240}}""")

        val result = run<JSONObject> { LocationTrackingNative.ready(app, config, true, it) }

        assertJsonEquals(state(), result.getOrThrow())
        assertEquals(240, services.configStore.config.value.heartbeat.minInterval)
        assertEquals(listOf("ready"), services.engine.calls)
        assertEquals(listOf("ready(reset=true)"), services.configStore.calls.filter { it.startsWith("ready") })
        assertFalse(PluginHandlers.processReady.get())
    }

    @Test
    fun `ready without a config and without reset`() {
        val result = run<JSONObject> { LocationTrackingNative.ready(app, null, false, it) }

        assertTrue(result.isSuccess)
        assertEquals(listOf("ready(reset=false)"), services.configStore.calls.filter { it.startsWith("ready") })
    }

    @Test
    fun `calls are not subject to the NOT_READY rule`() {
        assertFalse(PluginHandlers.processReady.get())

        assertTrue(run<JSONObject> { LocationTrackingNative.start(app, it) }.isSuccess)
        assertTrue(run<JSONArray> { LocationTrackingNative.getGeofences(app, it) }.isSuccess)
    }

    @Test
    fun `state methods run the engine and return the State JSON`() {
        val setConfig = run<JSONObject> {
            LocationTrackingNative.setConfig(app, JSONObject("""{"heartbeat":{"minInterval":200}}"""), it)
        }
        assertJsonEquals(state(), setConfig.getOrThrow())
        assertEquals(200, services.configStore.config.value.heartbeat.minInterval)

        val started = run<JSONObject> { LocationTrackingNative.start(app, it) }.getOrThrow()
        assertTrue(started.getBoolean("enabled"))
        assertEquals("location", started.getString("trackingMode"))

        val geofences = run<JSONObject> { LocationTrackingNative.startGeofences(app, it) }.getOrThrow()
        assertEquals("geofences", geofences.getString("trackingMode"))

        assertJsonEquals(state(), run<JSONObject> { LocationTrackingNative.getState(app, it) }.getOrThrow())

        val stopped = run<JSONObject> { LocationTrackingNative.stop(app, it) }.getOrThrow()
        assertFalse(stopped.getBoolean("enabled"))
        assertEquals(listOf("setConfig", "start", "startGeofences", "stop"), services.engine.calls)
    }

    @Test
    fun `changePace forwards isMoving`() {
        val result = run<Unit> { LocationTrackingNative.changePace(app, true, it) }

        assertEquals(Unit, result.getOrThrow())
        assertEquals(listOf(true), services.engine.paceChanges.toList())
    }

    @Test
    fun `engine failures keep their JS error code`() {
        services.engine.failWith = TrackingException(ErrorCode.PERMISSION_DENIED, "no location permission")

        val result = run<JSONObject> { LocationTrackingNative.start(app, it) }

        assertEquals(ErrorCode.PERMISSION_DENIED, result.code())
        assertEquals("no location permission", result.exceptionOrNull()!!.message)
    }

    @Test
    fun `other exceptions become INTERNAL with the original as the cause`() {
        val boom = IllegalStateException("boom")
        val failing = object : HeartbeatScheduler by FakeHeartbeatScheduler() {
            override suspend fun status(): HeartbeatStatus = throw boom
        }
        useServices(object : BridgeServices by services {
            override val heartbeat: HeartbeatScheduler = failing
        })

        val result = run<JSONObject> { LocationTrackingNative.getHeartbeatStatus(app, it) }

        assertEquals(ErrorCode.INTERNAL, result.code())
        assertSame(boom, result.exceptionOrNull()!!.cause)
        assertTrue(logs.lines.any { it.level == LogLevel.ERROR && it.error === boom })
    }

    // ---------------------------------------------------------------- heartbeat, sync, records

    @Test
    fun `getHeartbeatStatus returns the HeartbeatStatus JSON`() {
        val result = run<JSONObject> { LocationTrackingNative.getHeartbeatStatus(app, it) }

        assertJsonEquals(HeartbeatStatusJson.toJson(services.heartbeat.statusValue), result.getOrThrow())
    }

    @Test
    fun `sync returns the uploaded records and maps its errors`() {
        val uploaded = listOf(Fixtures.record(uuid = "a"), Fixtures.record(uuid = "b", event = RecordEvent.HEARTBEAT))
        services.syncer.syncResult = uploaded

        assertJsonEquals(RecordJson.toJsonArray(uploaded), run<JSONArray> { LocationTrackingNative.sync(app, it) }.getOrThrow())

        services.syncer.syncError = TrackingException(ErrorCode.NO_URL, "http.url is not set")
        assertEquals(ErrorCode.NO_URL, run<JSONArray> { LocationTrackingNative.sync(app, it) }.code())
    }

    @Test
    fun `insertLocation queues the record, dispatches it to the record hooks and returns its uuid`() {
        val input = JSONObject("""{"coords":{"latitude":24.7,"longitude":46.6,"accuracy":8},"extras":{"k":"v"}}""")

        val uuid = run<String> { LocationTrackingNative.insertLocation(app, input, it) }.getOrThrow()

        val record = services.recordFactory.created.single()
        assertEquals(record.uuid, uuid)
        assertEquals(listOf(record), services.locationStore.all)
        assertEquals(listOf(record), hooked.toList())
        assertEquals(listOf(record), services.syncer.inserted.toList())
        assertTrue(services.events.events.isEmpty())
    }

    @Test
    fun `insertLocation validates its input like the JS method`() {
        val bad = listOf(
            """{}""",
            """{"coords":{"latitude":91,"longitude":0}}""",
            """{"coords":{"latitude":0,"longitude":0},"timestamp":"yesterday"}""",
        )
        for (text in bad) {
            assertEquals(text, ErrorCode.INVALID_ARGUMENT, run<String> { LocationTrackingNative.insertLocation(app, JSONObject(text), it) }.code())
        }
        assertTrue(services.locationStore.all.isEmpty())
        assertTrue(hooked.isEmpty())
    }

    // ---------------------------------------------------------------- geofences

    @Test
    fun `geofences are added, listed and removed in the JS shapes`() {
        val hq = JSONObject("""{"identifier":"premise:hq","latitude":24.7136,"longitude":46.6753,"radius":150,"extras":{"premise":"hq"}}""")

        assertEquals(Unit, run<Unit> { LocationTrackingNative.addGeofence(app, hq, it) }.getOrThrow())
        val listed = run<JSONArray> { LocationTrackingNative.getGeofences(app, it) }.getOrThrow()
        assertEquals(1, listed.length())
        assertEquals("premise:hq", listed.getJSONObject(0).getString("identifier"))
        assertJsonEquals(GeofenceJson.toJsonArray(kotlinx.coroutines.runBlocking { services.geofences.list() }), listed)

        assertEquals(Unit, run<Unit> { LocationTrackingNative.removeGeofence(app, "premise:hq", it) }.getOrThrow())
        assertEquals(0, run<JSONArray> { LocationTrackingNative.getGeofences(app, it) }.getOrThrow().length())
    }

    @Test
    fun `geofence errors keep their codes`() {
        val bad = JSONObject("""{"identifier":"x","latitude":1,"longitude":2,"radius":0}""")
        assertEquals(ErrorCode.INVALID_ARGUMENT, run<Unit> { LocationTrackingNative.addGeofence(app, bad, it) }.code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, run<Unit> { LocationTrackingNative.removeGeofence(app, "", it) }.code())

        services.geofences.addError = TrackingException(ErrorCode.TOO_MANY_GEOFENCES, "limit")
        val ok = JSONObject("""{"identifier":"y","latitude":1,"longitude":2,"radius":50}""")
        assertEquals(ErrorCode.TOO_MANY_GEOFENCES, run<Unit> { LocationTrackingNative.addGeofence(app, ok, it) }.code())
    }

    // ---------------------------------------------------------------- plumbing

    @Test
    fun `JSON arguments are copied when the method is called`() {
        val scheduler = TestCoroutineScheduler()
        val deferred = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        useServices(services, deferred)
        val config = JSONObject("""{"heartbeat":{"minInterval":200}}""")
        val probe = CallbackProbe<JSONObject>()

        LocationTrackingNative.setConfig(app, config, probe)
        config.getJSONObject("heartbeat").put("minInterval", 400)
        scheduler.runCurrent()

        assertTrue(probe.await().isSuccess)
        assertEquals(200, services.configStore.config.value.heartbeat.minInterval)
        deferred.cancel()
    }

    @Test
    fun `a cancelled scope still invokes the callback, with INTERNAL`() {
        val dead = CoroutineScope(SupervisorJob()).also { it.cancel() }
        useServices(services, dead)

        val result = run<JSONObject> { LocationTrackingNative.getState(app, it) }

        assertEquals(ErrorCode.INTERNAL, result.code())
        assertTrue(result.exceptionOrNull()!!.message!!.contains("components were shut down"))
        assertTrue(NativeThread.awaitIdle())
        assertEquals("logged once", 1, logs.lines.count { it.level == LogLevel.ERROR })
    }

    @Test
    fun `a scope cancelled while the call runs delivers one shut-down failure, logged once`() {
        val scheduler = TestCoroutineScheduler()
        val running = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val waiting = object : HeartbeatScheduler by FakeHeartbeatScheduler() {
            override suspend fun status(): HeartbeatStatus = kotlinx.coroutines.awaitCancellation()
        }
        useServices(object : BridgeServices by services {
            override val heartbeat: HeartbeatScheduler = waiting
        }, running)
        val probe = CallbackProbe<JSONObject>()

        LocationTrackingNative.getHeartbeatStatus(app, probe)
        scheduler.runCurrent()
        running.cancel()
        scheduler.runCurrent()

        assertEquals(ErrorCode.INTERNAL, probe.await().code())
        assertTrue(probe.results.single().exceptionOrNull()!!.message!!.contains("components were shut down"))
        assertTrue(NativeThread.awaitIdle())
        assertEquals(1, probe.results.size)
        assertEquals("logged once", 1, logs.lines.count { it.level == LogLevel.ERROR })
    }

    @Test
    fun `a callback that throws is logged and later callbacks still run`() {
        LocationTrackingNative.getState(app) { throw IllegalStateException("callback boom") }

        val result = run<JSONObject> { LocationTrackingNative.getState(app, it) }

        assertTrue(result.isSuccess)
        assertTrue(logs.lines.any { it.level == LogLevel.ERROR && it.error?.message == "callback boom" })
    }

    @Test
    fun `callbacks arrive in completion order, each exactly once`() {
        val scheduler = TestCoroutineScheduler()
        val deferred = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val slowHeartbeat = object : HeartbeatScheduler by FakeHeartbeatScheduler() {
            override suspend fun status(): HeartbeatStatus {
                kotlinx.coroutines.delay(1_000) // suspends, like a call that waits for I/O
                return services.heartbeat.statusValue
            }
        }
        useServices(object : BridgeServices by services {
            override val heartbeat: HeartbeatScheduler = slowHeartbeat
        }, deferred)
        val order = CopyOnWriteArrayList<String>()
        val first = CallbackProbe<JSONObject>()
        val second = CallbackProbe<JSONObject>()

        LocationTrackingNative.getHeartbeatStatus(app) { order += "heartbeatStatus"; first.onResult(it) }
        LocationTrackingNative.getState(app) { order += "state"; second.onResult(it) }
        scheduler.advanceUntilIdle()
        first.await()
        second.await()
        assertTrue(NativeThread.awaitIdle())

        assertEquals(listOf("state", "heartbeatStatus"), order.toList())
        assertEquals(1, first.results.size)
        assertEquals(1, second.results.size)
        deferred.cancel()
    }

    @Test
    fun `a cancellation inside a running call is INTERNAL with its own message, logged once`() {
        val cancelling = object : HeartbeatScheduler by FakeHeartbeatScheduler() {
            override suspend fun status(): HeartbeatStatus = throw kotlinx.coroutines.CancellationException("task cancelled")
        }
        useServices(object : BridgeServices by services {
            override val heartbeat: HeartbeatScheduler = cancelling
        })

        val result = run<JSONObject> { LocationTrackingNative.getHeartbeatStatus(app, it) }

        assertEquals(ErrorCode.INTERNAL, result.code())
        assertEquals("task cancelled", result.exceptionOrNull()!!.message)
        assertTrue(NativeThread.awaitIdle())
        assertEquals(1, logs.lines.count { it.level == LogLevel.ERROR })
    }

    @Test
    fun `a JSON argument that cannot be serialized fails the call instead of throwing to the caller`() {
        val loop = JSONObject()
        loop.put("self", loop)

        val result = run<Unit> { LocationTrackingNative.addGeofence(app, loop, it) }

        assertEquals(ErrorCode.INVALID_ARGUMENT, result.code())
        assertEquals(0, kotlinx.coroutines.runBlocking { services.geofences.list() }.size)
    }

    @Test
    fun `a call waits for a bootstrap that another thread is still running`() {
        LocationTrackingNative.backendOverride = null
        Components.get(app)
        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        // Stands in for Components.bootstrap() running on another thread: it holds the Components lock.
        val bootstrap = Thread {
            synchronized(Components) {
                holding.countDown()
                release.await(15, java.util.concurrent.TimeUnit.SECONDS)
            }
        }.apply { start() }
        holding.await(15, java.util.concurrent.TimeUnit.SECONDS)
        val probe = CallbackProbe<JSONObject>()
        val caller = Thread { LocationTrackingNative.getState(app, probe) }.apply { start() }

        Thread.sleep(200)
        assertTrue("the call must wait for the lock", probe.results.isEmpty())
        assertEquals(Thread.State.BLOCKED, caller.state)
        release.countDown()

        assertTrue(probe.await().isSuccess)
        bootstrap.join(5_000)
        caller.join(5_000)
    }

    @Test
    fun `without an override the calls run on the process Components`() {
        LocationTrackingNative.backendOverride = null

        val result = run<JSONObject> { LocationTrackingNative.getState(app, it) }

        val state = result.getOrThrow()
        assertFalse(state.getBoolean("enabled"))
        assertNotNull(state.getJSONObject("config"))
        assertFalse(PluginHandlers.processReady.get())
    }
}
