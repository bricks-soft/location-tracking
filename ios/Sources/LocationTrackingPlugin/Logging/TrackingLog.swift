import Foundation
import os

final class TrackingLog {
    private let logger = Logger(subsystem: "com.brickssoft.locationtracking", category: "plugin")
    private var entries: [TrackingJSON] = []
    private let store: TrackingStore
    private var minimumLevel = "info"
    private var maximumDays = 3
    private let severity = ["off": Int.max, "error": 5, "warn": 4, "info": 3, "debug": 2, "verbose": 1]
    init(store: TrackingStore) {
        self.store = store
        entries = (try? store.load("logs"))?["entries"] as? [TrackingJSON] ?? []
    }
    func configure(_ config: TrackingJSON) {
        minimumLevel = config.text("logLevel", "info")
        maximumDays = max(1, Int(config.number("logMaxDays", 3)))
        prune()
    }
    private func prune() {
        let cutoff = Date().addingTimeInterval(-Double(maximumDays) * 86400).timeIntervalSince1970 * 1000
        entries.removeAll { $0.number("at") < cutoff }
    }
    func write(_ level: String, _ message: String) {
        guard (severity[level] ?? 0) >= (severity[minimumLevel] ?? 3) else { return }
        prune()
        let safe = Self.redact(message)
        entries.append(["at": Date().timeIntervalSince1970 * 1000, "level": level, "text": "\(iso()) \(level) \(safe)"])
        if entries.count > 2000 { entries.removeFirst(entries.count - 2000) }
        do { try store.save("logs", ["entries": entries]) }
        catch { logger.error("Log persistence failed") }
        logger.log("\(safe, privacy: .private)")
    }
    func read(_ query: TrackingJSON) -> String {
        prune()
        var values = entries.filter { $0.number("at") >= query.number("start", 0) && $0.number("at") <= query.number("end", .greatestFiniteMagnitude) && (query.text("level").isEmpty || (severity[$0.text("level")] ?? 0) >= (severity[query.text("level")] ?? 0)) }
        if query.text("order", "asc") == "desc" { values.reverse() }
        return values.prefix(max(0, Int(query.number("limit", 2000)))).map { $0.text("text") }.joined(separator: "\n")
    }
    func clear() throws { try store.save("logs", ["entries": []]); entries = [] }
    static func redact(_ text: String) -> String {
        var result = text
        for pattern in [#"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+"#, #"(?i)\b(?:https?|wss?)://[^\s"'<>]+"#, #"\beyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*"#, #"(?i)("?\b(?:access[_-]?token|refresh[_-]?token|id[_-]?token|token|password|passwd|secret|client[_-]?secret|api[_-]?key|authorization)\b"?\s*[:=]\s*)("(?:[^"\\]|\\.)*"|[^\s,;&}]+)"#] {
            if let regex = try? NSRegularExpression(pattern: pattern) { result = regex.stringByReplacingMatches(in: result, range: NSRange(result.startIndex..., in: result), withTemplate: "[redacted]") }
        }
        return result
    }
}
