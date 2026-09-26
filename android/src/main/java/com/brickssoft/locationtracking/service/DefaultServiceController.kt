// STUB — owned by Unit 8 (Service). Replace this implementation.
package com.brickssoft.locationtracking.service

import android.content.Context
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.EventBus

/** Starts / stops [LocationTrackingService]. Stub: never starts anything. */
@Suppress("unused")
class DefaultServiceController(
    private val context: Context,
    private val configStore: ConfigStore,
    private val events: EventBus,
) : ServiceController {
    override val isRunning: Boolean get() = false

    override fun start(): Boolean = false

    override fun stop() = Unit

    override fun refreshNotification() = Unit
}
