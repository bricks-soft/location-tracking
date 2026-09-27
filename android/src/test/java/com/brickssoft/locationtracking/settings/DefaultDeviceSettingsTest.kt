package com.brickssoft.locationtracking.settings

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.PowerManagerInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34])
class DefaultDeviceSettingsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = DefaultDeviceSettings(app)

    @After
    fun tearDown() {
        Logger.sink = null
    }

    /** Makes [className] in [packageName] resolvable, as if the OEM app were installed. */
    private fun install(packageName: String, className: String, exported: Boolean = true, permission: String? = null) {
        val info = shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(packageName, className))
        info.exported = exported
        info.permission = permission
    }

    private fun nextStartedFromApp(): Intent? = shadowOf(app).nextStartedActivity

    @Test
    fun `battery optimization opens the settings list, not the request dialog`() {
        assertTrue(settings.openBatteryOptimizationSettings(null))

        val intent = nextStartedFromApp()!!
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intent.action)
        assertNotEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertNull(intent.data)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `screens start from the given activity when there is one`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        assertTrue(settings.openBatteryOptimizationSettings(activity))

        val intent = shadowOf(activity).nextStartedActivity!!
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intent.action)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun `a finishing activity falls back to the app context`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.finish()

        assertTrue(settings.openLocationSettings(activity))

        // Robolectric has one started-activities queue; the NEW_TASK flag shows it came from the app context.
        val intent = nextStartedFromApp()!!
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `location settings`() {
        assertTrue(settings.openLocationSettings(null))

        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, nextStartedFromApp()!!.action)
    }

    @Test
    fun `app settings target this package`() {
        assertTrue(settings.openAppSettings(null))

        val intent = nextStartedFromApp()!!
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals(Uri.parse("package:${app.packageName}"), intent.data)
    }

    @Test
    fun `unresolvable screens return false instead of throwing`() {
        shadowOf(app).checkActivities(true)

        assertFalse(settings.openBatteryOptimizationSettings(null))
        assertFalse(settings.openLocationSettings(null))
        assertFalse(settings.openAppSettings(null))
    }

    @Test
    fun `huawei power manager is available and opened when it resolves`() {
        ShadowBuild.setManufacturer("HUAWEI")
        install("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")

        assertEquals(PowerManagerInfo(manufacturer = "HUAWEI", available = true), settings.powerManagerInfo())
        assertTrue(settings.openPowerManagerSettings(null))

        val intent = nextStartedFromApp()!!
        assertEquals(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            intent.component,
        )
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `huawei app launch screen is preferred over older screens`() {
        ShadowBuild.setManufacturer("Huawei")
        install("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
        install("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        assertTrue(settings.openPowerManagerSettings(activity))

        assertEquals(
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            shadowOf(activity).nextStartedActivity!!.component!!.className,
        )
    }

    @Test
    fun `nothing resolvable means not available and nothing opened`() {
        ShadowBuild.setManufacturer("HUAWEI")

        assertEquals(PowerManagerInfo(manufacturer = "HUAWEI", available = false), settings.powerManagerInfo())
        assertFalse(settings.openPowerManagerSettings(null))
        assertNull(nextStartedFromApp())
    }

    @Test
    fun `another maker's screen is not used`() {
        ShadowBuild.setManufacturer("Google")
        install("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")

        assertFalse(settings.powerManagerInfo().available)
        assertFalse(settings.openPowerManagerSettings(null))
        assertNull(nextStartedFromApp())
    }

    @Test
    fun `non-exported or permission-protected screens are not available`() {
        ShadowBuild.setManufacturer("HUAWEI")
        install("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity", exported = false)
        install(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            permission = "com.huawei.permission.SECRET",
        )

        assertFalse(settings.powerManagerInfo().available)
        assertFalse(settings.openPowerManagerSettings(null))
    }

    @Test
    fun `a screen that refuses to start falls through to the next candidate`() {
        ShadowBuild.setManufacturer("HUAWEI")
        install("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        install("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
        val activity = Robolectric.buildActivity(RefusingActivity::class.java).setup().get()
        activity.refused += "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"

        assertTrue(settings.openPowerManagerSettings(activity))

        assertEquals(
            "com.huawei.systemmanager.optimize.process.ProtectActivity",
            shadowOf(activity).nextStartedActivity!!.component!!.className,
        )
    }

    @Test
    fun `every candidate refusing returns false`() {
        ShadowBuild.setManufacturer("HUAWEI")
        install("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
        val activity = Robolectric.buildActivity(RefusingActivity::class.java).setup().get()
        activity.refused += "com.huawei.systemmanager.optimize.process.ProtectActivity"

        assertFalse(settings.openPowerManagerSettings(activity))
    }

    @Test
    fun `honor falls back to huawei's system manager`() {
        ShadowBuild.setManufacturer("HONOR")
        install("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")

        assertEquals(PowerManagerInfo(manufacturer = "HONOR", available = true), settings.powerManagerInfo())
    }

    @Test
    fun `xiaomi battery saver screen gets the package extras`() {
        ShadowBuild.setManufacturer("Xiaomi")
        install("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")

        assertTrue(settings.openPowerManagerSettings(null))

        val intent = nextStartedFromApp()!!
        assertEquals("com.miui.powerkeeper.ui.HiddenAppsConfigActivity", intent.component!!.className)
        assertEquals(app.packageName, intent.getStringExtra("package_name"))
        assertTrue(!intent.getStringExtra("package_label").isNullOrEmpty())
    }

    @Test
    fun `samsung battery screen`() {
        ShadowBuild.setManufacturer("samsung")
        install("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")

        assertTrue(settings.openPowerManagerSettings(null))

        assertEquals(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            nextStartedFromApp()!!.component,
        )
    }

    /** Activity whose `startActivity` throws SecurityException for the [refused] class names. */
    class RefusingActivity : Activity() {
        val refused = mutableSetOf<String>()

        override fun startActivity(intent: Intent) {
            if (intent.component?.className in refused) throw SecurityException("Permission Denial")
            super.startActivity(intent)
        }
    }
}
