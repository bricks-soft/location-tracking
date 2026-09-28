package com.brickssoft.locationtracking.api

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.RecordHooks
import com.brickssoft.locationtracking.core.SimpleEventBus
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.ConnectivityType
import com.brickssoft.locationtracking.model.EventJson
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.GeofenceHit
import com.brickssoft.locationtracking.model.HttpResult
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.RecordJson
import com.brickssoft.locationtracking.record.DefaultRecordSink
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeHeartbeatScheduler
import com.brickssoft.locationtracking.testing.FakeHttpSyncer
import com.brickssoft.locationtracking.testing.FakeLocationStore
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.Fixtures
import com.brickssoft.locationtracking.testing.JsonAssert.assertJsonEquals
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class NativeListenersTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val logs = FakeLogStore()
    private val base = LocationTrackingNative.LISTENER_META_DATA

    @Before
    fun setUp() {
        NativeListeners.resetForTests()
        ListenerLog.clear()
        RecordingListener.created.set(0)
        SecondListener.created.set(0)
        Logger.sink = logs
    }

    @After
    fun tearDown() {
        NativeListeners.resetForTests()
        Components.reset()
        Logger.sink = null
    }

    private fun drain() = assertTrue("LT-native did not drain", NativeThread.awaitIdle())

    private fun errors() = logs.lines.filter { it.level == LogLevel.ERROR }

    // ---------------------------------------------------------------- manifest

    @Test
    @Config(sdk = [29, 30, 33, 34, 35])
    fun `Components creates the manifest listener once and connects it to records and events`() {
        setMetaData(app, mapOf(base to RecordingListener::class.java.name))

        val components = Components.get(app)
        Components.get(app)

        assertEquals(1, RecordingListener.created.get())
        val record = Fixtures.record(event = RecordEvent.HEARTBEAT)
        components.recordHooks.dispatch(record)
        components.events.emit(TrackingEvent.Heartbeat(record))
        drain()
        val calls = ListenerLog.of(RecordingListener.NAME)
        assertEquals(listOf("record:heartbeat", "event:heartbeat"), ListenerLog.keys(RecordingListener.NAME))
        assertJsonEquals(RecordJson.toJson(record), calls[0].json)
        assertFalse("no sent_at", calls[0].json.has("sent_at"))
        assertJsonEquals(EventJson.payload(TrackingEvent.Heartbeat(record)), calls[1].json)
        assertTrue(calls.all { it.thread == LocationTrackingNative.THREAD_NAME })
        assertTrue(calls.all { it.context === app })
    }

    @Test
    fun `suffixed meta-data names install further listeners, base name first, unrelated keys ignored`() {
        setMetaData(
            app,
            mapOf(
                "$base.zeta" to SecondListener::class.java.name,
                "$base.alpha" to RecordingListener::class.java.name,
                "${base}X" to NotAListener::class.java.name, // no dot: not ours
                "com.example.OTHER" to "com.example.Nothing",
            ),
        )

        val declared = NativeListeners.declaredListenerClasses(app)

        assertEquals(
            listOf("$base.alpha" to RecordingListener::class.java.name, "$base.zeta" to SecondListener::class.java.name),
            declared,
        )
        val created = NativeListeners.createManifestListeners(app)
        assertEquals(listOf("$base.alpha", "$base.zeta"), created.map { it.first })
        assertTrue(errors().isEmpty())
        val components = Components.get(app)
        components.recordHooks.dispatch(Fixtures.record())
        drain()
        assertEquals(listOf("recording", "second"), ListenerLog.calls.map { it.listener })
        assertEquals(2, SecondListener.created.get()) // once above, once by Components
    }

    @Test
    fun `the base name comes before suffixed names`() {
        setMetaData(
            app,
            mapOf("$base.a" to SecondListener::class.java.name, base to RecordingListener::class.java.name),
        )

        assertEquals(
            listOf(base to RecordingListener::class.java.name, "$base.a" to SecondListener::class.java.name),
            NativeListeners.declaredListenerClasses(app),
        )
    }

    @Test
    fun `a class named twice is created once`() {
        setMetaData(
            app,
            mapOf(
                base to RecordingListener::class.java.name,
                "$base.again" to " ${RecordingListener::class.java.name} ",
            ),
        )

        Components.get(app).recordHooks.dispatch(Fixtures.record())
        drain()

        assertEquals(1, RecordingListener.created.get())
        assertEquals(1, ListenerLog.calls.size)
    }

    @Test
    fun `bad classes are logged with their Throwable and skipped, good ones are created`() {
        setMetaData(
            app,
            mapOf(
                "$base.a" to "com.example.DoesNotExist",
                "$base.b" to NotAListener::class.java.name,
                "$base.c" to NeedsArgumentListener::class.java.name,
                "$base.d" to ConstructorThrowsListener::class.java.name,
                "$base.e" to StaticInitThrowsListener::class.java.name,
                "$base.f" to 42,
                "$base.g" to "  ",
                "$base.z" to RecordingListener::class.java.name,
            ),
        )

        val created = NativeListeners.createManifestListeners(app)

        assertEquals(listOf("$base.z"), created.map { it.first })
        assertTrue(created.single().second is RecordingListener)
        val failures = errors().filter { it.tag == "LT.Native" }
        assertEquals(failures.joinToString("\n") { it.message }, 7, failures.size)
        val causes = failures.mapNotNull { it.error }
        assertTrue(causes.any { it is ClassNotFoundException })
        assertTrue(causes.any { it is NoSuchMethodException })
        assertTrue(causes.any { it is InvocationTargetException && it.cause?.message == "constructor boom" })
        // The first load fails with ExceptionInInitializerError; later loads in the same class loader (another test
        // of this sandbox ran first) fail with NoClassDefFoundError. Both are LinkageErrors.
        assertTrue(causes.any { it is ExceptionInInitializerError || it is NoClassDefFoundError })
        assertTrue(failures.any { it.message.contains("does not implement") })
        assertEquals(2, failures.count { it.message.contains("must be a class name") })
    }

    @Test
    fun `a process with bad listener classes still starts and delivers to the good ones`() {
        setMetaData(
            app,
            mapOf(
                "$base.a" to ConstructorThrowsListener::class.java.name,
                "$base.b" to StaticInitThrowsListener::class.java.name,
                "$base.c" to RecordingListener::class.java.name,
            ),
        )

        val components = Components.get(app)
        components.recordHooks.dispatch(Fixtures.record())
        drain()

        assertEquals(listOf("recording"), ListenerLog.calls.map { it.listener })
    }

    @Test
    fun `no meta-data creates nothing and logs nothing`() {
        setMetaData(app, emptyMap())

        assertTrue(NativeListeners.createManifestListeners(app).isEmpty())
        assertTrue(logs.lines.isEmpty())
        Components.get(app)
        assertEquals(0, NativeListeners.listenerCount)
    }

    @Test
    fun `a new Components instance replaces the previous subscriptions and manifest listeners`() {
        setMetaData(app, mapOf(base to RecordingListener::class.java.name))
        val first = Components.get(app)
        val programmatic = LoggingProbe("programmatic")
        LocationTrackingNative.addListener(app, programmatic)

        Components.reset()
        val second = Components.get(app)
        assertNotSame(first, second)
        first.recordHooks.dispatch(Fixtures.record(uuid = "old"))
        first.events.emit(TrackingEvent.EnabledChange(true))
        second.recordHooks.dispatch(Fixtures.record(uuid = "new"))
        drain()

        assertEquals(2, RecordingListener.created.get())
        assertEquals(2, NativeListeners.listenerCount) // the new manifest listener + the programmatic one
        assertEquals(listOf("new"), ListenerLog.of(RecordingListener.NAME).map { it.json.getString("uuid") })
        assertEquals(listOf("new"), ListenerLog.of("programmatic").map { it.json.getString("uuid") })
    }

    // ---------------------------------------------------------------- programmatic

    @Test
    fun `addListener receives only what is emitted after the call and remove is idempotent`() {
        val hooks = RecordHooks()
        val events = SimpleEventBus()
        NativeListeners.connect(app, hooks, events, emptyList())
        hooks.dispatch(Fixtures.record(uuid = "before"))

        val probe = LoggingProbe("probe")
        val other = LoggingProbe("other")
        val subscription = LocationTrackingNative.addListener(app, probe)
        LocationTrackingNative.addListener(app, other)
        hooks.dispatch(Fixtures.record(uuid = "during"))
        events.emit(TrackingEvent.EnabledChange(true))
        drain()
        subscription.remove()
        subscription.remove()
        hooks.dispatch(Fixtures.record(uuid = "after"))
        events.emit(TrackingEvent.EnabledChange(false))
        drain()

        assertEquals(listOf("record:location", "event:enabledchange"), ListenerLog.keys("probe"))
        assertEquals("during", ListenerLog.of("probe")[0].json.getString("uuid"))
        assertEquals(4, ListenerLog.of("other").size)
        assertEquals(1, NativeListeners.listenerCount)
    }

    @Test
    fun `a listener removed while deliveries are queued receives none of them`() {
        val hooks = RecordHooks()
        NativeListeners.connect(app, hooks, SimpleEventBus(), emptyList())
        val gate = CountDownLatch(1)
        NativeThread.post { gate.await(5, TimeUnit.SECONDS) } // holds the LT-native thread
        val probe = LoggingProbe("probe")
        val subscription = LocationTrackingNative.addListener(app, probe)

        hooks.dispatch(Fixtures.record())
        subscription.remove()
        gate.countDown()
        drain()

        assertTrue(ListenerLog.of("probe").isEmpty())
    }

    // ---------------------------------------------------------------- delivery

    @Test
    fun `a record reaches onRecord before the events that carry it`() = runTest {
        val hooks = RecordHooks()
        val events = SimpleEventBus()
        NativeListeners.connect(app, hooks, events, listOf(base to RecordingListener()))
        val sink = DefaultRecordSink(
            FakeLocationStore(), FakeConfigStore(), FakeHeartbeatScheduler(), FakeHttpSyncer(), events, hooks,
        )

        sink.submit(Fixtures.record(uuid = "m", event = RecordEvent.MOTIONCHANGE, isMoving = true))
        sink.submit(Fixtures.record(uuid = "l", event = RecordEvent.LOCATION))
        sink.submit(Fixtures.record(uuid = "h", event = RecordEvent.HEARTBEAT))
        drain()

        assertEquals(
            listOf(
                "record:motionchange", "event:location", "event:motionchange",
                "record:location", "event:location",
                "record:heartbeat", "event:heartbeat",
            ),
            ListenerLog.keys(RecordingListener.NAME),
        )
        val calls = ListenerLog.of(RecordingListener.NAME)
        assertEquals("m", calls[2].json.getJSONObject("location").getString("uuid"))
        assertEquals(true, calls[2].json.getBoolean("isMoving"))
    }

    @Test
    fun `the record hook fires even when the database insert failed`() = runTest {
        val hooks = RecordHooks()
        NativeListeners.connect(app, hooks, SimpleEventBus(), listOf(base to RecordingListener()))
        val store = FakeLocationStore().apply { failInsertWith = IOException("disk full") }
        val sink = DefaultRecordSink(store, FakeConfigStore(), FakeHeartbeatScheduler(), FakeHttpSyncer(), SimpleEventBus(), hooks)

        sink.submit(Fixtures.record(uuid = "lost", event = RecordEvent.TRACKING_START, reason = "start"))
        drain()

        val call = ListenerLog.of(RecordingListener.NAME).single()
        assertEquals("tracking_start", call.name)
        assertEquals("start", call.json.getString("reason"))
    }

    @Test
    fun `every event type is mirrored with the JS name and payload`() {
        val events = SimpleEventBus()
        NativeListeners.connect(app, RecordHooks(), events, listOf(base to RecordingListener()))
        val record = Fixtures.record()
        val all = listOf(
            TrackingEvent.Location(record),
            TrackingEvent.MotionChange(true, record),
            TrackingEvent.ActivityChange(record.activity),
            TrackingEvent.ProviderChange(Fixtures.providerState(gps = false)),
            TrackingEvent.Heartbeat(record),
            TrackingEvent.Geofence("hq", GeofenceAction.ENTER, record.copy(geofence = GeofenceHit("hq", GeofenceAction.ENTER, null)), """{"a":1}"""),
            TrackingEvent.GeofencesChange(listOf(Fixtures.circle()), listOf("old")),
            TrackingEvent.Http(HttpResult(true, 200, "ok", listOf(record.uuid))),
            TrackingEvent.ConnectivityChange(Connectivity(true, ConnectivityType.WIFI)),
            TrackingEvent.PowerSaveChange(true),
            TrackingEvent.EnabledChange(true),
            TrackingEvent.NotificationAction("stop"),
            TrackingEvent.Authorization(true, 200, null, """{"accessToken":"t"}"""),
        )

        all.forEach { events.emit(it) }
        drain()

        val calls = ListenerLog.of(RecordingListener.NAME)
        assertEquals(all.map { EventJson.name(it) }, calls.map { it.name })
        assertEquals(13, calls.map { it.name }.toSet().size)
        for ((event, call) in all.zip(calls)) assertJsonEquals(EventJson.payload(event), call.json)
    }

    @Test
    fun `exceptions and errors of one listener are logged and do not affect the others`() {
        val hooks = RecordHooks()
        val events = SimpleEventBus()
        NativeListeners.connect(app, hooks, events, listOf("$base.a" to ThrowingListener(), "$base.b" to RecordingListener()))

        hooks.dispatch(Fixtures.record(uuid = "1"))
        events.emit(TrackingEvent.EnabledChange(true))
        hooks.dispatch(Fixtures.record(uuid = "2"))
        drain()

        assertEquals(
            listOf("record:location", "event:enabledchange", "record:location"),
            ListenerLog.keys(RecordingListener.NAME),
        )
        val failures = errors().filter { it.message.contains(ThrowingListener::class.java.name) }
        assertEquals(3, failures.size)
        assertTrue(failures[0].error is IllegalStateException)
        assertTrue(failures[1].error is NoClassDefFoundError)
    }

    @Test
    fun `a record whose JSON cannot be built is logged and later records are still delivered`() {
        val hooks = RecordHooks()
        NativeListeners.connect(app, hooks, SimpleEventBus(), listOf(base to RecordingListener()))

        hooks.dispatch(Fixtures.record(uuid = "broken", location = Fixtures.location(latitude = Double.NaN)))
        hooks.dispatch(Fixtures.record(uuid = "fine"))
        drain()

        assertEquals(listOf("fine"), ListenerLog.calls.map { it.json.getString("uuid") })
        assertTrue(errors().any { it.message.contains("broken") && it.error != null })
    }

    @Test
    fun `a blocked LT-native thread logs a backlog warning and drops nothing`() {
        val hooks = RecordHooks()
        NativeListeners.connect(app, hooks, SimpleEventBus(), listOf(base to RecordingListener()))
        val gate = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        NativeThread.post {
            blocked.countDown()
            gate.await(15, TimeUnit.SECONDS)
        }
        assertTrue(blocked.await(15, TimeUnit.SECONDS)) // the thread runs the gate, so the queue holds only records

        repeat(NativeThread.BACKLOG_WARNING) { hooks.dispatch(Fixtures.record(uuid = "r$it")) }
        val warnings = logs.lines.filter { it.level == LogLevel.WARN && it.message.contains("blocking") }
        gate.countDown()
        drain()

        assertEquals(1, warnings.size)
        assertEquals(NativeThread.BACKLOG_WARNING, ListenerLog.calls.size)
    }

    @Test
    fun `each listener receives its own JSON object`() {
        val hooks = RecordHooks()
        val mutator = object : LocationTrackingListener {
            override fun onRecord(context: android.content.Context, record: JSONObject) {
                record.put("uuid", "changed")
            }
        }
        NativeListeners.connect(app, hooks, SimpleEventBus(), listOf("$base.a" to mutator, "$base.b" to RecordingListener()))

        val record = Fixtures.record(uuid = "original", extras = """{"k":"v"}""")
        hooks.dispatch(record)
        drain()

        val received = ListenerLog.of(RecordingListener.NAME).single().json
        assertEquals("original", received.getString("uuid"))
        assertJsonEquals(RecordJson.toJson(record), received)
    }

    @Test
    fun `emitting never runs a listener on the emitting thread`() {
        val hooks = RecordHooks()
        val events = SimpleEventBus()
        val threads = CopyOnWriteArrayList<String>()
        val blocker = CountDownLatch(1)
        LocationTrackingNative.addListener(
            app,
            object : LocationTrackingListener {
                override fun onRecord(context: android.content.Context, record: JSONObject) {
                    threads += Thread.currentThread().name
                    blocker.await(5, TimeUnit.SECONDS)
                }
            },
        )
        NativeListeners.connect(app, hooks, events, emptyList())

        val start = System.nanoTime()
        hooks.dispatch(Fixtures.record())
        hooks.dispatch(Fixtures.record())
        val dispatchMs = (System.nanoTime() - start) / 1_000_000
        blocker.countDown()
        drain()

        assertTrue("dispatch took $dispatchMs ms", dispatchMs < 1_000)
        assertEquals(listOf(LocationTrackingNative.THREAD_NAME, LocationTrackingNative.THREAD_NAME), threads.toList())
    }

    @Test
    fun `nothing is queued while no listener is registered`() {
        val hooks = RecordHooks()
        NativeListeners.connect(app, hooks, SimpleEventBus(), emptyList())
        val gate = CountDownLatch(1)
        NativeThread.post { gate.await(5, TimeUnit.SECONDS) }

        hooks.dispatch(Fixtures.record())
        val probe = LoggingProbe("late")
        LocationTrackingNative.addListener(app, probe)
        gate.countDown()
        drain()

        assertTrue(ListenerLog.of("late").isEmpty())
    }
}

/** A programmatic listener logging under [label]. */
internal class LoggingProbe(label: String) : LoggingListener(label)
