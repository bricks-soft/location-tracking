package com.brickssoft.locationtracking.processing

import com.brickssoft.locationtracking.model.TrackedLocation
import com.brickssoft.locationtracking.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class KalmanFilterTest {
    private val kalman = KalmanFilter()

    @Test
    fun `first fix is returned unchanged`() {
        val fix = Fixtures.location()

        assertEquals(fix, kalman.filter(fix))
    }

    @Test
    fun `equal variances average the estimate and the measurement`() {
        val first = Fixtures.location(accuracy = 10f)
        kalman.filter(first)
        // dt = 0 => no process noise, so estimate and measurement both have variance 100 m².
        val second = Fixtures.moved(first, northMeters = 20.0, timeDeltaMs = 0)

        val out = kalman.filter(second)

        assertEquals((first.latitude + second.latitude) / 2, out.latitude, 1e-12)
        assertEquals((first.longitude + second.longitude) / 2, out.longitude, 1e-12)
    }

    @Test
    fun `fast movement trusts the new fix`() {
        val first = Fixtures.location(accuracy = 5f, speed = 30f)
        kalman.filter(first)
        val second = Fixtures.moved(first, northMeters = 30.0).copy(speed = 30f)

        val out = kalman.filter(second)

        // q = 900 m² vs r = 25 m²: gain ~0.97.
        assertEquals(0.0, Geo.distance(out, second), 1.5)
    }

    @Test
    fun `missing speed uses 3 metres per second`() {
        val first = Fixtures.location(accuracy = 3f, speed = null)
        kalman.filter(first)
        val second = Fixtures.moved(first, northMeters = 10.0, timeDeltaMs = 1_000).copy(speed = null)

        val out = kalman.filter(second)

        // P = 9 + 9 = 18, r = 9 => gain 2/3.
        assertEquals(10.0 * 2 / 3, Geo.distance(first, out), 0.05)
    }

    @Test
    fun `a reported speed of 0 is floored at 1 metre per second`() {
        val first = Fixtures.location(accuracy = 3f, speed = 0f)
        kalman.filter(first)
        val second = Fixtures.moved(first, northMeters = 10.0, timeDeltaMs = 1_000).copy(speed = 0f)

        val out = kalman.filter(second)

        // P = 9 + 1 = 10, r = 9 => gain 10/19.
        assertEquals(Geo.distance(first, second) * 10 / 19, Geo.distance(first, out), 0.01)
    }

    @Test
    fun `a stationary estimate still follows a real move`() {
        var fix = Fixtures.location(accuracy = 10f, speed = 0f)
        repeat(300) {
            fix = fix.copy(time = fix.time + 1_000)
            kalman.filter(fix)
        }
        val moved = Fixtures.moved(fix, northMeters = 50.0)
        var out = moved
        repeat(30) { i -> out = kalman.filter(moved.copy(time = moved.time + i * 1_000L)) }

        assertTrue("still ${Geo.distance(out, moved)} m behind", Geo.distance(out, moved) < 10.0)
    }

    @Test
    fun `sparse fixes get process noise that grows with the square of the gap`() {
        val first = Fixtures.location(accuracy = 10f, speed = 10f)
        kalman.filter(first)
        val second = Fixtures.moved(first, northMeters = 100.0, timeDeltaMs = 10_000).copy(speed = 10f)

        val out = kalman.filter(second)

        // q = (10 m/s * 10 s)² = 10 000 m² => gain 0.99 (speed² * dt alone would give 0.92, ~8 m behind).
        assertTrue("${Geo.distance(out, second)} m behind", Geo.distance(out, second) < 1.5)
    }

    @Test
    fun `longitude is smoothed across the antimeridian`() {
        val east = Fixtures.location(latitude = 10.0, longitude = 179.9999)
        kalman.filter(east)

        val out = kalman.filter(east.copy(longitude = -179.9991))

        // Midpoint of the short arc: 179.9999 + 0.0010 / 2 = 180.0004 = -179.9996.
        assertEquals(-179.9996, out.longitude, 1e-9)
        assertTrue(Geo.distance(out, east) < 60.0)
    }

    @Test
    fun `longitude stays in range at the antimeridian`() {
        val east = Fixtures.location(latitude = 0.0, longitude = 179.9999)
        kalman.filter(east)

        val out = kalman.filter(east.copy(longitude = -179.9999))

        assertEquals(180.0, abs(out.longitude), 1e-9)
        assertEquals(Geo.distance(east, east.copy(longitude = -179.9999)) / 2, Geo.distance(out, east), 0.01)
    }

    @Test
    fun `elapsed realtime is preferred over wall time`() {
        val first = Fixtures.location(speed = 0f, elapsedRealtimeNanos = 5_000_000_000L)
        kalman.filter(first)
        // Wall clock jumped back 2 minutes, but only 1 s passed: no reset.
        val second = Fixtures.moved(first, northMeters = 20.0, timeDeltaMs = 1_000)
            .copy(time = first.time - 120_000, speed = 0f)

        assertNotEquals(second, kalman.filter(second))

        // 61 s on the elapsed clock (wall clock says 1 s): reset.
        val third = Fixtures.moved(second, northMeters = 20.0, timeDeltaMs = 61_000).copy(time = second.time + 1_000)
        assertEquals(third, kalman.filter(third))
    }

    @Test
    fun `only latitude and longitude change`() {
        val first = Fixtures.location()
        kalman.filter(first)
        val second = Fixtures.moved(first, northMeters = 8.0, eastMeters = 3.0)
            .copy(altitude = 700.0, speed = 1.5f, heading = 12f, provider = "fused", isMock = true)

        val out = kalman.filter(second)

        assertNotEquals(second.latitude, out.latitude)
        assertNotEquals(second.longitude, out.longitude)
        assertEquals(second.copy(latitude = out.latitude, longitude = out.longitude), out)
    }

    @Test
    fun `a gap over 60 seconds restarts from the measurement`() {
        val first = Fixtures.location(speed = 0f)
        kalman.filter(first)
        val second = Fixtures.moved(first, northMeters = 50.0, timeDeltaMs = 60_001).copy(speed = 0f)

        assertEquals(second, kalman.filter(second))
    }

    @Test
    fun `a gap of exactly 60 seconds still smooths`() {
        val first = Fixtures.location(speed = 0f)
        kalman.filter(first)
        val second = Fixtures.moved(first, northMeters = 50.0, timeDeltaMs = 60_000).copy(speed = 0f)

        assertNotEquals(second, kalman.filter(second))
    }

    @Test
    fun `time going backwards restarts from the measurement`() {
        val first = Fixtures.location(speed = 0f)
        kalman.filter(first)
        val older = Fixtures.moved(first, northMeters = 50.0, timeDeltaMs = -1_000).copy(speed = 0f)

        assertEquals(older, kalman.filter(older))
    }

    @Test
    fun `reset forgets history`() {
        val first = Fixtures.location(speed = 0f)
        kalman.filter(first)
        kalman.reset()
        val second = Fixtures.moved(first, northMeters = 50.0).copy(speed = 0f)

        assertEquals(second, kalman.filter(second))
    }

    @Test
    fun `zero accuracy and same-time fixes stay finite`() {
        val first = Fixtures.location(accuracy = 0f, speed = 0f)
        kalman.filter(first)
        val same = first.copy(latitude = first.latitude + 0.0001, accuracy = 0f)

        val out = kalman.filter(same)

        assertTrue(out.latitude.isFinite() && out.longitude.isFinite())
        assertEquals((first.latitude + same.latitude) / 2, out.latitude, 1e-12)
    }

    @Test
    fun `smoothing reduces the error of a stationary noisy track`() {
        val truth = Fixtures.location(accuracy = 10f, speed = 0f)
        val noisy = noisyTrack(truth, count = 180, sigmaMeters = 10.0, northSpeed = 0.0, reportedSpeed = 0f)

        val (rawError, smoothedError) = meanErrors(noisy, truth, northSpeed = 0.0)

        assertTrue("raw=$rawError smoothed=$smoothedError", smoothedError < rawError * 0.4)
    }

    @Test
    fun `smoothing reduces the error of a walking noisy track`() {
        val start = Fixtures.location(accuracy = 10f)
        val noisy = noisyTrack(start, count = 600, sigmaMeters = 10.0, northSpeed = 1.2, reportedSpeed = 1.2f)

        val (rawError, smoothedError) = meanErrors(noisy, start, northSpeed = 1.2)

        assertTrue("raw=$rawError smoothed=$smoothedError", smoothedError < rawError * 0.9)
    }

    @Test
    fun `smoothing reduces the error when fixes carry no speed`() {
        val start = Fixtures.location(accuracy = 15f)
        val noisy = noisyTrack(start, count = 600, sigmaMeters = 15.0, northSpeed = 1.0, reportedSpeed = null)

        val (rawError, smoothedError) = meanErrors(noisy, start, northSpeed = 1.0)

        assertTrue("raw=$rawError smoothed=$smoothedError", smoothedError < rawError * 0.8)
    }

    /** 1 Hz fixes moving north at [northSpeed] m/s from [start], with Gaussian noise of [sigmaMeters] per axis. */
    private fun noisyTrack(
        start: TrackedLocation,
        count: Int,
        sigmaMeters: Double,
        northSpeed: Double,
        reportedSpeed: Float?,
    ): List<TrackedLocation> {
        val random = Random(42)
        return (0 until count).map { i ->
            Fixtures.moved(
                start,
                northMeters = northSpeed * i + random.nextGaussian() * sigmaMeters,
                eastMeters = random.nextGaussian() * sigmaMeters,
                timeDeltaMs = i * 1_000L,
            ).copy(speed = reportedSpeed)
        }
    }

    /** Mean distance to the true position (after a 10-fix warm-up) of the raw and of the smoothed fixes. */
    private fun meanErrors(fixes: List<TrackedLocation>, start: TrackedLocation, northSpeed: Double): Pair<Double, Double> {
        var raw = 0.0
        var smoothed = 0.0
        var n = 0
        fixes.forEachIndexed { i, fix ->
            val out = kalman.filter(fix)
            if (i < 10) return@forEachIndexed
            val truth = Fixtures.moved(start, northMeters = northSpeed * i, timeDeltaMs = i * 1_000L)
            raw += Geo.distance(truth, fix)
            smoothed += Geo.distance(truth, out)
            n++
        }
        return raw / n to smoothed / n
    }
}
