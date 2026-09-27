package com.brickssoft.locationtracking.testing

import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.processing.FilterResult
import com.brickssoft.locationtracking.processing.LocationProcessor
import com.brickssoft.locationtracking.processing.Odometer
import java.util.concurrent.CopyOnWriteArrayList

/** Accepts every fix unless a result is queued in [scripted] (consumed in order) or [rejectAll] is set. */
class FakeLocationProcessor : LocationProcessor {
    val processed = CopyOnWriteArrayList<Pair<TrackedLocation, Boolean>>()
    val scripted = ArrayDeque<FilterResult>()

    @Volatile
    var rejectAll: String? = null

    @Volatile
    var resetCalls = 0

    override fun process(raw: TrackedLocation, isMoving: Boolean): FilterResult {
        processed += raw to isMoving
        rejectAll?.let { return FilterResult.Rejected(it) }
        return synchronized(scripted) { scripted.removeFirstOrNull() } ?: FilterResult.Accepted(raw)
    }

    override fun reset() {
        resetCalls++
    }
}

/** [Odometer] that adds [increment] per location (default 0) and records the locations. */
class FakeOdometer(initial: Double = 0.0) : Odometer {
    @Volatile
    private var current = initial
    val locations = CopyOnWriteArrayList<TrackedLocation>()

    @Volatile
    var increment = 0.0

    override val value: Double get() = current

    override fun onLocation(location: TrackedLocation) {
        locations += location
        current += increment
    }

    override fun set(value: Double) {
        current = value
    }

    override fun reset() {
        current = 0.0
    }
}
