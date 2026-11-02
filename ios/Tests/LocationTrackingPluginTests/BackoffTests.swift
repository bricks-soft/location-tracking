import XCTest
@testable import LocationTrackingPlugin

final class BackoffTests: XCTestCase {
    func testAndroidRetryAndWaitInputs() {
        XCTAssertEqual(UploadBackoff.retryDelay(0, random: 0.5), 2000)
        XCTAssertEqual(UploadBackoff.retryDelay(1, random: 0.5), 4000)
        XCTAssertEqual(UploadBackoff.retryDelay(2, random: 0.5), 8000)
        XCTAssertEqual(UploadBackoff.serverWait(1, random: 0.5, retryAfter: nil, minimum: 0), 30000)
        XCTAssertEqual(UploadBackoff.serverWait(2, random: 0.5, retryAfter: nil, minimum: 0), 60000)
        XCTAssertEqual(UploadBackoff.serverWait(20, random: 0.5, retryAfter: nil, minimum: 0), 900000)
        XCTAssertEqual(UploadBackoff.serverWait(1, random: 0.5, retryAfter: 3600000, minimum: 60000), 3600000)
    }
    func testRetryAfterDeltaDateAndClamping() {
        XCTAssertEqual(UploadBackoff.retryAfter("7200", maximum: 3600), 3600000)
        XCTAssertEqual(UploadBackoff.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT", maximum: 3600, now: Date(timeIntervalSince1970: 1445412420)), 60000)
        XCTAssertNil(UploadBackoff.retryAfter("invalid", maximum: 3600))
    }
    func testCredentialsChangeWaitKeyWithoutStoringTokens() {
        let key = UploadBackoff.authKey(["authorization": ["accessToken": "old", "refreshToken": "refresh"]])
        XCTAssertEqual(key.count, 24)
        XCTAssertNotEqual(key, UploadBackoff.authKey(["authorization": ["accessToken": "new", "refreshToken": "refresh"]]))
    }
}
