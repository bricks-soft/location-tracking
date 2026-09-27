package com.brickssoft.locationtracking.heartbeat

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.data.LocationStore
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.model.HeartbeatMeta
import com.brickssoft.locationtracking.model.HeartbeatStatus
import com.brickssoft.locationtracking.model.HeartbeatStrategy
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.record.RecordSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Alarm-based [HeartbeatScheduler] (architecture §3 "Heartbeat algorithm"; round 2: docs/e2e/architecture.md §5).
 *
 * While tracking is enabled (`runtime.enabled`, either mode) and `heartbeat.enabled`, one window is kept armed.
 * If no record was created for `heartbeat.minInterval`, a `heartbeat` record carrying the last known location
 * is submitted through the [RecordSink]. The sink persists it, restarts the window ([onRecordRecorded]), passes it to
 * the record hooks (the native listeners of companion plugins), emits the Heartbeat event and uploads it immediately
 * (priority record; if the upload fails it stays queued).
 *
 * Alarms always use `ELAPSED_REALTIME_WAKEUP`:
 * - [HeartbeatStrategy.EXACT] when `canScheduleExactAlarms()` (battery-exempt): one exact allow-while-idle
 *   PendingIntent alarm. A [SecurityException] falls back to the next strategy.
 * - Otherwise an in-process exact listener alarm plus an inexact allow-while-idle PendingIntent backup
 *   ([HeartbeatStrategy.LISTENER_WITH_BACKUP]); in deep idle the backup is paced at least 9 min after the
 *   previous backup fire ([HeartbeatStrategy.IDLE_PACED]).
 *
 * Alarms fire into [onAlarm] (listener: here; PendingIntent: [HeartbeatAlarmReceiver]), which is serialized, so
 * a listener and a backup firing for the same window produce one heartbeat.
 *
 * **Timestamps.** A heartbeat's `recorded_at` is the time it is created. Its location is the last known fix, unchanged,
 * so its `timestamp` is the time that fix was acquired: a phone that has been stationary for an hour sends heartbeats
 * with new `recorded_at` values and the same hour-old `timestamp`.
 *
 * **Metadata.** Every heartbeat record carries a [HeartbeatMeta] (`record.heartbeat`). The next window is armed before
 * the record is submitted, so the metadata describes exactly what AlarmManager holds: the strategy, the config
 * intervals, `nextAt` (when the next heartbeat is due if no other record is created) and the battery-exemption and
 * deep-idle flags. [status] reports the same values at the same moment.
 *
 * **Cost.**
 * - AlarmManager is called only when [HeartbeatWindow.needsRearm] says so: a changed strategy, a trigger that moved
 *   earlier, a due time that moved later by at least [HeartbeatWindow.REARM_THRESHOLD_MS], or any move of an
 *   idle-paced backup. A record every 5 s therefore sets the alarms once per 30 s instead of once per record. An alarm
 *   left in place fires up to 30 s early; [onAlarm] then finds the window not due, creates no heartbeat and arms the
 *   real due time.
 * - One partial wake lock per alarm delivery. It is released when the heartbeat has been submitted (the upload holds
 *   its own wake lock) and times out after [WAKE_LOCK_TIMEOUT_MS] if something hangs.
 * - The backend's last known location is requested only when `runtime.lastLocation` is null, and not again within
 *   [LAST_LOCATION_RETRY_MS] after the backend answered that it has none (a timeout or an error is retried at the
 *   next heartbeat).
 * - The provider state check runs once per created heartbeat, as before. It is the only check that sees permission
 *   changes that neither kill the process nor send a broadcast; early alarms and superseded alarms skip it.
 *
 * @param alarms AlarmManager access; injectable for tests.
 */
