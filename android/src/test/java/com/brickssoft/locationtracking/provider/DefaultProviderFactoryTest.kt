package com.brickssoft.locationtracking.provider

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.api.setMetaData
import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.core.LogLevel
import com.brickssoft.locationtracking.core.LogSink
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.LocationProviderSetting
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.android.AndroidLocationBackend
import com.brickssoft.locationtracking.provider.android.AndroidProviderBundle
import com.brickssoft.locationtracking.testing.FakeActivityBackend
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.FakeGeofenceBackend
import com.brickssoft.locationtracking.testing.FakeLocationBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class DefaultProviderFactoryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val configStore = FakeConfigStore()

    private class TestBundle(override val kind: ProviderKind) : ProviderBundle {
        @Volatile
        var available = true

        @Volatile
        var availabilityError: Throwable? = null
        val location = FakeLocationBackend(kind)
        val activity = FakeActivityBackend(kind)
        val geofence = FakeGeofenceBackend(true, kind)

        override fun isAvailable(): Boolean {
            availabilityError?.let { throw it }
            return available
        }

        override fun location(): LocationBackend = location

        override fun activity(): ActivityBackend = activity

        override fun geofence(): GeofenceBackend = geofence
    }

    private val gms = TestBundle(ProviderKind.GMS)
    private val hms = TestBundle(ProviderKind.HMS)
    private val android = TestBundle(ProviderKind.ANDROID)

    /** Classes the fake class lookup reports as present (both SDKs by default). */
    private val present: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        add(ProviderBundles.GMS_SDK_CLASS)
        add(ProviderBundles.HMS_SDK_CLASS)
    }
    /** Receivers the fake manifest lookup reports as declared (all four by default). */
    private val declared: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(ProviderBundles.GMS_RECEIVERS)
        addAll(ProviderBundles.HMS_RECEIVERS)
    }
    private val lookups = CopyOnWriteArrayList<String>()
    private val created = CopyOnWriteArrayList<String>()
    private val creationErrors = ConcurrentHashMap<String, Throwable>()
    private val warnings = CopyOnWriteArrayList<String>()
    private val errors = CopyOnWriteArrayList<String>()

    /** The app's PROVIDERS meta-data value the fake reports; null means the app declares none. */
    @Volatile
    private var providersMetaData: String? = null

    private fun factory(setting: LocationProviderSetting = LocationProviderSetting.AUTO): DefaultProviderFactory {
        configStore.configFlow.value = Config(locationProvider = setting)
        return DefaultProviderFactory(
            app,
            configStore,
            classPresent = { name ->
                lookups += name
                name in present
            },
            createBundle = { name ->
                created += name
                creationErrors[name]?.let { throw it }
                when (name) {
                    ProviderBundles.GMS_BUNDLE -> gms
                    ProviderBundles.HMS_BUNDLE -> hms
                    ProviderBundles.ANDROID_BUNDLE -> android
                    else -> throw ClassNotFoundException(name)
                }
            },
            receiverDeclared = { name -> name in declared },
            providersMetaData = { providersMetaData },
        )
    }

    private fun setSetting(setting: LocationProviderSetting) {
        configStore.update { it.copy(locationProvider = setting) }
    }

    private fun captureWarnings() {
        Logger.sink = object : LogSink {
            override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
                if (level == LogLevel.WARN) warnings += message
                if (level == LogLevel.ERROR) errors += message
            }
        }
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    // ---- auto

    @Test
    fun `auto prefers gms when packaged and available`() {
        val f = factory()

        assertEquals(ProviderKind.GMS, f.kind)
        assertSame(gms.location, f.location())
        assertSame(gms.activity, f.activity())
        assertSame(gms.geofence, f.geofence())
    }

    @Test
    fun `auto uses hms when gms is not available`() {
        gms.available = false

        assertEquals(ProviderKind.HMS, factory().kind)
    }

    @Test
    fun `auto never creates the gms bundle when its sdk is not packaged`() {
        present -= ProviderBundles.GMS_SDK_CLASS

        val f = factory()

        assertEquals(ProviderKind.HMS, f.kind)
        assertFalse(ProviderBundles.GMS_BUNDLE in created)
    }

    @Test
    fun `auto falls back to android when neither sdk is usable`() {
        present -= ProviderBundles.GMS_SDK_CLASS
        hms.available = false

        val f = factory()

        assertEquals(ProviderKind.ANDROID, f.kind)
        assertSame(android.location, f.location())
    }

    @Test
    fun `NoClassDefFoundError while creating a bundle means unavailable`() {
        creationErrors[ProviderBundles.GMS_BUNDLE] = NoClassDefFoundError("com/google/android/gms/common/Feature")
        creationErrors[ProviderBundles.HMS_BUNDLE] = ExceptionInInitializerError(IllegalStateException("static init"))

        val f = factory()

        assertEquals(ProviderKind.ANDROID, f.kind)
        assertFalse(f.isAvailable(ProviderKind.GMS))
        assertFalse(f.isAvailable(ProviderKind.HMS))
    }

    @Test
    fun `LinkageError from isAvailable or the class lookup means unavailable`() {
        gms.availabilityError = NoClassDefFoundError("com/google/android/gms/common/GoogleApiAvailability")
        val throwingLookup = DefaultProviderFactory(
            app,
            configStore,
            classPresent = { name ->
                if (name == ProviderBundles.HMS_SDK_CLASS) throw LinkageError("bad dex") else true
            },
            createBundle = { name -> if (name == ProviderBundles.GMS_BUNDLE) gms else android },
        )

        assertEquals(ProviderKind.ANDROID, throwingLookup.kind)
        assertFalse(throwingLookup.isAvailable(ProviderKind.GMS))
        assertFalse(throwingLookup.isAvailable(ProviderKind.HMS))
    }

    @Test
    fun `auto skips gms when the app removed its receivers`() {
        declared -= ProviderBundles.GMS_RECEIVERS.first()

        val f = factory()

        assertEquals(ProviderKind.HMS, f.kind)
        assertFalse(f.isAvailable(ProviderKind.GMS))
        assertFalse(ProviderBundles.GMS_BUNDLE in created)
    }

    @Test
    fun `auto skips hms when the app removed its receivers`() {
        gms.available = false
        declared -= ProviderBundles.HMS_RECEIVERS.last()

        assertEquals(ProviderKind.ANDROID, factory().kind)
        assertFalse(ProviderBundles.HMS_BUNDLE in created)
    }

    @Test
    fun `a throwing receiver lookup means not packaged`() {
        val f = DefaultProviderFactory(
            app,
            configStore,
            classPresent = { true },
            createBundle = { name ->
                when (name) {
                    ProviderBundles.GMS_BUNDLE -> gms
                    ProviderBundles.HMS_BUNDLE -> hms
                    else -> android
                }
            },
            receiverDeclared = { name ->
                if (name in ProviderBundles.GMS_RECEIVERS) throw SecurityException("boom") else true
            },
        )

        assertEquals(ProviderKind.HMS, f.kind)
        assertFalse(f.isAvailable(ProviderKind.GMS))
    }

    // ---- PROVIDERS meta-data

    @Test
    fun `meta-data hms makes auto use hms although gms is packaged and available`() {
        providersMetaData = "hms"

        val f = factory()

        assertEquals(ProviderKind.HMS, f.kind)
        assertFalse(f.isAvailable(ProviderKind.GMS))
        assertFalse(ProviderBundles.GMS_BUNDLE in created)
        assertFalse(ProviderBundles.GMS_SDK_CLASS in lookups)
    }

    @Test
    fun `meta-data gms makes explicit hms fall back to android with a warning`() {
        captureWarnings()
        providersMetaData = "gms"

        val f = factory(LocationProviderSetting.HMS)

        assertEquals(ProviderKind.ANDROID, f.kind)
        assertFalse(ProviderBundles.HMS_BUNDLE in created)
        assertTrue(warnings.any { "locationProvider=hms" in it })
    }

    @Test
    fun `meta-data android allows neither gms nor hms`() {
        providersMetaData = "android"

        val f = factory()

        assertEquals(ProviderKind.ANDROID, f.kind)
        assertFalse(f.isAvailable(ProviderKind.GMS))
        assertFalse(f.isAvailable(ProviderKind.HMS))
    }

    @Test
    fun `meta-data listing both providers behaves like no entry`() {
        providersMetaData = "gms,hms"

        assertEquals(ProviderKind.GMS, factory().kind)
    }

    @Test
    fun `meta-data ignores case, spaces and unknown names`() {
        captureWarnings()
        providersMetaData = " HMS , huawei ,"

        val f = factory()

        assertEquals(ProviderKind.HMS, f.kind)
        assertFalse(f.isAvailable(ProviderKind.GMS))
        assertTrue(warnings.any { "'huawei'" in it })
    }

    @Test
    fun `meta-data that names no known provider is ignored with an error`() {
        captureWarnings()
        providersMetaData = "huawei"

        assertEquals(ProviderKind.GMS, factory().kind)
        assertTrue(errors.any { ProviderPackaging.PROVIDERS_META_DATA in it })
    }

    @Test
    fun `a listed provider whose receivers were removed is not used and warns`() {
        captureWarnings()
        providersMetaData = "hms"
        declared.removeAll(ProviderBundles.HMS_RECEIVERS.toSet())

        val f = factory()

        assertEquals(ProviderKind.ANDROID, f.kind)
        assertTrue(warnings.any { "hms is listed in ${ProviderPackaging.PROVIDERS_META_DATA}" in it })
    }

    @Test
    fun `a listed provider whose sdk is missing is not used and warns`() {
        captureWarnings()
        providersMetaData = "gms"
        present -= ProviderBundles.GMS_SDK_CLASS

        assertEquals(ProviderKind.ANDROID, factory().kind)
        assertTrue(warnings.any { "gms is listed in ${ProviderPackaging.PROVIDERS_META_DATA}" in it })
    }

    @Test
    fun `default meta-data lookup reads the application meta-data`() {
        val read = ProviderPackaging.providersMetaDataIn(app)
        assertEquals(null, read())

        setMetaData(app, mapOf(ProviderPackaging.PROVIDERS_META_DATA to "hms"))

        assertEquals("hms", read())
    }

    // ---- explicit settings

    @Test
    fun `explicit hms wins over an available gms`() {
        val f = factory(LocationProviderSetting.HMS)

        assertEquals(ProviderKind.HMS, f.kind)
        assertFalse(ProviderBundles.GMS_BUNDLE in created)
    }

    @Test
    fun `explicit gms falls back to android with a warning when unavailable`() {
        captureWarnings()
        gms.available = false

        val f = factory(LocationProviderSetting.GMS)

        assertEquals(ProviderKind.ANDROID, f.kind)
        assertTrue(warnings.any { "locationProvider=gms" in it })
    }

    @Test
    fun `explicit hms falls back to android when its sdk is not packaged`() {
        present -= ProviderBundles.HMS_SDK_CLASS

        val f = factory(LocationProviderSetting.HMS)

        assertEquals(ProviderKind.ANDROID, f.kind)
        assertFalse(ProviderBundles.HMS_BUNDLE in created)
    }

    @Test
    fun `explicit gms falls back to android with a warning when its receivers were removed`() {
        captureWarnings()
        declared.removeAll(ProviderBundles.GMS_RECEIVERS.toSet())

        val f = factory(LocationProviderSetting.GMS)

        assertEquals(ProviderKind.ANDROID, f.kind)
        assertFalse(ProviderBundles.GMS_BUNDLE in created)
        assertTrue(warnings.any { "locationProvider=gms" in it })
    }

    @Test
    fun `explicit android never touches gms or hms`() {
        val f = factory(LocationProviderSetting.ANDROID)

        assertEquals(ProviderKind.ANDROID, f.kind)
        assertEquals(listOf(ProviderBundles.ANDROID_BUNDLE), created.toList())
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun `android is constructed directly if its reflective creation fails`() {
        creationErrors[ProviderBundles.ANDROID_BUNDLE] = ClassNotFoundException(ProviderBundles.ANDROID_BUNDLE)

        val f = factory(LocationProviderSetting.ANDROID)

        assertTrue(f.isAvailable(ProviderKind.ANDROID))
        assertEquals(ProviderKind.ANDROID, f.kind)
        assertTrue(f.location() is AndroidLocationBackend)
        assertEquals(1, created.count { it == ProviderBundles.ANDROID_BUNDLE })
    }

    // ---- laziness, caching, availability

    @Test
    fun `selection is lazy`() {
        val f = factory()

        assertTrue(lookups.isEmpty())
        assertTrue(created.isEmpty())

        f.kind
        assertEquals(listOf(ProviderBundles.GMS_BUNDLE), created.toList())
    }

    @Test
    fun `bundles and sdk lookups are cached`() {
        gms.available = false
        val f = factory()

        f.kind
        f.reselect()
        f.isAvailable(ProviderKind.GMS)
        f.isAvailable(ProviderKind.HMS)
        f.isAvailable(ProviderKind.ANDROID)

        assertEquals(1, created.count { it == ProviderBundles.GMS_BUNDLE })
        assertEquals(1, created.count { it == ProviderBundles.HMS_BUNDLE })
        assertEquals(1, lookups.count { it == ProviderBundles.GMS_SDK_CLASS })
    }

    @Test
    fun `isAvailable reports each backend`() {
        hms.available = false
        android.available = false
        val f = factory()

        assertTrue(f.isAvailable(ProviderKind.GMS))
        assertFalse(f.isAvailable(ProviderKind.HMS))
        assertFalse(f.isAvailable(ProviderKind.ANDROID))
        gms.available = false
        assertFalse(f.isAvailable(ProviderKind.GMS))
    }

    @Test
    fun `concurrent first access selects exactly once`() {
        val f = factory()
        val start = CountDownLatch(1)
        val kinds = ConcurrentHashMap.newKeySet<ProviderKind>()
        val failures = AtomicInteger()
        val threads = (1..8).map {
            thread {
                start.await(5, TimeUnit.SECONDS)
                try {
                    kinds += f.kind
                    f.location()
                } catch (t: Throwable) {
                    failures.incrementAndGet()
                }
            }
        }

        start.countDown()
        threads.forEach { it.join(5_000) }

        assertEquals(0, failures.get())
        assertEquals(setOf(ProviderKind.GMS), kinds)
        assertEquals(1, created.size)
        assertEquals(1, lookups.size)
    }

    // ---- reselect

    @Test
    fun `reselect follows a config change and reports it`() {
        val f = factory()
        assertEquals(ProviderKind.GMS, f.kind)

        setSetting(LocationProviderSetting.HMS)
        assertEquals(ProviderKind.GMS, f.kind)
        assertTrue(f.reselect())
        assertEquals(ProviderKind.HMS, f.kind)
        assertSame(hms.location, f.location())

        assertFalse(f.reselect())
        setSetting(LocationProviderSetting.ANDROID)
        assertTrue(f.reselect())
        assertEquals(ProviderKind.ANDROID, f.kind)
    }

    @Test
    fun `reselect re-evaluates availability`() {
        val f = factory()
        assertEquals(ProviderKind.GMS, f.kind)

        gms.available = false
        assertTrue(f.reselect())
        assertEquals(ProviderKind.HMS, f.kind)

        gms.available = true
        assertTrue(f.reselect())
        assertEquals(ProviderKind.GMS, f.kind)
    }

    @Test
    fun `reselect before any access selects and reports no change`() {
        val f = factory(LocationProviderSetting.HMS)

        assertFalse(f.reselect())
        assertEquals(ProviderKind.HMS, f.kind)
    }

    // ---- default reflection

    @Test
    fun `default class lookup finds present classes and rejects missing ones`() {
        val present = DefaultProviderFactory.reflectiveClassPresent(app.classLoader)

        assertTrue(present(ProviderBundles.ANDROID_BUNDLE))
        assertFalse(present("com.brickssoft.locationtracking.provider.DoesNotExist"))
    }

    @Test
    fun `default receiver lookup reads the merged manifest`() {
        val declaredIn = ProviderPackaging.receiverDeclaredIn(app)

        (ProviderBundles.GMS_RECEIVERS + ProviderBundles.HMS_RECEIVERS).forEach { assertTrue(it, declaredIn(it)) }
        assertFalse(declaredIn("com.brickssoft.locationtracking.provider.gms.DoesNotExist"))
    }

    @Test
    fun `default lookups report both sdks as packaged in the plugin's own manifest`() {
        // The unit-test classpath has both SDKs, and the plugin manifest declares every receiver.
        val packaging = ProviderPackaging(
            DefaultProviderFactory.reflectiveClassPresent(app.classLoader),
            ProviderPackaging.receiverDeclaredIn(app),
        )

        assertEquals(ProviderPackaging.Status.PACKAGED, packaging.status(ProviderKind.GMS))
        assertEquals(ProviderPackaging.Status.PACKAGED, packaging.status(ProviderKind.HMS))
        assertEquals(ProviderPackaging.Status.PACKAGED, packaging.status(ProviderKind.ANDROID))
    }

    @Test
    fun `default reflection creates the android bundle`() {
        configStore.configFlow.value = Config(locationProvider = LocationProviderSetting.ANDROID)
        val f = DefaultProviderFactory(app, configStore)

        assertEquals(ProviderKind.ANDROID, f.kind)
        assertTrue(f.isAvailable(ProviderKind.ANDROID))
        assertTrue(f.location() is AndroidLocationBackend)
        val create = DefaultProviderFactory.reflectiveBundleCreator(app)
        assertTrue(create(ProviderBundles.ANDROID_BUNDLE) is AndroidProviderBundle)
    }
}
