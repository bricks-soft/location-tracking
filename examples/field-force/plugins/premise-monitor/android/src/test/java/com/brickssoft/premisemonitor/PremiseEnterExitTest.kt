package com.brickssoft.premisemonitor

import android.Manifest
import android.app.Notification
import android.content.Intent
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.TrackingException
import androidx.core.app.ServiceCompat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
internal class PremiseEnterExitTest : PremiseTestBase() {
    @Test
    fun startPersistsThePremiseAndAddsTheGeofence() {
        val status = startMonitoring(HQ.toJson(), "http://10.0.2.2:8787/premise-audit")

        assertTrue(status.getBoolean("monitoring"))
        assertEquals("hq", status.getJSONObject("premise").getString("id"))
        assertTrue(status.isNull("inside"))
        assertEquals("http://10.0.2.2:8787/premise-audit", status.getString("auditUrl"))

        val geofence = gateway.added.single()
        assertEquals("premise:hq", geofence.getString("identifier"))
        assertEquals(HQ.latitude, geofence.getDouble("latitude"), 0.0)
        assertEquals(HQ.longitude, geofence.getDouble("longitude"), 0.0)
        assertEquals(150.0, geofence.getDouble("radius"), 0.0)
        assertTrue(geofence.getBoolean("notifyOnEntry"))
        assertTrue(geofence.getBoolean("notifyOnExit"))
        assertFalse(geofence.getBoolean("notifyOnDwell"))

        val stored = PremiseState(app)
        assertTrue(stored.monitoring)
        assertEquals(HQ, stored.premise)
        assertEquals("http://10.0.2.2:8787/premise-audit", stored.auditUrl)

        val started = premiseEntries("monitoring_started").single()
        assertEquals("hq", started.getString("premise_id"))
    }

    @Test
    fun invalidArgumentsAreRejectedWithoutSideEffects() {
        val invalid = listOf(
            JSONObject().put("latitude", 1.0).put("longitude", 1.0).put("radius", 100.0) to null,
            HQ.toJson().put("radius", 0) to null,
            HQ.toJson().put("latitude", 91.0) to null,
            HQ.toJson().put("longitude", "east") to null,
            HQ.toJson().put("id", "x".repeat(Premise.MAX_ID_LENGTH + 1)) to null,
            HQ.toJson() to "ftp://example.com/audit",
            HQ.toJson() to "not a url",
        )
        for ((premise, url) in invalid) {
            val result = await { PremiseMonitorNative.start(app, premise, url, it) }
            val error = result.exceptionOrNull() as? TrackingException
            assertNotNull("rejected: $premise $url", error)
            assertEquals(ErrorCode.INVALID_ARGUMENT, error!!.code)
        }
        assertTrue(gateway.added.isEmpty())
        assertFalse(PremiseState(app).monitoring)
        assertTrue(entries().isEmpty())
    }

    @Test
    fun geofenceFailureRollsBackMonitoring() {
        gateway.addError = TrackingException(ErrorCode.TOO_MANY_GEOFENCES, "at most 100 geofences")
        val result = await { PremiseMonitorNative.start(app, HQ.toJson(), null, it) }

        assertEquals(ErrorCode.TOO_MANY_GEOFENCES, (result.exceptionOrNull() as TrackingException).code)
        assertFalse(status().getBoolean("monitoring"))
        val types = premiseEntries().map { it.getString("type") }
        assertEquals(listOf("monitoring_started", "monitoring_stopped"), types)
        assertTrue(premiseEntries("monitoring_stopped").single().getString("detail").contains("addGeofence failed"))
    }

