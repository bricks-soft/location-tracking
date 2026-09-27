package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.GeolocationConfig
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.config.State
import com.brickssoft.locationtracking.config.TrackingMode
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.Subscription
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.geofence.GeofenceManager
import com.brickssoft.locationtracking.heartbeat.HeartbeatScheduler
import com.brickssoft.locationtracking.http.HttpSyncer
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.model.PermissionLevel
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.permission.PermissionManager
import com.brickssoft.locationtracking.processing.FilterResult
import com.brickssoft.locationtracking.processing.LocationProcessor
import com.brickssoft.locationtracking.processing.Odometer
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.provider.ActivityBackend
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.LocationListener
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.record.RecordSink
import com.brickssoft.locationtracking.service.ServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Default [TrackingEngine]: runs the tracking session (foreground service, location and activity backends,
 * heartbeat, geofences), the STATIONARY / MOVING [MotionStateMachine], the location pipeline and the
 * `tracking_start` / `tracking_stop` / `motionchange` records.
 *
 * Every state change runs on [scope]'s dispatcher (`dispatchers.engine`, single-threaded) while holding one
 * mutex, so public calls, backend callbacks (any thread) and timers are serialized.
 *
 * Location pipeline, for each fix while tracking: `processor` -> (LOCATION mode) motion state machine ->
 * `odometer` -> `geofences.onLocation` -> elastic distance check against the last recorded fix ->
 * `recordFactory` -> `recordSink`. While STATIONARY a fix only feeds the state machine and the geofences
 * (no odometer, no record), so GPS jitter while parked is neither recorded nor counted.
 */
