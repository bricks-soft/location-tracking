import XCTest
@testable import LocationTrackingPlugin

final class ProcessingTests: XCTestCase {
    func accepted(_ result: FilterResult, file: StaticString = #filePath, line: UInt = #line) -> TrackedLocation {
        if case .accepted(let fix) = result { return fix }
        XCTFail("Expected accepted fix", file: file, line: line)
        return EngineFixtures.location()
    }
    func rejected(_ result: FilterResult, _ reason: String, file: StaticString = #filePath, line: UInt = #line) {
        if case .rejected(let text) = result { XCTAssertTrue(text.contains(reason), text, file: file, line: line) }
        else { XCTFail("Expected rejection: " + reason, file: file, line: line) }
    }
    func testElasticDistanceAndroidFixtures() {
        for (distance, speed, multiplier, disabled, expected) in [(10.0, 13.4, 1.0, false, 30.0), (10, 12.5, 1, false, 30), (10, 10, 2, false, 40), (5, 25, 1, false, 25), (10, 2.4, 1, false, 10), (10, 10, 0.25, false, 10), (10, 40, 0, false, 10), (10, -20, 1, false, 10), (10, .nan, 1, false, 10), (10, 40, 3, true, 10), (-5, 40, 1, true, 0)] {
            XCTAssertEqual(ElasticDistance.threshold(distance, speed, multiplier, disabled), expected, accuracy: 1e-9)
        }
        XCTAssertEqual(ElasticDistance.threshold(10, nil, 1, false), 10)
        let last = EngineFixtures.location()
        XCTAssertTrue(ElasticDistance.shouldRecord(nil, last, [:]))
        XCTAssertFalse(ElasticDistance.shouldRecord(last, EngineFixtures.moved(last, 25), [:]))
        XCTAssertTrue(ElasticDistance.shouldRecord(last, EngineFixtures.moved(last, 35), [:]))
        var slow = EngineFixtures.moved(last, 12); slow.speed = 1
        XCTAssertTrue(ElasticDistance.shouldRecord(last, slow, [:]))
        XCTAssertTrue(ElasticDistance.shouldRecord(last, EngineFixtures.moved(last, 12), ["disableElasticity": true]))
    }
    func testGeoDistanceAndInvalidAccuracy() {
        XCTAssertEqual(Geo.distance(0, 0, 1, 0), 111195, accuracy: 1)
        XCTAssertEqual(Geo.distance(0, 0, 0, 1), 111195, accuracy: 1)
        XCTAssertEqual(Geo.distance(24.7, 46.6, 24.7, 46.6), 0)
        let a = EngineFixtures.location(), b = EngineFixtures.moved(a, 300, east: 400)
        XCTAssertEqual(Geo.distance(a, b), Geo.distance(b, a), accuracy: 1e-9)
        XCTAssertEqual(Geo.distance(a, b), 500, accuracy: 2)
        for (accuracy, expected) in [(5.0, 5.0), (-1, 0), (.nan, 0)] { XCTAssertEqual(EngineFixtures.location(accuracy: accuracy).accuracyMeters, expected) }
    }
    func testFilterAccuracyBoundariesAndLiveConfig() {
        let processor = LocationProcessor()
        for accuracy in [100.5, .nan, .infinity] { rejected(processor.process(EngineFixtures.location(accuracy: accuracy), filter: [:]), "accuracy") }
        XCTAssertEqual(accepted(processor.process(EngineFixtures.location(accuracy: 100), filter: [:])).accuracy, 100)
        processor.reset()
        let fix = EngineFixtures.location(accuracy: 30)
        rejected(processor.process(fix, filter: ["trackingAccuracyThreshold": 20]), "accuracy")
        XCTAssertEqual(accepted(processor.process(fix, filter: ["trackingAccuracyThreshold": 50])), fix)
        var rejectedFix = fix; rejectedFix.time += 1000; rejectedFix.accuracy = 500
        rejected(processor.process(rejectedFix, filter: [:]), "accuracy")
        rejected(processor.process(fix, filter: [:]), "identical")
    }
    func testInvalidCoordinatesAndZeroGlitch() {
        let processor = LocationProcessor()
        for fix in [EngineFixtures.location(latitude: 91), EngineFixtures.location(longitude: -181), EngineFixtures.location(latitude: .nan), EngineFixtures.location(latitude: 0, longitude: 0)] { rejected(processor.process(fix, filter: [:]), "invalid coordinates") }
        _ = accepted(processor.process(EngineFixtures.location(latitude: 0, longitude: 0.001, time: 1000), filter: [:]))
        _ = accepted(processor.process(EngineFixtures.location(latitude: 0.001, longitude: 0, time: 10000), filter: [:]))
    }
    func testMockIdenticalAndStaleFixtures() {
        let processor = LocationProcessor(); var fix = EngineFixtures.location(); fix.isMock = true
        rejected(processor.process(fix, filter: ["rejectMockLocations": true]), "mock")
        XCTAssertTrue(accepted(processor.process(fix, filter: [:])).isMock)
        var same = fix; same.accuracy = 3
        rejected(processor.process(same, filter: [:]), "identical")
        XCTAssertEqual(accepted(processor.process(fix, filter: ["allowIdenticalLocations": true])), fix)
        same.time -= 1
        rejected(processor.process(same, filter: ["allowIdenticalLocations": true, "maxImpliedSpeed": 0]), "stale")
        same = fix; same.time += 1000; _ = accepted(processor.process(same, filter: [:]))
        _ = accepted(processor.process(EngineFixtures.moved(same, 500, milliseconds: 0), filter: [:]))
    }
    func testImpliedSpeedFromLastAcceptedFixAndLongGapRecovery() {
        let processor = LocationProcessor(); let first = EngineFixtures.location()
        _ = accepted(processor.process(first, filter: [:]))
        rejected(processor.process(EngineFixtures.moved(first, 1000), filter: [:]), "implied speed")
        _ = accepted(processor.process(EngineFixtures.moved(first, 79), filter: [:]))
        processor.reset(); _ = accepted(processor.process(first, filter: ["maxImpliedSpeed": 30]))
        rejected(processor.process(EngineFixtures.moved(first, 40), filter: ["maxImpliedSpeed": 30]), "implied speed")
        _ = accepted(processor.process(EngineFixtures.moved(first, 100000), filter: ["maxImpliedSpeed": 0]))
        processor.reset(); _ = accepted(processor.process(first, filter: [:]))
        rejected(processor.process(EngineFixtures.moved(first, 5000), filter: [:]), "implied speed")
        _ = accepted(processor.process(EngineFixtures.moved(first, 150, milliseconds: 2000), filter: [:]))
        _ = accepted(processor.process(EngineFixtures.moved(first, 5000, milliseconds: 70000), filter: [:]))
        rejected(processor.process(first, filter: [:]), "stale")
    }
    func testElapsedPreferredAndMissingElapsedFallsBackToWall() {
        let processor = LocationProcessor(); let first = EngineFixtures.location(elapsed: 5000)
        _ = accepted(processor.process(first, filter: [:]))
        var next = EngineFixtures.moved(first, 20); next.time = first.time - 120000
        _ = accepted(processor.process(next, filter: [:]))
        var jump = EngineFixtures.moved(next, 1000); jump.time = next.time + 100000
        rejected(processor.process(jump, filter: [:]), "implied speed")
        processor.reset(); _ = accepted(processor.process(first, filter: [:]))
        next = EngineFixtures.moved(first, 1000, milliseconds: 100000); next.elapsed = nil
        _ = accepted(processor.process(next, filter: [:]))
    }
    func testKalmanOnlyChangesCoordinatesAndRawFixesAreCompared() {
        let processor = LocationProcessor(); let first = EngineFixtures.location(speed: 0)
        XCTAssertEqual(accepted(processor.process(first, filter: ["useKalman": true])), first)
        var second = EngineFixtures.moved(first, 20, milliseconds: 0); second.altitude = 1; second.heading = 90
        let out = accepted(processor.process(second, filter: ["useKalman": true]))
        XCTAssertEqual(out.latitude, (first.latitude + second.latitude) / 2, accuracy: 1e-12)
        var expected = second; expected.latitude = out.latitude; expected.longitude = out.longitude
        XCTAssertEqual(out, expected)
        rejected(processor.process(second, filter: ["useKalman": true]), "identical")
        let third = EngineFixtures.moved(second, 20)
        XCTAssertEqual(accepted(processor.process(third, filter: [:])), third)
        let fourth = EngineFixtures.moved(third, 20)
        XCTAssertEqual(accepted(processor.process(fourth, filter: ["useKalman": true])), fourth)
        processor.reset()
        XCTAssertEqual(accepted(processor.process(first, filter: ["useKalman": true])), first)
        processor.reset(); let far = EngineFixtures.moved(first, 10000)
        XCTAssertEqual(accepted(processor.process(far, filter: ["useKalman": true])), far)
    }
    func testProcessorConcurrentAndroidFixture() {
        let processor = LocationProcessor(), group = DispatchGroup(), lock = NSLock()
        var errors = 0
        let base = EngineFixtures.location(speed: 1)
        for thread in 0..<8 {
            group.enter()
            DispatchQueue.global().async {
                for i in 0..<500 {
                    let fix = EngineFixtures.moved(base, Double(i), east: Double(thread), milliseconds: Double(i * 100))
                    let result = processor.process(fix, filter: ["useKalman": true, "allowIdenticalLocations": true, "maxImpliedSpeed": 0])
                    var invalid = false
                    switch result {
                    case .accepted(let out): invalid = !out.latitude.isFinite || !out.longitude.isFinite
                    case .rejected(let reason): invalid = !reason.hasPrefix("stale")
                    }
                    if invalid { lock.lock(); errors += 1; lock.unlock() }
                    if i % 100 == 0 { processor.reset() }
                }
                group.leave()
            }
        }
        XCTAssertEqual(group.wait(timeout: .now() + 30), .success); XCTAssertEqual(errors, 0)
    }
}