class DefaultHeartbeatScheduler(
    context: Context,
    private val configStore: ConfigStore,
    private val device: DeviceMonitor,
    private val providers: ProviderFactory,
    private val locationStore: LocationStore,
    private val recordFactory: RecordFactory,
    private val recordSink: Lazy<RecordSink>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val alarms: HeartbeatAlarms = SystemHeartbeatAlarms(context),
) : HeartbeatScheduler {
    private enum class Lifecycle {
        /** Fresh process: never started. An alarm may still arrive from a previous process. */
        NEW,
        STARTED,
        STOPPED,
    }

    private val appContext: Context = context.applicationContext ?: context
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val powerManager: PowerManager? by lazy { appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** Guards every field below and every AlarmManager call. */
    private val lock = Any()
    private var lifecycle = Lifecycle.NEW

    /** The window whose trigger times AlarmManager holds (after a refused exact alarm: the fallback), or null. */
    private var armed: HeartbeatWindow? = null

    /**
     * The window the heartbeat follows now: the last evaluated window, with the strategy that is armed. It differs
     * from [armed] when a record moved the due time by less than [HeartbeatWindow.REARM_THRESHOLD_MS] and the alarms
     * were left in place. [status] reports it.
     */
    private var current: HeartbeatWindow? = null

    /** True if the last arm asked for an exact alarm and AlarmManager refused it. The next re-arm asks again. */
    private var exactRefused = false

    /** Incremented on every arm and cancel; lets [onAlarm] tell whether anything re-armed since the alarm. */
    private var generation = 0L

    /** True once the alarms were cancelled and nothing was armed since. */
    private var alarmsCleared = false

    /** Window base while no record exists yet, so an alarm can find the window due. */
    private var noRecordAnchorElapsed: Long? = null

    /** Last heartbeat attempt: the next one is not due before one min interval later, even if this one failed. */
    private var lastAttemptElapsed: Long? = null

    /** Elapsed time of the last backend last-location request that returned no location, or null. */
    private var lastLocationMissElapsed: Long? = null

    /** In-memory copy of the persisted last backup fire (elapsed, boot count); loaded lazily. */
    private var lastBackupFire: Pair<Long, Int>? = null
    private var lastBackupFireLoaded = false
    private var configJob: Job? = null
    private var idleReceiverRegistered = false

    /** Serializes alarm handling: the listener and the backup alarm may fire for the same window. */
    private val alarmMutex = Mutex()

    private val listener = AlarmManager.OnAlarmListener {
        // The alarm's own wake lock ends when this returns: hold ours across the hop to the coroutine and through the
        // handling (the handling takes no second wake lock).
        val wakeLock = acquireWakeLock()
        scope.launch { runAlarm(HeartbeatTrigger.LISTENER_ALARM) }.invokeOnCompletion { release(wakeLock) }
    }

    private val idleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Logger.d(TAG, "device idle mode changed")
            reschedule("idle-change", force = false)
        }
    }

    override fun start() {
        synchronized(lock) {
            if (lifecycle != Lifecycle.STARTED) {
                lifecycle = Lifecycle.STARTED
                registerIdleReceiverLocked()
                observeConfigLocked()
            }
            rescheduleLocked("start", force = false, fromAlarm = false)
        }
    }

    override fun stop() {
        synchronized(lock) {
            lifecycle = Lifecycle.STOPPED
            configJob?.cancel()
            configJob = null
            unregisterIdleReceiverLocked()
            cancelAlarmsLocked()
            noRecordAnchorElapsed = null
            lastAttemptElapsed = null
            lastLocationMissElapsed = null
            clearPersistedSchedule()
        }
        Logger.d(TAG, "stopped")
    }

    override fun onRecordRecorded(record: Record) {
        synchronized(lock) {
            noRecordAnchorElapsed = null
            rescheduleLocked("record:${record.event.wire}", force = false, fromAlarm = false)
        }
    }

    /** Handles an alarm while holding a partial wake lock (the receiver path; the listener holds its own). */
    override suspend fun onAlarm(trigger: HeartbeatTrigger) {
        val wakeLock = acquireWakeLock()
        try {
            runAlarm(trigger)
        } finally {
            release(wakeLock)
        }
    }

    override suspend fun status(): HeartbeatStatus {
        val heartbeat = configStore.config.value.heartbeat
        val runtime = configStore.runtime.value
        val (strategy, next) = synchronized(lock) { scheduleSnapshotLocked() }
        val pending = try {
            locationStore.count(setOf(RecordEvent.HEARTBEAT))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "cannot count pending heartbeats", e)
            0
        }
        return HeartbeatStatus(
            enabled = heartbeat.enabled,
            minInterval = heartbeat.minInterval,
            maxInterval = heartbeat.maxInterval,
            lastRecordAt = runtime.lastRecordAt,
            lastHeartbeatAt = runtime.lastHeartbeatAt,
            nextHeartbeatAt = next,
            strategy = strategy,
            canScheduleExactAlarms = device.canScheduleExactAlarms(),
            isIgnoringBatteryOptimizations = device.isIgnoringBatteryOptimizations(),
            isDeviceIdleMode = device.isDeviceIdleMode(),
            isPowerSaveMode = device.isPowerSaveMode(),
            pendingHeartbeats = pending,
        )
    }

    // ---- alarm handling

    private suspend fun runAlarm(trigger: HeartbeatTrigger) {
        alarmMutex.withLock { handleAlarm(trigger) }
    }

    private suspend fun handleAlarm(trigger: HeartbeatTrigger) {
        val nowElapsed = clock.elapsedRealtime()
        // Every backup delivery counts toward the OS allow-while-idle quota, whatever happens next.
        if (trigger == HeartbeatTrigger.BACKUP_ALARM) persistBackupFire(nowElapsed, clock.bootCount())

        var entryGeneration = 0L
        val window = synchronized(lock) {
            entryGeneration = generation
            if (lifecycle == Lifecycle.STOPPED || !isActive()) {
                Logger.d(TAG, "$trigger ignored: heartbeat or tracking disabled")
                cancelAlarmsLocked()
                clearPersistedSchedule()
                null
            } else {
                computeWindowLocked(device.canScheduleExactAlarms(), anchor = true)
            }
        } ?: return

        if (!window.shouldFire(nowElapsed)) {
            // Expected after records moved the due time by less than the re-arm threshold.
            Logger.d(TAG, "$trigger: not due for ${(window.dueElapsed - nowElapsed) / 1_000} s; re-arming")
            // The alarm that fired is consumed: re-arm even if the window did not change.
            reschedule("early-$trigger", force = true, fromAlarm = true)
            return
        }
        try {
            createHeartbeat(trigger, window, nowElapsed)
        } finally {
            synchronized(lock) {
                // createHeartbeat arms the next window before it submits, and a record or stop() in the meantime
                // re-armed or cancelled; re-arm here only if nothing did since the alarm (no heartbeat was created).
                if (generation == entryGeneration) rescheduleLocked("after-$trigger", force = true, fromAlarm = true)
            }
        }
    }

    private suspend fun createHeartbeat(trigger: HeartbeatTrigger, window: HeartbeatWindow, nowElapsed: Long) {
        val lateMs = nowElapsed - window.deadlineElapsed
        if (lateMs > HeartbeatWindow.FIRE_TOLERANCE_MS) {
            // Expected while idle-paced (documented limitation); anything else is worth a warning.
            val level = if (window.strategy == HeartbeatStrategy.IDLE_PACED) LogLevel.INFO else LogLevel.WARN
            Logger.log(level, TAG, "heartbeat is ${lateMs / 1_000} s past maxInterval ($trigger, ${window.strategy.wire})")
        }
        try {
            if (withTimeoutOrNull(PROVIDER_CHECK_TIMEOUT_MS) { device.checkProviderState("heartbeat") } == null) {
                Logger.w(TAG, "provider state check timed out")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "provider state check failed", e)
        }
        try {
            val location = heartbeatLocation(trigger)
            // The calls above suspend: a record (e.g. providerchange) may have restarted the window, or stop()
            // may have run. Decide again. Only the due time matters here, so the device flags of the entry are reused.
            var seenLastRecord: Pair<Long?, Long?>? = null
            val stillDue = synchronized(lock) {
                val nowAgain = clock.elapsedRealtime()
                val due = lifecycle != Lifecycle.STOPPED && isActive() &&
                    computeWindowLocked(
                        canExact = window.strategy == HeartbeatStrategy.EXACT,
                        anchor = true,
                        isIdle = window.strategy == HeartbeatStrategy.IDLE_PACED,
                    ).shouldFire(nowAgain)
                if (due) {
                    lastAttemptElapsed = nowAgain
                    seenLastRecord = lastRecordMarker()
                }
                due
            }
            if (!stillDue) {
                Logger.d(TAG, "$trigger: superseded by a newer record or stop()")
                return
            }
            val created = recordFactory.create(RecordEvent.HEARTBEAT, location)
            // Another thread may have submitted a record, or stopped tracking, while the record was being built.
            val record = synchronized(lock) {
                val superseded = lifecycle == Lifecycle.STOPPED || !isActive() || lastRecordMarker() != seenLastRecord
                if (superseded) null else created.copy(heartbeat = armAfterLocked(created))
            }
            if (record == null) {
                Logger.d(TAG, "$trigger: superseded by a newer record or stop() while the heartbeat was built")
                return
            }
            recordSink.value.submit(record)
            val meta = record.heartbeat
            Logger.i(
                TAG,
                "heartbeat ${record.uuid} ($trigger, next ${meta?.strategy?.wire} in " +
                    "${meta?.nextAt?.let { (it - record.recordedAt) / 1_000 }} s, location=${location != null})",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "failed to create heartbeat ($trigger)", e)
        }
    }

    /**
     * Arms the window that starts at [record] (the heartbeat about to be submitted) and returns the record's
     * metadata. Arming first makes the metadata describe what AlarmManager holds (also after a refused exact alarm);
     * the sink's [onRecordRecorded] then computes the same window and sets no alarm.
     */
    private fun armAfterLocked(record: Record): HeartbeatMeta {
        val isIdle = device.isDeviceIdleMode()
        val next = computeWindowLocked(device.canScheduleExactAlarms(), anchor = true, isIdle = isIdle, lastRecord = record)
        val armedWindow = armLocked(next, "heartbeat", isIdle = isIdle, lastRecord = record)
        val config = configStore.config.value.heartbeat
        val inMs = (armedWindow.expectedFireElapsed - record.elapsedRealtimeMs).coerceAtLeast(0L)
        return HeartbeatMeta(
            strategy = armedWindow.strategy,
            minInterval = config.minInterval,
            maxInterval = config.maxInterval,
            nextAt = record.recordedAt + inMs,
            batteryExempt = device.isIgnoringBatteryOptimizations(),
            deviceIdle = isIdle,
        )
    }

    /**
     * The heartbeat's location: `runtime.lastLocation` (kept fresh by the engine), else the backend's last known
     * location. After the backend answered that it has no location, it is not asked again within
     * [LAST_LOCATION_RETRY_MS]: the engine's own fixes reach `runtime.lastLocation` directly, so a new request would
     * almost always answer the same. A timeout or an exception is not remembered; the next heartbeat asks again.
     */
    private suspend fun heartbeatLocation(trigger: HeartbeatTrigger): TrackedLocation? {
        configStore.runtime.value.lastLocation?.let { return it }
        val nowElapsed = clock.elapsedRealtime()
        val lastMiss = synchronized(lock) { lastLocationMissElapsed }
        if (lastMiss != null && nowElapsed - lastMiss in 0L until LAST_LOCATION_RETRY_MS) {
            Logger.d(TAG, "$trigger: no known location; the backend had none ${(nowElapsed - lastMiss) / 1_000} s ago")
            return null
        }
        var answered = false
        val found = try {
            withTimeoutOrNull(LAST_LOCATION_TIMEOUT_MS) {
                providers.location().getLastLocation().also { answered = true }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "last known location unavailable", e)
            null
        }
        if (!answered) Logger.w(TAG, "$trigger: the last known location request failed or timed out")
        synchronized(lock) { lastLocationMissElapsed = if (found == null && answered) nowElapsed else null }
        return found
    }

    // ---- scheduling

    private fun reschedule(reason: String, force: Boolean, fromAlarm: Boolean = false) {
        synchronized(lock) { rescheduleLocked(reason, force, fromAlarm) }
    }

    /**
     * Arms, re-arms or cancels the alarms for the current state. Without [force], the armed alarms stay when
     * [HeartbeatWindow.needsRearm] is false (only [current] is updated). [fromAlarm] allows arming in a process whose
     * scheduler was never started (the alarm outlived its process; the receiver then restores tracking).
     */
    private fun rescheduleLocked(reason: String, force: Boolean, fromAlarm: Boolean) {
        val mayArm = when (lifecycle) {
            Lifecycle.STARTED -> true
            Lifecycle.NEW -> fromAlarm || armed != null
            Lifecycle.STOPPED -> false
        }
        if (!mayArm) return
        if (!isActive()) {
            if (!alarmsCleared) {
                Logger.d(TAG, "cancelling alarms ($reason): heartbeat or tracking disabled")
                cancelAlarmsLocked()
                clearPersistedSchedule()
            }
            return
        }
        val canExact = device.canScheduleExactAlarms()
        val isIdle = device.isDeviceIdleMode()
        // After a refused exact alarm the fallback stays until the window moves enough to re-arm anyway.
        val evaluated = computeWindowLocked(canExact && !exactRefused, anchor = true, isIdle = isIdle)
        val armedNow = armed
        if (!force && armedNow != null && !evaluated.needsRearm(armedNow)) {
            current = evaluated
            return
        }
        // A re-arm asks for the exact alarm again.
        val requested = if (canExact && exactRefused) computeWindowLocked(true, anchor = true, isIdle = isIdle) else evaluated
        armLocked(requested, reason, isIdle = isIdle)
    }

    /**
     * Sets the alarms of [window] and returns the window actually armed ([window], or the listener-and-backup
     * fallback when the exact alarm is refused). Nothing is cancelled first: AlarmManager replaces an alarm with the
     * same PendingIntent (exact and backup share one) or the same listener. Only a listener left over from a
     * non-exact schedule is cancelled when switching to exact.
     *
     * @param isIdle the deep-idle reading [window] was computed with (used for the fallback window).
     * @param lastRecord the record [window] starts at, if it is not yet in `runtime` (used for the fallback window).
     */
    private fun armLocked(
        window: HeartbeatWindow,
        reason: String,
        isIdle: Boolean = device.isDeviceIdleMode(),
        lastRecord: Record? = null,
    ): HeartbeatWindow {
        val previous = armed
        var effective = window
        var refused = false
        if (window.strategy == HeartbeatStrategy.EXACT) {
            try {
                alarms.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    window.dueElapsed,
                    HeartbeatIntents.pendingIntent(appContext, HeartbeatTrigger.EXACT_ALARM),
                )
                if (previous == null || previous.strategy != HeartbeatStrategy.EXACT) cancelListenerLocked()
            } catch (e: RuntimeException) {
                // SecurityException when exact alarms are not allowed after all (re-checked on every arm).
                Logger.w(TAG, "exact alarm refused; falling back to listener + backup alarms", e)
                refused = true
                effective = computeWindowLocked(canExact = false, anchor = true, isIdle = isIdle, lastRecord = lastRecord)
            }
        }
        if (effective.strategy != HeartbeatStrategy.EXACT) {
            try {
                alarms.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    effective.dueElapsed,
                    LISTENER_TAG,
                    listener,
                    mainHandler,
                )
            } catch (e: RuntimeException) {
                Logger.e(TAG, "cannot set the listener alarm", e)
            }
            try {
                alarms.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    effective.backupAtElapsed,
                    HeartbeatIntents.pendingIntent(appContext, HeartbeatTrigger.BACKUP_ALARM),
                )
            } catch (e: RuntimeException) {
                Logger.e(TAG, "cannot set the backup alarm", e)
            }
        }
        armed = effective
        current = effective
        exactRefused = refused
        generation++
        alarmsCleared = false
        persistSchedule(effective)
        val nowElapsed = clock.elapsedRealtime()
        Logger.d(
            TAG,
            "armed ($reason): ${effective.strategy.wire}, due in ${(effective.dueElapsed - nowElapsed) / 1_000} s, " +
                "backup in ${(effective.backupAtElapsed - nowElapsed) / 1_000} s",
        )
        return effective
    }

    /** Cancels both alarms, including a PendingIntent alarm armed by a previous process. */
    private fun cancelAlarmsLocked() {
        cancelListenerLocked()
        try {
            HeartbeatIntents.existing(appContext)?.let { alarms.cancel(it) }
        } catch (e: RuntimeException) {
            Logger.w(TAG, "cannot cancel the heartbeat alarm", e)
        }
        armed = null
        current = null
        exactRefused = false
        generation++
        alarmsCleared = true
    }

    private fun cancelListenerLocked() {
        try {
            alarms.cancel(listener)
        } catch (e: RuntimeException) {
            Logger.w(TAG, "cannot cancel the listener alarm", e)
        }
    }

    private fun isActive(): Boolean = configStore.runtime.value.enabled && configStore.config.value.heartbeat.enabled

    /**
     * @param anchor remember "now" as the window base while there is no record (false for read-only previews).
     * @param lastRecord the record the window starts at, if it is not yet in `runtime` (a heartbeat before submit).
     */
    private fun computeWindowLocked(
        canExact: Boolean,
        anchor: Boolean,
        isIdle: Boolean = device.isDeviceIdleMode(),
        lastRecord: Record? = null,
    ): HeartbeatWindow {
        val runtime = configStore.runtime.value
        val heartbeat = configStore.config.value.heartbeat
        val now = clock.now()
        val nowElapsed = clock.elapsedRealtime()
        val bootCount = clock.bootCount()
        val lastRecordAt = lastRecord?.recordedAt ?: runtime.lastRecordAt
        if (anchor && lastRecordAt == null && noRecordAnchorElapsed == null) noRecordAnchorElapsed = nowElapsed
        return HeartbeatWindow.compute(
            lastRecordAt = lastRecordAt,
            lastRecordElapsed = if (lastRecord != null) lastRecord.elapsedRealtimeMs else runtime.lastRecordElapsed,
            lastRecordBootCount = if (lastRecord != null) lastRecord.bootCount else runtime.lastRecordBootCount,
            now = now,
            nowElapsed = nowElapsed,
            bootCount = bootCount,
            minIntervalSec = heartbeat.minInterval,
            maxIntervalSec = heartbeat.maxInterval,
            isIdle = isIdle,
            canExact = canExact,
            lastBackupFireElapsed = lastBackupFireElapsed(bootCount),
            enabled = runtime.enabled && heartbeat.enabled,
            noRecordBaseElapsed = noRecordAnchorElapsed,
            lastAttemptElapsed = lastAttemptElapsed,
            trackingStartedAt = runtime.trackingStartedAt,
        )
    }

    /**
     * (strategy, nextHeartbeatAt) for [status]: the window the heartbeat follows in this process, else what a previous
     * process armed, else a preview.
     */
    private fun scheduleSnapshotLocked(): Pair<HeartbeatStrategy, Long?> {
        if (lifecycle == Lifecycle.STOPPED || !isActive()) return HeartbeatStrategy.DISABLED to null
        val now = clock.now()
        current?.let { window ->
            val inMs = (window.expectedFireElapsed - clock.elapsedRealtime()).coerceAtLeast(0L)
            return window.strategy to now + inMs
        }
        persistedSchedule(clock.bootCount())?.let { (strategy, next) ->
            if (configStore.runtime.value.lastRecordAt == null) return strategy to maxOf(next, now)
            // The previous process may have kept its alarms up to REARM_THRESHOLD_MS before the real due time, and the
            // persisted time is the alarm's. The real due time follows from the last record and the armed strategy.
            val window = computeWindowLocked(
                canExact = strategy == HeartbeatStrategy.EXACT,
                anchor = false,
                isIdle = strategy == HeartbeatStrategy.IDLE_PACED,
            )
            return strategy to (window.nextHeartbeatAt ?: maxOf(next, now))
        }
        val preview = computeWindowLocked(device.canScheduleExactAlarms(), anchor = false)
        return preview.strategy to preview.nextHeartbeatAt
    }

    /** Identifies the last record in `runtime`; it changes whenever a record is submitted. */
    private fun lastRecordMarker(): Pair<Long?, Long?> =
        configStore.runtime.value.let { it.lastRecordAt to it.lastRecordElapsed }

    private fun observeConfigLocked() {
        configJob?.cancel()
        configJob = scope.launch {
            combine(configStore.config, configStore.runtime) { config, runtime -> config.heartbeat to runtime.enabled }
                .distinctUntilChanged()
                .collect { reschedule("config", force = false) }
        }
    }

    private fun registerIdleReceiverLocked() {
        if (idleReceiverRegistered) return
        try {
            ContextCompat.registerReceiver(
                appContext,
                idleReceiver,
                IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            idleReceiverRegistered = true
        } catch (e: RuntimeException) {
            Logger.w(TAG, "cannot observe device idle mode", e)
        }
    }

    private fun unregisterIdleReceiverLocked() {
        if (!idleReceiverRegistered) return
        idleReceiverRegistered = false
        try {
            appContext.unregisterReceiver(idleReceiver)
        } catch (e: RuntimeException) {
            Logger.w(TAG, "cannot unregister the idle receiver", e)
        }
    }

    // ---- wake lock

    private fun acquireWakeLock(): PowerManager.WakeLock? = try {
        powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    } catch (e: RuntimeException) {
        Logger.w(TAG, "cannot acquire the wake lock", e)
        null
    }

    private fun release(wakeLock: PowerManager.WakeLock?) {
        try {
            if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (e: RuntimeException) {
            Logger.w(TAG, "cannot release the wake lock", e)
        }
    }

    // ---- persistence (own `hb_` keys in the shared plugin prefs)

    private fun persistBackupFire(elapsed: Long, bootCount: Int) {
        synchronized(lock) {
            lastBackupFire = elapsed to bootCount
            lastBackupFireLoaded = true
        }
        prefs.edit().putLong(KEY_LAST_BACKUP_ELAPSED, elapsed).putInt(KEY_LAST_BACKUP_BOOT, bootCount).apply()
    }

    /** The last backup fire in this boot, or null. Call with [lock] held. */
    private fun lastBackupFireElapsed(bootCount: Int): Long? {
        if (!lastBackupFireLoaded) {
            val elapsed = prefs.getLong(KEY_LAST_BACKUP_ELAPSED, -1L)
            lastBackupFire = if (elapsed >= 0L) elapsed to prefs.getInt(KEY_LAST_BACKUP_BOOT, Int.MIN_VALUE) else null
            lastBackupFireLoaded = true
        }
        return lastBackupFire?.takeIf { it.second == bootCount }?.first
    }

    /** Persisted on every arm (not on evaluations that keep the alarms), so a new process can report what was armed. */
    private fun persistSchedule(window: HeartbeatWindow) {
        val next = window.nextHeartbeatAt ?: return clearPersistedSchedule()
        prefs.edit()
            .putString(KEY_STRATEGY, window.strategy.wire)
            .putLong(KEY_NEXT_AT, next)
            .putInt(KEY_SCHEDULE_BOOT, clock.bootCount())
            .apply()
    }

    private fun clearPersistedSchedule() {
        prefs.edit().remove(KEY_STRATEGY).remove(KEY_NEXT_AT).remove(KEY_SCHEDULE_BOOT).apply()
    }

    /** What a previous process armed, if it did so in this boot (a reboot wipes every alarm). */
    private fun persistedSchedule(bootCount: Int): Pair<HeartbeatStrategy, Long>? {
        if (prefs.getInt(KEY_SCHEDULE_BOOT, Int.MIN_VALUE) != bootCount) return null
        val strategy = HeartbeatStrategy.fromWire(prefs.getString(KEY_STRATEGY, null)) ?: return null
        val next = prefs.getLong(KEY_NEXT_AT, -1L).takeIf { it >= 0L } ?: return null
        return strategy to next
    }

    internal companion object {
        const val TAG = "LT.Heartbeat"
        const val LISTENER_TAG = "lt-heartbeat"
        const val WAKE_LOCK_TAG = "LocationTracking:heartbeat"

        /**
         * Upper bound of the heartbeat wake lock. The lock is released as soon as the heartbeat is submitted
         * (milliseconds normally); the bound only matters when something hangs, and a shorter one could let the CPU
         * sleep before a slow submit completes.
         */
        const val WAKE_LOCK_TIMEOUT_MS = 60_000L

        // Both run inside the receiver's goAsync() budget.
        const val LAST_LOCATION_TIMEOUT_MS = 3_000L
        const val PROVIDER_CHECK_TIMEOUT_MS = 3_000L

        /** After the backend returned no last location, it is not asked again for this long. */
        const val LAST_LOCATION_RETRY_MS = 10 * 60_000L

        const val KEY_LAST_BACKUP_ELAPSED = "hb_last_backup_fire_elapsed"
        const val KEY_LAST_BACKUP_BOOT = "hb_last_backup_fire_boot"
        const val KEY_STRATEGY = "hb_strategy"
        const val KEY_NEXT_AT = "hb_next_heartbeat_at"
        const val KEY_SCHEDULE_BOOT = "hb_schedule_boot"
    }
}
