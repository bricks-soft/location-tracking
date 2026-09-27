package com.brickssoft.locationtracking.integration

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.testing.FakeLogStore
import com.brickssoft.locationtracking.testing.testDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.SharedPrefsConfigStore
import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.SimpleEventBus
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.data.GeofenceStore
import com.brickssoft.locationtracking.data.LocationStore
import com.brickssoft.locationtracking.data.SqliteGeofenceStore
import com.brickssoft.locationtracking.data.SqliteLocationStore
import com.brickssoft.locationtracking.data.TrackingDatabase
import com.brickssoft.locationtracking.device.DefaultDeviceMonitor
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.engine.DefaultTrackingEngine
import com.brickssoft.locationtracking.engine.TrackingEngine
import com.brickssoft.locationtracking.geofence.DefaultGeofenceManager
import com.brickssoft.locationtracking.geofence.GeofenceManager
import com.brickssoft.locationtracking.heartbeat.DefaultHeartbeatScheduler
import com.brickssoft.locationtracking.heartbeat.HeartbeatAlarmReceiver
import com.brickssoft.locationtracking.heartbeat.HeartbeatIntents
import com.brickssoft.locationtracking.heartbeat.HeartbeatScheduler
import com.brickssoft.locationtracking.http.HttpSyncer
import com.brickssoft.locationtracking.http.OkHttpSyncer
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.position.DefaultPositionService
import com.brickssoft.locationtracking.position.PositionService
import com.brickssoft.locationtracking.processing.DefaultLocationProcessor
import com.brickssoft.locationtracking.processing.DefaultOdometer
import com.brickssoft.locationtracking.processing.DefaultRecordFactory
import com.brickssoft.locationtracking.processing.LocationProcessor
import com.brickssoft.locationtracking.processing.Odometer
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.record.DefaultRecordSink
import com.brickssoft.locationtracking.record.RecordSink
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.FakePermissionManager
import com.brickssoft.locationtracking.testing.FakeProviderFactory
import com.brickssoft.locationtracking.testing.FakeServiceController
import com.brickssoft.locationtracking.testing.Fixtures
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One app process built like `core/Components.kt`, from the REAL implementations: SharedPreferences config store,
 * SQLite stores, processor, odometer, record factory, OkHttp syncer, record sink, alarm-based heartbeat scheduler,
 * geofence manager, tracking engine and device monitor (over Robolectric's system services).
 *
 * Only what needs hardware or OS UI is simulated: the location / activity / geofence backends ([providers], "the
 * GPS"), the foreground service ([service]) and the permission prompts ([permissions], all granted). As in
 * Components, every component is created on first use and the cycles are broken with `lazy { recordSink }` etc.;
 * only the scope and the event bus (with its recording subscriber) exist from construction.
 *
 * [kill] simulates the process dying: its coroutines stop, its database and HTTP connections close, and its dynamic
 * receivers, network callbacks and in-process listener alarms die with it. SharedPreferences, the SQLite file and
 * PendingIntent alarms survive, so a new [FullStackProcess] over the same [databaseName] is a restarted process.
 * Robolectric holds receivers, network callbacks and alarms app-wide, so [kill] removes all of the plugin's: kill a
 * process before creating the next one. SharedPreferences writes are in memory, so the durability of `apply()`
 * across a real process death is not simulated.
 *
 * @param databaseName SQLite file name, or null for an in-memory database.
 */
internal class FullStackProcess(
    val app: Application,
    parent: CoroutineScope,
    val dispatchers: AppDispatchers,
    val clock: FakeClock,
    databaseName: String?,
    val providers: FakeProviderFactory = FakeProviderFactory(ProviderKind.GMS),
    val service: FakeServiceController = FakeServiceController(),
    val permissions: FakePermissionManager = FakePermissionManager(),
    private val http: OkHttpClient = OkHttpClient(),
) {
    /** Exceptions that escaped a coroutine of [scope] (Components logs them; tests assert there are none). */
    val uncaught = CopyOnWriteArrayList<Throwable>()

    /** The process scope: `dispatchers.engine` + SupervisorJob, a child of the test's background scope. */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob(parent.coroutineContext[Job]) + dispatchers.engine + CoroutineExceptionHandler { _, t -> uncaught += t },
    )

    val events: EventBus = SimpleEventBus()

    /** Every event emitted on the real [SimpleEventBus], in order (a recording subscriber, like the bridge). */
    val emitted = CopyOnWriteArrayList<TrackingEvent>()

    init {
        events.subscribe { emitted += it }
    }

    val configStore: ConfigStore by lazy { SharedPrefsConfigStore(app, clock) }
    private val databaseDelegate = lazy { TrackingDatabase(app, databaseName) }
    val database: TrackingDatabase by databaseDelegate
    val locationStore: LocationStore by lazy { SqliteLocationStore(database, configStore, clock, dispatchers) }
    val geofenceStore: GeofenceStore by lazy { SqliteGeofenceStore(database, dispatchers) }

    val device: DeviceMonitor by lazy {
        DefaultDeviceMonitor(
            app, configStore, permissions, lazy { providers }, events, clock,
            lazy { recordFactory }, lazy { recordSink }, scope,
        )
    }
    val processor: LocationProcessor by lazy { DefaultLocationProcessor(configStore) }
    val odometer: Odometer by lazy { DefaultOdometer(configStore) }
    val recordFactory: RecordFactory by lazy { DefaultRecordFactory(configStore, device, providers, clock) }
    val syncer: HttpSyncer by lazy {
        OkHttpSyncer(configStore, locationStore, device, events, clock, http, dispatchers, scope)
    }

    /** Uses the real `SystemHeartbeatAlarms`, i.e. Robolectric's AlarmManager (see [HeartbeatAlarmsProbe]). */
    val heartbeat: HeartbeatScheduler by lazy {
        DefaultHeartbeatScheduler(
            app, configStore, device, providers, locationStore, recordFactory, lazy { recordSink }, clock, scope,
        )
    }
    val recordSink: RecordSink by lazy { DefaultRecordSink(locationStore, configStore, heartbeat, syncer, events) }
    val geofences: GeofenceManager by lazy {
        DefaultGeofenceManager(geofenceStore, providers, configStore, recordFactory, recordSink, events, clock, scope)
    }
    val positions: PositionService by lazy {
        DefaultPositionService(providers, configStore, permissions, device, recordFactory, recordSink, clock, scope)
    }
    val engine: TrackingEngine by lazy {
        DefaultTrackingEngine(
            configStore, providers, processor, odometer, recordFactory, recordSink,
            heartbeat, geofences, service, device, syncer, permissions, events, clock, scope,
        )
    }

    inline fun <reified T : TrackingEvent> eventsOf(): List<T> = emitted.filterIsInstance<T>()

    /** What `HeartbeatAlarmReceiver` does when a heartbeat PendingIntent alarm is delivered to this process. */
    suspend fun deliverHeartbeatIntent(alarm: ShadowAlarmManager.ScheduledAlarm) {
        HeartbeatAlarmReceiver.deliver(
            HeartbeatAlarmsProbe.triggerOf(alarm), heartbeat, configStore, lazy { service }, lazy { engine },
        )
    }

    /** Closes the database and the pooled HTTP connections (a finished test, or a dead process). */
    fun close() {
        if (databaseDelegate.isInitialized()) database.close()
        http.connectionPool.evictAll()
    }

    /** The process dies (see the class docs). */
    fun kill() {
        scope.cancel()
        close()
        val shadowApp = shadowOf(app)
        shadowApp.registeredReceivers
            .filter { it.broadcastReceiver.javaClass.name.startsWith(PACKAGE) }
            .forEach { app.unregisterReceiver(it.broadcastReceiver) }
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).networkCallbacks.toList().forEach { connectivity.unregisterNetworkCallback(it) }
        HeartbeatAlarmsProbe(app).killListenerAlarms()
    }

    private companion object {
        const val PACKAGE = "com.brickssoft.locationtracking"
    }
}

