package com.brickssoft.locationtracking.example.e2e

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.brickssoft.locationtracking.api.LocationTrackingNative
import com.brickssoft.locationtracking.api.NativeCallback
import com.brickssoft.locationtracking.example.e2e.E2eProtocol.Outcome
import org.json.JSONObject

/**
 * The command table of the debug receiver (docs/e2e/architecture.md §6): each command runs through
 * [LocationTrackingNative] and hands its outcome to `done` exactly once. `done` is called on the `LT-native` thread
 * for plugin commands and on the calling thread for `blockMainThread`.
 *
 * Commands never touch JS. `ready` is the native `ready` (it does not set the JS bridge's ready flag).
 */
object E2eCommandRunner {
    /** One command: reads its arguments (throwing [E2eProtocol.BadCommand]) and starts the plugin call. */
    private fun interface Command {
        fun run(app: Context, args: JSONObject, done: (Outcome) -> Unit)
    }

    private val table: Map<String, Command> = linkedMapOf(
        "ready" to Command { app, args, done ->
            val config = E2eProtocol.optObject(args, "config")
            val reset = E2eProtocol.optBoolean(args, "reset", true)
            LocationTrackingNative.ready(app, config, reset, callback(done))
        },
        "setConfig" to Command { app, args, done ->
            LocationTrackingNative.setConfig(app, E2eProtocol.requireObject(args, "config"), callback(done))
        },
        "start" to Command { app, _, done -> LocationTrackingNative.start(app, callback(done)) },
        "startGeofences" to Command { app, _, done -> LocationTrackingNative.startGeofences(app, callback(done)) },
        "stop" to Command { app, _, done -> LocationTrackingNative.stop(app, callback(done)) },
        "changePace" to Command { app, args, done ->
            LocationTrackingNative.changePace(app, E2eProtocol.requireBoolean(args, "isMoving"), callback(done))
        },
        "state" to Command { app, _, done -> LocationTrackingNative.getState(app, callback(done)) },
        "heartbeatStatus" to Command { app, _, done -> LocationTrackingNative.getHeartbeatStatus(app, callback(done)) },
        "sync" to Command { app, _, done -> LocationTrackingNative.sync(app, callback(done)) },
        "insertLocation" to Command { app, args, done ->
            val location = E2eProtocol.requireObject(args, "location")
            LocationTrackingNative.insertLocation(
                app,
                location,
                callback(done) { uuid: String -> JSONObject().put("uuid", uuid) },
            )
        },
        "addGeofence" to Command { app, args, done ->
            LocationTrackingNative.addGeofence(app, E2eProtocol.requireObject(args, "geofence"), callback(done))
        },
        "removeGeofence" to Command { app, args, done ->
            LocationTrackingNative.removeGeofence(app, E2eProtocol.requireString(args, "identifier"), callback(done))
        },
        "getGeofences" to Command { app, _, done -> LocationTrackingNative.getGeofences(app, callback(done)) },
        "blockMainThread" to Command { _, args, done ->
            val (ms, delayMs) = E2eProtocol.blockArgs(args)
            done(Outcome.Success(JSONObject().put("blockedMs", ms), afterLog = { blockMainThread(ms, delayMs) }))
        },
        // Test support, not a plugin command: another app's GPS request (see OtherAppLocation).
        "otherAppLocation" to Command { app, args, done ->
            val enabled = E2eProtocol.requireBoolean(args, "enabled")
            val intervalMs = E2eProtocol.optMillis(args, "intervalMs", 1000L, 60_000L)
            Handler(Looper.getMainLooper()).post {
                val outcome = try {
                    Outcome.Success(OtherAppLocation.set(app, enabled, intervalMs))
                } catch (e: Throwable) {
                    E2eProtocol.failureOf(e)
                }
                done(outcome)
            }
        },
    )

    /** Every command name, in the order of the §6 table. */
    val commands: Set<String> get() = table.keys

    /**
     * Runs [cmd].
     * @throws E2eProtocol.BadCommand for an unknown command or a missing or mistyped argument. It is thrown before
     *   the plugin is called, and `done` is not called then.
     */
    fun run(context: Context, cmd: String, args: JSONObject, done: (Outcome) -> Unit) {
        val command = table[cmd]
            ?: throw E2eProtocol.BadCommand("unknown cmd '${E2eProtocol.clip(cmd, E2eProtocol.MAX_ECHO_CHARS)}'")
        command.run(context.applicationContext ?: context, args, done)
    }

    /** A [NativeCallback] that maps the value with [map] (default: the value itself) and a failure to its code. */
    private fun <T> callback(done: (Outcome) -> Unit, map: (T) -> Any? = { it }): NativeCallback<T> =
        NativeCallback { result ->
            val outcome = try {
                result.fold(onSuccess = { Outcome.Success(map(it)) }, onFailure = { E2eProtocol.failureOf(it) })
            } catch (e: Throwable) {
                E2eProtocol.failureOf(e)
            }
            done(outcome)
        }

    /**
     * Posts a runnable to the main thread that sleeps [ms] after [delayMs]. `SystemClock.sleep` ignores interrupts,
     * so the main thread is blocked for the full time (this simulates a busy main thread around a service start,
     * scenario P-L13).
     */
    private fun blockMainThread(ms: Long, delayMs: Long) {
        Handler(Looper.getMainLooper()).postDelayed({ SystemClock.sleep(ms) }, delayMs)
    }
}
