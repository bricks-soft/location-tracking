package com.brickssoft.locationtracking.permission

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import com.brickssoft.locationtracking.R
import com.brickssoft.locationtracking.config.BackgroundPermissionRationale
import com.brickssoft.locationtracking.core.Logger

/**
 * The rationale AlertDialog shown on API 30+ before background location is requested (which makes
 * Android open the app's location-permission settings page, where the user picks "Allow all the time").
 * Texts come from [BackgroundPermissionRationale]; blank or missing values fall back to the
 * `lt_bg_rationale_*` string resources.
 */
internal object BackgroundRationaleDialog {
    private const val TAG = "LT.Permissions"

    /** Resolved dialog texts. */
    data class Texts(val title: String, val message: String, val positive: String, val negative: String)

    fun texts(context: Context, rationale: BackgroundPermissionRationale): Texts = Texts(
        title = rationale.title.orResource(context, R.string.lt_bg_rationale_title),
        message = rationale.message.orResource(context, R.string.lt_bg_rationale_message),
        positive = rationale.positiveAction.orResource(context, R.string.lt_bg_rationale_positive),
        negative = rationale.negativeAction.orResource(context, R.string.lt_bg_rationale_negative),
    )

    /**
     * Shows the dialog on [activity] (main thread). [onResult] runs exactly once on the main thread: true for
     * the positive button; false for the negative button, back/outside cancel, any other dismissal, or the
     * Activity being destroyed while the dialog is up. Returns false (and never calls [onResult]) if the
     * dialog could not be shown.
     */
    fun show(activity: Activity, rationale: BackgroundPermissionRationale, onResult: (Boolean) -> Unit): Boolean {
        val application: Application? = activity.application
        var watcher: Application.ActivityLifecycleCallbacks? = null
        var resolved = false
        val resolve = { accepted: Boolean ->
            if (!resolved) {
                resolved = true
                watcher?.let { application?.unregisterActivityLifecycleCallbacks(it) }
                onResult(accepted)
            }
        }
        return try {
            val texts = texts(activity, rationale)
            val dialog = AlertDialog.Builder(activity)
                .setTitle(texts.title)
                .setMessage(texts.message)
                .setPositiveButton(texts.positive) { _, _ -> resolve(true) }
                .setNegativeButton(texts.negative) { _, _ -> resolve(false) }
                .setOnCancelListener { resolve(false) }
                .setOnDismissListener { resolve(false) }
                .create()
            // A destroyed Activity drops its windows without dismissing the dialog; never leave the caller hanging.
            val owner = activity
            val destroyWatcher = object : ActivityLifecycleAdapter() {
                override fun onActivityDestroyed(activity: Activity) {
                    if (activity !== owner) return
                    resolve(false)
                    try {
                        dialog.dismiss()
                    } catch (e: RuntimeException) {
                        Logger.d(TAG, "rationale dialog already gone", e)
                    }
                }
            }
            watcher = destroyWatcher
            application?.registerActivityLifecycleCallbacks(destroyWatcher)
            dialog.show()
            true
        } catch (e: RuntimeException) {
            // e.g. WindowManager.BadTokenException when the activity window is gone
            resolved = true
            watcher?.let { application?.unregisterActivityLifecycleCallbacks(it) }
            Logger.w(TAG, "cannot show the background-location rationale dialog", e)
            false
        }
    }

    private fun String?.orResource(context: Context, resId: Int): String =
        this?.takeIf { it.isNotBlank() } ?: context.getString(resId)
}
