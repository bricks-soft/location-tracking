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
 * Alarm-based [HeartbeatScheduler] (architecture §3 "Heartbeat algorithm").
 *
 * While tracking is enabled (`runtime.enabled`, either mode) and `heartbeat.enabled`, one window is kept armed.
 * If no record was created for `heartbeat.minInterval`, a `heartbeat` record carrying the last known location
 * is submitted through the [RecordSink], which persists it, restarts the window ([onRecordRecorded]), emits the
 * Heartbeat event and uploads it immediately (priority record; on failure it stays queued).
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

    /** The window currently armed with AlarmManager, or null. */
    private var armed: HeartbeatWindow? = null

    /** Incremented on every arm and cancel; lets [onAlarm] tell whether the sink already re-armed. */
    private var generation = 0L

    /** True once the alarms were cancelled and nothing was armed since. */
    private var alarmsCleared = false

    /** Window base while no record exists yet, so an alarm can find the window due. */
    private var noRecordAnchorElapsed: Long? = null

    /** Last heartbeat attempt: the next one is not due before one min interval later, even if this one failed. */
    private var lastAttemptElapsed: Long? = null

    /** In-memory copy of the persisted last backup fire (elapsed, boot count); loaded lazily. */
    private var lastBackupFire: Pair<Long, Int>? = null
    private var lastBackupFireLoaded = false
    private var configJob: Job? = null
    private var idleReceiverRegistered = false

    /** Serializes [onAlarm]: the listener and the backup alarm may fire for the same window. */
    private val alarmMutex = Mutex()

    private val listener = AlarmManager.OnAlarmListener {
        // Hold our own wake lock across the hop to the coroutine; the alarm's wake lock ends when this returns.
        val wakeLock = acquireWakeLock()
        scope.launch { onAlarm(HeartbeatTrigger.LISTENER_ALARM) }.invokeOnCompletion { release(wakeLock) }
    }

    private val idleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Logger.d(TAG, "device idle mode changed (idle=${device.isDeviceIdleMode()})")
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

    override suspend fun onAlarm(trigger: HeartbeatTrigger) {
        val wakeLock = acquireWakeLock()
        try {
            alarmMutex.withLock { handleAlarm(trigger) }
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
            Logger.d(TAG, "$trigger: not due for ${(window.dueElapsed - nowElapsed) / 1_000} s; re-arming")
            // The alarm that fired is consumed: re-arm even if the window did not change.
            reschedule("early-$trigger", force = true, fromAlarm = true)
            return
        }
        try {
            createHeartbeat(trigger, window, nowElapsed)
        } finally {
            synchronized(lock) {
                // The sink re-arms through onRecordRecorded; re-arm here only if nothing did since the alarm.
                rescheduleLocked("after-$trigger", force = generation == entryGeneration, fromAlarm = true)
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
            val location = configStore.runtime.value.lastLocation ?: lastKnownLocation()
            // The calls above suspend: a record (e.g. providerchange) may have restarted the window, or stop()
            // may have run. Decide again, with no suspension point between this check and the submit.
            val stillDue = synchronized(lock) {
                val nowAgain = clock.elapsedRealtime()
                val due = lifecycle != Lifecycle.STOPPED && isActive() &&
                    computeWindowLocked(device.canScheduleExactAlarms(), anchor = true).shouldFire(nowAgain)
                if (due) lastAttemptElapsed = nowAgain
                due
            }
            if (!stillDue) {
                Logger.d(TAG, "$trigger: superseded by a newer record or stop()")
                return
            }
            val record = recordFactory.create(RecordEvent.HEARTBEAT, location)
            recordSink.value.submit(record)
            Logger.i(TAG, "heartbeat ${record.uuid} ($trigger, ${window.strategy.wire}, location=${location != null})")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "failed to create heartbeat ($trigger)", e)
        }
    }

    private suspend fun lastKnownLocation(): TrackedLocation? = try {
        withTimeoutOrNull(LAST_LOCATION_TIMEOUT_MS) { providers.location().getLastLocation() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "last known location unavailable", e)
        null
    }

    // ---- scheduling

    private fun reschedule(reason: String, force: Boolean, fromAlarm: Boolean = false) {
        synchronized(lock) { rescheduleLocked(reason, force, fromAlarm) }
    }

    /**
     * Arms, re-arms or cancels the alarms for the current state. Without [force] nothing happens if the armed
     * alarms already match. [fromAlarm] allows arming in a process whose scheduler was never started (the alarm
     * outlived its process; the receiver then restores tracking).
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
        val window = computeWindowLocked(device.canScheduleExactAlarms(), anchor = true)
        val current = armed
        if (!force && current != null && current.sameSchedule(window)) return
        armLocked(window, reason)
    }

    /**
     * Sets the alarms of [window]. Nothing is cancelled first: AlarmManager replaces an alarm with the same
     * PendingIntent (exact and backup share one) or the same listener. Only a listener left over from a
     * non-exact schedule is cancelled when switching to exact.
     */
    private fun armLocked(window: HeartbeatWindow, reason: String) {
        val previous = armed
        var effective = window
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
                effective = computeWindowLocked(canExact = false, anchor = true)
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
        generation++
        alarmsCleared = false
        persistSchedule(effective)
        val nowElapsed = clock.elapsedRealtime()
        Logger.d(
            TAG,
            "armed ($reason): ${effective.strategy.wire}, due in ${(effective.dueElapsed - nowElapsed) / 1_000} s, " +
                "backup in ${(effective.backupAtElapsed - nowElapsed) / 1_000} s",
        )
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

    /** @param anchor remember "now" as the window base while there is no record (false for read-only previews). */
    private fun computeWindowLocked(canExact: Boolean, anchor: Boolean): HeartbeatWindow {
        val runtime = configStore.runtime.value
        val heartbeat = configStore.config.value.heartbeat
        val now = clock.now()
        val nowElapsed = clock.elapsedRealtime()
        val bootCount = clock.bootCount()
        if (anchor && runtime.lastRecordAt == null && noRecordAnchorElapsed == null) noRecordAnchorElapsed = nowElapsed
        return HeartbeatWindow.compute(
            lastRecordAt = runtime.lastRecordAt,
            lastRecordElapsed = runtime.lastRecordElapsed,
            lastRecordBootCount = runtime.lastRecordBootCount,
            now = now,
            nowElapsed = nowElapsed,
            bootCount = bootCount,
            minIntervalSec = heartbeat.minInterval,
            maxIntervalSec = heartbeat.maxInterval,
            isIdle = device.isDeviceIdleMode(),
            canExact = canExact,
            lastBackupFireElapsed = lastBackupFireElapsed(bootCount),
            enabled = runtime.enabled && heartbeat.enabled,
            noRecordBaseElapsed = noRecordAnchorElapsed,
            lastAttemptElapsed = lastAttemptElapsed,
            trackingStartedAt = runtime.trackingStartedAt,
        )
    }

    /** (strategy, nextHeartbeatAt) for [status]: what is armed, else what a previous process armed, else a preview. */
    private fun scheduleSnapshotLocked(): Pair<HeartbeatStrategy, Long?> {
        if (lifecycle == Lifecycle.STOPPED || !isActive()) return HeartbeatStrategy.DISABLED to null
        val now = clock.now()
        armed?.let { window ->
            val inMs = (window.expectedFireElapsed - clock.elapsedRealtime()).coerceAtLeast(0L)
            return window.strategy to now + inMs
        }
        persistedSchedule(clock.bootCount())?.let { (strategy, next) -> return strategy to maxOf(next, now) }
        val preview = computeWindowLocked(device.canScheduleExactAlarms(), anchor = false)
        return preview.strategy to preview.nextHeartbeatAt
    }

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
        const val WAKE_LOCK_TIMEOUT_MS = 60_000L
        // Both run inside the receiver's goAsync() budget.
        const val LAST_LOCATION_TIMEOUT_MS = 3_000L
        const val PROVIDER_CHECK_TIMEOUT_MS = 3_000L

        const val KEY_LAST_BACKUP_ELAPSED = "hb_last_backup_fire_elapsed"
        const val KEY_LAST_BACKUP_BOOT = "hb_last_backup_fire_boot"
        const val KEY_STRATEGY = "hb_strategy"
        const val KEY_NEXT_AT = "hb_next_heartbeat_at"
        const val KEY_SCHEDULE_BOOT = "hb_schedule_boot"
    }
}
