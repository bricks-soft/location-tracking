package com.brickssoft.locationtracking.heartbeat

import com.brickssoft.locationtracking.model.HeartbeatStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure window math (plain JVM). */
class HeartbeatWindowTest {
    private val now = 1_790_417_730_456L
    private val nowElapsed = 86_400_123L
    private val boot = 42

    private fun window(
        lastRecordAt: Long? = now - 100_000L,
        lastRecordElapsed: Long? = nowElapsed - 100_000L,
        lastRecordBootCount: Int? = boot,
        bootCount: Int = boot,
        minIntervalSec: Int = 180,
        maxIntervalSec: Int = 300,
        isIdle: Boolean = false,
        canExact: Boolean = false,
        lastBackupFireElapsed: Long? = null,
        enabled: Boolean = true,
        noRecordBaseElapsed: Long? = null,
        lastAttemptElapsed: Long? = null,
        trackingStartedAt: Long? = null,
        nowWall: Long = now,
        nowEl: Long = nowElapsed,
    ) = HeartbeatWindow.compute(
        lastRecordAt = lastRecordAt,
        lastRecordElapsed = lastRecordElapsed,
        lastRecordBootCount = lastRecordBootCount,
        now = nowWall,
        nowElapsed = nowEl,
        bootCount = bootCount,
        minIntervalSec = minIntervalSec,
        maxIntervalSec = maxIntervalSec,
        isIdle = isIdle,
        canExact = canExact,
        lastBackupFireElapsed = lastBackupFireElapsed,
        enabled = enabled,
        noRecordBaseElapsed = noRecordBaseElapsed,
        lastAttemptElapsed = lastAttemptElapsed,
        trackingStartedAt = trackingStartedAt,
    )

    @Test
    fun `same boot uses the elapsed clock of the last record`() {
        // The wall clock was moved by an hour; the elapsed clock is authoritative within a boot.
        val w = window(lastRecordAt = now - 3_600_000L, lastRecordElapsed = nowElapsed - 100_000L)

        assertEquals(nowElapsed - 100_000L, w.baseElapsed)
        assertEquals(nowElapsed + 80_000L, w.dueElapsed)
        assertEquals(nowElapsed + 200_000L, w.deadlineElapsed)
        assertEquals(now + 80_000L, w.nextHeartbeatAt)
    }

    @Test
    fun `boot count change derives the base from the wall clock`() {
        // Recorded in boot 41 at elapsed 5_000_000 (meaningless now); 100 s ago by the wall clock.
        val w = window(lastRecordAt = now - 100_000L, lastRecordElapsed = 5_000_000L, lastRecordBootCount = 41)

        assertEquals(nowElapsed - 100_000L, w.baseElapsed)
        assertEquals(nowElapsed + 80_000L, w.dueElapsed)
    }

    @Test
    fun `record from a previous boot long ago is overdue and fires as soon as it is armed`() {
        val w = window(lastRecordAt = now - 86_400_000L, lastRecordElapsed = 1_000L, lastRecordBootCount = 41)

        assertTrue(w.dueElapsed < nowElapsed)
        assertTrue(w.shouldFire(nowElapsed))
        assertEquals(now, w.nextHeartbeatAt)
    }

    @Test
    fun `unknown boot count uses the elapsed clock unless it is ahead of now`() {
        val same = window(lastRecordBootCount = -1, bootCount = -1)
        assertEquals(nowElapsed - 100_000L, same.baseElapsed)

        val rebooted = window(
            lastRecordAt = now - 50_000L,
            lastRecordElapsed = nowElapsed + 1_000_000L,
            lastRecordBootCount = -1,
            bootCount = -1,
        )
        assertEquals(nowElapsed - 50_000L, rebooted.baseElapsed)
    }

    @Test
    fun `missing elapsed time falls back to the wall clock`() {
        val w = window(lastRecordAt = now - 30_000L, lastRecordElapsed = null)

        assertEquals(nowElapsed - 30_000L, w.baseElapsed)
    }

