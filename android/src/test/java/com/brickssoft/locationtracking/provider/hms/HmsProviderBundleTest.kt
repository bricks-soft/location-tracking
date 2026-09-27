package com.brickssoft.locationtracking.provider.hms

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.ProviderBundle
import com.brickssoft.locationtracking.provider.ProviderBundles
import com.huawei.hms.api.ConnectionResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HmsProviderBundleTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `is created reflectively through its public Context constructor`() {
        val cls = Class.forName(ProviderBundles.HMS_BUNDLE, false, javaClass.classLoader)

        val bundle = cls.getConstructor(Context::class.java).newInstance(app) as ProviderBundle

        assertTrue(bundle is HmsProviderBundle)
        assertEquals(ProviderKind.HMS, bundle.kind)
    }

    @Test
    fun `backends are HMS and reused`() {
        val bundle = HmsProviderBundle(app)

        assertEquals(ProviderKind.HMS, bundle.location().kind)
        assertEquals(ProviderKind.HMS, bundle.activity().kind)
        assertEquals(ProviderKind.HMS, bundle.geofence().kind)
        assertTrue(bundle.activity().isSupported)
        assertTrue(bundle.geofence().supportsDwell)
        assertSame(bundle.location(), bundle.location())
        assertSame(bundle.activity(), bundle.activity())
        assertSame(bundle.geofence(), bundle.geofence())
    }

    @Test
    fun `available only when HMS Core reports SUCCESS`() {
        val seen = mutableListOf<Context>()
        assertTrue(
            HmsProviderBundle(app) {
                seen += it
                ConnectionResult.SUCCESS
            }.isAvailable(),
        )
        assertSame(app, seen.single())
        assertFalse(HmsProviderBundle(app) { ConnectionResult.SERVICE_MISSING }.isAvailable())
        assertFalse(HmsProviderBundle(app) { ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED }.isAvailable())
    }

    @Test
    fun `a failing availability check means unavailable`() {
        assertFalse(HmsProviderBundle(app) { throw NoClassDefFoundError("com/huawei/hms/api/HuaweiApiAvailability") }.isAvailable())
        assertFalse(HmsProviderBundle(app) { throw IllegalStateException("boom") }.isAvailable())
    }

    @Test
    fun `the real check reports unavailable without HMS Core and never throws`() {
        assertFalse(HmsProviderBundle(app).isAvailable())
    }
}
