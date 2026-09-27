package com.brickssoft.locationtracking.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OemPowerManagersTest {
    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun classesFor(manufacturer: String) = OemPowerManagers.screensFor(manufacturer).map { it.className }

    @Test
    fun `manufacturer matching is case-insensitive`() {
        val expected = listOf(
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        )
        assertEquals(expected, classesFor("HUAWEI"))
        assertEquals(expected, classesFor("huawei"))
        assertEquals(expected, classesFor(" Huawei "))
    }

    @Test
    fun `every supported maker has candidates`() {
        listOf(
            "HUAWEI", "HONOR", "Xiaomi", "Redmi", "POCO", "OPPO", "realme", "OnePlus", "vivo", "iQOO", "samsung",
            "asus", "Letv", "LeMobile", "HTC", "Meizu", "HMD Global", "Nokia",
        ).forEach { maker ->
            assertTrue("no candidates for $maker", OemPowerManagers.screensFor(maker).isNotEmpty())
        }
    }

    @Test
    fun `unknown or empty makers have none`() {
        assertEquals(emptyList<Any>(), OemPowerManagers.screensFor("Google"))
        assertEquals(emptyList<Any>(), OemPowerManagers.screensFor(""))
        assertEquals(emptyList<Any>(), OemPowerManagers.screensFor(null))
        // prefix matches need a word boundary
        assertEquals(emptyList<Any>(), OemPowerManagers.screensFor("htcx"))
    }

    @Test
    fun `specific makers come before shared fallbacks`() {
        assertEquals("com.oneplus.security", OemPowerManagers.screensFor("OnePlus").first().packageName)
        assertTrue(OemPowerManagers.screensFor("OnePlus").any { it.packageName == "com.coloros.safecenter" })
        assertEquals("com.hihonor.systemmanager", OemPowerManagers.screensFor("HONOR").first().packageName)
        assertEquals("com.miui.securitycenter", OemPowerManagers.screensFor("Xiaomi").first().packageName)
    }

    @Test
    fun `intents are explicit components or package-scoped actions`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        listOf("HUAWEI", "Xiaomi", "OPPO", "vivo", "samsung", "asus", "Meizu", "HMD Global").forEach { maker ->
            OemPowerManagers.screensFor(maker).forEach { screen ->
                val intent = screen.toIntent(app)
                assertTrue(
                    "$screen is neither explicit nor package-scoped",
                    intent.component != null || (intent.`package` == screen.packageName && intent.action != null),
                )
            }
        }
    }

    @Test
    fun `every package is declared in the manifest queries`() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("android/src/main/AndroidManifest.xml"))
            .first { it.exists() }
            .readText()
        val declared = Regex("""<package\s+android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()

        val missing = OemPowerManagers.allPackages - declared
        assertTrue("not in <queries>: $missing", missing.isEmpty())
    }
}
