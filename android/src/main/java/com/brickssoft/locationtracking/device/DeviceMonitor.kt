package com.brickssoft.locationtracking.device

import com.brickssoft.locationtracking.model.BatterySnapshot
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.DeviceInfo
import com.brickssoft.locationtracking.model.ProviderState
import com.brickssoft.locationtracking.model.Sensors

/** Device state: location providers, connectivity, battery, power and idle modes. */
interface DeviceMonitor {
    /** Idempotent: PROVIDERS_CHANGED, NetworkCallback, POWER_SAVE_MODE_CHANGED, DEVICE_IDLE_MODE_CHANGED. */
    fun start()

    /** Diffs against runtime.providerState -> ProviderChange event (+ providerchange record if enabled). */
    suspend fun checkProviderState(reason: String)

    fun providerState(): ProviderState

    fun connectivity(): Connectivity

    fun battery(): BatterySnapshot

    fun isPowerSaveMode(): Boolean

    fun isDeviceIdleMode(): Boolean

    fun isIgnoringBatteryOptimizations(): Boolean

    fun canScheduleExactAlarms(): Boolean
}

interface DeviceInfoProvider {
    fun deviceInfo(): DeviceInfo

    fun sensors(): Sensors
}
