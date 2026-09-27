package com.brickssoft.locationtracking.integration

import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.model.GeofenceAction
import com.brickssoft.locationtracking.model.RecordEvent
import com.brickssoft.locationtracking.provider.OsGeofenceTransition
import com.brickssoft.locationtracking.testing.FakeServiceController
import com.brickssoft.locationtracking.testing.Fixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real component graph across process lifecycles: a process dies ([FullStackProcess.kill]) and a new one is built
 * over the same SharedPreferences and SQLite file, as after a task killer, a reboot or a refused foreground service.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class ProcessLifecycleIntegrationTest : FullStackTestBase() {
    // ------------------------------------------------------------------ k

    @Test
    fun `k - restore(boot) in a new process posts tracking_start boot and uploads what the dead process queued`() =
        runTest {
            val serverUp = AtomicBoolean(false)
            server.respond = { _, _, _ -> if (serverUp.get()) IngestServer.ok() else IngestServer.status(500) }
            val a = newProcess(DATABASE)
            a.engine.ready(config(), reset = true)
            start(a)
            advance(10_000)
            emit(a, gps.fix(100.0))
            advance(3_000)
            emit(a, gps.fix(140.0))
            val queued = a.locationStore.list()
            assertEquals(
                listOf(RecordEvent.TRACKING_START, RecordEvent.MOTIONCHANGE, RecordEvent.MOTIONCHANGE, RecordEvent.LOCATION),
                queued.map { it.event },
            )
            assertTrue(server.uploads.isNotEmpty())
            assertTrue(server.uploads.none { it.accepted })
            val odometer = a.configStore.runtime.value.odometer
            assertTrue(odometer > 0)
            val bootCountBefore = clock.bootCount()

            // The device reboots: the process dies, every alarm is wiped, the elapsed clock restarts.
            a.kill()
            alarms.reboot()
            advance(60_000)
            clock.reboot(elapsedAfterBoot = 30_000L)
            serverUp.set(true)
            val b = newProcess(DATABASE)
            assertTrue(b.configStore.runtime.value.enabled)
            assertEquals(server.url(), b.configStore.config.value.http.url)
            located(b, gps.fix(150.0, speed = 0f))
            val restartedAt = clock.now()

            b.engine.restore("boot")
            runCurrent()

            val accepted = server.accepted()
            assertEquals(queued.map { it.uuid }, accepted.take(queued.size).map { it.getString("uuid") })
            for ((record, sent) in queued.zip(accepted)) {
                assertEquals(record.recordedAt, Wire.recordedAt(sent))
                assertEquals(restartedAt, Wire.sentAt(sent))
                assertEquals(bootCountBefore, sent.getInt("boot_count"))
            }
            val afterRestart = accepted.drop(queued.size)
            assertEquals(listOf("tracking_start", "motionchange"), afterRestart.map { it.getString("event") })
            assertEquals("boot", afterRestart[0].getString("reason"))
            assertEquals(bootCountBefore + 1, afterRestart[0].getInt("boot_count"))
            assertEquals(30_000L, afterRestart[0].getLong("elapsed_realtime_ms"))
            assertFalse(afterRestart[1].getBoolean("is_moving"))
            assertEquals(0, b.locationStore.count())
            assertTrue(b.configStore.runtime.value.enabled)
            assertEquals(odometer, b.configStore.runtime.value.odometer, 1e-9)
            assertEquals(odometer, afterRestart[0].getDouble("odometer"), 1e-9)
            // The new process armed its own heartbeat (the dead one's listener alarm died with it).
            assertEquals(clock.elapsedRealtime() + MIN_MS, alarms.listener()!!.triggerAtMs)
            assertEquals(clock.elapsedRealtime() + MIN_MS, alarms.intent()!!.triggerAtMs)
            assertHealthy(a, b)
        }

    @Test
    fun `a heartbeat alarm in a killed process posts the heartbeat and restores tracking`() = runTest {
        val a = newProcess(DATABASE)
        a.engine.ready(config(), reset = true)
        val t0 = clock.elapsedRealtime()
        val w0 = clock.now()
        val origin = start(a)
        a.kill()
        assertNull(alarms.listener())
        val alarm = alarms.intent()!!
        assertEquals(t0 + MIN_MS, alarm.triggerAtMs)

        advance(MIN_MS)
        val b = newProcess(DATABASE)
        located(b, gps.fix(2.0, speed = 0f))
        b.deliverHeartbeatIntent(alarm)
        runCurrent()

        val afterRestart = server.received().drop(2)
        assertEquals(listOf("heartbeat", "tracking_start", "motionchange"), afterRestart.map { it.getString("event") })
        val heartbeat = afterRestart[0]
        assertEquals(origin.latitude, Wire.latitude(heartbeat), 1e-9) // the dead process's last known location
        assertEquals(w0 + MIN_MS, Wire.recordedAt(heartbeat))
        assertEquals("restore", afterRestart[1].getString("reason"))
        assertTrue(server.uploads.all { it.accepted })
        assertEquals(1, b.service.startCalls)
        assertTrue(b.configStore.runtime.value.enabled)
        assertEquals(w0 + MIN_MS, b.configStore.runtime.value.lastHeartbeatAt)
        assertEquals(clock.elapsedRealtime() + MIN_MS, alarms.listener()!!.triggerAtMs)
        assertEquals(0, b.locationStore.count())
        assertHealthy(a, b)
    }

    @Test
    fun `relaunching the app (ready in a new process) restores tracking with reason restore`() = runTest {
        val a = newProcess(DATABASE)
        a.engine.ready(config(), reset = true)
        start(a)
        a.kill()

        advance(5 * MINUTE)
        val b = newProcess(DATABASE)
        val here = located(b, gps.fix(20.0, speed = 0f))
        val state = b.engine.ready(config(), reset = true)
        runCurrent()

        assertTrue(state.runtime.enabled)
        val afterRestart = server.received().drop(2)
        assertEquals(listOf("tracking_start", "motionchange"), afterRestart.map { it.getString("event") })
        assertEquals("restore", afterRestart[0].getString("reason"))
        assertEquals(here.latitude, Wire.latitude(afterRestart[1]), 1e-9)
        assertEquals(1, b.service.startCalls)
        assertTrue(b.providers.locationBackend.isRequesting)
        assertEquals(clock.elapsedRealtime() + MIN_MS, alarms.listener()!!.triggerAtMs)
        assertHealthy(a, b)
    }

    @Test
    fun `a permission revoked while the process was dead ends tracking with tracking_stop permission_denied`() = runTest {
        val a = newProcess(DATABASE)
        a.engine.ready(config(), reset = true)
        val origin = start(a)
        a.kill() // Android kills the process when a location permission is revoked

        advance(60_000)
        val b = newProcess(DATABASE)
        b.permissions.denyAll()
        b.engine.restore("restore")
        runCurrent()

        val stop = server.received().last()
        assertEquals("tracking_stop", stop.getString("event"))
        assertEquals("permission_denied", stop.getString("reason"))
        assertEquals(origin.latitude, Wire.latitude(stop), 1e-9)
        assertFalse(b.configStore.runtime.value.enabled)
        assertEquals(0, b.service.startCalls)
        assertTrue(alarms.scheduled().isEmpty())
        assertHealthy(a, b)
    }

    @Test
    fun `a geofence that was entered before the process died is not entered again after restore`() = runTest {
        val a = newProcess(DATABASE)
        a.engine.ready(config(), reset = true)
        start(a)
        a.geofences.add(listOf(Fixtures.circle(identifier = "home", radius = 200f)))
        runCurrent()
        advance(10_000)
        a.geofences.onGeofenceTransitions(listOf(OsGeofenceTransition("home", GeofenceAction.ENTER, gps.fix(5.0))))
        runCurrent()
        assertEquals(listOf("ENTER"), geofenceActions())
        a.kill()

        // An OEM task killer: the heartbeat's PendingIntent alarm restores tracking in a new process, which
        // registers the geofences again; the OS confirms "inside" with its initial ENTER trigger. The restore's
        // initial fix reaches the geofence manager first and confirms the restored "inside" state, so the OS
        // trigger is a duplicate. (If the trigger won that race, the manager would report ENTER again: restored
        // state is deliberately not trusted until confirmed.)
        advance(5 * MINUTE)
        val b = newProcess(DATABASE)
        located(b, gps.fix(6.0, speed = 0f))
        b.engine.restore("restore")
        runCurrent()
        assertTrue("home" in b.providers.geofenceBackend.registered)
        b.geofences.onGeofenceTransitions(listOf(OsGeofenceTransition("home", GeofenceAction.ENTER, gps.fix(6.0))))
        runCurrent()

        assertEquals(listOf("ENTER"), geofenceActions())

        // Leaving is still reported.
        advance(60_000)
        b.geofences.onGeofenceTransitions(listOf(OsGeofenceTransition("home", GeofenceAction.EXIT, gps.fix(400.0))))
        runCurrent()
        assertEquals(listOf("ENTER", "EXIT"), geofenceActions())
        assertHealthy(a, b)
    }

    // ------------------------------------------------------------------ j

    @Test
    fun `j - a refused foreground service on restore posts tracking_stop service_start_failed and disables tracking`() =
        runTest {
            val a = newProcess(DATABASE)
            a.engine.ready(config(), reset = true)
            val origin = start(a)
            a.kill()

            advance(60_000)
            val b = newProcess(DATABASE, service = FakeServiceController().apply { startResult = false })
            b.engine.restore("restore")
            runCurrent()

            val stop = server.received().last()
            assertEquals("tracking_stop", stop.getString("event"))
            assertEquals("service_start_failed", stop.getString("reason"))
            assertEquals(origin.latitude, Wire.latitude(stop), 1e-9)
            assertEquals(listOf("tracking_start", "motionchange", "tracking_stop"), server.acceptedEvents())
            assertFalse(b.configStore.runtime.value.enabled)
            assertEquals(listOf(false), b.eventsOf<TrackingEvent.EnabledChange>().map { it.enabled })
            assertEquals(1, b.service.startCalls)
            assertFalse(b.providers.locationBackend.isRequesting)
            assertEquals(1, b.providers.activityBackend.stopCalls)
            // The dead process's PendingIntent alarm is cancelled too: nothing would restore tracking again.
            assertTrue(alarms.scheduled().isEmpty())
            assertEquals(0, b.locationStore.count())

            // A later restore (e.g. boot) has nothing to do.
            b.engine.restore("boot")
            runCurrent()
            assertEquals(3, server.received().size)
            assertHealthy(a, b)
        }

    @Test
    fun `j - onServiceStartFailed during a running session posts tracking_stop service_start_failed`() = runTest {
        val p = newProcess(DATABASE)
        p.engine.ready(config(), reset = true)
        start(p)
        advance(20_000)

        p.engine.onServiceStartFailed("ForegroundServiceStartNotAllowedException: startForegroundService() not allowed")
        runCurrent()

        val stop = server.received().last()
        assertEquals("tracking_stop", stop.getString("event"))
        assertEquals("service_start_failed", stop.getString("reason"))
        assertFalse(p.configStore.runtime.value.enabled)
        assertEquals(listOf(true, false), p.eventsOf<TrackingEvent.EnabledChange>().map { it.enabled })
        assertEquals(1, p.service.stopCalls)
        assertFalse(p.providers.locationBackend.isRequesting)
        assertTrue(alarms.scheduled().isEmpty())
        assertEquals(0, p.locationStore.count())
        assertHealthy(p)
    }

    private companion object {
        const val DATABASE = "lt-integration.db"
    }
}
