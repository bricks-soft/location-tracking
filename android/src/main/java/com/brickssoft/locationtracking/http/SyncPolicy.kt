package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.model.Connectivity
import com.brickssoft.locationtracking.model.ConnectivityType
import com.brickssoft.locationtracking.model.RecordEvent

/**
 * Decides what an automatic upload pass sends (architecture §2, "Priority records"; round 2 §4, `http.syncInterval`).
 * Manual `sync()` bypasses it.
 *
 * - No `http.url`, or no connectivity: nothing.
 * - A queued priority record (heartbeat, tracking_start, tracking_stop, providerchange) forces an upload, ignoring
 *   `autoSync`, `autoSyncThreshold` and `syncInterval`: only priority records on cellular with
 *   `disableAutoSyncOnCellular`, otherwise the whole queue.
 * - Otherwise nothing if `autoSync` is off or cellular is restricted. Then, by [IntervalCheck]:
 *   - [IntervalCheck.OFF] (`syncInterval = 0`, or tracking off): the whole queue once it holds at least
 *     `autoSyncThreshold` records (0 = every record).
 *   - `syncInterval > 0` while tracking: the whole queue once the oldest pending normal record is at least
 *     `syncInterval` seconds old (age = now − `recorded_at`; a negative age, which a wall clock set back produces,
 *     counts as due), or once the queue holds `autoSyncThreshold` records when that is above 0 (a size cap that
 *     uploads earlier); nothing while a retry after a failed upload is pending.
 */
internal object SyncPolicy {
    /** The records an upload pass sends. */
    enum class Scope {
        NONE,
        ALL,
        PRIORITY_ONLY,
    }

    /** The state of the `syncInterval` rule for one pass (decided by the syncer). */
    enum class IntervalCheck {
        /** The rule does not apply (`syncInterval = 0`, `autoSync` off, or tracking off): threshold rule. */
        OFF,

        /** The oldest pending normal record is younger than `syncInterval`: only the size cap uploads. */
        NOT_DUE,

        /** The oldest pending normal record is due ([isIntervalDue]). */
        DUE,

        /** An automatic upload failed less than `syncInterval` ago: normal records wait for the retry time. */
        RETRY_PENDING,
    }

    val PRIORITY_EVENTS: Set<RecordEvent> = RecordEvent.entries.filter { it.isPriority }.toSet()

    /** The events that `autoSync`, `autoSyncThreshold` and `syncInterval` apply to. */
    val NORMAL_EVENTS: Set<RecordEvent> = RecordEvent.entries.filterNot { it.isPriority }.toSet()

    private const val MS_PER_SECOND = 1_000L

    fun hasUrl(http: HttpConfig): Boolean = !http.url.isNullOrBlank()

    /** True if [connectivity] is cellular and `disableAutoSyncOnCellular` is set. */
    fun isCellularRestricted(http: HttpConfig, connectivity: Connectivity): Boolean =
        http.disableAutoSyncOnCellular && connectivity.type == ConnectivityType.CELLULAR

    /** True if normal records follow `syncInterval` (it is above 0 and `autoSync` is on). */
    fun usesInterval(http: HttpConfig): Boolean = http.autoSync && http.syncInterval > 0

    /** `syncInterval` in milliseconds (the validator keeps it at 0 or above). */
    fun intervalMs(http: HttpConfig): Long = http.syncInterval * MS_PER_SECOND

    /**
     * True if the oldest pending normal record, created at [oldestRecordedAt], is due for upload at [now]: its age
     * is at least `syncInterval`, or negative (the wall clock was set back after it was created).
     */
    fun isIntervalDue(http: HttpConfig, oldestRecordedAt: Long, now: Long): Boolean {
        val age = now - oldestRecordedAt
        return age < 0 || age >= intervalMs(http)
    }

    /**
     * The wall time (epoch ms) at which the oldest pending normal record, created at [oldestRecordedAt], becomes due:
     * `oldestRecordedAt + syncInterval`; null if it is already due at [now] (see [isIntervalDue]).
     */
    fun intervalDueAt(http: HttpConfig, oldestRecordedAt: Long, now: Long): Long? =
        if (isIntervalDue(http, oldestRecordedAt, now)) null else oldestRecordedAt + intervalMs(http)

    /**
     * @param queued number of queued records.
     * @param priorityQueued number of queued priority records.
     * @param interval the state of the `syncInterval` rule; [IntervalCheck.OFF] applies the threshold rule.
     */
    fun autoScope(
        http: HttpConfig,
        connectivity: Connectivity,
        queued: Int,
        priorityQueued: Int,
        interval: IntervalCheck = IntervalCheck.OFF,
    ): Scope {
        if (!hasUrl(http) || !connectivity.connected) return Scope.NONE
        val restricted = isCellularRestricted(http, connectivity)
        if (priorityQueued > 0) return if (restricted) Scope.PRIORITY_ONLY else Scope.ALL
        if (restricted || !http.autoSync || queued <= 0) return Scope.NONE
        val upload = when (interval) {
            IntervalCheck.OFF -> queued >= maxOf(1, http.autoSyncThreshold)
            IntervalCheck.NOT_DUE -> http.autoSyncThreshold > 0 && queued >= http.autoSyncThreshold
            IntervalCheck.DUE -> true
            IntervalCheck.RETRY_PENDING -> false
        }
        return if (upload) Scope.ALL else Scope.NONE
    }

    /** Records per request: `maxBatchSize` (at least 1) when `batchSync`, else 1. */
    fun chunkSize(http: HttpConfig): Int = if (http.batchSync) maxOf(1, http.maxBatchSize) else 1
}
