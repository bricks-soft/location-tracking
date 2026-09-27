package com.brickssoft.locationtracking.testing

import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.core.Subscription
import com.brickssoft.locationtracking.core.TrackingEvent
import java.util.concurrent.CopyOnWriteArrayList

/** [EventBus] that records every emitted event and also delivers to subscribers (synchronously). */
class RecordingEventBus : EventBus {
    private val recorded = CopyOnWriteArrayList<TrackingEvent>()
    private val listeners = CopyOnWriteArrayList<(TrackingEvent) -> Unit>()

    /** Every emitted event, in order. */
    val events: List<TrackingEvent> get() = recorded.toList()

    override fun emit(event: TrackingEvent) {
        recorded.add(event)
        for (listener in listeners) listener(event)
    }

    override fun subscribe(listener: (TrackingEvent) -> Unit): Subscription {
        listeners.add(listener)
        return Subscription { listeners.remove(listener) }
    }

    val subscriberCount: Int get() = listeners.size

    inline fun <reified T : TrackingEvent> ofType(): List<T> = events.filterIsInstance<T>()

    fun clear() = recorded.clear()
}
