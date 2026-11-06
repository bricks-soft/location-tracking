import XCTest
import CoreLocation
@testable import LocationTrackingPlugin

final class PositionTests: XCTestCase {
    func fix(_ accuracy: Double, time: Double = 1000) -> CLLocation {
        CLLocation(coordinate: CLLocationCoordinate2D(latitude: 24.7136, longitude: 46.6753), altitude: 0, horizontalAccuracy: accuracy, verticalAccuracy: -1, timestamp: Date(timeIntervalSince1970: time / 1000))
    }
    func testKeepsMostAccurateAndFinishesAtWantedCount() {
        let collector = SampleCollector(3)
        XCTAssertNil(collector.best)
        XCTAssertFalse(collector.add(fix(40)))
        XCTAssertFalse(collector.add(fix(15)))
        XCTAssertTrue(collector.add(fix(25)))
        XCTAssertEqual(collector.best?.horizontalAccuracy, 15)
        XCTAssertEqual(collector.count, 3)
    }
    func testFinishesEarlyAtTenMeters() {
        let collector = SampleCollector(5)
        XCTAssertFalse(collector.add(fix(10.5)))
        XCTAssertTrue(collector.add(fix(10)))
        XCTAssertTrue(collector.add(fix(30)))
        XCTAssertEqual(collector.best?.horizontalAccuracy, 10)
    }
    func testUnknownAccuracyRanksLast() {
        let collector = SampleCollector(4)
        XCTAssertFalse(collector.add(fix(0)))
        XCTAssertFalse(collector.add(fix(.nan)))
        XCTAssertEqual(collector.count, 2)
        XCTAssertFalse(collector.add(fix(80)))
        XCTAssertEqual(collector.best?.horizontalAccuracy, 80)
    }
    func testEqualAccuracyPrefersNewerFix() {
        let collector = SampleCollector(3)
        _ = collector.add(fix(20, time: 1000))
        let newer = fix(20, time: 2000)
        _ = collector.add(newer)
        XCTAssertTrue(collector.best === newer)
    }
    func testEqualAccuracyKeepsNewerWhenOlderArrivesLater() {
        let collector = SampleCollector(3)
        let newer = fix(20, time: 2000)
        _ = collector.add(newer)
        _ = collector.add(fix(20, time: 1000))
        XCTAssertTrue(collector.best === newer)
    }
    func testWantedBelowOneMeansSingleSample() {
        XCTAssertTrue(SampleCollector(0).add(fix(90)))
        XCTAssertTrue(SampleCollector(-3).add(fix(90)))
    }
    func testCacheAgeSurvivesWallClockMovingBack() {
        let cached = CachedPosition(fix(20, time: 1000), wall: Date(timeIntervalSince1970: 6), elapsed: 10000)
        XCTAssertEqual(cached.age(wall: Date(timeIntervalSince1970: 1), elapsed: 15000), 10000)
    }
    func testCacheAgeUsesLargerWallClockAge() {
        let cached = CachedPosition(fix(20, time: 1000), wall: Date(timeIntervalSince1970: 6), elapsed: 10000)
        XCTAssertEqual(cached.age(wall: Date(timeIntervalSince1970: 100), elapsed: 15000), 99000)
    }
}
