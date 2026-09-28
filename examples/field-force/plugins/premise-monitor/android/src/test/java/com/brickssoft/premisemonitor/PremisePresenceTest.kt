package com.brickssoft.premisemonitor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Presence validation while inside: `distance(centre, fix) − accuracy > radius` → `presence_violation` (F-12). */
@RunWith(RobolectricTestRunner::class)
internal class PremisePresenceTest : PremiseTestBase() {
    @Test
    fun geometry() {
        val (latIn, lonIn) = northOf(HQ, 100.0)
        assertEquals(100.0, HQ.distanceTo(Fix(latIn, lonIn, 10.0)), 0.01)
        assertFalse("inside", HQ.isCertainlyOutside(Fix(latIn, lonIn, 10.0)))

        val (latNear, lonNear) = northOf(HQ, 180.0)
        assertFalse("outside by less than the accuracy", HQ.isCertainlyOutside(Fix(latNear, lonNear, 40.0)))
        assertFalse("exactly on the boundary after accuracy", HQ.isCertainlyOutside(Fix(latNear, lonNear, 30.0 + 1e-6)))
        assertTrue("outside by more than the accuracy", HQ.isCertainlyOutside(Fix(latNear, lonNear, 20.0)))

        val (latFar, lonFar) = northOf(HQ, 400.0)
        assertTrue("outside", HQ.isCertainlyOutside(Fix(latFar, lonFar, 10.0)))
        assertTrue("an unknown accuracy counts as 0", HQ.isCertainlyOutside(Fix(latFar, lonFar, 0.0)))
    }

    @Test
    fun fixesOutsideTheRadiusWhileInsideAreFlagged() {
        val enteredAt = System.currentTimeMillis() - 10_000
        enter(enteredAt)
        val (latIn, lonIn) = northOf(HQ, 100.0)
        val (latNear, lonNear) = northOf(HQ, 180.0)
        val (latFar, lonFar) = northOf(HQ, 400.0)
        val inside = wireRecord("location", latIn, lonIn, accuracy = 10.0)
        val nearOutside = wireRecord("location", latNear, lonNear, accuracy = 40.0)
        val outside = wireRecord("location", latFar, lonFar, accuracy = 10.0)
        deliverRecord(inside)
        deliverRecord(nearOutside)
        deliverRecord(outside)

        val violation = premiseEntries("presence_violation").single()
        assertEquals("hq", violation.getString("premise_id"))
        assertEquals(400.0, violation.getDouble("distance_m"), 0.1)
        assertEquals(outside.getString("uuid"), violation.getJSONObject("location").getString("uuid"))
        assertTrue(violation.getString("detail"), violation.getString("detail").contains("> radius 150.0 m"))
        // The violation follows its record.
        val kinds = entries().takeLast(2).map { it.getString("kind") }
        assertEquals(listOf("record", "premise"), kinds)
    }

    @Test
    fun heartbeatsAreValidatedToo() {
        enter(System.currentTimeMillis() - 10_000)
        val (lat, lon) = northOf(HQ, 300.0)
        deliverRecord(wireRecord("heartbeat", lat, lon, accuracy = 20.0))
        assertEquals(300.0, premiseEntries("presence_violation").single().getDouble("distance_m"), 0.1)
    }

    @Test
    fun fixesAcquiredBeforeTheEnterAreNotEvidence() {
        val enteredAt = System.currentTimeMillis()
        enter(enteredAt)
        val (lat, lon) = northOf(HQ, 400.0)
        // A heartbeat carries the last known fix, acquired before the ENTER (stationary, GPS off).
        deliverRecord(wireRecord("heartbeat", lat, lon, timestamp = enteredAt - 60_000, recordedAt = enteredAt + 60_000))
        assertTrue(premiseEntries("presence_violation").isEmpty())
    }

    @Test
    fun recordsWithoutCoordinatesAreSkipped() {
        enter(System.currentTimeMillis() - 10_000)
        deliverRecord(wireRecord("heartbeat", latitude = null, longitude = null))
        deliverRecord(wireRecord("providerchange").put("coords", JSONObject().put("accuracy", 5.0)))
        assertTrue(premiseEntries("presence_violation").isEmpty())
    }

    @Test
    fun noValidationWhileOutsideOrUnknown() {
        startMonitoring()
        val (lat, lon) = northOf(HQ, 400.0)
        deliverRecord(wireRecord("location", lat, lon))
        deliverRecord(geofenceRecord("ENTER"))
        deliverRecord(geofenceRecord("EXIT", latitude = lat, longitude = lon))
        deliverRecord(wireRecord("location", lat, lon))
        assertTrue(premiseEntries("presence_violation").isEmpty())
        assertEquals(400.0, premiseEntries("exit").single().getDouble("distance_m"), 0.1)
    }

    private fun enter(at: Long) {
        startMonitoring()
        deliverRecord(geofenceRecord("ENTER", at = at))
        drainStartedServices()
    }
}
