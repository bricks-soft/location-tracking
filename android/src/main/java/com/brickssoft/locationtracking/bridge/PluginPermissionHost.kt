// STUB — owned by Unit 2 (Android bridge). Replace this implementation.
package com.brickssoft.locationtracking.bridge

import android.app.Activity
import com.brickssoft.locationtracking.permission.PermissionHost
import com.getcapacitor.Plugin

/** [PermissionHost] on top of the Capacitor plugin. Stub: requests nothing and completes immediately. */
class PluginPermissionHost(private val plugin: Plugin) : PermissionHost {
    override val activity: Activity? get() = runCatching { plugin.activity }.getOrNull()

    override fun requestAliases(aliases: List<String>, onDone: () -> Unit) = onDone()
}