/** The simulated phone: permissions, location settings, network and battery, controlled through Robolectric. */
internal class DeviceEnvironment(private val app: Application) {
    val locationManager: LocationManager = app.getSystemService(LocationManager::class.java)
    val connectivityManager: ConnectivityManager = app.getSystemService(ConnectivityManager::class.java)

    fun install() {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            // Below API 33 ContextCompat's RECEIVER_NOT_EXPORTED needs it (granted by androidx.core in real apps).
            app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        setLocation(enabled = true, gps = true, network = true)
        setWifi()
        battery(level = 81, charging = false)
    }

    /** Changes the location settings and sends the broadcast the OS sends; receivers run on the main looper. */
    fun setLocation(enabled: Boolean, gps: Boolean, network: Boolean, broadcast: Boolean = false) {
        val shadow = shadowOf(locationManager)
        shadow.setLocationEnabled(enabled)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, gps)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, network)
        if (broadcast) {
            app.sendBroadcast(Intent(LocationManager.PROVIDERS_CHANGED_ACTION))
            idleMainLooper()
        }
    }

    /** The default network is Wi-Fi with internet access. */
    fun setWifi() {
        val shadow = shadowOf(connectivityManager)
        shadow.setDefaultNetworkActive(true)
        shadow.setNetworkCapabilities(connectivityManager.activeNetwork, wifiCapabilities())
    }

    /** The default network is cellular with internet access. */
    fun setCellular() {
        val shadow = shadowOf(connectivityManager)
        shadow.setDefaultNetworkActive(true)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(connectivityManager.activeNetwork, caps)
    }

    /**
     * The OS reports losing the default network and then a new Wi-Fi network, through the registered default
     * network callbacks (DefaultDeviceMonitor's).
     */
    fun reconnectWifi(newNetwork: Network) {
        val callbacks = shadowOf(connectivityManager).networkCallbacks.toList()
        assertTrue("no network callback is registered", callbacks.isNotEmpty())
        callbacks.forEach { it.onLost(connectivityManager.activeNetwork!!) }
        callbacks.forEach {
            it.onAvailable(newNetwork)
            it.onCapabilitiesChanged(newNetwork, wifiCapabilities())
        }
    }

    /** The system's sticky ACTION_BATTERY_CHANGED (only the system may send it; tests use the deprecated API). */
    @Suppress("DEPRECATION")
    fun battery(level: Int, charging: Boolean) {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, level)
            .putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(
                BatteryManager.EXTRA_STATUS,
                if (charging) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING,
            )
            .putExtra(BatteryManager.EXTRA_PLUGGED, if (charging) BatteryManager.BATTERY_PLUGGED_AC else 0)
        app.sendStickyBroadcast(intent)
        idleMainLooper()
    }

    fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun wifiCapabilities(): NetworkCapabilities {
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return caps
    }
}

