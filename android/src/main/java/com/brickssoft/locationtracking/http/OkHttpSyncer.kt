package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.data.LocationStore
import com.brickssoft.locationtracking.device.DeviceMonitor
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.ConnectivityType
import com.brickssoft.locationtracking.model.HttpResult
import com.brickssoft.locationtracking.model.Record
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * [HttpSyncer] over OkHttp (architecture §2; round 2 §4 for `http.syncInterval`).
 *
 * - Automatic passes run on [scope] / `dispatchers.io`, triggered by [onRecordInserted], by
 *   `ConnectivityChange(connected = true)` once [start]ed, by [start] itself, and by the `syncInterval` timer
 *   (below). [SyncPolicy] decides what they send. Triggers are coalesced: any number of triggers during a pass
 *   schedule exactly one more pass.
 * - Uploads are single-flight: automatic passes and [sync] share one mutex, so a record is never in two requests
 *   at once.
 * - The queue drains oldest first, `maxBatchSize` records per request when `batchSync`, else one per request, and
 *   stops at the first failure. If the server rejected an upload, an automatic pass still sends the queued priority
 *   records (heartbeat and audit records), so one record the server keeps rejecting cannot hold them back.
 * - One upload makes up to 4 tries (owner decision R2-Q18): a try without an answer (connection error or timeout),
 *   HTTP 5xx or HTTP 429 is tried again after 2, 4 and 8 s, while the upload lock is held. Any other answer ends the
 *   upload; a 401 keeps its token refresh and one more request, once per upload.
 * - 2xx deletes the records; a failure that ends the upload keeps them and marks one attempt. One `Http` event
 *   is emitted per HTTP request (every try; a 401 that triggers a token refresh and a retry yields two).
 *
 * **`http.syncInterval` (seconds, 0 = off).** While tracking is on (`runtime.enabled`) with `syncInterval > 0` and
 * `autoSync`, normal records (every event that is not a priority record) are held until the oldest pending normal
 * record is `syncInterval` seconds old, or until the queue reaches `autoSyncThreshold` when that is above 0; then
 * one pass uploads the whole queue. Priority records still upload at once and take the whole queue with them.
 * Every pass checks this rule (a pass runs on every insert, on connectivity regained, on [start], and on the timer).
 * - **Timer.** After each pass, while the rule applies, `http.url` is valid and the oldest pending normal record is
 *   not yet due, one coroutine timer is armed for `oldest.recorded_at + syncInterval`; it requests a pass, so the
 *   upload happens without a further record. It is replaced when that due time changes. A pass that finds the queue
 *   empty, tracking off or the rule off cancels it. Deleting records through the store directly (for example
 *   `destroyLocations()`) is not seen: the timer then fires once and its pass finds nothing to send.
 * - **Tracking off.** No timer, and normal records follow the `syncInterval = 0` rule (`autoSyncThreshold`), so a
 *   record created while tracking is off (for example by `getCurrentPosition`) is not held without a timer.
 * - **Settings.** Once `syncInterval > 0` was seen in this process, a pass also runs when tracking is switched on or
 *   off and when `url`, `autoSync`, `autoSyncThreshold`, `syncInterval` or `disableAutoSyncOnCellular` change.
 * - **Failures.** After a failed automatic upload (after its last try), the rule retries normal records once per
 *   `syncInterval`
 *   (measured on elapsed realtime) through the timer, not on every insert. Connectivity regained, a queued priority
 *   record and [sync] still upload at once. Records that are due but could not be tried (offline, cellular
 *   restricted) get no timer: connectivity regained triggers them.
 *
 * Worst-case staleness of the server's live location: about `syncInterval`. The timer is a coroutine `delay` and
 * holds no wake lock. On Android a `delay` counts only the time the CPU is awake (the monotonic clock stops in deep
 * sleep), so in deep sleep or Doze the timer fires later than the due time. Two other triggers bound that delay:
 * while moving, every new location record runs the check against the wall clock; while stationary, the heartbeat
 * alarm records a heartbeat, a priority record that uploads the whole queue. With the heartbeat disabled and no new
 * record, held records wait until the CPU is awake long enough for the rest of the `delay` to run.
 * A wall clock set back makes the records created before the change look newer than the ones created after it; the
 * store orders by `recorded_at`, so such records wait for the oldest record created after the change (at most
 * `syncInterval` after it) unless they are the only pending normal records, which are then due at once.
 * Held records count against `persistence.maxRecordsToPersist` (default unlimited): a limit below the number of
 * records created in `syncInterval` lets pruning delete held records before they are uploaded.
 */
