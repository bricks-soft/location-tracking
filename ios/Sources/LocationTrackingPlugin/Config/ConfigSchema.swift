import Foundation
import CoreFoundation

struct ConfigSchema {
    static let authorization: TrackingJSON = ["strategy": "JWT", "accessToken": NSNull(), "refreshToken": NSNull(), "refreshUrl": NSNull(), "refreshPayload": TrackingJSON(), "refreshHeaders": TrackingJSON(), "refreshPayloadEncoding": "json", "expires": -1]
    static let maps = Set(["http.headers", "http.params", "persistence.extras", "http.authorization.refreshPayload", "http.authorization.refreshHeaders"])
    static let enums = ["geolocation.desiredAccuracy": ["high", "balanced", "low", "passive"], "http.method": ["POST", "PUT", "PATCH"], "http.authorization.strategy": ["JWT"], "http.authorization.refreshPayloadEncoding": ["json", "form"], "notification.priority": ["min", "low", "default", "high", "max"], "logger.logLevel": ["off", "error", "warn", "info", "debug", "verbose"], "locationProvider": ["auto", "gms", "hms", "android"]]
    static func parse(_ patch: TrackingJSON, base: TrackingJSON, defaults: TrackingJSON = ConfigDefaults.value, path: String = "", lenient: Bool = false, warnings: inout [String]) throws -> TrackingJSON {
        var result = base
        let schema = path == "http.authorization" ? authorization : defaults
        for (key, raw) in patch {
            let name = path.isEmpty ? key : path + "." + key
            guard let fallback = schema[key] else { warnings.append("Ignoring unknown config key \(name)"); continue }
            do {
                if raw is NSNull { result[key] = fallback; continue }
                if maps.contains(name) {
                    guard var map = raw as? TrackingJSON else { throw invalid(name) }
                    if name.hasSuffix("Headers") || name == "http.headers" {
                        var strings: TrackingJSON = [:]
                        for (field, value) in map where !(value is NSNull) {
                            if let text = value as? String { strings[field] = text }
                            else if value is NSNumber { strings[field] = String(decoding: try jsonData(value), as: UTF8.self) }
                            else { throw invalid(name) }
                        }
                        map = strings
                    }
                    if name.hasPrefix("http.authorization.") {
                        for (field, value) in map {
                            if let text = value as? String, let stored = base.object(key)[field] as? String, text == mask(stored) { map[field] = stored }
                        }
                    }
                    result[key] = map
                } else if name == "http.authorization" || fallback is TrackingJSON {
                    guard let object = raw as? TrackingJSON else { throw invalid(name) }
                    let nested = name == "http.authorization" ? authorization : fallback as? TrackingJSON ?? [:]
                    result[key] = try parse(object, base: base[key] as? TrackingJSON ?? nested, defaults: nested, path: name, lenient: lenient, warnings: &warnings)
                } else if let allowed = enums[name] {
                    guard let text = raw as? String, let canonical = allowed.first(where: { $0.lowercased() == text.lowercased() }) else { throw invalid(name) }
                    result[key] = canonical
                } else if name == "notification.actions" {
                    guard let actions = raw as? [TrackingJSON] else { throw invalid(name) }
                    result[key] = try actions.map { action -> TrackingJSON in
                        guard let id = action["id"] as? String, !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, let label = action["label"] as? String else { throw invalid(name) }
                        return ["id": id, "label": label]
                    }
                } else if let number = fallback as? NSNumber {
                    if CFGetTypeID(number) == CFBooleanGetTypeID() {
                        if let value = raw as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() { result[key] = value }
                        else if let text = raw as? String, ["true", "false"].contains(text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()) { result[key] = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "true" }
                        else { throw invalid(name) }
                    } else {
                        let value: Double?
                        if let number = raw as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() { value = number.doubleValue }
                        else if let text = raw as? String { value = Double(text.trimmingCharacters(in: .whitespacesAndNewlines)) }
                        else { value = nil }
                        guard let value, value.isFinite, abs(value) < Double(Int64.max) / 2 else { throw invalid(name) }
                        let decimals = Set(["geolocation.distanceFilter", "geolocation.elasticityMultiplier", "geolocation.stationaryRadius", "geolocation.filter.trackingAccuracyThreshold", "geolocation.filter.maxImpliedSpeed", "geolocation.filter.odometerAccuracyThreshold"])
                        result[key] = decimals.contains(name) ? value : value.rounded(.towardZero)
                    }
                } else {
                    guard let text = raw as? String else { throw invalid(name) }
                    if name == "http.authorization.accessToken" || name == "http.authorization.refreshToken", let stored = base[key] as? String, text == mask(stored) { result[key] = stored }
                    else { result[key] = text }
                }
            } catch {
                if !lenient { throw error }
                warnings.append("Ignoring invalid stored config key \(name)")
            }
        }
        return result
    }
    private static func invalid(_ path: String) -> TrackingError { TrackingError("INVALID_ARGUMENT", "Invalid config.\(path)") }
    private static func mask(_ text: String) -> String { text.count > 8 ? "***" + text.suffix(4) : "***" }
}
