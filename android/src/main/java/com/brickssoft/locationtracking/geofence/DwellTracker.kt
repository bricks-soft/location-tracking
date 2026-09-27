package com.brickssoft.locationtracking.geofence

import com.brickssoft.locationtracking.core.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Synthesized dwell for geofences whose backend has no dwell support, and for polygons.
 *
 * [arm] starts a one-shot timer that fires [onDue] once the geofence has been inside for its loitering delay;
 * [cancel] stops it (EXIT). Timers are coroutines on [scope], measured with [clock] (wall time, so a deadline
 * survives a process restart). Because a coroutine `delay` can be stretched by deep sleep, callers also poll
 * [takeDue] whenever they get a fix. Every armed timer is reported at most once, either by [onDue] or by [takeDue].
 *
 * Thread-safe. [onDue] runs on [scope] without any lock held; the token passed to it is the one given to [arm].
 */
internal class DwellTracker(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val onDue: suspend (id: String, token: Long) -> Unit,
) {
    private class Pending(val deadline: Long, val token: Long, val seq: Long, val job: Job)

    private val pending = HashMap<String, Pending>()
    private var nextSeq = 0L

    /**
     * Arms (or re-arms) the dwell timer of [id] to fire at [deadline] (epoch ms, [Clock.now] base). A deadline in
     * the past fires as soon as [scope] runs the timer.
     */
    fun arm(id: String, deadline: Long, token: Long) {
        val seq = synchronized(pending) { ++nextSeq }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val wait = deadline - clock.now()
            if (wait > 0) delay(wait)
            if (release(id, seq)) onDue(id, token)
        }
        val previous = synchronized(pending) { pending.put(id, Pending(deadline, token, seq, job)) }
        previous?.job?.cancel()
        job.start()
    }

    fun cancel(id: String) {
        synchronized(pending) { pending.remove(id) }?.job?.cancel()
    }

    fun cancelAll() {
        val all = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        all.forEach { it.job.cancel() }
    }

    fun isArmed(id: String): Boolean = synchronized(pending) { id in pending }

    fun hasArmed(): Boolean = synchronized(pending) { pending.isNotEmpty() }

    /** Removes every timer whose deadline is at or before [now] and returns their (id, token) pairs. */
    fun takeDue(now: Long = clock.now()): List<Pair<String, Long>> {
        val due = synchronized(pending) {
            val entries = pending.entries.filter { it.value.deadline <= now }.map { it.key to it.value }
            entries.forEach { (id, _) -> pending.remove(id) }
            entries
        }
        due.forEach { (_, p) -> p.job.cancel() }
        return due.map { (id, p) -> id to p.token }
    }

    /** True if timer [seq] of [id] was still armed; it is then disarmed. */
    private fun release(id: String, seq: Long): Boolean = synchronized(pending) {
        val current = pending[id]
        if (current != null && current.seq == seq) {
            pending.remove(id)
            true
        } else {
            false
        }
    }
}
