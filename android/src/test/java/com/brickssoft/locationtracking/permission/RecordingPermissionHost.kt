package com.brickssoft.locationtracking.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import org.robolectric.Shadows.shadowOf

/**
 * [PermissionHost] for tests: records every requested alias group, lets [answer] simulate the user (for
 * example grant via [grant]), then calls back — posted to the main looper like Capacitor's
 * `@PermissionCallback`, or synchronously when [async] is false.
 */
internal class RecordingPermissionHost(
    override var activity: Activity?,
    private val async: Boolean = true,
    private val answer: (alias: String) -> Unit = {},
) : PermissionHost {
    val requested = mutableListOf<List<String>>()

    /** Times the host invokes each callback (to simulate a misbehaving host). */
    var callbacksPerRequest = 1

    /** If set, `requestAliases` throws this after recording the request. */
    var throwOnRequest: RuntimeException? = null

    override fun requestAliases(aliases: List<String>, onDone: () -> Unit) {
        requested += aliases
        throwOnRequest?.let { throw it }
        aliases.forEach(answer)
        repeat(callbacksPerRequest) {
            if (async) Handler(Looper.getMainLooper()).post(onDone) else onDone()
        }
    }

    companion object {
        /** Android permissions behind each alias of the `@CapacitorPlugin` annotation. */
        val PERMISSIONS: Map<String, Array<String>> = mapOf(
            "location" to arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            "backgroundLocation" to arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            "activityRecognition" to arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
            "notifications" to arrayOf(Manifest.permission.POST_NOTIFICATIONS),
        )

        /** An [answer] that grants everything requested. */
        fun grant(app: Application): (String) -> Unit = { alias ->
            shadowOf(app).grantPermissions(*PERMISSIONS.getValue(alias))
        }
    }
}
