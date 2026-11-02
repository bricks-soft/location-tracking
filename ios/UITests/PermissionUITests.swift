import XCTest
import CoreLocation

final class PermissionUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
        if #available(iOS 16.4, *) { XCUIDevice.shared.location = XCUILocation(location: CLLocation(latitude: 24.7136, longitude: 46.6753)) }
    }
    override func tearDown() {
        let app = XCUIApplication()
        let systemAlert = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch
        let alert = systemAlert.exists ? systemAlert : app.alerts.firstMatch
        if alert.exists { alert.buttons.firstMatch.tap() }
        app.terminate()
        if #available(iOS 16.4, *) { XCUIDevice.shared.location = nil }
    }
    func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ios-permission-smoke"]
        app.launch()
        XCTAssertTrue(app.buttons["Request Location"].waitForExistence(timeout: 30))
        app.terminate()
        app.resetAuthorizationStatus(for: .location)
        app.launch()
        XCTAssertTrue(app.buttons["Request Location"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["Location permission: prompt"].exists)
        XCTAssertFalse(XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch.waitForExistence(timeout: 5), "a launch without a permission request must not prompt")
        return app
    }
    func testLocationPromptDenial() {
        let app = launch()
        app.buttons["Request Location"].tap()
        let alert = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch
        if !alert.waitForExistence(timeout: 5) && !app.staticTexts["Location request started"].exists { app.buttons["Request Location"].tap() }
        XCTAssertTrue(alert.waitForExistence(timeout: 15))
        let deny = alert.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'allow' AND label CONTAINS[c] 't '")).firstMatch
        XCTAssertTrue(deny.exists)
        deny.tap()
        XCTAssertTrue(app.staticTexts["Location permission: denied"].waitForExistence(timeout: 15))
        app.buttons["Start Route"].tap()
        XCTAssertTrue(app.staticTexts["Route error: PERMISSION_DENIED"].waitForExistence(timeout: 15))
        app.buttons["Request Position"].tap()
        XCTAssertTrue(app.staticTexts["Position error: PERMISSION_DENIED"].waitForExistence(timeout: 15))
    }
    func testApproximatePermissionAndTemporaryPrecision() {
        let app = launch()
        app.buttons["Request Location"].tap()
        let alert = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch
        if !alert.waitForExistence(timeout: 5) && !app.staticTexts["Location request started"].exists { app.buttons["Request Location"].tap() }
        XCTAssertTrue(alert.waitForExistence(timeout: 15))
        let allow = alert.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'while using'")).firstMatch
        XCTAssertTrue(allow.exists)
        allow.tap()
        XCTAssertTrue(app.staticTexts["Provider accuracy: approximate"].waitForExistence(timeout: 15))
        app.buttons["Start Route"].tap()
        XCTAssertTrue(app.staticTexts["Route: approximate enabled"].waitForExistence(timeout: 15))
        app.buttons["Request Precision"].tap()
        let precise = app.staticTexts["Provider accuracy: precise"]
        let presented = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in alert.exists || app.alerts.firstMatch.exists || precise.exists }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [presented], timeout: 15), .completed)
        if !precise.exists {
            let prompt = alert.exists ? alert : app.alerts.firstMatch
            let upgrade = prompt.buttons.matching(NSPredicate(format: "(label CONTAINS[c] 'precise' OR label BEGINSWITH[c] 'allow' OR label == 'OK') AND NOT label CONTAINS[c] 'keep'")).firstMatch
            XCTAssertTrue(upgrade.waitForExistence(timeout: 10), prompt.debugDescription)
            upgrade.tap()
        }
        XCTAssertTrue(precise.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["Route: precise enabled"].waitForExistence(timeout: 15))
        app.buttons["Stop Route"].tap()
        XCTAssertTrue(app.staticTexts["Route: stopped"].waitForExistence(timeout: 15))
    }
}
