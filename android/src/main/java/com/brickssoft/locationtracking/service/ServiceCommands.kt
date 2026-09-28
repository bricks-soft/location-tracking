package com.brickssoft.locationtracking.service

import android.content.Intent
import android.os.Process
import androidx.annotation.VisibleForTesting
import com.brickssoft.locationtracking.config.NotificationConfig
import com.brickssoft.locationtracking.config.NotificationPriority
import kotlin.random.Random

/**
 * The start/stop state machine that [DefaultServiceController] (the sender) and [LocationTrackingService] (the
 * receiver) share inside one process. It exists because of one Android rule: after `startForegroundService()`, the
 * service must call `startForeground()` before it stops. A `stopService()` that arrives before that makes the system
 * crash the app with `ForegroundServiceDidNotStartInTimeException` ("Context.startForegroundService() did not then call
 * Service.startForeground()").
 *
 * **Commands.** Every command the controller sends carries a sequence number ([EXTRA_SEQ], increasing within the
 * process) and the process token ([EXTRA_ORIGIN] = [processToken]).
 * - A **start** is sent with `startForegroundService()` and is marked [EXTRA_FOREGROUND_REQUIRED]. It also carries the
 *   configured notification's channel, priority, title, text, small icon and color as plain string extras
 *   ([putNotification]), so the service's first `startForeground()` uses the configured channel and content without
 *   loading the config (see [ServiceCommand.notification]).
 * - A **stop** is sent with `startService()` (action [LocationTrackingService.ACTION_STOP]). Android delivers commands
 *   to `onStartCommand` in the order they were sent, so a stop is handled after every start sent before it, and each of
 *   those starts has entered the foreground by then.
 *
 * **States.** A start is *pending* from the moment it was sent until `onStartCommand` has called `startForeground()`
 * for it ([startHandled], called on success and on failure). The controller records:
 * - `lastStart`: the sequence number of the last start that Android accepted;
 * - `lastStop`: the sequence number of the last stop request (recorded before the stop command is sent, and also when
 *   Android refuses the stop command);
 * - `handledStart`: the sequence number of the last start that `onStartCommand` has handled.
 *
 * A start is pending while `lastStart > handledStart`. The latest request is a stop while `lastStop > lastStart`.
 *
 * **Transitions.**
 * - `start()` while a start is pending and no stop followed it: nothing is sent, `start()` returns true (the pending
 *   start will bring the service into the foreground). Otherwise a new start is sent.
 * - `stop()`: records `lastStop`, then sends the stop command. `Context.stopService()` is used only when Android
 *   refuses the stop command **and** no start is pending; while a start is pending the service stops itself after it
 *   has entered the foreground (see below).
 * - `onStartCommand(start s)`: calls `startForeground()` first, then [startHandled]. If [isStoppedAfter] (a stop was
 *   requested after `s` and no start followed that stop), the service calls `stopSelf(startId)`. If the stop command is
 *   still queued, Android ignores this `stopSelf` (a newer start id exists) and the stop command stops the service.
 * - `onStartCommand(stop t)`: if [isStartedAfter] (a start was sent after `t`), the stop is ignored. Otherwise the service
 *   calls `stopSelf(startId)`, which Android ignores when a newer command was already sent.
 *
 * Result: start → stop → start in one main-thread turn ends with the service running; start → stop ends with the
 * service stopped after it entered the foreground once; no path calls `stopService()` while a start is pending.
 *
 * **Other processes.** A command whose [EXTRA_ORIGIN] differs from [processToken] was sent by an earlier process that
 * died before `onStartCommand` ran (Android keeps undelivered commands and delivers them to the restarted service).
 * Its sequence number means nothing here; the service decides from the persisted `enabled` flag instead.
 *
 * All state is guarded by one lock; no method blocks or performs I/O.
 */
internal object ServiceCommands {
    /** Long: the command's sequence number in the sending process. */
    const val EXTRA_SEQ = "com.brickssoft.locationtracking.service.EXTRA_SEQ"

    /** String: the [processToken] of the process that sent the command. */
    const val EXTRA_ORIGIN = "com.brickssoft.locationtracking.service.EXTRA_ORIGIN"

    /** Boolean: the command was sent with `startForegroundService()`, so `onStartCommand` must call `startForeground()`. */
    const val EXTRA_FOREGROUND_REQUIRED = "com.brickssoft.locationtracking.service.EXTRA_FOREGROUND_REQUIRED"

    // The notification fields of a start command (see putNotification).
    const val EXTRA_CHANNEL_ID = "com.brickssoft.locationtracking.service.EXTRA_CHANNEL_ID"
    const val EXTRA_CHANNEL_NAME = "com.brickssoft.locationtracking.service.EXTRA_CHANNEL_NAME"
    const val EXTRA_PRIORITY = "com.brickssoft.locationtracking.service.EXTRA_PRIORITY"
    const val EXTRA_TITLE = "com.brickssoft.locationtracking.service.EXTRA_TITLE"
    const val EXTRA_TEXT = "com.brickssoft.locationtracking.service.EXTRA_TEXT"
    const val EXTRA_SMALL_ICON = "com.brickssoft.locationtracking.service.EXTRA_SMALL_ICON"
    const val EXTRA_COLOR = "com.brickssoft.locationtracking.service.EXTRA_COLOR"

    /** Identifies this process. It differs between processes (pid plus a random number drawn at process start). */
    val processToken: String = "${Process.myPid()}-${Random.nextLong().toULong().toString(16)}"

