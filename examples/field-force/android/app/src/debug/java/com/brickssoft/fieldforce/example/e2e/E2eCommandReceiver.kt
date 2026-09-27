// Debug build only (app/src/debug). Contract: docs/e2e/architecture.md §6 (debug hook contract).
package com.brickssoft.fieldforce.example.e2e

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.brickssoft.locationtracking.api.LocationTrackingNative
import com.brickssoft.locationtracking.api.NativeCallback
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.premisemonitor.PremiseMonitorNative
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets the e2e kit (testing/e2e-kit, `E2eCommands`) drive the plugin and the PremiseMonitor companion natively,
 * without the WebView:
 *
 * ```
 * adb shell am broadcast -a com.brickssoft.fieldforce.example.E2E \
 *   -n com.brickssoft.fieldforce.example/.e2e.E2eCommandReceiver --include-stopped-packages \
 *   --es id r1 --es cmd state --es json64 e30=
 * ```
 *
 * Extras: `id` (`[A-Za-z0-9._-]{1,64}`), `cmd`, and the JSON arguments as `json64` (base64 of UTF-8 JSON) or `json`
 * (plain JSON); none = `{}`. Every request produces exactly one `LT-E2E` logcat line (see [Reply]). Tracking
 * commands go through [LocationTrackingNative], `premise.*` through [PremiseMonitorNative]. Nothing here touches JS.
 */
