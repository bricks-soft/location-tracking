package com.brickssoft.locationtracking.core

import com.brickssoft.locationtracking.model.Record
import java.util.concurrent.CopyOnWriteArrayList

/** Observes every record queued for upload. See [RecordHooks]. */
fun interface RecordObserver {
    fun onRecordQueued(record: Record)
}

/**
 * Fan-out of every queued record to in-process observers (SCAFFOLD, round 2). The companion native API
 * (`api/NativeListeners`, unit 5) registers the observer that forwards records to `LocationTrackingListener.onRecord`.
 *
 * Callers (unit 5 adds the calls):
 * - `DefaultRecordSink.submit`, right after the insert (also when the insert failed: a companion listener keeps its
 *   own audit trail, so a local database failure must not hide the record from it);
 * - the bridge's `insertLocation`, right after its insert (inserted records bypass the sink).
 *
 * [dispatch] is synchronous on the caller's thread (the engine or an I/O coroutine), so observers must not block: they
 * hand the record off to their own thread. A throwing observer is logged and does not affect the others.
 */
class RecordHooks {
    private val observers = CopyOnWriteArrayList<RecordObserver>()

    fun add(observer: RecordObserver): Subscription {
        observers.add(observer)
        return Subscription { observers.remove(observer) }
    }

    /** True when at least one observer is registered (lets callers skip work). */
    val hasObservers: Boolean get() = observers.isNotEmpty()

    fun dispatch(record: Record) {
        for (observer in observers) {
            try {
                observer.onRecordQueued(record)
            } catch (e: Exception) {
                Logger.e(TAG, "record observer failed for ${record.event.wire} ${record.uuid}", e)
            }
        }
    }

    private companion object {
        const val TAG = "LT.RecordHooks"
    }
}
