package com.brickssoft.locationtracking.service

import android.annotation.SuppressLint
import android.content.Context

/**
 * Boot counts (`Settings.Global.BOOT_COUNT`) that [BootReceiver] uses to tell a real boot from a repeated or fake boot
 * broadcast, in the SharedPreferences file [PREFS_NAME] (owned by the service package):
 * - [lastHandled]: the boot count of the last boot broadcast [BootReceiver] handled;
 * - [lastServiceStart]: the boot count during which [DefaultServiceController] last had a start of the location service
 *   accepted by Android (a tracking session was started or restored during that boot).
 *
 * The file is loaded on first use (disk I/O); call these methods off the main thread.
 */
internal class BootCountStore(context: Context) {
    private val context: Context = context.applicationContext ?: context

    private val prefs by lazy { this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /** The boot count of the last handled boot broadcast, or null if none was stored. */
    fun lastHandled(): Int? = read(KEY_LAST_HANDLED)

    /** Stores [bootCount] as handled; written synchronously (`commit`), so it survives a process death right after. */
    @SuppressLint("ApplySharedPref") // deliberate: called off the main thread, and the value must be on disk
    fun setLastHandled(bootCount: Int) {
        prefs.edit().putInt(KEY_LAST_HANDLED, bootCount).commit()
    }

    /** The boot count during which the location service was last started, or null if none was stored. */
    fun lastServiceStart(): Int? = read(KEY_LAST_SERVICE_START)

    /** Records that the location service was started during boot [bootCount]; writes only when the value changes. */
    fun setServiceStarted(bootCount: Int) {
        if (read(KEY_LAST_SERVICE_START) != bootCount) prefs.edit().putInt(KEY_LAST_SERVICE_START, bootCount).apply()
    }

    private fun read(key: String): Int? = if (prefs.contains(key)) prefs.getInt(key, -1) else null

    companion object {
        const val PREFS_NAME = "location_tracking_boot"
        private const val KEY_LAST_HANDLED = "last_handled_boot_count"
        private const val KEY_LAST_SERVICE_START = "last_service_start_boot_count"
    }
}
