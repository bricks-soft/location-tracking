import XCTest
import CoreLocation
@testable import LocationTrackingPlugin

@MainActor
final class RouteSourceTests: XCTestCase {
    func testLastLocationReadsOnlyTheDelegateCacheAndKeepsTheNewestValidFix() {
        let manager = NoSynchronousLocationManager(), relaunch = NoSynchronousLocationManager()
        let source = RouteSource(manager: manager, relaunch: relaunch)
        var callbacks = 0
        source.locations = { _, _ in callbacks += 1 }
        XCTAssertNil(source.lastLocation)
        let first = EngineFixtures.location(time: 1000), latest = EngineFixtures.location(latitude: 24.8, time: 2000)
        source.locationManager(manager, didUpdateLocations: [latest.location, first.location])
        XCTAssertEqual(source.lastLocation, latest)
        source.locationManager(manager, didUpdateLocations: [first.location])
        XCTAssertEqual(source.lastLocation, latest)
        source.locationManager(relaunch, didUpdateLocations: [EngineFixtures.location(time: 3000).location])
        XCTAssertEqual(source.lastLocation, latest)
        let bad = CLLocation(coordinate: CLLocationCoordinate2D(latitude: 91, longitude: 46), altitude: 0, horizontalAccuracy: 5, verticalAccuracy: -1, timestamp: Date(timeIntervalSince1970: 4))
        source.locationManager(manager, didUpdateLocations: [bad])
        XCTAssertEqual(source.lastLocation, latest)
        XCTAssertEqual(callbacks, 0)
    }
    func testBatchAcrossABackwardClockChangeKeepsOccurrenceOrder() {
        var now = TrackingInstant(wall: 998000, elapsed: 98000)
        let manager = NoSynchronousLocationManager(), relaunch = NoSynchronousLocationManager()
        let source = RouteSource(manager: manager, relaunch: relaunch, clock: { now })
        var delivered: [TrackedLocation] = []
        source.apply(RouteProfile(enabled: true, moving: true, accuracy: "high", activityEnabled: false))
        source.locations = { fixes, _ in delivered = fixes }
        source.locationManager(manager, didUpdateLocations: [EngineFixtures.location(time: 997500).location])
        XCTAssertEqual(delivered.count, 1); XCTAssertEqual(delivered.first?.elapsed ?? 0, 97500, accuracy: 0.01)
        now = TrackingInstant(wall: 402000, elapsed: 102000)
        let old = EngineFixtures.location(latitude: 24.7, time: 999000), fresh = EngineFixtures.location(latitude: 24.8, time: 401900)
        source.locationManager(manager, didUpdateLocations: [old.location, fresh.location])
        XCTAssertEqual(delivered.map(\.latitude), [old.latitude, fresh.latitude])
        XCTAssertEqual(delivered.count, 2)
        XCTAssertEqual(delivered.first?.elapsed ?? 0, 99000, accuracy: 0.01); XCTAssertEqual(delivered.last?.elapsed ?? 0, 101900, accuracy: 0.01)
        XCTAssertEqual(source.lastLocation?.latitude, fresh.latitude)
    }
    func testFutureStampedFixWithoutAKnownTimebaseIsNotTreatedAsFresh() {
        let now = TrackingInstant(wall: 402000, elapsed: 102000)
        let manager = NoSynchronousLocationManager(), relaunch = NoSynchronousLocationManager()
        let source = RouteSource(manager: manager, relaunch: relaunch, clock: { now })
        var delivered: [TrackedLocation] = []
        source.apply(RouteProfile(enabled: true, moving: true, accuracy: "high", activityEnabled: false))
        source.locations = { fixes, _ in delivered = fixes }
        let old = EngineFixtures.location(latitude: 24.7, time: 999000), fresh = EngineFixtures.location(latitude: 24.8, time: 401900)
        source.locationManager(manager, didUpdateLocations: [old.location, fresh.location])
        XCTAssertEqual(delivered.map(\.latitude), [fresh.latitude])
        XCTAssertEqual(delivered.first?.elapsed ?? 0, 101900, accuracy: 0.01)
        source.locationManager(manager, didUpdateLocations: [old.location])
        XCTAssertEqual(source.lastLocation?.latitude, fresh.latitude)
        XCTAssertNil(TrackedLocation(old.location, receivedAt: now).elapsed)
    }
}

private final class NoSynchronousLocationManager: CLLocationManager {
    override var location: CLLocation? { XCTFail("The route source must not synchronously query Core Location"); return nil }
}
