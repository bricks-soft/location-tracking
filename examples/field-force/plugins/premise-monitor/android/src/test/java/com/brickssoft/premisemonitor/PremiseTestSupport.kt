package com.brickssoft.premisemonitor

import com.brickssoft.locationtracking.core.Iso8601
import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.api.NativeCallback
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Stands in for the tracking plugin's geofence API; answers synchronously on the calling thread. */
internal class FakeGateway : TrackingGateway {
    val added = CopyOnWriteArrayList<JSONObject>()
    val removed = CopyOnWriteArrayList<String>()
    val geofences = java.util.Collections.synchronizedMap(LinkedHashMap<String, JSONObject>())

    @Volatile
    var addError: Throwable? = null

    /** When true, addGeofence answers only when [releaseAdds] is called (a slow tracking plugin). */
    @Volatile
    var deferAdds = false
    private val deferred = CopyOnWriteArrayList<() -> Unit>()

    fun releaseAdds() {
        deferAdds = false
        val pending = deferred.toList()
        deferred.clear()
        pending.forEach { it() }
    }

    /** Thrown synchronously by addGeofence (a broken tracking plugin). */
    @Volatile
    var addThrows: RuntimeException? = null

    override fun addGeofence(context: Context, geofence: JSONObject, callback: NativeCallback<Unit>) {
        addThrows?.let { throw it }
        if (deferAdds) {
            deferred.add { addGeofence(context, geofence, callback) }
            return
        }
        added.add(geofence)
        val error = addError
        if (error != null) {
            callback.onResult(Result.failure(error))
        } else {
            geofences[geofence.getString("identifier")] = geofence
            callback.onResult(Result.success(Unit))
        }
    }

    override fun removeGeofence(context: Context, identifier: String, callback: NativeCallback<Unit>) {
        removed.add(identifier)
        geofences.remove(identifier)
        callback.onResult(Result.success(Unit))
    }

    override fun getGeofences(context: Context, callback: NativeCallback<JSONArray>) {
        val array = JSONArray()
        synchronized(geofences) { geofences.values.forEach { array.put(it) } }
        callback.onResult(Result.success(array))
    }
}

/** A service control that records requests and can refuse starts. */
internal class FakeServiceControl(@Volatile var refusal: Throwable? = null) : PremiseServiceControl {
    val starts = CopyOnWriteArrayList<String>()
    val stops = CopyOnWriteArrayList<String>()

    @Volatile
    var running = false

    override val isRunningOrStarting: Boolean get() = running
    override val isRunning: Boolean get() = running

    override fun start(context: Context, premiseId: String, reason: String): Throwable? {
        starts.add(reason)
        refusal?.let { return it }
        running = true
        return null
    }

    override fun stop(context: Context, reason: String) {
        stops.add(reason)
        running = false
    }
}

/**
 * Base of the Robolectric tests: a fresh [PremiseMonitorCore] per test (installed as the process instance, so the
 * listener, the native API and the service use it), a [FakeGateway], location permissions granted and the service's
 * static state reset.
 */
internal abstract class PremiseTestBase {
    protected lateinit var app: Application
    protected lateinit var gateway: FakeGateway
    protected lateinit var core: PremiseMonitorCore
    protected val listener = PremiseAuditListener()

