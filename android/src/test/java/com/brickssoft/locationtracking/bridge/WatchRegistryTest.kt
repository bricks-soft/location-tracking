package com.brickssoft.locationtracking.bridge

import com.brickssoft.locationtracking.core.ErrorCode
import com.getcapacitor.Bridge
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchRegistryTest {
    private val registry = WatchRegistry()

    @Test
    fun `add, get and release`() {
        val target = RecordingWatchTarget()

        registry.add("a", target)

        assertSame(target, registry["a"])
        assertTrue("a" in registry)
        assertEquals(setOf("a"), registry.ids)
        assertTrue(registry.release("a"))
        assertEquals(1, target.releases)
        assertNull(registry["a"])
        assertFalse(registry.release("a"))
        assertEquals(1, target.releases)
    }

    @Test
    fun `re-adding the same target does not release it`() {
        val target = RecordingWatchTarget()

        registry.add("a", target)
        registry.add("a", target)

        assertEquals(0, target.releases)
        assertEquals(1, registry.size)
    }

    @Test
    fun `releaseAll releases every target once, even if one throws`() {
        val a = RecordingWatchTarget()
        val broken = object : WatchTarget {
            override fun deliver(location: JSONObject) = Unit

            override fun fail(code: ErrorCode, message: String) = Unit

            override fun release() = throw IllegalStateException("bridge gone")
        }
        registry.add("a", a)
        registry.add("broken", broken)

        assertEquals(setOf("a", "broken"), registry.releaseAll().toSet())

        assertEquals(1, a.releases)
        assertEquals(0, registry.size)
        assertTrue(registry.releaseAll().isEmpty())
    }

    @Test
    fun `call target resolves, rejects and releases the kept-alive call`() {
        val call = mockk<PluginCall>(relaxed = true)
        val bridge = mockk<Bridge>(relaxed = true)
        val target = CallWatchTarget(call) { bridge }

        target.deliver(JSONObject().put("uuid", "u1"))
        target.fail(ErrorCode.LOCATION_DISABLED, "gps off")
        target.release()

        verify { call.resolve(match<JSObject> { it.getString("uuid") == "u1" }) }
        verify { call.reject("gps off", "LOCATION_DISABLED") }
        verify { call.release(bridge) }
    }

    @Test
    fun `call target without a bridge only drops keep-alive`() {
        val call = mockk<PluginCall>(relaxed = true)
        every { call.callbackId } returns "cb"

        CallWatchTarget(call) { null }.release()

        verify { call.setKeepAlive(false) }
        verify(exactly = 0) { call.release(any()) }
    }
}
