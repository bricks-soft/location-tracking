import Foundation

public typealias TrackingJSON = [String: Any]

public struct TrackingError: Error, LocalizedError {
    public let code: String
    public let message: String
    var storageFull = false
    public var errorDescription: String? { message }
    public init(_ code: String, _ message: String) { self.code = code; self.message = message }
}

func jsonData(_ value: Any) throws -> Data {
    guard JSONSerialization.isValidJSONObject(value) || value is NSNull || value is String || value is NSNumber else {
        throw TrackingError("INVALID_ARGUMENT", "Value must be JSON serializable")
    }
    return try JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed, .sortedKeys])
}

func jsonObject(_ data: Data) throws -> TrackingJSON {
    guard let result = try JSONSerialization.jsonObject(with: data) as? TrackingJSON else {
        throw TrackingError("IO_ERROR", "Expected a JSON object")
    }
    return result
}

func iso(_ date: Date = Date()) -> String {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter.string(from: date)
}

extension Dictionary where Key == String, Value == Any {
    func object(_ key: String) -> TrackingJSON { self[key] as? TrackingJSON ?? [:] }
    func number(_ key: String, _ fallback: Double = 0) -> Double { (self[key] as? NSNumber)?.doubleValue ?? fallback }
    func flag(_ key: String, _ fallback: Bool = false) -> Bool { self[key] as? Bool ?? fallback }
    func text(_ key: String, _ fallback: String = "") -> String { self[key] as? String ?? fallback }
    func requiringObject(_ key: String) throws -> TrackingJSON {
        guard let value = self[key] as? TrackingJSON else { throw TrackingError("INVALID_ARGUMENT", "\(key) must be an object") }
        return value
    }
    func requiringString(_ key: String) throws -> String {
        guard let value = self[key] as? String, !value.isEmpty else { throw TrackingError("INVALID_ARGUMENT", "\(key) must be a nonempty string") }
        return value
    }
}
