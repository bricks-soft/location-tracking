// STUB — owned by Unit 9 (Processing). Replace this implementation.
package com.brickssoft.locationtracking.processing

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.model.TrackedLocation

/** Location filters and smoothing. Stub: accepts every fix unchanged. */
@Suppress("unused")
class DefaultLocationProcessor(private val configStore: ConfigStore) : LocationProcessor {
    override fun process(raw: TrackedLocation, isMoving: Boolean): FilterResult = FilterResult.Accepted(raw)

    override fun reset() = Unit
}