    @Test
    fun `no record uses now, or the given anchor`() {
        val w = window(lastRecordAt = null, lastRecordElapsed = null, lastRecordBootCount = null)
        assertEquals(nowElapsed, w.baseElapsed)
        assertEquals(nowElapsed + 180_000L, w.dueElapsed)
        assertFalse(w.shouldFire(nowElapsed))

        val anchored = window(
            lastRecordAt = null,
            lastRecordElapsed = null,
            lastRecordBootCount = null,
            noRecordBaseElapsed = nowElapsed - 180_000L,
        )
        assertTrue(anchored.shouldFire(nowElapsed))
    }

    @Test
    fun `a record in the future is clamped to now`() {
        val w = window(lastRecordAt = now + 60_000L, lastRecordElapsed = nowElapsed + 60_000L)

        assertEquals(nowElapsed, w.baseElapsed)
    }

    @Test
    fun `shouldFire tolerates one second early`() {
        val w = window(lastRecordElapsed = nowElapsed, lastRecordAt = now)

        assertFalse(w.shouldFire(nowElapsed + 178_999L))
        assertTrue(w.shouldFire(nowElapsed + 179_000L))
        assertTrue(w.shouldFire(nowElapsed + 180_000L))
        assertTrue(w.shouldFire(nowElapsed + 900_000L))
    }

    @Test
    fun `the next heartbeat is not due before one interval after the last attempt`() {
        // Base is overdue (record 400 s ago) but a heartbeat was just attempted (and failed to produce a record).
        val w = window(lastRecordAt = now - 400_000L, lastRecordElapsed = nowElapsed - 400_000L, lastAttemptElapsed = nowElapsed)

        assertEquals(nowElapsed + 180_000L, w.dueElapsed)
        assertFalse(w.shouldFire(nowElapsed + 10_000L))
        assertTrue(w.shouldFire(nowElapsed + 180_000L))
    }

    @Test
    fun `strategies`() {
        assertEquals(HeartbeatStrategy.LISTENER_WITH_BACKUP, window().strategy)
        assertEquals(HeartbeatStrategy.IDLE_PACED, window(isIdle = true).strategy)
        assertEquals(HeartbeatStrategy.EXACT, window(canExact = true).strategy)
        assertEquals(HeartbeatStrategy.EXACT, window(canExact = true, isIdle = true).strategy)
        val disabled = window(enabled = false, canExact = true)
        assertEquals(HeartbeatStrategy.DISABLED, disabled.strategy)
        assertNull(disabled.nextHeartbeatAt)
    }

    @Test
    fun `backup equals due unless idle and not exact-capable`() {
        val lastBackup = nowElapsed - 60_000L

        assertEquals(window().dueElapsed, window(lastBackupFireElapsed = lastBackup).backupAtElapsed)
        assertEquals(
            window().dueElapsed,
            window(isIdle = true, canExact = true, lastBackupFireElapsed = lastBackup).backupAtElapsed,
        )
        val paced = window(isIdle = true, lastBackupFireElapsed = lastBackup)
        assertEquals(lastBackup + 9 * 60_000L, paced.backupAtElapsed)
        assertEquals(nowElapsed + 80_000L, paced.dueElapsed)
        // Idle-paced: the backup is what fires, so it drives the estimate.
        assertEquals(now + (lastBackup + 9 * 60_000L - nowElapsed), paced.nextHeartbeatAt)
    }

    @Test
    fun `idle pacing never moves the backup before due`() {
        val w = window(isIdle = true, lastBackupFireElapsed = nowElapsed - 20 * 60_000L)

        assertEquals(w.dueElapsed, w.backupAtElapsed)
    }

    @Test
    fun `idle without a known backup fire uses due`() {
        val w = window(isIdle = true, lastBackupFireElapsed = null)

        assertEquals(w.dueElapsed, w.backupAtElapsed)
        assertEquals(HeartbeatStrategy.IDLE_PACED, w.strategy)
    }

    @Test
    fun `a backup fire ahead of now belongs to a previous boot and is ignored`() {
        val w = window(isIdle = true, lastBackupFireElapsed = nowElapsed + 1_000L)

        assertEquals(w.dueElapsed, w.backupAtElapsed)
    }

    @Test
    fun `intervals are floored and ordered`() {
        val w = window(lastRecordAt = now, lastRecordElapsed = nowElapsed, minIntervalSec = 10, maxIntervalSec = 5)

        assertEquals(60_000L, w.minIntervalMs)
        assertEquals(nowElapsed + 60_000L, w.dueElapsed)
        assertEquals(nowElapsed + 60_000L, w.deadlineElapsed)
    }

