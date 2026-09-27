package com.brickssoft.locationtracking.position

import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.DesiredAccuracy
import com.brickssoft.locationtracking.model.Record

/**
 * @property timeoutMs null = `geolocation.locationTimeout`.
 * @property extras JSON object text.
 */
data class CurrentPositionOptions(
    val samples: Int = 3,
    val timeoutMs: Long? = null,
    val maximumAgeMs: Long = 0,
    val desiredAccuracy: DesiredAccuracy = DesiredAccuracy.HIGH,
    val persist: Boolean = true,
    val extras: String? = null,
)

/** @property extras JSON object text. */
data class WatchPositionOptions(
    val intervalMs: Long = 1000,
    val desiredAccuracy: DesiredAccuracy = DesiredAccuracy.HIGH,
    val persist: Boolean = false,
    val extras: String? = null,
)

interface PositionService {
    /** @throws TrackingException PERMISSION_DENIED, LOCATION_DISABLED, TIMEOUT, ... */
    suspend fun getCurrentPosition(o: CurrentPositionOptions): Record

    /** [callback] receives either a record or an error, possibly many times, until [clearWatch]. */
    fun watchPosition(id: String, o: WatchPositionOptions, callback: (Record?, TrackingException?) -> Unit)

    /** Returns false if [id] was not watching. */
    fun clearWatch(id: String): Boolean

    fun clearAllWatches()
}
