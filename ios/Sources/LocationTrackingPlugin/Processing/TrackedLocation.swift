import Foundation

struct TrackedLocation: Codable, Equatable {
    var latitude: Double
    var longitude: Double
    var accuracy: Double = 5.2
    var altitude: Double? = nil
    var altitudeAccuracy: Double? = nil
    var speed: Double? = nil
    var speedAccuracy: Double? = nil
    var heading: Double? = nil
    var headingAccuracy: Double? = nil
    var time: Double
    var elapsed: Double? = nil
    var isMock = false

    var accuracyMeters: Double { accuracy.isFinite && accuracy > 0 ? accuracy : 0 }
    func isOlder(than other: TrackedLocation) -> Bool { Geo.elapsed(self, other) > 0 }
    var json: TrackingJSON { (try? jsonObject(JSONEncoder().encode(self))) ?? [:] }
    static func from(_ json: TrackingJSON) -> TrackedLocation? { try? JSONDecoder().decode(Self.self, from: jsonData(json)) }
}

enum Geo {
    static func valid(_ latitude: Double, _ longitude: Double) -> Bool {
        latitude.isFinite && longitude.isFinite && (-90...90).contains(latitude) && (-180...180).contains(longitude)
    }
    static func distance(_ a: TrackedLocation, _ b: TrackedLocation) -> Double {
        distance(a.latitude, a.longitude, b.latitude, b.longitude)
    }
    static func distance(_ lat1: Double, _ lng1: Double, _ lat2: Double, _ lng2: Double) -> Double {
        let radians = Double.pi / 180
        let latitude = sin((lat2 - lat1) * radians / 2)
        let longitude = sin((lng2 - lng1) * radians / 2)
        let h = latitude * latitude + cos(lat1 * radians) * cos(lat2 * radians) * longitude * longitude
        return 6371008.8 * 2 * asin(sqrt(min(1, max(0, h))))
    }
    static func elapsed(_ a: TrackedLocation, _ b: TrackedLocation) -> Double {
        if let first = a.elapsed, let second = b.elapsed, first > 0, second > 0 { return second - first }
        return b.time - a.time
    }
    static func wrap(_ delta: Double) -> Double {
        let reduced = (delta + 180).truncatingRemainder(dividingBy: 360)
        return (reduced < 0 ? reduced + 360 : reduced) - 180
    }
    static func normalize(_ longitude: Double) -> Double {
        if (-180...180).contains(longitude) { return longitude }
        return wrap(longitude)
    }
}

enum ElasticDistance {
    static func threshold(_ distance: Double, _ speed: Double?, _ multiplier: Double, _ disabled: Bool) -> Double {
        let base = distance.isFinite ? max(0, distance) : 0
        if disabled { return base }
        let velocity = speed.flatMap { $0.isFinite && $0 > 0 ? $0 : nil } ?? 0
        let factor = multiplier.isFinite ? multiplier : 1
        return base * max(1, floor(velocity / 5 + 0.5) * factor)
    }
    static func shouldRecord(_ previous: TrackedLocation?, _ fix: TrackedLocation, _ config: TrackingJSON) -> Bool {
        guard let previous else { return true }
        return Geo.distance(previous, fix) >= threshold(config.number("distanceFilter", 10), fix.speed, config.number("elasticityMultiplier", 1), config.flag("disableElasticity"))
    }
}
