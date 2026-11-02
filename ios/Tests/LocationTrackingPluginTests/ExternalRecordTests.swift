import XCTest
@testable import LocationTrackingPlugin

final class ExternalRecordTests: XCTestCase {
    let now = 1790417730456.0
    func parse(_ text: String) throws -> ExternalRecord { try ExternalRecord(jsonObject(Data(text.utf8)), now: now) }
    func testFullAndroidInputMatchesTheLocationFixture() throws {
        let record = try parse("""
        {"coords":{"latitude":24.7136,"longitude":46.6753,"accuracy":5.2,"altitude":612.3,"altitude_accuracy":3.0,"speed":13.4,"speed_accuracy":0.8,"heading":271.5,"heading_accuracy":5.0},"timestamp":"2026-09-26T10:15:30.123Z","event":"motionchange","is_moving":false,"extras":{"source":"import"}}
        """)
        XCTAssertEqual(record.location, EngineFixtures.location())
        XCTAssertEqual(record.event, "motionchange"); XCTAssertEqual(record.moving, false)
        XCTAssertEqual(record.extras.text("source"), "import")
    }
    func testDefaultsAndJSONNullsMatchAndroid() throws {
        for suffix in ["", ",\"timestamp\":null,\"event\":null,\"is_moving\":null,\"extras\":null"] {
            let record = try parse("{\"coords\":{\"latitude\":1.5,\"longitude\":-2.25,\"accuracy\":5,\"speed\":null}" + suffix + "}")
            XCTAssertEqual(record.location, TrackedLocation(latitude: 1.5, longitude: -2.25, accuracy: 5, time: now))
            XCTAssertEqual(record.event, "location"); XCTAssertNil(record.moving); XCTAssertTrue(record.extras.isEmpty)
            for field in ["altitude", "altitude_accuracy", "speed", "speed_accuracy", "heading", "heading_accuracy"] { XCTAssertTrue(record.coordinates[field] is NSNull) }
        }
    }
    func testEveryWireEventAndNumericAndBooleanStrings() throws {
        for event in ExternalRecord.events {
            let record = try parse("{\"coords\":{\"latitude\":\"24.5\",\"longitude\":\"46.25\",\"accuracy\":5},\"event\":\"" + event + "\",\"is_moving\":\"true\"}")
            XCTAssertEqual(record.event, event); XCTAssertEqual(record.moving, true)
            XCTAssertEqual(record.location.latitude, 24.5); XCTAssertEqual(record.location.longitude, 46.25)
        }
    }
    func testTimestampOffsetsAndWholeSeconds() throws {
        XCTAssertEqual(try parse("{\"coords\":{\"latitude\":1,\"longitude\":2,\"accuracy\":5},\"timestamp\":\"2026-09-26T13:15:30.123+03:00\"}").location.time, 1790417730123)
        XCTAssertEqual(try parse("{\"coords\":{\"latitude\":1,\"longitude\":2,\"accuracy\":5},\"timestamp\":\"2026-09-26T10:15:30Z\"}").location.time, 1790417730000)
    }
    func testNegativeUnknownSentinelsBecomeNullAndNegativeAltitudeSurvives() throws {
        let record = try parse("{\"coords\":{\"latitude\":1,\"longitude\":2,\"accuracy\":5,\"altitude\":-12.5,\"altitude_accuracy\":-1,\"speed\":-1,\"speed_accuracy\":-1,\"heading\":-1,\"heading_accuracy\":-1}}")
        XCTAssertEqual(record.location.altitude, -12.5)
        for field in ["altitude_accuracy", "speed", "speed_accuracy", "heading", "heading_accuracy"] { XCTAssertTrue(record.coordinates[field] is NSNull) }
    }
    func testMissingInvalidAndOutOfRangeCoordinatesAndAccuracyReject() {
        for input in ["{}", "{\"coords\":null}", "{\"coords\":\"24,46\"}", "{\"coords\":{\"longitude\":46.6}}", "{\"coords\":{\"latitude\":24.7}}", "{\"coords\":{\"latitude\":null,\"longitude\":46.6}}", "{\"coords\":{\"latitude\":\"north\",\"longitude\":46.6}}", "{\"coords\":{\"latitude\":95,\"longitude\":46.6}}", "{\"coords\":{\"latitude\":24.7,\"longitude\":181}}"] {
            XCTAssertThrowsError(try parse(input)) { XCTAssertEqual(($0 as? TrackingError)?.code, "INVALID_ARGUMENT") }
        }
        for value in ["null", "-1", "0", "\"NaN\"", "true"] {
            XCTAssertThrowsError(try parse("{\"coords\":{\"latitude\":1,\"longitude\":2,\"accuracy\":" + value + "}}"))
        }
        XCTAssertThrowsError(try parse("{\"coords\":{\"latitude\":1,\"longitude\":2}}"))
    }
    func testInvalidOptionalFieldsRejectLikeAndroid() {
        for extra in ["\"timestamp\":\"yesterday\"", "\"timestamp\":1790417730123", "\"event\":\"teleport\"", "\"extras\":\"x=1\"", "\"is_moving\":1"] {
            XCTAssertThrowsError(try parse("{\"coords\":{\"latitude\":1,\"longitude\":2,\"accuracy\":5}," + extra + "}")) { XCTAssertEqual(($0 as? TrackingError)?.code, "INVALID_ARGUMENT") }
        }
    }
    func testInsertedLocationAndExtrasRoundTripThroughJSON() throws {
        let record = try parse("{\"coords\":{\"latitude\":24.7136,\"longitude\":46.6753,\"accuracy\":5.2,\"speed\":1.5},\"timestamp\":\"2026-09-26T10:15:30.123Z\",\"extras\":{\"k\":\"v\"}}")
        XCTAssertEqual(TrackedLocation.from(try jsonObject(jsonData(record.location.json))), record.location)
        XCTAssertEqual(try jsonObject(jsonData(record.extras)).text("k"), "v")
    }
}
