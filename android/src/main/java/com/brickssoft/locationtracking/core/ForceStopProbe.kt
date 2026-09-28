package com.brickssoft.locationtracking.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Tells whether this process started after the user force-stopped the app (Settings → Force stop, `am force-stop`).
 *
 * Android cancels the app's alarms and does not restart its services after a force stop, so tracking stays off until
 * the user opens the app again. A broadcast that was already on its way can still start the process (on the CI
 * emulator a Play services activity update arrived 0.5 s after `am force-stop`, scenario P-L04). The engine asks this
 * probe before a background trigger (activity update, stationary region EXIT) restores tracking.
 */
fun interface ForceStopProbe {
    /** True when the previous process of this app ended with a user force stop. */
    fun startedAfterForceStop(): Boolean

    companion object {
        /** Never reports a force stop (tests, and Android 10 and older). */
        val NEVER: ForceStopProbe = ForceStopProbe { false }

        /**
         * Reads `ActivityManager.getHistoricalProcessExitReasons` (Android 11+, no permission needed for the own
         * package) once per process: the newest exit record is the previous process of this app.
         */
        fun system(context: Context): ForceStopProbe {
            val app = context.applicationContext ?: context
            val result by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { lastExitWasForceStop(app) }
            return ForceStopProbe { result }
        }

        private fun lastExitWasForceStop(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            return try {
                val manager = context.getSystemService(ActivityManager::class.java) ?: return false
                val last = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
                last != null && last.reason == ApplicationExitInfo.REASON_USER_REQUESTED
            } catch (e: Exception) {
                Logger.w("LT.ForceStop", "the previous process's exit reason is unknown", e)
                false
            }
        }
    }
}