class OkHttpSyncer(
    private val configStore: ConfigStore,
    private val locationStore: LocationStore,
    private val device: DeviceMonitor,
    private val events: EventBus,
    private val clock: Clock,
    private val http: OkHttpClient,
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope,
) : HttpSyncer {
    private val uploadLock = Mutex()
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private val workerLaunched = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    /** The connectivity of the latest `ConnectivityChange(connected = true)` not yet seen by a pass. */
    private val connectivityHint = AtomicReference<Connectivity?>(null)
    private val authorization = AuthorizationManager(configStore, events, clock, dispatchers)

    /** Client derived from [http] for the last timeout used. */
    @Volatile
    private var timedClient: Pair<Long, OkHttpClient>? = null

    /** Guards [intervalTimer] and [intervalTimerKey]. */
    private val timerLock = Any()

    /** The armed `syncInterval` check (see the class docs), or null. */
    private var intervalTimer: Job? = null

    /** What the armed [intervalTimer] waits for. */
    private var intervalTimerKey: TimerKey? = null

    /**
     * Elapsed-realtime ms before which the interval rule does not retry normal records after a failed automatic
     * upload; null when no retry is pending. Elapsed realtime, so a wall-clock change cannot stretch the wait.
     */
    @Volatile
    private var retryAtElapsed: Long? = null

    private val stateWatcherLaunched = AtomicBoolean(false)

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        events.subscribe { event ->
            if (event is TrackingEvent.ConnectivityChange) {
                if (event.connectivity.connected) {
                    connectivityHint.set(event.connectivity)
                    requestPass()
                } else {
                    connectivityHint.set(null)
                }
            }
        }
        requestPass()
    }

    override fun onRecordInserted(record: Record) {
        if (!SyncPolicy.hasUrl(configStore.config.value.http)) return
        requestPass()
    }

    override suspend fun sync(): List<Record> {
        val url = configStore.config.value.http.url
        if (url.isNullOrBlank()) throw TrackingException(ErrorCode.NO_URL, "http.url is not set")
        if (HttpSupport.parseUrl(url) == null) {
            throw TrackingException(ErrorCode.NO_URL, "http.url is not a valid http(s) URL")
        }
        val result = uploadLock.withLock {
            val drained = drain(SyncPolicy.Scope.ALL)
            if (drained.failure == null) retryAtElapsed = null
            updateIntervalTimer(null)
            drained
        }
        result.failure?.let { failure ->
            val r = failure.result
            val message = "upload of ${r.uuids.size} record(s) failed"
            throw when (failure.kind) {
                FailureKind.HTTP -> TrackingException(ErrorCode.HTTP_ERROR, "$message: HTTP ${r.status}")
                FailureKind.NETWORK -> TrackingException(ErrorCode.NETWORK_ERROR, "$message: ${r.responseText}")
                FailureKind.INTERNAL -> TrackingException(ErrorCode.INTERNAL, "$message: ${r.responseText}")
            }
        }
        return result.uploaded
    }

    /** Schedules an automatic pass; never blocks. The worker is launched on first use. */
    private fun requestPass() {
        if (workerLaunched.compareAndSet(false, true)) {
            scope.launch(dispatchers.io) {
                while (true) {
                    triggers.receive()
                    try {
                        uploadLock.withLock { autoPass() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // autoPass handles its own failures; this keeps the worker alive if that ever changes.
                        Logger.e(TAG, "automatic pass failed unexpectedly", e)
                    }
                }
            }
        }
        triggers.trySend(Unit)
    }

    /** One automatic pass: the upload [SyncPolicy] allows, then the `syncInterval` timer for what stays queued. */
    private suspend fun autoPass() {
        val lookup = try {
            uploadPass()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "automatic upload failed", e)
            null
        }
        updateIntervalTimer(lookup)
    }

    /**
     * Uploads what [SyncPolicy.autoScope] allows. Returns the oldest-normal-record lookup it made if it left the
     * queue unchanged, else null.
     */
    private suspend fun uploadPass(): OldestNormal? {
        val hint = connectivityHint.getAndSet(null)
        // Connectivity regained since the last pass: retry at once. Cleared here, not in the event handler, so a pass
        // that fails while the event arrives cannot set the wait again after the handler cleared it.
        if (hint != null) retryAtElapsed = null
        val config = configStore.config.value.http
        if (!SyncPolicy.hasUrl(config)) return null
        if (HttpSupport.parseUrl(config.url) == null) {
            Logger.e(TAG, "http.url is not a valid http(s) URL; nothing uploaded")
            return null
        }
        val connectivity = currentConnectivity(hint)
        if (!connectivity.connected) {
            Logger.d(TAG, "offline; upload deferred until connectivity returns")
            return null
        }
        val priority = locationStore.count(SyncPolicy.PRIORITY_EVENTS)
        val queued = locationStore.count()
        val intervalOn = intervalApplies(config)
        // The interval rule needs the oldest pending normal record only when no priority record forces an upload.
        val lookup = if (intervalOn && priority == 0) lookUpOldestNormal() else null
        val check = when {
            !intervalOn -> SyncPolicy.IntervalCheck.OFF
            isRetryPending() -> SyncPolicy.IntervalCheck.RETRY_PENDING
            lookup?.record?.let { SyncPolicy.isIntervalDue(config, it.recordedAt, lookup.now) } == true ->
                SyncPolicy.IntervalCheck.DUE
            else -> SyncPolicy.IntervalCheck.NOT_DUE
        }
        val plan = SyncPolicy.autoScope(config, connectivity, queued, priority, check)
        if (plan == SyncPolicy.Scope.NONE) return lookup
        val failure = drain(plan).failure
        if (plan == SyncPolicy.Scope.ALL && priority > 0 && failure != null && failure.kind != FailureKind.NETWORK) {
            // The server is reachable but rejected an older upload: still deliver the audit records behind it.
            // Priority records that just failed on their own are not re-sent in this pass.
            val skip = if (failure.records.all { it.event.isPriority }) failure.result.uuids.toSet() else emptySet()
            drain(SyncPolicy.Scope.PRIORITY_ONLY, skip)
        }
        if (failure != null && intervalOn) {
            retryAtElapsed = clock.elapsedRealtime() + SyncPolicy.intervalMs(config)
        } else if (failure == null && plan == SyncPolicy.Scope.ALL) {
            retryAtElapsed = null
        }
        return null
    }

    /** True if normal records follow `syncInterval` now: configured ([SyncPolicy.usesInterval]) and tracking is on. */
    private fun intervalApplies(config: HttpConfig): Boolean =
        SyncPolicy.usesInterval(config) && configStore.runtime.value.enabled

    private fun isRetryPending(): Boolean = retryAtElapsed?.let { clock.elapsedRealtime() < it } ?: false

    private suspend fun lookUpOldestNormal(): OldestNormal =
        OldestNormal(locationStore.list(1, SyncPolicy.NORMAL_EVENTS).firstOrNull(), clock.now())

    /**
     * Arms, keeps or cancels the `syncInterval` timer for the current queue (see the class docs). [known] is a lookup
     * made by a pass that left the queue unchanged; its `now` is reused so the pass and the timer agree on whether
     * the record is due. Null means look it up now. Never throws (except cancellation).
     */
    private suspend fun updateIntervalTimer(known: OldestNormal?) {
        try {
            val config = configStore.config.value.http
            if (config.syncInterval > 0) launchStateWatcher()
            if (!intervalApplies(config) || HttpSupport.parseUrl(config.url) == null) {
                cancelIntervalTimer()
                return
            }
            val lookup = known ?: lookUpOldestNormal()
            val oldest = lookup.record
            if (oldest == null) {
                cancelIntervalTimer()
                return
            }
            val dueAt = SyncPolicy.intervalDueAt(config, oldest.recordedAt, lookup.now)
            val retryAt = retryAtElapsed?.takeIf { it > clock.elapsedRealtime() }
            if (dueAt == null && retryAt == null) {
                // Due and not waiting for a retry: this pass uploaded it, or could not try (offline, cellular
                // restricted). The next record, connectivity or start() triggers the next attempt.
                cancelIntervalTimer()
                return
            }
            val untilDue = dueAt?.let { it - clock.now() } ?: 0L
            val untilRetry = retryAt?.let { it - clock.elapsedRealtime() } ?: 0L
            armIntervalTimer(TimerKey(dueAt, retryAt), maxOf(untilDue, untilRetry, 0L), config.syncInterval)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "could not schedule the syncInterval check", e)
        }
    }

    /** Arms the timer for [key] unless it is already armed for it; replaces a timer armed for anything else. */
    private fun armIntervalTimer(key: TimerKey, delayMs: Long, syncInterval: Int) {
        synchronized(timerLock) {
            val current = intervalTimer
            if (current != null && current.isActive && intervalTimerKey == key) return
            current?.cancel()
            intervalTimerKey = key
            val due = key.dueAt?.let { Iso8601.format(it) } ?: "now"
            val retry = if (key.retryAtElapsed != null) ", retry after a failed upload" else ""
            Logger.d(TAG, "$TIMER_ARMED in ${delayMs / MS_PER_SECOND} s (syncInterval $syncInterval s, due $due$retry)")
            intervalTimer = scope.launch(dispatchers.io) {
                delay(delayMs)
                val self = currentCoroutineContext()[Job]
                synchronized(timerLock) {
                    if (intervalTimer === self) {
                        intervalTimer = null
                        intervalTimerKey = null
                    }
                }
                requestPass()
            }
        }
    }

    private fun cancelIntervalTimer() {
        synchronized(timerLock) {
            intervalTimer?.cancel()
            intervalTimer = null
            intervalTimerKey = null
        }
    }

    /**
     * Once `syncInterval` has been set in this process: a pass whenever tracking is switched on or off or an
     * interval-related `http` setting changes, so the timer and the held records follow at once (for example tracking
     * stopped: the timer is cancelled and the held records follow the rules for `syncInterval = 0`).
     */
    private fun launchStateWatcher() {
        if (!stateWatcherLaunched.compareAndSet(false, true)) return
        var last = WatchedState.of(configStore.config.value, configStore.runtime.value)
        scope.launch(dispatchers.io) {
            combine(configStore.config, configStore.runtime, WatchedState::of).collect { state ->
                if (state != last) {
                    last = state
                    requestPass()
                }
            }
        }
    }

    /**
     * The monitor's connectivity, unless it says offline while [hint] (a `ConnectivityChange(connected = true)` since
     * the last pass) says otherwise: the OS may report a new network before it becomes the default one.
     */
    private fun currentConnectivity(hint: Connectivity?): Connectivity {
        val reported = try {
            device.connectivity()
        } catch (e: Exception) {
            Logger.w(TAG, "connectivity unknown; trying to upload anyway", e)
            return Connectivity(connected = true, type = ConnectivityType.OTHER)
        }
        return if (!reported.connected && hint != null) hint else reported
    }

    /**
     * Uploads the queue ([plan] ALL or PRIORITY_ONLY) oldest first, skipping [skip], until it is empty or an upload
     * fails.
     */
    private suspend fun drain(plan: SyncPolicy.Scope, skip: Set<String> = emptySet()): DrainResult {
        val filter = if (plan == SyncPolicy.Scope.PRIORITY_ONLY) SyncPolicy.PRIORITY_EVENTS else null
        val uploaded = ArrayList<Record>()
        val attempted = HashSet(skip)
        while (true) {
            val config = configStore.config.value.http
            val url = HttpSupport.parseUrl(config.url) ?: break
            val limit = SyncPolicy.chunkSize(config)
            // Over-fetch by the skipped count so skipped records cannot hide the rest of the queue.
            val chunk = locationStore.list(limit + skip.size, filter).filter { it.uuid !in attempted }.take(limit)
            if (chunk.isEmpty()) break
            chunk.mapTo(attempted) { it.uuid }
            val failure = upload(url, config, chunk)
            if (failure != null) return DrainResult(uploaded, failure)
            uploaded += chunk
        }
        return DrainResult(uploaded, null)
    }

    /**
     * One upload of [records]: up to `1 + RETRY_DELAYS_MS.size` tries (owner decision R2-Q18), then the store update.
     * Only a try without an answer, HTTP 5xx or HTTP 429 is tried again, after the next delay. Returns null on
     * success.
     */
    private suspend fun upload(url: HttpUrl, config: HttpConfig, records: List<Record>): Failure? =
        withContext(dispatchers.io) {
            val uuids = records.map { it.uuid }
            var outcome = tryUpload(url, config, records, uuids, mayRefresh = true)
            var refreshed = outcome.refreshed
            for (wait in RETRY_DELAYS_MS) {
                if (!outcome.retryable) break
                if (!outcome.reported) events.emit(TrackingEvent.Http(outcome.result))
                Logger.w(TAG, "upload of ${uuids.size} record(s) failed (${outcome.reason}); next try in ${wait / MS_PER_SECOND} s")
                delay(wait)
                // At most one token refresh per upload, whether before a request or after a 401.
                outcome = tryUpload(url, config, records, uuids, mayRefresh = !refreshed)
                refreshed = refreshed || outcome.refreshed
            }
            if (outcome.kind == null) {
                locationStore.delete(uuids)
                Logger.i(TAG, "uploaded ${uuids.size} record(s): HTTP ${outcome.result.status}")
            } else {
                locationStore.markAttempt(uuids, clock.now())
                Logger.w(TAG, "upload of ${uuids.size} record(s) failed (${outcome.reason}); kept in the queue")
            }
            if (!outcome.reported) events.emit(TrackingEvent.Http(outcome.result))
            outcome.kind?.let { Failure(records, outcome.result, it) }
        }

    /**
     * One try of an upload: pre-emptive token refresh, request, and on a 401 a token refresh and one more request; no
     * refresh at all unless [mayRefresh]. Emits the Http event of every request but the last one (see [Try.reported]).
     */
    private suspend fun tryUpload(
        url: HttpUrl,
        config: HttpConfig,
        records: List<Record>,
        uuids: List<String>,
        mayRefresh: Boolean,
    ): Try {
        var reported = false
        var refreshed = false
        return try {
            val client = clientFor(config.timeout)
            val bearer = authorization.appliesTo(config)
            val token = if (bearer) authorization.tokenForRequest(client, mayRefresh) else NO_TOKEN
            refreshed = token.refreshAttempted
            var response = send(client, url, config, records, token.value)
            if (response.status == HTTP_UNAUTHORIZED && bearer && mayRefresh && !token.refreshAttempted) {
                events.emit(TrackingEvent.Http(HttpResult(false, response.status, response.body, uuids)))
                reported = true
                refreshed = true
                val renewed = authorization.refresh(client, token.value)
                if (renewed != null) {
                    reported = false // the retry is a new request with its own event, even if it throws
                    response = send(client, url, config, records, renewed)
                }
            }
            val result = HttpResult(response.isSuccessful, response.status, response.body, uuids)
            Try(result, if (result.success) null else FailureKind.HTTP, reported, refreshed)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // a call cancelled with the coroutine is not an upload failure
            Try(HttpResult(false, 0, e.message ?: e.javaClass.simpleName, uuids), FailureKind.NETWORK, reported, refreshed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "upload of ${uuids.size} record(s) failed unexpectedly", e)
            Try(HttpResult(false, 0, e.message ?: e.javaClass.simpleName, uuids), FailureKind.INTERNAL, reported, refreshed)
        }
    }

    private suspend fun send(
        client: OkHttpClient,
        url: HttpUrl,
        config: HttpConfig,
        records: List<Record>,
        token: String?,
    ): HttpResponse {
        val body = BodyBuilder.build(records, config, clock.now())
        val headers = HttpSupport.headers(HttpSupport.JSON_CONTENT_TYPE, config.headers, token)
        return HttpSupport.execute(client, HttpSupport.request(url, config.method.wire, headers, body), dispatchers.io)
    }

    private fun clientFor(timeoutMs: Long): OkHttpClient {
        timedClient?.let { (timeout, client) -> if (timeout == timeoutMs) return client }
        return HttpSupport.client(http, timeoutMs).also { timedClient = timeoutMs to it }
    }

    private enum class FailureKind {
        /** The server answered with a non-2xx status. */
        HTTP,

        /** No HTTP status was received. */
        NETWORK,

        /** The upload could not be attempted (e.g. the body could not be built). */
        INTERNAL,
    }

    /**
     * The outcome of one [tryUpload]: [kind] null on success. [reported] means the Http event for [result] was
     * already emitted (a 401 whose token refresh failed); [refreshed] means the try attempted a token refresh.
     */
    private class Try(val result: HttpResult, val kind: FailureKind?, val reported: Boolean, val refreshed: Boolean) {
        /** True for a request without an answer, HTTP 5xx or HTTP 429 (R2-Q18). */
        val retryable: Boolean
            get() = kind == FailureKind.NETWORK ||
                (kind == FailureKind.HTTP && (result.status in HTTP_SERVER_ERRORS || result.status == HTTP_TOO_MANY_REQUESTS))

        val reason: String get() = if (kind == FailureKind.HTTP) "HTTP ${result.status}" else result.responseText
    }

    /** A failed upload of [records]. */
    private class Failure(val records: List<Record>, val result: HttpResult, val kind: FailureKind)

    private class DrainResult(val uploaded: List<Record>, val failure: Failure?)

    /** The oldest pending normal record ([record], null if none) as looked up at wall time [now]. */
    private class OldestNormal(val record: Record?, val now: Long)

    /**
     * What an armed `syncInterval` timer waits for: [dueAt] (wall ms) when the oldest pending normal record becomes
     * due, null if it is due already; [retryAtElapsed] (elapsed-realtime ms) for a retry after a failed upload.
     */
    private data class TimerKey(val dueAt: Long?, val retryAtElapsed: Long?)

    /** The settings and runtime state whose change re-evaluates the `syncInterval` rule. */
    private data class WatchedState(
        val url: String?,
        val autoSync: Boolean,
        val autoSyncThreshold: Int,
        val syncInterval: Int,
        val disableAutoSyncOnCellular: Boolean,
        val trackingEnabled: Boolean,
    ) {
        companion object {
            fun of(config: Config, runtime: RuntimeState) = WatchedState(
                config.http.url,
                config.http.autoSync,
                config.http.autoSyncThreshold,
                config.http.syncInterval,
                config.http.disableAutoSyncOnCellular,
                runtime.enabled,
            )
        }
    }

    private companion object {
        const val TAG = "LT.Http"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY_REQUESTS = 429
        val HTTP_SERVER_ERRORS = 500..599

        /** Waits before the retries of one upload (R2-Q18): 3 retries after 2, 4 and 8 s. */
        val RETRY_DELAYS_MS = longArrayOf(2_000L, 4_000L, 8_000L)
        const val MS_PER_SECOND = 1_000L

        /** Start of the debug log line written when the `syncInterval` timer is armed (tests look for it). */
        const val TIMER_ARMED = "syncInterval check armed"
        val NO_TOKEN = AuthorizationManager.Token(null, refreshAttempted = false)
    }
}
