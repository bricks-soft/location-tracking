package com.brickssoft.locationtracking.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.PowerManagerInfo

/**
 * Opens system and OEM settings screens. Screens start from the given Activity when it is usable, otherwise
 * from the application context with `FLAG_ACTIVITY_NEW_TASK`. Any failure to start (ActivityNotFoundException,
 * SecurityException, ...) returns false.
 *
 * The battery-optimization screen is the settings LIST (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`); the
 * plugin never uses `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
 */
class DefaultDeviceSettings(context: Context) : DeviceSettings {
    private val appContext: Context = context.applicationContext ?: context

    override fun openBatteryOptimizationSettings(activity: Activity?): Boolean =
        launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), activity, "battery-optimization settings")

    override fun powerManagerInfo(): PowerManagerInfo {
        val manufacturer = manufacturer()
        return PowerManagerInfo(
            manufacturer = manufacturer,
            available = OemPowerManagers.launchableIntents(appContext, manufacturer).isNotEmpty(),
        )
    }

    override fun openPowerManagerSettings(activity: Activity?): Boolean {
        val manufacturer = manufacturer()
        val intents = OemPowerManagers.launchableIntents(appContext, manufacturer)
        if (intents.isEmpty()) {
            Logger.i(TAG, "no power-manager screen found for manufacturer '$manufacturer'")
            return false
        }
        // Several candidates can resolve; fall through to the next one if a screen refuses to start.
        return intents.any { intent -> launch(intent, activity, "power-manager screen ${describe(intent)}") }
    }

    override fun openLocationSettings(activity: Activity?): Boolean =
        launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), activity, "location settings")

    override fun openAppSettings(activity: Activity?): Boolean = launch(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", appContext.packageName, null)),
        activity,
        "app settings",
    )

    private fun launch(intent: Intent, activity: Activity?, what: String): Boolean {
        val host = activity?.takeIf { !it.isFinishing && !it.isDestroyed }
        return try {
            if (host != null) {
                host.startActivity(intent)
            } else {
                appContext.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Logger.d(TAG, "opened $what")
            true
        } catch (e: RuntimeException) {
            // ActivityNotFoundException, SecurityException, or an OEM ROM rejecting the intent
            Logger.w(TAG, "cannot open $what: ${e.javaClass.simpleName}", e)
            false
        }
    }

    private fun manufacturer(): String = Build.MANUFACTURER.orEmpty()

    private fun describe(intent: Intent): String =
        intent.component?.flattenToShortString() ?: "${intent.`package`}/${intent.action}"

    private companion object {
        const val TAG = "LT.DeviceSettings"
    }
}