class E2eCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending: PendingResult? = goAsync()
        val app = context.applicationContext ?: context
        val rawId = intent.getStringExtra(EXTRA_ID)
        val rawCmd = intent.getStringExtra(EXTRA_CMD)
        val foreground = (intent.flags and Intent.FLAG_RECEIVER_FOREGROUND) != 0
        val reply = Reply(app, rawId.orEmpty().take(MAX_ECHO_LENGTH), rawCmd.orEmpty().take(MAX_ECHO_LENGTH), pending, foreground)
        try {
            if (rawId == null || !ID_PATTERN.matches(rawId)) throw BadCommand("'id' must match [A-Za-z0-9._-]{1,64}")
            if (rawId in RESERVED_IDS) throw BadCommand("'id' $rawId is reserved (files/e2e/$rawId.json is a test-mode file)")
            if (rawCmd.isNullOrEmpty()) throw BadCommand("'cmd' is missing")
            dispatch(app, rawCmd, parseArgs(intent), reply)
        } catch (e: BadCommand) {
            reply.fail(BAD_COMMAND, e.message ?: "bad command")
        } catch (e: Throwable) {
            Log.e(HOOK_TAG, "command '$rawCmd' failed", e)
            reply.fail(e)
        }
    }

    private fun dispatch(context: Context, cmd: String, args: JSONObject, reply: Reply) {
        when (cmd) {
            "ready" -> LocationTrackingNative.ready(
                context,
                args.optObjectOrNull("config"),
                args.optBooleanStrict("reset", true),
                reply.passThrough(),
            )
            "setConfig" -> LocationTrackingNative.setConfig(context, args.requireObject("config"), reply.passThrough())
            "start" -> LocationTrackingNative.start(context, reply.passThrough())
            "startGeofences" -> LocationTrackingNative.startGeofences(context, reply.passThrough())
            "stop" -> LocationTrackingNative.stop(context, reply.passThrough())
            "changePace" -> LocationTrackingNative.changePace(context, args.requireBoolean("isMoving"), reply.noResult())
            "state" -> LocationTrackingNative.getState(context, reply.passThrough())
            "heartbeatStatus" -> LocationTrackingNative.getHeartbeatStatus(context, reply.passThrough())
            "sync" -> LocationTrackingNative.sync(context, reply.passThrough())
            "insertLocation" -> LocationTrackingNative.insertLocation(
                context,
                args.requireObject("location"),
                reply.callback { uuid: String -> JSONObject().put("uuid", uuid) },
            )
            "addGeofence" -> LocationTrackingNative.addGeofence(context, args.requireObject("geofence"), reply.noResult())
            "removeGeofence" -> LocationTrackingNative.removeGeofence(
                context,
                args.requireString("identifier"),
                reply.noResult(),
            )
            "getGeofences" -> LocationTrackingNative.getGeofences(context, reply.passThrough())
            "blockMainThread" -> blockMainThread(args, reply)
            "premise.start" -> PremiseMonitorNative.start(
                context,
                args.requireObject("premise"),
                args.optStringOrNull("auditUrl"),
                reply.passThrough(),
            )
            "premise.stop" -> PremiseMonitorNative.stop(context, reply.passThrough())
            "premise.status" -> PremiseMonitorNative.status(context, reply.passThrough())
            "premise.auditLog" -> PremiseMonitorNative.auditLog(
                context,
                args.optLongStrict("limit", 0L, 0L..Int.MAX_VALUE.toLong()).toInt(),
                reply.passThrough(),
            )
            else -> throw BadCommand("unknown cmd '$cmd'")
        }
    }

    /**
     * `blockMainThread {ms, delayMs? = 0}`: answers `{blockedMs}` first, then a main-thread runnable posted with
     * [delayMs] sleeps [ms] (simulates a busy main thread around a foreground-service start).
     */
    private fun blockMainThread(args: JSONObject, reply: Reply) {
        val ms = args.requireLong("ms", 0L..MAX_BLOCK_MS)
        val delayMs = args.optLongStrict("delayMs", 0L, 0L..MAX_BLOCK_DELAY_MS)
        reply.succeed(JSONObject().put("blockedMs", ms))
        Handler(Looper.getMainLooper()).postDelayed({
            Log.i(HOOK_TAG, "blocking the main thread for $ms ms")
            SystemClock.sleep(ms)
            Log.i(HOOK_TAG, "main thread released after $ms ms")
        }, delayMs)
    }

    /**
     * The answer to one request: exactly one `LT-E2E` line (the first of success, failure or timeout wins) and
     * exactly one `PendingResult.finish()`.
     *
     * - success: `{"id","cmd","ok":true,"result":…}`; when that line is longer than [MAX_LINE_BYTES] (logcat cuts
     *   lines at about 4 KB), the full response goes to `files/e2e/<id>.json` and the line is
     *   `{"id","cmd","ok":true,"resultFile":"files/e2e/<id>.json"}`;
     * - failure: `{"id","cmd","ok":false,"code","message"}`;
     * - no answer within [TIMEOUT_MS]: code `TIMEOUT`.
     *
     * A foreground broadcast (`--receiver-foreground`) must finish within 10 s or Android reports an ANR, so its
     * PendingResult is released after [FOREGROUND_RELEASE_MS] while the reply still waits up to [TIMEOUT_MS].
     */
    private class Reply(
        private val context: Context,
        private val id: String,
        private val cmd: String,
        private val pending: PendingResult?,
        foreground: Boolean,
    ) {
        private val answered = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        private val timeout: ScheduledFuture<*> = SCHEDULER.schedule(
            { fail(TIMEOUT, "no result within $TIMEOUT_MS ms") },
            TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
        private val earlyRelease: ScheduledFuture<*>? = if (foreground) {
            SCHEDULER.schedule({ release() }, FOREGROUND_RELEASE_MS, TimeUnit.MILLISECONDS)
        } else {
            null
        }

        /** Callback that answers with the facade's result converted by [transform] (on the hook thread). */
        fun <T> callback(transform: (T) -> Any?): NativeCallback<T> = NativeCallback { result ->
            SCHEDULER.execute {
                val error = result.exceptionOrNull()
                if (error != null) {
                    fail(error)
                    return@execute
                }
                val converted = try {
                    transform(result.getOrThrow())
                } catch (e: Throwable) {
                    fail(e)
                    return@execute
                }
                succeed(converted)
            }
        }

        /** The facade's JSON result as it is (State, HeartbeatStatus, PremiseStatus, arrays). */
        fun <T> passThrough(): NativeCallback<T> = callback { value: T -> value }

        /** For `void` methods: `result` is `null`. */
        fun noResult(): NativeCallback<Unit> = callback { _: Unit -> null }

        fun succeed(result: Any?) {
            if (!answered.compareAndSet(false, true)) return
            try {
                val value = result ?: JSONObject.NULL
                val line = JSONObject().put("id", id).put("cmd", cmd).put("ok", true).put("result", value).toString()
                if (line.toByteArray(Charsets.UTF_8).size <= MAX_LINE_BYTES) {
                    Log.i(TAG, line)
                } else {
                    val relative = "files/e2e/$id.json"
                    writeAtomically(File(context.filesDir, "e2e/$id.json"), line)
                    Log.i(
                        TAG,
                        JSONObject().put("id", id).put("cmd", cmd).put("ok", true).put("resultFile", relative).toString(),
                    )
                }
            } catch (e: Throwable) {
                Log.e(HOOK_TAG, "could not write the response of '$cmd'", e)
                logFailure(INTERNAL, "could not write the response: $e")
            } finally {
                done()
            }
        }

        fun fail(error: Throwable) {
            val code = (error as? TrackingException)?.code?.name ?: INTERNAL
            val message = if (error is TrackingException) error.message ?: code else error.toString()
            fail(code, message)
        }

        fun fail(code: String, message: String) {
            if (!answered.compareAndSet(false, true)) return
            try {
                logFailure(code, message)
            } finally {
                done()
            }
        }

        private fun logFailure(code: String, message: String) {
            Log.i(
                TAG,
                JSONObject()
                    .put("id", id)
                    .put("cmd", cmd)
                    .put("ok", false)
                    .put("code", code)
                    .put("message", message.take(MAX_MESSAGE_LENGTH))
                    .toString(),
            )
        }

        private fun done() {
            timeout.cancel(false)
            earlyRelease?.cancel(false)
            release()
        }

        private fun release() {
            if (!released.compareAndSet(false, true)) return
            try {
                pending?.finish()
            } catch (e: Throwable) {
                Log.w(HOOK_TAG, "PendingResult.finish() failed", e)
            }
        }
    }

    private class BadCommand(message: String) : Exception(message)

    companion object {
        /** Tag of the one response line per request (parsed by the e2e kit). */
        const val TAG = "LT-E2E"

        /** Diagnostics of this hook; deliberately not [TAG], so the kit never parses them. */
        private const val HOOK_TAG = "FF.E2eHook"

        const val ACTION = "com.brickssoft.fieldforce.example.E2E"
        const val EXTRA_ID = "id"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_JSON = "json"
        const val EXTRA_JSON64 = "json64"

        const val BAD_COMMAND = "BAD_COMMAND"
        const val TIMEOUT = "TIMEOUT"
        const val INTERNAL = "INTERNAL"

        /** Longest response line written to logcat, in UTF-8 bytes; longer responses go to a file. */
        const val MAX_LINE_BYTES = 3000

        /** How long a request waits for the facade's callback. */
        const val TIMEOUT_MS = 25_000L

        /** A foreground broadcast's PendingResult is finished after this long (ANR limit: 10 s). */
        const val FOREGROUND_RELEASE_MS = 8_000L

        private const val MAX_MESSAGE_LENGTH = 1500
        private const val MAX_ECHO_LENGTH = 100
        private const val MAX_BLOCK_MS = 60_000L
        private const val MAX_BLOCK_DELAY_MS = 60_000L

        private val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")

        /**
         * Ids whose result file `files/e2e/<id>.json` would replace a test-mode file the kit writes before a launch
         * (architecture §6 addendum), e.g. the page's overrides `files/e2e/ff-overrides.json`.
         */
        private val RESERVED_IDS = setOf("ff-overrides", "example")

        /** One daemon thread for timeouts and for writing the answers (off the main and LT-native threads). */
        private val SCHEDULER = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "FF-E2E").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        /** `json64` (preferred) or `json`; none or blank = `{}`; anything but one JSON object = BAD_COMMAND. */
        private fun parseArgs(intent: Intent): JSONObject {
            val encoded = intent.getStringExtra(EXTRA_JSON64)
            val text = when {
                encoded != null -> decodeBase64(encoded)
                else -> intent.getStringExtra(EXTRA_JSON) ?: return JSONObject()
            }
            if (text.isBlank()) return JSONObject()
            return try {
                val tokener = JSONTokener(text)
                val value = tokener.nextValue()
                if (tokener.nextClean() != 0.toChar()) throw BadCommand("unexpected text after the JSON arguments")
                value as? JSONObject ?: throw BadCommand("the JSON arguments must be an object")
            } catch (e: JSONException) {
                throw BadCommand("invalid JSON arguments: ${e.message}")
            }
        }

        private fun decodeBase64(encoded: String): String {
            val trimmed = encoded.trim()
            val flags = if (trimmed.contains('-') || trimmed.contains('_')) Base64.URL_SAFE else Base64.DEFAULT
            return try {
                String(Base64.decode(trimmed, flags), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                throw BadCommand("'json64' is not valid base64")
            }
        }

        private fun writeAtomically(file: File, text: String) {
            val dir = file.parentFile ?: throw IllegalStateException("no parent directory for $file")
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IllegalStateException("cannot create $dir")
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IllegalStateException("cannot rename $tmp to $file")
            }
        }

        // ---- argument readers: a missing or wrongly typed argument is BAD_COMMAND

        private fun JSONObject.present(key: String): Any? = opt(key).takeUnless { it == null || it == JSONObject.NULL }

        private fun JSONObject.requireObject(key: String): JSONObject =
            present(key) as? JSONObject ?: throw BadCommand("'$key' must be a JSON object")

        private fun JSONObject.optObjectOrNull(key: String): JSONObject? = when (val value = present(key)) {
            null -> null
            is JSONObject -> value
            else -> throw BadCommand("'$key' must be a JSON object")
        }

        private fun JSONObject.requireBoolean(key: String): Boolean =
            present(key) as? Boolean ?: throw BadCommand("'$key' must be true or false")

        private fun JSONObject.optBooleanStrict(key: String, default: Boolean): Boolean = when (val value = present(key)) {
            null -> default
            is Boolean -> value
            else -> throw BadCommand("'$key' must be true or false")
        }

        private fun JSONObject.requireString(key: String): String =
            (present(key) as? String)?.takeIf { it.isNotEmpty() } ?: throw BadCommand("'$key' must be a non-empty string")

        private fun JSONObject.optStringOrNull(key: String): String? = when (val value = present(key)) {
            null -> null
            is String -> value.takeIf { it.isNotEmpty() }
            else -> throw BadCommand("'$key' must be a string")
        }

        private fun JSONObject.requireLong(key: String, range: LongRange): Long =
            present(key)?.let { toWholeNumber(key, it, range) } ?: throw BadCommand("'$key' is missing")

        private fun JSONObject.optLongStrict(key: String, default: Long, range: LongRange): Long =
            present(key)?.let { toWholeNumber(key, it, range) } ?: default

        private fun toWholeNumber(key: String, value: Any, range: LongRange): Long {
            val number = value as? Number ?: throw BadCommand("'$key' must be a number")
            val double = number.toDouble()
            if (double.isNaN() || double.isInfinite() || double != Math.floor(double)) {
                throw BadCommand("'$key' must be a whole number")
            }
            val long = number.toLong()
            if (long !in range) throw BadCommand("'$key' must be in ${range.first}..${range.last}")
            return long
        }
    }
}
