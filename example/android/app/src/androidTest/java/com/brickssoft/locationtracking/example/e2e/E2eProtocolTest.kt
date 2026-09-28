package com.brickssoft.locationtracking.example.e2e

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.example.e2e.E2eProtocol.BadCommand
import com.brickssoft.locationtracking.example.e2e.E2eProtocol.Outcome
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pure protocol functions of the debug receiver (docs/e2e/architecture.md §6). Runs on a device or emulator:
 * `./gradlew :app:connectedDebugAndroidTest` in example/android.
 */
@RunWith(AndroidJUnit4::class)
class E2eProtocolTest {
    private val decode: (String) -> ByteArray = { Base64.decode(it, Base64.DEFAULT) }
    private val known: Set<String> = E2eCommandRunner.commands

    private fun b64(text: String): String = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun assertBadCommand(block: () -> Unit) {
        try {
            block()
            fail("expected BadCommand")
        } catch (e: BadCommand) {
            // expected
        }
    }

    // ---------------------------------------------------------------- ids and commands

    @Test
    fun validIds() {
        listOf("a", "req-1", "A.b_c-9", "0123456789", "x".repeat(64), "..", "e2e.4f9c-11ef_a").forEach {
            assertTrue("valid: $it", E2eProtocol.isValidId(it))
        }
    }

    @Test
    fun invalidIds() {
        listOf(null, "", "x".repeat(65), "a/b", "../x", "a b", "a\nb", "ä", "a;b", "a\$b").forEach {
            assertFalse("invalid: $it", E2eProtocol.isValidId(it))
        }
    }

    @Test
    fun parseRequestRejectsBadIdMissingAndUnknownCmd() {
        assertBadCommand { E2eProtocol.parseRequest(null, "state", null, null, known, decode) }
        assertBadCommand { E2eProtocol.parseRequest("a/b", "state", null, null, known, decode) }
        assertBadCommand { E2eProtocol.parseRequest("r1", null, null, null, known, decode) }
        assertBadCommand { E2eProtocol.parseRequest("r1", "", null, null, known, decode) }
        assertBadCommand { E2eProtocol.parseRequest("r1", "State", null, null, known, decode) }
        assertBadCommand { E2eProtocol.parseRequest("r1", "premise.start", null, null, known, decode) }
    }

    @Test
    fun theTestModeFileNameIsAReservedId() {
        assertBadCommand { E2eProtocol.parseRequest("example", "state", null, null, known, decode) }
        assertEquals("example-1", E2eProtocol.parseRequest("example-1", "state", null, null, known, decode).id)
    }

    @Test
    fun commandTableIsTheContractTable() {
        assertEquals(
            listOf(
                "ready", "setConfig", "start", "startGeofences", "stop", "changePace", "state", "heartbeatStatus",
                "sync", "insertLocation", "addGeofence", "removeGeofence", "getGeofences", "blockMainThread",
                "otherAppLocation",
            ),
            E2eCommandRunner.commands.toList(),
        )
    }

    @Test
    fun foregroundBroadcastsTimeOutBeforeTheirAnr() {
        assertEquals(25_000L, E2eProtocol.timeoutMs(foreground = false))
        assertTrue(E2eProtocol.timeoutMs(foreground = true) < 10_000L)
    }

    @Test
    fun parseRequestReturnsIdCmdAndArgs() {
        val request = E2eProtocol.parseRequest("r1", "changePace", b64("""{"isMoving":true}"""), null, known, decode)
        assertEquals("r1", request.id)
        assertEquals("changePace", request.cmd)
        assertEquals(true, request.args.get("isMoving"))
    }

    // ---------------------------------------------------------------- arguments

    @Test
    fun missingArgumentsAreAnEmptyObject() {
        assertEquals(0, E2eProtocol.parseArgs(null, null, decode).length())
        assertEquals(0, E2eProtocol.parseArgs("", "  ", decode).length())
    }

    @Test
    fun json64IsPreferredOverJson() {
        val args = E2eProtocol.parseArgs(b64("""{"from":"json64"}"""), """{"from":"json"}""", decode)
        assertEquals("json64", args.getString("from"))
        assertEquals("json", E2eProtocol.parseArgs(null, """{"from":"json"}""", decode).getString("from"))
        assertEquals("json", E2eProtocol.parseArgs(" ", """{"from":"json"}""", decode).getString("from"))
    }

    @Test
    fun json64CarriesUtf8() {
        val args = E2eProtocol.parseArgs(b64("""{"title":"تتبع الموقع"}"""), null, decode)
        assertEquals("تتبع الموقع", args.getString("title"))
    }

