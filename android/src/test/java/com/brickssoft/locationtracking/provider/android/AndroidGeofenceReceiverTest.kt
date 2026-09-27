package com.brickssoft.locationtracking.provider.android

import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.provider.GeofenceTransitionSink
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import com.brickssoft.locationtracking.testing.FakeClock
import com.brickssoft.locationtracking.testing.Fixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class AndroidGeofenceReceiverTest {
    private class RecordingSink(private val failure: Exception? = null) : GeofenceTransitionSink {
        val received = CopyOnWriteArrayList<OsGeofenceTransition>()

        override suspend fun onGeofenceTransitions(transitions: List<OsGeofenceTransition>) {
            failure?.let { throw it }
            received += transitions
        }
    }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun alert(id: String, entering: Boolean?): Intent =
        Intent(Constants.ACTION_GEOFENCE).setData(Uri.parse(Constants.geofenceUri(id))).apply {
            if (entering != null) putExtra(LocationManager.KEY_PROXIMITY_ENTERING, entering)
        }

    @Test
    fun `entering maps to ENTER and leaving to EXIT`() {
        assertEquals(
            OsGeofenceTransition("home", GeofenceAction.ENTER, null),
            AndroidGeofenceReceiver.parse(alert("home", entering = true)),
        )
        assertEquals(
            OsGeofenceTransition("home", GeofenceAction.EXIT, null),
            AndroidGeofenceReceiver.parse(alert("home", entering = false)),
        )
    }

    @Test
    fun `ids with reserved characters are decoded`() {
        val id = "a b/c?d#e:f@g%hé"

        assertEquals(id, AndroidGeofenceReceiver.parse(alert(id, entering = true))!!.id)
    }

    @Test
    fun `foreign or incomplete intents are ignored`() {
        assertNull(AndroidGeofenceReceiver.parse(alert("home", entering = null)))
        assertNull(AndroidGeofenceReceiver.parse(Intent().putExtra(LocationManager.KEY_PROXIMITY_ENTERING, true)))
        assertNull(
            AndroidGeofenceReceiver.parse(
                Intent().setData(Uri.parse("https://home")).putExtra(LocationManager.KEY_PROXIMITY_ENTERING, true),
            ),
        )
        assertNull(AndroidGeofenceReceiver.parse(alert("", entering = true)))
    }

    @Test
    fun `transitions the region did not ask for are dropped`() {
        val noEntry = alert("home", entering = true).putExtra(AndroidGeofenceBackend.EXTRA_NOTIFY_ENTRY, false)
        val noExit = alert("home", entering = false).putExtra(AndroidGeofenceBackend.EXTRA_NOTIFY_EXIT, false)
        val exitWanted = alert("home", entering = false).putExtra(AndroidGeofenceBackend.EXTRA_NOTIFY_ENTRY, false)

        assertNull(AndroidGeofenceReceiver.parse(noEntry))
        assertNull(AndroidGeofenceReceiver.parse(noExit))
        assertEquals(GeofenceAction.EXIT, AndroidGeofenceReceiver.parse(exitWanted)!!.action)
    }

    @Test
    fun `deliver adds the last known location and finishes`() = runTest {
        val sink = RecordingSink()
        var finished = 0
        val location = Fixtures.location()

        AndroidGeofenceReceiver.deliver(
            OsGeofenceTransition("home", GeofenceAction.ENTER, null), this, sink, { location }, { finished++ },
        ).join()

        assertEquals(listOf(OsGeofenceTransition("home", GeofenceAction.ENTER, location)), sink.received.toList())
        assertEquals(1, finished)
    }

    @Test
    fun `deliver finishes even when the lookup or the sink fails`() = runTest {
        val sink = RecordingSink()
        var finished = 0

        AndroidGeofenceReceiver.deliver(
            OsGeofenceTransition("home", GeofenceAction.EXIT, null), this, sink,
            { throw SecurityException("denied") }, { finished++ },
        ).join()
        AndroidGeofenceReceiver.deliver(
            OsGeofenceTransition("home", GeofenceAction.EXIT, null), this, RecordingSink(IllegalStateException("x")),
            { null }, { finished++ },
        ).join()

        assertEquals(listOf(OsGeofenceTransition("home", GeofenceAction.EXIT, null)), sink.received.toList())
        assertEquals(2, finished)
    }

    @Test
    fun `deliver finishes when the scope is already cancelled`() = runTest {
        val scope = CoroutineScope(Job()).apply { cancel() }
        val sink = RecordingSink()
        val finished = AtomicInteger()

        val transition = OsGeofenceTransition("home", GeofenceAction.ENTER, null)
        AndroidGeofenceReceiver.deliver(transition, scope, sink, { null }, { finished.incrementAndGet() }).join()

        assertEquals(1, finished.get())
        assertTrue(sink.received.isEmpty())
    }

    @Test
    fun `only recent last known fixes are attached`() {
        val clock = FakeClock()
        val elapsedNanos = clock.elapsedRealtime() * 1_000_000
        val fresh = Fixtures.location(elapsedRealtimeNanos = elapsedNanos - 30_000L * 1_000_000)
        val stale = Fixtures.location(elapsedRealtimeNanos = elapsedNanos - 10 * 60_000L * 1_000_000)
        val wallOnlyFresh = Fixtures.location(time = clock.now() - 60_000, elapsedRealtimeNanos = 0)
        val wallOnlyStale = Fixtures.location(time = clock.now() - 3 * 60_000, elapsedRealtimeNanos = 0)

        assertTrue(AndroidGeofenceReceiver.isRecent(fresh, clock))
        assertFalse(AndroidGeofenceReceiver.isRecent(stale, clock))
        assertTrue(AndroidGeofenceReceiver.isRecent(wallOnlyFresh, clock))
        assertFalse(AndroidGeofenceReceiver.isRecent(wallOnlyStale, clock))
    }
}
