import XCTest
@testable import LocationTrackingPlugin

final class ConfigTests: XCTestCase {
    func config() throws -> (TrackingStore, TrackingConfig, SecretStore) {
        let store = try TrackingStore(url: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("config.sqlite"))
        let secrets = SecretStore(service: "LT.config.test." + UUID().uuidString)
        return (store, try TrackingConfig(store: store, secrets: secrets), secrets)
    }
    func testSecretsOnlyInKeychainAndMaskedRoundTrip() throws {
        let (store, config, secrets) = try config()
        defer { try? secrets.save(nil) }
        try config.apply(["http": ["authorization": ["accessToken": "test-access-value", "refreshToken": "test-refresh-value", "refreshHeaders": ["X-Key": "secret"], "refreshPayload": ["refresh": "{refreshToken}"]]]], reset: true)
        XCTAssertEqual(try secrets.load()?.text("accessToken"), "test-access-value")
        XCTAssertTrue(try store.load("config")?.object("http")["authorization"] is NSNull)
        XCTAssertEqual(config.masked.object("http").object("authorization").text("accessToken"), "***alue")
        try config.apply(config.masked, reset: false)
        XCTAssertEqual(config.value.object("http").object("authorization").text("accessToken"), "test-access-value")
    }
    func testFreeFormMapsReplaceRatherThanMerge() throws {
        let (_, config, _) = try config()
        try config.apply(["http": ["headers": ["old": "value"]]], reset: true)
        try config.apply(["http": ["headers": ["new": "value"]]], reset: false)
        XCTAssertNil(config.value.object("http").object("headers")["old"])
    }
    func testCheckInDelayClampsWithWarning() throws {
        let (_, config, _) = try config()
        let warnings = try config.apply(["notification": ["resume": ["delay": 100]], "heartbeat": ["maxInterval": 700]], reset: true)
        XCTAssertEqual(config.value.object("notification").object("resume").number("delay"), 760)
        XCTAssertEqual(warnings.count, 1)
    }
    func testInvalidHTTPConfigRejectedBeforePersistence() throws {
        let (store, config, _) = try config()
        XCTAssertThrowsError(try config.apply(["http": ["url": "file:///secret"]], reset: true))
        XCTAssertNil(try store.load("config"))
    }
    func testEmptyAuthorizationStillHasAKeychainEntry() throws {
        let (store, config, secrets) = try config()
        defer { try? secrets.save(nil) }
        try config.apply(["http": ["authorization": TrackingJSON()]], reset: true)
        XCTAssertNotNil(try secrets.load())
        XCTAssertNoThrow(try TrackingConfig(store: store, secrets: secrets))
    }

    func testMissingKeychainEntryDoesNotPreventReplacement() throws {
        let (store, config, secrets) = try config()
        defer { try? secrets.save(nil) }
        try config.apply(["http": ["authorization": ["accessToken": "old"]]], reset: true)
        try secrets.save(nil)
        let reopened = try TrackingConfig(store: store, secrets: secrets)
        XCTAssertTrue(reopened.secretsUnavailable)
        try reopened.apply(["http": ["authorization": ["accessToken": "new"]]], reset: false)
        XCTAssertFalse(reopened.secretsUnavailable)
        XCTAssertEqual(try secrets.load()?.text("accessToken"), "new")
    }

