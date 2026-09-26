package com.brickssoft.locationtracking.core

import java.util.concurrent.CopyOnWriteArrayList

/** Handle returned by [EventBus.subscribe]. */
fun interface Subscription {
    fun cancel()
}

/** In-process event bus. Delivery is synchronous, on the emitter's thread. */
interface EventBus {
    fun emit(event: TrackingEvent)

    fun subscribe(listener: (TrackingEvent) -> Unit): Subscription
}

/** Default [EventBus]. A throwing listener is logged and does not affect other listeners. */
class SimpleEventBus : EventBus {
    private val listeners = CopyOnWriteArrayList<(TrackingEvent) -> Unit>()

    override fun emit(event: TrackingEvent) {
        for (listener in listeners) {
            try {
                listener(event)
            } catch (e: Exception) {
                Logger.e(TAG, "event listener failed for ${event::class.simpleName}", e)
            }
        }
    }

    override fun subscribe(listener: (TrackingEvent) -> Unit): Subscription {
        listeners.add(listener)
        return Subscription { listeners.remove(listener) }
    }

    private companion object {
        const val TAG = "LT.EventBus"
    }
}
