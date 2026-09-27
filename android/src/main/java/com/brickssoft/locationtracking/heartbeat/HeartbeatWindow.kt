package com.brickssoft.locationtracking.heartbeat

import com.brickssoft.locationtracking.model.HeartbeatStrategy

/**
 * One heartbeat window: when the next heartbeat is due and how it is scheduled (architecture §3).
 *
 * Pure Kotlin, no Android types. Every `*Elapsed` value is in the current boot's elapsed-realtime timebase
 * (ms), which is what `AlarmManager.ELAPSED_REALTIME_WAKEUP` uses.
 *
 * @property baseElapsed start of the window: the last record (or the no-record anchor), never before the session start.
 * @property dueElapsed `base + minInterval` (never before the last attempt + minInterval): when the heartbeat is due.
 * @property deadlineElapsed `base + maxInterval`: the heartbeat should exist by then.
 * @property backupAtElapsed trigger of the allow-while-idle backup alarm (`due`, or paced 9 min apart while idle).
 * @property strategy how the window is armed; [HeartbeatStrategy.DISABLED] if heartbeat or tracking is off.
 * @property nextHeartbeatAt wall-clock estimate of the next heartbeat (epoch ms), or null when disabled.
 * @property minIntervalMs the effective min interval (floored at [MIN_INTERVAL_FLOOR_S]).
 */
