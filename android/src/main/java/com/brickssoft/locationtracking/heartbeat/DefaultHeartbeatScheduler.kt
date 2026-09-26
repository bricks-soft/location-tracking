// STUB — owned by Unit 12 (Heartbeat). Replace this implementation.
package com.brickssoft.locationtracking.heartbeat

import android.content.Context
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.data.LocationStore
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.model.HeartbeatStatus
import com.brickssoft.locationtracking.model.HeartbeatStrategy
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.record.RecordSink
import kotlinx.coroutines.CoroutineScope

/** Alarm-based heartbeat scheduler. Stub: schedules nothing; status reports DISABLED. */
@Suppress("unused")
class DefaultHeartbeatScheduler(
    private val context: Context,
    private val configStore: ConfigStore,
    private val device: DeviceMonitor,
    private val providers: ProviderFactory,
    private val locationStore: LocationStore,
    private val recordFactory: RecordFactory,
    private val recordSink: Lazy<RecordSink>,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : HeartbeatScheduler {
    override fun start() = Unit

    override fun stop() = Unit

    override fun onRecordRecorded(record: Record) = Unit

    override suspend fun onAlarm(trigger: HeartbeatTrigger) = Unit

    override suspend fun status(): HeartbeatStatus {
        val config = configStore.config.value.heartbeat
        val runtime = configStore.runtime.value
        return HeartbeatStatus(
            enabled = config.enabled,
            minInterval = config.minInterval,
            maxInterval = config.maxInterval,
            lastRecordAt = runtime.lastRecordAt,
            lastHeartbeatAt = runtime.lastHeartbeatAt,
            nextHeartbeatAt = null,
            strategy = HeartbeatStrategy.DISABLED,
            canScheduleExactAlarms = false,
            isIgnoringBatteryOptimizations = false,
            isDeviceIdleMode = false,
            isPowerSaveMode = false,
            pendingHeartbeats = 0,
        )
    }
}
