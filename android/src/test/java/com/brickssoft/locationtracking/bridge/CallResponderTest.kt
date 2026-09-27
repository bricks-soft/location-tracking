package com.brickssoft.locationtracking.bridge

import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallResponderTest {
    private val call = mockk<PluginCall>(relaxed = true).also { every { it.methodName } returns "start" }

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `void results resolve without data`() {
        CallResponder.resolve(call, null)

        verify { call.resolve() }
    }

    @Test
    fun `object results resolve as JSObject`() {
        CallResponder.resolve(call, JSONObject().put("odometer", 12.5).put("nested", JSONObject().put("a", 1)))

        verify {
            call.resolve(
                match<JSObject> { it.getDouble("odometer") == 12.5 && it.getJSONObject("nested").getInt("a") == 1 },
            )
        }
    }

    @Test
    fun `tracking exceptions reject with their code`() {
        CallResponder.reject(call, TrackingException(ErrorCode.NOT_READY, "call ready() first"))

        verify { call.reject("call ready() first", "NOT_READY") }
    }

    @Test
    fun `other exceptions reject as INTERNAL`() {
        CallResponder.reject(call, IllegalStateException("boom"))
        CallResponder.reject(call, RuntimeException())

        verify { call.reject("boom", "INTERNAL") }
        verify { call.reject("RuntimeException", "INTERNAL") }
    }

    @Test
    fun `rejection mapping`() {
        assertEquals(Rejection("TIMEOUT", ErrorCode.TIMEOUT), Rejection.of(TrackingException(ErrorCode.TIMEOUT, "")))
        assertEquals(Rejection("x", ErrorCode.INTERNAL), Rejection.of(SecurityException("x")))
    }
}
