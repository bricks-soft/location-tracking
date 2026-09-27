package com.brickssoft.locationtracking.heartbeat

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger

/** The subset of [AlarmManager] the heartbeat uses. Injectable so tests can simulate a refusing AlarmManager. */
interface HeartbeatAlarms {
    /** @throws SecurityException if exact alarms are not allowed. */
    fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent)

    fun setExact(type: Int, triggerAtMillis: Long, tag: String, listener: AlarmManager.OnAlarmListener, handler: Handler)

    fun setAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent)

    fun cancel(operation: PendingIntent)

    fun cancel(listener: AlarmManager.OnAlarmListener)
}

/**
 * [HeartbeatAlarms] backed by the system [AlarmManager]. The plugin declares no exact-alarm permission: the
 * scheduler calls [setExactAndAllowWhileIdle] only when `canScheduleExactAlarms()` (battery-exempt apps) and
 * catches the [SecurityException] otherwise; listener-based [setExact] needs no permission.
 */
class SystemHeartbeatAlarms(context: Context) : HeartbeatAlarms {
    private val appContext: Context = context.applicationContext ?: context
    private val alarmManager: AlarmManager? by lazy {
        (appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager).also {
            if (it == null) Logger.e(TAG, "AlarmManager unavailable; heartbeat alarms are disabled")
        }
    }

    @SuppressLint("ScheduleExactAlarm", "MissingPermission")
    override fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {
        alarmManager?.setExactAndAllowWhileIdle(type, triggerAtMillis, operation)
    }

    @SuppressLint("ScheduleExactAlarm", "MissingPermission")
    override fun setExact(
        type: Int,
        triggerAtMillis: Long,
        tag: String,
        listener: AlarmManager.OnAlarmListener,
        handler: Handler,
    ) {
        alarmManager?.setExact(type, triggerAtMillis, tag, listener, handler)
    }

    override fun setAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {
        alarmManager?.setAndAllowWhileIdle(type, triggerAtMillis, operation)
    }

    override fun cancel(operation: PendingIntent) {
        alarmManager?.cancel(operation)
    }

    override fun cancel(listener: AlarmManager.OnAlarmListener) {
        alarmManager?.cancel(listener)
    }

    private companion object {
        const val TAG = DefaultHeartbeatScheduler.TAG
    }
}

/** The explicit, immutable heartbeat broadcast (request code [Constants.RC_HEARTBEAT], [Constants.ACTION_HEARTBEAT]). */
internal object HeartbeatIntents {
    /** [HeartbeatTrigger] name of the alarm that fired. */
    const val EXTRA_TRIGGER = "com.brickssoft.locationtracking.EXTRA_HEARTBEAT_TRIGGER"

    fun intent(context: Context, trigger: HeartbeatTrigger): Intent =
        Intent(context, HeartbeatAlarmReceiver::class.java)
            .setAction(Constants.ACTION_HEARTBEAT)
            .putExtra(EXTRA_TRIGGER, trigger.name)

    /** The heartbeat PendingIntent; [trigger] is updated in place (one PendingIntent, so one alarm, at a time). */
    fun pendingIntent(context: Context, trigger: HeartbeatTrigger): PendingIntent =
        PendingIntent.getBroadcast(context, Constants.RC_HEARTBEAT, intent(context, trigger), Constants.piImmutable())

    /** The heartbeat PendingIntent if it exists (for example armed by a previous process), without creating it. */
    fun existing(context: Context): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            Constants.RC_HEARTBEAT,
            Intent(context, HeartbeatAlarmReceiver::class.java).setAction(Constants.ACTION_HEARTBEAT),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The trigger carried by [intent]; [HeartbeatTrigger.BACKUP_ALARM] if missing or unknown. */
    fun triggerOf(intent: Intent?): HeartbeatTrigger {
        val name = intent?.getStringExtra(EXTRA_TRIGGER) ?: return HeartbeatTrigger.BACKUP_ALARM
        return HeartbeatTrigger.entries.firstOrNull { it.name == name } ?: HeartbeatTrigger.BACKUP_ALARM
    }
}
