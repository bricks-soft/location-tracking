package com.brickssoft.locationtracking.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.brickssoft.locationtracking.BuildConfig
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.DeviceInfo
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.Sensors
import com.brickssoft.locationtracking.provider.DefaultProviderFactory
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.provider.ProviderPackaging

/**
 * Default [DeviceInfoProvider]: build properties, plugin version, GMS/HMS availability and the active backend
 * (from [providers]), the providers packaged in the APK ([ProviderPackaging]), and which motion sensors the device has.
 * If the provider factory fails, both SDKs read as unavailable and the backend as `android` (the fallback).
 *
 * The constructor does not touch [providers].
 */
class DefaultDeviceInfoProvider(
    private val context: Context,
    private val providers: Lazy<ProviderFactory>,
    classPresent: (String) -> Boolean = DefaultProviderFactory.reflectiveClassPresent(context.classLoader),
    receiverDeclared: (String) -> Boolean = ProviderPackaging.receiverDeclaredIn(context),
) : DeviceInfoProvider {
    private val packaging = ProviderPackaging(classPresent, receiverDeclared)

    override fun deviceInfo(): DeviceInfo {
        // DeviceInfo is diagnostic: a broken provider factory must not hide the build properties.
        val factory = try {
            providers.value
        } catch (e: Exception) {
            Logger.e(TAG, "provider factory unavailable", e)
            null
        }
        val backend = try {
            factory?.kind
        } catch (e: Exception) {
            Logger.e(TAG, "provider kind unavailable", e)
            null
        }
        return DeviceInfo(
            platform = "android",
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            pluginVersion = BuildConfig.PLUGIN_VERSION,
            gmsAvailable = factory != null && isAvailable(factory, ProviderKind.GMS),
            hmsAvailable = factory != null && isAvailable(factory, ProviderKind.HMS),
            backend = backend ?: ProviderKind.ANDROID,
            packagedProviders = packagedProviders(),
        )
    }

    override fun sensors(): Sensors {
        val manager = try {
            ContextCompat.getSystemService(context, SensorManager::class.java)
        } catch (e: Exception) {
            Logger.w(TAG, "SensorManager unavailable", e)
            null
        }
        fun has(type: Int): Boolean = try {
            manager?.getDefaultSensor(type) != null
        } catch (e: Exception) {
            Logger.w(TAG, "getDefaultSensor($type) failed", e)
            false
        }
        return Sensors(
            accelerometer = has(Sensor.TYPE_ACCELEROMETER),
            gyroscope = has(Sensor.TYPE_GYROSCOPE),
            magnetometer = has(Sensor.TYPE_MAGNETIC_FIELD),
            significantMotion = has(Sensor.TYPE_SIGNIFICANT_MOTION),
            stepCounter = has(Sensor.TYPE_STEP_COUNTER),
            stepDetector = has(Sensor.TYPE_STEP_DETECTOR),
            barometer = has(Sensor.TYPE_PRESSURE),
        )
    }

    private fun isAvailable(factory: ProviderFactory, kind: ProviderKind): Boolean = try {
        factory.isAvailable(kind)
    } catch (e: Exception) {
        Logger.w(TAG, "isAvailable(${kind.wire}) failed", e)
        false
    }

    /**
     * The providers packaged in the APK ([ProviderPackaging]: SDK classes present and receivers declared), in the order
     * `gms`, `hms`. Read at runtime, so a flavored app that adds one SDK and removes the other provider's receivers
     * reports only that provider, even when another library brings the other SDK's classes.
     */
    private fun packagedProviders(): List<String> =
        listOf(ProviderKind.GMS, ProviderKind.HMS).filter(packaging::isPackaged).map { it.wire }

    internal companion object {
        private const val TAG = "LT.DeviceInfo"
    }
}
