// STUB — owned by Unit 9 (Processing). Replace this implementation.
package com.brickssoft.locationtracking.processing

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.model.TrackedLocation

/** Odometer persisted in runtime state. Stub: in-memory value, ignores locations. */
@Suppress("unused")
class DefaultOdometer(private val configStore: ConfigStore) : Odometer {
    @Volatile
    private var current: Double = 0.0

    override val value: Double get() = current

    override fun onLocation(location: TrackedLocation) = Unit

    override fun set(value: Double) {
        current = value
    }

    override fun reset() {
        current = 0.0
    }
}
