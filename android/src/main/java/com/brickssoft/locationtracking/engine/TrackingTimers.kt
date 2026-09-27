package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.core.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.EnumMap

/** The engine's timers. */
internal enum class TimerKind {
    /** MOVING -> STATIONARY after `stopTimeout` without evidence of motion. */
    STOP_TIMEOUT,

    /** STATIONARY -> MOVING once a moving activity has lasted `motionTriggerDelay`. */
    MOTION_TRIGGER,

    /** `stopAfterElapsedMinutes`, measured from `trackingStartedAt`. */
    STOP_AFTER_ELAPSED,
}

/**
 * One-shot timers built on coroutines ([delay] in [scope]) and [Clock], without Handlers.
 *
 * Each timer has a deadline on the elapsed-realtime clock. A timer's action runs while holding [guard] (the
 * engine's mutex), and only if the timer is still the current one of its kind, so a cancel or reschedule made
 * under [guard] always wins over a timer that has already woken up. `delay()` does not advance while the CPU
 * sleeps, so the engine also calls [fireDue] on every incoming fix or activity sample.
 *
 * Every method except the timer coroutines themselves must be called while holding [guard].
 */
internal class TrackingTimers(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val guard: Mutex,
) {
    private class Entry(val deadline: Long, val action: suspend () -> Unit) {
        var job: Job? = null
    }

    private val entries = EnumMap<TimerKind, Entry>(TimerKind::class.java)

    /** (Re)schedules [kind] to run [action] (while holding the guard) after [delayMs]; a negative delay fires ASAP. */
    fun schedule(kind: TimerKind, delayMs: Long, action: suspend () -> Unit) {
        cancel(kind)
        val delayMillis = delayMs.coerceAtLeast(0)
        val entry = Entry(clock.elapsedRealtime() + delayMillis, action)
        entries[kind] = entry
        entry.job = scope.launch {
            delay(delayMillis)
            guard.withLock { fireIfCurrent(kind, entry) }
        }
    }

    fun cancel(kind: TimerKind) {
        entries.remove(kind)?.job?.cancel()
    }

    fun cancelAll() {
        val all = entries.values.toList()
        entries.clear()
        all.forEach { it.job?.cancel() }
    }

    fun isArmed(kind: TimerKind): Boolean = entries.containsKey(kind)

    /** Milliseconds until [kind] fires (0 if overdue), or null if it is not armed. */
    fun remainingMs(kind: TimerKind): Long? =
        entries[kind]?.let { (it.deadline - clock.elapsedRealtime()).coerceAtLeast(0) }

    /** Runs every timer whose deadline has passed, in [TimerKind] order. */
    suspend fun fireDue() {
        for (kind in TimerKind.entries) {
            val entry = entries[kind] ?: continue
            if (entry.deadline > clock.elapsedRealtime()) continue
            entry.job?.cancel()
            fireIfCurrent(kind, entry)
        }
    }

    private suspend fun fireIfCurrent(kind: TimerKind, entry: Entry) {
        if (entries[kind] !== entry) return
        // Removed before running, so the action may cancel or reschedule timers (including this kind) freely.
        entries.remove(kind)
        entry.action()
    }
}
