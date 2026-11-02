import XCTest
@testable import LocationTrackingPlugin

final class HeartbeatWindowTests: XCTestCase {
    let now = 1790417730456.0, elapsed = 86400123.0
    func window(at: Double? = nil, recordElapsed: Double? = nil, sameBoot: Bool? = true, minimum: Double = 180, maximum: Double = 300, enabled: Bool = true, anchor: Double? = nil, attempt: Double? = nil, started: Double? = nil) -> HeartbeatWindow {
        HeartbeatWindow.compute(lastRecordAt: at ?? now - 100000, lastRecordElapsed: recordElapsed ?? elapsed - 100000, sameBoot: sameBoot, now: now, nowElapsed: elapsed, minInterval: minimum, maxInterval: maximum, enabled: enabled, noRecordBaseElapsed: anchor, lastAttemptElapsed: attempt, trackingStartedAt: started)
    }
    func testSameBootElapsedClockWinsWallClockChanges() {
        let w = window(at: now - 3600000)
        XCTAssertEqual(w.baseElapsed, elapsed - 100000); XCTAssertEqual(w.dueElapsed, elapsed + 80000)
        XCTAssertEqual(w.deadlineElapsed, elapsed + 200000); XCTAssertEqual(w.nextHeartbeatAt, now + 80000)
    }
    func testRebootMissingElapsedAndUnknownBootFixtures() {
        XCTAssertEqual(window(recordElapsed: 5000000, sameBoot: false).baseElapsed, elapsed - 100000)
        let overdue = window(at: now - 86400000, recordElapsed: 1000, sameBoot: false)
        XCTAssertTrue(overdue.shouldFire(elapsed)); XCTAssertEqual(overdue.nextHeartbeatAt, now)
        XCTAssertEqual(window(sameBoot: nil).baseElapsed, elapsed - 100000)
        XCTAssertEqual(window(at: now - 50000, recordElapsed: elapsed + 1000000, sameBoot: nil).baseElapsed, elapsed - 50000)
        let missing = HeartbeatWindow.compute(lastRecordAt: now - 30000, lastRecordElapsed: nil, sameBoot: nil, now: now, nowElapsed: elapsed)
        XCTAssertEqual(missing.baseElapsed, elapsed - 30000)
    }
    func testNoRecordUsesNowOrStableAnchorAndFutureIsClamped() {
        let w = HeartbeatWindow.compute(lastRecordAt: nil, lastRecordElapsed: nil, sameBoot: nil, now: now, nowElapsed: elapsed)
        XCTAssertEqual(w.baseElapsed, elapsed); XCTAssertFalse(w.shouldFire(elapsed))
        let anchored = HeartbeatWindow.compute(lastRecordAt: nil, lastRecordElapsed: nil, sameBoot: nil, now: now, nowElapsed: elapsed, noRecordBaseElapsed: elapsed - 180000)
        XCTAssertTrue(anchored.shouldFire(elapsed))
        XCTAssertEqual(window(at: now + 60000, recordElapsed: elapsed + 60000).baseElapsed, elapsed)
    }
    func testHeartbeat179SecondsVersus180Seconds() {
        let w = window(at: now, recordElapsed: elapsed)
        XCTAssertFalse(w.shouldFire(elapsed + 178999))
        XCTAssertTrue(w.shouldFire(elapsed + 179000))
        XCTAssertTrue(w.shouldFire(elapsed + 180000))
        XCTAssertTrue(w.shouldFire(elapsed + 900000))
    }
    func testAttemptSpacingFloorsOrderingAndDisabled() {
        let w = window(at: now - 400000, recordElapsed: elapsed - 400000, attempt: elapsed)
        XCTAssertEqual(w.dueElapsed, elapsed + 180000); XCTAssertFalse(w.shouldFire(elapsed + 10000))
        XCTAssertTrue(w.shouldFire(elapsed + 180000))
        let floor = window(at: now, recordElapsed: elapsed, minimum: 10, maximum: 5)
        XCTAssertEqual(floor.minInterval, 60000); XCTAssertEqual(floor.dueElapsed, elapsed + 60000); XCTAssertEqual(floor.deadlineElapsed, elapsed + 60000)
        XCTAssertNil(window(enabled: false).nextHeartbeatAt)
    }
    func testSessionStartClampsOnlyRecordsFromEarlierSessions() {
        let recent = window(at: now - 5 * 3600000, recordElapsed: elapsed - 5 * 3600000, started: now - 10000)
        XCTAssertEqual(recent.baseElapsed, elapsed - 10000); XCTAssertEqual(recent.dueElapsed, elapsed + 170000)
        XCTAssertFalse(recent.shouldFire(elapsed))
        XCTAssertEqual(window(started: now - 3600000).baseElapsed, elapsed - 100000)
        let future = window(at: now - 400000, recordElapsed: elapsed - 400000, started: now + 60000)
        XCTAssertEqual(future.baseElapsed, elapsed - 400000); XCTAssertTrue(future.shouldFire(elapsed))
    }
    func testRearmThresholdAndEveryEarlierMove() {
        let armed = window()
        XCTAssertTrue(armed.needsRearm(nil)); XCTAssertFalse(armed.needsRearm(armed))
        for (move, expected) in [(29999.0, false), (30000, true), (120000, true), (-1, true)] {
            XCTAssertEqual(window(recordElapsed: elapsed - 100000 + move).needsRearm(armed), expected)
        }
        XCTAssertTrue(window(minimum: 179).needsRearm(armed)); XCTAssertTrue(window(enabled: false).needsRearm(armed))
    }
}