/** The heartbeat alarms as Robolectric's AlarmManager holds them. */
internal class HeartbeatAlarmsProbe(app: Application) {
    private val alarmManager: AlarmManager = app.getSystemService(AlarmManager::class.java)

    fun scheduled(): List<ShadowAlarmManager.ScheduledAlarm> = shadowOf(alarmManager).scheduledAlarms

    /** The in-process listener alarm (`setExact` with the heartbeat listener), or null. */
    fun listener(): ShadowAlarmManager.ScheduledAlarm? = scheduled().singleOrNull { listenerOf(it) != null }

    /** The heartbeat PendingIntent alarm (exact or allow-while-idle backup), or null. */
    fun intent(): ShadowAlarmManager.ScheduledAlarm? = scheduled().singleOrNull { operationOf(it) != null }

    /** Delivers the listener alarm like AlarmManager does (the heartbeat work is launched on the process scope). */
    fun fireListener() {
        val alarm = listener()
        assertNotNull("no heartbeat listener alarm is scheduled", alarm)
        listenerOf(alarm!!)!!.onAlarm()
    }

    /** Listener alarms die with their process; PendingIntent alarms survive. */
    fun killListenerAlarms() {
        scheduled().mapNotNull { listenerOf(it) }.forEach { alarmManager.cancel(it) }
    }

