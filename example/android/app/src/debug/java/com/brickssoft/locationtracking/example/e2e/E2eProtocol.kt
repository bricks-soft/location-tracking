package com.brickssoft.locationtracking.example.e2e

import com.brickssoft.locationtracking.core.TrackingException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Pure functions of the debug command protocol (docs/e2e/architecture.md §6): request id validation, argument
 * decoding and checks, and the shape of the single `LT-E2E` logcat line.
 *
 * This file uses no Android API (base64 decoding is passed in), so every function here can be tested on a device
 * (`src/androidTest`) and on a plain JVM that has org.json on its classpath.
 */
object E2eProtocol {
    /** Logcat tag of the one response line per request. */
    const val LOG_TAG = "LT-E2E"

    const val EXTRA_ID = "id"
    const val EXTRA_CMD = "cmd"
    const val EXTRA_JSON64 = "json64"
    const val EXTRA_JSON = "json"

    /** Failure codes that the receiver adds to the plugin's `ErrorCode` names. */
    const val BAD_COMMAND = "BAD_COMMAND"
    const val TIMEOUT = "TIMEOUT"
    const val INTERNAL = "INTERNAL"

    /**
     * Largest response line in UTF-8 bytes. Logcat cuts a message at about 4 KB; counting bytes instead of
     * characters keeps a line with non-ASCII text (for example an Arabic notification title) under that limit too.
     */
    const val MAX_LINE_BYTES = 3000

    /**
     * How long the receiver waits for a `LocationTrackingNative` callback before it answers TIMEOUT, for a
     * background broadcast (the default of `am broadcast`; Android allows it 60 s before a broadcast ANR).
     */
    const val TIMEOUT_MS = 25_000L

    /**
     * The same for a foreground broadcast (`am broadcast --receiver-foreground`): Android allows it only 10 s before
     * a broadcast ANR, so the receiver answers TIMEOUT and finishes after 8 s.
     */
    const val FOREGROUND_TIMEOUT_MS = 8_000L

    /** Largest `ms` and `delayMs` accepted by `blockMainThread`. */
    const val MAX_BLOCK_MS = 60_000L

    /** Directory under `Context.getFilesDir()` for results that do not fit into one line. */
    const val RESULT_DIR = "e2e"

    /** Failure messages are cut to this many characters before the line-size check. */
    const val MAX_MESSAGE_CHARS = 1000

    /** An id or cmd that is echoed back in a failure line is cut to this many characters. */
    const val MAX_ECHO_CHARS = 64

    private val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")

    /**
     * Ids that are refused because their result file would replace a test-mode file of the page in the same
     * directory (`files/e2e/example.json`, docs/e2e/architecture.md §6).
     */
    val RESERVED_IDS: Set<String> = setOf("example")

    /** A malformed request (bad id, unknown cmd, invalid JSON, a missing or mistyped argument): answered BAD_COMMAND. */
    class BadCommand(message: String) : Exception(message)

    /** What a command produced. */
    sealed class Outcome {
        /**
         * [result] is any org.json value (JSONObject, JSONArray, String, Boolean, Number) or null / [Unit] for JSON
         * `null`. [afterLog] runs after the response line is logged and the broadcast is finished.
         */
        class Success(val result: Any?, val afterLog: (() -> Unit)? = null) : Outcome()

        class Failure(val code: String, val message: String) : Outcome()
    }

    /**
     * The response of one request: the logcat line, whether it is an `ok:true` line, and, for a large result, the
     * file written before the line.
     */
    data class Output(val line: String, val ok: Boolean, val file: ResultFile? = null)

    /** [name] is the file name inside [RESULT_DIR]; [content] is the full response JSON. */
    data class ResultFile(val name: String, val content: String)

    /** A parsed request. */
    data class Request(val id: String, val cmd: String, val args: JSONObject)

    // ---------------------------------------------------------------- request

    fun isValidId(id: String?): Boolean = id != null && ID_PATTERN.matches(id)

    /** The TIMEOUT delay for a foreground or a background broadcast. */
    fun timeoutMs(foreground: Boolean): Long = if (foreground) FOREGROUND_TIMEOUT_MS else TIMEOUT_MS

