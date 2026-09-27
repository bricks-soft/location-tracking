package com.brickssoft.premisemonitor

import com.brickssoft.locationtracking.core.Iso8601
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class PremiseAuditListenerTest : PremiseTestBase() {
    @Test
    fun constructorDoesNoWork() {
        PremiseMonitorCore.install(null)
        PremiseAuditListener()
        assertNull("the listener constructor must not create the core", PremiseMonitorCore.peek())
    }

    @Test
    fun recordsAndEventsBecomeEntriesInEmissionOrder() {
        val location = wireRecord("location")
        val heartbeat = wireRecord("heartbeat")
        listener.onRecord(app, location)
        listener.onEvent(app, "location", JSONObject(location.toString()))
        listener.onRecord(app, heartbeat)
        listener.onEvent(app, "heartbeat", JSONObject().put("location", heartbeat))
        listener.onEvent(app, "connectivitychange", JSONObject().put("connected", true).put("type", "wifi"))

        val entries = entries()
        assertEquals(listOf("record", "event", "record", "event", "event"), entries.map { it.getString("kind") })
        assertEquals(location.getString("uuid"), entries[0].getJSONObject("record").getString("uuid"))
        assertEquals("location", entries[1].getString("name"))
        assertEquals(location.getString("uuid"), entries[1].getJSONObject("payload").getString("uuid"))
        assertEquals(heartbeat.getString("uuid"), entries[2].getJSONObject("record").getString("uuid"))
        assertEquals("heartbeat", entries[3].getString("name"))
        assertEquals(heartbeat.getString("uuid"), entries[3].getJSONObject("payload").getJSONObject("location").getString("uuid"))
        assertEquals("wifi", entries[4].getJSONObject("payload").getString("type"))

        assertEquals("entry ids are unique", entries.size, entries.map { it.getString("id") }.toSet().size)
        for (entry in entries) {
            assertEquals("manifest", entry.getString("source"))
            assertEquals(android.os.Process.myPid(), entry.getInt("pid"))
            assertFalse(entry.getBoolean("js"))
            assertNotNull("at is ISO-8601 UTC ms: ${entry.getString("at")}", Iso8601.parse(entry.getString("at")))
        }
    }

    @Test
    fun manyInterleavedCallbacksKeepTheirOrder() {
        repeat(60) { i ->
            if (i % 2 == 0) {
                listener.onRecord(app, wireRecord("location").put("uuid", "r$i"))
            } else {
                listener.onEvent(app, "location", JSONObject().put("uuid", "e$i"))
            }
        }
        val ids = entries().map {
            if (it.getString("kind") == "record") it.getJSONObject("record").getString("uuid") else it.getJSONObject("payload").getString("uuid")
        }
        assertEquals((0 until 60).map { if (it % 2 == 0) "r$it" else "e$it" }, ids)
    }

    @Test
    fun theCallerMayReuseItsJsonAfterTheCallback() {
        val record = wireRecord("location").put("uuid", "original")
        listener.onRecord(app, record)
        record.put("uuid", "changed")
        assertEquals("original", entries().single().getJSONObject("record").getString("uuid"))
    }

    @Test
    fun jsFlagTurnsTrueOncePluginIsLoaded() {
        listener.onRecord(app, wireRecord("location"))
        core.awaitIdle()
        PremiseMonitorPlugin().load()
        listener.onRecord(app, wireRecord("location"))
        listener.onEvent(app, "heartbeat", JSONObject())

        val entries = entries()
        assertEquals(listOf(false, true, true), entries.map { it.getBoolean("js") })
        assertTrue(ProcessInfo.jsLoaded)
    }
}
