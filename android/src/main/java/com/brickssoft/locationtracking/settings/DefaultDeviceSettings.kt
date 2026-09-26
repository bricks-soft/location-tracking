// STUB — owned by Unit 15 (Permissions + settings). Replace this implementation.
package com.brickssoft.locationtracking.settings

import android.app.Activity
import android.content.Context
import com.brickssoft.locationtracking.model.PowerManagerInfo

/** Opens system / OEM settings screens. Stub: opens nothing. */
@Suppress("unused")
class DefaultDeviceSettings(private val context: Context) : DeviceSettings {
    override fun openBatteryOptimizationSettings(activity: Activity?): Boolean = false

    override fun powerManagerInfo(): PowerManagerInfo = PowerManagerInfo(manufacturer = "", available = false)

    override fun openPowerManagerSettings(activity: Activity?): Boolean = false

    override fun openLocationSettings(activity: Activity?): Boolean = false

    override fun openAppSettings(activity: Activity?): Boolean = false
}
