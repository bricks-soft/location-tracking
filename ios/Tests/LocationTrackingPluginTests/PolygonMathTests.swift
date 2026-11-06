import XCTest
@testable import LocationTrackingPlugin

enum GeofenceFixtures {
    static let lat = 24.7136, lng = 46.6753
    static let metersPerDegree = 111320.0
    static func at(_ north: Double, _ east: Double, _ lat0: Double = lat, _ lng0: Double = lng) -> LatLng {
        LatLng(latitude: lat0 + north / metersPerDegree, longitude: lng0 + east / (metersPerDegree * cos(lat0 * .pi / 180)))
    }
    static func square(_ latitude: Double, _ longitude: Double, _ half: Double) -> [LatLng] {
        let dLat = half / metersPerDegree, dLng = half / (metersPerDegree * cos(latitude * .pi / 180))
        return [LatLng(latitude: latitude - dLat, longitude: longitude - dLng), LatLng(latitude: latitude - dLat, longitude: longitude + dLng), LatLng(latitude: latitude + dLat, longitude: longitude + dLng), LatLng(latitude: latitude + dLat, longitude: longitude - dLng)]
    }
    static func circle(_ identifier: String = "home", latitude: Double = lat, longitude: Double = lng, radius: Double = 100, notifyOnEntry: Bool = true, notifyOnExit: Bool = true, notifyOnDwell: Bool = false, loiteringDelay: Double = 30000, extras: TrackingJSON? = nil) -> GeofenceSpec {
        GeofenceSpec(identifier: identifier, latitude: latitude, longitude: longitude, radius: radius, notifyOnEntry: notifyOnEntry, notifyOnExit: notifyOnExit, notifyOnDwell: notifyOnDwell, loiteringDelay: loiteringDelay, extras: extras)
    }
    static func polygon(_ identifier: String = "zone", vertices: [LatLng] = square(lat, lng, 100), notifyOnEntry: Bool = true, notifyOnExit: Bool = true, notifyOnDwell: Bool = false, loiteringDelay: Double = 30000, extras: TrackingJSON? = nil) -> GeofenceSpec {
        GeofenceSpec(identifier: identifier, latitude: 0, longitude: 0, radius: 0, vertices: vertices, notifyOnEntry: notifyOnEntry, notifyOnExit: notifyOnExit, notifyOnDwell: notifyOnDwell, loiteringDelay: loiteringDelay, extras: extras)
    }
}

final class PolygonMathTests: XCTestCase {
    let lat = GeofenceFixtures.lat, lng = GeofenceFixtures.lng
    func at(_ north: Double, _ east: Double, _ lat0: Double? = nil, _ lng0: Double? = nil) -> LatLng { GeofenceFixtures.at(north, east, lat0 ?? lat, lng0 ?? lng) }
    func contains(_ vertices: [LatLng], _ p: LatLng) -> Bool { PolygonMath.contains(vertices, p.latitude, p.longitude) }

