package com.brickssoft.locationtracking.provider.android

import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.ProviderBundle
import com.brickssoft.locationtracking.provider.ProviderBundles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34])
class AndroidProviderBundleTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `is created reflectively through its Context constructor`() {
        val bundle = Class.forName(ProviderBundles.ANDROID_BUNDLE)
            .getConstructor(Context::class.java)
            .newInstance(app) as ProviderBundle

        assertEquals(ProviderKind.ANDROID, bundle.kind)
        assertTrue(bundle.isAvailable())
        assertEquals(ProviderKind.ANDROID, bundle.location().kind)
        assertEquals(ProviderKind.ANDROID, bundle.activity().kind)
        assertEquals(ProviderKind.ANDROID, bundle.geofence().kind)
    }

    @Test
    fun `is unavailable without a LocationManager`() {
        val noLocation = object : android.content.ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun getSystemService(name: String): Any? =
                if (name == Context.LOCATION_SERVICE) null else super.getSystemService(name)
        }

        assertFalse(AndroidProviderBundle(noLocation).isAvailable())
        assertTrue(app.getSystemService(Context.LOCATION_SERVICE) is LocationManager)
    }

    @Test
    fun `returns the same backends every time`() {
        val bundle = AndroidProviderBundle(app)

        assertSame(bundle.location(), bundle.location())
        assertSame(bundle.geofence(), bundle.geofence())
        assertSame(bundle.activity(), bundle.activity())
    }

    @Test
    fun `activity recognition is not supported`() {
        val activity = AndroidProviderBundle(app).activity()

        assertFalse(activity.isSupported)
        assertFalse(activity.start(10_000))
        activity.stop()
    }

    @Test
    fun `geofences have no dwell`() {
        assertFalse(AndroidProviderBundle(app).geofence().supportsDwell)
    }
}
