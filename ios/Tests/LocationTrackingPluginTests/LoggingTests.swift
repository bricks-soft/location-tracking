import XCTest
@testable import LocationTrackingPlugin

final class LoggingTests: XCTestCase {
    func testRedactionAndPersistence() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("logs.sqlite")
        do {
            let store = try TrackingStore(url: url)
            let log = TrackingLog(store: store)
            log.write("info", "Authorization: Bearer test-token https://example.com/token?secret=value refresh_token=hidden")
        }
        let store = try TrackingStore(url: url)
        let log = TrackingLog(store: store)
        let text = log.read([:])
        XCTAssertFalse(text.contains("test-token"))
        XCTAssertFalse(text.contains("https://"))
        XCTAssertFalse(text.contains("hidden"))
        XCTAssertTrue(text.contains("redacted"))
        XCTAssertFalse(TrackingLog.redact(#"{"password":"sensitive phrase"}"#).contains("phrase"))
        try log.clear()
        XCTAssertEqual(log.read([:]), "")
    }
    func testGzipEnvelope() throws {
        let compressed = try LogExport.gzip(Data("location log".utf8))
        XCTAssertEqual(Array(compressed.prefix(2)), [0x1f, 0x8b])
    }
    func testConfiguredSeverityAndQuerySeverity() throws {
        let store = try TrackingStore(url: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("logs.sqlite"))
        let log = TrackingLog(store: store)
        log.configure(["logLevel": "off"])
        log.write("error", "off message")
        XCTAssertEqual(log.read([:]), "")
        log.configure(["logLevel": "warn"])
        log.write("info", "info message")
        log.write("warn", "warning message")
        log.write("error", "error message")
        XCTAssertFalse(log.read([:]).contains("info message"))
        XCTAssertFalse(log.read(["level": "error"]).contains("warning message"))
        XCTAssertTrue(log.read(["level": "warn"]).contains("warning message"))
        XCTAssertTrue(log.read(["level": "warn"]).contains("error message"))
    }
    func testLogRetentionUsesConfiguredDays() throws {
        let store = try TrackingStore(url: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("logs.sqlite"))
        try store.save("logs", ["entries": [["at": Date().addingTimeInterval(-4 * 86400).timeIntervalSince1970 * 1000, "level": "info", "text": "old"], ["at": Date().timeIntervalSince1970 * 1000, "level": "info", "text": "recent"]]])
        let log = TrackingLog(store: store)
        log.configure(["logMaxDays": 3])
        XCTAssertEqual(log.read([:]), "recent")
    }

}
