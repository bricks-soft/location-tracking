// STUB — owned by Unit 14 (Device). Replace this implementation.
package com.brickssoft.locationtracking.device

import android.content.Context
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.model.AccuracyLevel
import com.brickssoft.locationtracking.model.BatterySnapshot
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.ConnectivityType
import com.brickssoft.locationtracking.model.PermissionLevel
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.permission.PermissionManager
import com.brickssoft.locationtracking.processing.RecordFactory
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.record.RecordSink
import kotlinx.coroutines.CoroutineScope

/** Device state monitor. Stub: registers nothing and reports neutral values. */
@Suppress("unused")
class DefaultDeviceMonitor(
    private val context: Context,
    private val configStore: ConfigStore,
    private val permissions: PermissionManager,
    private val providers: Lazy<ProviderFactory>,
    private val events: EventBus,
    private val clock: Clock,
    private val recordFactory: Lazy<RecordFactory>,
    private val recordSink: Lazy<RecordSink>,
    private val scope: CoroutineScope,
) : DeviceMonitor {
    override fun start() = Unit

    override suspend fun checkProviderState(reason: String) = Unit

    override fun providerState(): ProviderState = ProviderState(
        enabled = false,
        gps = false,
        network = false,
        permission = PermissionLevel.DENIED,
        accuracy = AccuracyLevel.NONE,
        backend = ProviderKind.ANDROID,
    )

    override fun connectivity(): Connectivity = Connectivity(connected = false, type = ConnectivityType.NONE)

    override fun battery(): BatterySnapshot = BatterySnapshot.UNKNOWN

    override fun isPowerSaveMode(): Boolean = false

    override fun isDeviceIdleMode(): Boolean = false

    override fun isIgnoringBatteryOptimizations(): Boolean = false

    override fun canScheduleExactAlarms(): Boolean = false
}
