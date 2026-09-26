package com.brickssoft.locationtracking.model

import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventJsonTest {
    private val record = Fixtures.record()

    private fun keys(event: TrackingEvent): Set<String> = EventJson.payload(event).keys().asSequence().toSet()

    @Test
    fun `every event has its JS name and payload keys`() {
        val cases: List<Triple<TrackingEvent, String, Set<String>>> = listOf(
            Triple(TrackingEvent.Location(record), "location", RecordJson.toJson(record).keys().asSequence().toSet()),
            Triple(TrackingEvent.MotionChange(true, record), "motionchange", setOf("isMoving", "location")),
            Triple(
                TrackingEvent.ActivityChange(ActivitySample(ActivityType.WALKING, 80)),
                "activitychange",
                setOf("activity", "confidence"),
            ),
            Triple(
                TrackingEvent.ProviderChange(Fixtures.providerState()),
                "providerchange",
                setOf("enabled", "gps", "network", "permission", "accuracy", "backend"),
            ),
            Triple(TrackingEvent.Heartbeat(record), "heartbeat", setOf("location")),
            Triple(
                TrackingEvent.Geofence("home", GeofenceAction.EXIT, record, """{"a":1}"""),
                "geofence",
                setOf("identifier", "action", "location", "extras"),
            ),
            Triple(
                TrackingEvent.GeofencesChange(listOf(Fixtures.circle()), listOf("old")),
                "geofenceschange",
                setOf("on", "off"),
            ),
            Triple(
                TrackingEvent.Http(HttpResult(true, 200, "ok", listOf("u1"))),
                "http",
                setOf("success", "status", "responseText", "uuids"),
            ),
            Triple(
                TrackingEvent.ConnectivityChange(Connectivity(true, ConnectivityType.CELLULAR)),
                "connectivitychange",
                setOf("connected", "type"),
            ),
            Triple(TrackingEvent.PowerSaveChange(true), "powersavechange", setOf("isPowerSaveMode")),
            Triple(TrackingEvent.EnabledChange(false), "enabledchange", setOf("enabled")),
            Triple(TrackingEvent.NotificationAction("pause"), "notificationaction", setOf("id")),
            Triple(
                TrackingEvent.Authorization(true, 200, "none", """{"accessToken":"t"}"""),
                "authorization",
                setOf("success", "status", "error", "response"),
            ),
        )

        assertEquals(13, cases.map { it.second }.toSet().size)
        for ((event, name, expectedKeys) in cases) {
            assertEquals(name, EventJson.name(event))
            assertEquals(name, expectedKeys, keys(event))
        }
    }

    @Test
    fun `optional keys are omitted when absent`() {
        assertEquals(
            setOf("identifier", "action", "location"),
            keys(TrackingEvent.Geofence("home", GeofenceAction.ENTER, record, null)),
        )
        assertEquals(setOf("success", "status"), keys(TrackingEvent.Authorization(false, 401, null, null)))
    }

    @Test
    fun `payload values use wire names`() {
        val activity = EventJson.payload(TrackingEvent.ActivityChange(ActivitySample(ActivityType.ON_BICYCLE, 66)))
        assertEquals("on_bicycle", activity.getString("activity"))
        assertEquals(66, activity.getInt("confidence"))

        val connectivity = EventJson.payload(TrackingEvent.ConnectivityChange(Connectivity(false, ConnectivityType.NONE)))
        assertEquals("none", connectivity.getString("type"))

        val motion = EventJson.payload(TrackingEvent.MotionChange(false, record))
        assertEquals(record.uuid, motion.getJSONObject("location").getString("uuid"))

        val change = EventJson.payload(TrackingEvent.GeofencesChange(listOf(Fixtures.circle("a")), listOf("b")))
        assertEquals("a", change.getJSONArray("on").getJSONObject(0).getString("identifier"))
        assertEquals("b", change.getJSONArray("off").getString(0))

        val auth = EventJson.payload(TrackingEvent.Authorization(true, 200, null, """{"accessToken":"t"}"""))
        assertEquals("t", auth.getJSONObject("response").getString("accessToken"))
    }
}
