package com.brickssoft.locationtracking.api

import androidx.annotation.VisibleForTesting
import com.brickssoft.locationtracking.core.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The one background thread of the companion native API, named [LocationTrackingNative.THREAD_NAME] (`LT-native`).
 *
 * Every [LocationTrackingListener] call and every [NativeCallback] runs here, one task at a time, in the order the
 * tasks were posted. Because there is only one thread for all listeners and all callbacks, the order is global: a
 * record posted before an event is delivered before that event, to every listener.
 *
 * The thread is a daemon thread (it never keeps the JVM alive) and exists for the whole process once created. The
 * queue has no size limit, so nothing is dropped; a listener that blocks this thread delays every later delivery.
 */
internal object NativeThread {
    private const val TAG = "LT.Native"

    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { runnable ->
        Thread(runnable, LocationTrackingNative.THREAD_NAME).apply { isDaemon = true }
    }

    /** Queue length at which (and at every further multiple of which) a backlog warning is logged. */
    const val BACKLOG_WARNING = 1_000

    /** Queues [task] for the `LT-native` thread. [task] must catch its own exceptions. */
    fun post(task: Runnable) {
        try {
            executor.execute(task)
        } catch (e: RejectedExecutionException) {
            // Only possible if the executor was shut down, which this object never does.
            Logger.e(TAG, "the ${LocationTrackingNative.THREAD_NAME} executor rejected a task", e)
            return
        }
        val queued = executor.queue.size
        if (queued >= BACKLOG_WARNING && queued % BACKLOG_WARNING == 0) {
            // Nothing is dropped; this only makes a blocked listener or callback visible in the log.
            Logger.w(TAG, "$queued tasks wait for the ${LocationTrackingNative.THREAD_NAME} thread: a native listener or callback is blocking it")
        }
    }

    /**
     * Test hook: waits until every task queued before this call has run. Returns false on timeout. Must not be
     * called from the `LT-native` thread itself (it would wait for itself).
     */
    @VisibleForTesting
    fun awaitIdle(timeoutMs: Long = 15_000L): Boolean {
        val done = CountDownLatch(1)
        post { done.countDown() }
        return done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
