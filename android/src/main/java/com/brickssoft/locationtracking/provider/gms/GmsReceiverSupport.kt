package com.brickssoft.locationtracking.provider.gms

import android.content.BroadcastReceiver
import android.content.Context
import com.brickssoft.locationtracking.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** Where a PendingIntent receiver delivers its parsed payload: normally `Components.get(ctx).scope` and a sink. */
internal class ReceiverTarget<T>(val scope: CoroutineScope, val sink: T)

/**
 * The system reports a broadcast ANR if `goAsync()` is not finished in time (10 s for foreground broadcasts), so
 * the broadcast is finished after this deadline even if the delivery is still running.
 */
internal const val RECEIVER_FINISH_DEADLINE_MS = 9_000L

/**
 * Called from `onReceive`: calls `goAsync()`, resolves the target and runs [deliver] with its sink in its scope.
 * The broadcast is always finished, also if the target cannot be resolved.
 */
internal fun <T> BroadcastReceiver.deliverAsync(
    context: Context,
    target: (Context) -> ReceiverTarget<T>,
    tag: String,
    what: String,
    deliver: suspend (T) -> Unit,
) {
    val pending = goAsync()
    try {
        val resolved = target(context)
        resolved.scope.launchReceiverWork(pending, tag, what) { deliver(resolved.sink) }
    } catch (e: Exception) {
        Logger.e(tag, "cannot start $what", e)
        try {
            pending?.finish()
        } catch (finishError: Exception) {
            Logger.w(tag, "$what: finishing the broadcast failed", finishError)
        }
    }
}

/**
 * Runs [block] in this scope for a receiver that called `goAsync()`. [pending] is finished exactly once: when the
 * work completes, fails or is cancelled (even if the scope was already cancelled), or after [deadlineMs] while the
 * work keeps running. Failures are logged.
 */
internal fun CoroutineScope.launchReceiverWork(
    pending: BroadcastReceiver.PendingResult?,
    tag: String,
    what: String,
    deadlineMs: Long = RECEIVER_FINISH_DEADLINE_MS,
    block: suspend () -> Unit,
): Job {
    val finished = AtomicBoolean(false)
    val finish = {
        if (finished.compareAndSet(false, true)) {
            try {
                pending?.finish()
            } catch (e: Exception) {
                Logger.w(tag, "$what: finishing the broadcast failed", e)
            }
        }
    }
    val job = launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(tag, "$what failed", e)
        }
    }
    val watchdog = launch {
        delay(deadlineMs)
        Logger.w(tag, "$what still running after $deadlineMs ms; finishing the broadcast early")
        finish()
    }
    job.invokeOnCompletion {
        watchdog.cancel()
        finish()
    }
    return job
}
