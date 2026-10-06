package com.brickssoft.locationtracking.device

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.BuildConfig
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.DeviceInfo
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.model.Sensors
import com.brickssoft.locationtracking.provider.ProviderBundles
import com.brickssoft.locationtracking.provider.ProviderFactory
import com.brickssoft.locationtracking.testing.FakeProviderFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowSensor

@RunWith(RobolectricTestRunner::class)
class DefaultDeviceInfoProviderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun setBuild() {
        ShadowBuild.setManufacturer("HUAWEI")
        ShadowBuild.setModel("ELS-NX9")
        ShadowBuild.setBrand("HUAWEI")
        ShadowBuild.setVersionRelease("14")
    }

    @Test
    fun `deviceInfo reports build values, availability, backend and packaged providers`() {
        setBuild()
        val providers = FakeProviderFactory(ProviderKind.HMS)

        val info = DefaultDeviceInfoProvider(app, lazy { providers }, classPresent = { true }).deviceInfo()

        assertEquals(
            DeviceInfo(
                platform = "android",
                manufacturer = "HUAWEI",
                model = "ELS-NX9",
                brand = "HUAWEI",
                osVersion = "14",
                sdkInt = 34,
                pluginVersion = BuildConfig.PLUGIN_VERSION,
                gmsAvailable = false,
                hmsAvailable = true,
                backend = ProviderKind.HMS,
                packagedProviders = listOf("gms", "hms"),
            ),
            info,
        )
        assertTrue(info.pluginVersion.isNotEmpty())
    }

    @Test
    @Config(sdk = [29])
    fun `deviceInfo reports the running SDK and GMS availability`() {
        val providers = FakeProviderFactory(ProviderKind.GMS).apply { available.add(ProviderKind.HMS) }

        val info = DefaultDeviceInfoProvider(app, lazy { providers }).deviceInfo()

        assertEquals(29, info.sdkInt)
        assertEquals(true, info.gmsAvailable)
        assertEquals(true, info.hmsAvailable)
        assertEquals(ProviderKind.GMS, info.backend)
    }

    @Test
    fun `packaged providers default to the SDK classes on the classpath`() {
        // The unit-test classpath has both SDKs (testImplementation).
        val info = DefaultDeviceInfoProvider(app, lazy { FakeProviderFactory(ProviderKind.ANDROID) }).deviceInfo()

        assertEquals(listOf("gms", "hms"), info.packagedProviders)
    }

    @Test
    fun `packaged providers list only the SDK classes that are present`() {
        fun packaged(present: Set<String>) =
            DefaultDeviceInfoProvider(app, lazy { FakeProviderFactory() }, classPresent = { it in present })
                .deviceInfo().packagedProviders

        assertEquals(listOf("gms"), packaged(setOf(ProviderBundles.GMS_SDK_CLASS)))
        assertEquals(listOf("hms"), packaged(setOf(ProviderBundles.HMS_SDK_CLASS)))
        assertEquals(emptyList<String>(), packaged(emptySet()))
    }

    @Test
    fun `a throwing class probe reads as not packaged`() {
        val info = DefaultDeviceInfoProvider(
            app,
            lazy { FakeProviderFactory() },
            classPresent = { if (it == ProviderBundles.GMS_SDK_CLASS) error("boom") else true },
        ).deviceInfo()

        assertEquals(listOf("hms"), info.packagedProviders)
    }

    @Test
    fun `a throwing availability check reads as unavailable`() {
        val providers = object : ProviderFactory by FakeProviderFactory(ProviderKind.ANDROID) {
            override fun isAvailable(kind: ProviderKind): Boolean = throw IllegalStateException("boom")
        }

        val info = DefaultDeviceInfoProvider(app, lazy { providers }).deviceInfo()

        assertEquals(false, info.gmsAvailable)
        assertEquals(false, info.hmsAvailable)
        assertEquals(ProviderKind.ANDROID, info.backend)
    }

    @Test
    fun `a failing provider factory still reports the build values`() {
        setBuild()

        val info = DefaultDeviceInfoProvider(app, lazy<ProviderFactory> { error("factory broken") }).deviceInfo()

        assertEquals("HUAWEI", info.manufacturer)
        assertEquals("ELS-NX9", info.model)
        assertEquals(false, info.gmsAvailable)
        assertEquals(false, info.hmsAvailable)
        assertEquals(ProviderKind.ANDROID, info.backend)
    }

    @Test
    fun `constructor and sensors do not touch the provider factory`() {
        val provider = DefaultDeviceInfoProvider(app, lazy<ProviderFactory> { error("providers touched") })

        provider.sensors()
    }

    @Test
    fun `sensors are all absent on a bare device`() {
        val sensors = DefaultDeviceInfoProvider(app, lazy { FakeProviderFactory() }).sensors()

        assertEquals(Sensors(false, false, false, false, false, false, false), sensors)
    }

    @Test
    fun `sensors report the default sensors present`() {
        val shadow = shadowOf(app.getSystemService(SensorManager::class.java))
        listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_STEP_COUNTER,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_SIGNIFICANT_MOTION,
        ).forEach { shadow.addSensor(ShadowSensor.newInstance(it)) }

        val sensors = DefaultDeviceInfoProvider(app, lazy { FakeProviderFactory() }).sensors()

        assertEquals(
            Sensors(
                accelerometer = true,
                gyroscope = false,
                magnetometer = false,
                significantMotion = true,
                stepCounter = true,
                stepDetector = false,
                barometer = true,
            ),
            sensors,
        )
    }

    @Test
    fun `sensors report every supported type`() {
        val shadow = shadowOf(app.getSystemService(SensorManager::class.java))
        listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_SIGNIFICANT_MOTION,
            Sensor.TYPE_STEP_COUNTER,
            Sensor.TYPE_STEP_DETECTOR,
            Sensor.TYPE_PRESSURE,
        ).forEach { shadow.addSensor(ShadowSensor.newInstance(it)) }

        val sensors = DefaultDeviceInfoProvider(app, lazy { FakeProviderFactory() }).sensors()

        assertEquals(Sensors(true, true, true, true, true, true, true), sensors)
    }
}
