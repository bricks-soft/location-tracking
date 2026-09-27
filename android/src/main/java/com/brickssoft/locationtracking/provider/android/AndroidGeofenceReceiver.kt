package com.brickssoft.locationtracking.provider.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.Components
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.GeofenceTransitionSink
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Delivers `LocationManager` proximity alerts (see [AndroidGeofenceBackend]) to the GeofenceManager.
 *
 * The id comes from the data URI `lt-geofence://<id>` and the action from [LocationManager.KEY_PROXIMITY_ENTERING].
 * The alert carries no fix, so the transition gets the freshest last known location if it is at most
 * [MAX_TRIGGER_FIX_AGE_MS] old, else null.
 */
class AndroidGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val transition = parse(intent) ?: return
        val components = Components.get(context)
        val sink = components.geofences
        val pending = goAsync()
        deliver(transition, components.scope, sink, { recentLastKnown(components.context, components.clock) }) {
            pending.finish()
        }
    }

    internal companion object {
        private const val TAG = "LT.AndroidGeofenceRx"

        /** A last known fix older than this is not attached to a transition. */
        const val MAX_TRIGGER_FIX_AGE_MS = 2 * 60_000L

        /**
         * The transition described by a proximity-alert [intent] (location = null), or null if the intent is not one
         * of ours or its action was not requested for the region.
         */
        fun parse(intent: Intent): OsGeofenceTransition? {
            val id = idFrom(intent.data) ?: return null
            if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return null
            val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
            val wantedExtra = if (entering) {
                AndroidGeofenceBackend.EXTRA_NOTIFY_ENTRY
            } else {
                AndroidGeofenceBackend.EXTRA_NOTIFY_EXIT
            }
            if (!intent.getBooleanExtra(wantedExtra, true)) return null
            return OsGeofenceTransition(id, if (entering) GeofenceAction.ENTER else GeofenceAction.EXIT, null)
        }

        /** Decodes `<id>` of [Constants.geofenceUri]. */
        fun idFrom(uri: Uri?): String? {
            if (uri == null || uri.scheme != Constants.GEOFENCE_URI_SCHEME) return null
            val encoded = uri.encodedSchemeSpecificPart?.removePrefix("//") ?: return null
            return Uri.decode(encoded).takeIf { it.isNotEmpty() }
        }

        /**
         * Adds the location from [locate] (null if it fails) and hands [transition] to [sink] on [scope].
         * [finish] runs exactly once when the job completes, even if [scope] is already cancelled.
         */
        fun deliver(
            transition: OsGeofenceTransition,
            scope: CoroutineScope,
            sink: GeofenceTransitionSink,
            locate: () -> TrackedLocation?,
            finish: () -> Unit,
        ): Job {
            val job = scope.launch {
                try {
                    val location = try {
                        locate()
                    } catch (e: Exception) {
                        Logger.w(TAG, "no last known location for geofence ${transition.id}", e)
                        null
                    }
                    sink.onGeofenceTransitions(listOf(transition.copy(location = location)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "failed to deliver ${transition.action} of geofence ${transition.id}", e)
                }
            }
            job.invokeOnCompletion { finish() }
            return job
        }

        /** True if [fix] is at most [MAX_TRIGGER_FIX_AGE_MS] old (elapsed-realtime clock when the fix carries it). */
        fun isRecent(fix: TrackedLocation, clock: Clock): Boolean {
            val ageMs = if (fix.elapsedRealtimeNanos > 0) {
                clock.elapsedRealtime() - fix.elapsedRealtimeNanos / 1_000_000
            } else {
                clock.now() - fix.time
            }
            return ageMs <= MAX_TRIGGER_FIX_AGE_MS
        }

        private fun recentLastKnown(context: Context, clock: Clock): TrackedLocation? {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val fix = LocationProviders.freshestLastKnown(lm)?.let(TrackedLocation::from) ?: return null
            return fix.takeIf { isRecent(it, clock) }
        }
    }
}
