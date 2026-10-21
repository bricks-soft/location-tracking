package com.brickssoft.locationtracking.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What the JS `log()` method removes before the text reaches logcat and the log files (recommendation 16). */
class LogRedactionTest {
    @Test
    fun `credential fields lose their values in every common notation`() {
        assertEquals("accessToken: [redacted] done", LogRedaction.redact("accessToken: abc.def done"))
        assertEquals("refresh_token=[redacted]&x=1", LogRedaction.redact("refresh_token=r-123&x=1"))
        assertEquals("""{"password":"[redacted]","user":"bob"}""", LogRedaction.redact("""{"password":"p@ss \"w\"","user":"bob"}"""))
        assertEquals("API_KEY = [redacted]", LogRedaction.redact("API_KEY = k1"))
        assertEquals("client-secret: [redacted]", LogRedaction.redact("client-secret: s"))
    }

    @Test
    fun `bearer tokens, JWTs and URLs are removed`() {
        assertEquals("header Bearer [redacted] sent", LogRedaction.redact("header Bearer abc123._-~+/= sent"))
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl"
        assertEquals("got [jwt] back", LogRedaction.redact("got $jwt back"))
        assertEquals("posting to [url] now", LogRedaction.redact("posting to https://user:pw@example.com/a?token=x now"))
    }

    @Test
    fun `ordinary text is unchanged`() {
        val text = "driver 7 started the shift; battery 81%, tokens left: none"
        assertEquals("driver 7 started the shift; battery 81%, tokens left: none", LogRedaction.redact(text))
        assertFalse(LogRedaction.redact("token=abc").contains("abc"))
    }
}
