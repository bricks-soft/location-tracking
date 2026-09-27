package com.brickssoft.locationtracking.bridge

import android.app.Activity
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.permission.PermissionHost
import com.brickssoft.locationtracking.permission.PermissionType
import com.getcapacitor.Plugin
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.util.PermissionHelper

/**
 * Starts a Capacitor permission request for plugin permission aliases and calls `onDone` from the plugin's
 * `@PermissionCallback`. Implemented by `LocationTrackingPlugin` per call, because `requestPermissionForAliases`
 * is protected.
 */
fun interface PermissionRequestLauncher {
    fun launch(aliases: List<String>, onDone: () -> Unit)
}

/**
 * [PermissionHost] on top of the Capacitor plugin.
 *
 * [requestAliases] forwards the known aliases whose Android permissions are declared in the app manifest to
 * [launcher]. It completes immediately (nothing to ask) when no alias is left, when there is no [launcher], or when
 * the launch fails; otherwise [launcher] calls `onDone` from the permission callback, on the main thread.
 * Undeclared permissions are skipped because Capacitor would reject the call without ever calling back.
 *
 * @param isRequestable whether an alias can be requested; default: all its permissions are in the app manifest.
 */
class PluginPermissionHost(
    private val plugin: Plugin,
    private val launcher: PermissionRequestLauncher? = null,
    private val isRequestable: (alias: String) -> Boolean = { declaredInManifest(plugin, it) },
) : PermissionHost {
    /** The plugin's activity, or null without a bridge or while it is finishing or destroyed. */
    override val activity: Activity? get() = usableActivity(plugin)

    override fun requestAliases(aliases: List<String>, onDone: () -> Unit) {
        val known = aliases.filter { PermissionType.fromAlias(it) != null }.distinct()
        if (known.size != aliases.distinct().size) Logger.w(TAG, "ignoring unknown permission aliases in $aliases")
        val requestable = known.filter(isRequestable)
        if (requestable.size != known.size) {
            Logger.w(TAG, "not requesting ${known - requestable.toSet()}: permissions missing from the app manifest")
        }
        val l = launcher
        if (requestable.isEmpty() || l == null) {
            onDone()
            return
        }
        try {
            l.launch(requestable, onDone)
        } catch (e: Exception) {
            Logger.e(TAG, "permission request for $requestable failed", e)
            onDone()
        }
    }

    internal companion object {
        private const val TAG = "LT.PermissionHost"

        /** The plugin's activity, or null without a bridge or while it is finishing or destroyed. */
        fun usableActivity(plugin: Plugin): Activity? =
            runCatching { plugin.activity }.getOrNull()?.takeUnless { it.isFinishing || it.isDestroyed }

        /** True if every Android permission of [alias] (per the `@CapacitorPlugin` annotation) is in the manifest. */
        fun declaredInManifest(plugin: Plugin, alias: String): Boolean {
            val strings = plugin.javaClass.getAnnotation(CapacitorPlugin::class.java)
                ?.permissions
                ?.firstOrNull { it.alias == alias }
                ?.strings
                ?: return false
            val context = runCatching { plugin.context }.getOrNull() ?: return false
            return PermissionHelper.hasDefinedPermissions(context, strings)
        }
    }
}
