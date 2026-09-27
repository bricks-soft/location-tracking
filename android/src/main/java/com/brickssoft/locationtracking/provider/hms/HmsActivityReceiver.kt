package com.brickssoft.locationtracking.provider.hms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.huawei.hms.location.ActivityIdentificationData
import com.huawei.hms.location.ActivityIdentificationResponse

/**
 * Delivers HMS activity-identification results to the engine (`Components.get(ctx).engine`, an `ActivitySink`).
 * Declared in the manifest; it only fires for the PendingIntent registered by [HmsActivityBackend].
 */
class HmsActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val samples = parseActivitySamples(intent)
        if (samples.isEmpty()) return
        val components = Components.get(context)
        deliverAsync(goAsync(), components.scope, TAG, "activity samples") {
            components.engine.onActivitySamples(samples)
        }
    }

    internal companion object {
        private const val TAG = "LT.HmsActivityReceiver"

        /** Samples in [intent], most probable first; empty if it carries no HMS activity result. */
        fun parseActivitySamples(intent: Intent?): List<ActivitySample> {
            val response = try {
                if (intent == null) null else ActivityIdentificationResponse.getDataFromIntent(intent)
            } catch (e: Exception) {
                Logger.w(TAG, "unreadable activity identification result", e)
                null
            } ?: return emptyList()
            return response.activityIdentificationDatas.orEmpty()
                .mapNotNull { data -> data?.let(::toActivitySample) }
                .sortedByDescending { it.confidence }
        }

        /** Maps one HMS identification; unknown types become [ActivityType.UNKNOWN]. Confidence is clamped to 0..100. */
        fun toActivitySample(data: ActivityIdentificationData): ActivitySample =
            ActivitySample(activityType(data.identificationActivity), data.possibility.coerceIn(0, 100))

        fun activityType(hmsType: Int): ActivityType = when (hmsType) {
            ActivityIdentificationData.VEHICLE -> ActivityType.IN_VEHICLE
            ActivityIdentificationData.BIKE -> ActivityType.ON_BICYCLE
            ActivityIdentificationData.FOOT -> ActivityType.ON_FOOT
            ActivityIdentificationData.WALKING -> ActivityType.WALKING
            ActivityIdentificationData.RUNNING -> ActivityType.RUNNING
            ActivityIdentificationData.STILL -> ActivityType.STILL
            else -> ActivityType.UNKNOWN // OTHERS and types added by newer HMS versions
        }
    }
}