    @Test
    fun enterStartsTheServiceAndPersistsInside() {
        startMonitoring()
        val enter = geofenceRecord("ENTER")
        deliverRecord(enter)

        assertEquals(true, PremiseState(app).inside)
        assertTrue(status().getBoolean("inside"))
        val entries = entries()
        val kinds = entries.map { if (it.getString("kind") == "premise") it.getString("type") else it.getString("kind") }
        assertEquals(listOf("monitoring_started", "record", "enter"), kinds)
        val enterEntry = entries.last()
        assertEquals("hq", enterEntry.getString("premise_id"))
        assertEquals(enter.getString("uuid"), enterEntry.getJSONObject("location").getString("uuid"))
        assertEquals(0.0, enterEntry.getDouble("distance_m"), 0.05)

        // The service enters the foreground at once with its own channel and notification.
        val intent = singleStartRequest("enter")
        val controller = runService(intent)
        val notification = shadowOf(controller.get()).lastForegroundNotification
        assertNotNull(notification)
        assertEquals(PremiseMonitorService.CHANNEL_ID, notification.channelId)
        assertEquals("Inside premise hq", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(PremiseMonitorService.NOTIFICATION_ID, shadowOf(controller.get()).lastForegroundNotificationId)

        val started = premiseEntries("service_started").single()
        assertEquals("enter", started.getString("detail"))
        assertEquals("hq", started.getString("premise_id"))
        assertTrue(status().getBoolean("serviceRunning"))
    }

    @Test
    fun exitStopsTheServiceAndPersistsOutside() {
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER"))
        val controller = runService(singleStartRequest("enter"))

        val exit = geofenceRecord("EXIT")
        deliverRecord(exit)
        assertEquals(false, PremiseState(app).inside)
        val exitEntry = premiseEntries("exit").single()
        assertEquals(exit.getString("uuid"), exitEntry.getJSONObject("location").getString("uuid"))

        val stop = drainStartedServices().single()
        assertEquals(PremiseMonitorService.ACTION_STOP, stop.action)
        assertEquals("exit", stop.getStringExtra(PremiseMonitorService.EXTRA_REASON))
        deliverCommand(controller, stop, 2)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()

        val stopped = premiseEntries("service_stopped").single()
        assertEquals("exit", stopped.getString("detail"))
        val status = status()
        assertFalse(status.getBoolean("serviceRunning"))
        assertFalse(status.getBoolean("inside"))
        val order = premiseEntries().map { it.getString("type") }
        assertEquals(listOf("monitoring_started", "enter", "service_started", "exit", "service_stopped"), order)
    }

    @Test
    fun otherGeofencesAndDwellAreOnlyAudited() {
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER", identifier = "warehouse"))
        deliverRecord(geofenceRecord("DWELL"))

        assertEquals(null, PremiseState(app).inside)
        assertEquals(listOf("monitoring_started"), premiseEntries().map { it.getString("type") })
        assertEquals(2, entries().count { it.getString("kind") == "record" })
        assertTrue(drainStartedServices().isEmpty())
    }

    @Test
    fun transitionsAreIgnoredWhileNotMonitoring() {
        deliverRecord(geofenceRecord("ENTER"))
        assertEquals(null, PremiseState(app).inside)
        assertTrue(premiseEntries().isEmpty())
        assertTrue(drainStartedServices().isEmpty())
    }

    @Test
    fun refusedStartIsAuditedNotThrown() {
        val services = FakeServiceControl(refusal = IllegalStateException("Not allowed to start service"))
        PremiseMonitorCore.install(newCore(services = services).also { core = it })
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER"))

        val failed = premiseEntries("service_start_failed").single()
        assertEquals("java.lang.IllegalStateException: Not allowed to start service", failed.getString("detail"))
        assertEquals("hq", failed.getString("premise_id"))
        assertEquals(true, PremiseState(app).inside)
        assertEquals(listOf("enter"), services.starts)
    }

    @Test
    @Config(sdk = [34])
    fun android14WithoutLocationPermissionIsRefusedBeforeStarting() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER"))

        val failed = premiseEntries("service_start_failed").single()
        assertTrue(failed.getString("detail"), failed.getString("detail").startsWith("java.lang.SecurityException"))
        assertTrue(drainStartedServices().isEmpty())
        assertEquals(0, PremiseMonitorService.pendingStarts.get())
    }

