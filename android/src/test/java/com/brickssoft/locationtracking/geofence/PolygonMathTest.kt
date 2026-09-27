package com.brickssoft.locationtracking.geofence

import com.brickssoft.locationtracking.model.LatLng
import com.brickssoft.locationtracking.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

class PolygonMathTest {
    private val lat = Fixtures.LAT
    private val lng = Fixtures.LNG

    /** [lat]/[lng] moved by the given meters. */
    private fun at(northMeters: Double, eastMeters: Double, lat0: Double = lat, lng0: Double = lng) = LatLng(
        lat0 + northMeters / METERS_PER_DEGREE,
        lng0 + eastMeters / (METERS_PER_DEGREE * cos(Math.toRadians(lat0))),
    )

    private fun contains(vertices: List<LatLng>, p: LatLng) = PolygonMath.contains(vertices, p.latitude, p.longitude)

    @Test
    fun `square contains its centre and not points outside`() {
        val square = Fixtures.square(lat, lng, 100.0)

        assertTrue(contains(square, at(0.0, 0.0)))
        assertTrue(contains(square, at(95.0, -95.0)))
        assertFalse(contains(square, at(105.0, 0.0)))
        assertFalse(contains(square, at(0.0, -105.0)))
        assertFalse(contains(square, at(500.0, 500.0)))
    }

    @Test
    fun `concave U-shaped polygon excludes the notch`() {
        // A "U" 300 m wide and 300 m tall with a 100 m wide notch cut from the top middle down to 100 m.
        val u = listOf(
            at(0.0, 0.0), at(0.0, 300.0), at(300.0, 300.0), at(300.0, 200.0),
            at(100.0, 200.0), at(100.0, 100.0), at(300.0, 100.0), at(300.0, 0.0),
        )

        assertTrue("left arm", contains(u, at(250.0, 50.0)))
        assertTrue("right arm", contains(u, at(250.0, 250.0)))
        assertTrue("base", contains(u, at(50.0, 150.0)))
        assertFalse("notch", contains(u, at(250.0, 150.0)))
        assertFalse("notch bottom", contains(u, at(150.0, 150.0)))
        assertFalse("outside", contains(u, at(-10.0, 150.0)))
    }

    @Test
    fun `winding order and a repeated closing vertex do not matter`() {
        val triangle = listOf(at(0.0, 0.0), at(0.0, 200.0), at(200.0, 0.0))
        val closedReversed = (triangle + triangle.first()).reversed()

        for (vertices in listOf(triangle, closedReversed)) {
            assertTrue(contains(vertices, at(50.0, 50.0)))
            assertFalse(contains(vertices, at(150.0, 150.0)))
        }
    }

    @Test
    fun `works in every quadrant of the normal range`() {
        for ((lat0, lng0) in listOf(-33.86 to 151.21, 51.5 to -0.12, -22.9 to -43.2, 64.1 to -21.9, 0.0 to 179.9)) {
            val square = Fixtures.square(lat0, lng0, 50.0)
            assertTrue("$lat0,$lng0 inside", contains(square, at(10.0, -10.0, lat0, lng0)))
            assertFalse("$lat0,$lng0 outside", contains(square, at(60.0, 0.0, lat0, lng0)))
        }
    }

    @Test
    fun `distance to boundary`() {
        val square = Fixtures.square(lat, lng, 100.0)

        assertEquals(100.0, PolygonMath.distanceToBoundary(square, lat, lng), 0.5)
        at(90.0, 0.0).let { assertEquals(10.0, PolygonMath.distanceToBoundary(square, it.latitude, it.longitude), 0.5) }
        at(150.0, 0.0).let { assertEquals(50.0, PolygonMath.distanceToBoundary(square, it.latitude, it.longitude), 0.5) }
        // Nearest point is a corner.
        at(130.0, 140.0).let {
            assertEquals(sqrt(30.0 * 30 + 40.0 * 40), PolygonMath.distanceToBoundary(square, it.latitude, it.longitude), 0.5)
        }
    }

