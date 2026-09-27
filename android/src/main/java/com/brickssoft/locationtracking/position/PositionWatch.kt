package com.brickssoft.locationtracking.position

import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.provider.LocationBackend
import com.brickssoft.locationtracking.provider.LocationListener
import com.brickssoft.locationtracking.provider.LocationRequestSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * One active `watchPosition`. The backend listener only enqueues fixes; a single consumer coroutine on the
 * given scope turns each fix into a record (through [toRecord], which may persist it) and invokes [callback],
 * so deliveries keep the fix order and never run on the backend's thread.
 *
 * After [stop] returns, the listener is removed, queued fixes are dropped and [callback] is never invoked again
 * (a callback that is running when [stop] is called finishes first); a record that is already being persisted
 * finishes persisting. At most [BUFFER] fixes wait for the consumer; beyond that the oldest are dropped.
 * [start] and [stop] are thread-safe and idempotent.
 *
 * @param toRecord returns null to skip a fix (mock rule).
 */
internal class PositionWatch(
    val id: String,
    private val backend: LocationBackend,
    private val spec: LocationRequestSpec,
    private val callback: (Record?, TrackingException?) -> Unit,
    private val toRecord: suspend (TrackedLocation) -> Record?,
) {
    /** Guards [started], [stopped], [registered] and every [callback] invocation. */
    private val lock = Any()
    private val fixes = Channel<TrackedLocation>(BUFFER, BufferOverflow.DROP_OLDEST)
    private val listener = LocationListener { batch -> batch.forEach { fixes.trySend(it) } }

    @Volatile
    private var stopped = false
    private var started = false
    private var registered = false

    /**
     * Starts the consumer on [scope] and registers the backend listener. Returns the error if the backend refused
     * (the watch is then stopped and [callback] is not invoked); null on success or if already started/stopped.
     */
    fun start(scope: CoroutineScope): TrackingException? {
        synchronized(lock) {
            if (stopped || started) return null
            started = true
            scope.launch { consume() }
            try {
                backend.requestUpdates(spec, listener)
                registered = true
                Logger.d(DefaultPositionService.TAG, "watch $id started (${spec.accuracy.wire}, ${spec.intervalMs}ms)")
                return null
            } catch (e: Exception) {
                stopped = true
                fixes.close()
                // A backend may have registered before failing.
                DefaultPositionService.removeQuietly(backend, listener)
                return DefaultPositionService.backendError(e)
            }
        }
    }

    /** Removes the backend listener and ends deliveries. */
    fun stop() {
        val wasRegistered = synchronized(lock) {
            if (stopped) return
            stopped = true
            registered.also { registered = false }
        }
        fixes.close()
        if (wasRegistered) DefaultPositionService.removeQuietly(backend, listener)
        Logger.d(DefaultPositionService.TAG, "watch $id cleared")
    }

    private suspend fun consume() {
        for (fix in fixes) {
            if (stopped) break
            val record = try {
                toRecord(fix) ?: continue
            } catch (e: Exception) {
                // A stray CancellationException from a collaborator is a failure; our own cancellation propagates.
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                Logger.e(DefaultPositionService.TAG, "watch $id: failed to build record", e)
                deliver(null, TrackingException(ErrorCode.INTERNAL, "Failed to record watched position: ${e.message}", e))
                continue
            }
            deliver(record, null)
        }
    }

    private fun deliver(record: Record?, error: TrackingException?) {
        synchronized(lock) {
            if (stopped) return
            try {
                callback(record, error)
            } catch (e: Exception) {
                Logger.e(DefaultPositionService.TAG, "watch $id callback failed", e)
            }
        }
    }

    private companion object {
        /** Fixes that may wait for the consumer (a slow sink); older ones are dropped first. */
        const val BUFFER = 64
    }
}
