// STUB — owned by Unit 16 (Positions). Replace this implementation.
package com.brickssoft.locationtracking.position

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.permission.PermissionManager
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.record.RecordSink
import kotlinx.coroutines.CoroutineScope

/** One-shot and watched positions. Stub: returns a record without a location; watches never fire. */
@Suppress("unused")
class DefaultPositionService(
    private val providers: ProviderFactory,
    private val configStore: ConfigStore,
    private val permissions: PermissionManager,
    private val device: DeviceMonitor,
    private val recordFactory: RecordFactory,
    private val recordSink: RecordSink,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : PositionService {
    override suspend fun getCurrentPosition(o: CurrentPositionOptions): Record =
        recordFactory.create(RecordEvent.CURRENT_POSITION, null, o.extras)

    override fun watchPosition(id: String, o: WatchPositionOptions, callback: (Record?, TrackingException?) -> Unit) =
        Unit

    override fun clearWatch(id: String): Boolean = false

    override fun clearAllWatches() = Unit
}