    @Test
    fun `haversine distance and area`() {
        assertEquals(111_195.0, PolygonMath.distanceMeters(0.0, 0.0, 1.0, 0.0), 5.0)
        assertEquals(0.0, PolygonMath.distanceMeters(lat, lng, lat, lng), 1e-9)
        assertEquals(40_000.0, PolygonMath.areaSquareMeters(Fixtures.square(lat, lng, 100.0)), 400.0)
        assertEquals(0.0, PolygonMath.areaSquareMeters(listOf(at(0.0, 0.0), at(10.0, 0.0), at(20.0, 0.0))), 1e-3)
    }

    @Test
    fun `lat lng validation`() {
        assertTrue(PolygonMath.isValidLatLng(90.0, -180.0))
        assertTrue(PolygonMath.isValidLatLng(-90.0, 180.0))
        assertFalse(PolygonMath.isValidLatLng(90.01, 0.0))
        assertFalse(PolygonMath.isValidLatLng(0.0, -180.01))
        assertFalse(PolygonMath.isValidLatLng(Double.NaN, 0.0))
        assertFalse(PolygonMath.isValidLatLng(0.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `minimal enclosing circle of a square is its circumcircle`() {
        val circle = PolygonMath.minimalEnclosingCircle(Fixtures.square(lat, lng, 100.0))

        assertEquals(0.0, PolygonMath.distanceMeters(lat, lng, circle.latitude, circle.longitude), 0.5)
        assertEquals(100.0 * sqrt(2.0), circle.radius, 0.5)
    }

    @Test
    fun `minimal enclosing circle of an obtuse triangle spans its longest side`() {
        val a = at(0.0, 0.0)
        val b = at(0.0, 400.0)
        val c = at(30.0, 200.0)

        val circle = PolygonMath.minimalEnclosingCircle(listOf(a, b, c))

        val mid = at(0.0, 200.0)
        assertEquals(0.0, PolygonMath.distanceMeters(mid.latitude, mid.longitude, circle.latitude, circle.longitude), 0.5)
        assertEquals(200.0, circle.radius, 0.5)
    }

    @Test
    fun `enclosing circle is padded by 10 percent with a 50 m minimum`() {
        val big = PolygonMath.enclosingCircle(Fixtures.square(lat, lng, 100.0))
        assertEquals(100.0 * sqrt(2.0) * 1.1, big.radius, 0.5)

        val tiny = PolygonMath.enclosingCircle(Fixtures.square(lat, lng, 5.0))
        assertEquals(50.0, tiny.radius, 1e-9)
    }

    @Test
    fun `enclosing circle contains every vertex of random polygons`() {
        val random = Random(7)
        repeat(200) { n ->
            val lat0 = random.nextDouble(-60.0, 60.0)
            val lng0 = random.nextDouble(-170.0, 170.0)
            val size = random.nextDouble(20.0, 20_000.0)
            val vertices = List(3 + random.nextInt(40)) {
                at(random.nextDouble(-size, size), random.nextDouble(-size, size), lat0, lng0)
            }

            val mec = PolygonMath.minimalEnclosingCircle(vertices)
            val padded = PolygonMath.enclosingCircle(vertices)

            val farthest = vertices.maxOf { PolygonMath.distanceMeters(mec.latitude, mec.longitude, it.latitude, it.longitude) }
            assertTrue("case $n: every vertex inside", farthest <= mec.radius + 1e-6)
            assertTrue("case $n: padded", padded.radius >= mec.radius * 1.1 - 1e-6 && padded.radius >= 50.0)
            // Minimal: no vertex pair is farther apart than the diameter, and the diameter is not much larger
            // than the widest pair (<= 2/sqrt(3) for any point set).
            val widest = vertices.maxOf { p ->
                vertices.maxOf { q -> PolygonMath.distanceMeters(p.latitude, p.longitude, q.latitude, q.longitude) }
            }
            assertTrue("case $n: minimal", 2 * mec.radius <= widest * 2 / sqrt(3.0) * 1.001 + 1e-6)
        }
    }

    @Test
    fun `enclosing circle handles collinear and duplicate points`() {
        val line = listOf(at(0.0, 0.0), at(0.0, 100.0), at(0.0, 200.0), at(0.0, 100.0), at(0.0, 0.0))

        val circle = PolygonMath.minimalEnclosingCircle(line)

        assertEquals(100.0, circle.radius, 0.5)
        val mid = at(0.0, 100.0)
        assertEquals(0.0, PolygonMath.distanceMeters(mid.latitude, mid.longitude, circle.latitude, circle.longitude), 0.5)
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
}
