package com.brickssoft.locationtracking.testing

import android.app.Activity
import com.brickssoft.locationtracking.config.BackgroundPermissionRationale
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.model.AccuracyLevel
import com.brickssoft.locationtracking.model.BatterySnapshot
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.ConnectivityType
import com.brickssoft.locationtracking.model.PermissionLevel
import com.brickssoft.locationtracking.model.PowerManagerInfo
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.permission.PermissionHost
import com.brickssoft.locationtracking.permission.PermissionManager
import com.brickssoft.locationtracking.permission.PermissionState
import com.brickssoft.locationtracking.permission.PermissionType
import com.brickssoft.locationtracking.settings.DeviceSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** [DeviceMonitor] with mutable fields (defaults: everything enabled, wifi, not idle, no exact alarms). */
class FakeDeviceMonitor : DeviceMonitor {
    @Volatile
    var providerStateValue = ProviderState(
        enabled = true,
        gps = true,
        network = true,
        permission = PermissionLevel.ALWAYS,
        accuracy = AccuracyLevel.PRECISE,
        backend = ProviderKind.GMS,
    )

    @Volatile
    var connectivityValue = Connectivity(connected = true, type = ConnectivityType.WIFI)

    @Volatile
    var batteryValue = BatterySnapshot(0.8f, false)

    @Volatile
    var powerSaveMode = false

    @Volatile
    var deviceIdleMode = false

    @Volatile
    var ignoringBatteryOptimizations = false

    @Volatile
    var exactAlarms = false

    @Volatile
    var startCalls = 0

    /** Reasons passed to [checkProviderState], in order. */
    val checkReasons = CopyOnWriteArrayList<String>()

    override fun start() {
        startCalls++
    }

    override suspend fun checkProviderState(reason: String) {
        checkReasons += reason
    }

    override fun providerState(): ProviderState = providerStateValue

    override fun connectivity(): Connectivity = connectivityValue

    override fun battery(): BatterySnapshot = batteryValue

    override fun isPowerSaveMode(): Boolean = powerSaveMode

    override fun isDeviceIdleMode(): Boolean = deviceIdleMode

    override fun isIgnoringBatteryOptimizations(): Boolean = ignoringBatteryOptimizations

    override fun canScheduleExactAlarms(): Boolean = exactAlarms
}

/** [DeviceSettings] that records which screens were opened; every `open*` returns [openResult]. */
class FakeDeviceSettings : DeviceSettings {
    val opened = CopyOnWriteArrayList<String>()

    @Volatile
    var openResult = true

    @Volatile
    var powerManagerInfoValue = PowerManagerInfo(manufacturer = "Google", available = false)

    override fun openBatteryOptimizationSettings(activity: Activity?): Boolean = open("batteryOptimization")

    override fun powerManagerInfo(): PowerManagerInfo = powerManagerInfoValue

    override fun openPowerManagerSettings(activity: Activity?): Boolean = open("powerManager")

    override fun openLocationSettings(activity: Activity?): Boolean = open("location")

    override fun openAppSettings(activity: Activity?): Boolean = open("app")

    private fun open(name: String): Boolean {
        opened += name
        return openResult
    }
}

/**
 * [PermissionManager] over the mutable [statuses] map (default: all GRANTED).
 * `request` records the types, applies [grantOnRequest] (if set) to every requested type, then calls back.
 */
class FakePermissionManager : PermissionManager {
    val statuses: MutableMap<PermissionType, PermissionState> =
        ConcurrentHashMap(PermissionType.entries.associateWith { PermissionState.GRANTED })
    val requests = CopyOnWriteArrayList<List<PermissionType>>()

    @Volatile
    var grantOnRequest: PermissionState? = null

    fun set(type: PermissionType, state: PermissionState) {
        statuses[type] = state
    }

    fun denyAll() = PermissionType.entries.forEach { statuses[it] = PermissionState.DENIED }

    override fun status(): Map<PermissionType, PermissionState> = statuses.toMap()

    override fun hasForegroundLocation(): Boolean = statuses[PermissionType.LOCATION] == PermissionState.GRANTED

    override fun hasBackgroundLocation(): Boolean =
        statuses[PermissionType.BACKGROUND_LOCATION] == PermissionState.GRANTED

    override fun hasActivityRecognition(): Boolean =
        statuses[PermissionType.ACTIVITY_RECOGNITION] == PermissionState.GRANTED

    override fun hasNotifications(): Boolean = statuses[PermissionType.NOTIFICATIONS] == PermissionState.GRANTED

    override fun request(
        host: PermissionHost,
        types: List<PermissionType>,
        rationale: BackgroundPermissionRationale,
        onDone: (Map<PermissionType, PermissionState>) -> Unit,
    ) {
        requests += types
        grantOnRequest?.let { state -> types.forEach { statuses[it] = state } }
        onDone(status())
    }
}
