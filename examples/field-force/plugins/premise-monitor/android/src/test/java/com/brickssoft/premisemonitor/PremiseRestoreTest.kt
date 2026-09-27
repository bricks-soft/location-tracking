package com.brickssoft.premisemonitor

import com.brickssoft.locationtracking.core.Iso8601
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicLong

/** A new process (kill -9 + START_STICKY restore, reboot): the listener restarts the service while inside (F-09/F-10). */
@RunWith(RobolectricTestRunner::class)
internal class PremiseRestoreTest : PremiseTestBase() {
    @Test
    fun theFirstRecordOfANewProcessRestartsTheServiceWhenInside() {
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER"))
        runService(singleStartRequest("enter"))
        PremiseMonitorPlugin().load() // the old process had JS

        simulateNewProcess()
        val boot = wireRecord("tracking_start", reason = "boot")
        deliverRecord(boot)

        val controller = runService(singleStartRequest("restore"))
        assertTrue(PremiseMonitorService.isRunning)
        val entries = entries()
        val bootEntry = entries.single { it.optJSONObject("record")?.optString("uuid") == boot.getString("uuid") }
        assertFalse("no JS in the restored process", bootEntry.getBoolean("js"))
        val started = premiseEntries("service_started")
        assertEquals(listOf("enter", "restore"), started.map { it.getString("detail") })
        assertFalse(started.last().getBoolean("js"))
        assertTrue(status().getBoolean("inside"))

        // Later records do not start it again.
        deliverRecord(wireRecord("heartbeat"))
        assertTrue(drainStartedServices().isEmpty())
        controller.destroy()
    }

    @Test
    fun noRestartWhenOutsideOrUnknownOrNotMonitoring() {
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER"))
        drainStartedServices()
        deliverRecord(geofenceRecord("EXIT"))

        simulateNewProcess()
        deliverRecord(wireRecord("tracking_start", reason = "boot"))
        assertTrue("outside", drainStartedServices().isEmpty())

        simulateNewProcess()
        startMonitoring(Premise("other", null, 1.0, 1.0, 100.0).toJson())
        deliverRecord(wireRecord("heartbeat"))
        assertTrue("unknown", drainStartedServices().none { it.action == PremiseMonitorService.ACTION_START })

        await { PremiseMonitorNative.stop(app, it) }
        simulateNewProcess()
        deliverRecord(wireRecord("heartbeat"))
        assertTrue("not monitoring", drainStartedServices().isEmpty())
    }

    @Test
    fun insideAndEnterTimeSurviveANewProcess() {
        startMonitoring()
        val enteredAt = System.currentTimeMillis() - 60_000
        deliverRecord(geofenceRecord("ENTER", at = enteredAt))
        simulateNewProcess()
        val state = PremiseState(app)
        assertEquals(true, state.inside)
        assertEquals(Iso8601.parse(Iso8601.format(enteredAt)), state.enteredAt)
        assertTrue(state.monitoring)
        assertEquals(HQ, state.premise)
    }

    @Test
    fun aRefusedRestartIsRetriedAtMostOncePerBackoff() {
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER"))
        drainStartedServices()

        val now = AtomicLong(System.currentTimeMillis())
        val services = FakeServiceControl(refusal = IllegalStateException("background start not allowed"))
        simulateNewProcess(services = services, clock = { now.get() })

        deliverRecord(wireRecord("tracking_start", reason = "restore"))
        deliverRecord(wireRecord("heartbeat"))
        now.addAndGet(PremiseMonitorCore.RESTART_BACKOFF_MS - 1)
        deliverRecord(wireRecord("heartbeat"))
        assertEquals(listOf("restore"), services.starts)

        now.addAndGet(2)
        services.refusal = null
        deliverRecord(wireRecord("heartbeat"))
        assertEquals(listOf("restore", "retry"), services.starts)
        assertEquals(1, premiseEntries("service_start_failed").size)
        assertTrue(services.running)
    }
}
