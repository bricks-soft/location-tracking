import XCTest
@testable import LocationTrackingPlugin

final class TemplateTests: XCTestCase {
    let record: TrackingJSON = ["uuid": "0d6c6a1e-6c1e-4b8e-9d8f-2b6f3f0f7a11", "event": "location", "timestamp": "2026-09-26T10:15:30.123Z", "coords": ["latitude": 24.7136, "longitude": 46.6753, "accuracy": 5.2, "altitude": 612.3, "speed": 13.4, "heading": 271.5], "odometer": 1532.4, "is_moving": true, "mock": false, "activity": ["type": "in_vehicle", "confidence": 92], "battery": ["level": 0.81, "is_charging": false], "elapsed_realtime_ms": 86400123, "boot_count": 42]
    func testNumbersAndBooleansInsertedBare() throws {
        let result = try XCTUnwrap(BodyBuilder.render(#"{"lat":<%= latitude %>,"lng":<%=longitude%>,"acc":<%= accuracy %>,"speed":<%= speed %>,"moving":<%= is_moving %>,"conf":<%= activity.confidence %>}"#, record: record) as? TrackingJSON)
        XCTAssertEqual(result.number("lat"), 24.7136)
        XCTAssertEqual(result.number("lng"), 46.6753)
        XCTAssertEqual(result.number("acc"), 5.2)
        XCTAssertEqual(result.number("speed"), 13.4)
        XCTAssertTrue(result.flag("moving"))
        XCTAssertEqual(result.number("conf"), 92)
    }
    func testStringsEscapedAndNullsRemainNull() throws {
        var input = record; input["reason"] = "he said \"hi\" \\ then\nleft\t\u{0001}"
        let result = try XCTUnwrap(BodyBuilder.render(#"{"r":"<%= reason %>","missing":"<%= geofence.identifier %>","note":"at <%= provider.permission %>"}"#, record: input) as? TrackingJSON)
        XCTAssertEqual(result.text("r"), input.text("reason"))
        XCTAssertTrue(result["missing"] is NSNull)
        XCTAssertEqual(result.text("note"), "at null")
    }
    func testUnknownAndInvalidTemplates() {
        XCTAssertEqual((BodyBuilder.render(#"{"value":"<%= does_not_exist %>"}"#, record: record) as? TrackingJSON)?.text("value"), "")
        XCTAssertNil(BodyBuilder.render(#"{"value":<%= does_not_exist %>}"#, record: record))
    }
    func testRootParamsDoNotOverwriteRecordAndBatchHasStableIDs() throws {
        let result = try jsonObject(BodyBuilder.build([record], http: ["rootProperty": ".", "params": ["uuid": "wrong", "driver": 7]], sentAt: "sent"))
        XCTAssertEqual(result.text("uuid"), record.text("uuid"))
        XCTAssertEqual(result.text("sent_at"), "sent")
        XCTAssertEqual(result.number("driver"), 7)
        let batch = try JSONSerialization.jsonObject(with: BodyBuilder.build([record, record], http: ["batchSync": true, "rootProperty": "."])) as? [TrackingJSON]
        XCTAssertEqual(batch?.count, 2)
    }
    func testGeofenceTemplateAndFallback() throws {
        var fence = record; fence["event"] = "geofence"
        let http: TrackingJSON = ["geofenceTemplate": #"{"id":"<%= uuid %>","fence":true}"#, "locationTemplate": "bad JSON"]
        let body = try jsonObject(BodyBuilder.build([fence], http: http))
        XCTAssertTrue(body.object("location").flag("fence"))
        let fallback = try jsonObject(BodyBuilder.build([record], http: http))
        XCTAssertEqual(fallback.object("location").text("uuid"), record.text("uuid"))
    }
    func testStrictJSONRejectsLenientSyntax() {
        for invalid in ["{a:1}", "{'a':1}", "{\"a\":01}", "{\"a\":NaN}", "{\"a\":1,}", "[1,]", "true false"] {
            XCTAssertFalse(StrictJSON.isValid(invalid), invalid)
        }
        for valid in ["null", "true", "-1.25e+3", "{\"a\":[1,false,null]}"] { XCTAssertTrue(StrictJSON.isValid(valid), valid) }
    }

}
