import XCTest
@testable import LocationTrackingPlugin

final class OdometerTests: XCTestCase {
    var runtime: TrackingJSON = ["odometer": 0.0]
    var threshold = 20.0
    let odometer = Odometer()
    func add(_ fix: TrackedLocation, to target: Odometer? = nil) {
        let delta = (target ?? odometer).onLocation(fix, runtime: runtime, threshold: threshold)
        if delta > 0 { runtime["odometer"] = runtime.number("odometer") + delta }
    }
    func testFirstFixAndMeridianPath() {
        for i in 0...10 { add(EngineFixtures.location(latitude: Double(i) * 0.001, longitude: 0, accuracy: 5, time: Double(i * 1000))) }
        XCTAssertEqual(runtime.number("odometer"), 1111.95, accuracy: 11.1195)
    }
    func testSquareWalk() {
        var fix = EngineFixtures.location(accuracy: 4); add(fix)
        XCTAssertEqual(runtime.number("odometer"), 0)
        for (north, east) in [(25.0, 0.0), (0, 25), (-25, 0), (0, -25)] {
            for _ in 0..<10 { fix = EngineFixtures.moved(fix, north, east: east); add(fix) }
        }
        XCTAssertEqual(runtime.number("odometer"), 1000, accuracy: 10)
    }
    func testAccuracySkipsKeepAnchorAndThresholdIsLive() {
        let a = EngineFixtures.location(accuracy: 5); add(a)
        var bad = EngineFixtures.moved(a, 500); bad.accuracy = 20.5
        add(bad); bad.accuracy = .nan; add(bad)
        XCTAssertEqual(runtime.number("odometer"), 0)
        let c = EngineFixtures.moved(a, 100, milliseconds: 2000); add(c)
        XCTAssertEqual(runtime.number("odometer"), Geo.distance(a, c), accuracy: 1e-9)
        runtime["odometer"] = 0.0; odometer.reset(session: nil)
        threshold = 10; let coarse = EngineFixtures.location(accuracy: 30), next = EngineFixtures.moved(coarse, 50)
        add(coarse); add(next); XCTAssertEqual(runtime.number("odometer"), 0)
        threshold = 50; add(coarse); add(next)
        XCTAssertEqual(runtime.number("odometer"), Geo.distance(coarse, next), accuracy: 1e-9)
    }
    func testAccuracyEqualCountsSetKeepsAnchorAndSamePositionAddsNothing() {
        let a = EngineFixtures.location(accuracy: 20), b = EngineFixtures.moved(EngineFixtures.location(accuracy: 20), 50)
        add(a); runtime["odometer"] = 250.0; add(b)
        XCTAssertEqual(runtime.number("odometer"), 250 + Geo.distance(a, b), accuracy: 1e-9)
        let before = runtime.number("odometer"); var same = b; same.time += 1000; add(same)
        XCTAssertEqual(runtime.number("odometer"), before)
    }
    func testNewSessionUsesOnlyItsOwnStartPosition() {
        let home = EngineFixtures.location(accuracy: 5, time: 2000)
        runtime["enabled"] = true; runtime["trackingStartedAt"] = 1000.0
        add(home); add(EngineFixtures.moved(home, 100)); let before = runtime.number("odometer")
        let start = EngineFixtures.moved(home, 50000, milliseconds: 9000000)
        runtime["trackingStartedAt"] = start.time; runtime["lastLocation"] = start.json
        let moving = EngineFixtures.moved(start, 100); add(moving)
        XCTAssertEqual(runtime.number("odometer"), before + Geo.distance(start, moving), accuracy: 1e-9)
        runtime["trackingStartedAt"] = 19000000.0
        let far = EngineFixtures.moved(home, 90000, milliseconds: 19500000); let previous = runtime.number("odometer"); add(far)
        XCTAssertEqual(runtime.number("odometer"), previous)
    }
    func testNewSessionWithoutStartPositionDoesNotCountInterSessionTravel() {
        let home = EngineFixtures.location(accuracy: 5)
        runtime["enabled"] = true; runtime["trackingStartedAt"] = 1000.0
        add(home); add(EngineFixtures.moved(home, 100)); let before = runtime.number("odometer")
        runtime["enabled"] = false; runtime.removeValue(forKey: "trackingStartedAt")
        runtime["enabled"] = true; runtime["trackingStartedAt"] = 9000000.0
        let far = EngineFixtures.moved(home, 50000, milliseconds: 3600000); add(far)
        XCTAssertEqual(runtime.number("odometer"), before)
        let next = EngineFixtures.moved(far, 30); add(next)
        XCTAssertEqual(runtime.number("odometer"), before + Geo.distance(far, next), accuracy: 1e-9)
    }
    func testColdRestoreSeedsOnceAndSetKeepsRestoredAnchor() {
        let last = EngineFixtures.location(accuracy: 5, time: 2000000)
        runtime = ["enabled": true, "trackingStartedAt": 1000000.0, "lastLocation": last.json, "odometer": 500.0]
        let next = EngineFixtures.moved(last, 800, milliseconds: 120000)
        add(next)
        XCTAssertEqual(runtime.number("odometer"), 500 + Geo.distance(last, next), accuracy: 1e-9)
        runtime["lastLocation"] = EngineFixtures.moved(last, 5000).json
        let after = runtime.number("odometer"), moved = EngineFixtures.moved(next, 10); add(moved)
        XCTAssertEqual(runtime.number("odometer"), after + Geo.distance(next, moved), accuracy: 1e-9)
        runtime["odometer"] = 40.0; runtime["lastLocation"] = last.json
        let cold = Odometer(); let hundred = EngineFixtures.moved(last, 100); add(hundred, to: cold)
        XCTAssertEqual(runtime.number("odometer"), 40 + Geo.distance(last, hundred), accuracy: 1e-9)
    }
    func testSameSessionAnchorSurvivesABackwardClockCorrection() {
        let started = 2000000.0, last = EngineFixtures.location(accuracy: 5, time: started - 600000, elapsed: 5000)
        runtime = ["enabled": true, "trackingStartedAt": started, "lastLocation": last.json, "lastLocationSession": started, "odometer": 100.0]
        let next = EngineFixtures.moved(last, 100); add(next)
        XCTAssertEqual(runtime.number("odometer"), 100 + Geo.distance(last, next), accuracy: 1e-9)
        runtime = ["enabled": true, "trackingStartedAt": started, "lastLocation": last.json, "lastLocationSession": started - 1, "odometer": 100.0]
        add(EngineFixtures.moved(last, 100), to: Odometer())
        XCTAssertEqual(runtime.number("odometer"), 100)
    }
    func testInvalidRestoreAnchorsAndReset() {
        let last = EngineFixtures.location(accuracy: 5, time: 2000000)
        for (enabled, started, accuracy) in [(true, 3000000.0 as Double?, 5.0), (false, 1000000, 5), (true, nil, 5), (true, 1000000, 50)] {
            var seed = last; seed.accuracy = accuracy
            runtime = ["enabled": enabled, "lastLocation": seed.json, "odometer": 0.0]
            runtime["trackingStartedAt"] = started
            add(EngineFixtures.moved(last, 800), to: Odometer())
            XCTAssertEqual(runtime.number("odometer"), 0)
        }
        runtime = ["enabled": true, "trackingStartedAt": 1000000.0, "lastLocation": last.json, "odometer": 500.0]
        odometer.reset(session: 1000000); runtime["odometer"] = 0.0
        let next = EngineFixtures.moved(last, 800); add(next); XCTAssertEqual(runtime.number("odometer"), 0)
        let after = EngineFixtures.moved(next, 40); add(after)
        XCTAssertEqual(runtime.number("odometer"), Geo.distance(next, after), accuracy: 1e-9)
    }
    func testFutureRestoreAnchorIsNotUsed() {
        let fix = EngineFixtures.location(accuracy: 5, time: 2000000)
        runtime = ["enabled": true, "trackingStartedAt": 1000000.0, "lastLocation": EngineFixtures.moved(fix, 300, milliseconds: 5000).json, "odometer": 0.0]
        add(fix); XCTAssertEqual(runtime.number("odometer"), 0)
        let next = EngineFixtures.moved(fix, 20); add(next)
        XCTAssertEqual(runtime.number("odometer"), Geo.distance(fix, next), accuracy: 1e-9)
    }
}
