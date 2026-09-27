package com.brickssoft.locationtracking.processing

import com.brickssoft.locationtracking.config.Config
import com.brickssoft.locationtracking.config.LocationFilterConfig
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DefaultLocationProcessorTest {
    private val configStore = FakeConfigStore()
    private val processor = DefaultLocationProcessor(configStore)

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun setFilter(transform: (LocationFilterConfig) -> LocationFilterConfig) {
        val c = configStore.configFlow.value
        configStore.configFlow.value = c.copy(geolocation = c.geolocation.copy(filter = transform(c.geolocation.filter)))
    }

    private fun accepted(result: FilterResult): TrackedLocation {
        if (result !is FilterResult.Accepted) fail("expected Accepted but was $result")
        return (result as FilterResult.Accepted).location
    }

    private fun assertRejected(result: FilterResult, reasonPart: String) {
        if (result !is FilterResult.Rejected) fail("expected Rejected but was $result")
        val reason = (result as FilterResult.Rejected).reason
        assertTrue("reason '$reason' should mention '$reasonPart'", reason.contains(reasonPart))
    }

    @Test
    fun `defaults match the documented filter config`() {
        assertEquals(LocationFilterConfig(), Config().geolocation.filter)
        assertEquals(100.0, Config().geolocation.filter.trackingAccuracyThreshold, 0.0)
        assertEquals(80.0, Config().geolocation.filter.maxImpliedSpeed, 0.0)
    }

    @Test
    fun `a good fix is accepted unchanged`() {
        val fix = Fixtures.location()

        assertEquals(fix, accepted(processor.process(fix, isMoving = true)))
    }

    // ---- accuracy

    @Test
    fun `rejects accuracy worse than the threshold`() {
        assertRejected(processor.process(Fixtures.location(accuracy = 100.5f), true), "accuracy")
    }

    @Test
    fun `accepts accuracy equal to the threshold`() {
        accepted(processor.process(Fixtures.location(accuracy = 100f), true))
    }

    @Test
    fun `rejects non-finite accuracy`() {
        assertRejected(processor.process(Fixtures.location(accuracy = Float.NaN), true), "accuracy")
        assertRejected(processor.process(Fixtures.location(accuracy = Float.POSITIVE_INFINITY), true), "accuracy")
    }

    @Test
    fun `reads the accuracy threshold live`() {
        val fix = Fixtures.location(accuracy = 30f)
        setFilter { it.copy(trackingAccuracyThreshold = 20.0) }
        assertRejected(processor.process(fix, true), "accuracy")

        setFilter { it.copy(trackingAccuracyThreshold = 50.0) }
        accepted(processor.process(fix, true))
    }

    @Test
    fun `a rejected fix does not become the reference`() {
        val first = Fixtures.location()
        accepted(processor.process(first, true))
        processor.process(first.copy(time = first.time + 1_000, accuracy = 500f), true)

        // Identical to the first accepted fix (the rejected one is ignored) => still rejected.
        assertRejected(processor.process(first, true), "identical")
    }

    // ---- coordinates

    @Test
    fun `rejects invalid coordinates`() {
        assertRejected(processor.process(Fixtures.location(latitude = 91.0), true), "invalid coordinates")
        assertRejected(processor.process(Fixtures.location(longitude = -181.0), true), "invalid coordinates")
        assertRejected(processor.process(Fixtures.location(latitude = Double.NaN), true), "invalid coordinates")
    }

    @Test
    fun `rejects the 0,0 glitch but not the equator or the prime meridian`() {
        assertRejected(processor.process(Fixtures.location(latitude = 0.0, longitude = 0.0), true), "invalid coordinates")
        accepted(processor.process(Fixtures.location(latitude = 0.0, longitude = 0.001, time = 1_000), true))
        accepted(processor.process(Fixtures.location(latitude = 0.001, longitude = 0.0, time = 10_000), true))
    }

    // ---- mock

    @Test
    fun `keeps mock fixes by default`() {
        val fix = Fixtures.location(isMock = true)

        assertTrue(accepted(processor.process(fix, true)).isMock)
    }

    @Test
    fun `rejects mock fixes when configured`() {
        setFilter { it.copy(rejectMockLocations = true) }

        assertRejected(processor.process(Fixtures.location(isMock = true), true), "mock")
        accepted(processor.process(Fixtures.location(isMock = false), true))
    }

    // ---- identical

    @Test
    fun `rejects a fix identical to the previous accepted one`() {
        val fix = Fixtures.location()
        accepted(processor.process(fix, true))

        assertRejected(processor.process(fix.copy(accuracy = 3f), true), "identical")
    }

    @Test
    fun `accepts identical fixes when allowed`() {
        setFilter { it.copy(allowIdenticalLocations = true) }
        val fix = Fixtures.location()
        accepted(processor.process(fix, true))

        assertEquals(fix, accepted(processor.process(fix, true)))
    }

    @Test
    fun `same position at a new time is not identical`() {
        val fix = Fixtures.location()
        accepted(processor.process(fix, true))

        accepted(processor.process(fix.copy(time = fix.time + 1_000), true))
    }

    @Test
    fun `new position at the same time is not identical`() {
        val fix = Fixtures.location()
        accepted(processor.process(fix, true))

        // dt = 0: the implied-speed check is skipped.
        accepted(processor.process(Fixtures.moved(fix, northMeters = 500.0, timeDeltaMs = 0), true))
    }

    // ---- implied speed

    @Test
    fun `rejects a jump implying a speed above the limit`() {
        val fix = Fixtures.location()
        accepted(processor.process(fix, true))

        // ~1 km in 1 s.
        assertRejected(processor.process(Fixtures.moved(fix, northMeters = 1_000.0, timeDeltaMs = 1_000), true), "implied speed")
    }

    @Test
    fun `accepts movement below the speed limit`() {
        val fix = Fixtures.location()
        accepted(processor.process(fix, true))

        // ~79 m/s.
        accepted(processor.process(Fixtures.moved(fix, northMeters = 79.0, timeDeltaMs = 1_000), true))
    }

    @Test
    fun `the speed limit is configurable and 0 disables it`() {
        val fix = Fixtures.location()
        val next = Fixtures.moved(fix, northMeters = 40.0, timeDeltaMs = 1_000)
        setFilter { it.copy(maxImpliedSpeed = 30.0) }
        accepted(processor.process(fix, true))
        assertRejected(processor.process(next, true), "implied speed")

        setFilter { it.copy(maxImpliedSpeed = 0.0) }
        accepted(processor.process(Fixtures.moved(fix, northMeters = 100_000.0, timeDeltaMs = 1_000), true))
    }

    // ---- stale

    @Test
    fun `rejects fixes older than the previous accepted one`() {
        val fix = Fixtures.location()
        accepted(processor.process(fix, true))

        assertRejected(processor.process(Fixtures.moved(fix, northMeters = 10.0, timeDeltaMs = -1_000), true), "stale")
        // The reference is unchanged: re-delivering the first fix is still a duplicate.
        assertRejected(processor.process(fix, true), "identical")
    }

    @Test
    fun `stale fixes are rejected even when identical fixes are allowed`() {
        setFilter { it.copy(allowIdenticalLocations = true, maxImpliedSpeed = 0.0) }
        val fix = Fixtures.location()
        accepted(processor.process(fix, true))

        assertRejected(processor.process(fix.copy(time = fix.time - 1), true), "stale")
    }

    @Test
    fun `elapsed realtime is preferred over wall time`() {
        val fix = Fixtures.location(elapsedRealtimeNanos = 5_000_000_000L)
        accepted(processor.process(fix, true))

        // The wall clock jumped back 2 minutes, but 1 s passed on the elapsed clock: not stale, 20 m/s.
        val next = Fixtures.moved(fix, northMeters = 20.0, timeDeltaMs = 1_000).copy(time = fix.time - 120_000)
        accepted(processor.process(next, true))

        // The wall clock says 100 s, but only 1 s passed: ~1 km/s.
        val jump = Fixtures.moved(next, northMeters = 1_000.0, timeDeltaMs = 1_000).copy(time = next.time + 100_000)
        assertRejected(processor.process(jump, true), "implied speed")
    }

    @Test
    fun `wall time is used when a fix has no elapsed realtime`() {
        val fix = Fixtures.location(elapsedRealtimeNanos = 5_000_000_000L)
        accepted(processor.process(fix, true))

        // 1 km in 100 s of wall time = 10 m/s.
        accepted(processor.process(Fixtures.moved(fix, northMeters = 1_000.0, timeDeltaMs = 100_000).copy(elapsedRealtimeNanos = 0), true))
    }

    @Test
    fun `speed is measured from the last accepted fix`() {
        val a = Fixtures.location()
        accepted(processor.process(a, true))
        val outlier = Fixtures.moved(a, northMeters = 5_000.0, timeDeltaMs = 1_000)
        assertRejected(processor.process(outlier, true), "implied speed")

        // 150 m in 2 s from `a` (75 m/s) is fine; from the outlier it would not be.
        accepted(processor.process(Fixtures.moved(a, northMeters = 150.0, timeDeltaMs = 2_000), true))
    }

    @Test
    fun `a long gap lets the track recover from a stale reference`() {
        val a = Fixtures.location()
        accepted(processor.process(a, true))

        // 5 km after 70 s = ~71 m/s.
        accepted(processor.process(Fixtures.moved(a, northMeters = 5_000.0, timeDeltaMs = 70_000), true))
    }

    // ---- Kalman

    @Test
    fun `kalman smoothing changes only latitude and longitude`() {
        setFilter { it.copy(useKalman = true) }
        val first = Fixtures.location(speed = 0f)
        assertEquals(first, accepted(processor.process(first, true)))
        // Same time => no process noise => equal weights.
        val second = Fixtures.moved(first, northMeters = 20.0, timeDeltaMs = 0).copy(altitude = 1.0, heading = 90f)

        val out = accepted(processor.process(second, true))

        assertNotEquals(second.latitude, out.latitude)
        assertEquals((first.latitude + second.latitude) / 2, out.latitude, 1e-12)
        assertEquals(second.copy(latitude = out.latitude, longitude = out.longitude), out)
    }

    @Test
    fun `kalman off returns the raw fix`() {
        val first = Fixtures.location(speed = 0f)
        processor.process(first, true)
        val second = Fixtures.moved(first, northMeters = 20.0).copy(speed = 0f)

        assertEquals(second, accepted(processor.process(second, true)))
    }

    @Test
    fun `turning kalman off and on again starts fresh`() {
        setFilter { it.copy(useKalman = true) }
        val first = Fixtures.location(speed = 0f)
        processor.process(first, true)
        setFilter { it.copy(useKalman = false) }
        val second = Fixtures.moved(first, northMeters = 20.0).copy(speed = 0f)
        processor.process(second, true)
        setFilter { it.copy(useKalman = true) }
        val third = Fixtures.moved(second, northMeters = 20.0).copy(speed = 0f)

        assertEquals(third, accepted(processor.process(third, true)))
    }

    @Test
    fun `identical and speed checks compare raw fixes when smoothing`() {
        setFilter { it.copy(useKalman = true) }
        val first = Fixtures.location(speed = 0f)
        processor.process(first, true)
        val second = Fixtures.moved(first, northMeters = 20.0).copy(speed = 0f)
        processor.process(second, true)

        assertRejected(processor.process(second, true), "identical")
    }

    // ---- reset

    @Test
    fun `reset clears the history`() {
        setFilter { it.copy(useKalman = true) }
        val fix = Fixtures.location(speed = 0f)
        processor.process(fix, true)

        processor.reset()

        assertEquals(fix, accepted(processor.process(fix, true)))
        processor.reset()
        val far = Fixtures.moved(fix, northMeters = 10_000.0, timeDeltaMs = 1_000).copy(speed = 0f)
        assertEquals(far, accepted(processor.process(far, true)))
    }

    // ---- threads

    @Test
    fun `concurrent callers never see torn state`() {
        setFilter { it.copy(useKalman = true, allowIdenticalLocations = true, maxImpliedSpeed = 0.0) }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val errors = AtomicInteger()
        val base = Fixtures.location(speed = 1f)
        repeat(8) { t ->
            pool.execute {
                start.await()
                repeat(500) { i ->
                    val fix = Fixtures.moved(base, northMeters = i.toDouble(), eastMeters = t.toDouble(), timeDeltaMs = i * 100L)
                    // Threads interleave, so fixes may be older than the last accepted one ("stale").
                    when (val result = processor.process(fix, true)) {
                        is FilterResult.Accepted ->
                            if (!result.location.latitude.isFinite() || !result.location.longitude.isFinite()) {
                                errors.incrementAndGet()
                            }
                        is FilterResult.Rejected -> if (!result.reason.startsWith("stale")) errors.incrementAndGet()
                    }
                    if (i % 100 == 0) processor.reset()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(0, errors.get())
    }
}
