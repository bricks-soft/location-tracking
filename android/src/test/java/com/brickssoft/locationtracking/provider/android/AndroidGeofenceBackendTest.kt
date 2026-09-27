package com.brickssoft.locationtracking.provider.android

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.ProviderKind
import com.brickssoft.locationtracking.provider.OsGeofence
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class AndroidGeofenceBackendTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** Records proximity-alert calls; [failOnAdd] makes `add` throw for that region latitude. */
    private class FakeProximityAlerts : ProximityAlerts {
        data class Alert(val latitude: Double, val longitude: Double, val radius: Float, val intent: PendingIntent)

        val calls = CopyOnWriteArrayList<String>()
        val active = LinkedHashMap<PendingIntent, Alert>()
        var failOnAdd: Pair<Double, Exception>? = null

        override fun add(latitude: Double, longitude: Double, radius: Float, intent: PendingIntent) {
            failOnAdd?.let { (lat, e) -> if (lat == latitude) throw e }
            calls += "add:${idOf(intent)}"
            active[intent] = Alert(latitude, longitude, radius, intent)
        }

        override fun remove(intent: PendingIntent) {
            calls += "remove:${idOf(intent)}"
            active.remove(intent)
        }

        fun activeIds(): Set<String> = active.keys.map(::idOf).toSet()

        companion object {
            fun idOf(intent: PendingIntent): String = shadowOf(intent).savedIntent.data!!.authority!!
        }
    }

    private val alerts = FakeProximityAlerts()
    private val backend = AndroidGeofenceBackend(app, alerts)

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun region(id: String, lat: Double = 24.7, onEntry: Boolean = true, onExit: Boolean = true) = OsGeofence(
        id = id, latitude = lat, longitude = 46.6, radius = 150f, onEntry = onEntry, onExit = onExit,
        onDwell = false, loiteringDelayMs = 30_000, initialTriggerEntry = true,
    )

    @Test
    fun `reports android kind without dwell`() {
        assertEquals(ProviderKind.ANDROID, backend.kind)
        assertFalse(backend.supportsDwell)
    }

    @Test
    fun `add registers one explicit mutable PendingIntent per region`() = runTest {
        backend.add(listOf(region("home", lat = 1.0), region("work", lat = 2.0, onEntry = false)))

        assertEquals(setOf("home", "work"), alerts.activeIds())
        val home = alerts.active.values.first { FakeProximityAlerts.idOf(it.intent) == "home" }
        assertEquals(1.0, home.latitude, 0.0)
        assertEquals(46.6, home.longitude, 0.0)
        assertEquals(150f, home.radius)
        val shadow = shadowOf(home.intent)
        assertTrue(shadow.isBroadcastIntent)
        assertEquals(Constants.RC_ANDROID_PROXIMITY, shadow.requestCode)
        assertTrue(shadow.flags and PendingIntent.FLAG_MUTABLE != 0)
        val intent = shadow.savedIntent
        assertEquals(ComponentName(app, AndroidGeofenceReceiver::class.java), intent.component)
        assertEquals(Constants.ACTION_GEOFENCE, intent.action)
        assertEquals("lt-geofence://home", intent.dataString)
        val work = alerts.active.values.first { FakeProximityAlerts.idOf(it.intent) == "work" }
        assertFalse(shadowOf(work.intent).savedIntent.getBooleanExtra(AndroidGeofenceBackend.EXTRA_NOTIFY_ENTRY, true))
        assertTrue(shadowOf(work.intent).savedIntent.getBooleanExtra(AndroidGeofenceBackend.EXTRA_NOTIFY_EXIT, false))
        assertNotSame(home.intent, work.intent)
        assertEquals(setOf("home", "work"), backend.registeredIds)
    }

    @Test
    @Config(sdk = [30])
    fun `before API 31 the PendingIntent carries no mutability flag`() = runTest {
        backend.add(listOf(region("home")))

        val shadow = shadowOf(alerts.active.keys.single())
        assertEquals(0, shadow.flags and (PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_IMMUTABLE))
    }

    @Test
    fun `re-adding an id replaces its previous alert`() = runTest {
        backend.add(listOf(region("home", lat = 1.0)))
        backend.add(listOf(region("home", lat = 3.0)))

        assertEquals(listOf("remove:home", "add:home", "remove:home", "add:home"), alerts.calls)
        assertEquals(3.0, alerts.active.values.single().latitude, 0.0)
    }

    @Test
    fun `remove unregisters and cancels the PendingIntent`() = runTest {
        backend.add(listOf(region("home"), region("work", lat = 2.0)))
        val home = alerts.active.keys.first { FakeProximityAlerts.idOf(it) == "home" }

        backend.remove(listOf("home", "unknown"))

        assertEquals(setOf("work"), alerts.activeIds())
        assertTrue(shadowOf(home).isCanceled)
        assertEquals(setOf("work"), backend.registeredIds)
    }

    @Test
    fun `removeAll removes every registered id`() = runTest {
        backend.add(listOf(region("a"), region("b", lat = 2.0), region("c", lat = 3.0)))

        backend.removeAll()

        assertTrue(alerts.active.isEmpty())
        assertTrue(backend.registeredIds.isEmpty())
    }

    @Test
    fun `registered ids survive a process restart`() = runTest {
        backend.add(listOf(region("a"), region("b", lat = 2.0)))

        val restarted = AndroidGeofenceBackend(app, alerts)
        restarted.removeAll()

        assertTrue(alerts.active.isEmpty())
        assertTrue(restarted.registeredIds.isEmpty())
        assertTrue(AndroidGeofenceBackend(app, alerts).registeredIds.isEmpty())
    }

    private suspend fun assertAddFails(code: ErrorCode, regions: List<OsGeofence>) {
        try {
            backend.add(regions)
            fail("expected $code")
        } catch (e: TrackingException) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun `SecurityException maps to PERMISSION_DENIED and removes the regions the call introduced`() = runTest {
        backend.add(listOf(region("existing", lat = 9.0), region("kept", lat = 8.0)))
        alerts.failOnAdd = 2.0 to SecurityException("no fine location")

        assertAddFails(
            ErrorCode.PERMISSION_DENIED,
            listOf(region("a", lat = 1.0), region("kept", lat = 7.0), region("b", lat = 2.0)),
        )

        assertEquals(setOf("existing", "kept"), alerts.activeIds())
        assertEquals(setOf("existing", "kept"), backend.registeredIds)
        assertEquals(7.0, alerts.active.values.first { FakeProximityAlerts.idOf(it.intent) == "kept" }.latitude, 0.0)
    }

    @Test
    fun `a registered region whose re-add fails is dropped`() = runTest {
        backend.add(listOf(region("home", lat = 1.0), region("work", lat = 3.0)))
        alerts.failOnAdd = 2.0 to IllegalStateException("system server died")

        assertAddFails(ErrorCode.UNAVAILABLE, listOf(region("home", lat = 2.0)))

        assertEquals(setOf("work"), alerts.activeIds())
        assertEquals(setOf("work"), backend.registeredIds)
    }

    @Test
    fun `invalid regions are rejected before any alert changes`() = runTest {
        backend.add(listOf(region("home")))
        alerts.calls.clear()

        assertAddFails(ErrorCode.INVALID_ARGUMENT, listOf(region("a"), region("b").copy(radius = 0f)))
        assertAddFails(ErrorCode.INVALID_ARGUMENT, listOf(region("a", lat = 91.0)))
        assertAddFails(ErrorCode.INVALID_ARGUMENT, listOf(region("a").copy(longitude = Double.NaN)))
        assertAddFails(ErrorCode.INVALID_ARGUMENT, listOf(region("")))

        assertTrue(alerts.calls.isEmpty())
        assertEquals(setOf("home"), backend.registeredIds)
    }

    @Test
    fun `a platform IllegalArgumentException maps to INVALID_ARGUMENT`() = runTest {
        alerts.failOnAdd = 1.0 to IllegalArgumentException("bad radius")

        assertAddFails(ErrorCode.INVALID_ARGUMENT, listOf(region("a", lat = 1.0)))
        assertTrue(backend.registeredIds.isEmpty())
    }

    @Test
    fun `registered PendingIntent round-trips through the receiver parser`() = runTest {
        backend.add(listOf(region("gate 7/b", onExit = false)))
        val saved = shadowOf(alerts.active.keys.single()).savedIntent

        val enter = AndroidGeofenceReceiver.parse(
            saved.cloneFilter().putExtras(saved).putExtra(LocationManager.KEY_PROXIMITY_ENTERING, true),
        )
        val exit = AndroidGeofenceReceiver.parse(
            saved.cloneFilter().putExtras(saved).putExtra(LocationManager.KEY_PROXIMITY_ENTERING, false),
        )

        assertEquals("gate 7/b", enter!!.id)
        assertEquals(GeofenceAction.ENTER, enter.action)
        assertEquals(null, exit)
    }

    @Test
    fun `the default platform alerts can be registered and removed`() = runTest {
        val real = AndroidGeofenceBackend(app)

        real.add(listOf(region("home")))
        assertEquals(setOf("home"), real.registeredIds)
        real.removeAll()

        assertTrue(real.registeredIds.isEmpty())
    }
}
