import Foundation
import CoreLocation

final class LocationServices {
    static let shared = LocationServices()
    private(set) var enabled = true
    private(set) var isReady = false
    private var refreshing = false
    private var completions: [() -> Void] = []
    private let read: () -> Bool
    init(read: @escaping () -> Bool = { CLLocationManager.locationServicesEnabled() }) { self.read = read }
    func whenReady(_ completion: @escaping () -> Void) {
        if isReady { completion() }
        else { refresh(completion: completion) }
    }
    func refresh(completion: (() -> Void)? = nil) {
        if let completion { completions.append(completion) }
        guard !refreshing else { return }
        refreshing = true
        DispatchQueue.global(qos: .utility).async {
            let enabled = self.read()
            DispatchQueue.main.async {
                self.enabled = enabled
                self.isReady = true
                self.refreshing = false
                let waiting = self.completions
                self.completions = []
                waiting.forEach { $0() }
            }
        }
    }
}