    /** A reboot wipes every alarm. */
    fun reboot() {
        killListenerAlarms()
        scheduled().mapNotNull { operationOf(it) }.forEach { alarmManager.cancel(it) }
    }

    companion object {
        // ScheduledAlarm exposes the listener and the PendingIntent only as deprecated fields.
        @Suppress("DEPRECATION")
        fun listenerOf(alarm: ShadowAlarmManager.ScheduledAlarm): AlarmManager.OnAlarmListener? = alarm.onAlarmListener

        @Suppress("DEPRECATION")
        fun operationOf(alarm: ShadowAlarmManager.ScheduledAlarm) = alarm.operation

        fun triggerOf(alarm: ShadowAlarmManager.ScheduledAlarm) =
            HeartbeatIntents.triggerOf(shadowOf(operationOf(alarm)).savedIntent)
    }
}

/**
 * The customer's ingest server: a [MockWebServer] that records every request (path, headers, body) and answers
 * through [respond] (default 200). Bodies are parsed as JSON on the test thread.
 */
internal class IngestServer : Closeable {
    /** One request as the server received it, with the status it answered. */
    class Request(val path: String, val method: String, val headers: Headers, val body: String, val status: Int) {
        val json: JSONObject by lazy { JSONObject(body) }

        /** The records of a location upload: the object, or the batch array, under `location`. */
        val records: List<JSONObject>
            get() = when (val value = json.opt("location")) {
                is JSONArray -> (0 until value.length()).map { value.getJSONObject(it) }
                is JSONObject -> listOf(value)
                else -> emptyList()
            }

        val isBatch: Boolean get() = json.opt("location") is JSONArray
        val events: List<String> get() = records.map { it.getString("event") }
        val accepted: Boolean get() = status in 200..299
    }

    private val server = MockWebServer()
    val requests = CopyOnWriteArrayList<Request>()

    /** Decides the response from the request's path, headers and body text. */
    @Volatile
    var respond: (path: String, headers: Headers, body: String) -> MockResponse = { _, _, _ -> ok() }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                val response = respond(path, request.headers, body)
                val status = response.status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                requests += Request(path, request.method.orEmpty(), request.headers, body, status)
                return response
            }
        }
    }

    fun start() = server.start()

    fun url(path: String = LOCATIONS): String = server.url(path).toString()

    /** Record uploads (every request except the token refresh), in arrival order. */
    val uploads: List<Request> get() = requests.filter { it.path != REFRESH }

    /** Every uploaded record, in order (retries included). */
    fun received(): List<JSONObject> = uploads.flatMap { it.records }

    /** Records the server accepted (2xx), in order. */
    fun accepted(): List<JSONObject> = uploads.filter { it.accepted }.flatMap { it.records }

    fun receivedEvents(): List<String> = received().map { it.getString("event") }

    fun acceptedEvents(): List<String> = accepted().map { it.getString("event") }

    override fun close() = server.shutdown()

    companion object {
        const val LOCATIONS = "/locations"
        const val REFRESH = "/auth/refresh"

        fun ok(body: String = """{"ok":true}""") = MockResponse().setResponseCode(200).setBody(body)

        fun status(code: Int) = MockResponse().setResponseCode(code).setBody("""{"error":$code}""")

        /** True if [body] is an upload containing a record of [event] (a cheap check usable on the server thread). */
        fun contains(body: String, event: String) = body.contains("\"event\":\"$event\"")
    }
}

/** Asserts on the wire shape of architecture §2. */
internal object Wire {
    /** Keys every record must carry (the event-specific keys and `extras` are optional). */
    val RECORD_KEYS = listOf(
        "uuid", "event", "timestamp", "recorded_at", "sent_at", "elapsed_realtime_ms", "boot_count", "is_moving",
        "odometer", "mock", "coords", "activity", "battery", "backend",
    )