    @Test
    fun trailingWhitespaceIsAccepted() {
        assertEquals(1, E2eProtocol.parseArgs(null, "{\"a\":1}\n  ", decode).getInt("a"))
    }

    @Test
    fun invalidArgumentTextIsBadCommand() {
        assertBadCommand { E2eProtocol.parseArgs("not base64 !!!", null, decode) }
        assertBadCommand { E2eProtocol.parseArgs(null, "{", decode) }
        assertBadCommand { E2eProtocol.parseArgs(null, "[1,2]", decode) }
        assertBadCommand { E2eProtocol.parseArgs(null, "\"text\"", decode) }
        assertBadCommand { E2eProtocol.parseArgs(null, "{} {}", decode) }
        assertBadCommand { E2eProtocol.parseArgs(b64("[]"), null, decode) }
    }

    @Test
    fun objectArguments() {
        val args = JSONObject("""{"config":{"a":1},"nil":null,"num":3}""")
        assertEquals(1, E2eProtocol.optObject(args, "config")!!.getInt("a"))
        assertNull(E2eProtocol.optObject(args, "nil"))
        assertNull(E2eProtocol.optObject(args, "missing"))
        assertBadCommand { E2eProtocol.optObject(args, "num") }
        assertBadCommand { E2eProtocol.requireObject(args, "missing") }
        assertBadCommand { E2eProtocol.requireObject(args, "nil") }
    }

    @Test
    fun booleanArguments() {
        val args = JSONObject("""{"t":true,"f":false,"s":"true","n":1}""")
        assertTrue(E2eProtocol.requireBoolean(args, "t"))
        assertFalse(E2eProtocol.requireBoolean(args, "f"))
        assertTrue(E2eProtocol.optBoolean(args, "missing", true))
        assertBadCommand { E2eProtocol.requireBoolean(args, "s") }
        assertBadCommand { E2eProtocol.requireBoolean(args, "n") }
        assertBadCommand { E2eProtocol.requireBoolean(args, "missing") }
    }

    @Test
    fun stringArguments() {
        val args = JSONObject("""{"id":"hq","empty":"","n":5}""")
        assertEquals("hq", E2eProtocol.requireString(args, "id"))
        assertBadCommand { E2eProtocol.requireString(args, "empty") }
        assertBadCommand { E2eProtocol.requireString(args, "n") }
        assertBadCommand { E2eProtocol.requireString(args, "missing") }
    }

    @Test
    fun blockMainThreadArguments() {
        assertEquals(4000L to 0L, E2eProtocol.blockArgs(JSONObject("""{"ms":4000}""")))
        assertEquals(4000L to 250L, E2eProtocol.blockArgs(JSONObject("""{"ms":4000.7,"delayMs":250}""")))
        assertBadCommand { E2eProtocol.blockArgs(JSONObject("{}")) }
        assertBadCommand { E2eProtocol.blockArgs(JSONObject("""{"ms":-1}""")) }
        assertBadCommand { E2eProtocol.blockArgs(JSONObject("""{"ms":60001}""")) }
        assertBadCommand { E2eProtocol.blockArgs(JSONObject("""{"ms":"4000"}""")) }
        assertBadCommand { E2eProtocol.blockArgs(JSONObject("""{"ms":10,"delayMs":-5}""")) }
    }

    // ---------------------------------------------------------------- responses

    @Test
    fun smallSuccessIsOneLine() {
        val output = E2eProtocol.shape("r1", "state", Outcome.Success(JSONObject().put("enabled", true)))
        assertTrue(output.ok)
        assertNull(output.file)
        assertFalse(output.line.contains('\n'))
        val line = JSONObject(output.line)
        assertEquals("r1", line.getString("id"))
        assertEquals("state", line.getString("cmd"))
        assertTrue(line.getBoolean("ok"))
        assertTrue(line.getJSONObject("result").getBoolean("enabled"))
    }

    @Test
    fun nullAndUnitResultsAreJsonNull() {
        listOf(null, Unit).forEach { value ->
            val line = JSONObject(E2eProtocol.shape("r1", "changePace", Outcome.Success(value)).line)
            assertTrue(line.has("result"))
            assertTrue(line.isNull("result"))
        }
    }

    @Test
    fun arrayResultStaysAnArray() {
        val line = JSONObject(E2eProtocol.shape("r1", "getGeofences", Outcome.Success(JSONArray())).line)
        assertEquals(0, line.getJSONArray("result").length())
    }

