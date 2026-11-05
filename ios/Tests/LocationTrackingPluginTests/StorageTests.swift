import XCTest
@testable import LocationTrackingPlugin

final class StorageTests: XCTestCase {
    func path() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("tracking.sqlite") }
    func testReopenRetainsUUIDAndState() throws {
        let url = path()
        let id = UUID().uuidString
        do {
            let store = try TrackingStore(url: url)
            try store.transaction {
                try store.insert(["uuid": id, "event": "location", "recorded_at": iso()])
                try store.save("runtime", ["odometer": 123.5])
            }
        }
        let reopened = try TrackingStore(url: url)
        XCTAssertEqual(try reopened.count, 1)
        XCTAssertEqual(try reopened.locations().first?.text("uuid"), id)
        XCTAssertEqual(try reopened.load("runtime")?.number("odometer"), 123.5)
    }
    func testInterruptedTransactionRollsBackRecordAndState() throws {
        let url = path()
        do {
            let store = try TrackingStore(url: url)
            try store.save("runtime", ["odometer": 10])
            XCTAssertThrowsError(try store.transaction {
                try store.insert(["uuid": "interrupted", "event": "location"])
                try store.save("runtime", ["odometer": 999])
                throw TrackingError("IO_ERROR", "Injected interruption")
            })
        }
        let store = try TrackingStore(url: url)
        XCTAssertEqual(try store.count, 0)
        XCTAssertEqual(try store.load("runtime")?.number("odometer"), 10)
    }
    func testSQLiteRecoveryAfterUncommittedWrite() throws {
        let url = path()
        do {
            let store = try TrackingStore(url: url)
            try store.insert(["uuid": "committed"])
            try store.execute("BEGIN IMMEDIATE")
            try store.insert(["uuid": "lost"])
        }
        let reopened = try TrackingStore(url: url)
        XCTAssertEqual(try reopened.locations().map { $0.text("uuid") }, ["committed"])
    }
    func testDuplicateUUIDFailsAndDeleteIsIdempotent() throws {
        let store = try TrackingStore(url: path())
        try store.insert(["uuid": "same"])
        XCTAssertThrowsError(try store.insert(["uuid": "same"]))
        XCTAssertTrue(try store.delete("same"))
        XCTAssertFalse(try store.delete("same"))
    }
    func testQueueOrderFollowsRecordedAtThenInsertion() throws {
        let store = try TrackingStore(url: path())
        try store.insert(["uuid": "late", "event": "location", "recorded_at": "2026-11-05T10:00:02.000Z"])
        try store.insert(["uuid": "early", "event": "heartbeat", "recorded_at": "2026-11-05T10:00:01.000Z"])
        try store.insert(["uuid": "same", "event": "location", "recorded_at": "2026-11-05T10:00:01.000Z"])
        XCTAssertEqual(try store.locations().map { $0.text("uuid") }, ["early", "same", "late"])
        XCTAssertEqual(try store.locations(limit: 1).map { $0.text("uuid") }, ["early"])
        XCTAssertEqual(try store.locations(events: ["heartbeat"]).map { $0.text("uuid") }, ["early"])
        XCTAssertTrue(try store.contains(events: ["heartbeat", "tracking_start"]))
        XCTAssertFalse(try store.contains(events: ["tracking_stop"]))
        try store.prune(days: -1, maximum: 2)
        XCTAssertEqual(try store.locations().map { $0.text("uuid") }, ["same", "late"])
    }
    func testCountAndRetention() throws {
        let store = try TrackingStore(url: path())
        for n in 0..<5 { try store.insert(["uuid": "\(n)", "recorded_at": iso()]) }
        try store.prune(days: 7, maximum: 2)
        XCTAssertEqual(try store.locations().map { $0.text("uuid") }, ["3", "4"])
        XCTAssertEqual(try store.clear(), 2)
    }
}