    func testSquareContainsItsCentreAndNotPointsOutside() {
        let square = GeofenceFixtures.square(lat, lng, 100)
        XCTAssertTrue(contains(square, at(0, 0)))
        XCTAssertTrue(contains(square, at(95, -95)))
        XCTAssertFalse(contains(square, at(105, 0)))
        XCTAssertFalse(contains(square, at(0, -105)))
        XCTAssertFalse(contains(square, at(500, 500)))
    }
    func testConcaveUShapedPolygonExcludesTheNotch() {
        let u = [at(0, 0), at(0, 300), at(300, 300), at(300, 200), at(100, 200), at(100, 100), at(300, 100), at(300, 0)]
        XCTAssertTrue(contains(u, at(250, 50)), "left arm")
        XCTAssertTrue(contains(u, at(250, 250)), "right arm")
        XCTAssertTrue(contains(u, at(50, 150)), "base")
        XCTAssertFalse(contains(u, at(250, 150)), "notch")
        XCTAssertFalse(contains(u, at(150, 150)), "notch bottom")
        XCTAssertFalse(contains(u, at(-10, 150)), "outside")
    }
    func testWindingOrderAndARepeatedClosingVertexDoNotMatter() {
        let triangle = [at(0, 0), at(0, 200), at(200, 0)]
        for vertices in [triangle, Array((triangle + [triangle[0]]).reversed())] {
            XCTAssertTrue(contains(vertices, at(50, 50)))
            XCTAssertFalse(contains(vertices, at(150, 150)))
        }
    }
    func testWorksInEveryQuadrantOfTheNormalRange() {
        for (lat0, lng0) in [(-33.86, 151.21), (51.5, -0.12), (-22.9, -43.2), (64.1, -21.9), (0.0, 179.9)] {
            let square = GeofenceFixtures.square(lat0, lng0, 50)
            XCTAssertTrue(contains(square, at(10, -10, lat0, lng0)), "\(lat0),\(lng0) inside")
            XCTAssertFalse(contains(square, at(60, 0, lat0, lng0)), "\(lat0),\(lng0) outside")
        }
    }
    func testDistanceToBoundary() {
        let square = GeofenceFixtures.square(lat, lng, 100)
        XCTAssertEqual(PolygonMath.distanceToBoundary(square, lat, lng), 100, accuracy: 0.5)
        var p = at(90, 0); XCTAssertEqual(PolygonMath.distanceToBoundary(square, p.latitude, p.longitude), 10, accuracy: 0.5)
        p = at(150, 0); XCTAssertEqual(PolygonMath.distanceToBoundary(square, p.latitude, p.longitude), 50, accuracy: 0.5)
        p = at(130, 140); XCTAssertEqual(PolygonMath.distanceToBoundary(square, p.latitude, p.longitude), sqrt(30.0 * 30 + 40.0 * 40), accuracy: 0.5)
    }
    func testHaversineDistanceAndArea() {
        XCTAssertEqual(PolygonMath.distanceMeters(0, 0, 1, 0), 111195, accuracy: 5)
        XCTAssertEqual(PolygonMath.distanceMeters(lat, lng, lat, lng), 0, accuracy: 1e-9)
        XCTAssertEqual(PolygonMath.areaSquareMeters(GeofenceFixtures.square(lat, lng, 100)), 40000, accuracy: 400)
        XCTAssertEqual(PolygonMath.areaSquareMeters([at(0, 0), at(10, 0), at(20, 0)]), 0, accuracy: 1e-3)
    }
    func testLatLngValidation() {
        XCTAssertTrue(PolygonMath.isValidLatLng(90, -180))
        XCTAssertTrue(PolygonMath.isValidLatLng(-90, 180))
        XCTAssertFalse(PolygonMath.isValidLatLng(90.01, 0))
        XCTAssertFalse(PolygonMath.isValidLatLng(0, -180.01))
        XCTAssertFalse(PolygonMath.isValidLatLng(.nan, 0))
        XCTAssertFalse(PolygonMath.isValidLatLng(0, .infinity))
    }
    func testMinimalEnclosingCircleOfASquareIsItsCircumcircle() {
        let circle = PolygonMath.minimalEnclosingCircle(GeofenceFixtures.square(lat, lng, 100))
        XCTAssertEqual(PolygonMath.distanceMeters(lat, lng, circle.latitude, circle.longitude), 0, accuracy: 0.5)
        XCTAssertEqual(circle.radius, 100 * sqrt(2.0), accuracy: 0.5)
    }
    func testMinimalEnclosingCircleOfAnObtuseTriangleSpansItsLongestSide() {
        let circle = PolygonMath.minimalEnclosingCircle([at(0, 0), at(0, 400), at(30, 200)])
        let mid = at(0, 200)
        XCTAssertEqual(PolygonMath.distanceMeters(mid.latitude, mid.longitude, circle.latitude, circle.longitude), 0, accuracy: 0.5)
        XCTAssertEqual(circle.radius, 200, accuracy: 0.5)
    }
    func testEnclosingCircleIsPaddedByTenPercentWithA50MetreMinimum() {
        XCTAssertEqual(PolygonMath.enclosingCircle(GeofenceFixtures.square(lat, lng, 100)).radius, 100 * sqrt(2.0) * 1.1, accuracy: 0.5)
        XCTAssertEqual(PolygonMath.enclosingCircle(GeofenceFixtures.square(lat, lng, 5)).radius, 50, accuracy: 1e-9)
    }
    func testEnclosingCircleContainsEveryVertexOfRandomPolygons() {
        var random = XorWowRandom(seed: 7)
        for n in 0..<200 {
            let lat0 = random.nextDouble(-60, 60), lng0 = random.nextDouble(-170, 170), size = random.nextDouble(20, 20000)
            let count = 3 + Int(random.nextInt(40))
            var vertices: [LatLng] = []
            for _ in 0..<count { let north = random.nextDouble(-size, size), east = random.nextDouble(-size, size); vertices.append(at(north, east, lat0, lng0)) }
            let mec = PolygonMath.minimalEnclosingCircle(vertices), padded = PolygonMath.enclosingCircle(vertices)
            let farthest = vertices.map { PolygonMath.distanceMeters(mec.latitude, mec.longitude, $0.latitude, $0.longitude) }.max()!
            XCTAssertLessThanOrEqual(farthest, mec.radius + 1e-6, "case \(n): every vertex inside")
            XCTAssertTrue(padded.radius >= mec.radius * 1.1 - 1e-6 && padded.radius >= 50, "case \(n): padded")
            let widest = vertices.map { p in vertices.map { q in PolygonMath.distanceMeters(p.latitude, p.longitude, q.latitude, q.longitude) }.max()! }.max()!
            XCTAssertLessThanOrEqual(2 * mec.radius, widest * 2 / sqrt(3.0) * 1.001 + 1e-6, "case \(n): minimal")
        }
    }
    func testEnclosingCircleHandlesCollinearAndDuplicatePoints() {
        let circle = PolygonMath.minimalEnclosingCircle([at(0, 0), at(0, 100), at(0, 200), at(0, 100), at(0, 0)])
        XCTAssertEqual(circle.radius, 100, accuracy: 0.5)
        let mid = at(0, 100)
        XCTAssertEqual(PolygonMath.distanceMeters(mid.latitude, mid.longitude, circle.latitude, circle.longitude), 0, accuracy: 0.5)
    }
    func testXorWowMatchesKotlinRandom() {
        var random = XorWowRandom(seed: 7)
        XCTAssertEqual((0..<3).map { _ in random.nextInt() }, [-182312124, 11901178, -1941452650])
        var draws = XorWowRandom(seed: 7)
        XCTAssertEqual(draws.nextDouble(-60, 60), 54.906257991977256)
        XCTAssertEqual(draws.nextDouble(-170, 170), 16.30991230413383)
        XCTAssertEqual(draws.nextDouble(20, 20000), 7281.137658497918)
        XCTAssertEqual(draws.nextInt(40), 12)
        var shuffle = XorWowRandom(seed: 4)
        XCTAssertEqual(shuffle.shuffled([0, 1, 2, 3]), [2, 1, 3, 0])
    }
}

