package com.brickssoft.locationtracking.core

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/** Time source. Units must use this instead of `System.currentTimeMillis()` so tests can control time. */
interface Clock {
    /** Wall-clock time, epoch ms. */
    fun now(): Long

    /** Milliseconds since boot, including deep sleep ([SystemClock.elapsedRealtime]). */
    fun elapsedRealtime(): Long

    /** `Settings.Global.BOOT_COUNT`, or -1 if unavailable. */
    fun bootCount(): Int
}

/** Production [Clock]. */
class SystemClockImpl(context: Context) : Clock {
    private val appContext = context.applicationContext ?: context

    @Volatile
    private var cachedBootCount: Int? = null

    override fun now(): Long = System.currentTimeMillis()

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun bootCount(): Int {
        cachedBootCount?.let { return it }
        val value = try {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) {
            -1
        }
        // The boot count cannot change while this process is alive.
        if (value >= 0) cachedBootCount = value
        return value
    }
}
