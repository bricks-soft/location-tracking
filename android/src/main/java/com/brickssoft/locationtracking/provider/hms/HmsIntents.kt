package com.brickssoft.locationtracking.provider.hms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * PendingIntents handed to HMS. They are explicit broadcasts to our receivers and mutable (API 31+), because HMS
 * fills in the result extras. Creating one again returns the same PendingIntent, which is what the delete calls need.
 */
internal object HmsPendingIntents {
    fun activity(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        Constants.RC_HMS_ACTIVITY,
        Intent(context, HmsActivityReceiver::class.java).setAction(Constants.ACTION_ACTIVITY),
        Constants.piMutable(),
    )

    fun geofence(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        Constants.RC_HMS_GEOFENCE,
        Intent(context, HmsGeofenceReceiver::class.java).setAction(Constants.ACTION_GEOFENCE),
        Constants.piMutable(),
    )
}

/** Receivers must finish within about 10 s (architecture §7.12); the broadcast is released before that. */
internal const val BROADCAST_FINISH_DEADLINE_MS = 9_000L

/**
 * Runs [block] on [scope] for a broadcast kept alive with `goAsync()`. Failures are logged, never rethrown.
 *
 * [pending] is finished exactly once: when the job completes (including when it fails or is cancelled before it
 * starts), or after [finishAfterMs] if it is still running. In that case the work goes on without holding the
 * broadcast, so a slow sink cannot trigger a broadcast timeout.
 */
internal fun deliverAsync(
    pending: BroadcastReceiver.PendingResult?,
    scope: CoroutineScope,
    tag: String,
    what: String,
    finishAfterMs: Long = BROADCAST_FINISH_DEADLINE_MS,
    block: suspend () -> Unit,
): Job {
    val finished = AtomicBoolean(false)
    fun finishOnce() {
        if (!finished.compareAndSet(false, true)) return
        try {
            pending?.finish()
        } catch (e: Exception) {
            Logger.w(tag, "finishing the broadcast failed", e)
        }
    }

    val job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(tag, "failed to deliver $what", e)
        }
    }
    val deadline = scope.launch {
        delay(finishAfterMs)
        Logger.w(tag, "delivering $what takes longer than $finishAfterMs ms; releasing the broadcast")
        finishOnce()
    }
    job.invokeOnCompletion {
        deadline.cancel()
        finishOnce()
    }
    return job
}
