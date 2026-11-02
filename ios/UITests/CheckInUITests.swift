import XCTest
import CoreLocation

final class CheckInUITests: XCTestCase {
    override func tearDown() {
        let app = XCUIApplication()
        app.launchArguments = ["--ios-stage2-scenario"]
        app.launch()
        let alert = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch
        if alert.exists { alert.buttons.firstMatch.tap() }
        if app.buttons["Stop tracking"].waitForExistence(timeout: 15) {
            app.buttons["Stop tracking"].tap()
            _ = app.staticTexts["Harness cleared"].waitForExistence(timeout: 15)
        }
        app.terminate()
        if #available(iOS 16.4, *) { XCUIDevice.shared.location = nil }
        super.tearDown()
    }
    func testNotificationFiresAfterKillAndTapRestoresOriginalSessionDeadline() {
        continueAfterFailure = false
        if #available(iOS 16.4, *) { XCUIDevice.shared.location = XCUILocation(location: CLLocation(latitude: 24.7136, longitude: 46.6753)) }
        let app = XCUIApplication()
        app.launchArguments = ["--ios-stage2-scenario"]
        app.launch(); app.terminate(); app.resetAuthorizationStatus(for: .location); app.launch()
        XCTAssertTrue(app.buttons["Start check-in"].waitForExistence(timeout: 30))
        app.buttons["Start check-in"].tap()
        let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let deadline = Date().addingTimeInterval(40)
        while !app.staticTexts["State: enabled"].exists && Date() < deadline {
            let alert = system.alerts.firstMatch
            if alert.waitForExistence(timeout: 1) {
                let whileUsing = alert.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'while using'")).firstMatch
                let allow = alert.buttons.matching(NSPredicate(format: "label == 'Allow' OR label == 'OK'")).firstMatch
                if whileUsing.exists { whileUsing.tap() }
                else if allow.exists { allow.tap() }
                else { XCTFail(alert.debugDescription); return }
            }
        }
        XCTAssertTrue(app.staticTexts["State: enabled"].exists)
        XCTAssertTrue(app.staticTexts["Check-in pending: true"].waitForExistence(timeout: 15))
        let start = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Session start:'")).firstMatch.label
        let elapsedStart = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Elapsed start:'")).firstMatch.label
        XCTAssertFalse(start.hasSuffix(": 0"))
        XCTAssertFalse(elapsedStart.hasSuffix(": 0"))
        app.terminate(); XCUIDevice.shared.press(.home)
        system.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.01)).press(forDuration: 0.1, thenDragTo: system.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7)))
        let title = "Location tracking test check-in"
        func delivered() -> XCUIElement { system.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR (identifier == 'NotificationCell' AND label CONTAINS %@)", title, title)).firstMatch }
        func notification() -> XCUIElement {
            let text = system.staticTexts.matching(NSPredicate(format: "label == %@", title)).firstMatch
            return text.exists ? text : system.buttons.matching(NSPredicate(format: "identifier == 'NotificationCell' AND label CONTAINS %@", title)).firstMatch
        }
        let deliveryDeadline = Date().addingTimeInterval(160)
        while Date() < deliveryDeadline {
            if delivered().waitForExistence(timeout: min(15, max(0, deliveryDeadline.timeIntervalSinceNow))) { break }
            system.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.45)).tap()
        }
        XCTAssertTrue(delivered().exists, system.debugDescription)
        let fired = XCTAttachment(screenshot: system.screenshot()); fired.name = "check-in-after-kill"; fired.lifetime = .keepAlways; add(fired)
        XCTAssertEqual(app.state, .notRunning)
        notification().tap()
        if !app.wait(for: .runningForeground, timeout: 3), notification().exists {
            notification().swipeRight()
            let open = system.buttons["Open"]
            if open.waitForExistence(timeout: 5) { open.tap() }
        }
        let opened = app.wait(for: .runningForeground, timeout: 30)
        if !opened {
            let screen = XCTAttachment(screenshot: system.screenshot()); screen.name = "check-in-tap-state"; screen.lifetime = .keepAlways; add(screen)
        }
        XCTAssertTrue(opened)
        XCTAssertTrue(app.staticTexts["Last start reason: restore"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts[start].exists)
        XCTAssertTrue(app.staticTexts[elapsedStart].exists)
        XCTAssertTrue(app.staticTexts["Delivered: 0"].waitForExistence(timeout: 10))
        let restored = XCTAttachment(screenshot: app.screenshot()); restored.name = "restored-original-deadline"; restored.lifetime = .keepAlways; add(restored)
        app.buttons["Stop tracking"].tap()
        XCTAssertTrue(app.staticTexts["State: disabled"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Check-in pending: false"].waitForExistence(timeout: 10))
    }
}