    @Test
    fun `a last record from an earlier tracking session does not make the new session overdue`() {
        // Tracking stopped 5 h ago (last record) and was started again 10 s ago.
        val w = window(
            lastRecordAt = now - 5 * 3_600_000L,
            lastRecordElapsed = nowElapsed - 5 * 3_600_000L,
            trackingStartedAt = now - 10_000L,
        )

        assertEquals(nowElapsed - 10_000L, w.baseElapsed)
        assertEquals(nowElapsed + 170_000L, w.dueElapsed)
        assertFalse(w.shouldFire(nowElapsed))
    }

    @Test
    fun `a last record inside the current session is the base`() {
        // Restored after the process died: the session started an hour ago, the last record is 100 s old.
        val w = window(trackingStartedAt = now - 3_600_000L)

        assertEquals(nowElapsed - 100_000L, w.baseElapsed)
    }

    @Test
    fun `a session start in the future (wall clock moved back) is ignored`() {
        val w = window(
            lastRecordAt = now - 400_000L,
            lastRecordElapsed = nowElapsed - 400_000L,
            trackingStartedAt = now + 60_000L,
        )

        assertEquals(nowElapsed - 400_000L, w.baseElapsed)
        assertTrue(w.shouldFire(nowElapsed))
    }

    @Test
    fun `needsRearm compares strategy and trigger times only`() {
        val a = window()
        val b = window(nowWall = now + 5_000L, nowEl = nowElapsed + 5_000L)

        assertFalse(b.needsRearm(a))
        assertTrue(window(isIdle = true).needsRearm(a))
        assertTrue(a.needsRearm(null))
    }

    @Test
    fun `needsRearm keeps the alarms for a due time that moved later by less than 30 s`() {
        val armed = window()

        assertFalse(window(lastRecordElapsed = nowElapsed - 100_000L + 29_999L).needsRearm(armed))
        assertTrue(window(lastRecordElapsed = nowElapsed - 100_000L + 30_000L).needsRearm(armed))
        assertTrue(window(lastRecordElapsed = nowElapsed - 100_000L + 120_000L).needsRearm(armed))
        // Exact: one alarm, same threshold.
        val exact = window(canExact = true)
        assertFalse(window(canExact = true, lastRecordElapsed = nowElapsed - 90_000L).needsRearm(exact))
        assertTrue(window(canExact = true, lastRecordElapsed = nowElapsed - 70_000L).needsRearm(exact))
    }

    @Test
    fun `needsRearm re-arms any move earlier, however small`() {
        val armed = window()

        // A shorter minInterval moves the due time 1 s earlier: the armed alarm would fire 1 s late.
        assertTrue(window(minIntervalSec = 179).needsRearm(armed))
        // Leaving pacing moves the backup earlier (the strategy changes too).
        val paced = window(isIdle = true, lastBackupFireElapsed = nowElapsed - 60_000L)
        assertTrue(window(lastBackupFireElapsed = nowElapsed - 60_000L).needsRearm(paced))
    }

    @Test
    fun `needsRearm re-arms any move of an idle-paced backup`() {
        val lastBackup = nowElapsed - 60_000L
        // Backup driven by the due time (the last backup fire is long enough ago).
        val oldBackup = nowElapsed - 20 * 60_000L
        val armed = window(isIdle = true, lastBackupFireElapsed = oldBackup)
        val moved = window(isIdle = true, lastBackupFireElapsed = oldBackup, lastRecordElapsed = nowElapsed - 95_000L)
        assertEquals(5_000L, moved.backupAtElapsed - armed.backupAtElapsed)
        assertTrue(moved.needsRearm(armed))

        // Backup driven by the pacing: a record moves only the listener's due time, by less than 30 s.
        val pacedArmed = window(isIdle = true, lastBackupFireElapsed = lastBackup)
        val pacedLater = window(isIdle = true, lastBackupFireElapsed = lastBackup, lastRecordElapsed = nowElapsed - 95_000L)
        assertEquals(pacedArmed.backupAtElapsed, pacedLater.backupAtElapsed)
        assertFalse(pacedLater.needsRearm(pacedArmed))
    }
}
