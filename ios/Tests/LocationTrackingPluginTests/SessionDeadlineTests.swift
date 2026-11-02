import XCTest
@testable import LocationTrackingPlugin

final class SessionDeadlineTests: XCTestCase {
    let start = TrackingInstant(wall: 1790417730456, elapsed: 86400123)
    func testElapsedDeadlineSurvivesWallClockMovingThreeHoursBack() {
        let deadline = SessionDeadline(now: start)
        var now = start; now.wall -= 3 * 3600000
        now.elapsed += 59 * 60000; now.wall += 59 * 60000
        XCTAssertEqual(deadline.remaining(minutes: 60, now: now), 60000)
        now.elapsed += 60000
        XCTAssertEqual(deadline.remaining(minutes: 60, now: now), 0)
    }
    func testRestorePreservesStartAndRemainingWindow() {
        let now = TrackingInstant(wall: start.wall + 20 * 60000, elapsed: start.elapsed + 20 * 60000)
        let restored = SessionDeadline(startedAt: start.wall, startedElapsed: start.elapsed, checkpoint: start, now: now)
        XCTAssertEqual(restored.startedAt, start.wall); XCTAssertEqual(restored.startedElapsed, start.elapsed)
        XCTAssertEqual(restored.remaining(minutes: 60, now: now), 40 * 60000)
    }
    func testRebootUsesOriginalWallStartThenElapsedClock() {
        let now = TrackingInstant(wall: start.wall + 61 * 60000, elapsed: 60000)
        let restored = SessionDeadline(startedAt: start.wall, startedElapsed: start.elapsed, checkpoint: start, now: now)
        XCTAssertEqual(restored.remaining(minutes: 60, now: now), -60000)
        var changed = now; changed.wall -= 3600000; changed.elapsed += 1000
        XCTAssertEqual(restored.remaining(minutes: 60, now: changed), -61000)
    }
    func testRebootDetectedEvenWhenNewUptimeExceedsPrevious() {
        let now = TrackingInstant(wall: start.wall + 2 * 86400000, elapsed: start.elapsed + 1000)
        XCTAssertFalse(now.continues(start))
        let restored = SessionDeadline(startedAt: start.wall, startedElapsed: start.elapsed, checkpoint: start, now: now)
        XCTAssertLessThan(restored.remaining(minutes: 60, now: now)!, 0)
    }
    func testModeSwitchStartsNewWindowAndConfigChangeUsesOriginalStart() {
        let now = TrackingInstant(wall: start.wall + 35 * 60000, elapsed: start.elapsed + 35 * 60000)
        let original = SessionDeadline(now: start), switched = SessionDeadline(now: now)
        XCTAssertEqual(original.remaining(minutes: 30, now: now), -5 * 60000)
        XCTAssertEqual(switched.remaining(minutes: 60, now: now), 60 * 60000)
        XCTAssertNil(original.remaining(minutes: 0, now: now))
    }
    func testMissingCheckpointAndClockChangeDuringProcessLossUseWallFallback() {
        let now = TrackingInstant(wall: start.wall + 3600000, elapsed: start.elapsed + 1000)
        for checkpoint in [nil, start] {
            let restored = SessionDeadline(startedAt: start.wall, startedElapsed: start.elapsed, checkpoint: checkpoint, now: now)
            XCTAssertEqual(restored.remaining(minutes: 60, now: now), 0)
        }
    }
    func testUpdatedCheckpointPreservesElapsedAfterClockChangeAndProcessLoss() {
        let checkpoint = TrackingInstant(wall: start.wall - 3 * 3600000 + 1000, elapsed: start.elapsed + 1000)
        let now = TrackingInstant(wall: checkpoint.wall + 2000, elapsed: checkpoint.elapsed + 2000)
        let restored = SessionDeadline(startedAt: start.wall, startedElapsed: start.elapsed, checkpoint: checkpoint, now: now)
        XCTAssertEqual(restored.remaining(minutes: 60, now: now), 3600000 - 3000)
    }
}