    private val lock = Any()
    private var lastSeq = 0L
    private var lastStart = 0L
    private var lastStop = 0L
    private var handledStart = 0L

    /** The next sequence number. */
    fun nextSeq(): Long = synchronized(lock) { ++lastSeq }

    /** Android accepted start [seq]. */
    fun startSent(seq: Long) = synchronized(lock) { if (seq > lastStart) lastStart = seq }

    /** Stop [seq] was requested (whether or not Android accepts the stop command). */
    fun stopRequested(seq: Long) = synchronized(lock) { if (seq > lastStop) lastStop = seq }

    /** `onStartCommand` called `startForeground()` for start [seq] (successfully or not). */
    fun startHandled(seq: Long) = synchronized(lock) { if (seq > handledStart) handledStart = seq }

    /** True while a start from this process has not reached `startForeground()` yet. */
    val isStartPending: Boolean get() = synchronized(lock) { lastStart > handledStart }

    /** True while a start is pending and it is the latest request (no stop was requested after it). */
    val isStartPendingAndLatest: Boolean
        get() = synchronized(lock) { lastStart > handledStart && lastStart > lastStop }

    /** True if a stop was requested after start [seq] and no start was sent after that stop. */
    fun isStoppedAfter(seq: Long): Boolean = synchronized(lock) { lastStop > seq && lastStop > lastStart }

    /** True if a start was sent after stop [seq]. */
    fun isStartedAfter(seq: Long): Boolean = synchronized(lock) { lastStart > seq }

    /** Adds the sequence number, origin and foreground marker to a command intent. */
    fun stamp(intent: Intent, seq: Long, foregroundRequired: Boolean): Intent = intent
        .putExtra(EXTRA_SEQ, seq)
        .putExtra(EXTRA_ORIGIN, processToken)
        .putExtra(EXTRA_FOREGROUND_REQUIRED, foregroundRequired)

    /**
     * Copies the fields of [config] that the first notification needs (channel id, channel name, priority, title, text,
     * small icon, color) into [intent]. Action buttons and the large icon are not copied; the configured notification
     * that replaces the first one has them.
     */
    fun putNotification(intent: Intent, config: NotificationConfig): Intent = intent
        .putExtra(EXTRA_CHANNEL_ID, config.channelId)
        .putExtra(EXTRA_CHANNEL_NAME, config.channelName)
        .putExtra(EXTRA_PRIORITY, config.priority.wire)
        .putExtra(EXTRA_TITLE, config.title)
        .putExtra(EXTRA_TEXT, config.text)
        .putExtra(EXTRA_SMALL_ICON, config.smallIcon)
        .putExtra(EXTRA_COLOR, config.color)

    /** The notification fields written by [putNotification], or null if [intent] has none. Never throws. */
    fun notificationOf(intent: Intent): NotificationConfig? = try {
        intent.getStringExtra(EXTRA_CHANNEL_ID)?.let { channelId ->
            val defaults = NotificationConfig()
            NotificationConfig(
                title = intent.getStringExtra(EXTRA_TITLE),
                text = intent.getStringExtra(EXTRA_TEXT) ?: defaults.text,
                smallIcon = intent.getStringExtra(EXTRA_SMALL_ICON) ?: defaults.smallIcon,
                color = intent.getStringExtra(EXTRA_COLOR),
                priority = NotificationPriority.fromWire(intent.getStringExtra(EXTRA_PRIORITY)) ?: defaults.priority,
                channelId = channelId,
                channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: defaults.channelName,
            )
        }
    } catch (e: Exception) {
        null
    }

    /** Test hook: forgets every command. */
    @VisibleForTesting
    fun reset() = synchronized(lock) {
        lastSeq = 0L
        lastStart = 0L
        lastStop = 0L
        handledStart = 0L
    }
}

/**
 * One `onStartCommand` intent, parsed without touching any component.
 *
 * @property kind what the command asks for.
 * @property seq the sender's sequence number, or 0 if the intent has none.
 * @property fromThisProcess true if [ServiceCommands.processToken] sent it; false for a command of an earlier process.
 * @property foregroundRequired true if the sender used `startForegroundService()`.
 * @property notification the notification fields the sender copied from its config ([ServiceCommands.putNotification]),
 *   or null (null intent, stop command, or an intent without them).
 */
internal data class ServiceCommand(
    val kind: Kind,
    val seq: Long,
    val fromThisProcess: Boolean,
    val foregroundRequired: Boolean,
    val notification: NotificationConfig? = null,
) {
    enum class Kind {
        /** Null intent: the system restarted the service after its process died (`START_STICKY`). */
        RESTART,
        START,
        STOP,
    }

    companion object {
        fun parse(intent: Intent?): ServiceCommand {
            if (intent == null) return ServiceCommand(Kind.RESTART, 0L, fromThisProcess = false, foregroundRequired = false)
            val kind = if (intent.action == LocationTrackingService.ACTION_STOP) Kind.STOP else Kind.START
            val origin = intent.getStringExtra(ServiceCommands.EXTRA_ORIGIN)
            return ServiceCommand(
                kind = kind,
                seq = intent.getLongExtra(ServiceCommands.EXTRA_SEQ, 0L),
                fromThisProcess = origin == ServiceCommands.processToken,
                // A start without the marker still came through startForegroundService(): the controller sends no other.
                foregroundRequired = intent.getBooleanExtra(ServiceCommands.EXTRA_FOREGROUND_REQUIRED, kind == Kind.START),
                notification = if (kind == Kind.START) ServiceCommands.notificationOf(intent) else null,
            )
        }
    }
}
