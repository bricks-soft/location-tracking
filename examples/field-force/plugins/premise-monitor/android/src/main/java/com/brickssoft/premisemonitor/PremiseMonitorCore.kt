package com.brickssoft.premisemonitor

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.brickssoft.locationtracking.api.NativeCallback
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.TrackingException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Process-wide facts written into every entry. */
internal object ProcessInfo {
    /** True once a [PremiseMonitorPlugin] instance was loaded in this process (a WebView with JS exists). */
    @Volatile
    var jsLoaded: Boolean = false

    fun pid(): Int = android.os.Process.myPid()
}

/** `pid`, `js` and `source` of an entry, captured when PremiseMonitor received the data. */
internal data class EntryMeta(val pid: Int, val js: Boolean, val source: String)

/**
 * PremiseMonitor's engine, one per process. Every state change and every audit write runs on the single thread
 * `PM-native`, in the order the calls arrived (the listener's calls arrive in the tracking plugin's emission order),
 * so the audit log keeps the order of records and events, and `at` (set when the entry is written) never goes back
 * between consecutive entries unless the wall clock does. Uploads run on `PM-upload` ([AuditUploader]).
 *
 * Audit entries (docs/e2e/architecture.md §7):
 * - `record`: every record from [onRecord]; `event`: every event from [onEvent];
 * - `premise`: `monitoring_started` / `monitoring_stopped` ([start] / [stop], a replaced premise, a failed geofence
 *   registration), `enter` / `exit` (a `geofence` record of `premise:<id>`), `presence_violation`,
 *   `service_started` / `service_stopped` / `service_start_failed` (reported by [PremiseMonitorService] or by a
 *   refused start request).
 *
 * Premise logic, while monitoring:
 * - ENTER → `inside = true` (persisted with the ENTER time), `enter`, a start command for the service (always sent,
 *   also when the service still runs, so a stop queued by an EXIT just before does not win); EXIT → `inside = false`,
 *   `exit`, stop the service.
 * - Every other record while inside: presence validation (`distance − accuracy > radius` → `presence_violation`).
 * - A `tracking_stop` record (after its validation) → `inside` becomes unknown (null) and the service stops: no
 *   geofence transitions or fixes arrive while tracking is off, so "inside" can no longer be known; a new ENTER after
 *   the next start sets it.
 * - While inside, the service is (re)started if it is not running: in a new process (restore, boot) the first
 *   record restarts it (detail `restore`); after a refused start it is retried at most once per [restartBackoffMs]
 *   (detail `retry`).
 * - A fix acquired before the ENTER (for example the last known location that a heartbeat carries) is not evidence
 *   of leaving and is not validated.
 *
 * `start`/`stop` calls are numbered ([generation]); the asynchronous geofence answer of an older call never undoes a
 * newer call: a failure rolls back only if no newer call came in, and a geofence added for a premise that is no longer
 * monitored is removed again.
 */