final class DwellTrackerTests: XCTestCase {
    var now = 1790417730456.0
    var fired: [(String, Double)] = []
    func tracker() -> DwellTracker { DwellTracker(queue: .main, now: { [unowned self] in self.now }, automatic: false) { [unowned self] in self.fired.append(($0, $1)) } }
    func advance(_ dwell: DwellTracker, _ ms: Double) { now += ms; dwell.fireDue() }
    func ids() -> [String] { fired.map { $0.0 + ":" + String(Int($0.1)) } }

    func testFiresOnceAtTheDeadlineWithItsToken() {
        let dwell = tracker()
        dwell.arm("a", deadline: now + 30000, token: 7)
        advance(dwell, 29999)
        XCTAssertTrue(fired.isEmpty); XCTAssertTrue(dwell.isArmed("a"))
        advance(dwell, 1)
        XCTAssertEqual(ids(), ["a:7"]); XCTAssertFalse(dwell.isArmed("a")); XCTAssertFalse(dwell.hasArmed)
        advance(dwell, 120000)
        XCTAssertEqual(fired.count, 1)
    }
    func testCancelPreventsFiring() {
        let dwell = tracker()
        dwell.arm("a", deadline: now + 1000, token: 1); dwell.arm("b", deadline: now + 1000, token: 2)
        dwell.cancel("a"); advance(dwell, 5000)
        XCTAssertEqual(ids(), ["b:2"])
    }
    func testCancelAllPreventsEveryTimer() {
        let dwell = tracker()
        dwell.arm("a", deadline: now + 1000, token: 1); dwell.arm("b", deadline: now + 2000, token: 2)
        dwell.cancelAll(); advance(dwell, 5000)
        XCTAssertTrue(fired.isEmpty); XCTAssertFalse(dwell.hasArmed)
    }
    func testReArmingReplacesThePreviousTimer() {
        let dwell = tracker()
        dwell.arm("a", deadline: now + 1000, token: 1); dwell.arm("a", deadline: now + 3000, token: 2)
        advance(dwell, 2000); XCTAssertTrue(fired.isEmpty)
        advance(dwell, 1000); XCTAssertEqual(ids(), ["a:2"])
    }
    func testADeadlineInThePastFiresImmediately() {
        let dwell = tracker()
        dwell.arm("a", deadline: now - 10000, token: 3); dwell.fireDue()
        XCTAssertEqual(ids(), ["a:3"])
    }
    func testTakeDueReturnsOverdueTimersExactlyOnceAndDisarmsThem() {
        let dwell = tracker(), start = now
        dwell.arm("a", deadline: start + 1000, token: 1); dwell.arm("b", deadline: start + 60000, token: 2)
        now += 2000
        XCTAssertEqual(dwell.takeDue().map { $0.0 + ":" + String(Int($0.1)) }, ["a:1"])
        XCTAssertTrue(dwell.takeDue().isEmpty)
        XCTAssertTrue(dwell.isArmed("b"))
        advance(dwell, 1000)
        XCTAssertTrue(fired.isEmpty, "the timer of a was disarmed")
    }
}
