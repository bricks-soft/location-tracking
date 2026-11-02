import Foundation
import CoreLocation

final class SampleCollector {
    private let wanted: Int
    private(set) var best: CLLocation?
    private(set) var count = 0
    init(_ wanted: Int) { self.wanted = max(1, wanted) }
    func add(_ fix: CLLocation) -> Bool {
        count += 1
        if let current = best {
            if rank(fix) < rank(current) || (rank(fix) == rank(current) && fix.timestamp >= current.timestamp) { best = fix }
        } else { best = fix }
        return count >= wanted || best.map { rank($0) <= 10 } == true
    }
    private func rank(_ fix: CLLocation) -> Double { fix.horizontalAccuracy > 0 ? fix.horizontalAccuracy : .infinity }
}

struct CachedPosition {
    let fix: CLLocation
    let receivedElapsed: Double
    let ageAtReceipt: Double
    init(_ fix: CLLocation, wall: Date = Date(), elapsed: Double = TrackingClock.elapsedMilliseconds()) {
        self.fix = fix
        receivedElapsed = elapsed
        ageAtReceipt = max(0, wall.timeIntervalSince(fix.timestamp) * 1000)
    }
    func age(wall: Date = Date(), elapsed: Double = TrackingClock.elapsedMilliseconds()) -> Double {
        max(wall.timeIntervalSince(fix.timestamp) * 1000, ageAtReceipt + max(0, elapsed - receivedElapsed))
    }
}
