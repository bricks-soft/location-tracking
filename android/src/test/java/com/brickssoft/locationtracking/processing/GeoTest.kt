package com.brickssoft.locationtracking.processing

import com.brickssoft.locationtracking.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test
    fun `one degree of latitude is about 111 km`() {
        assertEquals(111_195.08, Geo.haversine(0.0, 0.0, 1.0, 0.0), 0.01)
        assertEquals(111_195.08, Geo.haversine(0.0, 0.0, 0.0, 1.0), 0.01)
    }

    @Test
    fun `London to Paris matches the published great-circle distance`() {
        val d = Geo.haversine(51.5074, -0.1278, 48.8566, 2.3522)

        assertEquals(343_560.0, d, 500.0)
    }

    @Test
    fun `distance is zero for the same point and symmetric`() {
        val a = Fixtures.location()
        val b = Fixtures.moved(a, northMeters = 120.0, eastMeters = -45.0)

        assertEquals(0.0, Geo.distance(a, a), 0.0)
        assertEquals(Geo.distance(a, b), Geo.distance(b, a), 1e-9)
        assertEquals(128.0, Geo.distance(a, b), 0.5)
    }

    @Test
    fun `antipodal points are half the circumference apart`() {
        assertEquals(Math.PI * Geo.EARTH_RADIUS_M, Geo.haversine(0.0, 0.0, 0.0, 180.0), 1e-6)
        assertEquals(Math.PI * Geo.EARTH_RADIUS_M, Geo.haversine(90.0, 0.0, -90.0, 0.0), 1e-6)
    }

    @Test
    fun `crossing the antimeridian takes the short way`() {
        assertEquals(22_239.0, Geo.haversine(0.0, 179.9, 0.0, -179.9), 1.0)
    }

    @Test
    fun `longitude deltas wrap into the short arc`() {
        assertEquals(0.0, Geo.wrapLongitudeDelta(0.0), 0.0)
        assertEquals(10.0, Geo.wrapLongitudeDelta(10.0), 1e-12)
        assertEquals(0.2, Geo.wrapLongitudeDelta(-359.8), 1e-9)
        assertEquals(-0.2, Geo.wrapLongitudeDelta(359.8), 1e-9)
        assertEquals(-180.0, Geo.wrapLongitudeDelta(180.0), 0.0)
        assertEquals(-179.0, Geo.wrapLongitudeDelta(181.0), 1e-12)
    }

    @Test
    fun `longitudes are normalized into range`() {
        assertEquals(180.0, Geo.normalizeLongitude(180.0), 0.0)
        assertEquals(-180.0, Geo.normalizeLongitude(-180.0), 0.0)
        assertEquals(-179.5, Geo.normalizeLongitude(180.5), 1e-12)
        assertEquals(179.5, Geo.normalizeLongitude(-180.5), 1e-12)
        assertEquals(46.6753, Geo.normalizeLongitude(46.6753), 0.0)
    }

    @Test
    fun `elapsedMs prefers the elapsed-realtime clock`() {
        val a = Fixtures.location(time = 10_000L, elapsedRealtimeNanos = 2_000_000_000L)
        val b = a.copy(time = 5_000L, elapsedRealtimeNanos = 3_500_000_000L)

        assertEquals(1_500L, Geo.elapsedMs(a, b))
        assertEquals(-5_000L, Geo.elapsedMs(a, b.copy(elapsedRealtimeNanos = 0)))
        assertEquals(-5_000L, Geo.elapsedMs(a.copy(elapsedRealtimeNanos = 0), b))
    }

    @Test
    fun `isValid checks range and finiteness`() {
        assertTrue(Geo.isValid(0.0, 0.0))
        assertTrue(Geo.isValid(-90.0, 180.0))
        assertTrue(Geo.isValid(90.0, -180.0))
        assertFalse(Geo.isValid(90.01, 0.0))
        assertFalse(Geo.isValid(0.0, -180.01))
        assertFalse(Geo.isValid(Double.NaN, 0.0))
        assertFalse(Geo.isValid(0.0, Double.POSITIVE_INFINITY))
    }
}
