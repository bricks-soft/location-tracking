import Foundation

final class KalmanFilter {
    private var last: TrackedLocation?
    private var latitude = 0.0
    private var longitude = 0.0
    private var variance = 0.0
    func filter(_ fix: TrackedLocation) -> TrackedLocation {
        let accuracy = fix.accuracy.isFinite && fix.accuracy > 1 ? fix.accuracy : 1
        let measurement = accuracy * accuracy
        let dt = last.map { Geo.elapsed($0, fix) }
        last = fix
        guard let dt, dt >= 0, dt <= 60000 else {
            latitude = fix.latitude; longitude = fix.longitude; variance = measurement
            return fix
        }
        if dt > 0 {
            let reported = fix.speed.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }
            let speed = reported.map { max(1, $0) } ?? 3
            let seconds = dt / 1000
            variance += speed * speed * seconds * max(1, seconds)
        }
        let gain = variance / (variance + measurement)
        latitude += gain * (fix.latitude - latitude)
        longitude = Geo.normalize(longitude + gain * Geo.wrap(fix.longitude - longitude))
        variance *= 1 - gain
        var result = fix; result.latitude = latitude; result.longitude = longitude
        return result
    }
    func reset() { last = nil; variance = 0 }
}

enum FilterResult {
    case accepted(TrackedLocation)
    case rejected(String)
}

final class LocationProcessor {
    private let lock = NSLock()
    private let kalman = KalmanFilter()
    private var lastAccepted: TrackedLocation?
    func process(_ fix: TrackedLocation, filter: TrackingJSON) -> FilterResult {
        lock.lock(); defer { lock.unlock() }
        if !Geo.valid(fix.latitude, fix.longitude) || (fix.latitude == 0 && fix.longitude == 0) { return .rejected("invalid coordinates") }
        if !(fix.accuracy <= filter.number("trackingAccuracyThreshold", 100)) { return .rejected("accuracy exceeds trackingAccuracyThreshold") }
        if fix.isMock && filter.flag("rejectMockLocations") { return .rejected("mock location") }
        if let previous = lastAccepted {
            let dt = Geo.elapsed(previous, fix)
            if dt < 0 { return .rejected("stale fix") }
            if !filter.flag("allowIdenticalLocations") && fix.latitude == previous.latitude && fix.longitude == previous.longitude && fix.time == previous.time { return .rejected("identical fix") }
            let maxSpeed = filter.number("maxImpliedSpeed", 80)
            if maxSpeed > 0 && dt > 0 && Geo.distance(previous, fix) / (dt / 1000) > maxSpeed { return .rejected("implied speed exceeds maxImpliedSpeed") }
        }
        lastAccepted = fix
        if filter.flag("useKalman") { return .accepted(kalman.filter(fix)) }
        kalman.reset()
        return .accepted(fix)
    }
    func reset() { lock.lock(); defer { lock.unlock() }; lastAccepted = nil; kalman.reset() }
}

final class Odometer {
    private var anchor: TrackedLocation?
    private var session: Double?
    func onLocation(_ fix: TrackedLocation, runtime: TrackingJSON, threshold: Double) -> Double {
        guard passes(fix, threshold) else { return 0 }
        let started = runtime["trackingStartedAt"] as? Double
        if session != started {
            session = started
            anchor = nil
            if let started, runtime.flag("enabled"), let last = TrackedLocation.from(runtime.object("lastLocation")), Self.belongs(last, to: started, runtime: runtime), !fix.isOlder(than: last), passes(last, threshold) { anchor = last }
        }
        let previous = anchor
        anchor = fix
        return previous.map { Geo.distance($0, fix) } ?? 0
    }
    func reset(session: Double?) { anchor = nil; self.session = session }
    static func belongs(_ last: TrackedLocation, to started: Double, runtime: TrackingJSON) -> Bool {
        guard let session = last.session else { return last.time >= started }
        return session == started
    }
    private func passes(_ fix: TrackedLocation, _ threshold: Double) -> Bool { fix.accuracy <= threshold && Geo.valid(fix.latitude, fix.longitude) }
}
