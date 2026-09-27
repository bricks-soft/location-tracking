package com.brickssoft.locationtracking.provider.gms

import com.brickssoft.locationtracking.core.Logger
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Runs Task listeners on the thread that completes the task; the coroutine then resumes on its own dispatcher. */
internal object DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

/**
 * Suspends until this Task completes, without the play-services coroutines dependency.
 *
 * - Success resumes with the result (which may be null, e.g. no last location or `Task<Void>`).
 * - Failure rethrows the Task's exception unchanged.
 * - A cancelled Task throws [CancellationException].
 * - Cancelling the calling coroutine cancels [cancellationTokenSource], if given, so GMS stops the work.
 */
internal suspend fun <T> Task<T>.await(cancellationTokenSource: CancellationTokenSource? = null): T {
    if (isComplete) return completedResult()
    return suspendCancellableCoroutine { cont ->
        addOnCompleteListener(DirectExecutor) { task ->
            val error = task.exception
            when {
                error != null -> cont.resumeWithException(error)
                task.isCanceled -> cont.cancel()
                else -> cont.resume(task.result)
            }
        }
        if (cancellationTokenSource != null) {
            cont.invokeOnCancellation { cancellationTokenSource.cancel() }
        }
    }
}

private fun <T> Task<T>.completedResult(): T {
    exception?.let { throw it }
    if (isCanceled) throw CancellationException("Task ${this::class.java.name} was cancelled")
    return result
}

/** Adds a listener that only logs a failure of a fire-and-forget Task. Never throws. */
internal fun Task<*>?.logFailure(tag: String, what: String) {
    try {
        this?.addOnFailureListener(DirectExecutor) { e -> Logger.w(tag, "$what failed", e) }
    } catch (e: Exception) {
        Logger.w(tag, "$what: cannot observe result", e)
    }
}
