package com.brickssoft.locationtracking.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictJsonTest {
    @Test
    fun `accepts valid documents`() {
        val valid = listOf(
            """{}""",
            """[]""",
            """ { "a" : 1 , "b" : [true, false, null], "c": {"d": "e"} } """,
            """{"n": [0, -0, 1, -12, 3.25, -0.5, 1e10, 1E-5, 2.5e+3]}""",
            """{"s": "quote \" backslash \\ slash \/ \b\f\n\r\t é 😀"}""",
            """"just a string"""",
            """42""",
            "\n\t[1,\r\n2]\n",
            """{"unicode": "é ✓"}""",
        )
        for (text in valid) assertTrue("expected valid: $text", StrictJson.isValid(text))
    }

    @Test
    fun `rejects what lenient parsers accept`() {
        val invalid = listOf(
            "",
            "   ",
            """{"ts": 2026-09-26T10:15:30.123Z}""",
            """{'a': 1}""",
            """{a: 1}""",
            """{"a" = 1}""",
            """{"a": 1,}""",
            """[1, 2,]""",
            """{"a": 1; "b": 2}""",
            """{"a": 01}""",
            """{"a": 1.}""",
            """{"a": .5}""",
            """{"a": -}""",
            """{"a": +1}""",
            """{"a": 1e}""",
            """{"a": NaN}""",
            """{"a": Infinity}""",
            """{"a": tru}""",
            """{"a": "unterminated}""",
            "{\"a\": \"raw\nnewline\"}",
            """{"a": "bad escape \x"}""",
            """{"a": "short \u12"}""",
            """{"a": 1} trailing""",
            """{"a": 1}}""",
            """{"a" 1}""",
            """{"a": }""",
            """[1 2]""",
            """{"a": 1""",
        )
        for (text in invalid) assertFalse("expected invalid: $text", StrictJson.isValid(text))
    }

    @Test
    fun `limits nesting depth instead of overflowing the stack`() {
        assertTrue(StrictJson.isValid("[".repeat(200) + "]".repeat(200)))
        assertFalse(StrictJson.isValid("[".repeat(10_000) + "]".repeat(10_000)))
    }
}
