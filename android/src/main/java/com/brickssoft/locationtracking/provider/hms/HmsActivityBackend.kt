package com.brickssoft.locationtracking.provider.hms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.ActivityBackend
import com.huawei.hmf.tasks.TaskExecutors
import com.huawei.hms.location.ActivityIdentification
import com.huawei.hms.location.ActivityIdentificationService

/**
 * [ActivityBackend] over HMS activity identification. Results arrive as broadcasts to [HmsActivityReceiver] through
 * [HmsPendingIntents.activity].
 *
 * `start` returns false when `ACTIVITY_RECOGNITION` is missing (API 29+) or the request cannot be submitted; an
 * asynchronous HMS failure is only logged. The service is created lazily, on first use.
 */
internal class HmsActivityBackend(
    private val context: Context,
    serviceProvider: () -> ActivityIdentificationService = { ActivityIdentification.getService(context) },
) : ActivityBackend {
    override val kind: ProviderKind = ProviderKind.HMS
    override val isSupported: Boolean = true

    private val service: ActivityIdentificationService by lazy(serviceProvider)

    override fun start(intervalMs: Long): Boolean {
        if (!hasActivityPermission()) {
            Logger.w(TAG, "activity identification not started: ACTIVITY_RECOGNITION permission is missing")
            return false
        }
        return try {
            service.createActivityIdentificationUpdates(intervalMs.coerceAtLeast(0L), HmsPendingIntents.activity(context))
                .addOnFailureListener(TaskExecutors.immediate()) { e ->
                    Logger.w(TAG, "createActivityIdentificationUpdates failed: ${describeHmsError(e)}", e)
                }
            true
        } catch (e: Exception) {
            Logger.e(TAG, "createActivityIdentificationUpdates failed: ${describeHmsError(e)}", e)
            false
        }
    }

    override fun stop() {
        try {
            service.deleteActivityIdentificationUpdates(HmsPendingIntents.activity(context))
                .addOnFailureListener(TaskExecutors.immediate()) { e ->
                    Logger.d(TAG, "deleteActivityIdentificationUpdates failed: ${describeHmsError(e)}")
                }
        } catch (e: Exception) {
            Logger.w(TAG, "deleteActivityIdentificationUpdates failed: ${describeHmsError(e)}", e)
        }
    }

    /**
     * Below API 29 the permission is `com.huawei.hms.permission.ACTIVITY_RECOGNITION`, which the plugin treats as
     * granted (architecture §7.6); HMS reports a real denial through the task, which is logged.
     */
    private fun hasActivityPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "LT.HmsActivity"
    }
}
