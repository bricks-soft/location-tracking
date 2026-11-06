import Foundation
import CoreFoundation

struct ExternalRecord {
    let location: TrackedLocation
    let event: String
    let moving: Bool?
    let extras: TrackingJSON
    static let events = Set(["location", "motionchange", "current_position", "watch_position", "heartbeat", "geofence", "tracking_start", "tracking_stop", "providerchange"])
    init(_ input: TrackingJSON, now: Double) throws {
        let coords = try input.requiringObject("coords")
        guard let latitude = Self.number(coords["latitude"]) else { throw TrackingError("INVALID_ARGUMENT", "coords.latitude is required") }
        guard let longitude = Self.number(coords["longitude"]) else { throw TrackingError("INVALID_ARGUMENT", "coords.longitude is required") }
        guard Geo.valid(latitude, longitude) else { throw TrackingError("INVALID_ARGUMENT", "coords are out of range") }
        guard let accuracy = Self.number(coords["accuracy"]) else { throw TrackingError("INVALID_ARGUMENT", "coords.accuracy is required") }
        guard accuracy > 0 else { throw TrackingError("INVALID_ARGUMENT", "coords.accuracy must be > 0") }
        var time = now
        if let value = input["timestamp"], !(value is NSNull) {
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            guard let text = value as? String else { throw TrackingError("INVALID_ARGUMENT", "timestamp must be ISO-8601") }
            var parsed = formatter.date(from: text)
            if parsed == nil { formatter.formatOptions = [.withInternetDateTime]; parsed = formatter.date(from: text) }
            guard let parsed else { throw TrackingError("INVALID_ARGUMENT", "timestamp must be ISO-8601") }
            time = parsed.timeIntervalSince1970 * 1000
        }
        event = input["event"] == nil || input["event"] is NSNull ? "location" : input.text("event")
        guard Self.events.contains(event) else { throw TrackingError("INVALID_ARGUMENT", "Unknown event") }
        if let value = input["is_moving"], !(value is NSNull) {
            if let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() { moving = number.boolValue }
            else if let text = value as? String, ["true", "false"].contains(text.lowercased()) { moving = text.lowercased() == "true" }
            else { throw TrackingError("INVALID_ARGUMENT", "is_moving must be a boolean") }
        } else { moving = nil }
        if let value = input["extras"], !(value is NSNull) {
            guard let object = value as? TrackingJSON else { throw TrackingError("INVALID_ARGUMENT", "extras must be an object") }
            extras = object
        } else { extras = [:] }
        func nonNegative(_ key: String) -> Double? { Self.number(coords[key]).flatMap { $0 >= 0 ? $0 : nil } }
        location = TrackedLocation(latitude: latitude, longitude: longitude, accuracy: accuracy, altitude: Self.number(coords["altitude"]), altitudeAccuracy: nonNegative("altitude_accuracy"), speed: nonNegative("speed"), speedAccuracy: nonNegative("speed_accuracy"), heading: nonNegative("heading"), headingAccuracy: nonNegative("heading_accuracy"), time: time)
    }
    var coordinates: TrackingJSON {
        ["latitude": location.latitude, "longitude": location.longitude, "accuracy": location.accuracy,
         "altitude": location.altitude as Any? ?? NSNull(), "altitude_accuracy": location.altitudeAccuracy as Any? ?? NSNull(),
         "speed": location.speed as Any? ?? NSNull(), "speed_accuracy": location.speedAccuracy as Any? ?? NSNull(),
         "heading": location.heading as Any? ?? NSNull(), "heading_accuracy": location.headingAccuracy as Any? ?? NSNull()]
    }
    private static func number(_ value: Any?) -> Double? {
        let parsed: Double?
        if let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() { parsed = number.doubleValue }
        else if let text = value as? String { parsed = Double(text.trimmingCharacters(in: .whitespacesAndNewlines)) }
        else { parsed = nil }
        return parsed.flatMap { $0.isFinite ? $0 : nil }
    }
}