    @Before
    fun setUpBase() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        ProcessInfo.jsLoaded = false
        resetServiceStatics()
        gateway = FakeGateway()
        core = newCore()
        PremiseMonitorCore.install(core)
    }

    @After
    fun tearDownBase() {
        PremiseMonitorCore.install(null)
        ProcessInfo.jsLoaded = false
        resetServiceStatics()
    }

    protected fun newCore(
        services: PremiseServiceControl = AndroidServiceControl,
        retryDelayMs: Long = AuditUploader.RETRY_DELAY_MS,
        restartBackoffMs: Long = PremiseMonitorCore.RESTART_BACKOFF_MS,
        clock: () -> Long = System::currentTimeMillis,
    ) = PremiseMonitorCore(
        app,
        AuditStore(app),
        PremiseState(app),
        gateway,
        services,
        retryDelayMs,
        restartBackoffMs,
        clock,
    )

    /** Replaces the process instance, like a new process with the same data would (statics of the old one reset). */
    protected fun simulateNewProcess(
        services: PremiseServiceControl = AndroidServiceControl,
        clock: () -> Long = System::currentTimeMillis,
    ) {
        core.awaitIdle() // the old "process" finishes what it queued (for example a service_started entry)
        PremiseMonitorCore.install(null)
        ProcessInfo.jsLoaded = false
        resetServiceStatics()
        drainStartedServices()
        core = newCore(services = services, clock = clock)
        PremiseMonitorCore.install(core)
    }

    protected fun resetServiceStatics() {
        PremiseMonitorService.isRunning = false
        PremiseMonitorService.pendingStarts.set(0)
        AndroidServiceControl.reset()
    }

    protected fun <T> await(call: (NativeCallback<T>) -> Unit): Result<T> {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<T>>()
        call(NativeCallback { result.set(it); latch.countDown() })
        check(latch.await(10, TimeUnit.SECONDS)) { "callback not invoked" }
        return result.get()
    }

    protected fun startMonitoring(premise: JSONObject = HQ.toJson(), auditUrl: String? = null): JSONObject =
        await<JSONObject> { PremiseMonitorNative.start(app, premise, auditUrl, it) }.getOrThrow()

    protected fun status(): JSONObject = await<JSONObject> { PremiseMonitorNative.status(app, it) }.getOrThrow()

    protected fun entries(limit: Int = 0): List<JSONObject> {
        core.awaitIdle()
        val array = await<JSONArray> { PremiseMonitorNative.auditLog(app, limit, it) }.getOrThrow()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    protected fun premiseEntries(type: String? = null): List<JSONObject> =
        entries().filter { it.getString("kind") == "premise" && (type == null || it.getString("type") == type) }

    protected fun deliverRecord(record: JSONObject) {
        listener.onRecord(app, record)
        core.awaitIdle()
    }

    /** Intents passed to startService/startForegroundService since the last call. */
    protected fun drainStartedServices(): List<Intent> {
        val intents = ArrayList<Intent>()
        while (true) intents.add(shadowOf(app).nextStartedService ?: break)
        return intents
    }

    /** Runs the service's start command like the system would for [intent]. */
    protected fun runService(intent: Intent, startId: Int = 1): ServiceController<PremiseMonitorService> {
        val controller = Robolectric.buildService(PremiseMonitorService::class.java, intent).create()
        controller.startCommand(0, startId)
        return controller
    }

    /** Delivers a later command to a running service (what the system does for a second startService). */
    protected fun deliverCommand(controller: ServiceController<PremiseMonitorService>, intent: Intent, startId: Int) {
        controller.get().onStartCommand(intent, 0, startId)
    }

    /** The one start request made since the last drain; asserts its extras. */
    protected fun singleStartRequest(reason: String): Intent {
        val starts = drainStartedServices().filter { it.action == PremiseMonitorService.ACTION_START }
        assertEquals("start requests", 1, starts.size)
        val intent = starts[0]
        assertNotNull(intent.component)
        assertEquals(PremiseMonitorService::class.java.name, intent.component!!.className)
        assertEquals(HQ.id, intent.getStringExtra(PremiseMonitorService.EXTRA_PREMISE_ID))
        assertEquals(reason, intent.getStringExtra(PremiseMonitorService.EXTRA_REASON))
        return intent
    }

    companion object {
        val HQ = Premise("hq", "Head office", 24.7136, 46.6753, 150.0)

        /** Meters per degree of latitude with the mean earth radius used by [Geo]. */
        const val METERS_PER_DEGREE_LAT = 6_371_008.8 * Math.PI / 180.0

        /** A point [meters] north of the premise centre. */
        fun northOf(premise: Premise, meters: Double): Pair<Double, Double> =
            Pair(premise.latitude + meters / METERS_PER_DEGREE_LAT, premise.longitude)

        /** A wire record like `RecordJson.toJson` writes it (only the keys PremiseMonitor reads matter). */
        fun wireRecord(
            event: String,
            latitude: Double? = HQ.latitude,
            longitude: Double? = HQ.longitude,
            accuracy: Double = 10.0,
            timestamp: Long = System.currentTimeMillis(),
            recordedAt: Long = timestamp,
            geofence: JSONObject? = null,
            reason: String? = null,
        ): JSONObject {
            val record = JSONObject()
                .put("uuid", UUID.randomUUID().toString())
                .put("event", event)
                .put("timestamp", if (latitude == null) JSONObject.NULL else Iso8601.format(timestamp))
                .put("recorded_at", Iso8601.format(recordedAt))
                .put("is_moving", false)
                .put("odometer", 0.0)
                .put("mock", false)
                .put("battery", JSONObject().put("level", 0.8).put("is_charging", false))
                .put("backend", "gms")
            if (latitude != null && longitude != null) {
                record.put(
                    "coords",
                    JSONObject().put("latitude", latitude).put("longitude", longitude).put("accuracy", accuracy),
                )
            } else {
                record.put("coords", JSONObject.NULL)
            }
            if (geofence != null) record.put("geofence", geofence)
            if (reason != null) record.put("reason", reason)
            return record
        }

        fun geofenceRecord(
            action: String,
            identifier: String = HQ.geofenceId,
            latitude: Double = HQ.latitude,
            longitude: Double = HQ.longitude,
            at: Long = System.currentTimeMillis(),
        ): JSONObject = wireRecord(
            "geofence",
            latitude,
            longitude,
            timestamp = at,
            geofence = JSONObject().put("identifier", identifier).put("action", action),
        )
    }
}
