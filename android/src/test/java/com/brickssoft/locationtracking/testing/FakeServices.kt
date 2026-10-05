package com.brickssoft.locationtracking.testing

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.State
import com.brickssoft.locationtracking.config.TrackingMode
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.engine.TrackingEngine
import com.brickssoft.locationtracking.geofence.GeofenceManager
import com.brickssoft.locationtracking.heartbeat.HeartbeatScheduler
import com.brickssoft.locationtracking.heartbeat.HeartbeatTrigger
import com.brickssoft.locationtracking.http.HttpSyncer
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.GeofenceSpec
import com.brickssoft.locationtracking.model.HeartbeatStatus
import com.brickssoft.locationtracking.model.HeartbeatStrategy
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.position.CurrentPositionOptions
import com.brickssoft.locationtracking.position.PositionService
import com.brickssoft.locationtracking.position.WatchPositionOptions
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import com.brickssoft.locationtracking.service.ServiceController
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Recording [HttpSyncer]; `sync` throws [syncError] if set, else returns [syncResult]. */
class FakeHttpSyncer : HttpSyncer {
    val inserted = CopyOnWriteArrayList<Record>()

    @Volatile
    var startCalls = 0

    @Volatile
    var syncCalls = 0

    @Volatile
    var syncResult: List<Record> = emptyList()

    @Volatile
    var syncError: TrackingException? = null

    override fun start() {
        startCalls++
    }

    override fun onRecordInserted(record: Record) {
        inserted += record
    }

    override suspend fun sync(): List<Record> {
        syncCalls++
        syncError?.let { throw it }
        return syncResult
    }
}

/** Recording [HeartbeatScheduler]; `status` returns [statusValue]. */
class FakeHeartbeatScheduler : HeartbeatScheduler {
    val recorded = CopyOnWriteArrayList<Record>()
    val alarms = CopyOnWriteArrayList<HeartbeatTrigger>()

    @Volatile
    var startCalls = 0

    @Volatile
    var stopCalls = 0

    @Volatile
    var running = false

    @Volatile
    var statusValue = HeartbeatStatus(
        enabled = true,
        minInterval = 180,
        maxInterval = 300,
        lastRecordAt = null,
        lastHeartbeatAt = null,
        nextHeartbeatAt = null,
        strategy = HeartbeatStrategy.EXACT,
        canScheduleExactAlarms = true,
        isIgnoringBatteryOptimizations = false,
        isDeviceIdleMode = false,
        isPowerSaveMode = false,
        pendingHeartbeats = 0,
    )

    override fun start() {
        startCalls++
        running = true
    }

    override fun stop() {
        stopCalls++
        running = false
    }

    override fun onRecordRecorded(record: Record) {
        recorded += record
    }

    override suspend fun onAlarm(trigger: HeartbeatTrigger) {
        alarms += trigger
    }

    override suspend fun status(): HeartbeatStatus = statusValue
}

/** In-memory [GeofenceManager] without OS registration, polygon math or events. */
class FakeGeofenceManager : GeofenceManager {
    private val geofences = LinkedHashMap<String, GeofenceSpec>()
    val locations = CopyOnWriteArrayList<TrackedLocation>()
    val transitions = CopyOnWriteArrayList<OsGeofenceTransition>()
    val startedModes = CopyOnWriteArrayList<TrackingMode>()

    @Volatile
    var stoppedCalls = 0

    /** If set, `add` throws it (e.g. TOO_MANY_GEOFENCES). */
    @Volatile
    var addError: TrackingException? = null

    override val needsContinuousLocation = MutableStateFlow(false)

    override suspend fun add(geofences: List<GeofenceSpec>) {
        addError?.let { throw it }
        synchronized(this.geofences) { for (g in geofences) this.geofences[g.identifier] = g }
    }

    override suspend fun remove(ids: List<String>) {
        synchronized(geofences) { ids.forEach { geofences.remove(it) } }
    }

    override suspend fun removeAll() {
        synchronized(geofences) { geofences.clear() }
    }

    override suspend fun list(): List<GeofenceSpec> = synchronized(geofences) { geofences.values.toList() }

    override suspend fun get(id: String): GeofenceSpec? = synchronized(geofences) { geofences[id] }

    override suspend fun onTrackingStarted(mode: TrackingMode) {
        startedModes += mode
    }

    override suspend fun onTrackingStopped() {
        stoppedCalls++
    }

    override fun onLocation(location: TrackedLocation) {
        locations += location
    }

    override suspend fun onGeofenceTransitions(transitions: List<OsGeofenceTransition>) {
        this.transitions += transitions
    }
}

/** [ServiceController]; `start` returns [startResult] and sets [isRunning] accordingly. */
class FakeServiceController : ServiceController {
    @Volatile
    override var isRunning = false

    @Volatile
    var startResult = true

    @Volatile
    var startCalls = 0

    @Volatile
    var stopCalls = 0

    @Volatile
    var refreshCalls = 0

