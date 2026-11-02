import Foundation
import CoreFoundation

struct Options {
    static func validate(_ method: String, _ options: TrackingJSON) throws {
        let numbers: [String: [String]] = ["getLocations": ["limit"], "setOdometer": ["odometer"], "getCurrentPosition": ["samples", "timeout", "maximumAge"], "watchPosition": ["interval"], "getLog": ["start", "end", "limit"]]
        for key in numbers[method] ?? [] {
            if let value = options[key], !(value is NSNull) {
                guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.isFinite, abs(number.doubleValue) < Double(Int64.max) / 2 else { throw TrackingError("INVALID_ARGUMENT", "\(key) must be a finite number in range") }
            }
        }
        for key in ["persist", "reset", "isMoving"] {
            if let value = options[key], !(value is NSNull), !(value is NSNumber && CFGetTypeID(value as! NSNumber) == CFBooleanGetTypeID()) { throw TrackingError("INVALID_ARGUMENT", "\(key) must be a boolean") }
        }
        for key in ["config", "extras", "headers", "params", "location", "geofence"] {
            if let value = options[key], !(value is NSNull), !(value is TrackingJSON) { throw TrackingError("INVALID_ARGUMENT", "\(key) must be an object") }
        }
        if let desired = options["desiredAccuracy"], !(desired is NSNull) {
            guard let text = desired as? String, ["high", "balanced", "low", "passive"].contains(text) else { throw TrackingError("INVALID_ARGUMENT", "Unknown desiredAccuracy") }
        }
        if method == "requestPermissions", let value = options["permissions"], !(value is [String]) { throw TrackingError("INVALID_ARGUMENT", "permissions must be a string array") }
        if method == "log" {
            guard ["error", "warn", "info", "debug", "verbose"].contains(options.text("level")) else { throw TrackingError("INVALID_ARGUMENT", "Unknown log level") }
        }
    }
}
