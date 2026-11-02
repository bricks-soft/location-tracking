import XCTest
import UIKit
@testable import LocationTrackingPlugin

final class ExecutionTests: XCTestCase {
    func testExpirationCompletionAndLateCompletionEndAssertionOnce() {
        var expiration: (() -> Void)?, ended: [Int] = []
        let assertion = ExecutionAssertion(begin: { expiration = $0; return 42 }, end: { ended.append($0) })
        assertion.start(seconds: 10, queue: .main)
        expiration?(); assertion.finish(); expiration?()
        XCTAssertEqual(ended, [42])
    }
    func testExpirationBeforeIdentifierAssignmentAndInvalidAssertion() {
        var ended: [Int] = []
        let assertion = ExecutionAssertion(begin: { $0(); return 42 }, end: { ended.append($0) })
        assertion.start(seconds: 10, queue: .main); assertion.finish()
        XCTAssertEqual(ended, [42])
        let invalid = ExecutionAssertion(begin: { _ in UIBackgroundTaskIdentifier.invalid.rawValue }, end: { _ in XCTFail("Invalid assertion must not be ended") })
        invalid.start(seconds: 1, queue: .main); invalid.finish()
    }
    func testKeepAwakeTimeLimitEndsAssertion() {
        let expired = expectation(description: "Assertion released at configured limit")
        let assertion = ExecutionAssertion(begin: { _ in 42 }, end: { XCTAssertEqual($0, 42); expired.fulfill() })
        assertion.start(seconds: 0.02, queue: .main)
        wait(for: [expired], timeout: 2)
        assertion.finish()
    }
    func testTimerReplacementCancellationAndOverdueBatch() {
        var now = 1000.0, fired: [String] = []
        let timers = TrackingTimers(queue: .main, now: { now }, automatic: false)
        timers.schedule("stop", after: 100) { fired.append("old") }
        timers.schedule("stop", after: 200) { fired.append("new") }
        timers.schedule("cancelled", after: 50) { XCTFail("Cancelled timer fired") }
        timers.cancel("cancelled")
        now = 1199; timers.fireDue(); XCTAssertTrue(fired.isEmpty)
        now = 1200; timers.fireDue(); XCTAssertEqual(fired, ["new"])
        timers.schedule("a", after: 100) { fired.append("a"); timers.cancel("b") }
        timers.schedule("b", after: 200) { XCTFail("Callback after cancellation") }
        now = 2000; timers.fireDue(); XCTAssertEqual(fired, ["new", "a"])
        timers.cancelAll()
    }
}
