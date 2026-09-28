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
        private const val TAG = "LT.ForceStop"

        /** Exit records read to find the main process's newest one (other processes of the app leave records too). */
        private const val EXIT_RECORDS = 16

        /** Never reports a force stop (tests, and Android 10 and older). */
        val NEVER: ForceStopProbe = ForceStopProbe { false }

        /**
         * Reads `ActivityManager.getHistoricalProcessExitReasons` (Android 11+, no permission needed for the own
         * package) once per process: the newest exit record of the app's main process is the previous process.
         * Records of other processes are skipped: the WebView's isolated renderer runs under the app, and a force stop
         * kills it a few milliseconds after the main process ("isolated not needed"), so its record can be the newest
         * one (CI emulator, API 34, scenario P-L04: reading only the newest record missed the force stop).
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
                val mainProcess = context.applicationInfo?.processName ?: context.packageName
                val previous = manager.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_RECORDS)
                    .firstOrNull { it.processName == mainProcess }
                    ?: return false
                val forceStopped = previous.reason == ApplicationExitInfo.REASON_USER_REQUESTED
                Logger.i(
                    TAG,
                    "previous process ${previous.pid} exited with reason ${previous.reason} " +
                        "(${previous.description ?: "no description"}); force stop: $forceStopped",
                )
                forceStopped
            } catch (e: Exception) {
                Logger.w(TAG, "the previous process's exit reason is unknown", e)
                false
            }
        }
    }
}
