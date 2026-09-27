package com.brickssoft.locationtracking.processing

import com.brickssoft.locationtracking.config.RuntimeState
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.testing.FakeConfigStore
import com.brickssoft.locationtracking.testing.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DefaultOdometerTest {
    private val configStore = FakeConfigStore()
    private val odometer = DefaultOdometer(configStore)

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun setOdometerThreshold(meters: Double) {
        val c = configStore.configFlow.value
        configStore.configFlow.value = c.copy(
            geolocation = c.geolocation.copy(filter = c.geolocation.filter.copy(odometerAccuracyThreshold = meters)),
        )
    }

    @Test
    fun `value comes from the persisted runtime state`() {
        val store = FakeConfigStore(runtime = RuntimeState(odometer = 1532.4))

        assertEquals(1532.4, DefaultOdometer(store).value, 0.0)

        store.runtimeFlow.value = store.runtimeFlow.value.copy(odometer = 7.0)
        assertEquals(7.0, DefaultOdometer(store).value, 0.0)
    }

    @Test
    fun `first fix only sets the anchor`() {
        odometer.onLocation(Fixtures.location(accuracy = 5f))

        assertEquals(0.0, odometer.value, 0.0)
    }

    @Test
    fun `sums a known path along a meridian within 1 percent`() {
        // 0.000 .. 0.010 degrees of latitude at longitude 0 in 11 steps = 0.01 degree = 1111.95 m.
        for (i in 0..10) {
            odometer.onLocation(Fixtures.location(latitude = i * 0.001, longitude = 0.0, accuracy = 5f, time = i * 1_000L))
        }

        assertEquals(1_111.95, odometer.value, 1_111.95 * 0.01)
    }

    @Test
    fun `sums a square walk within 1 percent`() {
        // 4 sides of 250 m, 10 fixes per side.
        var fix = Fixtures.location(accuracy = 4f)
        odometer.onLocation(fix)
        for ((north, east) in listOf(25.0 to 0.0, 0.0 to 25.0, -25.0 to 0.0, 0.0 to -25.0)) {
            repeat(10) {
                fix = Fixtures.moved(fix, northMeters = north, eastMeters = east)
                odometer.onLocation(fix)
            }
        }

        assertEquals(1_000.0, odometer.value, 10.0)
    }

    @Test
    fun `fixes worse than the accuracy threshold are skipped and the anchor is kept`() {
        val a = Fixtures.location(accuracy = 5f)
        val bad = Fixtures.moved(a, northMeters = 500.0).copy(accuracy = 20.5f)
        val c = Fixtures.moved(a, northMeters = 100.0, timeDeltaMs = 2_000).copy(accuracy = 5f)

        odometer.onLocation(a)
        odometer.onLocation(bad)
        assertEquals(0.0, odometer.value, 0.0)
        odometer.onLocation(c)

        assertEquals(Geo.distance(a, c), odometer.value, 1e-9)
    }

    @Test
    fun `accuracy equal to the threshold counts`() {
        val a = Fixtures.location(accuracy = 20f)
        odometer.onLocation(a)
        val b = Fixtures.moved(a, northMeters = 50.0).copy(accuracy = 20f)
        odometer.onLocation(b)

        assertEquals(Geo.distance(a, b), odometer.value, 1e-9)
    }

    @Test
    fun `non-finite accuracy is skipped`() {
        val a = Fixtures.location(accuracy = 5f)
        odometer.onLocation(a)
        odometer.onLocation(Fixtures.moved(a, northMeters = 50.0).copy(accuracy = Float.NaN))

        assertEquals(0.0, odometer.value, 0.0)
    }

    @Test
    fun `reads the accuracy threshold live`() {
        val a = Fixtures.location(accuracy = 30f)
        val b = Fixtures.moved(a, northMeters = 50.0)
        setOdometerThreshold(10.0)
        odometer.onLocation(a)
        odometer.onLocation(b)
        assertEquals(0.0, odometer.value, 0.0)

        setOdometerThreshold(50.0)
        odometer.onLocation(a)
        odometer.onLocation(b)
        assertEquals(Geo.distance(a, b), odometer.value, 1e-9)
    }

    @Test
    fun `distance is persisted via updateRuntime and adds to the stored value`() {
        val store = FakeConfigStore(runtime = RuntimeState(odometer = 1_000.0, isMoving = true))
        val o = DefaultOdometer(store)
        val a = Fixtures.location(accuracy = 5f)
        val b = Fixtures.moved(a, northMeters = 100.0)

        o.onLocation(a)
        assertTrue("no write for the anchor fix", store.calls.none { it == "updateRuntime" })
        o.onLocation(b)

        assertEquals(listOf("updateRuntime"), store.calls.toList())
        assertEquals(1_000.0 + Geo.distance(a, b), store.runtimeFlow.value.odometer, 1e-9)
        assertTrue("other runtime fields are kept", store.runtimeFlow.value.isMoving)
    }

    @Test
    fun `a fix at the same position does not write`() {
        val a = Fixtures.location(accuracy = 5f)
        odometer.onLocation(a)
        odometer.onLocation(a.copy(time = a.time + 1_000))

        assertTrue(configStore.calls.none { it == "updateRuntime" })
    }

    @Test
    fun `set persists the value and keeps the anchor`() {
        val a = Fixtures.location(accuracy = 5f)
        val b = Fixtures.moved(a, northMeters = 100.0)
        odometer.onLocation(a)

        odometer.set(250.0)
        assertEquals(250.0, configStore.runtimeFlow.value.odometer, 0.0)
        odometer.onLocation(b)

        assertEquals(250.0 + Geo.distance(a, b), odometer.value, 1e-9)
    }

    @Test
    fun `set rejects invalid values and keeps the stored one`() {
        odometer.set(10.0)

        for (bad in listOf(-5.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            try {
                odometer.set(bad)
                fail("expected INVALID_ARGUMENT for $bad")
            } catch (e: TrackingException) {
                assertEquals(ErrorCode.INVALID_ARGUMENT, e.code)
            }
        }
        assertEquals(10.0, odometer.value, 0.0)
    }

    // ---- tracking sessions

    private fun startSession(store: FakeConfigStore, startedAt: Long) {
        store.runtimeFlow.value = store.runtimeFlow.value.copy(enabled = true, trackingStartedAt = startedAt)
    }

    @Test
    fun `a new tracking session without a recorded start position starts from its own first fix`() {
        startSession(configStore, startedAt = 1_000L)
        val home = Fixtures.location(accuracy = 5f)
        odometer.onLocation(home)
        odometer.onLocation(Fixtures.moved(home, northMeters = 100.0))
        val before = odometer.value

        // Stop, travel 50 km untracked, start again.
        configStore.runtimeFlow.value = configStore.runtimeFlow.value.copy(enabled = false, trackingStartedAt = null)
        startSession(configStore, startedAt = 9_000_000L)
        val far = Fixtures.moved(home, northMeters = 50_000.0, timeDeltaMs = 3_600_000)
        odometer.onLocation(far)
        assertEquals(before, odometer.value, 0.0)
        val next = Fixtures.moved(far, northMeters = 30.0)
        odometer.onLocation(next)

        assertEquals(before + Geo.distance(far, next), odometer.value, 1e-9)
    }

    @Test
    fun `a later session in the same process is measured from its start position`() {
        startSession(configStore, startedAt = 1_000L)
        val home = Fixtures.location(accuracy = 5f, time = 2_000L)
        odometer.onLocation(home)
        odometer.onLocation(Fixtures.moved(home, northMeters = 100.0))
        val before = odometer.value

        // Stop, travel untracked, start again: the engine records the start position (initial motionchange) as
        // runtime.lastLocation and feeds the odometer only from the first moving fix on.
        val start = Fixtures.moved(home, northMeters = 50_000.0, timeDeltaMs = 9_000_000L)
        configStore.runtimeFlow.value =
            configStore.runtimeFlow.value.copy(enabled = true, trackingStartedAt = start.time, lastLocation = start)
        val firstMoving = Fixtures.moved(start, northMeters = 100.0)
        odometer.onLocation(firstMoving)

        assertEquals(before + Geo.distance(start, firstMoving), odometer.value, 1e-9)
    }

    @Test
    fun `a later session is not measured from a last location of the previous session`() {
        startSession(configStore, startedAt = 1_000L)
        val home = Fixtures.location(accuracy = 5f, time = 2_000L)
        odometer.onLocation(home)
        val lastOfFirst = Fixtures.moved(home, northMeters = 100.0)
        odometer.onLocation(lastOfFirst)
        val before = odometer.value

        configStore.runtimeFlow.value =
            configStore.runtimeFlow.value.copy(enabled = true, trackingStartedAt = 9_000_000L, lastLocation = lastOfFirst)
        val far = Fixtures.moved(home, northMeters = 50_000.0, timeDeltaMs = 9_500_000L)
        odometer.onLocation(far)

        assertEquals(before, odometer.value, 0.0)
    }

    @Test
    fun `a last location newer than the fix is not an anchor`() {
        val fix = Fixtures.location(accuracy = 5f, time = 2_000_000L)
        val newer = Fixtures.moved(fix, northMeters = 300.0, timeDeltaMs = 5_000L)
        val store = FakeConfigStore(runtime = RuntimeState(enabled = true, trackingStartedAt = 1_000_000L, lastLocation = newer))
        val o = DefaultOdometer(store)

        o.onLocation(fix)
        assertEquals(0.0, o.value, 0.0)
        val next = Fixtures.moved(fix, northMeters = 20.0)
        o.onLocation(next)

        assertEquals(Geo.distance(fix, next), o.value, 1e-9)
    }

    @Test
    fun `reset right after a new session starts is not seeded from a location recorded before the reset`() {
        startSession(configStore, startedAt = 1_000L)
        val home = Fixtures.location(accuracy = 5f, time = 2_000L)
        odometer.onLocation(home)
        odometer.onLocation(Fixtures.moved(home, northMeters = 100.0))

        val start = Fixtures.moved(home, northMeters = 5_000.0, timeDeltaMs = 9_000_000L)
        configStore.runtimeFlow.value =
            configStore.runtimeFlow.value.copy(enabled = true, trackingStartedAt = start.time, lastLocation = start)
        odometer.reset()
        val next = Fixtures.moved(start, northMeters = 100.0)
        odometer.onLocation(next)
        assertEquals(0.0, odometer.value, 0.0)
        val after = Fixtures.moved(next, northMeters = 40.0)
        odometer.onLocation(after)

        assertEquals(Geo.distance(next, after), odometer.value, 1e-9)
    }

    @Test
    fun `after a cold restore the last location of the session is the anchor`() {
        val last = Fixtures.location(accuracy = 5f, time = 2_000_000L)
        val store = FakeConfigStore(
            runtime = RuntimeState(enabled = true, trackingStartedAt = 1_000_000L, lastLocation = last, odometer = 500.0),
        )
        val next = Fixtures.moved(last, northMeters = 800.0, timeDeltaMs = 120_000)

        DefaultOdometer(store).onLocation(next)

        assertEquals(500.0 + Geo.distance(last, next), store.runtimeFlow.value.odometer, 1e-9)
    }

    @Test
    fun `set keeps the restored anchor`() {
        val last = Fixtures.location(accuracy = 5f, time = 2_000_000L)
        val store = FakeConfigStore(runtime = RuntimeState(enabled = true, trackingStartedAt = 1_000_000L, lastLocation = last))
        val o = DefaultOdometer(store)
        val next = Fixtures.moved(last, northMeters = 100.0)

        o.set(40.0)
        o.onLocation(next)

        assertEquals(40.0 + Geo.distance(last, next), o.value, 1e-9)
    }

    @Test
    fun `no restore anchor from another session, when disabled, inaccurate or after reset`() {
        val cases = listOf(
            "older than the session" to RuntimeState(enabled = true, trackingStartedAt = 3_000_000L),
            "tracking disabled" to RuntimeState(enabled = false, trackingStartedAt = 1_000_000L),
            "no session" to RuntimeState(enabled = true, trackingStartedAt = null),
            "inaccurate" to RuntimeState(enabled = true, trackingStartedAt = 1_000_000L),
        )
        for ((name, runtime) in cases) {
            val accuracy = if (name == "inaccurate") 50f else 5f
            val last = Fixtures.location(accuracy = accuracy, time = 2_000_000L)
            val store = FakeConfigStore(runtime = runtime.copy(lastLocation = last))

            DefaultOdometer(store).onLocation(Fixtures.moved(last, northMeters = 800.0).copy(accuracy = 5f))

            assertEquals(name, 0.0, store.runtimeFlow.value.odometer, 0.0)
        }
        val last = Fixtures.location(accuracy = 5f, time = 2_000_000L)
        val store = FakeConfigStore(runtime = RuntimeState(enabled = true, trackingStartedAt = 1_000_000L, lastLocation = last))
        val o = DefaultOdometer(store)
        o.reset()
        o.onLocation(Fixtures.moved(last, northMeters = 800.0))
        assertEquals("after reset", 0.0, o.value, 0.0)
    }

    @Test
    fun `the anchor is seeded once per session, not on later lastLocation updates`() {
        val last = Fixtures.location(accuracy = 5f, time = 2_000_000L)
        val store = FakeConfigStore(runtime = RuntimeState(enabled = true, trackingStartedAt = 1_000_000L, lastLocation = last))
        val o = DefaultOdometer(store)
        val a = Fixtures.moved(last, northMeters = 100.0)
        o.onLocation(a)
        val afterFirst = o.value

        // A later lastLocation update (from the record sink) does not move the anchor.
        store.runtimeFlow.value = store.runtimeFlow.value.copy(lastLocation = Fixtures.moved(last, northMeters = 5_000.0))
        val b = Fixtures.moved(a, northMeters = 10.0)
        o.onLocation(b)

        assertEquals(afterFirst + Geo.distance(a, b), o.value, 1e-9)
    }

    @Test
    fun `reset zeroes the value and clears the anchor`() {
        val a = Fixtures.location(accuracy = 5f)
        val b = Fixtures.moved(a, northMeters = 100.0)
        val c = Fixtures.moved(b, northMeters = 40.0)
        odometer.onLocation(a)
        odometer.onLocation(b)
        assertTrue(odometer.value > 0)

        odometer.reset()
        assertEquals(0.0, configStore.runtimeFlow.value.odometer, 0.0)
        odometer.onLocation(c)
        assertEquals(0.0, odometer.value, 0.0)
        odometer.onLocation(Fixtures.moved(c, northMeters = 40.0))

        assertEquals(Geo.distance(c, Fixtures.moved(c, northMeters = 40.0)), odometer.value, 1e-9)
    }
}
