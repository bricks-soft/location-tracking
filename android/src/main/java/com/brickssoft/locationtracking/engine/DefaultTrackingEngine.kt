package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.GeolocationConfig
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.config.State
import com.brickssoft.locationtracking.config.TrackingMode
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.core.ForceStopProbe
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
import com.brickssoft.locationtracking.model.GeofenceAction
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
import com.brickssoft.locationtracking.provider.GeofenceBackend
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.LocationListener
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import com.brickssoft.locationtracking.provider.OsGeofence
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
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
 *
 * Stationary GPS-off mode (architecture round 2, §3). While STATIONARY in the LOCATION mode the engine makes no
 * location request that computes fixes: it registers the OS geofence [StationaryRegion] (id
 * `Constants.STATIONARY_REGION_ID`, EXIT only) around the stationary anchor and requests PASSIVE updates, which only
 * forward fixes that other apps cause. The foreground service and the heartbeat keep running. The heartbeat carries
 * `runtime.lastLocation`, which is the anchor fix while STATIONARY (it changes only when the anchor changes, see
 * [onStationaryFix]), so heartbeats show the anchor with the time it was acquired. STATIONARY ends (GPS on again,
 * the configured request, a `motionchange` with `is_moving: true`) on the first of: the region's EXIT
 * ([onStationaryRegionTransition]), a fix that is certainly outside the stationary radius (see
 * [MotionStateMachine]), a confident moving activity lasting `motionTriggerDelay`, or `changePace(true)`. If the
 * region cannot be registered (no background location permission, too many geofences, a backend error), or no
 * current anchor is known (see [syncStationaryRegion]), a LOW-power request (at most one fix per 3 minutes) replaces
 * the PASSIVE one. While `geofences.needsContinuousLocation` is true the configured request is used, as before.
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
    /** Background triggers do not restore tracking in a process started after a user force stop. */
    private val forceStopProbe: ForceStopProbe = ForceStopProbe.NEVER,
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
        // A paused session must not be resumed after the app stopped tracking (for example on logout).
        guarded("serviceController.cancelResumeNotification") { serviceController.cancelResumeNotification() }
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

    override suspend fun endWithoutRestore(reason: String) = serialized {
        if (session != null || !runtime.enabled) return@serialized
        Logger.i(TAG, "tracking is not resumed after $reason (app.startOnBoot is false)")
        stopLocked(reason)
    }

    /**
     * The `tracking_stop` reason is `permission_denied` when foreground location permission is no longer granted
     * (on Android 14+ the system's START_STICKY restart after a revoked permission fails in `startForeground`),
     * otherwise `service_start_failed`.
     */
    override suspend fun onServiceStartFailed(error: String) = serialized {
        if (session == null && !runtime.enabled) return@serialized
        val reason = if (permissions.hasForegroundLocation()) REASON_SERVICE_START_FAILED else REASON_PERMISSION_DENIED
        Logger.w(TAG, "the foreground service failed to start ($error); stopping tracking with reason $reason")
        stopLocked(reason)
        if (reason == REASON_SERVICE_START_FAILED) offerResume()
    }

    override suspend fun resumeFromNotification() = serialized {
        guarded("serviceController.cancelResumeNotification") { serviceController.cancelResumeNotification() }
        val deadline = elapsedStopAt()
        when {
            session != null -> Logger.i(TAG, "resume notification: tracking already runs")
            runtime.enabled -> restoreLocked(REASON_RESUME_NOTIFICATION)
            !config.notification.resume.enabled -> stopResumeService("notification.resume is disabled")
            deadline != null && deadline <= clock.now() -> stopResumeService("stopAfterElapsedMinutes has passed")
            !permissions.hasForegroundLocation() -> stopResumeService("location permission is not granted")
            else -> {
                Logger.i(TAG, "resume notification: resuming ${runtime.trackingMode.wire} tracking")
                configStore.updateRuntime { it.copy(enabled = true) }
                restoreLocked(REASON_RESUME_NOTIFICATION)
                if (session != null) events.emit(TrackingEvent.EnabledChange(true))
            }
        }
    }

    private fun stopResumeService(why: String) {
        Logger.i(TAG, "resume notification: not resuming ($why)")
        guarded("serviceController.stop") { serviceController.stop() }
    }

    /**
     * After Android refused to restore tracking from the background: posts the resume notification if
     * `notification.resume.enabled`, unless the session's `stopAfterElapsedMinutes` has already passed.
     */
    private fun offerResume() {
        if (!config.notification.resume.enabled) return
        val deadline = elapsedStopAt()
        if (deadline != null && deadline <= clock.now()) {
            Logger.i(TAG, "no resume notification: stopAfterElapsedMinutes has passed")
            return
        }
        guarded("serviceController.showResumeNotification") { serviceController.showResumeNotification(deadline) }
    }

    /** When `stopAfterElapsedMinutes` ends the session started at `trackingStartedAt`; null without a limit. */
    private fun elapsedStopAt(): Long? {
        val minutes = config.geolocation.stopAfterElapsedMinutes
        val startedAt = runtime.trackingStartedAt ?: return null
        return if (minutes > 0) startedAt + minutes * MINUTE_MS else null
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

    // ------------------------------------------------------------------ StationaryRegionSink

    override suspend fun onStationaryRegionTransition(transition: OsGeofenceTransition) = serialized {
        handleStationaryRegionTransition(transition)
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
            // Foreground location is granted (checked above): Android refused the foreground service, for example a
            // start from the background, or Android 14+ with only while-in-use location permission. The error code
            // stays PERMISSION_DENIED for the JS API; the audit record gives the precise reason.
            configStore.updateRuntime { it.copy(enabled = false) }
            if (wasEnabled) releaseOrphanedRegistrations()
            submit(RecordEvent.TRACKING_STOP, bestKnownLocation(null), reason = REASON_SERVICE_START_FAILED)
            throw TrackingException(
                ErrorCode.PERMISSION_DENIED,
                "The location foreground service could not be started (Android refused a start from the background)",
            )
        }
        // A process that died while tracking may have left its stationary region with the OS.
        val s = activate(mode, reason, leftoverRegion = wasEnabled)
        events.emit(TrackingEvent.EnabledChange(true))
        if (mode == TrackingMode.LOCATION) launchInitialFix(s)
    }

    /**
     * Cold process with `runtime.enabled`: restarts everything in the persisted mode, without permission prompts. If the
     * session's `stopAfterElapsedMinutes` deadline has passed, it records that stop instead and starts nothing.
     */
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
                    offerResume()
                }
            }
            return
        }
        // The session ended while no process ran its timer (the phone was off, or the app was killed or updated).
        val deadline = elapsedStopAt()
        if (deadline != null && deadline <= clock.now()) {
            Logger.i(TAG, "restore($reason): stopAfterElapsedMinutes passed while tracking was not running; stopping")
            stopLocked(REASON_STOP_AFTER_ELAPSED)
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
        // The system may have restarted the service itself (START_STICKY), which then calls restore(): starting it again
        // from the background could be refused on Android 12+ although it is already in the foreground.
        if (!serviceController.isRunning && !startService()) {
            // Without the foreground service Android throttles background location and the heartbeat alarms would
            // keep retrying; end the session with an explicit audit record instead.
            Logger.w(TAG, "restore($reason): the foreground service was refused; stopping tracking")
            stopLocked(REASON_SERVICE_START_FAILED)
            offerResume()
            return
        }
        // GMS and HMS keep geofences across a process death: the stationary region of the dead process may remain.
        val s = activate(mode, reason, leftoverRegion = true)
        if (mode == TrackingMode.LOCATION) launchInitialFix(s)
    }

    /**
     * Starts a new session: components, backends, location request, then the `tracking_start` record.
     * [leftoverRegion]: an earlier process may have left the stationary region registered with the OS.
     */
    private suspend fun activate(mode: TrackingMode, reason: String, leftoverRegion: Boolean): Session {
        val s = Session(nextSessionId++, mode, MotionSettings.from(config))
        s.leftoverRegion = leftoverRegion
        session = s
        guarded("processor.reset") { processor.reset() }
        guarded("device.start") { device.start() }
        guarded("syncer.start") { syncer.start() }
        guarded("heartbeat.start") { heartbeat.start() }
        guarded("geofences.onTrackingStarted") { geofences.onTrackingStarted(mode) }
        configureMode(s)
        watchGeofenceDemand(s)
        s.eventSubscription = events.subscribe { event -> onEvent(s, event) }
        guarded("serviceController.cancelResumeNotification") { serviceController.cancelResumeNotification() }
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

    /**
     * Resets the motion state to STATIONARY (without an anchor until the initial fix) and applies the mode's activity
     * updates, stationary region, request and timers.
     */
    private suspend fun configureMode(s: Session) {
        timers.cancel(TimerKind.STOP_TIMEOUT)
        timers.cancel(TimerKind.MOTION_TRIGGER)
        s.motion.reset()
        s.motionRecorded = false
        s.lastRecorded = null
        s.lastActivityType = null
        if (s.mode == TrackingMode.LOCATION) startActivityUpdates(s) else stopActivityUpdates(s)
        s.needsContinuousLocation = geofences.needsContinuousLocation.value
        applyMotionRequests(s)
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
        // After the record, so a slow OS call cannot delay it. Without a session (cold process): GMS and HMS keep
        // geofences across a process death, so the previous process may have left the region.
        if (s != null) removeStationaryRegion(s) else removeStationaryRegionFrom(null)
        guarded("serviceController.stop") { serviceController.stop() }
        guarded("heartbeat.stop") { heartbeat.stop() }
        guarded("geofences.onTrackingStopped") { geofences.onTrackingStopped() }
        configStore.updateRuntime { it.copy(enabled = false, isMoving = false) }
        events.emit(TrackingEvent.EnabledChange(false))
        Logger.i(TAG, "tracking stopped: reason=$reason")
    }

    /** A refused start in a cold process: drop the previous process's activity, alarm and geofence registrations. */
    /**
     * A background trigger ([trigger]) woke a process in which tracking is enabled but not running. Restores tracking,
     * except in a process that started after a user force stop: then it releases the leftover OS registrations (so they
     * stop waking the app) and leaves `enabled` set, so opening the app (`ready()`) restores tracking. Returns whether
     * a session runs afterwards.
     */
    private suspend fun restoreFromBackgroundTrigger(trigger: String): Boolean {
        if (forceStopProbe.startedAfterForceStop()) {
            Logger.i(TAG, "$trigger after a force stop: tracking stays off until the app is opened")
            releaseOrphanedRegistrations()
            return false
        }
        Logger.i(TAG, "$trigger in a process where tracking is enabled but not running; restoring")
        restoreLocked(REASON_RESTORE)
        return session != null
    }

    private suspend fun releaseOrphanedRegistrations() {
        stopActivityBackend()
        removeStationaryRegionFrom(null)
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
        if (old.notification.resume.enabled && !new.notification.resume.enabled) {
            guarded("serviceController.cancelResumeNotification") { serviceController.cancelResumeNotification() }
        }
        if (s == null) return
        if (!providerChanged && activitySettingsChanged(old, new)) {
            stopActivityUpdates(s)
            startActivityUpdates(s)
        }
        execute(s, s.motion.updateSettings(MotionSettings.from(new)))
        if (session !== s) return
        applyMotionRequests(s)
        if (old.geolocation.stopAfterElapsedMinutes != new.geolocation.stopAfterElapsedMinutes) scheduleElapsedStop(s)
        if (old.notification != new.notification) {
            guarded("serviceController.refreshNotification") { serviceController.refreshNotification() }
        }
    }

    private fun activitySettingsChanged(old: Config, new: Config): Boolean =
        old.activity.disableMotionActivityUpdates != new.activity.disableMotionActivityUpdates ||
            old.activity.activityRecognitionInterval != new.activity.activityRecognitionInterval

    /**
     * `locationProvider` changed: re-selects the backend. While tracking, listeners, activity updates, the
     * stationary region and geofences are removed from the current backend first and registered again on the
     * selected one.
     */
    private suspend fun switchProvider(s: Session?, old: Config, new: Config) {
        if (s != null) {
            removeLocationUpdates(s)
            stopActivityUpdates(s)
            removeStationaryRegion(s)
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
            // The region is registered on the selected backend, also after a failure on the previous one.
            applyMotionRequests(s, force = true)
        }
        if (changed) guarded("device.checkProviderState") { device.checkProviderState(PROVIDER_CHECK_REASON) }
    }

    // ------------------------------------------------------------------ backends

    /**
     * Brings the location request and the stationary region in line with the motion state. When the configured
     * (MOVING) request is wanted it is applied first, so GPS is on before the OS call that removes the region.
     * [force] and [checkLimit]: see [syncStationaryRegion].
     */
    private suspend fun applyMotionRequests(s: Session, force: Boolean = false, checkLimit: Boolean = false) {
        if (!isStationary(s)) applyLocationRequest(s)
        syncStationaryRegion(s, force, checkLimit)
        applyLocationRequest(s)
    }

    /** STATIONARY in the LOCATION mode: the state in which the stationary region is wanted. */
    private fun isStationary(s: Session): Boolean =
        session === s && s.mode == TrackingMode.LOCATION && !s.motion.isMoving

    private fun applyLocationRequest(s: Session) {
        val spec = LocationRequests.desired(
            s.mode,
            s.motion.isMoving,
            s.needsContinuousLocation,
            config.geolocation,
            // PASSIVE while the OS watches the region, and while the initial fix (its own request) is pending.
            passive = s.region != null || !s.motionRecorded,
        )
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

    /**
     * Registers the stationary region while the session is STATIONARY in the LOCATION mode, and removes it otherwise.
     *
     * The region is centred on the motion anchor and is registered only when:
     * - the initial `motionchange` after start / restore has been recorded (so the fresh initial fix is the centre);
     * - the anchor was current when it became the anchor (see [isAnchorConfirmed]): with `initialTriggerEntry =
     *   false` the OS never reports EXIT for a device that is already outside, so a region around an old fix could
     *   leave the device STATIONARY with GPS off after it moved away;
     * - it is not up to date already: registered on the current backend with the configured radius and a centre at
     *   most [REGION_RECENTER_M] from the anchor. [force] registers it again anyway (location services came back,
     *   and GMS / HMS may have dropped it);
     * - no earlier registration of this stationary period failed (not retried until [force] or leaving STATIONARY);
     * - the user geofences leave the OS a free slot (see [userGeofenceSlotsFull]; checked before registering and,
     *   with [checkLimit], for a registered region, which is then removed).
     * Otherwise STATIONARY uses the low-power fallback request. Never throws, except cancellation.
     */
    private suspend fun syncStationaryRegion(s: Session, force: Boolean = false, checkLimit: Boolean = false) {
        if (!isStationary(s)) {
            removeStationaryRegion(s)
            s.regionFailed = false
            return
        }
        if (!s.motionRecorded) return
        val anchor = s.motion.anchor ?: return
        if (checkLimit && s.region != null && userGeofenceSlotsFull()) {
            Logger.w(TAG, "the user geofences need every OS geofence slot: removing the stationary region")
            removeStationaryRegion(s)
            return
        }
        val backend = try {
            providers.geofence()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "no geofence backend for the stationary region; using the low-power request instead", e)
            s.region = null
            s.regionFailed = true
            return
        }
        val radius = StationaryRegion.radius(config.geolocation.stationaryRadius)
        val registered = s.region
        val upToDate = registered != null &&
            registered.radius == radius &&
            s.regionBackend?.kind == backend.kind &&
            distanceMeters(registered.latitude, registered.longitude, anchor.latitude, anchor.longitude) <=
            REGION_RECENTER_M
        if (upToDate && !force) return
        if (s.regionFailed && !force) return
        if (!isAnchorConfirmed(s, anchor)) {
            Logger.i(TAG, "stationary anchor is ${ageMs(anchor) / 1000} s old: low-power requests until a current fix")
            return
        }
        if (userGeofenceSlotsFull()) {
            Logger.w(TAG, "the user geofences need every OS geofence slot: no stationary region, low-power requests")
            removeStationaryRegion(s)
            return
        }
        if (s.regionBackend?.let { it.kind != backend.kind } == true) removeStationaryRegion(s)
        registerStationaryRegion(s, backend, StationaryRegion.around(anchor, config.geolocation.stationaryRadius))
    }

    /**
     * True if [anchor] was current (at most [ANCHOR_MAX_AGE_MS] old) when it became the anchor. The device is
     * believed not to have moved since, so a confirmed anchor stays confirmed for the whole stationary period.
     */
    private fun isAnchorConfirmed(s: Session, anchor: TrackedLocation): Boolean {
        if (anchor === s.confirmedAnchor) return true
        if (!isCurrent(anchor)) return false
        s.confirmedAnchor = anchor
        return true
    }

    /** At most [ANCHOR_MAX_AGE_MS] old by the wall clock (a fix from the future counts as current). */
    private fun isCurrent(fix: TrackedLocation): Boolean = ageMs(fix) <= ANCHOR_MAX_AGE_MS

    private fun ageMs(fix: TrackedLocation): Long = clock.now() - fix.time

    /**
     * True when the stored user geofences need every OS slot but one ([Constants.MAX_GEOFENCES] - 1 or more): GMS
     * accepts at most 100 geofences per app, and the stationary region must not make the user's last `add` fail.
     */
    private suspend fun userGeofenceSlotsFull(): Boolean = try {
        geofences.list().size >= Constants.MAX_GEOFENCES - 1
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "cannot count the user geofences", e)
        false
    }

    /** Adds [region] to [backend] (adding the same id replaces it); on failure the low-power fallback applies. */
    private suspend fun registerStationaryRegion(s: Session, backend: GeofenceBackend, region: OsGeofence) {
        // Remembered before the call: after a timeout the OS may still complete the registration.
        s.regionBackend = backend
        s.leftoverRegion = false // the same id: a region left by an earlier process is replaced
        val failure: Exception? = try {
            val done = withTimeoutOrNull(OS_GEOFENCE_TIMEOUT_MS) { backend.add(listOf(region)) }
            if (done != null) {
                null
            } else {
                TrackingException(ErrorCode.TIMEOUT, "no answer within $OS_GEOFENCE_TIMEOUT_MS ms")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        if (failure == null) {
            s.region = region
            s.regionFailed = false
            Logger.i(
                TAG,
                "stationary region registered on ${backend.kind.wire}: radius ${region.radius} m around " +
                    "(${region.latitude}, ${region.longitude}); GPS off, passive location updates",
            )
        } else {
            s.region = null
            s.regionFailed = true
            Logger.w(
                TAG,
                "stationary region could not be registered on ${backend.kind.wire} (${failure.message}); " +
                    "using a low-power request (at most one fix per 3 minutes) instead of passive updates",
                failure,
            )
        }
    }

    /**
     * Removes the stationary region this session registered (or tried to register), or one an earlier process may
     * have left ([Session.leftoverRegion]), from the OS.
     */
    private suspend fun removeStationaryRegion(s: Session) {
        val backend = s.regionBackend
        if (backend == null && !s.leftoverRegion) return
        s.regionBackend = null
        s.region = null
        s.leftoverRegion = false
        removeStationaryRegionFrom(backend)
    }

    /**
     * Removes [Constants.STATIONARY_REGION_ID] from [backend] (null: the current backend). Removing an id that is not
     * registered is harmless. Failures are logged, never thrown (except cancellation).
     */
    private suspend fun removeStationaryRegionFrom(backend: GeofenceBackend?) {
        try {
            val target = backend ?: providers.geofence()
            val done = withTimeoutOrNull(OS_GEOFENCE_TIMEOUT_MS) {
                target.remove(listOf(Constants.STATIONARY_REGION_ID))
            }
            if (done == null) {
                Logger.w(TAG, "removing the stationary region got no answer within $OS_GEOFENCE_TIMEOUT_MS ms")
            } else {
                Logger.d(TAG, "stationary region removed from ${target.kind.wire}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "failed to remove the stationary region", e)
        }
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
            // The user geofences may now need the OS slot of the stationary region, or leave it free again.
            is TrackingEvent.GeofencesChange -> scope.launch {
                mutex.withLock { if (session === s) applyMotionRequests(s, checkLimit = true) }
            }
            else -> Unit
        }
    }

    /**
     * GMS and HMS drop every registered geofence when location services are switched off, and geofences registered
     * without background permission may not fire. Registering all stored geofences again when location comes back
     * (or the permission level changes) keeps geofence audit records flowing. Re-adding the same ids is idempotent.
     *
     * The stationary region is registered again on every provider change while location is enabled (any change of
     * the location settings or of the backend may have made the OS drop it). A registration that failed before (for
     * example without background location permission) is tried again; on success the low-power fallback request
     * becomes the passive one.
     */
    private suspend fun onProviderChange(s: Session, state: ProviderState) {
        if (session !== s) return
        val wasEnabled = s.providerEnabled
        val oldPermission = s.providerPermission
        s.providerEnabled = state.enabled
        s.providerPermission = state.permission
        if (!state.enabled) return
        if (wasEnabled != true || oldPermission != state.permission) {
            Logger.i(TAG, "location provider available again (${state.permission.wire}); re-registering geofences")
            guarded("geofences.onTrackingStarted") { geofences.onTrackingStarted(s.mode) }
        }
        if (isStationary(s)) applyMotionRequests(s, force = true)
    }

    private suspend fun handleFix(s: Session, raw: TrackedLocation) {
        val fix = acceptedOrNull(raw, isMoving = s.motion.isMoving, what = "fix") ?: return
        s.lastFix = fix
        if (s.mode == TrackingMode.GEOFENCES) {
            guarded("odometer") { odometer.onLocation(fix) }
            notifyGeofences(fix)
            persistLastLocation(s, fix)
            return
        }
        val wasMoving = s.motion.isMoving
        val anchorBefore = s.motion.anchor
        val actions = s.motion.onLocation(fix)
        if (!wasMoving && !s.motion.isMoving) {
            // STATIONARY: no record, no odometer; `runtime.lastLocation` changes only with the anchor.
            notifyGeofences(fix)
            onStationaryFix(s, fix, anchorBefore)
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

    /** The processed [raw] fix, or null (logged) if the processor rejects it. */
    private fun acceptedOrNull(raw: TrackedLocation, isMoving: Boolean, what: String): TrackedLocation? =
        when (val result = processor.process(raw, isMoving)) {
            is FilterResult.Accepted -> result.location
            is FilterResult.Rejected -> {
                Logger.d(TAG, "$what rejected: ${result.reason}")
                null
            }
        }

    /**
     * A STATIONARY fix that did not leave STATIONARY. It changes the anchor when:
     * - there was none (nothing was known when STATIONARY began): the fix becomes the anchor;
     * - the anchor is not confirmed (it was more than [ANCHOR_MAX_AGE_MS] old when it became the anchor) and the fix
     *   is current and accurate enough: the fix replaces it;
     * - the motion state machine replaced the anchor with this more accurate fix.
     * A new anchor becomes `runtime.lastLocation` (the location of the heartbeats) and the stationary region follows
     * it (registered around a new confirmed anchor, moved only when the centre is more than [REGION_RECENTER_M]
     * away). Otherwise `runtime.lastLocation` keeps the anchor fix with its acquisition time.
     */
    private suspend fun onStationaryFix(s: Session, fix: TrackedLocation, anchorBefore: TrackedLocation?) {
        var anchor = s.motion.anchor ?: return
        val replacesOldAnchor = anchor === anchorBefore && !isAnchorConfirmed(s, anchor) && isCurrent(fix) &&
            s.motion.isAccurateEnough(fix)
        if (replacesOldAnchor) {
            Logger.i(TAG, "a current fix replaces the stationary anchor, which was ${ageMs(anchor) / 1000} s old")
            s.motion.setAnchor(fix)
            anchor = fix
        }
        if (anchor === anchorBefore) return
        configStore.updateRuntime { it.copy(lastLocation = anchor) }
        applyMotionRequests(s)
    }

    /**
     * An OS transition of the stationary region. Only EXIT matters: while STATIONARY it switches to MOVING (the
     * transition's fix, when the processor accepts it, is the `motionchange` location and goes to the odometer and
     * the geofences like the fix that leaves the stationary radius). An EXIT whose fix is certainly inside the
     * registered region belongs to an earlier region (delivered late) and is ignored. In a process where tracking
     * is enabled but not running (the geofence PendingIntent started it) tracking is restored first. A region
     * reported while tracking is off, or while the session is not STATIONARY, is removed from the OS.
     */
    private suspend fun handleStationaryRegionTransition(transition: OsGeofenceTransition) {
        if (transition.action != GeofenceAction.EXIT) {
            Logger.d(TAG, "stationary region ${transition.action} ignored")
            return
        }
        if (session == null) {
            if (!runtime.enabled) {
                Logger.i(TAG, "stationary region EXIT while tracking is off; removing the region")
                removeStationaryRegionFrom(null)
                return
            }
            if (!restoreFromBackgroundTrigger("stationary region EXIT")) return
        }
        val s = session ?: return
        // The OS has the region: this session removes it when it leaves STATIONARY or stops.
        if (s.regionBackend == null) s.leftoverRegion = true
        if (!isStationary(s)) {
            Logger.d(TAG, "stationary region EXIT ignored: the session is not STATIONARY")
            removeStationaryRegion(s)
            return
        }
        val region = s.region
        val reported = transition.location
        if (region != null && reported != null &&
            distanceMeters(region.latitude, region.longitude, reported.latitude, reported.longitude) +
            reported.accuracyMeters <= region.radius
        ) {
            Logger.i(TAG, "stationary region EXIT ignored: its fix is inside the registered region (a late EXIT)")
            return
        }
        Logger.i(TAG, "stationary region EXIT: leaving STATIONARY")
        val fix = reported?.let { acceptedOrNull(it, isMoving = false, what = "stationary region EXIT fix") }
        if (fix != null) {
            s.lastFix = newest(s.lastFix, fix)
            guarded("odometer") { odometer.onLocation(fix) }
            notifyGeofences(fix)
        }
        execute(s, s.motion.force(true, fix ?: bestKnownLocation(s)))
        fireDueTimers(s)
    }

    /**
     * Keeps `runtime.lastLocation` (used by heartbeats) fresh for MOVING and GEOFENCES-mode fixes that are not
     * recorded, at most every 10 s. STATIONARY fixes never call it: the heartbeats keep the anchor fix.
     */
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
            if (!restoreFromBackgroundTrigger("activity update")) return
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
                    // The configured request (GPS on) at once; the stationary region is removed after the record,
                    // so a slow OS call delays neither.
                    applyLocationRequest(s)
                    recordMotionChange(s, isMoving = true, location)
                    applyMotionRequests(s)
                }
                is MotionAction.EnterStationary -> {
                    Logger.i(TAG, "motion: MOVING -> STATIONARY${if (action.automatic) " (stop timeout)" else ""}")
                    recordMotionChange(s, isMoving = false, action.location)
                    if (action.automatic && config.geolocation.stopOnStationary) {
                        stopLocked(REASON_STOP_ON_STATIONARY)
                    } else {
                        // The stationary region around the anchor and passive updates (GPS off).
                        applyMotionRequests(s)
                    }
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
        val accepted = fresh?.let { acceptedOrNull(it, isMoving = false, what = "initial fix") }
        if (accepted != null) {
            s.lastFix = newest(s.lastFix, accepted)
            notifyGeofences(accepted)
            // The fresh fix is the anchor, also when a passive fix arrived first and became the anchor.
            s.motion.setAnchor(accepted)
        }
        val location = accepted ?: bestKnownLocation(s)
        s.motion.offerAnchor(location)
        recordMotionChange(s, isMoving = false, location)
        if (s.motion.anchor == null) {
            Logger.i(TAG, "no location is known: low-power requests until a first fix becomes the stationary anchor")
        }
        // Entering STATIONARY after start / restore: the stationary region around the anchor, passive updates.
        applyMotionRequests(s)
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

        /** The stationary region registered with the OS (null: none; STATIONARY then uses the low-power request). */
        var region: OsGeofence? = null

        /** The backend the stationary region was added to (also after a failed or timed-out add), for its removal. */
        var regionBackend: GeofenceBackend? = null

        /** The last registration of the stationary region failed; not retried until this is cleared. */
        var regionFailed = false

        /** An earlier process may have left the stationary region with the OS; removed with this session's. */
        var leftoverRegion = false

        /** The motion anchor that was current when it became the anchor (see `isAnchorConfirmed`). */
        var confirmedAnchor: TrackedLocation? = null

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
        const val REASON_RESUME_NOTIFICATION = "resume_notification"

        /** `device.checkProviderState` reason after the backend changed. */
        const val PROVIDER_CHECK_REASON = "reselect"

        const val MINUTE_MS = 60_000L
        const val LAST_LOCATION_TIMEOUT_MS = 3_000L
        const val CURRENT_LOCATION_GRACE_MS = 5_000L
        const val LAST_LOCATION_PERSIST_INTERVAL_MS = 10_000L
        const val ACTIVITY_PERSIST_INTERVAL_MS = 60_000L

        /** Longest wait for the OS to add or remove the stationary region (the engine's mutex is held meanwhile). */
        const val OS_GEOFENCE_TIMEOUT_MS = 10_000L

        /**
         * An anchor older than this when it becomes the anchor is not confirmed: no stationary region is registered
         * around it until a current fix replaces it (the low-power request provides one).
         */
        const val ANCHOR_MAX_AGE_MS = 10 * MINUTE_MS

        /** The stationary region is moved when the anchor is farther than this from its centre. */
        const val REGION_RECENTER_M = 50.0

        fun newest(a: TrackedLocation?, b: TrackedLocation): TrackedLocation = if (a == null || b.time >= a.time) b else a
    }
}
