package com.brickssoft.locationtracking.bridge

import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.getcapacitor.Bridge
import com.getcapacitor.JSObject
import com.getcapacitor.PluginCall
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Where a `watchPosition` delivers; in production a kept-alive [PluginCall] ([CallWatchTarget]). */
internal interface WatchTarget {
    /** A `Location` for the JS callback. */
    fun deliver(location: JSONObject)

    /** An error for the JS callback; the watch stays active. */
    fun fail(code: ErrorCode, message: String)

    /** Ends the watch on the JS side (releases the kept-alive call). */
    fun release()
}

/** Kept-alive `watchPosition` targets by watch id (the call's callback id); thread-safe. */
internal class WatchRegistry {
    private val targets = ConcurrentHashMap<String, WatchTarget>()

    val size: Int get() = targets.size

    val ids: Set<String> get() = targets.keys.toSet()

    operator fun get(id: String): WatchTarget? = targets[id]

    operator fun contains(id: String): Boolean = targets.containsKey(id)

    /** Registers [target] under [id]; a different target already registered under [id] is released. */
    fun add(id: String, target: WatchTarget) {
        val previous = targets.put(id, target)
        if (previous != null && previous !== target) releaseQuietly(id, previous)
    }

    /** Removes and releases the target of [id]; false if there was none. */
    fun release(id: String): Boolean {
        val target = targets.remove(id) ?: return false
        releaseQuietly(id, target)
        return true
    }

    /** Releases every target; returns the released ids. */
    fun releaseAll(): List<String> = targets.keys.toList().filter { release(it) }

    private fun releaseQuietly(id: String, target: WatchTarget) {
        try {
            target.release()
        } catch (e: Exception) {
            Logger.w(TAG, "failed to release watch $id", e)
        }
    }

    private companion object {
        const val TAG = "LT.Watch"
    }
}

/**
 * [WatchTarget] over a `RETURN_CALLBACK` [PluginCall] that was kept alive (`setKeepAlive(true)`): every
 * [deliver] / [fail] invokes the JS callback; [release] frees the call in the [Bridge].
 */
internal class CallWatchTarget(
    private val call: PluginCall,
    private val bridge: () -> Bridge?,
) : WatchTarget {
    override fun deliver(location: JSONObject) {
        call.resolve(JSObject.fromJSONObject(location))
    }

    override fun fail(code: ErrorCode, message: String) {
        call.reject(message, code.name)
    }

    override fun release() {
        val b = bridge()
        if (b != null) call.release(b) else call.setKeepAlive(false)
    }
}
