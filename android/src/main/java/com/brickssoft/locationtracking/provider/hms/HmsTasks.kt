package com.brickssoft.locationtracking.provider.hms

import com.brickssoft.locationtracking.core.TrackingException
import com.huawei.hmf.tasks.Task
import com.huawei.hmf.tasks.TaskExecutors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspends until this HMS task completes and returns its result (which may be null for `Task<Void>` or an absent
 * last location).
 *
 * - A failed task rethrows its exception unchanged (usually `com.huawei.hms.common.ApiException`).
 * - A task canceled by HMS throws [HmsTaskCanceledException], deliberately not a [CancellationException]: the
 *   caller's coroutine was not cancelled, so the failure must not be mistaken for cancellation.
 * - Cancelling the coroutine stops waiting; HMS tasks cannot be cancelled, so a late result is ignored.
 *
 * Listeners run on the completing thread ([TaskExecutors.immediate]); the continuation then resumes on the
 * caller's dispatcher.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    val executor = TaskExecutors.immediate()
    addOnSuccessListener(executor) { result -> cont.resume(result) }
    addOnFailureListener(executor) { e -> cont.resumeWithException(e) }
    addOnCanceledListener(executor) { cont.resumeWithException(HmsTaskCanceledException()) }
}

/** An HMS task was canceled by the SDK; [hmsCall] maps it to `UNAVAILABLE`. */
internal class HmsTaskCanceledException : Exception("HMS task was canceled")

/**
 * Runs [block] and converts every failure except coroutine cancellation into a [TrackingException]
 * (see [toTrackingException]). [operation] names the HMS call in the message.
 */
internal inline fun <T> hmsCall(operation: String, block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw e.toTrackingException(operation)
    }