class DefaultTrackingEngine(
    private val configStore: ConfigStore,
    private val providers: ProviderFactory,
    private val processor: LocationProcessor,
    private val odometer: Odometer,
    private val recordFactory: RecordFactory,
    private val recordSink: RecordSink,
    private val heartbeat: HeartbeatScheduler,
    private val geofences: GeofenceManager,
    private val serviceController: ServiceController,
    private val device: DeviceMonitor,
    private val syncer: HttpSyncer,
    private val permissions: PermissionManager,
    private val events: EventBus,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : TrackingEngine {
    private val mutex = Mutex()
    private val engineContext: CoroutineContext =
        scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext
    private val timers = TrackingTimers(scope, clock, mutex)

    /** The tracking session running in this process, or null. Guarded by [mutex]. */
    private var session: Session? = null
    private var nextSessionId = 1L

    private val config: Config get() = configStore.config.value
    private val runtime: RuntimeState get() = configStore.runtime.value

    // ------------------------------------------------------------------ config

    override suspend fun ready(config: JSONObject?, reset: Boolean): State = serialized {
        val old = this.config
        val new = configStore.ready(config, reset)
        configStore.updateRuntime { it.copy(didReady = true) }
        onConfigChanged(old, new)
        if (session == null && runtime.enabled) {
            Logger.i(TAG, "ready: tracking is enabled but not running in this process; restoring")
            restoreLocked(REASON_RESTORE)
        }
        state()
    }

    override suspend fun setConfig(config: JSONObject): State = serialized {
        val old = this.config
        onConfigChanged(old, configStore.merge(config))
        state()
    }

    override suspend fun reset(config: JSONObject?): State = serialized {
        val old = this.config
        onConfigChanged(old, configStore.reset(config))
        state()
    }

    // ------------------------------------------------------------------ tracking

    override suspend fun start(): State = serialized {
        startLocked(TrackingMode.LOCATION)
        state()
    }

    override suspend fun startGeofences(): State = serialized {
        startLocked(TrackingMode.GEOFENCES)
        state()
    }

    override suspend fun stop(): State = serialized {
        stopLocked(REASON_STOP)
        state()
    }

    override suspend fun changePace(isMoving: Boolean) = serialized {
        val s = session
        when {
            s == null -> Logger.i(TAG, "changePace($isMoving) ignored: tracking is not enabled")
            s.mode != TrackingMode.LOCATION -> Logger.i(TAG, "changePace($isMoving) ignored in geofences mode")
            else -> {
                Logger.i(TAG, "changePace($isMoving)")
                val location = if (isMoving != s.motion.isMoving) bestKnownLocation(s) else null
                execute(s, s.motion.force(isMoving, location))
            }
        }
    }

    override fun state(): State = State(configStore.config.value, configStore.runtime.value, providers.kind)

    override suspend fun restore(reason: String) = serialized { restoreLocked(reason) }

    override suspend fun onServiceStartFailed(error: String) = serialized {
        if (session == null && !runtime.enabled) return@serialized
        Logger.w(TAG, "the foreground service failed to start ($error); stopping tracking")
        stopLocked(REASON_SERVICE_START_FAILED)
    }

    override suspend fun onTerminate() = serialized {
        when {
            !config.app.stopOnTerminate -> Logger.i(TAG, "task removed: stopOnTerminate is false; tracking continues")
            session != null || runtime.enabled -> {
                Logger.i(TAG, "task removed: stopOnTerminate is true; stopping")
                stopLocked(REASON_TERMINATE)
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ ActivitySink

    override suspend fun onActivitySamples(samples: List<ActivitySample>) {
        if (samples.isEmpty()) return
        serialized { handleActivity(samples) }
    }

    // ------------------------------------------------------------------ start / stop / restore

    private suspend fun startLocked(mode: TrackingMode) {
        val reason = if (mode == TrackingMode.LOCATION) REASON_START else REASON_START_GEOFENCES
        val current = session
        if (current != null) {
            if (current.mode == mode) {
                Logger.d(TAG, "${mode.wire} tracking is already running")
            } else {
                switchMode(current, mode, reason)
            }
            return
        }
        if (!permissions.hasForegroundLocation()) {
            throw TrackingException(ErrorCode.PERMISSION_DENIED, "Location permission is not granted")
        }
        if (mode == TrackingMode.GEOFENCES && !permissions.hasBackgroundLocation()) {
            Logger.w(TAG, "startGeofences without background location permission: geofences may not fire in background")
        }
        val wasEnabled = runtime.enabled
        val startedAt = clock.now()
        configStore.updateRuntime {
            it.copy(enabled = true, trackingMode = mode, isMoving = false, trackingStartedAt = startedAt)
        }
        if (!startService()) {
            configStore.updateRuntime { it.copy(enabled = false) }
            if (wasEnabled) releaseOrphanedRegistrations()
            submit(RecordEvent.TRACKING_STOP, bestKnownLocation(null), reason = REASON_PERMISSION_DENIED)
            throw TrackingException(ErrorCode.PERMISSION_DENIED, "The location foreground service could not be started")
        }
        val s = activate(mode, reason)
        events.emit(TrackingEvent.EnabledChange(true))
        if (mode == TrackingMode.LOCATION) launchInitialFix(s)
    }

    /** Cold process with `runtime.enabled`: restarts everything in the persisted mode, without permission prompts. */
    private suspend fun restoreLocked(reason: String) {
        if (!runtime.enabled) {
            Logger.i(TAG, "restore($reason): tracking is not enabled; nothing to restore")
            return
        }
        if (session != null) {
            if (!serviceController.isRunning) {
                Logger.w(TAG, "restore($reason): tracking runs but the foreground service does not; restarting it")
                if (!startService()) {
                    Logger.w(TAG, "restore($reason): the foreground service was refused; stopping tracking")
                    stopLocked(REASON_SERVICE_START_FAILED)
                }
            }
            return
        }
        if (!permissions.hasForegroundLocation()) {
            Logger.w(TAG, "restore($reason): location permission is no longer granted; stopping")
            stopLocked(REASON_PERMISSION_DENIED)
            return
        }
        val mode = runtime.trackingMode
        val startedAt = runtime.trackingStartedAt ?: clock.now()
        configStore.updateRuntime {
            it.copy(enabled = true, trackingMode = mode, isMoving = false, trackingStartedAt = startedAt)
        }
        if (!startService()) {
            // Without the foreground service Android throttles background location and the heartbeat alarms would
            // keep retrying; end the session with an explicit audit record instead.
            Logger.w(TAG, "restore($reason): the foreground service was refused; stopping tracking")
            stopLocked(REASON_SERVICE_START_FAILED)
            return
        }
        val s = activate(mode, reason)
        if (mode == TrackingMode.LOCATION) launchInitialFix(s)
    }

    /** Starts a new session: components, backends, location request, then the `tracking_start` record. */
    private suspend fun activate(mode: TrackingMode, reason: String): Session {
        val s = Session(nextSessionId++, mode, MotionSettings.from(config))
        session = s
        guarded("processor.reset") { processor.reset() }
        guarded("device.start") { device.start() }
        guarded("syncer.start") { syncer.start() }
        guarded("heartbeat.start") { heartbeat.start() }
        guarded("geofences.onTrackingStarted") { geofences.onTrackingStarted(mode) }
        configureMode(s)
        watchGeofenceDemand(s)
        s.eventSubscription = events.subscribe { event -> onEvent(s, event) }
        submit(RecordEvent.TRACKING_START, bestKnownLocation(s), reason = reason)
        Logger.i(TAG, "tracking started: session=${s.id} mode=${mode.wire} reason=$reason backend=${providers.kind.wire}")
        return s
    }

    /** `start()` while `startGeofences()` runs, or the reverse. */
    private suspend fun switchMode(s: Session, mode: TrackingMode, reason: String) {
        if (!permissions.hasForegroundLocation()) {
            throw TrackingException(ErrorCode.PERMISSION_DENIED, "Location permission is not granted")
        }
        Logger.i(TAG, "switching tracking mode ${s.mode.wire} -> ${mode.wire}")
        s.initialFixJob?.cancel()
        if (s.mode == TrackingMode.LOCATION && s.motion.isMoving) {
            // Motion tracking ends with the location mode.
            recordMotionChange(s, isMoving = false, bestKnownLocation(s))
        }
        s.mode = mode
        val startedAt = clock.now()
        configStore.updateRuntime { it.copy(trackingMode = mode, isMoving = false, trackingStartedAt = startedAt) }
        guarded("geofences.onTrackingStarted") { geofences.onTrackingStarted(mode) }
        configureMode(s)
        submit(RecordEvent.TRACKING_START, bestKnownLocation(s), reason = reason)
        if (mode == TrackingMode.LOCATION) launchInitialFix(s)
    }

    /** Resets the motion state to STATIONARY and applies the mode's activity updates, request and timers. */
    private fun configureMode(s: Session) {
        timers.cancel(TimerKind.STOP_TIMEOUT)
        timers.cancel(TimerKind.MOTION_TRIGGER)
        s.motion.reset()
        s.motionRecorded = false
        s.lastRecorded = null
        s.lastActivityType = null
        if (s.mode == TrackingMode.LOCATION) startActivityUpdates(s) else stopActivityUpdates(s)
        s.needsContinuousLocation = geofences.needsContinuousLocation.value
        applyLocationRequest(s)
        scheduleElapsedStop(s)
    }

    /**
     * Stops tracking: data sources first, then the `tracking_stop` record (before the service stops, so it is
     * queued and its upload starts while the process still has foreground priority), then the service,
     * heartbeat and geofences. Without a session (cold process) it releases what the previous process left
     * registered with the OS.
     */
    private suspend fun stopLocked(reason: String) {
        val s = session
        if (s == null && !runtime.enabled) {
            Logger.d(TAG, "stop($reason): tracking is not enabled")
            return
        }
        session = null
        timers.cancelAll()
        if (s != null) {
            s.initialFixJob?.cancel()
            s.geofenceWatchJob?.cancel()
            s.eventSubscription?.cancel()
            removeLocationUpdates(s)
            stopActivityUpdates(s)
        } else {
            stopActivityBackend()
        }
        submit(RecordEvent.TRACKING_STOP, bestKnownLocation(s), reason = reason)
        guarded("serviceController.stop") { serviceController.stop() }
        guarded("heartbeat.stop") { heartbeat.stop() }
        guarded("geofences.onTrackingStopped") { geofences.onTrackingStopped() }
        configStore.updateRuntime { it.copy(enabled = false, isMoving = false) }
        events.emit(TrackingEvent.EnabledChange(false))
        Logger.i(TAG, "tracking stopped: reason=$reason")
    }

    /** A refused start in a cold process: drop the previous process's activity, alarm and geofence registrations. */
    private suspend fun releaseOrphanedRegistrations() {
        stopActivityBackend()
        guarded("heartbeat.stop") { heartbeat.stop() }
        guarded("geofences.onTrackingStopped") { geofences.onTrackingStopped() }
    }

    private fun startService(): Boolean = try {
        serviceController.start()
    } catch (e: Exception) {
        Logger.e(TAG, "foreground service start failed", e)
        false
    }

    // ------------------------------------------------------------------ config changes

    private suspend fun onConfigChanged(old: Config, new: Config) {
        if (old == new) return
        val s = session
        val providerChanged = old.locationProvider != new.locationProvider
        if (providerChanged) switchProvider(s, old, new)
        if (s == null) return
        if (!providerChanged && activitySettingsChanged(old, new)) {
            stopActivityUpdates(s)
            startActivityUpdates(s)
        }
        execute(s, s.motion.updateSettings(MotionSettings.from(new)))
        if (session !== s) return
        applyLocationRequest(s)
        if (old.geolocation.stopAfterElapsedMinutes != new.geolocation.stopAfterElapsedMinutes) scheduleElapsedStop(s)
        if (old.notification != new.notification) {
            guarded("serviceController.refreshNotification") { serviceController.refreshNotification() }
        }
    }

    private fun activitySettingsChanged(old: Config, new: Config): Boolean =
        old.activity.disableMotionActivityUpdates != new.activity.disableMotionActivityUpdates ||
            old.activity.activityRecognitionInterval != new.activity.activityRecognitionInterval

    /**
     * `locationProvider` changed: re-selects the backend. While tracking, listeners, activity updates and
     * geofences are removed from the current backend first and registered again on the selected one.
     */
    private suspend fun switchProvider(s: Session?, old: Config, new: Config) {
        if (s != null) {
            removeLocationUpdates(s)
            stopActivityUpdates(s)
            guarded("geofences.onTrackingStopped") { geofences.onTrackingStopped() }
        }
        val changed = try {
            providers.reselect()
        } catch (e: Exception) {
            Logger.e(TAG, "provider reselection failed", e)
            false
        }
        Logger.i(
            TAG,
            "locationProvider ${old.locationProvider.wire} -> ${new.locationProvider.wire}: " +
                "backend ${providers.kind.wire}${if (changed) " (changed)" else " (unchanged)"}",
        )
        if (s != null) {
            guarded("geofences.onTrackingStarted") { geofences.onTrackingStarted(s.mode) }
            startActivityUpdates(s)
            applyLocationRequest(s)
        }
        if (changed) guarded("device.checkProviderState") { device.checkProviderState(PROVIDER_CHECK_REASON) }
    }

    // ------------------------------------------------------------------ backends

    private fun applyLocationRequest(s: Session) {
        val spec = LocationRequests.desired(s.mode, s.motion.isMoving, s.needsContinuousLocation, config.geolocation)
        val backend = providers.location()
        val current = s.locationBackend
        if (spec == s.appliedSpec && (spec == null || current === backend)) return
        if (current != null && (spec == null || current !== backend)) removeLocationUpdates(s)
        if (spec == null) {
            Logger.d(TAG, "location request: none")
            return
        }
        try {
            backend.requestUpdates(spec, s.listener)
            s.locationBackend = backend
            s.appliedSpec = spec
            Logger.d(TAG, "location request: $spec on ${backend.kind.wire}")
        } catch (e: Exception) {
            Logger.e(TAG, "location request failed", e)
        }
    }

    private fun removeLocationUpdates(s: Session) {
        val backend = s.locationBackend ?: return
        s.locationBackend = null
        s.appliedSpec = null
        guarded("removeUpdates") { backend.removeUpdates(s.listener) }
    }

    private fun startActivityUpdates(s: Session) {
        if (s.mode != TrackingMode.LOCATION || s.activityBackend != null) return
        val settings = config.activity
        if (settings.disableMotionActivityUpdates) {
            Logger.d(TAG, "activity updates disabled by config")
            return
        }
        val backend = providers.activity()
        if (!backend.isSupported) {
            Logger.i(TAG, "activity recognition is not supported by the ${backend.kind.wire} backend")
            return
        }
        if (!permissions.hasActivityRecognition()) {
            Logger.w(TAG, "activity recognition permission is not granted; motion detection uses location only")
            return
        }
        val started = try {
            backend.start(settings.activityRecognitionInterval)
        } catch (e: Exception) {
            Logger.e(TAG, "activity updates failed to start", e)
            false
        }
        if (started) s.activityBackend = backend else Logger.w(TAG, "activity updates could not be requested")
    }

    private fun stopActivityUpdates(s: Session) {
        val backend = s.activityBackend ?: return
        s.activityBackend = null
        guarded("activity.stop") { backend.stop() }
    }

    private fun stopActivityBackend() {
        guarded("activity.stop") {
            val backend = providers.activity()
            if (backend.isSupported) backend.stop()
        }
    }

    /** Polygon geofences need continuous fixes while the device is inside one's enclosing circle. */
    private fun watchGeofenceDemand(s: Session) {
        s.geofenceWatchJob?.cancel()
        s.geofenceWatchJob = scope.launch {
            geofences.needsContinuousLocation.collect { needs ->
                mutex.withLock {
                    if (session === s && s.needsContinuousLocation != needs) {
                        s.needsContinuousLocation = needs
                        Logger.d(TAG, "polygon geofences ${if (needs) "need" else "no longer need"} continuous location")
                        applyLocationRequest(s)
                    }
                }
            }
        }
    }

    private fun scheduleElapsedStop(s: Session) {
        val minutes = config.geolocation.stopAfterElapsedMinutes
        if (minutes <= 0) {
            timers.cancel(TimerKind.STOP_AFTER_ELAPSED)
            return
        }
        val startedAt = runtime.trackingStartedAt ?: clock.now()
        timers.schedule(TimerKind.STOP_AFTER_ELAPSED, startedAt + minutes * MINUTE_MS - clock.now()) {
            if (session === s) {
                Logger.i(TAG, "stopAfterElapsedMinutes ($minutes) reached")
                guarded("stop after elapsed") { stopLocked(REASON_STOP_AFTER_ELAPSED) }
            }
        }
    }

    // ------------------------------------------------------------------ location pipeline

    private fun dispatchLocations(s: Session, locations: List<TrackedLocation>) {
        if (locations.isEmpty()) return
        val batch = locations.toList()
        scope.launch {
            mutex.withLock {
                for (raw in batch) {
                    if (session !== s) return@withLock
                    guarded("location fix") { handleFix(s, raw) }
                }
                // After the batch, so motion evidence in it wins over a stop timeout that expired while asleep.
                fireDueTimers(s)
            }
        }
    }

    /**
     * `delay()` does not advance while the CPU sleeps, so overdue timers are also checked on every fix, activity
     * sample and heartbeat (whose alarm wakes the device every few minutes).
     */
    private suspend fun fireDueTimers(s: Session) {
        if (session === s) guarded("timers") { timers.fireDue() }
    }

    private fun onEvent(s: Session, event: TrackingEvent) {
        when (event) {
            is TrackingEvent.Heartbeat -> scope.launch { mutex.withLock { fireDueTimers(s) } }
            is TrackingEvent.ProviderChange -> scope.launch { mutex.withLock { onProviderChange(s, event.state) } }
            else -> Unit
        }
    }

    /**
     * GMS and HMS drop every registered geofence when location services are switched off, and geofences registered
     * without background permission may not fire. Registering all stored geofences again when location comes back
     * (or the permission level changes) keeps geofence audit records flowing. Re-adding the same ids is idempotent.
     */
    private suspend fun onProviderChange(s: Session, state: ProviderState) {
        if (session !== s) return
        val wasEnabled = s.providerEnabled
        val oldPermission = s.providerPermission
        s.providerEnabled = state.enabled
        s.providerPermission = state.permission
        if (!state.enabled) return
        if (wasEnabled == true && oldPermission == state.permission) return
        Logger.i(TAG, "location provider available again (${state.permission.wire}); re-registering geofences")
        guarded("geofences.onTrackingStarted") { geofences.onTrackingStarted(s.mode) }
    }

    private suspend fun handleFix(s: Session, raw: TrackedLocation) {
        val fix = when (val result = processor.process(raw, s.motion.isMoving)) {
            is FilterResult.Accepted -> result.location
            is FilterResult.Rejected -> {
                Logger.d(TAG, "fix rejected: ${result.reason}")
                return
            }
        }
        s.lastFix = fix
        if (s.mode == TrackingMode.GEOFENCES) {
            guarded("odometer") { odometer.onLocation(fix) }
            notifyGeofences(fix)
            persistLastLocation(s, fix)
            return
        }
        val wasMoving = s.motion.isMoving
        val actions = s.motion.onLocation(fix)
        if (!wasMoving && !s.motion.isMoving) {
            notifyGeofences(fix)
            persistLastLocation(s, fix)
            return
        }
        guarded("odometer") { odometer.onLocation(fix) }
        notifyGeofences(fix)
        // Leaving STATIONARY records this fix as the `motionchange` (is_moving=true).
        execute(s, actions)
        if (session !== s || !wasMoving) return
        if (ElasticDistance.shouldRecord(s.lastRecorded, fix, config.geolocation)) {
            submit(RecordEvent.LOCATION, fix)
            s.lastRecorded = fix
            s.lastLocationPersistedAt = clock.elapsedRealtime()
        } else {
            persistLastLocation(s, fix)
        }
    }

    private fun notifyGeofences(fix: TrackedLocation) {
        guarded("geofences.onLocation") { geofences.onLocation(fix) }
    }

    /** Keeps `runtime.lastLocation` (used by heartbeats) fresh for fixes that are not recorded, at most every 10 s. */
    private fun persistLastLocation(s: Session, fix: TrackedLocation) {
        val now = clock.elapsedRealtime()
        val last = s.lastLocationPersistedAt
        if (last != null && now - last < LAST_LOCATION_PERSIST_INTERVAL_MS) return
        s.lastLocationPersistedAt = now
        configStore.updateRuntime { it.copy(lastLocation = fix) }
    }

    // ------------------------------------------------------------------ motion

    private suspend fun handleActivity(samples: List<ActivitySample>) {
        if (session == null) {
            if (!runtime.enabled) {
                Logger.v(TAG, "activity ignored: tracking is not enabled")
                return
            }
            // The activity PendingIntent woke a process that was killed while tracking.
            Logger.i(TAG, "activity update in a process where tracking is enabled but not running; restoring")
            restoreLocked(REASON_RESTORE)
        }
        val s = session ?: return
        val best = samples.maxBy { it.confidence }
        val threshold = config.activity.minimumActivityRecognitionConfidence
        if (best.confidence >= threshold) {
            persistActivity(s, best)
            if (s.lastActivityType != best.type) {
                s.lastActivityType = best.type
                Logger.d(TAG, "activity: ${best.type.wire} (${best.confidence})")
                events.emit(TrackingEvent.ActivityChange(best))
            }
            if (s.mode == TrackingMode.LOCATION) execute(s, s.motion.onActivity(best))
        } else {
            Logger.v(TAG, "activity ${best.type.wire} (${best.confidence}) below confidence $threshold")
        }
        fireDueTimers(s)
    }

    /** `runtime.activity`: a new type is written at once, a confidence-only change at most once a minute. */
    private fun persistActivity(s: Session, sample: ActivitySample) {
        val current = runtime.activity
        if (current == sample) return
        val now = clock.elapsedRealtime()
        val last = s.lastActivityPersistedAt
        if (current.type == sample.type && last != null && now - last < ACTIVITY_PERSIST_INTERVAL_MS) return
        s.lastActivityPersistedAt = now
        configStore.updateRuntime { it.copy(activity = sample) }
    }

    private suspend fun execute(s: Session, actions: List<MotionAction>) {
        for (action in actions) {
            if (session !== s) return
            when (action) {
                is MotionAction.StartStopTimer ->
                    timers.schedule(TimerKind.STOP_TIMEOUT, action.delayMs) { onStopTimeout(s) }
                MotionAction.CancelStopTimer -> timers.cancel(TimerKind.STOP_TIMEOUT)
                is MotionAction.StartMotionTrigger ->
                    timers.schedule(TimerKind.MOTION_TRIGGER, action.delayMs) { onMotionTrigger(s) }
                MotionAction.CancelMotionTrigger -> timers.cancel(TimerKind.MOTION_TRIGGER)
                is MotionAction.EnterMoving -> {
                    Logger.i(TAG, "motion: STATIONARY -> MOVING")
                    val location = action.location ?: bestKnownLocation(s)
                    applyLocationRequest(s)
                    recordMotionChange(s, isMoving = true, location)
                }
                is MotionAction.EnterStationary -> {
                    Logger.i(TAG, "motion: MOVING -> STATIONARY${if (action.automatic) " (stop timeout)" else ""}")
                    applyLocationRequest(s)
                    recordMotionChange(s, isMoving = false, action.location)
                    if (action.automatic && config.geolocation.stopOnStationary) stopLocked(REASON_STOP_ON_STATIONARY)
                }
            }
        }
    }

    private suspend fun onStopTimeout(s: Session) {
        if (session !== s || !s.motion.isMoving) return
        guarded("stop timeout") { execute(s, s.motion.onStopTimeout(bestKnownLocation(s))) }
    }

    private suspend fun onMotionTrigger(s: Session) {
        if (session !== s) return
        guarded("motion trigger") { execute(s, s.motion.onMotionTriggerElapsed()) }
    }

    private suspend fun recordMotionChange(s: Session, isMoving: Boolean, location: TrackedLocation?) {
        configStore.updateRuntime { it.copy(isMoving = isMoving) }
        submit(RecordEvent.MOTIONCHANGE, location, isMoving = isMoving)
        s.motionRecorded = true
        if (location != null) {
            s.lastRecorded = location
            s.lastLocationPersistedAt = clock.elapsedRealtime()
        }
    }

    /** After start: a fresh fix (or the last known one) is recorded as the initial `motionchange` (is_moving=false). */
    private fun launchInitialFix(s: Session) {
        s.initialFixJob?.cancel()
        val geolocation = config.geolocation
        s.initialFixJob = scope.launch {
            val fresh = currentLocation(geolocation)
            mutex.withLock {
                // Skipped if tracking stopped, the mode changed, or a transition was already recorded.
                if (session !== s || s.mode != TrackingMode.LOCATION || s.motionRecorded) return@withLock
                guarded("initial motionchange") { recordInitialMotionChange(s, fresh) }
            }
        }
    }

    private suspend fun recordInitialMotionChange(s: Session, fresh: TrackedLocation?) {
        val accepted = fresh?.let { raw ->
            when (val result = processor.process(raw, false)) {
                is FilterResult.Accepted -> result.location
                is FilterResult.Rejected -> {
                    Logger.d(TAG, "initial fix rejected: ${result.reason}")
                    null
                }
            }
        }
        if (accepted != null) {
            s.lastFix = newest(s.lastFix, accepted)
            notifyGeofences(accepted)
        }
        val location = accepted ?: bestKnownLocation(s)
        s.motion.offerAnchor(location)
        recordMotionChange(s, isMoving = false, location)
    }

    private suspend fun currentLocation(geolocation: GeolocationConfig): TrackedLocation? = try {
        withTimeoutOrNull(geolocation.locationTimeout.coerceAtLeast(0) + CURRENT_LOCATION_GRACE_MS) {
            providers.location().getCurrentLocation(geolocation.desiredAccuracy, geolocation.locationTimeout)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "current location failed", e)
        null
    }

    /**
     * The newest of the last accepted fix, `runtime.lastLocation` and the backend's last location. The backend's
     * location (not vetted by the processor, possibly a coarse network fix) only counts if its accuracy is within
     * `filter.trackingAccuracyThreshold`, unless nothing else is known.
     */
    private suspend fun bestKnownLocation(s: Session?): TrackedLocation? {
        val fromBackend = try {
            withTimeoutOrNull(LAST_LOCATION_TIMEOUT_MS) { providers.location().getLastLocation() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "last location failed", e)
            null
        }
        val threshold = config.geolocation.filter.trackingAccuracyThreshold
        val vetted = fromBackend?.takeIf { threshold <= 0.0 || it.accuracyMeters <= threshold }
        return listOfNotNull(s?.lastFix, runtime.lastLocation, vetted).maxByOrNull { it.time } ?: fromBackend
    }

    private suspend fun submit(
        event: RecordEvent,
        location: TrackedLocation?,
        reason: String? = null,
        isMoving: Boolean? = null,
    ): Record? = try {
        var record = recordFactory.create(event, location, reason = reason)
        if (isMoving != null && record.isMoving != isMoving) record = record.copy(isMoving = isMoving)
        recordSink.submit(record)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(TAG, "failed to record ${event.wire}", e)
        null
    }

    private suspend fun <T> serialized(block: suspend () -> T): T =
        withContext(engineContext) { mutex.withLock { block() } }

    /** Runs [block], logging (not propagating) any failure except cancellation. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "$what failed", e)
        }
    }

    /** State of one tracking session in this process. Guarded by [mutex]. */
    private inner class Session(val id: Long, var mode: TrackingMode, settings: MotionSettings) {
        val motion = MotionStateMachine(settings)
        val listener = LocationListener { locations -> dispatchLocations(this@Session, locations) }
        var locationBackend: LocationBackend? = null
        var appliedSpec: LocationRequestSpec? = null
        var activityBackend: ActivityBackend? = null
        var needsContinuousLocation = false

        /** Last fix accepted by the processor. */
        var lastFix: TrackedLocation? = null

        /** Location of the last `location` / `motionchange` record (reference of the elastic distance filter). */
        var lastRecorded: TrackedLocation? = null
        var lastLocationPersistedAt: Long? = null
        var lastActivityType: ActivityType? = null
        var lastActivityPersistedAt: Long? = null
        var motionRecorded = false
        var initialFixJob: Job? = null
        var geofenceWatchJob: Job? = null
        var eventSubscription: Subscription? = null

        /** Last provider state seen through `ProviderChange` events (null until the first event of this session). */
        var providerEnabled: Boolean? = null
        var providerPermission: PermissionLevel? = null
    }

    private companion object {
        const val TAG = "LT.Engine"

        const val REASON_START = "start"
        const val REASON_START_GEOFENCES = "start_geofences"
        const val REASON_RESTORE = "restore"
        const val REASON_STOP = "stop"
        const val REASON_STOP_ON_STATIONARY = "stop_on_stationary"
        const val REASON_STOP_AFTER_ELAPSED = "stop_after_elapsed"
        const val REASON_TERMINATE = "terminate"
        const val REASON_PERMISSION_DENIED = "permission_denied"
        const val REASON_SERVICE_START_FAILED = "service_start_failed"

        /** `device.checkProviderState` reason after the backend changed. */
        const val PROVIDER_CHECK_REASON = "reselect"

        const val MINUTE_MS = 60_000L
        const val LAST_LOCATION_TIMEOUT_MS = 3_000L
        const val CURRENT_LOCATION_GRACE_MS = 5_000L
        const val LAST_LOCATION_PERSIST_INTERVAL_MS = 10_000L
        const val ACTIVITY_PERSIST_INTERVAL_MS = 60_000L

        fun newest(a: TrackedLocation?, b: TrackedLocation): TrackedLocation = if (a == null || b.time >= a.time) b else a
    }
}
