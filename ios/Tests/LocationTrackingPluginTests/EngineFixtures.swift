import Foundation
@testable import LocationTrackingPlugin

enum EngineFixtures {
    static func location(latitude: Double = 24.7136, longitude: Double = 46.6753, accuracy: Double = 5.2, speed: Double? = 13.4, time: Double = 1790417730123, elapsed: Double? = nil) -> TrackedLocation {
        TrackedLocation(latitude: latitude, longitude: longitude, accuracy: accuracy, altitude: 612.3, altitudeAccuracy: 3, speed: speed, speedAccuracy: 0.8, heading: 271.5, headingAccuracy: 5, time: time, elapsed: elapsed)
    }
    static func moved(_ from: TrackedLocation, _ north: Double, east: Double = 0, milliseconds: Double = 1000) -> TrackedLocation {
        var fix = from
        fix.latitude += north / 111320
        fix.longitude += east / (111320 * cos(from.latitude * .pi / 180))
        fix.time += milliseconds
        fix.elapsed = from.elapsed.map { $0 + milliseconds }
        return fix
    }
}