    @Test
    fun refusedStartForegroundInsideTheServiceIsAuditedAndStops() {
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER"))
        val intent = singleStartRequest("enter")
        mockkStatic(ServiceCompat::class)
        try {
            // What Android 14 does for a location service started from the background without while-in-use access.
            every { ServiceCompat.startForeground(any(), any(), any(), any()) } throws
                SecurityException("Starting FGS with type location requires permissions")
            val controller = runService(intent)
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            controller.destroy()
        } finally {
            unmockkStatic(ServiceCompat::class)
        }

        val failed = premiseEntries("service_start_failed").single()
        assertEquals(
            "java.lang.SecurityException: Starting FGS with type location requires permissions",
            failed.getString("detail"),
        )
        assertEquals("hq", failed.getString("premise_id"))
        assertTrue("no service_stopped for a service that never ran in the foreground", premiseEntries("service_stopped").isEmpty())
        assertTrue(premiseEntries("service_started").isEmpty())
        assertFalse(PremiseMonitorService.isRunning)
        assertEquals(0, PremiseMonitorService.pendingStarts.get())
    }

    @Test
    fun trackingStopWhileInsideStopsTheServiceAndForgetsInside() {
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER"))
        val controller = runService(singleStartRequest("enter"))

        deliverRecord(wireRecord("tracking_stop", reason = "stop_after_elapsed"))
        assertEquals(null, PremiseState(app).inside)
        val stop = drainStartedServices().single()
        assertEquals("tracking_stop: stop_after_elapsed", stop.getStringExtra(PremiseMonitorService.EXTRA_REASON))
        deliverCommand(controller, stop, 2)
        controller.destroy()
        assertEquals("tracking_stop: stop_after_elapsed", premiseEntries("service_stopped").single().getString("detail"))
        assertTrue(status().isNull("inside"))
        assertTrue("still monitoring", status().getBoolean("monitoring"))
    }

    @Test
    fun stopMonitoringRemovesTheGeofenceAndStopsTheService() {
        val url = "http://10.0.2.2:8787/premise-audit"
        startMonitoring(auditUrl = url)
        deliverRecord(geofenceRecord("ENTER"))
        val controller = runService(singleStartRequest("enter"))

        val status = await { PremiseMonitorNative.stop(app, it) }.getOrThrow()
        assertFalse(status.getBoolean("monitoring"))
        assertTrue(status.isNull("premise"))
        assertTrue(status.isNull("inside"))
        assertEquals("the audit URL stays for pending entries", url, status.getString("auditUrl"))
        assertEquals(listOf("premise:hq"), gateway.removed.toList())

        val stop = drainStartedServices().single()
        assertEquals("stop_monitoring", stop.getStringExtra(PremiseMonitorService.EXTRA_REASON))
        deliverCommand(controller, stop, 2)
        controller.destroy()
        val order = premiseEntries().map { it.getString("type") }
        assertEquals(listOf("monitoring_started", "enter", "service_started", "monitoring_stopped", "service_stopped"), order)
        assertEquals("hq", premiseEntries("service_stopped").single().getString("premise_id"))

        // Later transitions of the old geofence change nothing.
        deliverRecord(geofenceRecord("ENTER"))
        assertTrue(drainStartedServices().isEmpty())
    }

    @Test
    fun theSamePremiseAgainKeepsInsideAndDoesNotReAddTheGeofence() {
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER"))
        drainStartedServices()

        val status = startMonitoring()
        assertTrue(status.getBoolean("inside"))
        assertEquals(1, gateway.added.size)
        assertEquals(1, premiseEntries("monitoring_started").size)

        // The tracking plugin lost the geofence (for example removeGeofences() by the app): it is added again.
        gateway.geofences.clear()
        startMonitoring()
        assertEquals(2, gateway.added.size)
        val restarted = premiseEntries("monitoring_started")
        assertEquals(2, restarted.size)
        assertTrue(restarted.last().getString("detail").startsWith("geofence re-registered"))
        assertEquals(true, PremiseState(app).inside)
    }

    @Test
    fun aDifferentPremiseReplacesTheOldOne() {
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER"))
        drainStartedServices()
        PremiseMonitorService.isRunning = true // as if the service ran

        val other = Premise("warehouse", null, 24.80, 46.70, 200.0)
        val status = startMonitoring(other.toJson())
        assertEquals("warehouse", status.getJSONObject("premise").getString("id"))
        assertTrue(status.isNull("inside"))
        assertEquals(listOf("premise:hq"), gateway.removed.toList())
        assertEquals("premise:warehouse", gateway.added.last().getString("identifier"))
        val stop = drainStartedServices().single { it.action == PremiseMonitorService.ACTION_STOP }
        assertEquals("premise_changed", stop.getStringExtra(PremiseMonitorService.EXTRA_REASON))
    }