    override fun start(): Boolean {
        startCalls++
        if (startResult) isRunning = true
        return startResult
    }

    override fun stop() {
        stopCalls++
        isRunning = false
    }

    override fun refreshNotification() {
        refreshCalls++
    }

    /** The deadline of each [showResumeNotification] call. */
    val resumeNotifications = java.util.concurrent.CopyOnWriteArrayList<Long?>()

    @Volatile
    var cancelResumeCalls = 0

    override fun showResumeNotification(deadline: Long?) {
        resumeNotifications += deadline
    }

    override fun cancelResumeNotification() {
        cancelResumeCalls++
    }
}

/**
 * [PositionService] with a scripted result: `getCurrentPosition` throws [currentError] if set, else returns
 * [currentResult] (or [Fixtures.record] with event CURRENT_POSITION). Use [emitWatch] to drive a watch.
 */
class FakePositionService : PositionService {
    val currentCalls = CopyOnWriteArrayList<CurrentPositionOptions>()
    val watches = ConcurrentHashMap<String, Pair<WatchPositionOptions, (Record?, TrackingException?) -> Unit>>()

    @Volatile
    var currentResult: Record? = null

    @Volatile
    var currentError: TrackingException? = null

    @Volatile
    var clearAllCalls = 0

    override suspend fun getCurrentPosition(o: CurrentPositionOptions): Record {
        currentCalls += o
        currentError?.let { throw it }
        return currentResult ?: Fixtures.record(event = RecordEvent.CURRENT_POSITION)
    }

    override fun watchPosition(id: String, o: WatchPositionOptions, callback: (Record?, TrackingException?) -> Unit) {
        watches[id] = o to callback
    }

    override fun clearWatch(id: String): Boolean = watches.remove(id) != null

    override fun clearAllWatches() {
        clearAllCalls++
        watches.clear()
    }

    /** Invokes the callback of watch [id]; returns false if there is no such watch. */
    fun emitWatch(id: String, record: Record?, error: TrackingException? = null): Boolean {
        val watch = watches[id] ?: return false
        watch.second(record, error)
        return true
    }
}

/**
 * Recording [TrackingEngine] over a [ConfigStore]. start/startGeofences/stop flip `runtime.enabled` and the
 * mode; config calls go to the store. If [failWith] is set, every suspend call throws it. [calls] records the
 * method names in order.
 */
class FakeTrackingEngine(
    val configStore: ConfigStore = FakeConfigStore(),
    @Volatile var backend: ProviderKind = ProviderKind.GMS,
) : TrackingEngine {
    val calls = CopyOnWriteArrayList<String>()
    val activitySamples = CopyOnWriteArrayList<ActivitySample>()
    val restoreReasons = CopyOnWriteArrayList<String>()
    val paceChanges = CopyOnWriteArrayList<Boolean>()

    @Volatile
    var failWith: TrackingException? = null

    override suspend fun ready(config: JSONObject?, reset: Boolean): State = call("ready") {
        configStore.ready(config, reset)
        configStore.updateRuntime { it.copy(didReady = true) }
    }

    override suspend fun setConfig(config: JSONObject): State = call("setConfig") { configStore.merge(config) }

    override suspend fun reset(config: JSONObject?): State = call("reset") { configStore.reset(config) }

    override suspend fun start(): State = call("start") {
        configStore.updateRuntime { it.copy(enabled = true, trackingMode = TrackingMode.LOCATION) }
    }

    override suspend fun startGeofences(): State = call("startGeofences") {
        configStore.updateRuntime { it.copy(enabled = true, trackingMode = TrackingMode.GEOFENCES) }
    }

    override suspend fun stop(): State = call("stop") { configStore.updateRuntime { it.copy(enabled = false) } }

    override suspend fun changePace(isMoving: Boolean) {
        call("changePace") {
            paceChanges += isMoving
            configStore.updateRuntime { it.copy(isMoving = isMoving) }
        }
    }

    override fun state(): State = State(configStore.config.value, configStore.runtime.value, backend)

    override suspend fun restore(reason: String) {
        call("restore") { restoreReasons += reason }
    }

    override suspend fun onTerminate() {
        call("onTerminate") {}
    }

    val serviceStartFailures = CopyOnWriteArrayList<String>()
    val endReasons = CopyOnWriteArrayList<String>()

    override suspend fun endWithoutRestore(reason: String) {
        call("endWithoutRestore") {
            endReasons += reason
            configStore.updateRuntime { it.copy(enabled = false) }
        }
    }

    override suspend fun onServiceStartFailed(error: String) {
        call("onServiceStartFailed") { serviceStartFailures += error }
    }

    override suspend fun resumeFromNotification() {
        call("resumeFromNotification") {}
    }

    override suspend fun onActivitySamples(samples: List<ActivitySample>) {
        call("onActivitySamples") { activitySamples += samples }
    }

    private inline fun call(name: String, block: () -> Unit): State {
        calls += name
        failWith?.let { throw it }
        block()
        return state()
    }
}
