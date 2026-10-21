package com.brickssoft.locationtracking.device

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.BatteryDetails
import com.brickssoft.locationtracking.model.DeviceSnapshot

/**
 * Device readings carried by records (the device-information parts d1 to d7):
 * - [deviceId]: `Settings.Secure.ANDROID_ID`, on every record (`device.device_id`); read once per process.
 * - [forHeartbeat]: `background_restricted` and `standby_bucket` (Android 9+), `disk_free_bytes`, `thermal_status`
 *   (Android 10+). They explain gaps: a "Restricted" battery setting, a low standby bucket and a full disk each stop or
 *   delay records, and heartbeats are the records sent while nothing else is recorded.
 * - [batteryDetails]: battery `temperature`, `health` and `plugged`, for heartbeat records.
 * - [forTrackingStart]: phone `manufacturer`, `model`, `os_version`, `sdk_int`, `low_ram_device` and
 *   `ram_total_bytes`. They do not change during a session.
 *
 * Every reading is null when the Android version does not have it or reading it fails (logged); none needs a
 * permission. Never throws.
 */
interface DeviceDetails {
    fun forHeartbeat(): DeviceSnapshot

    fun forTrackingStart(): DeviceSnapshot

    fun batteryDetails(): BatteryDetails?

    fun deviceId(): String?

    companion object {
        /** No readings (tests, and callers without a Context). */
        val NONE: DeviceDetails = object : DeviceDetails {
            override fun forHeartbeat(): DeviceSnapshot = DeviceSnapshot()

            override fun forTrackingStart(): DeviceSnapshot = DeviceSnapshot()

            override fun batteryDetails(): BatteryDetails? = null

            override fun deviceId(): String? = null
        }
    }
}

/** [DeviceDetails] from the platform services. */
class DefaultDeviceDetails(context: Context, private val sdkInt: Int = Build.VERSION.SDK_INT) : DeviceDetails {
    private val appContext: Context = context.applicationContext ?: context

    // One instance per process (Components); ANDROID_ID only changes on a factory reset or a new signing key.
    private val deviceId: String? by lazy {
        read("ANDROID_ID") { Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) }
    }

    override fun forHeartbeat(): DeviceSnapshot = DeviceSnapshot(
        backgroundRestricted = read("isBackgroundRestricted") {
            if (sdkInt >= Build.VERSION_CODES.P) activityManager()?.isBackgroundRestricted else null
        },
        standbyBucket = read("getAppStandbyBucket") {
            if (sdkInt >= Build.VERSION_CODES.P) {
                appContext.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket?.let(::standbyBucketName)
            } else {
                null
            }
        },
        diskFreeBytes = read("StatFs") { StatFs(appContext.filesDir.path).availableBytes },
        thermalStatus = read("getCurrentThermalStatus") {
            if (sdkInt >= Build.VERSION_CODES.Q) {
                appContext.getSystemService(PowerManager::class.java)?.currentThermalStatus?.let(::thermalStatusName)
            } else {
                null
            }
        },
    )

    override fun forTrackingStart(): DeviceSnapshot {
        val memory = read("getMemoryInfo") {
            activityManager()?.let { manager -> ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) } }
        }
        return DeviceSnapshot(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = sdkInt,
            lowRamDevice = read("isLowRamDevice") { activityManager()?.isLowRamDevice },
            ramTotalBytes = memory?.totalMem?.takeIf { it > 0 },
        )
    }

    override fun deviceId(): String? = deviceId

    override fun batteryDetails(): BatteryDetails? = read("battery details") {
        val intent: Intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return@read null
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        BatteryDetails(
            temperature = if (temperature == Int.MIN_VALUE) null else temperature / 10f,
            health = healthName(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)),
            plugged = pluggedName(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)),
        )
    }

    private fun activityManager(): ActivityManager? = appContext.getSystemService(ActivityManager::class.java)

    private inline fun <T> read(what: String, block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        Logger.w(TAG, "$what unavailable", e)
        null
    }

    internal companion object {
        private const val TAG = "LT.DeviceDetails"

        /** `UsageStatsManager` bucket values (EXEMPTED = 5 is not public API). */
        fun standbyBucketName(bucket: Int): String = when {
            bucket <= STANDBY_BUCKET_EXEMPTED -> "exempted"
            bucket <= UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
            bucket <= UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working_set"
            bucket <= UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
            bucket <= UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
            else -> "restricted"
        }

        private const val STANDBY_BUCKET_EXEMPTED = 5

        fun thermalStatusName(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }

        fun healthName(health: Int): String = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
            else -> "unknown"
        }

        /** `EXTRA_PLUGGED`; the dock value (8) is public only from Android 14. */
        fun pluggedName(plugged: Int): String = when (plugged) {
            0 -> "none"
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            BATTERY_PLUGGED_DOCK -> "dock"
            else -> "other"
        }

        private const val BATTERY_PLUGGED_DOCK = 8
    }
}