internal class PremiseMonitorCore(
    context: Context,
    private val store: AuditStore = AuditStore(context),
    private val state: PremiseState = PremiseState(context),
    private val gateway: TrackingGateway = LocationTrackingGateway,
    private val services: PremiseServiceControl = AndroidServiceControl,
    retryDelayMs: Long = AuditUploader.RETRY_DELAY_MS,
    private val restartBackoffMs: Long = RESTART_BACKOFF_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val context: Context = context.applicationContext ?: context

    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }

    internal val uploader = AuditUploader(store, { state.auditUrl }, { state.deviceId }, retryDelayMs)

    // ---- PM-native thread only

    /** Bumped by every start/stop call, so a late geofence answer cannot undo a newer call. */
    private var generation = 0

    /** When the last service start was refused (null = no refusal since the last success, EXIT or start). */
    private var lastStartFailureAt: Long? = null

    /** False until the first record of this process was handled (its service restart is a `restore`). */
    private var sawRecord = false

    // ---- listener (called on the tracking plugin's LT-native thread: copy the text only, parse on PM-native)

    fun onRecord(record: JSONObject, source: String = SOURCE_MANIFEST) {
        val meta = meta(source)
        val text = record.toString() // the caller may reuse its object after returning
        execute("onRecord") { handleRecord(JSONObject(text), meta) }
    }

    fun onEvent(name: String, payload: JSONObject, source: String = SOURCE_MANIFEST) {
        val meta = meta(source)
        val text = payload.toString()
        execute("onEvent") {
            append(base(KIND_EVENT, meta).put("name", name).put("payload", JSONObject(text)))
        }
    }

    // ---- service (called on the main thread)

    fun onServiceStarted(premiseId: String?, reason: String) = execute("onServiceStarted") {
        lastStartFailureAt = null
        append(premiseEntry(TYPE_SERVICE_STARTED, premiseId, detail = reason))
    }

    fun onServiceStartFailed(premiseId: String?, error: Throwable) = execute("onServiceStartFailed") {
        lastStartFailureAt = clock()
        append(premiseEntry(TYPE_SERVICE_START_FAILED, premiseId, detail = describe(error)))
    }

    fun onServiceStopped(premiseId: String?, reason: String) = execute("onServiceStopped") {
        append(premiseEntry(TYPE_SERVICE_STOPPED, premiseId, detail = reason))
    }

    // ---- native API (results on PM-native)

    fun start(premiseJson: JSONObject, auditUrl: String?, callback: NativeCallback<JSONObject>) =
        execute("start", callback) {
            val premise = Premise.fromJson(premiseJson)
            val url = normalizeAuditUrl(auditUrl)
            val current = monitoredPremise()
            val startGeneration = ++generation
            if (current != null && current.sameArea(premise)) {
                resumeMonitoring(current, premise, url, startGeneration, callback)
                return@execute
            }
            if (current != null) {
                append(premiseEntry(TYPE_MONITORING_STOPPED, current.id, detail = "replaced by premise ${premise.id}"))
                if (current.geofenceId != premise.geofenceId) removeGeofenceQuietly(current)
                services.stop(context, REASON_PREMISE_CHANGED)
            }
            state.startMonitoring(premise, url)
            lastStartFailureAt = null
            append(premiseEntry(TYPE_MONITORING_STARTED, premise.id, detail = describe(premise, url)))
            // Monitoring is persisted first: the OS may report the initial ENTER before this answer arrives.
            addGeofence(premise, "start.addGeofence", callback) { error ->
                settleGeofenceAdd(premise, startGeneration, error, callback, detailOnSuccess = null)
            }
        }

    fun stop(callback: NativeCallback<JSONObject>) = execute("stop", callback) {
        generation++
        val premise = monitoredPremise()
        if (premise == null) {
            services.stop(context, REASON_STOP_MONITORING)
            deliver(callback, Result.success(statusJson()))
            return@execute
        }
        state.stopMonitoring()
        lastStartFailureAt = null
        append(premiseEntry(TYPE_MONITORING_STOPPED, premise.id))
        services.stop(context, REASON_STOP_MONITORING)
        removeGeofence(premise) { error ->
            execute("stop.removeGeofence", callback) {
                if (error != null) PmLog.w(TAG, "removeGeofence(${premise.geofenceId}) failed", error)
                deliver(callback, Result.success(statusJson()))
            }
        }
    }

    fun status(callback: NativeCallback<JSONObject>) = execute("status", callback) {
        deliver(callback, Result.success(statusJson()))
    }

    fun auditLog(limit: Int, callback: NativeCallback<JSONArray>) = execute("auditLog", callback) {
        val entries = JSONArray()
        for (text in store.recent(limit)) entries.put(JSONObject(text))
        deliver(callback, Result.success(entries))
    }

    // ---- internals (PM-native)

    private fun monitoredPremise(): Premise? = if (state.monitoring) state.premise else null

    private fun handleRecord(record: JSONObject, meta: EntryMeta) {
        append(base(KIND_RECORD, meta).put("record", record))
        val firstRecord = !sawRecord
        sawRecord = true
        val premise = monitoredPremise() ?: return
        val event = record.optString("event")
        val hit = record.optJSONObject("geofence")
        if (event == EVENT_GEOFENCE && hit != null && hit.optString("identifier") == premise.geofenceId) {
            when (hit.optString("action")) {
                "ENTER" -> onEnter(premise, record)
                "EXIT" -> onExit(premise, record)
            }
            return
        }
        if (state.inside == true) validatePresence(premise, record)
        if (event == EVENT_TRACKING_STOP) {
            onTrackingStopped(record)
            return
        }
        if (state.inside == true) ensureServiceRunning(premise, if (firstRecord) REASON_RESTORE else REASON_RETRY)
    }

    private fun onEnter(premise: Premise, record: JSONObject) {
        val enteredAt = if (state.inside == true) {
            state.enteredAt
        } else {
            Iso8601.parse(record.optString("recorded_at")) ?: clock()
        }
        state.setInside(true, enteredAt)
        append(premiseEntry(TYPE_ENTER, premise.id, record, distanceOf(premise, record)))
        lastStartFailureAt = null
        // Always send the start, unless the service runs and no stop was requested since its start: a stop still
        // queued from an EXIT just before would otherwise stop the service while inside.
        if (!(services.isRunning && services.isRunningOrStarting)) startService(premise, REASON_ENTER)
    }

    private fun onExit(premise: Premise, record: JSONObject) {
        state.setInside(false)
        append(premiseEntry(TYPE_EXIT, premise.id, record, distanceOf(premise, record)))
        lastStartFailureAt = null
        services.stop(context, REASON_EXIT)
    }

    private fun onTrackingStopped(record: JSONObject) {
        if (state.inside == null) return
        state.setInside(null)
        val reason = record.optString("reason").takeIf { it.isNotEmpty() && it != "null" }
        services.stop(context, if (reason != null) "$REASON_TRACKING_STOP: $reason" else REASON_TRACKING_STOP)
    }

    private fun validatePresence(premise: Premise, record: JSONObject) {
        val fix = Fix.of(record) ?: return
        val fixTime = Iso8601.parse(record.optString("timestamp"))
        if (fixTime != null && fixTime < state.enteredAt) return
        val distance = premise.distanceTo(fix)
        if (!premise.isCertainlyOutside(fix, distance)) return
        val detail = "distance ${Geo.round(distance)} m - accuracy ${Geo.round(fix.accuracy)} m > radius ${premise.radius} m"
        append(premiseEntry(TYPE_PRESENCE_VIOLATION, premise.id, record, distance, detail))
    }

    private fun ensureServiceRunning(premise: Premise, reason: String) {
        if (services.isRunningOrStarting) return
        val failedAt = lastStartFailureAt
        if (failedAt != null && clock() - failedAt in 0 until restartBackoffMs) return
        startService(premise, reason)
    }

    private fun startService(premise: Premise, reason: String) {
        val error = services.start(context, premise.id, reason) ?: return
        PmLog.w(TAG, "service start refused ($reason)", error)
        lastStartFailureAt = clock()
        append(premiseEntry(TYPE_SERVICE_START_FAILED, premise.id, detail = describe(error)))
    }

    /**
     * Same premise area again (for example a page reload): keep `inside`; take a new name and audit URL; re-register
     * the geofence only if the tracking plugin no longer has it.
     */
    private fun resumeMonitoring(
        current: Premise,
        premise: Premise,
        url: String?,
        startGeneration: Int,
        callback: NativeCallback<JSONObject>,
    ) {
        val changed = state.auditUrl != url || current.name != premise.name
        if (state.auditUrl != url) state.setAuditUrl(url)
        if (current.name != premise.name) state.updatePremise(premise)
        val onGeofences = NativeCallback<JSONArray> { result ->
            execute("start.getGeofences", callback) {
                if (startGeneration != generation) {
                    deliver(callback, Result.success(statusJson())) // a newer call decides
                    return@execute
                }
                val registered = result.getOrNull()?.let { containsIdentifier(it, premise.geofenceId) } ?: false
                if (registered) {
                    if (changed) append(premiseEntry(TYPE_MONITORING_STARTED, premise.id, detail = describe(premise, url)))
                    deliver(callback, Result.success(statusJson()))
                    return@execute
                }
                addGeofence(premise, "start.readdGeofence", callback) { error ->
                    val detail = "geofence re-registered; ${describe(premise, url)}"
                    settleGeofenceAdd(premise, startGeneration, error, callback, detailOnSuccess = detail)
                }
            }
        }
        try {
            gateway.getGeofences(context, onGeofences)
        } catch (e: Exception) {
            onGeofences.onResult(Result.failure(e))
        }
    }

    /**
     * The answer of a geofence add made by the start call number [startGeneration]: a failure rolls monitoring back
     * (unless a newer call came in) and fails the call; a success is audited with [detailOnSuccess] (if any) when the
     * call is still the newest, and the geofence is removed again when its premise is no longer monitored.
     */
    private fun settleGeofenceAdd(
        premise: Premise,
        startGeneration: Int,
        error: Throwable?,
        callback: NativeCallback<JSONObject>,
        detailOnSuccess: String?,
    ) {
        val newest = startGeneration == generation
        if (error != null) {
            if (newest) rollBack(premise, error)
            deliver(callback, failure(error))
            return
        }
        if (newest) {
            if (detailOnSuccess != null) append(premiseEntry(TYPE_MONITORING_STARTED, premise.id, detail = detailOnSuccess))
        } else if (monitoredPremise()?.geofenceId != premise.geofenceId) {
            removeGeofenceQuietly(premise)
        }
        deliver(callback, Result.success(statusJson()))
    }

    /** The geofence could not be added: nothing is monitored, the service stops, no geofence is left behind. */
    private fun rollBack(premise: Premise, error: Throwable) {
        state.stopMonitoring()
        lastStartFailureAt = null
        append(premiseEntry(TYPE_MONITORING_STOPPED, premise.id, detail = "addGeofence failed: ${describe(error)}"))
        services.stop(context, REASON_START_FAILED)
        // A same-id geofence of an earlier registration may still exist in the tracking plugin.
        removeGeofenceQuietly(premise)
    }

    /** Adds the premise geofence; [onResult] runs on PM-native (also when the call throws). */
    private fun addGeofence(
        premise: Premise,
        name: String,
        callback: NativeCallback<JSONObject>,
        onResult: (Throwable?) -> Unit,
    ) {
        try {
            gateway.addGeofence(context, premise.toGeofenceJson()) { result ->
                execute(name, callback) { onResult(result.exceptionOrNull()) }
            }
        } catch (e: Exception) {
            onResult(e)
        }
    }

    /** Removes the premise geofence; [onResult] runs on the gateway's thread, or here if the call throws. */
    private fun removeGeofence(premise: Premise, onResult: (Throwable?) -> Unit) {
        try {
            gateway.removeGeofence(context, premise.geofenceId) { result -> onResult(result.exceptionOrNull()) }
        } catch (e: Exception) {
            onResult(e)
        }
    }

    private fun removeGeofenceQuietly(premise: Premise) {
        removeGeofence(premise) { error ->
            if (error != null) PmLog.w(TAG, "removeGeofence(${premise.geofenceId}) failed", error)
        }
    }

    private fun statusJson(): JSONObject {
        val premise = monitoredPremise()
        return JSONObject()
            .put("monitoring", premise != null)
            .put("premise", premise?.toJson() ?: JSONObject.NULL)
            .put("inside", (if (premise != null) state.inside else null) ?: JSONObject.NULL)
            .put("serviceRunning", services.isRunning)
            .put("auditUrl", state.auditUrl ?: JSONObject.NULL)
            .put("lastEntryAt", store.lastEntryAt() ?: JSONObject.NULL)
            .put("pendingUploads", store.pendingCount())
    }

    private fun append(entry: JSONObject) {
        try {
            store.append(entry)
        } catch (e: Exception) {
            PmLog.e(TAG, "cannot store audit entry ${entry.optString("kind")}", e)
            return
        }
        uploader.kick()
    }

    private fun meta(source: String) = EntryMeta(ProcessInfo.pid(), ProcessInfo.jsLoaded, source)

    /** A new entry; `at` is now (entries are built on PM-native right before they are written). */
    private fun base(kind: String, meta: EntryMeta): JSONObject = JSONObject()
        .put("id", UUID.randomUUID().toString())
        .put("kind", kind)
        .put("at", Iso8601.format(clock()))
        .put("pid", meta.pid)
        .put("js", meta.js)
        .put("source", meta.source)

    private fun premiseEntry(
        type: String,
        premiseId: String?,
        location: JSONObject? = null,
        distance: Double? = null,
        detail: String? = null,
    ): JSONObject {
        val entry = base(KIND_PREMISE, meta(SOURCE_MANIFEST)).put("type", type)
        if (premiseId != null) entry.put("premise_id", premiseId)
        entry.put("location", location ?: JSONObject.NULL)
        if (distance != null) entry.put("distance_m", Geo.round(distance))
        if (detail != null) entry.put("detail", detail)
        return entry
    }

    private fun distanceOf(premise: Premise, record: JSONObject): Double? = Fix.of(record)?.let { premise.distanceTo(it) }

    private fun execute(name: String, block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    PmLog.e(TAG, "$name failed", e)
                }
            }
        } catch (e: Exception) {
            PmLog.e(TAG, "$name not scheduled", e)
        }
    }

    /** Runs [block] on PM-native; an exception it throws is delivered to [callback] as a failure. */
    private fun <T> execute(name: String, callback: NativeCallback<T>, block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    if (e !is TrackingException) PmLog.e(TAG, "$name failed", e)
                    deliver(callback, failure(e))
                }
            }
        } catch (e: Exception) {
            PmLog.e(TAG, "$name not scheduled", e)
            deliver(callback, failure(e))
        }
    }

    private fun <T> deliver(callback: NativeCallback<T>, result: Result<T>) {
        try {
            callback.onResult(result)
        } catch (e: Throwable) {
            PmLog.e(TAG, "callback failed", e)
        }
    }

    /** Test support: waits until PM-native has run every queued task (including tasks those tasks queued). */
    @VisibleForTesting
    internal fun awaitIdle(timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            executor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        } while ((executor.queue.isNotEmpty() || executor.activeCount > 0) && System.currentTimeMillis() < deadline)
    }

    internal fun shutdown() {
        executor.shutdownNow()
        uploader.shutdown()
        try {
            store.close()
        } catch (e: Exception) {
            PmLog.w(TAG, "cannot close the audit store", e)
        }
    }

    internal companion object {
        private const val TAG = "PM.Core"
        const val THREAD_NAME = "PM-native"

        /** At most one service start retry per minute after a refusal, so a refusing OS does not flood the log. */
        const val RESTART_BACKOFF_MS = 60_000L

        const val SOURCE_MANIFEST = "manifest"
        const val SOURCE_SUBSCRIPTION = "subscription"

        const val KIND_RECORD = "record"
        const val KIND_EVENT = "event"
        const val KIND_PREMISE = "premise"

        const val TYPE_MONITORING_STARTED = "monitoring_started"
        const val TYPE_MONITORING_STOPPED = "monitoring_stopped"
        const val TYPE_ENTER = "enter"
        const val TYPE_EXIT = "exit"
        const val TYPE_PRESENCE_VIOLATION = "presence_violation"
        const val TYPE_SERVICE_STARTED = "service_started"
        const val TYPE_SERVICE_STOPPED = "service_stopped"
        const val TYPE_SERVICE_START_FAILED = "service_start_failed"

        /** `detail` of service entries. */
        const val REASON_ENTER = "enter"
        const val REASON_EXIT = "exit"
        const val REASON_RESTORE = "restore"
        const val REASON_RETRY = "retry"
        const val REASON_STOP_MONITORING = "stop_monitoring"
        const val REASON_PREMISE_CHANGED = "premise_changed"
        const val REASON_START_FAILED = "monitoring_start_failed"
        const val REASON_TRACKING_STOP = "tracking_stop"

        private const val EVENT_GEOFENCE = "geofence"
        private const val EVENT_TRACKING_STOP = "tracking_stop"

        @Volatile
        private var instance: PremiseMonitorCore? = null

        fun get(context: Context): PremiseMonitorCore = instance ?: synchronized(this) {
            instance ?: PremiseMonitorCore(context.applicationContext ?: context).also { instance = it }
        }

        /** The instance of this process, without creating it. */
        fun peek(): PremiseMonitorCore? = instance

        /** Test support: replaces the process instance (the previous one is shut down). */
        @VisibleForTesting
        fun install(core: PremiseMonitorCore?) {
            synchronized(this) {
                instance?.takeIf { it !== core }?.shutdown()
                instance = core
            }
        }

        fun describe(error: Throwable): String = "${error.javaClass.name}: ${error.message}"

        private fun describe(premise: Premise, url: String?): String =
            "premise ${premise.toJson()}; auditUrl ${url ?: "none"}"

        private fun containsIdentifier(geofences: JSONArray, identifier: String): Boolean =
            (0 until geofences.length()).any { geofences.optJSONObject(it)?.optString("identifier") == identifier }

        private fun <T> failure(error: Throwable): Result<T> = Result.failure(
            error as? TrackingException
                ?: TrackingException(ErrorCode.INTERNAL, error.message ?: error.javaClass.simpleName, error),
        )
    }
}
