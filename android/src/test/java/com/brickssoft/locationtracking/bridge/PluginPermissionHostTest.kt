package com.brickssoft.locationtracking.bridge

import android.Manifest
import android.app.Application
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.LocationTrackingPlugin
import com.brickssoft.locationtracking.permission.PermissionType
import com.getcapacitor.Bridge
import com.getcapacitor.Plugin
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PluginPermissionHostTest {
    private val plugin = mockk<Plugin>(relaxed = true)
    private val launched = mutableListOf<List<String>>()
    private var pendingDone: (() -> Unit)? = null
    private val launcher = PermissionRequestLauncher { aliases, onDone ->
        launched += aliases
        pendingDone = onDone
    }
    private val everything: (String) -> Boolean = { true }

    @Test
    fun `activity comes from the plugin, or null without a bridge or while finishing`() {
        val activity = mockk<AppCompatActivity>(relaxed = true)
        every { plugin.activity } returns activity
        assertSame(activity, PluginPermissionHost(plugin).activity)

        every { activity.isFinishing } returns true
        assertNull(PluginPermissionHost(plugin).activity)

        every { plugin.activity } throws NullPointerException("no bridge")
        assertNull(PluginPermissionHost(plugin).activity)
    }

    @Test
    fun `known aliases are launched and onDone waits for the permission callback`() {
        var done = 0
        val host = PluginPermissionHost(plugin, launcher, everything)

        host.requestAliases(listOf("location", "notifications", "location", "camera")) { done++ }

        assertEquals(listOf(listOf("location", "notifications")), launched)
        assertEquals(0, done)
        pendingDone!!()
        assertEquals(1, done)
    }

    @Test
    fun `aliases whose permissions are not in the manifest are skipped`() {
        var done = 0
        val host = PluginPermissionHost(plugin, launcher) { it != "activityRecognition" }

        host.requestAliases(listOf("location", "activityRecognition")) { done++ }
        host.requestAliases(listOf("activityRecognition")) { done++ }

        assertEquals(listOf(listOf("location")), launched)
        assertEquals("only the request with nothing to ask completed", 1, done)
    }

    @Test
    fun `nothing to launch completes immediately`() {
        var done = 0

        PluginPermissionHost(plugin, launcher, everything).requestAliases(listOf("camera")) { done++ }
        PluginPermissionHost(plugin, launcher, everything).requestAliases(emptyList()) { done++ }
        PluginPermissionHost(plugin, isRequestable = everything).requestAliases(listOf("location")) { done++ }
        PluginPermissionHost(plugin, launcher).requestAliases(listOf("location")) { done++ } // no annotation on a mock

        assertEquals(4, done)
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `a failing launch still completes`() {
        var done = 0
        val failing = PermissionRequestLauncher { _, _ -> throw IllegalStateException("no launcher registered") }

        PluginPermissionHost(plugin, failing, everything).requestAliases(listOf("backgroundLocation")) { done++ }

        assertEquals(1, done)
    }

    @Test
    fun `manifest check uses the plugin annotation and the app manifest`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val real = LocationTrackingPlugin().apply { bridge = mockk<Bridge> { every { context } returns app } }

        for (type in PermissionType.entries) {
            assertTrue(type.alias, PluginPermissionHost.declaredInManifest(real, type.alias))
        }
        assertFalse(PluginPermissionHost.declaredInManifest(real, "camera"))

        @Suppress("DEPRECATION")
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).requestedPermissions =
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

        assertTrue(PluginPermissionHost.declaredInManifest(real, "location"))
        assertFalse(PluginPermissionHost.declaredInManifest(real, "notifications"))
    }

    @Test
    fun `manifest check is false without a bridge`() {
        assertFalse(PluginPermissionHost.declaredInManifest(LocationTrackingPlugin(), "location"))
    }
}
