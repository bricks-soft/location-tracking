// STUB — owned by Unit 11 (HTTP). Replace this implementation.
package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.data.LocationStore
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.model.Record
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/** OkHttp uploader. Stub: uploads nothing. */
@Suppress("unused")
class OkHttpSyncer(
    private val configStore: ConfigStore,
    private val locationStore: LocationStore,
    private val device: DeviceMonitor,
    private val events: EventBus,
    private val clock: Clock,
    private val http: OkHttpClient,
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope,
) : HttpSyncer {
    override fun start() = Unit

    override fun onRecordInserted(record: Record) = Unit

    override suspend fun sync(): List<Record> = emptyList()
}
