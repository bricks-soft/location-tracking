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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
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
 *   HTTP 5xx or HTTP 429 is tried again after about 2, 4 and 8 s (each wait moved by up to 20 % up or down), while the
 *   upload lock is held. A 429 or 503 whose `Retry-After` asks for a longer wait than the next retry ends the upload.
 *   Any other answer ends the upload; a 401 keeps its token refresh and one more request, once per upload. A 3xx is a
 *   failed upload like any other non-2xx answer: the client follows no redirects ([HttpSupport.sharedClient]).
 * - Every try of one upload is sent with the credentials read at the start of the upload, or credentials refreshed
 *   from them ([AuthorizationManager]); if the app configured other tokens in the meantime, the upload stops and keeps
 *   its records (recommendation 2). No wait is stored and no attempt is counted. If `http.authorization` is still set,
 *   a new automatic pass runs [CHANGED_RETRY_MS] later with the new tokens (a coroutine timer, and the upload job for
 *   the case that this process dies); automatic passes before then upload nothing, so other triggers join that pass.
 *   If it was removed, nothing is started (README, "Logging out and switching users").
 * - 2xx deletes the records; a failure that ends the upload keeps them and marks one attempt. One `Http` event
 *   is emitted per HTTP request (every try; a 401 that triggers a token refresh and a retry yields two). At most
 *   [HttpSupport.MAX_UPLOAD_RESPONSE_BYTES] of an answer are read into the event.
 * - HTTP 413 for a batch of more than one record halves the batch size, and the same records are sent again at once in
 *   smaller batches. The smaller size applies for the rest of the pass, and the next pass after a pass with a success
 *   uses `maxBatchSize` again. A 413 for a single record is a failure like other 4xx answers.
 *
 * **Stored wait between failed automatic uploads** (recommendations 4 and 11; [NextTry], [UploadBackoff]). After a
 * failed automatic upload the next allowed time is stored (it survives a process restart):
 * - reason `server` (the server answered with a non-2xx status other than an authorization failure, or did not
 *   answer): 30 s, doubling with each further failure up to 15 minutes, each moved by up to 20 % up or down; at least
 *   the `Retry-After` of a 429 or 503 (limited to `http.maxRetryAfter`); while the `syncInterval` rule applies, at least
 *   `syncInterval`. Cleared when the network returns.
 * - reason `authorization` (HTTP 401 and the token could not be refreshed, or the stored tokens cannot be read):
 *   15 minutes, ended at once when the app configures other tokens. Not cleared when the network returns.
 * - Both are cleared by a successful upload. While the wait runs, normal records are not sent automatically; priority
 *   records and [sync] are not held back, as before. When the wait ends, the upload job (scheduled for that time)
 *   runs a pass; while the `syncInterval` rule applies, its timer does too.
 *
 * **Upload job** (recommendation 10, [UploadScheduler]). Queued records are sent whether tracking is on or off, also
 * after this process died: after an automatic pass that could not send due records because the device was offline
 * (or on cellular with `disableAutoSyncOnCellular`), the upload job is scheduled to run as soon as a (unmetered)
 * network is available; after a failed automatic upload it is scheduled for the stored next-try time; when the queue
 * becomes empty it is cancelled. [onTrackingStopped] schedules it at once as a safety net, and so does every automatic
 * upload outside the job while tracking is off: such a process has no foreground service, so Android can freeze it
 * (cached app freezer) or kill it during the upload and its retry waits.
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
 * - **Failures.** After a failed automatic upload (after its last try), normal records wait for the stored next-try
 *   time (above), which is at least `syncInterval` while the rule applies; the timer then requests a pass. Connectivity
 *   regained, a queued priority record and [sync] still upload at once. Records that are due but could not be tried
 *   (offline, cellular restricted) get no timer: connectivity regained (or the upload job) triggers them.
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
    private val nextTryStore: NextTryStore = NextTryStore.inMemory(),
    private val scheduler: UploadScheduler = UploadScheduler.NONE,
    /** Uniform in [0, 1): the jitter of the retry waits. */
    private val random: () -> Double = { Random.nextDouble() },
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

    /** The stored wait after a failed automatic upload (see the class docs); loaded on first use. */
    @Volatile
    private var nextTryValue: NextTry? = null

    @Volatile
    private var nextTryLoaded = false

    /** The batch size after an HTTP 413 (see the class docs), or null for `maxBatchSize`. Guarded by [uploadLock]. */
    private var reducedBatch: Int? = null

    /** What the last automatic pass did; read by [runScheduledPass]. Guarded by [uploadLock]. */
    private var lastPass = PassResult.NOTHING

    /** Automatic passes started so far (see [uploadTicket]). */
    private val passesStarted = AtomicLong(0)

    /** The number of the newest finished automatic pass. */
    private val passesFinished = MutableStateFlow(0L)

    private val stateWatcherLaunched = AtomicBoolean(false)

    /** `elapsedRealtime` (deep sleep included) when the last upload request ended, or null. */
    @Volatile
    private var lastExchangeEnd: Long? = null

    /** The timer of the pass after an upload stopped for new tokens; guarded by [uploadLock]. */
    private var changedRetry: Job? = null

    /** True until [changedRetry] runs: automatic passes upload nothing meanwhile. */
    @Volatile
    private var changedPause = false

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
        // New tokens from the app end an authorization wait at once.
        scope.launch(dispatchers.io) {
            configStore.config.map { authorization.current() }.distinctUntilChanged().drop(1).collect {
                if (nextTry()?.reason == NextTry.Reason.AUTHORIZATION) requestPass()
            }
        }
        requestPass()
    }

    override fun onTrackingStopped() {
        if (!SyncPolicy.hasUrl(configStore.config.value.http)) return
        // A safety net: the pass that the tracking_stop record started cancels the job again if it empties the queue.
        scheduler.schedule(0L)
    }

    override suspend fun runScheduledPass() {
        repeat(MAX_JOB_PASSES) {
            val result = uploadLock.withLock {
                autoPass(fromJob = true)
                lastPass
            }
            // "After a success with records left, it runs again at once": records that arrived during the pass.
            if (result != PassResult.SUCCESS || locationStore.count() == 0) return
        }
    }

    override fun uploadTicket(): Long = passesStarted.get() + 1

    override suspend fun awaitUpload(ticket: Long) {
        if (HttpSupport.parseUrl(configStore.config.value.http.url) == null) return
        passesFinished.first { it >= ticket }
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
            if (drained.anySuccess) reducedBatch = null
            if (drained.failure == null) {
                setNextTry(null)
                cancelJobIfEmpty()
            }
            if (drained.failure?.kind == FailureKind.CHANGED) retryAfterAuthorizationChange(configStore.config.value.http)
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
                FailureKind.AUTH -> TrackingException(
                    ErrorCode.HTTP_ERROR,
                    "$message: authorization failed (${if (r.status > 0) "HTTP ${r.status}" else r.responseText})",
                )
                FailureKind.CHANGED -> TrackingException(
                    ErrorCode.HTTP_ERROR,
                    "upload of ${r.uuids.size} record(s) stopped: http.authorization changed during the upload; " +
                        "the records are kept",
                )
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

    /**
     * One automatic pass: the upload [SyncPolicy] allows, then the timer for what stays queued. Caller holds
     * [uploadLock].
     */
    private suspend fun autoPass(fromJob: Boolean = false) {
        val number = passesStarted.incrementAndGet()
        try {
            lastPass = PassResult.NOTHING
            val lookup = try {
                uploadPass(fromJob)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "automatic upload failed", e)
                null
            }
            updateIntervalTimer(lookup)
        } finally {
            passesFinished.update { maxOf(it, number) }
        }
    }

    /**
     * Uploads what [SyncPolicy.autoScope] allows. Returns the oldest-normal-record lookup it made if it left the
     * queue unchanged, else null.
     */
    private suspend fun uploadPass(fromJob: Boolean): OldestNormal? {
        if (changedPause) {
            // Triggers during the pause after new tokens (the upload job's included) wait for the pass at its end.
            Logger.d(TAG, "http.authorization changed: automatic uploads wait for the pass after the pause")
            return null
        }
        val hint = connectivityHint.getAndSet(null)
        // Connectivity regained since the last pass: retry at once. Cleared here, not in the event handler, so a pass
        // that fails while the event arrives cannot set the wait again after the handler cleared it.
        if (hint != null) clearServerWait("connectivity returned")
        val config = configStore.config.value.http
        if (!SyncPolicy.hasUrl(config)) return null
        if (HttpSupport.parseUrl(config.url) == null) {
            Logger.e(TAG, "http.url is not a valid http(s) URL; nothing uploaded")
            return null
        }
        val priority = locationStore.count(SyncPolicy.PRIORITY_EVENTS)
        val queued = locationStore.count()
        if (queued == 0) {
            cancelJobIfEmpty(0)
            return null
        }
        val intervalOn = intervalApplies(config)
        val waiting = isWaiting(config)
        // The interval rule needs the oldest pending normal record only when no priority record forces an upload.
        val lookup = if (intervalOn && priority == 0) lookUpOldestNormal() else null
        val check = when {
            !intervalOn -> SyncPolicy.IntervalCheck.OFF
            lookup?.record?.let { SyncPolicy.isIntervalDue(config, it.recordedAt, lookup.now) } == true ->
                SyncPolicy.IntervalCheck.DUE
            else -> SyncPolicy.IntervalCheck.NOT_DUE
        }
        val connectivity = currentConnectivity(hint)
        // What would be sent on an unrestricted network: records that are due although this pass cannot send them.
        val dueOnline = SyncPolicy.autoScope(config, ONLINE, queued, priority, check, waiting)
        if (!connectivity.connected) {
            Logger.d(TAG, "offline; upload deferred until connectivity returns")
            if (dueOnline != SyncPolicy.Scope.NONE) scheduler.schedule(0L)
            return null
        }
        val plan = SyncPolicy.autoScope(config, connectivity, queued, priority, check, waiting)
        if (SyncPolicy.isCellularRestricted(config, connectivity) && dueOnline == SyncPolicy.Scope.ALL &&
            queued > priority
        ) {
            // Normal records wait for an unmetered network, in this process or the next one.
            scheduler.schedule(0L, unmetered = true)
        }
        if (plan == SyncPolicy.Scope.NONE) return lookup
        if (!fromJob && !configStore.runtime.value.enabled) {
            // Without a tracking session nothing keeps this process running: Android 11+ freezes a cached process
            // (seen on API 34 during the retry waits) and may kill it. The job runs as soon as possible, waits for the
            // upload lock and keeps the process active; if this process dies, it finishes the upload.
            scheduler.schedule(0L)
        }
        val drained = drain(plan)
        var anySuccess = drained.anySuccess
        val failure = drained.failure
        if (plan == SyncPolicy.Scope.ALL && priority > 0 && failure != null &&
            (failure.kind == FailureKind.HTTP || failure.kind == FailureKind.INTERNAL)
        ) {
            // The server is reachable but rejected an older upload: still deliver the audit records behind it.
            // Priority records that just failed on their own are not re-sent in this pass.
            val skip = if (failure.records.all { it.event.isPriority }) failure.result.uuids.toSet() else emptySet()
            anySuccess = drain(SyncPolicy.Scope.PRIORITY_ONLY, skip).anySuccess || anySuccess
        }
        if (anySuccess) reducedBatch = null
        when {
            failure == null -> {
                lastPass = PassResult.SUCCESS
                if (plan == SyncPolicy.Scope.ALL) setNextTry(null)
                cancelJobIfEmpty()
            }
            failure.kind == FailureKind.CHANGED -> {
                // Not a failure of the server, so no wait and no attempt.
                lastPass = PassResult.FAILED
                retryAfterAuthorizationChange(config)
            }
            else -> {
                lastPass = PassResult.FAILED
                storeWait(failure, config, intervalOn)
            }
        }
        return null
    }

    /**
     * After an upload stopped because the app changed `http.authorization`: a new automatic pass [CHANGED_RETRY_MS]
     * later, if the authorization is still set. Caller holds [uploadLock].
     */
    private fun retryAfterAuthorizationChange(config: HttpConfig) {
        if (authorization.current() == null) {
            Logger.i(TAG, "http.authorization was removed during the upload; no new upload is started")
            return
        }
        Logger.i(TAG, "http.authorization changed during the upload; next upload in ${CHANGED_RETRY_MS / MS_PER_SECOND} s")
        changedPause = true
        changedRetry?.cancel()
        changedRetry = scope.launch(dispatchers.io) {
            delay(CHANGED_RETRY_MS)
            changedPause = false
            requestPass()
        }
        // The job replaces the waiting one; a longer stored wait keeps its time, as normal records wait for it anyway.
        scheduler.schedule(maxOf(CHANGED_RETRY_MS, waitRemainingMs(config)))
    }

    /** True if normal records follow `syncInterval` now: configured ([SyncPolicy.usesInterval]) and tracking is on. */
    private fun intervalApplies(config: HttpConfig): Boolean =
        SyncPolicy.usesInterval(config) && configStore.runtime.value.enabled

    // ---- stored wait (NextTry)

    private fun nextTry(): NextTry? {
        if (!nextTryLoaded) {
            nextTryValue = nextTryStore.load()
            nextTryLoaded = true
        }
        return nextTryValue
    }

    private fun setNextTry(value: NextTry?) {
        if (value == null && nextTry() == null) return
        nextTryValue = value
        nextTryLoaded = true
        nextTryStore.save(value)
    }

    /**
     * True while the stored wait runs. An authorization wait ends when the configured tokens are not the rejected ones
     * any more. A wait longer than any wait the rules can produce (the wall clock was set back across a reboot) is
     * shortened to that longest wait.
     */
    private fun isWaiting(config: HttpConfig): Boolean {
        val wait = nextTry() ?: return false
        if (wait.reason == NextTry.Reason.AUTHORIZATION && currentAuthKey() != wait.authKey) {
            Logger.i(TAG, "the authorization changed: automatic uploads resume")
            setNextTry(null)
            return false
        }
        val remaining = wait.remainingMs(clock)
        if (remaining <= 0) return false
        val longest = longestWaitMs(config)
        if (remaining > longest) {
            setNextTry(
                wait.copy(
                    atElapsed = clock.elapsedRealtime() + longest,
                    bootCount = clock.bootCount(),
                    atWall = clock.now() + longest,
                ),
            )
        }
        return true
    }

    /** Remaining ms of the stored wait (0 if none or due). */
    private fun waitRemainingMs(config: HttpConfig): Long =
        if (isWaiting(config)) nextTry()?.remainingMs(clock)?.coerceAtLeast(0L) ?: 0L else 0L

    private fun longestWaitMs(config: HttpConfig): Long = maxOf(
        UploadBackoff.MAX_WAIT_MS + UploadBackoff.MAX_WAIT_MS / 4,
        UploadBackoff.AUTH_WAIT_MS,
        config.maxRetryAfter * MS_PER_SECOND,
        SyncPolicy.intervalMs(config) * 2,
    )

    private fun clearServerWait(why: String) {
        val wait = nextTry() ?: return
        if (wait.reason != NextTry.Reason.SERVER) return
        Logger.d(TAG, "$why: the wait before the next automatic upload ends")
        setNextTry(null)
    }

    /** Stores the wait after the failed automatic upload [failure] and schedules the upload job for its end. */
    private fun storeWait(failure: Failure, config: HttpConfig, intervalOn: Boolean) {
        val previous = nextTry()
        val wait = if (failure.kind == FailureKind.AUTH) {
            NextTry(
                reason = NextTry.Reason.AUTHORIZATION,
                failures = 1,
                atElapsed = clock.elapsedRealtime() + UploadBackoff.AUTH_WAIT_MS,
                bootCount = clock.bootCount(),
                atWall = clock.now() + UploadBackoff.AUTH_WAIT_MS,
                authKey = failure.credentials?.let { UploadBackoff.authKey(it.accessToken, it.refreshToken) }
                    ?: currentAuthKey(),
            )
        } else {
            val failures = (previous?.takeIf { it.reason == NextTry.Reason.SERVER }?.failures ?: 0) + 1
            val status = failure.result.status
            val retryAfter = if (status == HTTP_TOO_MANY_REQUESTS || status == HTTP_SERVICE_UNAVAILABLE) {
                UploadBackoff.retryAfterMs(failure.retryAfter, clock.now(), config.maxRetryAfter)
            } else {
                null
            }
            val minimum = if (intervalOn) SyncPolicy.intervalMs(config) else 0L
            val ms = UploadBackoff.serverWaitMs(failures, random(), retryAfter, minimum)
            NextTry(
                reason = NextTry.Reason.SERVER,
                failures = failures,
                atElapsed = clock.elapsedRealtime() + ms,
                bootCount = clock.bootCount(),
                atWall = clock.now() + ms,
            )
        }
        setNextTry(wait)
        val ms = wait.remainingMs(clock).coerceAtLeast(0L)
        Logger.i(TAG, "next automatic upload of normal records in ${ms / MS_PER_SECOND} s (${wait.reason.wire} wait)")
        scheduler.schedule(ms)
    }

    private fun currentAuthKey(): String? =
        authorization.current()?.let { UploadBackoff.authKey(it.accessToken, it.refreshToken) }

    /** Cancels the upload job when the queue is empty ([queued]: the count, if already known). */
    private suspend fun cancelJobIfEmpty(queued: Int? = null) {
        val count = queued ?: try {
            locationStore.count()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (count == 0) scheduler.cancel()
    }

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
            if (HttpSupport.parseUrl(config.url) == null) {
                cancelIntervalTimer()
                return
            }
            if (!intervalApplies(config)) {
                // Without the interval rule, the end of a stored wait is handled by the upload job (scheduled for it
                // when the upload failed), the next record, connectivity regained or a priority record.
                cancelIntervalTimer()
                return
            }
            val untilRetry = waitRemainingMs(config)
            val retryAt = if (untilRetry > 0) clock.elapsedRealtime() + untilRetry else null
            val lookup = known ?: lookUpOldestNormal()
            val oldest = lookup.record
            if (oldest == null) {
                cancelIntervalTimer()
                return
            }
            val dueAt = SyncPolicy.intervalDueAt(config, oldest.recordedAt, lookup.now)
            if (dueAt == null && retryAt == null) {
                // Due and not waiting for a retry: this pass uploaded it, or could not try (offline, cellular
                // restricted). The next record, connectivity or start() triggers the next attempt.
                cancelIntervalTimer()
                return
            }
            val untilDue = dueAt?.let { it - clock.now() } ?: 0L
            val key = TimerKey(dueAt, if (retryAt != null) nextTryKey() else null)
            armIntervalTimer(key, maxOf(untilDue, untilRetry, 0L), config.syncInterval)
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
            val retry = if (key.retry != null) ", retry after a failed upload" else ""
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

    /** Identifies the stored wait for [TimerKey] (stable while it does not change). */
    private fun nextTryKey(): Long? = nextTry()?.let { it.atWall xor (it.atElapsed shl 1) }

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
            val limit = minOf(SyncPolicy.chunkSize(config), reducedBatch ?: Int.MAX_VALUE)
            // Over-fetch by the skipped count so skipped records cannot hide the rest of the queue.
            val chunk = locationStore.list(limit + skip.size, filter).filter { it.uuid !in attempted }.take(limit)
            if (chunk.isEmpty()) break
            chunk.mapTo(attempted) { it.uuid }
            val failure = upload(url, config, chunk)
            if (failure != null) {
                if (failure.isPayloadTooLarge && chunk.size > 1) {
                    // The same records again at once, in smaller batches.
                    reducedBatch = chunk.size / 2
                    chunk.forEach { attempted.remove(it.uuid) }
                    Logger.w(TAG, "HTTP 413 for ${chunk.size} records: sending batches of $reducedBatch")
                    continue
                }
                return DrainResult(uploaded, failure)
            }
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
            // Read once: every try of this upload uses these credentials or ones refreshed from them.
            var credentials = if (authorization.appliesTo(config)) authorization.current() else null
            var outcome = tryUpload(url, config, records, uuids, credentials, mayRefresh = true)
            credentials = outcome.credentials ?: credentials
            var refreshed = outcome.refreshed
            for (index in UploadBackoff.RETRY_DELAYS_MS.indices) {
                if (!outcome.retryable) break
                val wait = UploadBackoff.retryDelayMs(index, random())
                val retryAfter = UploadBackoff.retryAfterMs(outcome.retryAfter, clock.now(), config.maxRetryAfter)
                if (retryAfter != null && retryAfter > wait) {
                    Logger.w(TAG, "upload of ${uuids.size} record(s) failed (${outcome.reason}); Retry-After asks for ${retryAfter / MS_PER_SECOND} s")
                    break
                }
                if (!outcome.reported) events.emit(TrackingEvent.Http(outcome.result))
                Logger.w(TAG, "upload of ${uuids.size} record(s) failed (${outcome.reason}); next try in ${wait / MS_PER_SECOND} s")
                delay(wait)
                // At most one token refresh per upload, whether before a request or after a 401.
                outcome = tryUpload(url, config, records, uuids, credentials, mayRefresh = !refreshed)
                credentials = outcome.credentials ?: credentials
                refreshed = refreshed || outcome.refreshed
            }
            val tooLarge = outcome.result.status == HTTP_PAYLOAD_TOO_LARGE && records.size > 1
            when {
                outcome.kind == null -> {
                    locationStore.delete(uuids)
                    Logger.i(TAG, "uploaded ${uuids.size} record(s): HTTP ${outcome.result.status}")
                }
                outcome.kind == FailureKind.CHANGED ->
                    Logger.w(TAG, "upload of ${uuids.size} record(s) stopped: http.authorization changed; kept in the queue")
                tooLarge -> Unit // sent again at once in smaller batches
                else -> {
                    locationStore.markAttempt(uuids, clock.now())
                    Logger.w(TAG, "upload of ${uuids.size} record(s) failed (${outcome.reason}); kept in the queue")
                }
            }
            if (!outcome.reported && outcome.sent) events.emit(TrackingEvent.Http(outcome.result))
            outcome.kind?.let { Failure(records, outcome.result, it, outcome.retryAfter, credentials) }
        }

    /**
     * One try of an upload with [credentials] (null: no bearer token): pre-emptive token refresh, request, and on a 401
     * a token refresh and one more request; no refresh at all unless [mayRefresh]. Emits the Http event of every request
     * but the last one (see [Try.reported]). Stops before sending when the configured tokens are no longer
     * [credentials] ([FailureKind.CHANGED]), and does not send when the stored tokens cannot be read
     * ([FailureKind.AUTH]).
     */
    private suspend fun tryUpload(
        url: HttpUrl,
        config: HttpConfig,
        records: List<Record>,
        uuids: List<String>,
        credentials: AuthorizationManager.Credentials?,
        mayRefresh: Boolean,
    ): Try {
        var reported = false
        var refreshed = false
        var used = credentials
        return try {
            val client = clientFor(config.timeout)
            if (used != null) {
                if (configStore.secretsUnavailable) {
                    val error = "the stored tokens could not be decrypted; set http.authorization again"
                    Logger.e(TAG, "$error; nothing uploaded")
                    authorization.reportUnusable(error)
                    return Try(HttpResult(false, 0, error, uuids), FailureKind.AUTH, false, false, used, sent = false)
                }
                val prepared = authorization.prepare(client, used, mayRefresh)
                refreshed = prepared.refreshAttempted
                used = prepared.credentials
                if (prepared.outcome == AuthorizationManager.Outcome.CHANGED) {
                    return Try(HttpResult(false, 0, AUTH_CHANGED, uuids), FailureKind.CHANGED, false, refreshed, used, sent = false)
                }
            }
            var response = send(client, url, config, records, used?.accessToken)
            var kind: FailureKind? = if (response.isSuccessful) null else FailureKind.HTTP
            if (response.status == HTTP_UNAUTHORIZED && used != null) {
                kind = FailureKind.AUTH
                if (mayRefresh && !refreshed) {
                    events.emit(TrackingEvent.Http(HttpResult(false, response.status, response.body, uuids)))
                    reported = true
                    refreshed = true
                    val renewed = authorization.renew(client, used)
                    when (renewed.outcome) {
                        AuthorizationManager.Outcome.RENEWED -> {
                            used = renewed.credentials
                            reported = false // the retry is a new request with its own event, even if it throws
                            response = send(client, url, config, records, used.accessToken)
                            kind = when {
                                response.isSuccessful -> null
                                response.status == HTTP_UNAUTHORIZED -> FailureKind.AUTH
                                else -> FailureKind.HTTP
                            }
                        }
                        AuthorizationManager.Outcome.CHANGED -> kind = FailureKind.CHANGED
                        else -> Unit // FAILED: the 401 stands, an authorization failure
                    }
                }
            }
            val result = HttpResult(response.isSuccessful, response.status, response.body, uuids)
            Try(result, kind, reported, refreshed, used, retryAfter = response.retryAfter)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // a call cancelled with the coroutine is not an upload failure
            Try(HttpResult(false, 0, e.message ?: e.javaClass.simpleName, uuids), FailureKind.NETWORK, reported, refreshed, used)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "upload of ${uuids.size} record(s) failed unexpectedly", e)
            Try(HttpResult(false, 0, e.message ?: e.javaClass.simpleName, uuids), FailureKind.INTERNAL, reported, refreshed, used)
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
        val request = HttpSupport.request(url, config.method.wire, headers, body)
        // The client does not repeat a request after a connection failure (HttpSupport.sharedClient), so a pooled
        // connection the server has closed would fail the try. OkHttp checks a pooled connection only after 10 s idle,
        // on a clock that stops in deep sleep; many servers close idle connections after 5 s (Node.js, Apache).
        val idleSince = lastExchangeEnd
        if (idleSince != null && clock.elapsedRealtime() - idleSince > POOLED_CONNECTION_MAX_IDLE_MS) {
            client.connectionPool.evictAll()
        }
        try {
            return HttpSupport.execute(client, request, dispatchers.io, HttpSupport.MAX_UPLOAD_RESPONSE_BYTES)
        } finally {
            lastExchangeEnd = clock.elapsedRealtime()
        }
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

        /** HTTP 401 and no refreshed token was accepted, or the stored tokens cannot be read. */
        AUTH,

        /** The configured tokens changed during the upload: stopped, records kept, no attempt counted. */
        CHANGED,
    }

    /** What the last automatic pass did. */
    private enum class PassResult { NOTHING, SUCCESS, FAILED }

    /**
     * The outcome of one [tryUpload]: [kind] null on success. [reported] means the Http event for [result] was
     * already emitted (a 401 whose token refresh failed); [refreshed] means the try attempted a token refresh;
     * [credentials] are the ones the try ended with; [sent] is false when no request was made.
     */
    private class Try(
        val result: HttpResult,
        val kind: FailureKind?,
        val reported: Boolean,
        val refreshed: Boolean,
        val credentials: AuthorizationManager.Credentials?,
        val retryAfter: String? = null,
        val sent: Boolean = true,
    ) {
        /** True for a request without an answer, HTTP 5xx or HTTP 429 (R2-Q18). */
        val retryable: Boolean
            get() = kind == FailureKind.NETWORK ||
                (kind == FailureKind.HTTP && (result.status in HTTP_SERVER_ERRORS || result.status == HTTP_TOO_MANY_REQUESTS))

        val reason: String
            get() = if (kind == FailureKind.HTTP || kind == FailureKind.AUTH && result.status > 0) {
                "HTTP ${result.status}"
            } else {
                result.responseText
            }
    }

    /** A failed upload of [records]; [credentials] are the ones it was sent with (for the authorization wait). */
    private class Failure(
        val records: List<Record>,
        val result: HttpResult,
        val kind: FailureKind,
        val retryAfter: String?,
        val credentials: AuthorizationManager.Credentials?,
    ) {
        val isPayloadTooLarge: Boolean get() = kind == FailureKind.HTTP && result.status == HTTP_PAYLOAD_TOO_LARGE
    }

    private class DrainResult(val uploaded: List<Record>, val failure: Failure?) {
        val anySuccess: Boolean get() = uploaded.isNotEmpty()
    }

    /** The oldest pending normal record ([record], null if none) as looked up at wall time [now]. */
    private class OldestNormal(val record: Record?, val now: Long)

    /**
     * What an armed timer waits for: [dueAt] (wall ms) when the oldest pending normal record becomes due, null if it is
     * due already or the `syncInterval` rule does not apply; [retry] identifies the stored wait after a failed upload.
     */
    private data class TimerKey(val dueAt: Long?, val retry: Long?)

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

    internal companion object {
        private const val TAG = "LT.Http"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_PAYLOAD_TOO_LARGE = 413
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVICE_UNAVAILABLE = 503
        val HTTP_SERVER_ERRORS = 500..599
        const val MS_PER_SECOND = 1_000L

        /** Passes one upload job runs at most while they succeed and leave records due. */
        const val MAX_JOB_PASSES = 5

        const val AUTH_CHANGED = "http.authorization changed during the upload"

        /** Pooled connections idle longer than this are closed before an upload request (see [send]). */
        const val POOLED_CONNECTION_MAX_IDLE_MS = 4_000L

        /** The wait before the pass that follows an upload stopped for new tokens (rule 2a). */
        const val CHANGED_RETRY_MS = 10_000L

        /** An unrestricted network, to ask the policy what is due whatever the network is. */
        val ONLINE = Connectivity(connected = true, type = ConnectivityType.WIFI)

        /** Start of the debug log line written when the `syncInterval` timer is armed (tests look for it). */
        const val TIMER_ARMED = "syncInterval check armed"
    }
}
