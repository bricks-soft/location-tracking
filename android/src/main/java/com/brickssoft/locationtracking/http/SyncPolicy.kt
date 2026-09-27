package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.ConnectivityType
import com.brickssoft.locationtracking.model.RecordEvent

/**
 * Decides what an automatic upload pass sends (architecture §2, "Priority records"). Manual `sync()` bypasses it.
 *
 * - No `http.url`, or no connectivity: nothing.
 * - A queued priority record (heartbeat, tracking_start, tracking_stop, providerchange) forces an upload, ignoring
 *   `autoSync` and `autoSyncThreshold`: only priority records on cellular with `disableAutoSyncOnCellular`,
 *   otherwise the whole queue.
 * - Otherwise the whole queue if `autoSync`, not restricted by cellular, and the queue holds at least
 *   `autoSyncThreshold` records (0 = every record).
 */
internal object SyncPolicy {
    /** The records an upload pass sends. */
    enum class Scope {
        NONE,
        ALL,
        PRIORITY_ONLY,
    }

    val PRIORITY_EVENTS: Set<RecordEvent> = RecordEvent.entries.filter { it.isPriority }.toSet()

    fun hasUrl(http: HttpConfig): Boolean = !http.url.isNullOrBlank()

    /** True if [connectivity] is cellular and `disableAutoSyncOnCellular` is set. */
    fun isCellularRestricted(http: HttpConfig, connectivity: Connectivity): Boolean =
        http.disableAutoSyncOnCellular && connectivity.type == ConnectivityType.CELLULAR

    /**
     * @param queued number of queued records.
     * @param priorityQueued number of queued priority records.
     */
    fun autoScope(http: HttpConfig, connectivity: Connectivity, queued: Int, priorityQueued: Int): Scope {
        if (!hasUrl(http) || !connectivity.connected) return Scope.NONE
        val restricted = isCellularRestricted(http, connectivity)
        if (priorityQueued > 0) return if (restricted) Scope.PRIORITY_ONLY else Scope.ALL
        if (restricted || !http.autoSync) return Scope.NONE
        return if (queued >= maxOf(1, http.autoSyncThreshold)) Scope.ALL else Scope.NONE
    }

    /** Records per request: `maxBatchSize` (at least 1) when `batchSync`, else 1. */
    fun chunkSize(http: HttpConfig): Int = if (http.batchSync) maxOf(1, http.maxBatchSize) else 1
}