    func testNullLeavesAndGroupsRestoreDefaults() throws {
        let (_, config, _) = try config()
        try config.apply(["geolocation": ["distanceFilter": 99, "filter": ["useKalman": true]], "http": ["autoSync": false, "headers": ["A": "old"]]], reset: true)
        try config.apply(["geolocation": ["distanceFilter": NSNull(), "filter": NSNull()], "http": NSNull()], reset: false)
        XCTAssertEqual(try jsonData(config.value.object("geolocation")), try jsonData(ConfigDefaults.value.object("geolocation")))
        XCTAssertEqual(try jsonData(config.value.object("http")), try jsonData(ConfigDefaults.value.object("http")))
    }
    func testNullAuthorizationLeavesRestoreDefaults() throws {
        let (_, config, secrets) = try config()
        defer { try? secrets.save(nil) }
        try config.apply(["http": ["authorization": ["accessToken": "old", "refreshToken": "old", "expires": 123]]], reset: true)
        try config.apply(["http": ["authorization": ["accessToken": NSNull(), "refreshToken": NSNull(), "expires": NSNull()]]], reset: false)
        XCTAssertTrue(config.value.object("http").object("authorization")["accessToken"] is NSNull)
        XCTAssertEqual(config.value.object("http").object("authorization").number("expires"), -1)
    }
    func testAndroidScalarHeadersAndCoercionFixtures() throws {
        let (_, config, _) = try config()
        try config.apply(["http": ["headers": ["A": "1", "N": 2, "Dropped": NSNull()], "autoSync": "false", "method": "patch"], "geolocation": ["distanceFilter": "15.5", "desiredAccuracy": "PASSIVE"], "notification": ["priority": "Max"]], reset: true)
        XCTAssertEqual(config.value.object("http")["headers"] as? [String: String], ["A": "1", "N": "2"])
        XCTAssertFalse(config.value.object("http").flag("autoSync"))
        XCTAssertEqual(config.value.object("http").text("method"), "PATCH")
        XCTAssertEqual(config.value.object("geolocation").number("distanceFilter"), 15.5)
        XCTAssertEqual(config.value.object("geolocation").text("desiredAccuracy"), "passive")
    }
    func testAndroidInvalidTypeAndEnumFixturesAreAtomic() throws {
        let (store, config, _) = try config()
        try config.apply(["http": ["headers": ["Existing": "retained"]]], reset: true)
        let before = try jsonData(config.value)
        let fixtures = [
            #"{"geolocation":"high"}"#, #"{"geolocation":{"distanceFilter":"far"}}"#,
            #"{"geolocation":{"distanceFilter":true}}"#, #"{"geolocation":{"filter":[]}}"#,
            #"{"heartbeat":{"minInterval":{}}}"#, #"{"http":{"autoSync":"maybe"}}"#,
            #"{"http":{"url":5}}"#, #"{"http":{"headers":"A: 1"}}"#, #"{"http":{"params":[1]}}"#,
            #"{"http":{"authorization":"token"}}"#, #"{"persistence":{"extras":"x"}}"#,
            #"{"notification":{"actions":{}}}"#, #"{"notification":{"actions":[null]}}"#,
            #"{"notification":{"actions":["stop"]}}"#, #"{"notification":{"actions":[{"label":"x"}]}}"#,
            #"{"notification":{"text":false}}"#, #"{"geolocation":{"desiredAccuracy":"best"}}"#,
            #"{"http":{"method":"GET"}}"#, #"{"http":{"authorization":{"strategy":"basic"}}}"#,
            #"{"http":{"authorization":{"refreshPayloadEncoding":"xml"}}}"#,
            #"{"notification":{"priority":"urgent"}}"#, #"{"logger":{"logLevel":"trace"}}"#,
            #"{"locationProvider":"gps"}"#, #"{"locationProvider":1}"#
        ]
        for fixture in fixtures {
            XCTAssertThrowsError(try config.apply(jsonObject(Data(fixture.utf8)), reset: false), fixture)
            XCTAssertEqual(try jsonData(config.value), before, fixture)
        }
        XCTAssertEqual(try store.load("config")?.object("http")["headers"] as? [String: String], ["Existing": "retained"])
    }
    func testUnknownKeysWarnAndStoredInvalidFieldsKeepDefaults() throws {
        let (store, config, secrets) = try config()
        XCTAssertFalse(try config.apply(["unknown": 1], reset: false).isEmpty)
        XCTAssertNil(config.value["unknown"])
        try store.save("config", ["http": ["method": "bad", "url": "https://example.invalid"]])
        let reopened = try TrackingConfig(store: store, secrets: secrets)
        XCTAssertEqual(reopened.value.object("http").text("method"), "POST")
        XCTAssertEqual(reopened.value.object("http").text("url"), "https://example.invalid")
    }

    func testAndroidNullEveryLeafFixtureAndProviderEnums() throws {
        func nullLeaves(_ object: TrackingJSON) -> TrackingJSON {
            object.mapValues { value -> Any in
                if let group = value as? TrackingJSON, !group.isEmpty { return nullLeaves(group) }
                return NSNull()
            }
        }
        let (_, config, _) = try config()
        try config.apply(["http": ["autoSync": false], "notification": ["largeIcon": "icon", "color": "#FFFFFF", "resume": ["title": "Paused"]], "backgroundPermissionRationale": ["message": "Custom"]], reset: true)
        try config.apply(nullLeaves(ConfigDefaults.value), reset: false)
        XCTAssertEqual(try jsonData(config.value), try jsonData(ConfigDefaults.value))
        for provider in ["auto", "gms", "hms", "android"] {
            try config.apply(["locationProvider": provider.uppercased()], reset: false)
            XCTAssertEqual(config.value.text("locationProvider"), provider)
        }
    }

}
