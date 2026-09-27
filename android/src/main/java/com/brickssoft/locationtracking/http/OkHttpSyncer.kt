package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.EventBus
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
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
 * [HttpSyncer] over OkHttp (architecture §2).
 *
 * - Automatic passes run on [scope] / `dispatchers.io`, triggered by [onRecordInserted], by
 *   `ConnectivityChange(connected = true)` once [start]ed, and by [start] itself. [SyncPolicy] decides what they
 *   send. Triggers are coalesced: any number of triggers during a pass schedule exactly one more pass.
 * - Uploads are single-flight: automatic passes and [sync] share one mutex, so a record is never in two requests
 *   at once.
 * - The queue drains oldest first, `maxBatchSize` records per request when `batchSync`, else one per request, and
 *   stops at the first failure. If the server rejected an upload, an automatic pass still sends the queued priority
 *   records (heartbeat and audit records), so one record the server keeps rejecting cannot hold them back.
 * - 2xx deletes the records; anything else keeps them and marks one attempt per upload. One `Http` event is
 *   emitted per HTTP request (a 401 that triggers a token refresh and a retry yields two).
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
        val result = uploadLock.withLock { drain(SyncPolicy.Scope.ALL) }
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
                        Logger.e(TAG, "automatic upload failed", e)
                    }
                }
            }
        }
        triggers.trySend(Unit)
    }

    private suspend fun autoPass() {
        val hint = connectivityHint.getAndSet(null)
        val config = configStore.config.value.http
        if (!SyncPolicy.hasUrl(config)) return
        if (HttpSupport.parseUrl(config.url) == null) {
            Logger.e(TAG, "http.url is not a valid http(s) URL; nothing uploaded")
            return
        }
        val connectivity = currentConnectivity(hint)
        if (!connectivity.connected) {
            Logger.d(TAG, "offline; upload deferred until connectivity returns")
            return
        }
        val priority = locationStore.count(SyncPolicy.PRIORITY_EVENTS)
        val queued = locationStore.count()
        val plan = SyncPolicy.autoScope(config, connectivity, queued, priority)
        if (plan == SyncPolicy.Scope.NONE) return
        val failure = drain(plan).failure
        if (plan == SyncPolicy.Scope.ALL && priority > 0 && failure != null && failure.kind != FailureKind.NETWORK) {
            // The server is reachable but rejected an older upload: still deliver the audit records behind it.
            // Priority records that just failed on their own are not re-sent in this pass.
            val skip = if (failure.records.all { it.event.isPriority }) failure.result.uuids.toSet() else emptySet()
            drain(SyncPolicy.Scope.PRIORITY_ONLY, skip)
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
     * One upload of [records]: pre-emptive token refresh, request, 401 refresh-and-retry, store update and one Http
     * event per request. Returns null on success.
     */
    private suspend fun upload(url: HttpUrl, config: HttpConfig, records: List<Record>): Failure? =
        withContext(dispatchers.io) {
            val uuids = records.map { it.uuid }
            var reported = false
            val (result, kind) = try {
                val client = clientFor(config.timeout)
                val bearer = authorization.appliesTo(config)
                val token = if (bearer) authorization.tokenForRequest(client) else NO_TOKEN
                var response = send(client, url, config, records, token.value)
                if (response.status == HTTP_UNAUTHORIZED && bearer && !token.refreshAttempted) {
                    events.emit(TrackingEvent.Http(HttpResult(false, response.status, response.body, uuids)))
                    reported = true
                    val renewed = authorization.refresh(client, token.value)
                    if (renewed != null) {
                        reported = false // the retry is a new request with its own event, even if it throws
                        response = send(client, url, config, records, renewed)
                    }
                }
                val result = HttpResult(response.isSuccessful, response.status, response.body, uuids)
                result to (if (result.success) null else FailureKind.HTTP)
            } catch (e: IOException) {
                ensureActive() // a call cancelled because the coroutine was cancelled is not an upload failure
                HttpResult(false, 0, e.message ?: e.javaClass.simpleName, uuids) to FailureKind.NETWORK
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "upload of ${uuids.size} record(s) failed unexpectedly", e)
                HttpResult(false, 0, e.message ?: e.javaClass.simpleName, uuids) to FailureKind.INTERNAL
            }
            if (kind == null) {
                locationStore.delete(uuids)
                Logger.i(TAG, "uploaded ${uuids.size} record(s): HTTP ${result.status}")
            } else {
                locationStore.markAttempt(uuids, clock.now())
                val reason = if (kind == FailureKind.HTTP) "HTTP ${result.status}" else result.responseText
                Logger.w(TAG, "upload of ${uuids.size} record(s) failed ($reason); kept in the queue")
            }
            if (!reported) events.emit(TrackingEvent.Http(result))
            kind?.let { Failure(records, result, it) }
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

    /** A failed upload of [records]. */
    private class Failure(val records: List<Record>, val result: HttpResult, val kind: FailureKind)

    private class DrainResult(val uploaded: List<Record>, val failure: Failure?)

    private companion object {
        const val TAG = "LT.Http"
        const val HTTP_UNAUTHORIZED = 401
        val NO_TOKEN = AuthorizationManager.Token(null, refreshAttempted = false)
    }
}
