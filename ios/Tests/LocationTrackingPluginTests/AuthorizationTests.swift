import XCTest
@testable import LocationTrackingPlugin

final class AuthorizationTests: XCTestCase {
    func testMissingAccessTokenNeedsRefresh() {
        XCTAssertTrue(Syncer.needsRefresh([:], now: 1000))
        XCTAssertTrue(Syncer.needsRefresh(["accessToken": "  "], now: 1000))
    }
    func testUnknownExpiryDoesNotRefreshUsableToken() {
        XCTAssertFalse(Syncer.needsRefresh(["accessToken": "token", "expires": -1], now: 1000))
    }
    func testRefreshBeginsAtSixtySecondMargin() {
        let auth: TrackingJSON = ["accessToken": "token", "expires": 100000]
        XCTAssertFalse(Syncer.needsRefresh(auth, now: 39999))
        XCTAssertTrue(Syncer.needsRefresh(auth, now: 40000))
    }
    func testShortLivedRefreshedTokenWaitsUntilActualExpiry() {
        let auth: TrackingJSON = ["accessToken": "token", "expires": 100000]
        XCTAssertFalse(Syncer.needsRefresh(auth, now: 99999, shortLivedToken: "token"))
        XCTAssertTrue(Syncer.needsRefresh(auth, now: 100000, shortLivedToken: "token"))
    }
}