    /**
     * Every record of [request] has the §2 keys, ISO-8601 timestamps with `sent_at >= recorded_at`, a well-formed
     * activity / battery / backend, and [params] merged at the root of the body.
     */
    fun assertShape(request: IngestServer.Request, params: Map<String, Any> = emptyMap()) {
        assertTrue("an upload holds at least one record: ${request.body}", request.records.isNotEmpty())
        for (record in request.records) {
            for (key in RECORD_KEYS) assertTrue("record lacks '$key': $record", record.has(key))
            val recordedAt = Iso8601.parse(record.getString("recorded_at"))
            val sentAt = Iso8601.parse(record.getString("sent_at"))
            assertNotNull("recorded_at is ISO-8601: $record", recordedAt)
            assertNotNull("sent_at is ISO-8601: $record", sentAt)
            assertTrue("sent_at >= recorded_at: $record", sentAt!! >= recordedAt!!)
            val activity = record.getJSONObject("activity")
            assertTrue(activity.has("type") && activity.has("confidence"))
            val battery = record.getJSONObject("battery")
            assertTrue(battery.has("level") && battery.has("is_charging"))
            assertTrue("backend: $record", record.getString("backend") in setOf("gms", "hms", "android"))
            if (!record.isNull("coords")) {
                val coords = record.getJSONObject("coords")
                for (key in listOf("latitude", "longitude", "accuracy", "altitude", "speed", "heading")) {
                    assertTrue("coords lacks '$key': $record", coords.has(key))
                }
                assertNotNull("a record with coords has a timestamp", Iso8601.parse(record.getString("timestamp")))
            }
        }
        for ((key, value) in params) assertEquals("param '$key' at the body root", value, request.json.get(key))
    }

    fun latitude(record: JSONObject): Double = record.getJSONObject("coords").getDouble("latitude")

    fun longitude(record: JSONObject): Double = record.getJSONObject("coords").getDouble("longitude")

    fun recordedAt(record: JSONObject): Long = Iso8601.parse(record.getString("recorded_at"))!!

    fun sentAt(record: JSONObject): Long = Iso8601.parse(record.getString("sent_at"))!!
}

/** "The GPS": fixes north / east of ([Fixtures.LAT], [Fixtures.LNG]), stamped with the (virtual) clock. */
internal class Gps(private val clock: FakeClock) {
    /** A fix [northMeters] / [eastMeters] from the origin, taken now. */
    fun fix(northMeters: Double, eastMeters: Double = 0.0, speed: Float? = 13.4f, accuracy: Float = 5f): TrackedLocation {
        val origin = Fixtures.location(
            accuracy = accuracy,
            speed = speed,
            time = clock.now(),
            elapsedRealtimeNanos = clock.elapsedRealtime() * 1_000_000L,
        )
        return Fixtures.moved(origin, northMeters, eastMeters, timeDeltaMs = 0L)
    }

    /** The latitude of [fix] at [northMeters]. */
    fun latitudeAt(northMeters: Double): Double = Fixtures.moved(Fixtures.location(), northMeters).latitude
}