    @Test
    @Config(sdk = [29, 30, 33, 35])
    fun enterAndExitOnOtherApiLevels() {
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER"))
        val controller = runService(singleStartRequest("enter"))
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        deliverRecord(geofenceRecord("EXIT"))
        deliverCommand(controller, drainStartedServices().single(), 2)
        controller.destroy()
        assertEquals(
            listOf("monitoring_started", "enter", "service_started", "exit", "service_stopped"),
            premiseEntries().map { it.getString("type") },
        )
    }

    @Test
    @Config(sdk = [33])
    fun belowAndroid14TheOsDecidesWithoutAPermissionPreCheck() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER"))
        singleStartRequest("enter")
        assertTrue(premiseEntries("service_start_failed").isEmpty())
    }

    @Test
    fun aStopDuringTheGeofenceAddLeavesNoGeofenceBehind() {
        gateway.deferAdds = true
        val started = java.util.concurrent.CountDownLatch(1)
        PremiseMonitorNative.start(app, HQ.toJson(), null) { started.countDown() }
        core.awaitIdle()
        val stopped = await { PremiseMonitorNative.stop(app, it) }.getOrThrow()
        assertFalse(stopped.getBoolean("monitoring"))

        gateway.releaseAdds()
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
        core.awaitIdle()
        assertEquals(listOf("premise:hq", "premise:hq"), gateway.removed.toList())
        assertTrue(gateway.geofences.isEmpty())
        assertFalse(status().getBoolean("monitoring"))
    }

    @Test
    fun anEnterRightAfterAnExitKeepsTheServiceRunning() {
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER"))
        val controller = runService(singleStartRequest("enter"))

        // EXIT queues a stop; an ENTER arrives before the main thread handled it.
        deliverRecord(geofenceRecord("EXIT"))
        deliverRecord(geofenceRecord("ENTER"))
        val commands = drainStartedServices()
        assertEquals(
            listOf(PremiseMonitorService.ACTION_STOP, PremiseMonitorService.ACTION_START),
            commands.map { it.action },
        )
        deliverCommand(controller, commands[0], 2)
        deliverCommand(controller, commands[1], 3)
        assertEquals(true, PremiseState(app).inside)
        assertTrue("the last command was a start", AndroidServiceControl.isRunningOrStarting)
        // The start after the stop cleared the stop reason, so a later destroy is not reported as "exit".
        controller.destroy()
        assertEquals("destroyed", premiseEntries("service_stopped").single().getString("detail"))
    }

    @Test
    fun aDuplicateEnterWhileRunningSendsNoSecondStart() {
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER"))
        runService(singleStartRequest("enter"))
        deliverRecord(geofenceRecord("ENTER"))
        assertTrue(drainStartedServices().isEmpty())
        assertEquals(2, premiseEntries("enter").size)
        assertEquals(1, premiseEntries("service_started").size)
    }

    @Test
    fun trackingStopIsValidatedBeforeInsideIsForgotten() {
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER", at = System.currentTimeMillis() - 10_000))
        drainStartedServices()
        val (lat, lon) = northOf(HQ, 400.0)
        deliverRecord(wireRecord("tracking_stop", lat, lon, reason = "stop"))
        val types = premiseEntries().map { it.getString("type") }
        assertEquals(listOf("monitoring_started", "enter", "presence_violation"), types)
        assertEquals(null, PremiseState(app).inside)
    }

    @Test
    fun theSameAreaWithANewNameKeepsInside() {
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER"))
        drainStartedServices()

        val renamed = startMonitoring(HQ.copy(name = "Head Office (new)").toJson())
        assertTrue(renamed.getBoolean("inside"))
        assertEquals("Head Office (new)", renamed.getJSONObject("premise").getString("name"))
        assertEquals(1, gateway.added.size)
        assertTrue(drainStartedServices().none { it.action == PremiseMonitorService.ACTION_STOP })
        assertEquals(2, premiseEntries("monitoring_started").size)
    }

    @Test
    fun replacingAPremiseClosesTheOldOneInTheAudit() {
        startMonitoring()
        startMonitoring(Premise("warehouse", null, 24.80, 46.70, 200.0).toJson())
        val entries = premiseEntries()
        assertEquals(listOf("monitoring_started", "monitoring_stopped", "monitoring_started"), entries.map { it.getString("type") })
        assertEquals(listOf("hq", "hq", "warehouse"), entries.map { it.getString("premise_id") })
        assertEquals("replaced by premise warehouse", entries[1].getString("detail"))
    }

    @Test
    fun aFailedAddAfterAnEnterStopsTheServiceAndRemovesTheGeofence() {
        gateway.deferAdds = true
        val result = java.util.concurrent.atomic.AtomicReference<Result<JSONObject>>()
        val done = java.util.concurrent.CountDownLatch(1)
        PremiseMonitorNative.start(app, HQ.toJson(), null) { result.set(it); done.countDown() }
        core.awaitIdle()
        // The OS reports the initial ENTER before the tracking plugin answers the add.
        deliverRecord(geofenceRecord("ENTER"))
        val controller = runService(singleStartRequest("enter"))

        gateway.addError = TrackingException(ErrorCode.INTERNAL, "registration failed")
        gateway.releaseAdds()
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(result.get().isFailure)
        core.awaitIdle()
        assertFalse(status().getBoolean("monitoring"))
        assertEquals(listOf("premise:hq"), gateway.removed.toList())
        val stop = drainStartedServices().single()
        assertEquals(PremiseMonitorService.ACTION_STOP, stop.action)
        assertEquals("monitoring_start_failed", stop.getStringExtra(PremiseMonitorService.EXTRA_REASON))
        deliverCommand(controller, stop, 2)
        controller.destroy()
        assertEquals("monitoring_start_failed", premiseEntries("service_stopped").single().getString("detail"))
    }

    @Test
    fun aFailedReAddOfTheSamePremiseRollsBack() {
        startMonitoring()
        gateway.geofences.clear()
        gateway.addError = TrackingException(ErrorCode.TOO_MANY_GEOFENCES, "at most 100 geofences")
        val result = await { PremiseMonitorNative.start(app, HQ.toJson(), null, it) }
        assertEquals(ErrorCode.TOO_MANY_GEOFENCES, (result.exceptionOrNull() as TrackingException).code)
        assertFalse(status().getBoolean("monitoring"))
        assertEquals(listOf("monitoring_started", "monitoring_stopped"), premiseEntries().map { it.getString("type") })
    }

    @Test
    fun aThrowingGeofenceCallRollsBack() {
        gateway.addThrows = IllegalStateException("tracking plugin not initialized")
        val result = await { PremiseMonitorNative.start(app, HQ.toJson(), null, it) }
        assertEquals(ErrorCode.INTERNAL, (result.exceptionOrNull() as TrackingException).code)
        assertFalse(status().getBoolean("monitoring"))
        assertTrue(premiseEntries("monitoring_stopped").single().getString("detail").contains("IllegalStateException"))
    }

    @Test
    fun aStopDuringAReAddLeavesNoGeofenceBehind() {
        startMonitoring()
        gateway.geofences.clear()
        gateway.deferAdds = true
        val done = java.util.concurrent.CountDownLatch(1)
        PremiseMonitorNative.start(app, HQ.toJson(), null) { done.countDown() }
        core.awaitIdle()
        await { PremiseMonitorNative.stop(app, it) }.getOrThrow()
        gateway.releaseAdds()
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        core.awaitIdle()
        assertTrue(gateway.geofences.isEmpty())
        assertEquals(
            "no monitoring_started after the stop",
            listOf("monitoring_started", "monitoring_stopped"),
            premiseEntries().map { it.getString("type") },
        )
    }

    @Test
    fun stopCommandBeforeAnyStartDoesNotNeedTheForeground() {
        val controller = runService(Intent(app, PremiseMonitorService::class.java).setAction(PremiseMonitorService.ACTION_STOP))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
        core.awaitIdle()
        assertTrue(premiseEntries().isEmpty())
    }
}
