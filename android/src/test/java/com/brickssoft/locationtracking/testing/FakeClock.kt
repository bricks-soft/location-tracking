package com.brickssoft.locationtracking.testing

import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Controllable [Clock]. [advance] moves wall and elapsed time together.
 *
 * If [scheduler] is given, the scheduler's virtual time is added to both clocks, so `delay()` in a `runTest`
 * body and this clock stay in sync (pass `testScheduler`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeClock(
    now: Long = DEFAULT_NOW,
    elapsed: Long = DEFAULT_ELAPSED,
    bootCount: Int = DEFAULT_BOOT_COUNT,
    private val scheduler: TestCoroutineScheduler? = null,
) : Clock {
    @Volatile
    var nowMs: Long = now

    @Volatile
    var elapsedMs: Long = elapsed

    @Volatile
    var bootCountValue: Int = bootCount

    override fun now(): Long = nowMs + (scheduler?.currentTime ?: 0L)

    override fun elapsedRealtime(): Long = elapsedMs + (scheduler?.currentTime ?: 0L)

    override fun bootCount(): Int = bootCountValue

    /** Advances wall and elapsed time by [ms]. */
    fun advance(ms: Long) {
        nowMs += ms
        elapsedMs += ms
    }

    /** Simulates a reboot: elapsed time restarts at [elapsedAfterBoot] and the boot count increments. */
    fun reboot(elapsedAfterBoot: Long = 10_000L) {
        elapsedMs = elapsedAfterBoot - (scheduler?.currentTime ?: 0L)
        bootCountValue += 1
    }

    companion object {
        /** 2026-09-26T10:15:30.456Z */
        const val DEFAULT_NOW = 1_790_417_730_456L
        const val DEFAULT_ELAPSED = 86_400_123L
        const val DEFAULT_BOOT_COUNT = 42
    }
}

/** All three dispatchers backed by one [StandardTestDispatcher] on [scheduler] (use `testScheduler` in `runTest`). */
@OptIn(ExperimentalCoroutinesApi::class)
fun testDispatchers(scheduler: TestCoroutineScheduler = TestCoroutineScheduler()): AppDispatchers {
    val dispatcher = StandardTestDispatcher(scheduler)
    return AppDispatchers(dispatcher, dispatcher, dispatcher)
}