internal data class HeartbeatWindow(
    val baseElapsed: Long,
    val dueElapsed: Long,
    val deadlineElapsed: Long,
    val backupAtElapsed: Long,
    val strategy: HeartbeatStrategy,
    val nextHeartbeatAt: Long?,
    val minIntervalMs: Long,
) {
    /**
     * Elapsed time at which the heartbeat is expected: the backup alarm while idle-paced (the listener alarm is
     * deferred in deep idle), otherwise [dueElapsed]. This is the heartbeat metadata's `next_at` and
     * `getHeartbeatStatus().nextHeartbeatAt`, converted to wall-clock time.
     */
    val expectedFireElapsed: Long
        get() = if (strategy == HeartbeatStrategy.IDLE_PACED) backupAtElapsed else dueElapsed

    /**
     * True if a heartbeat must be created now: `now - base >= minInterval - 1 s` (and the previous attempt is at
     * least `minInterval - 1 s` ago).
     */
    fun shouldFire(nowElapsed: Long): Boolean = nowElapsed >= dueElapsed - FIRE_TOLERANCE_MS

    /**
     * True if the alarms armed for [armed] must be set again for this window (null [armed]: nothing is armed).
     *
     * Every record moves the window later, and a moving phone records every few seconds. Setting the alarms for each
     * record costs AlarmManager calls, so a due time that moved later by less than [REARM_THRESHOLD_MS] keeps the
     * armed alarms. Such an alarm fires up to [REARM_THRESHOLD_MS] before the real due time; the alarm handler then
     * finds the window not due, creates no heartbeat and arms the real due time.
     *
     * The alarms are always set again when:
     * - the strategy differs;
     * - a trigger time moved earlier (the armed alarm would fire after the heartbeat is due);
     * - the strategy is [HeartbeatStrategy.IDLE_PACED] and the backup time moved at all. An early backup alarm uses
     *   one of the few allow-while-idle alarms Android grants per hour and would move the next backup about
     *   9 minutes later ([IDLE_BACKUP_SPACING_MS]). Records are rare in deep idle, so this costs little.
     */
    fun needsRearm(armed: HeartbeatWindow?): Boolean {
        if (armed == null || armed.strategy != strategy) return true
        val dueMove = dueElapsed - armed.dueElapsed
        val backupMove = backupAtElapsed - armed.backupAtElapsed
        if (dueMove < 0L || backupMove < 0L) return true
        if (strategy == HeartbeatStrategy.IDLE_PACED && backupMove != 0L) return true
        // Outside idle pacing the backup time equals the due time, so the due time decides.
        return dueMove >= REARM_THRESHOLD_MS
    }

    companion object {
        /** Tolerance of [shouldFire], so an alarm delivered a little early still counts. */
        const val FIRE_TOLERANCE_MS = 1_000L

        /**
         * A due time that moved later by less than this keeps the armed alarms (see [needsRearm]). With a record every
         * 5 s, the alarms are set once per 30 s of records instead of once per record.
         */
        const val REARM_THRESHOLD_MS = 30_000L

        /** Minimum spacing of allow-while-idle backup alarms while idle and not battery-exempt. */
        const val IDLE_BACKUP_SPACING_MS = 9 * 60_000L

        /** Floor of `heartbeat.minInterval` (the documented minimum). */
        const val MIN_INTERVAL_FLOOR_S = 60

        /**
         * Computes the window.
         *
         * Base: the last record's elapsed time if it was recorded in this boot; otherwise it is derived from the
         * wall clock (`nowElapsed - (now - lastRecordAt)`); if there is no record, [noRecordBaseElapsed] (or
         * [nowElapsed]). A last record older than [trackingStartedAt] belongs to an earlier tracking session, so
         * the base is then the session start instead (otherwise every `start()` after a pause would find the window
         * overdue). The base never lies in the future. When the boot count is unknown (-1) on either side, the
         * record counts as "this boot" as long as its elapsed time is not ahead of [nowElapsed].
         *
         * @param lastRecordAt wall time of the last record (epoch ms), or null if there is none.
         * @param lastRecordElapsed elapsed time of the last record.
         * @param lastRecordBootCount boot count of the last record.
         * @param minIntervalSec `heartbeat.minInterval` (floored at [MIN_INTERVAL_FLOOR_S]).
         * @param maxIntervalSec `heartbeat.maxInterval` (at least the min interval).
         * @param isIdle device is in deep idle (Doze).
         * @param canExact `canScheduleExactAlarms()`: battery-exempt, alarms are not rate-limited.
         * @param lastBackupFireElapsed when a backup alarm last fired in this boot, or null.
         * @param enabled tracking and heartbeat are both enabled; false yields [HeartbeatStrategy.DISABLED].
         * @param noRecordBaseElapsed base to use when there is no record (a stable anchor), default [nowElapsed].
         * @param lastAttemptElapsed elapsed time of the last heartbeat attempt: the next one is due no earlier than
         *   one min interval later, even if that attempt failed to produce a record.
         * @param trackingStartedAt wall time the current tracking session started (`runtime.trackingStartedAt`).
         */
        fun compute(
            lastRecordAt: Long?,
            lastRecordElapsed: Long?,
            lastRecordBootCount: Int?,
            now: Long,
            nowElapsed: Long,
            bootCount: Int,
            minIntervalSec: Int,
            maxIntervalSec: Int,
            isIdle: Boolean,
            canExact: Boolean,
            lastBackupFireElapsed: Long?,
            enabled: Boolean = true,
            noRecordBaseElapsed: Long? = null,
            lastAttemptElapsed: Long? = null,
            trackingStartedAt: Long? = null,
        ): HeartbeatWindow {
            val minMs = minIntervalSec.coerceAtLeast(MIN_INTERVAL_FLOOR_S) * 1_000L
            val maxMs = (maxIntervalSec * 1_000L).coerceAtLeast(minMs)

            val base = when {
                lastRecordAt == null -> noRecordBaseElapsed ?: nowElapsed
                lastRecordElapsed != null && sameBoot(lastRecordElapsed, lastRecordBootCount, nowElapsed, bootCount) ->
                    lastRecordElapsed
                else -> nowElapsed - (now - lastRecordAt)
            }.coerceAtLeast(sessionStartElapsed(lastRecordAt, trackingStartedAt, now, nowElapsed) ?: Long.MIN_VALUE)
                .coerceAtMost(nowElapsed)
            val due = maxOf(base + minMs, lastAttemptElapsed?.plus(minMs) ?: Long.MIN_VALUE)

            // A backup fire from a previous boot (ahead of now) says nothing about the current idle quota.
            val lastBackup = lastBackupFireElapsed?.takeIf { it <= nowElapsed }
            val backupAt = if (isIdle && !canExact && lastBackup != null) {
                maxOf(due, lastBackup + IDLE_BACKUP_SPACING_MS)
            } else {
                due
            }

            val strategy = when {
                !enabled -> HeartbeatStrategy.DISABLED
                canExact -> HeartbeatStrategy.EXACT
                isIdle -> HeartbeatStrategy.IDLE_PACED
                else -> HeartbeatStrategy.LISTENER_WITH_BACKUP
            }
            val window = HeartbeatWindow(
                baseElapsed = base,
                dueElapsed = due,
                deadlineElapsed = base + maxMs,
                backupAtElapsed = backupAt,
                strategy = strategy,
                nextHeartbeatAt = null,
                minIntervalMs = minMs,
            )
            if (strategy == HeartbeatStrategy.DISABLED) return window
            // An overdue window fires as soon as it is armed.
            val next = now + (maxOf(window.expectedFireElapsed, nowElapsed) - nowElapsed)
            return window.copy(nextHeartbeatAt = next)
        }

        /** Elapsed time of the session start, if the last record predates it (and the start is not in the future). */
        private fun sessionStartElapsed(lastRecordAt: Long?, trackingStartedAt: Long?, now: Long, nowElapsed: Long): Long? {
            if (lastRecordAt == null || trackingStartedAt == null) return null
            if (lastRecordAt >= trackingStartedAt || trackingStartedAt > now) return null
            return nowElapsed - (now - trackingStartedAt)
        }

        private fun sameBoot(recordElapsed: Long, recordBootCount: Int?, nowElapsed: Long, bootCount: Int): Boolean =
            if (recordBootCount != null && recordBootCount >= 0 && bootCount >= 0) {
                recordBootCount == bootCount
            } else {
                recordElapsed <= nowElapsed
            }
    }
}
