package com.brickssoft.locationtracking.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Iso8601Test {
    @Test
    fun `formats UTC with milliseconds`() {
        assertEquals("2026-09-26T10:15:30.123Z", Iso8601.format(1_790_417_730_123L))
        assertEquals("1970-01-01T00:00:00.000Z", Iso8601.format(0L))
    }

    @Test
    fun `parses its own format and common variants`() {
        assertEquals(1_790_417_730_123L, Iso8601.parse("2026-09-26T10:15:30.123Z"))
        assertEquals(1_790_417_730_000L, Iso8601.parse("2026-09-26T10:15:30Z"))
        assertEquals(1_790_417_730_123L, Iso8601.parse("2026-09-26T13:15:30.123+03:00"))
        assertEquals(1_790_417_730_100L, Iso8601.parse("2026-09-26T10:15:30.1Z"))
        assertEquals(1_790_417_730_123L, Iso8601.parse("2026-09-26T10:15:30.123456Z"))
    }

    @Test
    fun `rejects invalid input`() {
        assertNull(Iso8601.parse(null))
        assertNull(Iso8601.parse(""))
        assertNull(Iso8601.parse("yesterday"))
        assertNull(Iso8601.parse("2026-09-26T10:15:30.123Zjunk"))
    }
}
