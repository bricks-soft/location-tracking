package com.brickssoft.locationtracking.provider.gms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ActivitySample
import com.brickssoft.locationtracking.model.ActivityType
import com.brickssoft.locationtracking.provider.ActivitySink
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Receives `ActivityRecognitionResult`s from the PendingIntent of [GmsActivityBackend] and delivers them to the
 * engine ([ActivitySink]) with `goAsync()` on `Components.scope`.
 */
class GmsActivityReceiver internal constructor(
    private val target: (Context) -> ReceiverTarget<ActivitySink>,
) : BroadcastReceiver() {
    constructor() : this({ ctx -> Components.get(ctx).let { ReceiverTarget(it.scope, it.engine) } })

    override fun onReceive(context: Context, intent: Intent) {
        val samples = parseActivitySamples(intent)
        if (samples.isEmpty()) return
        Logger.v(TAG, "activity: $samples")
        deliverAsync(context, target, TAG, "activity delivery") { it.onActivitySamples(samples) }
    }

    internal companion object {
        private const val TAG = "LT.GmsActivity"

        /** The samples in [intent], most probable first; empty if it carries no result. */
        fun parseActivitySamples(intent: Intent?): List<ActivitySample> {
            val result = try {
                intent?.let { ActivityRecognitionResult.extractResult(it) }
            } catch (e: Exception) {
                Logger.w(TAG, "unreadable activity result", e)
                null
            } ?: return emptyList()
            return toActivitySamples(result.probableActivities.orEmpty())
        }

        /** Maps GMS activities, keeping the highest confidence per [ActivityType], sorted by confidence descending. */
        fun toActivitySamples(activities: List<DetectedActivity>): List<ActivitySample> = activities
            .map { ActivitySample(mapActivityType(it.type), it.confidence.coerceIn(0, 100)) }
            .groupBy { it.type }
            .map { (_, samples) -> samples.maxBy { it.confidence } }
            .sortedByDescending { it.confidence }

        /** TILTING, UNKNOWN and any future type map to [ActivityType.UNKNOWN]. */
        fun mapActivityType(type: Int): ActivityType = when (type) {
            DetectedActivity.IN_VEHICLE -> ActivityType.IN_VEHICLE
            DetectedActivity.ON_BICYCLE -> ActivityType.ON_BICYCLE
            DetectedActivity.ON_FOOT -> ActivityType.ON_FOOT
            DetectedActivity.WALKING -> ActivityType.WALKING
            DetectedActivity.RUNNING -> ActivityType.RUNNING
            DetectedActivity.STILL -> ActivityType.STILL
            else -> ActivityType.UNKNOWN
        }
    }
}