/**
 * Shared fixture of the integration tests: the simulated phone ([env]), the ingest server ([server]), Robolectric's
 * heartbeat alarms ([alarms]), a log sink that must stay free of errors ([logs]), virtual time ([clock], following
 * the test scheduler) and the GPS ([gps]). Processes made with [newProcess] share the clock, SharedPreferences and
 * (given the same database name) the SQLite file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class FullStackTestBase {
    protected val app: Application = ApplicationProvider.getApplicationContext()
    protected val env = DeviceEnvironment(app)
    protected val alarms = HeartbeatAlarmsProbe(app)
    protected val server = IngestServer()
    protected val logs = FakeLogStore()
    private val processes = mutableListOf<FullStackProcess>()
    protected lateinit var clock: FakeClock
    protected lateinit var gps: Gps

    @Before
    fun setUpFullStack() {
        env.install()
        server.start()
        Logger.sink = logs
    }

    @After
    fun tearDownFullStack() {
        processes.forEach { it.close() }
        server.close()
        Logger.sink = null
    }

    /** A new app process; [databaseName] null = in-memory database (a restart needs a file name). */
    protected fun TestScope.newProcess(
        databaseName: String? = null,
        providers: FakeProviderFactory = FakeProviderFactory(ProviderKind.GMS),
        service: FakeServiceController = FakeServiceController(),
    ): FullStackProcess {
        if (!::clock.isInitialized) {
            clock = FakeClock(scheduler = testScheduler)
            gps = Gps(clock)
        }
        val dispatchers = testDispatchers(testScheduler)
        return FullStackProcess(app, backgroundScope, dispatchers, clock, databaseName, providers, service)
            .also { processes += it }
    }

    /**
     * The `ready()` config: the ingest server, [PARAMS] and the 180-300 s heartbeat, plus [http] overrides and other
     * config [sections] (e.g. `"geolocation" to JSONObject(...)`).
     */
    protected fun config(http: Map<String, Any> = emptyMap(), sections: Map<String, JSONObject> = emptyMap()): JSONObject {
        val httpJson = JSONObject()
            .put("url", server.url())
            .put("params", JSONObject(PARAMS))
            .put("autoSync", true)
        http.forEach { (key, value) -> httpJson.put(key, value) }
        val config = JSONObject()
            .put("http", httpJson)
            .put("heartbeat", JSONObject().put("minInterval", 180).put("maxInterval", 300))
        sections.forEach { (key, value) -> config.put(key, value) }
        return config
    }

    /** What the OS's location service reports: [at] is both the cached and the next current location. */
    protected fun located(p: FullStackProcess, at: TrackedLocation): TrackedLocation {
        p.providers.locationBackend.lastLocation = at
        p.providers.locationBackend.currentLocation = at
        return at
    }

    /** `start()` with [at] as the OS's cached location and the initial fix; settles everything due now. */
    protected suspend fun TestScope.start(p: FullStackProcess, at: TrackedLocation = gps.fix(0.0, speed = 0f)): TrackedLocation {
        located(p, at)
        p.engine.start()
        runCurrent()
        return at
    }

    protected fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** The GPS delivers [fixes] (one batch); the pipeline, the sink and the uploads run. */
    protected fun TestScope.emit(p: FullStackProcess, vararg fixes: TrackedLocation) {
        p.providers.locationBackend.emit(*fixes)
        runCurrent()
    }

    /** Toggles the location settings like the user does; the monitor's debounced check runs. */
    protected fun TestScope.setLocation(enabled: Boolean, gps: Boolean, network: Boolean) {
        env.setLocation(enabled, gps, network, broadcast = true)
        advance(DefaultDeviceMonitor.PROVIDER_CHECK_DEBOUNCE_MS)
    }

    protected fun uuids(records: List<JSONObject>) = records.map { it.getString("uuid") }

    protected fun heartbeatsReceived() = server.received().filter { it.getString("event") == "heartbeat" }

    protected fun geofenceActions() = server.received()
        .filter { it.getString("event") == "geofence" }
        .map { it.getJSONObject("geofence").getString("action") }

    /** No coroutine crashed, nothing was logged at ERROR, every upload has the documented shape. */
    protected fun assertHealthy(vararg ps: FullStackProcess) {
        ps.forEach { assertTrue("uncaught: ${it.uncaught}", it.uncaught.isEmpty()) }
        val errors = logs.lines.filter { it.level == LogLevel.ERROR }
        assertTrue("errors were logged: ${errors.joinToString("\n")}", errors.isEmpty())
        server.uploads.forEach { Wire.assertShape(it, PARAMS_MAP) }
    }

    companion object {
        const val MINUTE = 60_000L

        /** `heartbeat.minInterval` of [config], ms. */
        const val MIN_MS = 180_000L
        const val PARAMS = """{"device_id":"abc","fleet":7}"""
        val PARAMS_MAP: Map<String, Any> = mapOf("device_id" to "abc", "fleet" to 7)
    }
}