    /**
     * Validates the id and cmd and decodes the arguments. [knownCommands] is the receiver's command table
     * (`E2eCommandRunner.commands`).
     * @throws BadCommand for an invalid or reserved id, a missing or unknown cmd, or invalid argument JSON.
     */
    fun parseRequest(
        id: String?,
        cmd: String?,
        json64: String?,
        json: String?,
        knownCommands: Set<String>,
        decodeBase64: (String) -> ByteArray,
    ): Request {
        if (!isValidId(id)) throw BadCommand("extra 'id' must match [A-Za-z0-9._-]{1,64}")
        if (id in RESERVED_IDS) throw BadCommand("id '$id' is reserved (files/e2e/$id.json is a test-mode file)")
        if (cmd.isNullOrEmpty()) throw BadCommand("extra 'cmd' is missing")
        if (cmd !in knownCommands) throw BadCommand("unknown cmd '${clip(cmd, MAX_ECHO_CHARS)}'")
        return Request(id!!, cmd, parseArgs(json64, json, decodeBase64))
    }

    /**
     * The arguments: `json64` (base64 of UTF-8 JSON) when it is present and not blank, else `json`, else `{}`.
     * @throws BadCommand when the text is not base64, not JSON, or not a JSON object.
     */
    fun parseArgs(json64: String?, json: String?, decodeBase64: (String) -> ByteArray): JSONObject {
        val text = when {
            !json64.isNullOrBlank() -> {
                val bytes = try {
                    decodeBase64(json64.trim())
                } catch (e: IllegalArgumentException) {
                    throw BadCommand("extra 'json64' is not valid base64: ${e.message}")
                }
                String(bytes, Charsets.UTF_8)
            }
            !json.isNullOrBlank() -> json
            else -> return JSONObject()
        }
        return parseObject(text)
    }

    /** Parses [text] as exactly one JSON object (text after the object is an error). */
    fun parseObject(text: String): JSONObject {
        val tokener = JSONTokener(text)
        val value = try {
            tokener.nextValue()
        } catch (e: JSONException) {
            throw BadCommand("arguments are not valid JSON: ${e.message}")
        }
        if (value !is JSONObject) throw BadCommand("arguments must be a JSON object")
        val rest = try {
            tokener.nextClean()
        } catch (e: JSONException) {
            throw BadCommand("arguments are not valid JSON: ${e.message}")
        }
        if (rest != 0.toChar()) throw BadCommand("arguments have text after the JSON object")
        return value
    }

    // ---------------------------------------------------------------- arguments

    private fun absent(args: JSONObject, name: String): Boolean = !args.has(name) || args.isNull(name)

    /** An optional object argument; absent or `null` gives null. */
    fun optObject(args: JSONObject, name: String): JSONObject? {
        if (absent(args, name)) return null
        return args.opt(name) as? JSONObject ?: throw BadCommand("argument '$name' must be a JSON object")
    }

    fun requireObject(args: JSONObject, name: String): JSONObject =
        optObject(args, name) ?: throw BadCommand("argument '$name' (a JSON object) is required")

    /** An optional boolean argument; only JSON `true` / `false` are accepted (no strings). */
    fun optBoolean(args: JSONObject, name: String, default: Boolean): Boolean {
        if (absent(args, name)) return default
        return args.opt(name) as? Boolean ?: throw BadCommand("argument '$name' must be true or false")
    }

    fun requireBoolean(args: JSONObject, name: String): Boolean {
        if (absent(args, name)) throw BadCommand("argument '$name' (true or false) is required")
        return optBoolean(args, name, false)
    }

    /** A required non-empty string argument. */
    fun requireString(args: JSONObject, name: String): String {
        val value = if (absent(args, name)) null else args.opt(name)
        if (value !is String || value.isEmpty()) throw BadCommand("argument '$name' (a non-empty string) is required")
        return value
    }

    /** A millisecond count in `0..max`; a JSON number with a fraction is truncated. */
    fun optMillis(args: JSONObject, name: String, default: Long?, max: Long): Long {
        if (absent(args, name)) {
            return default ?: throw BadCommand("argument '$name' (milliseconds) is required")
        }
        val value = args.opt(name) as? Number ?: throw BadCommand("argument '$name' must be a number of milliseconds")
        val d = value.toDouble()
        if (d.isNaN() || d < 0 || d > max) throw BadCommand("argument '$name' must be between 0 and $max")
        return d.toLong()
    }

    /** `blockMainThread` arguments: `ms` (required) and `delayMs` (default 0). */
    fun blockArgs(args: JSONObject): Pair<Long, Long> =
        optMillis(args, "ms", null, MAX_BLOCK_MS) to optMillis(args, "delayMs", 0L, MAX_BLOCK_MS)

