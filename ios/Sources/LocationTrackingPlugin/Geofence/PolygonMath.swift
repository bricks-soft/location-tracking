import Foundation

struct LatLng: Equatable, Hashable {
    var latitude: Double
    var longitude: Double
}

struct XorWowRandom {
    private var x: Int32, y: Int32, z: Int32, w: Int32, v: Int32, addend: Int32
    init(seed: Int32) {
        x = seed; y = seed >> 31; z = 0; w = 0; v = ~seed
        addend = (seed << 10) ^ Int32(bitPattern: UInt32(bitPattern: seed >> 31) >> 4)
        for _ in 0..<64 { _ = nextInt() }
    }
    mutating func nextInt() -> Int32 {
        var t = x
        t = t ^ Int32(bitPattern: UInt32(bitPattern: t) >> 2)
        x = y; y = z; z = w
        let v0 = v
        w = v0
        t = (t ^ (t << 1)) ^ v0 ^ (v0 << 4)
        v = t
        addend = addend &+ 362437
        return t &+ addend
    }
    mutating func nextBits(_ count: Int32) -> Int32 {
        Int32(bitPattern: UInt32(bitPattern: nextInt()) >> UInt32(32 - count)) & ((-count) >> 31)
    }
    mutating func nextInt(_ until: Int32) -> Int32 {
        if until & -until == until { return nextBits(Int32(31 - until.leadingZeroBitCount)) }
        while true {
            let bits = Int32(bitPattern: UInt32(bitPattern: nextInt()) >> 1)
            let value = bits % until
            if bits &- value &+ (until - 1) >= 0 { return value }
        }
    }
    mutating func nextDouble() -> Double {
        let high = Int64(nextBits(26)), low = Int64(nextBits(27))
        return Double((high << 27) + low) / Double(Int64(1) << 53)
    }
    mutating func nextDouble(_ from: Double, _ until: Double) -> Double {
        let value = from + nextDouble() * (until - from)
        return value >= until ? until.nextDown : value
    }
    mutating func shuffled<T>(_ values: [T]) -> [T] {
        var result = values
        for index in stride(from: result.count - 1, through: 1, by: -1) { result.swapAt(index, Int(nextInt(Int32(index + 1)))) }
        return result
    }
}

enum PolygonMath {
    static let earthRadius = 6371008.8
    static let padding = 1.1
    static let minimumEnclosingRadius = 50.0
    private static let epsilon = 1e-6

    struct Circle: Equatable {
        var latitude: Double
        var longitude: Double
        var radius: Double
    }

    static func isValidLatLng(_ latitude: Double, _ longitude: Double) -> Bool {
        latitude.isFinite && longitude.isFinite && (-90...90).contains(latitude) && (-180...180).contains(longitude)
    }

    static func distanceMeters(_ lat1: Double, _ lng1: Double, _ lat2: Double, _ lng2: Double) -> Double {
        let phi1 = lat1 * .pi / 180, phi2 = lat2 * .pi / 180
        let dPhi = phi2 - phi1, dLambda = (lng2 - lng1) * .pi / 180
        let a = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * earthRadius * asin(min(1, sqrt(a)))
    }

    static func contains(_ vertices: [LatLng], _ latitude: Double, _ longitude: Double) -> Bool {
        var inside = false
        var j = vertices.count - 1
        for i in vertices.indices {
            let yi = vertices[i].latitude, xi = vertices[i].longitude, yj = vertices[j].latitude, xj = vertices[j].longitude
            if (yi > latitude) != (yj > latitude) && longitude < (xj - xi) * (latitude - yi) / (yj - yi) + xi { inside.toggle() }
            j = i
        }
        return inside
    }

    static func distanceToBoundary(_ vertices: [LatLng], _ latitude: Double, _ longitude: Double) -> Double {
        guard !vertices.isEmpty else { return .infinity }
        let projection = Projection(latitude, longitude)
        let points = vertices.map { projection.point($0.latitude, $0.longitude) }
        if points.count == 1 { return hypot(points[0].x, points[0].y) }
        var best = Double.infinity
        var j = points.count - 1
        for i in points.indices {
            best = min(best, distanceToSegment(points[j], points[i]))
            j = i
        }
        return best
    }

