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
import org.junit.Assert.assertNull
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
            HmsProviderBundle(app, availabilityCheck = {
                seen += it
                ConnectionResult.SUCCESS
            }).isAvailable(),
        )
        assertSame(app, seen.single())
        assertFalse(HmsProviderBundle(app, availabilityCheck = { ConnectionResult.SERVICE_MISSING }).isAvailable())
        val outdated = HmsProviderBundle(app, availabilityCheck = { ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED })
        assertFalse(outdated.isAvailable())
        assertEquals(ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED, outdated.availabilityStatus())
    }

    @Test
    fun `a failing availability check means unavailable`() {
        val missing = HmsProviderBundle(app, availabilityCheck = {
            throw NoClassDefFoundError("com/huawei/hms/api/HuaweiApiAvailability")
        })
        assertFalse(missing.isAvailable())
        assertNull(missing.availabilityStatus())
        assertFalse(HmsProviderBundle(app, availabilityCheck = { throw IllegalStateException("boom") }).isAvailable())
    }

    @Test
    fun `an HMS Core older than 6_3_0_301 is a diagnostic, not a reason to skip HMS`() {
        val minimum = mutableListOf<Int>()
        val old = HmsProviderBundle(
            app,
            availabilityCheck = { ConnectionResult.SUCCESS },
            versionCheck = { _, version ->
                minimum += version
                ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED
            },
        )
        assertTrue(old.isAvailable())
        assertTrue(old.versionDiagnostic()!!.contains("6.3.0.301"))
        assertEquals(listOf(60300301), minimum)
        val current = HmsProviderBundle(app, versionCheck = { _, _ -> ConnectionResult.SUCCESS })
        assertNull(current.versionDiagnostic())
        assertNull(HmsProviderBundle(app, versionCheck = { _, _ -> throw IllegalStateException("x") }).versionDiagnostic())
    }

    @Test
    fun `the real check reports unavailable without HMS Core and never throws`() {
        assertFalse(HmsProviderBundle(app).isAvailable())
    }
}
