package com.brickssoft.premisemonitor

import com.brickssoft.locationtracking.core.ErrorCode
import com.getcapacitor.Bridge
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** JSON shapes of docs/e2e/architecture.md §7 (`PremiseStatus`, `PremiseAuditEntry`) and the JS plugin layer. */
@RunWith(RobolectricTestRunner::class)
internal class PremiseShapesTest : PremiseTestBase() {
    private val statusKeys = setOf(
        "monitoring", "premise", "inside", "serviceRunning", "auditUrl", "lastEntryAt", "pendingUploads",
    )
    private val commonKeys = setOf("id", "kind", "at", "pid", "js", "source")

    @Test
    fun statusBeforeAnything() {
        val status = status()
        assertEquals(statusKeys, status.keys().asSequence().toSet())
        assertFalse(status.getBoolean("monitoring"))
        assertTrue(status.isNull("premise"))
        assertTrue(status.isNull("inside"))
        assertFalse(status.getBoolean("serviceRunning"))
        assertTrue(status.isNull("auditUrl"))
        assertTrue(status.isNull("lastEntryAt"))
        assertEquals(0, status.getInt("pendingUploads"))
    }

    @Test
    fun statusWhileMonitoringInside() {
        startMonitoring(auditUrl = "http://127.0.0.1:1/premise-audit")
        deliverRecord(geofenceRecord("ENTER"))
        val status = status()
        assertEquals(statusKeys, status.keys().asSequence().toSet())
        assertTrue(status.getBoolean("monitoring"))
        assertEquals(
            setOf("id", "name", "latitude", "longitude", "radius"),
            status.getJSONObject("premise").keys().asSequence().toSet(),
        )
        assertEquals(150.0, status.getJSONObject("premise").getDouble("radius"), 0.0)
        assertEquals(true, status.get("inside"))
        assertEquals("http://127.0.0.1:1/premise-audit", status.getString("auditUrl"))
        assertEquals(entries().last().getString("at"), status.getString("lastEntryAt"))
        assertTrue(status.get("pendingUploads") is Int)
    }

    @Test
    fun entryShapes() {
        startMonitoring()
        listener.onEvent(app, "heartbeat", JSONObject().put("location", wireRecord("heartbeat")))
        deliverRecord(geofenceRecord("ENTER"))
        val entries = entries()

        val started = entries.first { it.optString("type") == "monitoring_started" }
        assertEquals(commonKeys + setOf("type", "premise_id", "location", "detail"), started.keys().asSequence().toSet())
        assertTrue(started.isNull("location"))

        val event = entries.first { it.getString("kind") == "event" }
        assertEquals(commonKeys + setOf("name", "payload"), event.keys().asSequence().toSet())

        val record = entries.first { it.getString("kind") == "record" }
        assertEquals(commonKeys + setOf("record"), record.keys().asSequence().toSet())
        assertEquals("geofence", record.getJSONObject("record").getString("event"))

        val enter = entries.first { it.optString("type") == "enter" }
        assertEquals(commonKeys + setOf("type", "premise_id", "location", "distance_m"), enter.keys().asSequence().toSet())
        assertTrue(enter.get("distance_m") is Number)

        for (entry in entries) {
            assertTrue(entry.getString("kind") in setOf("record", "event", "premise"))
            assertTrue(entry.get("pid") is Int)
            assertTrue(entry.get("js") is Boolean)
            assertEquals("manifest", entry.getString("source"))
            assertTrue(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""").matches(entry.getString("at")))
        }
    }

    @Test
    fun auditLogLimitReturnsTheNewestLast() {
        repeat(5) { i -> listener.onRecord(app, wireRecord("location").put("uuid", "r$i")) }
        val newest = entries(limit = 2).map { it.getJSONObject("record").getString("uuid") }
        assertEquals(listOf("r3", "r4"), newest)
        assertEquals(5, entries(limit = 0).size)
        assertEquals(5, entries(limit = -1).size)
    }

    // ---- JS layer

    @Test
    fun pluginGetAuditLogResolvesWithEntries() {
        listener.onRecord(app, wireRecord("location").put("uuid", "r0"))
        core.awaitIdle()
        val plugin = plugin()
        val call = mockk<PluginCall>(relaxed = true)
        every { call.getInt("limit") } returns null
        val resolved = slot<JSObject>()
        val latch = CountDownLatch(1)
        every { call.resolve(capture(resolved)) } answers { latch.countDown() }

        plugin.getAuditLog(call)
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        val entries = resolved.captured.getJSONArray("entries")
        assertEquals(1, entries.length())
        assertEquals("r0", entries.getJSONObject(0).getJSONObject("record").getString("uuid"))
    }

    @Test
    fun pluginRejectsWithTheErrorCode() {
        val plugin = plugin()
        val missing = mockk<PluginCall>(relaxed = true)
        every { missing.getObject("premise") } returns null
        plugin.startMonitoring(missing)
        verify { missing.reject(any(), ErrorCode.INVALID_ARGUMENT.name) }

        val invalid = mockk<PluginCall>(relaxed = true)
        every { invalid.getObject("premise") } returns JSObject.fromJSONObject(HQ.toJson().put("radius", -1))
        every { invalid.getString("auditUrl") } returns null
        val latch = CountDownLatch(1)
        every { invalid.reject(any(), any<String>()) } answers { latch.countDown() }
        plugin.startMonitoring(invalid)
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        verify { invalid.reject(match { it.contains("radius") }, ErrorCode.INVALID_ARGUMENT.name) }
    }

    @Test
    fun pluginStartMonitoringResolvesWithStatus() {
        val plugin = plugin()
        val call = mockk<PluginCall>(relaxed = true)
        every { call.getObject("premise") } returns JSObject.fromJSONObject(HQ.toJson())
        every { call.getString("auditUrl") } returns "https://audit.example.com/premise"
        val resolved = slot<JSObject>()
        val latch = CountDownLatch(1)
        every { call.resolve(capture(resolved)) } answers { latch.countDown() }

        plugin.startMonitoring(call)
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertTrue(resolved.captured.getBoolean("monitoring"))
        assertEquals("https://audit.example.com/premise", resolved.captured.getString("auditUrl"))
        assertNotNull(PremiseState(app).premise)
    }

    private fun plugin(): PremiseMonitorPlugin {
        val bridge = mockk<Bridge>(relaxed = true)
        every { bridge.context } returns app
        return PremiseMonitorPlugin().apply {
            setBridge(bridge)
            load()
        }
    }
}
