import XCTest
import UserNotifications
@testable import LocationTrackingPlugin

@MainActor
final class CheckInTests: XCTestCase {
    var now = TrackingInstant(wall: 1790417730456, elapsed: 100000)
    private let center = FakeCheckInCenter()
    var notifier: CheckInNotifier!
    let settings: TrackingJSON = ["enabled": true, "delay": 120, "title": "Tracking check-in", "text": "Tap to resume"]
    override func setUp() { notifier = CheckInNotifier(queue: .main, clock: { [unowned self] in self.now }, center: center, appName: "Fixture app", warning: { _ in }) }
    override func tearDown() { notifier.cancel() }
    func settle() async { for _ in 0..<3 { await withCheckedContinuation { c in DispatchQueue.main.async { c.resume() } } } }
    func testSchedulesOneRequestMovesOncePerMinuteAndCancels() async throws {
        notifier.start(); notifier.touch(settings, deadlineElapsed: nil)
        await settle()
        XCTAssertEqual(center.requests.count, 1); XCTAssertEqual(center.cleared, 1)
        let first = try XCTUnwrap(center.pending)
        XCTAssertEqual(first.identifier, CheckInNotifier.identifier)
        XCTAssertEqual((first.trigger as? UNTimeIntervalNotificationTrigger)?.timeInterval, 120)
        XCTAssertEqual(first.content.title, "Tracking check-in")
        now.elapsed += 59999; notifier.touch(settings, deadlineElapsed: nil); await settle()
        XCTAssertEqual(center.requests.count, 1)
        now.elapsed += 1; notifier.touch(settings, deadlineElapsed: nil); await settle()
        XCTAssertEqual(center.requests.count, 2); XCTAssertNotNil(center.pending)
        notifier.cancel(); XCTAssertNil(center.pending)
    }
    func testBlankAndNullTitlesUseAppLabelLikeAndroid() async throws {
        for title in [NSNull(), "", " \n"] as [Any] {
            var options = settings; options["title"] = title
            notifier.touch(options, deadlineElapsed: nil, force: true); await settle()
            XCTAssertEqual(center.pending?.content.title, "Fixture app")
        }
    }
    func testNeverSchedulesPastDeadlineAndDisabledOrDeniedCancels() async {
        notifier.touch(settings, deadlineElapsed: now.elapsed + 119999); await settle()
        XCTAssertTrue(center.requests.isEmpty)
        notifier.touch(settings, deadlineElapsed: now.elapsed + 120000); await settle()
        XCTAssertEqual(center.requests.count, 1)
        notifier.touch(["enabled": false], deadlineElapsed: nil); XCTAssertNil(center.pending)
        center.allowed = false; notifier.touch(settings, deadlineElapsed: nil); await settle()
        XCTAssertEqual(center.requests.count, 1); XCTAssertNil(center.pending)
    }
    func testDisabledColdRestoreClearsStaleCheckIn() async throws {
        notifier.touch(settings, deadlineElapsed: nil); await settle()
        XCTAssertNotNil(center.pending)
        let core = try TrackingCore(queue: .main, permissions: Permissions(), positions: Positions(), emit: { _, _ in }, record: { _ in }, url: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("disabled.sqlite"), automaticTimers: false)
        core.engine.notifier = notifier
        try core.engine.restore(); await settle()
        XCTAssertNil(center.pending); XCTAssertFalse(core.engine.running)
        core.syncer.close()
    }
    func testCancelBeforeAuthorizationReturnsCannotSchedule() async {
        center.holdAuthorization = true
        notifier.touch(settings, deadlineElapsed: nil); notifier.cancel()
        center.authorization?(true); await settle()
        XCTAssertTrue(center.requests.isEmpty); XCTAssertNil(center.pending)
    }
    func testCancelWhileAddIsOutstandingRemovesLateRequest() async {
        center.holdAdd = true
        notifier.touch(settings, deadlineElapsed: nil); await settle()
        XCTAssertEqual(center.requests.count, 1)
        notifier.cancel(); center.finishAdd(); await settle()
        XCTAssertNil(center.pending)
    }
    func testConfigChangeDuringAddWinsAndDeadlineIsRecheckedAfterAuthorization() async {
        center.holdAdd = true
        notifier.touch(settings, deadlineElapsed: nil); await settle()
        var changed = settings; changed["title"] = "New title"
        notifier.touch(changed, deadlineElapsed: nil, force: true)
        center.holdAdd = false; center.finishAdd(); await settle()
        XCTAssertEqual(center.pending?.content.title, "New title")
        notifier.cancel(); center.holdAuthorization = true
        notifier.touch(settings, deadlineElapsed: now.elapsed + 121000)
        now.elapsed += 2000; center.authorization?(true); await settle()
        XCTAssertNil(center.pending)
    }
    func testShortenedDeadlineWhileAuthorizationIsHeldSchedulesNothing() async {
        center.holdAuthorization = true
        notifier.touch(settings, deadlineElapsed: now.elapsed + 600000)
        notifier.touch(settings, deadlineElapsed: now.elapsed + 60000)
        center.authorization?(true); await settle()
        XCTAssertTrue(center.requests.isEmpty); XCTAssertNil(center.pending)
    }
    func testExtendedDeadlineWhileAuthorizationIsHeldStillSchedulesOnce() async {
        center.holdAuthorization = true
        notifier.touch(settings, deadlineElapsed: now.elapsed + 300000)
        notifier.touch(settings, deadlineElapsed: now.elapsed + 600000)
        center.holdAuthorization = false; center.authorization?(true); await settle()
        XCTAssertEqual(center.requests.count, 1); XCTAssertNotNil(center.pending)
    }
    func testShortenedDeadlineWhileAddIsHeldRemovesTheObsoleteRequest() async {
        center.holdAdd = true
        notifier.touch(settings, deadlineElapsed: now.elapsed + 600000); await settle()
        XCTAssertEqual(center.requests.count, 1)
        notifier.touch(settings, deadlineElapsed: now.elapsed + 60000)
        center.finishAdd(); await settle()
        XCTAssertEqual(center.requests.count, 1); XCTAssertNil(center.pending)
    }
}

private final class FakeCheckInCenter: CheckInCenter {
    var allowed = true
    var holdAuthorization = false
    var holdAdd = false
    var authorization: ((Bool) -> Void)?
    var addCompletion: ((Error?) -> Void)?
    var requests: [UNNotificationRequest] = []
    var pending: UNNotificationRequest?
    var cleared = 0
    func authorized(_ completion: @escaping (Bool) -> Void) { if holdAuthorization { authorization = completion } else { completion(allowed) } }
    func add(_ request: UNNotificationRequest, completion: @escaping (Error?) -> Void) {
        requests.append(request)
        if holdAdd { addCompletion = completion }
        else { pending = request; completion(nil) }
    }
    func finishAdd() { pending = requests.last; let callback = addCompletion; addCompletion = nil; callback?(nil) }
    func cancel() { pending = nil }
    func clearDelivered() { cleared += 1 }
}