    @Test
    fun largeSuccessGoesToAFile() {
        val big = JSONObject().put("text", "x".repeat(4000))
        val output = E2eProtocol.shape("r-big", "sync", Outcome.Success(big))
        assertTrue(output.ok)
        val file = output.file
        assertNotNull(file)
        assertEquals("r-big.json", file!!.name)
        val full = JSONObject(file.content)
        assertEquals("r-big", full.getString("id"))
        assertEquals("sync", full.getString("cmd"))
        assertTrue(full.getBoolean("ok"))
        assertEquals(4000, full.getJSONObject("result").getString("text").length)
        val line = JSONObject(output.line)
        assertEquals("files/e2e/r-big.json", line.getString("resultFile"))
        assertTrue(line.getBoolean("ok"))
        assertFalse(line.has("result"))
    }

    @Test
    fun lineLimitCountsUtf8Bytes() {
        // 1600 Arabic letters are 1600 characters but 3200 UTF-8 bytes: too long for one line.
        val output = E2eProtocol.shape("r1", "state", Outcome.Success(JSONObject().put("t", "م".repeat(1600))))
        assertNotNull(output.file)
        assertTrue(E2eProtocol.fitsOneLine(output.line))
    }

    @Test
    fun failureLineShape() {
        val output = E2eProtocol.shape("r1", "start", Outcome.Failure("PERMISSION_DENIED", "no location permission"))
        assertFalse(output.ok)
        assertNull(output.file)
        val line = JSONObject(output.line)
        assertEquals("r1", line.getString("id"))
        assertEquals("start", line.getString("cmd"))
        assertFalse(line.getBoolean("ok"))
        assertEquals("PERMISSION_DENIED", line.getString("code"))
        assertEquals("no location permission", line.getString("message"))
    }

    @Test
    fun longFailureMessageIsCutToFitOneLine() {
        val output = E2eProtocol.shape("r1", "start", Outcome.Failure("INTERNAL", "\u0001".repeat(5000)))
        assertTrue(E2eProtocol.fitsOneLine(output.line))
        assertEquals("INTERNAL", JSONObject(output.line).getString("code"))
    }

    @Test
    fun failureEchoesInvalidIdAndCmdCut() {
        val line = JSONObject(E2eProtocol.shape("a/b".repeat(40), "c".repeat(200), Outcome.Failure("BAD_COMMAND", "x")).line)
        assertTrue(line.getString("id").length <= E2eProtocol.MAX_ECHO_CHARS)
        assertTrue(line.getString("cmd").length <= E2eProtocol.MAX_ECHO_CHARS)
        val nulls = JSONObject(E2eProtocol.shape(null, null, Outcome.Failure("BAD_COMMAND", "x")).line)
        assertTrue(nulls.isNull("id"))
        assertTrue(nulls.isNull("cmd"))
    }

    @Test
    fun successWithInvalidIdNeverBuildsAFileName() {
        val output = E2eProtocol.shape("../x", "state", Outcome.Success(JSONObject().put("t", "x".repeat(4000))))
        assertFalse(output.ok)
        assertNull(output.file)
        assertEquals("INTERNAL", JSONObject(output.line).getString("code"))
    }

    @Test
    fun failureCodes() {
        assertEquals(
            "PERMISSION_DENIED",
            E2eProtocol.failureOf(TrackingException(ErrorCode.PERMISSION_DENIED, "denied")).code,
        )
        assertEquals("BAD_COMMAND", E2eProtocol.failureOf(BadCommand("bad")).code)
        val internal = E2eProtocol.failureOf(IllegalStateException("boom"))
        assertEquals("INTERNAL", internal.code)
        assertTrue(internal.message.contains("boom"))
    }

    @Test
    fun clip() {
        assertEquals("abc", E2eProtocol.clip("abc", 3))
        assertEquals("abcd...", E2eProtocol.clip("abcdefghij", 7))
        assertEquals("", E2eProtocol.clip("abc", 0))
    }

    @Test
    fun clipNeverSplitsASurrogatePair() {
        val emoji = "\uD83D\uDE80" // one code point, two UTF-16 characters
        // 9 characters cut to 7: the first 4 characters end with the first half of the second emoji, which is
        // dropped instead of being kept alone.
        assertEquals("a$emoji...", E2eProtocol.clip("a$emoji$emoji$emoji$emoji", 7))
        assertEquals("a", E2eProtocol.clip("a$emoji", 2))
    }
}
