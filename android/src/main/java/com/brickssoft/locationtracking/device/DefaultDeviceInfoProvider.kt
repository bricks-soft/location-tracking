// STUB — owned by Unit 14 (Device). Replace this implementation.
package com.brickssoft.locationtracking.device

import android.content.Context
import com.brickssoft.locationtracking.model.DeviceInfo
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.Sensors
import com.brickssoft.locationtracking.provider.ProviderFactory

/** Device info and sensors. Stub: empty values. */
@Suppress("unused")
class DefaultDeviceInfoProvider(
    private val context: Context,
    private val providers: Lazy<ProviderFactory>,
) : DeviceInfoProvider {
    override fun deviceInfo(): DeviceInfo = DeviceInfo(
        platform = "android",
        manufacturer = "",
        model = "",
        brand = "",
        osVersion = "",
        sdkInt = 0,
        pluginVersion = "",
        gmsAvailable = false,
        hmsAvailable = false,
        backend = ProviderKind.ANDROID,
        packagedProviders = emptyList(),
    )

    override fun sensors(): Sensors = Sensors(
        accelerometer = false,
        gyroscope = false,
        magnetometer = false,
        significantMotion = false,
        stepCounter = false,
        stepDetector = false,
        barometer = false,
    )
}
