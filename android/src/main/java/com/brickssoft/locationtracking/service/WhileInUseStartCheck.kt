package com.brickssoft.locationtracking.service

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.permission.ActivityLifecycleAdapter
import com.brickssoft.locationtracking.permission.CurrentActivityTracker

/**
 * Predicts, before `startForegroundService()`, whether Android 14+ would refuse a location foreground service because
 * the app would not get while-in-use location access.
 *
 * On Android 14 (API 34) and later, `startForeground()` with type `location` throws `SecurityException` when the app
 * has only while-in-use location permission (no `ACCESS_BACKGROUND_LOCATION`) and is not in a state that grants
 * while-in-use access. The main such state is a visible activity; Android also grants it for 10 s after an activity of
 * the app was started or finished. A boot, alarm or heartbeat start from the background therefore fails.
 *
 * [refusalReason] returns a reason (start refused) only when all of these are true:
 * - API level ≥ 34;
 * - `ACCESS_BACKGROUND_LOCATION` is not granted;
 * - the process importance (`ActivityManager.getMyMemoryState`) is worse than `IMPORTANCE_VISIBLE`. An importance of
 *   `IMPORTANCE_FOREGROUND_SERVICE` (another foreground service of the app runs, for example a companion plugin's) is
 *   treated as possibly visible, because the activity state cannot be told from it;
 * - the activity that `CurrentActivityTracker` remembers (the plugin seeds it with its activity at load) is not in the
 *   `STARTED` lifecycle state or later;
 * - no activity is started according to the lifecycle callbacks registered by this object (they see only events after
 *   this object was created), and no activity was started, stopped or destroyed during the last [GRACE_MS] ms. This
 *   covers Android's 10 s grace period with a margin.
 *
 * When the check cannot be evaluated (an exception), it does not refuse. Every uncertain case lets the start proceed:
 * a start that Android then refuses is still handled by the service (`onServiceStartFailed`).
 *
 * Other Android exemptions (a notification tap, a PendingIntent from a visible app) are not modelled; a start in such a
 * state without a visible activity is refused here.
 */
internal class WhileInUseStartCheck(context: Context, private val clock: Clock) {
    private val context: Context = context.applicationContext ?: context

    @Volatile
    private var startedActivities = 0

    /** `clock.elapsedRealtime()` of the last activity start, stop or destroy seen; null if none was seen. */
    @Volatile
    private var lastActivityChangeAt: Long? = null

    private val callbacks = object : ActivityLifecycleAdapter() {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            lastActivityChangeAt = clock.elapsedRealtime()
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            lastActivityChangeAt = clock.elapsedRealtime()
        }

        override fun onActivityDestroyed(activity: Activity) {
            lastActivityChangeAt = clock.elapsedRealtime()
        }
    }

    init {
        (this.context as? Application)?.registerActivityLifecycleCallbacks(callbacks)
    }

    /** Why a location foreground service started now would be refused, or null if it may be allowed. */
    fun refusalReason(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return try {
            when {
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> null
                processMayBeVisible() -> null
                rememberedActivityStarted() -> null
                startedActivities > 0 -> null
                activityChangedRecently() -> null
                else -> "Android 14+: no visible activity and no background location permission"
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun processMayBeVisible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun rememberedActivityStarted(): Boolean {
        val owner = CurrentActivityTracker.attach(context).current() as? LifecycleOwner ?: return false
        return owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    private fun activityChangedRecently(): Boolean {
        val at = lastActivityChangeAt ?: return false
        val age = clock.elapsedRealtime() - at
        return age in 0 until GRACE_MS
    }

    companion object {
        /** Android grants while-in-use access for 10 s after an activity start or finish; 15 s adds a margin. */
        const val GRACE_MS = 15_000L
    }
}
