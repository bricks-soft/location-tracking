package com.brickssoft.locationtracking.engine

import com.brickssoft.locationtracking.config.GeolocationConfig
import com.brickssoft.locationtracking.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElasticDistanceTest {
    @Test
    fun `threshold follows distanceFilter times rounded speed step times multiplier`() {
        // round(13.4 / 5) = 3
        assertEquals(30.0, ElasticDistance.threshold(10.0, 13.4f, 1.0, false), 1e-9)
        // round(2.5) rounds half up
        assertEquals(30.0, ElasticDistance.threshold(10.0, 12.5f, 1.0, false), 1e-9)
        assertEquals(40.0, ElasticDistance.threshold(10.0, 10f, 2.0, false), 1e-9)
        assertEquals(25.0, ElasticDistance.threshold(5.0, 25f, 1.0, false), 1e-9)
    }

    @Test
    fun `threshold never drops below distanceFilter`() {
        // round(0.48) = 0 -> max(1, 0)
        assertEquals(10.0, ElasticDistance.threshold(10.0, 2.4f, 1.0, false), 1e-9)
        // 2 * 0.25 = 0.5 -> max(1, 0.5)
        assertEquals(10.0, ElasticDistance.threshold(10.0, 10f, 0.25, false), 1e-9)
        assertEquals(10.0, ElasticDistance.threshold(10.0, 40f, 0.0, false), 1e-9)
    }

    @Test
    fun `unknown or invalid speed counts as zero`() {
        assertEquals(10.0, ElasticDistance.threshold(10.0, null, 1.0, false), 1e-9)
        assertEquals(10.0, ElasticDistance.threshold(10.0, -20f, 1.0, false), 1e-9)
        assertEquals(10.0, ElasticDistance.threshold(10.0, Float.NaN, 1.0, false), 1e-9)
    }

    @Test
    fun `disableElasticity uses the plain distance filter`() {
        assertEquals(10.0, ElasticDistance.threshold(10.0, 40f, 3.0, true), 1e-9)
        assertEquals(0.0, ElasticDistance.threshold(-5.0, 40f, 1.0, true), 1e-9)
    }

    @Test
    fun `shouldRecord compares the distance from the last recorded fix`() {
        val config = GeolocationConfig(distanceFilter = 10.0)
        val last = Fixtures.location(speed = 13.4f)

        assertTrue(ElasticDistance.shouldRecord(null, last, config))
        assertFalse(ElasticDistance.shouldRecord(last, Fixtures.moved(last, 25.0), config))
        assertTrue(ElasticDistance.shouldRecord(last, Fixtures.moved(last, 35.0), config))
        val slow = Fixtures.moved(last, 12.0).copy(speed = 1f)
        assertTrue(ElasticDistance.shouldRecord(last, slow, config))
        assertTrue(ElasticDistance.shouldRecord(last, Fixtures.moved(last, 12.0), config.copy(disableElasticity = true)))
    }

    @Test
    fun `distanceMeters is the great-circle distance`() {
        assertEquals(111_195.0, distanceMeters(0.0, 0.0, 1.0, 0.0), 1.0)
        assertEquals(111_195.0, distanceMeters(0.0, 0.0, 0.0, 1.0), 1.0)
        assertEquals(0.0, distanceMeters(24.7, 46.6, 24.7, 46.6), 1e-9)
        val a = Fixtures.location()
        val b = Fixtures.moved(a, 300.0, 400.0)
        assertEquals(distanceMeters(a, b), distanceMeters(b, a), 1e-9)
        assertEquals(500.0, distanceMeters(a, b), 2.0)
    }

    @Test
    fun `accuracyMeters treats invalid accuracy as zero`() {
        assertEquals(5.0, Fixtures.location(accuracy = 5f).accuracyMeters, 1e-9)
        assertEquals(0.0, Fixtures.location(accuracy = -1f).accuracyMeters, 1e-9)
        assertEquals(0.0, Fixtures.location(accuracy = Float.NaN).accuracyMeters, 1e-9)
    }
}