    // ---------------------------------------------------------------- response

    /** Maps a command failure: a `TrackingException` keeps its `ErrorCode` name, anything else is INTERNAL. */
    fun failureOf(error: Throwable): Outcome.Failure = when (error) {
        is TrackingException -> Outcome.Failure(error.code.name, error.message ?: error.code.name)
        is BadCommand -> Outcome.Failure(BAD_COMMAND, error.message ?: BAD_COMMAND)
        else -> Outcome.Failure(INTERNAL, "${error.javaClass.name}: ${error.message}")
    }

    /** A result as an org.json value: null and [Unit] become `JSONObject.NULL`; other unknown types their text. */
    fun jsonValue(value: Any?): Any = when (value) {
        null, Unit -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long -> value
        is Number -> value.toDouble().let { if (it.isNaN() || it.isInfinite()) JSONObject.NULL else it }
        else -> if (value == JSONObject.NULL) value else value.toString()
    }

    fun successResponse(id: String, cmd: String, result: Any?): JSONObject =
        JSONObject().put("id", id).put("cmd", cmd).put("ok", true).put("result", jsonValue(result))

    fun resultFileResponse(id: String, cmd: String): JSONObject =
        JSONObject().put("id", id).put("cmd", cmd).put("ok", true).put("resultFile", resultFilePath(id))

    /** A failure line; [id] and [cmd] may be null or invalid (they are echoed, cut to [MAX_ECHO_CHARS]). */
    fun failureResponse(id: String?, cmd: String?, code: String, message: String): JSONObject =
        JSONObject()
            .put("id", id?.let { clip(it, MAX_ECHO_CHARS) } ?: JSONObject.NULL)
            .put("cmd", cmd?.let { clip(it, MAX_ECHO_CHARS) } ?: JSONObject.NULL)
            .put("ok", false)
            .put("code", code)
            .put("message", message)

    /** `files/e2e/<id>.json`: the path relative to the app's data directory (what `run-as <appId> cat` takes). */
    fun resultFilePath(id: String): String = "files/$RESULT_DIR/${resultFileName(id)}"

    fun resultFileName(id: String): String = "$id.json"

    fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    fun fitsOneLine(line: String): Boolean = utf8Length(line) <= MAX_LINE_BYTES

    /**
     * The response of a finished request. A success whose line is longer than [MAX_LINE_BYTES] becomes a
     * [ResultFile] (the full response) plus a `resultFile` line; a failure is always one line, with its message cut
     * until the line fits. A success with an invalid id (not possible through [parseRequest]) is answered INTERNAL,
     * so no file name is ever built from an invalid id.
     */
    fun shape(id: String?, cmd: String?, outcome: Outcome): Output = when (outcome) {
        is Outcome.Failure -> Output(failureLine(id, cmd, outcome.code, outcome.message), ok = false)
        is Outcome.Success -> {
            if (!isValidId(id) || cmd == null) {
                Output(failureLine(id, cmd, INTERNAL, "success for an invalid id or a missing cmd"), ok = false)
            } else {
                val full = successResponse(id!!, cmd, outcome.result).toString()
                if (fitsOneLine(full)) {
                    Output(full, ok = true)
                } else {
                    Output(resultFileResponse(id, cmd).toString(), ok = true, file = ResultFile(resultFileName(id), full))
                }
            }
        }
    }

    /** One failure line that fits into [MAX_LINE_BYTES]: the message is cut (and halved again) until it fits. */
    fun failureLine(id: String?, cmd: String?, code: String, message: String): String {
        var text = clip(message, MAX_MESSAGE_CHARS)
        while (true) {
            val line = failureResponse(id, cmd, code, text).toString()
            if (fitsOneLine(line) || text.isEmpty()) return line
            text = clip(text, text.length / 2)
        }
    }

    /**
     * [text] cut to at most [max] UTF-16 characters, with "..." at the end when it was cut. A cut never splits a
     * surrogate pair (a lone surrogate would be written as '?' in logcat).
     */
    fun clip(text: String, max: Int): String = when {
        text.length <= max -> text
        max <= 3 -> prefix(text, max)
        else -> prefix(text, max - 3) + "..."
    }

    private fun prefix(text: String, count: Int): String {
        var end = count.coerceIn(0, text.length)
        if (end in 1 until text.length && Character.isHighSurrogate(text[end - 1])) end -= 1
        return text.substring(0, end)
    }
}
