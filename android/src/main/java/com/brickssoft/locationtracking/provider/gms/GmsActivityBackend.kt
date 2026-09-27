package com.brickssoft.locationtracking.provider.gms

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.ActivityBackend
import com.google.android.gms.location.ActivityRecognitionClient

/**
 * Activity recognition through `ActivityRecognitionClient.requestActivityUpdates`. Results arrive at
 * [GmsActivityReceiver] through a mutable, explicit broadcast PendingIntent.
 */
internal class GmsActivityBackend(
    private val context: Context,
    private val client: ActivityRecognitionClient,
) : ActivityBackend {
    override val kind: ProviderKind = ProviderKind.GMS
    override val isSupported: Boolean = true

    /**
     * Returns false if the ACTIVITY_RECOGNITION runtime permission is missing (API 29+) or GMS rejects the call
     * synchronously. The GMS Task completes later, so an asynchronous rejection can only be logged.
     */
    override fun start(intervalMs: Long): Boolean {
        if (!hasPermission()) {
            Logger.w(TAG, "activity updates not requested: ACTIVITY_RECOGNITION permission denied")
            return false
        }
        return try {
            client.requestActivityUpdates(intervalMs.coerceAtLeast(0), pendingIntent(context))
                .logFailure(TAG, "requestActivityUpdates")
            Logger.d(TAG, "activity updates requested every $intervalMs ms")
            true
        } catch (e: Exception) {
            Logger.w(TAG, "requestActivityUpdates failed", e)
            false
        }
    }

    /** Removes the updates if their PendingIntent exists (GMS keeps it alive while registered, across restarts). */
    override fun stop() {
        try {
            val pending = existingPendingIntent(context) ?: return
            client.removeActivityUpdates(pending).logFailure(TAG, "removeActivityUpdates")
        } catch (e: Exception) {
            Logger.w(TAG, "removeActivityUpdates failed", e)
        }
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "LT.GmsActivity"

        /** Explicit broadcast to [GmsActivityReceiver]; mutable because GMS fills in the result. */
        fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(context, Constants.RC_GMS_ACTIVITY, intent(context), Constants.piMutable())

        /** The PendingIntent of [pendingIntent] if it exists, without creating it. */
        fun existingPendingIntent(context: Context): PendingIntent? = PendingIntent.getBroadcast(
            context,
            Constants.RC_GMS_ACTIVITY,
            intent(context),
            Constants.piMutable() or PendingIntent.FLAG_NO_CREATE,
        )

        private fun intent(context: Context): Intent =
            Intent(context, GmsActivityReceiver::class.java).setAction(Constants.ACTION_ACTIVITY)
    }
}
