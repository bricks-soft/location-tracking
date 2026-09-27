package com.brickssoft.locationtracking.geofence

import com.brickssoft.locationtracking.model.LatLng
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Geometry for polygon geofences: ray-cast point-in-polygon, distance to the boundary and the enclosing circle
 * that is registered with the OS.
 *
 * Coordinates are in the normal range (latitude -90..90, longitude -180..180); polygons that cross the
 * anti-meridian are not supported. Planar work uses a local equirectangular projection, which is accurate for
 * geofence-sized shapes (up to tens of kilometres).
 */
internal object PolygonMath {
    /** Mean Earth radius (m). */
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Padding factor applied to the minimal enclosing circle. */
    const val PADDING = 1.1

    /** Smallest radius (m) of a padded enclosing circle. */
    const val MIN_ENCLOSING_RADIUS_M = 50.0

    private const val METERS_PER_RADIAN = EARTH_RADIUS_M
    private const val EPSILON_M = 1e-6

    /** A circle; [radius] in meters. */
    data class Circle(val latitude: Double, val longitude: Double, val radius: Double)

    fun isValidLatLng(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

    /** Great-circle (haversine) distance in meters. */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = phi2 - phi1
        val dLambda = Math.toRadians(lng2 - lng1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * Even-odd ray casting in the latitude/longitude plane. A repeated closing vertex is harmless. Points exactly
     * on an edge may be classified either way; callers apply hysteresis around the boundary.
     */
    fun contains(vertices: List<LatLng>, latitude: Double, longitude: Double): Boolean {
        var inside = false
        var j = vertices.size - 1
        for (i in vertices.indices) {
            val yi = vertices[i].latitude
            val xi = vertices[i].longitude
            val yj = vertices[j].latitude
            val xj = vertices[j].longitude
            if ((yi > latitude) != (yj > latitude) && longitude < (xj - xi) * (latitude - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Shortest distance (m) from the point to any edge of the polygon (including the closing edge). */
    fun distanceToBoundary(vertices: List<LatLng>, latitude: Double, longitude: Double): Double {
        if (vertices.isEmpty()) return Double.POSITIVE_INFINITY
        val projection = Projection(latitude, longitude)
        val points = vertices.map { projection.toPoint(it.latitude, it.longitude) }
        if (points.size == 1) return hypot(points[0].x, points[0].y)
        var best = Double.POSITIVE_INFINITY
        var j = points.size - 1
        for (i in points.indices) {
            best = min(best, distanceToSegment(points[j], points[i]))
            j = i
        }
        return best
    }

    /** Unsigned planar area (m²) of the polygon (shoelace formula in a local projection). */
    fun areaSquareMeters(vertices: List<LatLng>): Double {
        if (vertices.size < 3) return 0.0
        val projection = Projection.around(vertices)
        val points = vertices.map { projection.toPoint(it.latitude, it.longitude) }
        var sum = 0.0
        var j = points.size - 1
        for (i in points.indices) {
            sum += points[j].x * points[i].y - points[i].x * points[j].y
            j = i
        }
        return abs(sum) / 2
    }

    /**
     * Minimal enclosing circle of [vertices] (randomized incremental Welzl algorithm, deterministic seed). The
     * radius is the largest great-circle distance from the centre to a vertex, so every vertex is inside.
     */
    fun minimalEnclosingCircle(vertices: List<LatLng>): Circle {
        require(vertices.isNotEmpty()) { "no vertices" }
        val projection = Projection.around(vertices)
        val points = vertices.map { projection.toPoint(it.latitude, it.longitude) }.shuffled(Random(vertices.size))
        var c = PlaneCircle(points[0].x, points[0].y, 0.0)
        for (i in points.indices) {
            if (c.contains(points[i])) continue
            c = PlaneCircle(points[i].x, points[i].y, 0.0)
            for (j in 0 until i) {
                if (c.contains(points[j])) continue
                c = PlaneCircle.diameter(points[i], points[j])
                for (k in 0 until j) {
                    if (!c.contains(points[k])) c = PlaneCircle.through(points[i], points[j], points[k])
                }
            }
        }
        val center = projection.toLatLng(c.x, c.y)
        val radius = vertices.maxOf { distanceMeters(center.latitude, center.longitude, it.latitude, it.longitude) }
        return Circle(center.latitude, center.longitude, radius)
    }

    /** The circle registered with the OS for a polygon: the minimal enclosing circle padded by 10 %, at least 50 m. */
    fun enclosingCircle(vertices: List<LatLng>): Circle {
        val mec = minimalEnclosingCircle(vertices)
        return mec.copy(radius = max(mec.radius * PADDING, MIN_ENCLOSING_RADIUS_M))
    }

    private fun distanceToSegment(a: Point, b: Point): Double {
        // Distance from the origin (the query point) to segment ab.
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return hypot(a.x, a.y)
        val t = ((-a.x) * dx + (-a.y) * dy) / lengthSquared
        val clamped = t.coerceIn(0.0, 1.0)
        return hypot(a.x + clamped * dx, a.y + clamped * dy)
    }

    private data class Point(val x: Double, val y: Double)

    /** Local equirectangular projection around (lat0, lng0); x east and y north, in meters. */
    private class Projection(private val lat0: Double, private val lng0: Double) {
        private val cosLat0 = cos(Math.toRadians(lat0)).coerceAtLeast(1e-9)

        fun toPoint(latitude: Double, longitude: Double) = Point(
            x = Math.toRadians(longitude - lng0) * cosLat0 * METERS_PER_RADIAN,
            y = Math.toRadians(latitude - lat0) * METERS_PER_RADIAN,
        )

        fun toLatLng(x: Double, y: Double) = LatLng(
            latitude = lat0 + Math.toDegrees(y / METERS_PER_RADIAN),
            longitude = lng0 + Math.toDegrees(x / (METERS_PER_RADIAN * cosLat0)),
        )

        companion object {
            /** Centred on the bounding box of [vertices]. */
            fun around(vertices: List<LatLng>): Projection {
                val lat = (vertices.minOf { it.latitude } + vertices.maxOf { it.latitude }) / 2
                val lng = (vertices.minOf { it.longitude } + vertices.maxOf { it.longitude }) / 2
                return Projection(lat, lng)
            }
        }
    }

    private data class PlaneCircle(val x: Double, val y: Double, val r: Double) {
        fun contains(p: Point): Boolean = hypot(p.x - x, p.y - y) <= r + EPSILON_M + r * 1e-12

        companion object {
            fun diameter(a: Point, b: Point) =
                PlaneCircle((a.x + b.x) / 2, (a.y + b.y) / 2, hypot(a.x - b.x, a.y - b.y) / 2)

            /** Circumcircle of three points; for (nearly) collinear points, the circle over the farthest pair. */
            fun through(a: Point, b: Point, c: Point): PlaneCircle {
                val bx = b.x - a.x
                val by = b.y - a.y
                val cx = c.x - a.x
                val cy = c.y - a.y
                val d = 2 * (bx * cy - by * cx)
                val scale = max(bx * bx + by * by, cx * cx + cy * cy)
                if (abs(d) <= 1e-12 * max(scale, 1e-12)) {
                    return listOf(diameter(a, b), diameter(a, c), diameter(b, c)).maxBy { it.r }
                }
                val b2 = bx * bx + by * by
                val c2 = cx * cx + cy * cy
                val ux = (cy * b2 - by * c2) / d
                val uy = (bx * c2 - cx * b2) / d
                return PlaneCircle(a.x + ux, a.y + uy, hypot(ux, uy))
            }
        }
    }
}
