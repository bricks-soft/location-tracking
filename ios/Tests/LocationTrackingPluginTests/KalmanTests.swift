import XCTest
@testable import LocationTrackingPlugin

final class KalmanTests: XCTestCase {
    func testFirstFixEqualVarianceAndMetadata() {
        let kalman = KalmanFilter(); let first = EngineFixtures.location(accuracy: 10)
        XCTAssertEqual(kalman.filter(first), first)
        var second = EngineFixtures.moved(first, 20, east: 3, milliseconds: 0)
        second.altitude = 700; second.speed = 1.5; second.heading = 12; second.isMock = true
        let out = kalman.filter(second)
        XCTAssertEqual(out.latitude, (first.latitude + second.latitude) / 2, accuracy: 1e-12)
        XCTAssertEqual(out.longitude, (first.longitude + second.longitude) / 2, accuracy: 1e-12)
        var expected = second; expected.latitude = out.latitude; expected.longitude = out.longitude
        XCTAssertEqual(out, expected)
    }
    func testFastMovementAndSparseFixes() {
        for (speed, distance, milliseconds) in [(30.0, 30.0, 1000.0), (10, 100, 10000)] {
            let kalman = KalmanFilter(); let first = EngineFixtures.location(accuracy: speed == 30 ? 5 : 10, speed: speed)
            _ = kalman.filter(first); let second = EngineFixtures.moved(first, distance, milliseconds: milliseconds)
            XCTAssertLessThan(Geo.distance(kalman.filter(second), second), 1.5)
        }
    }
    func testMissingSpeedUsesThreeAndZeroIsFlooredAtOne() {
        for (speed, expected) in [(nil as Double?, 10.0 * 2 / 3), (0, 10.0 * 10 / 19)] {
            let kalman = KalmanFilter(); let first = EngineFixtures.location(accuracy: 3, speed: speed)
            _ = kalman.filter(first); let second = EngineFixtures.moved(first, 10)
            XCTAssertEqual(Geo.distance(first, kalman.filter(second)), expected, accuracy: 0.05)
        }
    }
    func testStationaryEstimateFollowsRealMovement() {
        let kalman = KalmanFilter(); var fix = EngineFixtures.location(accuracy: 10, speed: 0)
        for _ in 0..<300 { fix.time += 1000; _ = kalman.filter(fix) }
        let moved = EngineFixtures.moved(fix, 50); var out = moved
        for i in 0..<30 { var next = moved; next.time += Double(i * 1000); out = kalman.filter(next) }
        XCTAssertLessThan(Geo.distance(out, moved), 10)
    }
    func testAntimeridianWrap() {
        let kalman = KalmanFilter(); let east = EngineFixtures.location(latitude: 10, longitude: 179.9999)
        _ = kalman.filter(east); var west = east; west.longitude = -179.9991
        let out = kalman.filter(west)
        XCTAssertEqual(out.longitude, -179.9996, accuracy: 1e-9); XCTAssertLessThan(Geo.distance(out, east), 60)
        kalman.reset(); var equator = east; equator.latitude = 0
        _ = kalman.filter(equator); west = equator; west.longitude = -179.9999
        let halfway = kalman.filter(west)
        XCTAssertEqual(abs(halfway.longitude), 180, accuracy: 1e-9)
        XCTAssertEqual(Geo.distance(equator, halfway), Geo.distance(equator, west) / 2, accuracy: 0.01)
    }
    func testElapsedTimeAndGapBoundaries() {
        let first = EngineFixtures.location(speed: 0, elapsed: 5000)
        let kalman = KalmanFilter(); _ = kalman.filter(first)
        var second = EngineFixtures.moved(first, 20); second.time = first.time - 120000
        XCTAssertNotEqual(kalman.filter(second), second)
        var third = EngineFixtures.moved(second, 20, milliseconds: 61000); third.time = second.time + 1000
        XCTAssertEqual(kalman.filter(third), third)
        for gap in [-1000.0, 60000, 60001] {
            kalman.reset(); let start = EngineFixtures.location(speed: 0); _ = kalman.filter(start)
            let moved = EngineFixtures.moved(start, 50, milliseconds: gap)
            XCTAssertEqual(kalman.filter(moved) == moved, gap < 0 || gap > 60000)
        }
        kalman.reset(); XCTAssertEqual(kalman.filter(first), first)
    }
    func testZeroAccuracyAndSameTimeStayFinite() {
        let kalman = KalmanFilter(); let first = EngineFixtures.location(accuracy: 0, speed: 0)
        _ = kalman.filter(first); var same = first; same.latitude += 0.0001
        let out = kalman.filter(same)
        XCTAssertTrue(out.latitude.isFinite && out.longitude.isFinite)
        XCTAssertEqual(out.latitude, (first.latitude + same.latitude) / 2, accuracy: 1e-12)
    }
    func testAndroidSeed42NoisyTracks() {
        for (count, sigma, northSpeed, reportedSpeed, ratio) in [(180, 10.0, 0.0, 0 as Double?, 0.4), (600, 10, 1.2, 1.2, 0.9), (600, 15, 1, nil, 0.8)] {
            let kalman = KalmanFilter(), random = JavaFixtureRandom(seed: 42)
            let start = EngineFixtures.location(accuracy: sigma, speed: reportedSpeed)
            var raw = 0.0, smoothed = 0.0
            for i in 0..<count {
                let truth = EngineFixtures.moved(start, northSpeed * Double(i), milliseconds: Double(i * 1000))
                let fix = EngineFixtures.moved(start, northSpeed * Double(i) + random.gaussian() * sigma, east: random.gaussian() * sigma, milliseconds: Double(i * 1000))
                let out = kalman.filter(fix)
                if i >= 10 { raw += Geo.distance(truth, fix); smoothed += Geo.distance(truth, out) }
            }
            XCTAssertLessThan(smoothed, raw * ratio)
        }
    }
}

private final class JavaFixtureRandom {
    private var seed: UInt64
    private var cached: Double?
    init(seed: UInt64) { self.seed = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1) }
    private func bits(_ count: Int) -> UInt64 {
        seed = (seed &* 0x5DEECE66D &+ 0xB) & ((1 << 48) - 1)
        return seed >> (48 - count)
    }
    private func double() -> Double { Double((bits(26) << 27) + bits(27)) / 9007199254740992 }
    func gaussian() -> Double {
        if let cached { self.cached = nil; return cached }
        while true {
            let a = 2 * double() - 1, b = 2 * double() - 1, s = a * a + b * b
            if s >= 1 || s == 0 { continue }
            let factor = sqrt(-2 * log(s) / s)
            cached = b * factor
            return a * factor
        }
    }
}
