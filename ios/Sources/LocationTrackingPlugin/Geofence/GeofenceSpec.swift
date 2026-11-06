import Foundation
import CoreFoundation

struct GeofenceSpec: Equatable {
    var identifier: String
    var latitude: Double
    var longitude: Double
    var radius: Double
    var vertices: [LatLng]? = nil
    var notifyOnEntry = true
    var notifyOnExit = true
    var notifyOnDwell = false
    var loiteringDelay: Double = 30000
    var extras: TrackingJSON? = nil

    var isPolygon: Bool { vertices != nil }
    var isActive: Bool { notifyOnEntry || notifyOnExit || notifyOnDwell }

    static func == (a: GeofenceSpec, b: GeofenceSpec) -> Bool {
        a.identifier == b.identifier && a.latitude == b.latitude && a.longitude == b.longitude && a.radius == b.radius && a.vertices == b.vertices && a.notifyOnEntry == b.notifyOnEntry && a.notifyOnExit == b.notifyOnExit && a.notifyOnDwell == b.notifyOnDwell && a.loiteringDelay == b.loiteringDelay && NSDictionary(dictionary: a.extras ?? [:]).isEqual(to: b.extras ?? [:]) && (a.extras == nil) == (b.extras == nil)
    }

    var json: TrackingJSON {
        var value: TrackingJSON = ["identifier": identifier, "latitude": latitude, "longitude": longitude, "radius": radius, "notifyOnEntry": notifyOnEntry, "notifyOnExit": notifyOnExit, "notifyOnDwell": notifyOnDwell, "loiteringDelay": loiteringDelay]
        if let vertices { value["vertices"] = vertices.map { [$0.latitude, $0.longitude] } }
        if let extras { value["extras"] = extras }
        return value
    }

    static func stored(_ json: TrackingJSON) -> GeofenceSpec? {
        guard let identifier = json["identifier"] as? String else { return nil }
        let vertices = (json["vertices"] as? [[Double]]).map { $0.map { LatLng(latitude: $0[0], longitude: $0[1]) } }
        return GeofenceSpec(identifier: identifier, latitude: json.number("latitude"), longitude: json.number("longitude"), radius: json.number("radius"), vertices: vertices, notifyOnEntry: json.flag("notifyOnEntry", true), notifyOnExit: json.flag("notifyOnExit", true), notifyOnDwell: json.flag("notifyOnDwell"), loiteringDelay: json.number("loiteringDelay", 30000), extras: json["extras"] as? TrackingJSON)
    }

    static func parse(_ json: TrackingJSON) throws -> GeofenceSpec {
        guard let identifier = json["identifier"] as? String, !identifier.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw invalid("geofence.identifier is required") }
        for key in ["latitude", "longitude", "radius", "loiteringDelay"] where !isNull(json[key]) && number(json[key]) == nil { throw invalid("geofence '\(identifier)' \(key) must be a number") }
        for key in ["notifyOnEntry", "notifyOnExit", "notifyOnDwell"] where !isNull(json[key]) && !isBoolean(json[key]) { throw invalid("geofence '\(identifier)' \(key) must be a boolean") }
        if !isNull(json["extras"]) && !(json["extras"] is TrackingJSON) { throw invalid("geofence '\(identifier)' extras must be an object") }
        var vertices: [LatLng]?
        if !isNull(json["vertices"]) {
            guard let array = json["vertices"] as? [Any] else { throw invalid("geofence '\(identifier)': vertices must be [[lat,lng],...]") }
            vertices = try array.enumerated().map { index, item in
                guard let pair = item as? [Any], pair.count == 2, let latitude = number(pair[0]), let longitude = number(pair[1]) else { throw invalid("geofence '\(identifier)': vertices[\(index)] must be [lat,lng]") }
                return LatLng(latitude: latitude, longitude: longitude)
            }
        }
        let latitude = number(json["latitude"]), longitude = number(json["longitude"]), radius = number(json["radius"])
        if vertices == nil && (latitude == nil || longitude == nil || radius == nil) { throw invalid("geofence '\(identifier)' needs latitude, longitude and radius, or vertices") }
        let delay = number(json["loiteringDelay"]) ?? 30000
        if delay < 0 { throw invalid("geofence '\(identifier)' loiteringDelay must be >= 0") }
        let spec = GeofenceSpec(identifier: identifier, latitude: latitude ?? 0, longitude: longitude ?? 0, radius: radius ?? 0, vertices: vertices, notifyOnEntry: (json["notifyOnEntry"] as? Bool) ?? true, notifyOnExit: (json["notifyOnExit"] as? Bool) ?? true, notifyOnDwell: (json["notifyOnDwell"] as? Bool) ?? false, loiteringDelay: delay.rounded(.towardZero), extras: json["extras"] as? TrackingJSON)
        if let vertices {
            if vertices.count < 3 { throw invalid("geofence '\(identifier)' needs at least 3 vertices") }
            for (index, vertex) in vertices.enumerated() {
                if !(-90...90).contains(vertex.latitude) || !vertex.latitude.isFinite { throw invalid("geofence '\(identifier)' vertices[\(index)] latitude is out of range") }
                if !(-180...180).contains(vertex.longitude) || !vertex.longitude.isFinite { throw invalid("geofence '\(identifier)' vertices[\(index)] longitude is out of range") }
            }
        } else {
            if !(-90...90).contains(spec.latitude) || !spec.latitude.isFinite { throw invalid("geofence '\(identifier)' latitude is out of range") }
            if !(-180...180).contains(spec.longitude) || !spec.longitude.isFinite { throw invalid("geofence '\(identifier)' longitude is out of range") }
            if !(spec.radius > 0) || spec.radius.isInfinite { throw invalid("geofence '\(identifier)' radius must be > 0") }
        }
        return spec
    }

    private static func isNull(_ value: Any?) -> Bool { value == nil || value is NSNull }
    private static func isBoolean(_ value: Any?) -> Bool { (value as? NSNumber).map { CFGetTypeID($0) == CFBooleanGetTypeID() } ?? false }
    private static func number(_ value: Any?) -> Double? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.isFinite else { return nil }
        return number.doubleValue
    }
    private static func invalid(_ message: String) -> TrackingError { TrackingError("INVALID_ARGUMENT", message) }
}

struct GeofenceRuntime: Equatable {
    var insideCircle = false
    var insidePolygon = false
    var enteredAt: Double? = nil
    static let unknown = GeofenceRuntime()
    var json: TrackingJSON { ["insideCircle": insideCircle, "insidePolygon": insidePolygon, "enteredAt": enteredAt as Any? ?? NSNull()] }
    static func from(_ json: TrackingJSON) -> GeofenceRuntime { GeofenceRuntime(insideCircle: json.flag("insideCircle"), insidePolygon: json.flag("insidePolygon"), enteredAt: json["enteredAt"] as? Double) }
}

enum GeofenceAction: String { case enter = "ENTER", exit = "EXIT", dwell = "DWELL" }

struct OsGeofence: Equatable {
    var id: String
    var latitude: Double
    var longitude: Double
    var radius: Double
    var onEntry: Bool
    var onExit: Bool
    var initialTriggerEntry: Bool
}

struct OsGeofenceTransition: Equatable {
    var id: String
    var action: GeofenceAction
    var location: TrackedLocation?
    var initial = false
}