    static func areaSquareMeters(_ vertices: [LatLng]) -> Double {
        guard vertices.count >= 3 else { return 0 }
        let projection = Projection.around(vertices)
        let points = vertices.map { projection.point($0.latitude, $0.longitude) }
        var sum = 0.0
        var j = points.count - 1
        for i in points.indices {
            sum += points[j].x * points[i].y - points[i].x * points[j].y
            j = i
        }
        return abs(sum) / 2
    }

    static func minimalEnclosingCircle(_ vertices: [LatLng]) -> Circle {
        precondition(!vertices.isEmpty)
        let projection = Projection.around(vertices)
        var random = XorWowRandom(seed: Int32(vertices.count))
        let points = random.shuffled(vertices.map { projection.point($0.latitude, $0.longitude) })
        var c = PlaneCircle(x: points[0].x, y: points[0].y, r: 0)
        for i in points.indices where !c.contains(points[i]) {
            c = PlaneCircle(x: points[i].x, y: points[i].y, r: 0)
            for j in 0..<i where !c.contains(points[j]) {
                c = PlaneCircle.diameter(points[i], points[j])
                for k in 0..<j where !c.contains(points[k]) { c = PlaneCircle.through(points[i], points[j], points[k]) }
            }
        }
        let center = projection.latLng(c.x, c.y)
        let radius = vertices.map { distanceMeters(center.latitude, center.longitude, $0.latitude, $0.longitude) }.max() ?? 0
        return Circle(latitude: center.latitude, longitude: center.longitude, radius: radius)
    }

    static func enclosingCircle(_ vertices: [LatLng]) -> Circle {
        var circle = minimalEnclosingCircle(vertices)
        circle.radius = max(circle.radius * padding, minimumEnclosingRadius)
        return circle
    }

    private static func distanceToSegment(_ a: Point, _ b: Point) -> Double {
        let dx = b.x - a.x, dy = b.y - a.y
        let lengthSquared = dx * dx + dy * dy
        if lengthSquared == 0 { return hypot(a.x, a.y) }
        let t = min(1, max(0, (-a.x * dx - a.y * dy) / lengthSquared))
        return hypot(a.x + t * dx, a.y + t * dy)
    }

    private struct Point { var x: Double; var y: Double }

    private struct Projection {
        let lat0: Double, lng0: Double, cosLat0: Double
        init(_ lat0: Double, _ lng0: Double) { self.lat0 = lat0; self.lng0 = lng0; cosLat0 = max(cos(lat0 * .pi / 180), 1e-9) }
        func point(_ latitude: Double, _ longitude: Double) -> Point {
            Point(x: (longitude - lng0) * .pi / 180 * cosLat0 * PolygonMath.earthRadius, y: (latitude - lat0) * .pi / 180 * PolygonMath.earthRadius)
        }
        func latLng(_ x: Double, _ y: Double) -> LatLng {
            LatLng(latitude: lat0 + y / PolygonMath.earthRadius * 180 / .pi, longitude: lng0 + x / (PolygonMath.earthRadius * cosLat0) * 180 / .pi)
        }
        static func around(_ vertices: [LatLng]) -> Projection {
            let latitudes = vertices.map(\.latitude), longitudes = vertices.map(\.longitude)
            return Projection((latitudes.min()! + latitudes.max()!) / 2, (longitudes.min()! + longitudes.max()!) / 2)
        }
    }

    private struct PlaneCircle {
        var x: Double, y: Double, r: Double
        func contains(_ p: Point) -> Bool { hypot(p.x - x, p.y - y) <= r + PolygonMath.epsilon + r * 1e-12 }
        static func diameter(_ a: Point, _ b: Point) -> PlaneCircle { PlaneCircle(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, r: hypot(a.x - b.x, a.y - b.y) / 2) }
        static func through(_ a: Point, _ b: Point, _ c: Point) -> PlaneCircle {
            let bx = b.x - a.x, by = b.y - a.y, cx = c.x - a.x, cy = c.y - a.y
            let d = 2 * (bx * cy - by * cx)
            let scale = max(bx * bx + by * by, cx * cx + cy * cy)
            if abs(d) <= 1e-12 * max(scale, 1e-12) { return [diameter(a, b), diameter(a, c), diameter(b, c)].max { $0.r < $1.r }! }
            let b2 = bx * bx + by * by, c2 = cx * cx + cy * cy
            let ux = (cy * b2 - by * c2) / d, uy = (bx * c2 - cx * b2) / d
            return PlaneCircle(x: a.x + ux, y: a.y + uy, r: hypot(ux, uy))
        }
    }
}
