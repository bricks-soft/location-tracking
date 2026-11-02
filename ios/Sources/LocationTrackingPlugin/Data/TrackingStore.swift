import Foundation
import SQLite3

final class TrackingStore {
    private var db: OpaquePointer?
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    init(url: URL) throws {
        var directory = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var resources = URLResourceValues()
        resources.isExcludedFromBackup = true
        try directory.setResourceValues(resources)
        guard sqlite3_open_v2(url.path, &db, SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            sqlite3_close(db)
            throw TrackingError("IO_ERROR", "Cannot open tracking database")
        }
        sqlite3_busy_timeout(db, 1000)
        do {
            try execute("PRAGMA journal_mode=WAL")
            try execute("PRAGMA synchronous=FULL")
            try execute("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            try execute("CREATE TABLE IF NOT EXISTS records (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT UNIQUE NOT NULL, value TEXT NOT NULL)")
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
        } catch { sqlite3_close(db); db = nil; throw error }
    }

    deinit { sqlite3_close(db) }

    private func databaseError(_ message: String) -> TrackingError {
        var error = TrackingError("IO_ERROR", message)
        error.storageFull = sqlite3_errcode(db) == SQLITE_FULL
        return error
    }

    func execute(_ sql: String) throws {
        guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else { throw databaseError("Database operation failed") }
    }

    private func statement(_ sql: String, _ values: [String] = []) throws -> OpaquePointer {
        var query: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &query, nil) == SQLITE_OK, let query else { throw databaseError("Cannot prepare database operation") }
        for (index, value) in values.enumerated() {
            guard sqlite3_bind_text(query, Int32(index + 1), value, -1, transient) == SQLITE_OK else {
                sqlite3_finalize(query)
                throw TrackingError("IO_ERROR", "Cannot bind database value")
            }
        }
        return query
    }

    private func write(_ sql: String, _ values: [String]) throws {
        let query = try statement(sql, values)
        defer { sqlite3_finalize(query) }
        guard sqlite3_step(query) == SQLITE_DONE else { throw databaseError("Cannot write database value") }
    }

    func transaction<T>(_ action: () throws -> T) throws -> T {
        try execute("BEGIN IMMEDIATE")
        do {
            let result = try action()
            try execute("COMMIT")
            return result
        } catch { try? execute("ROLLBACK"); throw error }
    }

    func save(_ key: String, _ value: TrackingJSON) throws {
        try write("INSERT OR REPLACE INTO metadata(key,value) VALUES(?,?)", [key, String(decoding: try jsonData(value), as: UTF8.self)])
    }

    func load(_ key: String) throws -> TrackingJSON? {
        let query = try statement("SELECT value FROM metadata WHERE key=?", [key])
        defer { sqlite3_finalize(query) }
        let status = sqlite3_step(query)
        if status == SQLITE_DONE { return nil }
        guard status == SQLITE_ROW, let value = sqlite3_column_text(query, 0) else { throw TrackingError("IO_ERROR", "Cannot read database value") }
        return try jsonObject(Data(String(cString: value).utf8))
    }

    func insert(_ record: TrackingJSON) throws {
        try write("INSERT INTO records(uuid,value) VALUES(?,?)", [try record.requiringString("uuid"), String(decoding: try jsonData(record), as: UTF8.self)])
    }

    func locations(limit: Int = 0) throws -> [TrackingJSON] {
        let query = try statement("SELECT value FROM records ORDER BY id ASC" + (limit > 0 ? " LIMIT \(limit)" : ""))
        defer { sqlite3_finalize(query) }
        var result: [TrackingJSON] = []
        while true {
            let status = sqlite3_step(query)
            if status == SQLITE_DONE { return result }
            guard status == SQLITE_ROW, let value = sqlite3_column_text(query, 0) else { throw TrackingError("IO_ERROR", "Cannot read queued records") }
            result.append(try jsonObject(Data(String(cString: value).utf8)))
        }
    }

    var count: Int {
        get throws {
            let query = try statement("SELECT COUNT(*) FROM records")
            defer { sqlite3_finalize(query) }
            guard sqlite3_step(query) == SQLITE_ROW else { throw TrackingError("IO_ERROR", "Cannot count records") }
            return Int(sqlite3_column_int64(query, 0))
        }
    }

    func count(event: String) throws -> Int {
        let query = try statement("SELECT COUNT(*) FROM records WHERE json_extract(value,'$.event')=?", [event])
        defer { sqlite3_finalize(query) }
        guard sqlite3_step(query) == SQLITE_ROW else { throw TrackingError("IO_ERROR", "Cannot count queued records") }
        return Int(sqlite3_column_int64(query, 0))
    }

    @discardableResult func delete(_ uuid: String) throws -> Bool {
        try write("DELETE FROM records WHERE uuid=?", [uuid])
        return sqlite3_changes(db) > 0
    }

    func clear() throws -> Int {
        try execute("DELETE FROM records")
        return Int(sqlite3_changes(db))
    }

    func prune(days: Int, maximum: Int, now: Date = Date()) throws {
        try transaction { try enforceRetention(days: days, maximum: maximum, now: now) }
    }

    func enforceRetention(days: Int, maximum: Int, now: Date = Date()) throws {
            if days >= 0 {
                let cutoff = iso(now.addingTimeInterval(-Double(days) * 86400))
                try write("DELETE FROM records WHERE json_extract(value,'$.recorded_at') < ?", [cutoff])
            }
            if maximum > 0 {
                try execute("DELETE FROM records WHERE id NOT IN (SELECT id FROM records ORDER BY id DESC LIMIT \(maximum))")
            }
    }
}
