package com.brickssoft.locationtracking.settings

import android.app.Activity
import com.brickssoft.locationtracking.model.PowerManagerInfo

/**
 * Opens system and OEM settings screens. Every `open*` returns false instead of throwing when the screen
 * cannot be opened (ActivityNotFoundException, SecurityException).
 */
interface DeviceSettings {
    /** Opens the battery-optimization list (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`). */
    fun openBatteryOptimizationSettings(activity: Activity?): Boolean

    fun powerManagerInfo(): PowerManagerInfo

    fun openPowerManagerSettings(activity: Activity?): Boolean

    fun openLocationSettings(activity: Activity?): Boolean

    fun openAppSettings(activity: Activity?): Boolean
}
