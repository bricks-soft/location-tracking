package com.brickssoft.locationtracking.device

import android.app.ActivityManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

/** The device readings of heartbeat and `tracking_start` records (device-information parts d1 to d7). */
@RunWith(RobolectricTestRunner::class)
class DeviceDetailsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [34])
    fun `heartbeat readings come from the platform services`() {
        shadowOf(app.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(true)
        shadowOf(app.getSystemService(UsageStatsManager::class.java))
            .setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RARE)
        shadowOf(app.getSystemService(PowerManager::class.java)).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
        ShadowStatFs.registerStats(app.filesDir, 1_000, 500, 400)

        val snapshot = DefaultDeviceDetails(app).forHeartbeat()

        assertEquals(true, snapshot.backgroundRestricted)
        assertEquals("rare", snapshot.standbyBucket)
        assertEquals("severe", snapshot.thermalStatus)
        assertNotNull(snapshot.diskFreeBytes)
        assertNull(snapshot.model) // phone details are only on tracking_start records
    }

    @Test
    fun `readings the Android version does not have are null`() {
        val snapshot = DefaultDeviceDetails(app, sdkInt = Build.VERSION_CODES.O_MR1).forHeartbeat()

        assertNull(snapshot.backgroundRestricted) // Android 9+
        assertNull(snapshot.standbyBucket) // Android 9+
        assertNull(snapshot.thermalStatus) // Android 10+
    }

    @Test
    fun `tracking_start readings name the phone and include ANDROID_ID`() {
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ANDROID_ID, "a1b2c3d4e5f60718")

        val snapshot = DefaultDeviceDetails(app).forTrackingStart()

        assertEquals(Build.MANUFACTURER, snapshot.manufacturer)
        assertEquals(Build.MODEL, snapshot.model)
        assertEquals(Build.VERSION.RELEASE, snapshot.osVersion)
        assertEquals(Build.VERSION.SDK_INT, snapshot.sdkInt)
        assertEquals("a1b2c3d4e5f60718", snapshot.androidId)
        assertNotNull(snapshot.lowRamDevice)
        assertNull(snapshot.standbyBucket)
    }

    @Test
    fun `battery details come from the sticky battery broadcast`() {
        app.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_TEMPERATURE, 315)
                .putExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_OVERHEAT)
                .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB),
        )

        val details = DefaultDeviceDetails(app).batteryDetails()!!

        assertEquals(31.5f, details.temperature!!, 0.001f)
        assertEquals("overheat", details.health)
        assertEquals("usb", details.plugged)
    }

    @Test
    fun `platform values map to stable names`() {
        assertEquals("active", DefaultDeviceDetails.standbyBucketName(UsageStatsManager.STANDBY_BUCKET_ACTIVE))
        assertEquals("working_set", DefaultDeviceDetails.standbyBucketName(UsageStatsManager.STANDBY_BUCKET_WORKING_SET))
        assertEquals("frequent", DefaultDeviceDetails.standbyBucketName(UsageStatsManager.STANDBY_BUCKET_FREQUENT))
        assertEquals("restricted", DefaultDeviceDetails.standbyBucketName(45))
        assertEquals("exempted", DefaultDeviceDetails.standbyBucketName(5))
        assertEquals("none", DefaultDeviceDetails.thermalStatusName(PowerManager.THERMAL_STATUS_NONE))
        assertEquals("shutdown", DefaultDeviceDetails.thermalStatusName(PowerManager.THERMAL_STATUS_SHUTDOWN))
        assertEquals("good", DefaultDeviceDetails.healthName(BatteryManager.BATTERY_HEALTH_GOOD))
        assertEquals("unknown", DefaultDeviceDetails.healthName(99))
        assertEquals("none", DefaultDeviceDetails.pluggedName(0))
        assertEquals("ac", DefaultDeviceDetails.pluggedName(BatteryManager.BATTERY_PLUGGED_AC))
        assertEquals("wireless", DefaultDeviceDetails.pluggedName(BatteryManager.BATTERY_PLUGGED_WIRELESS))
        assertEquals("dock", DefaultDeviceDetails.pluggedName(8))
    }
}
