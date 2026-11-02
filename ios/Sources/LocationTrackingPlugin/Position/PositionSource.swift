import Foundation
import CoreLocation

final class PositionSource: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let services: LocationServices
    private let callback: (Result<CLLocation, Error>) -> Void
    private let collector: SampleCollector
    private let rejectMock: Bool
    private let requestedAt = Date()
    private let continuous: Bool
    private let interval: TimeInterval
    private var stopped = false
    private var timeout: DispatchWorkItem?
    private var deliveredAt: Double?
    init(options: TrackingJSON, continuous: Bool, services: LocationServices = .shared, callback: @escaping (Result<CLLocation, Error>) -> Void) {
        self.services = services
        self.callback = callback
        self.collector = SampleCollector(Int(options.number("samples", 3)))
        self.rejectMock = options.flag("_rejectMock")
        self.continuous = continuous
        self.interval = max(0, options.number("interval", 1000)) / 1000
        super.init()
        manager.delegate = self
        switch options.text("desiredAccuracy", "high") {
        case "balanced": manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        case "low": manager.desiredAccuracy = kCLLocationAccuracyKilometer
        case "passive": manager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
        default: manager.desiredAccuracy = kCLLocationAccuracyBest
        }
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        manager.allowsBackgroundLocationUpdates = continuous && modes.contains("location")
        manager.showsBackgroundLocationIndicator = continuous
        if !continuous {
            let item = DispatchWorkItem { [weak self] in
                guard let self, !self.stopped else { return }
                if let best = self.collector.best { self.callback(.success(best)) }
                else { self.callback(.failure(TrackingError("TIMEOUT", "Position request timed out"))) }
                self.stop()
            }
            timeout = item
            DispatchQueue.main.asyncAfter(deadline: .now() + options.number("timeout", 30000) / 1000, execute: item)
        }
    }
    func start() {
        guard continuous || services.enabled else { callback(.failure(TrackingError("LOCATION_DISABLED", "Location Services are disabled"))); stop(); return }
        guard !services.enabled || manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse else { callback(.failure(TrackingError("PERMISSION_DENIED", "Location permission is required"))); stop(); return }
        manager.startUpdatingLocation()
    }
    func stop() { stopped = true; timeout?.cancel(); manager.stopUpdatingLocation(); manager.delegate = nil }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard !stopped else { return }
        for fix in locations where fix.horizontalAccuracy >= 0 && CLLocationCoordinate2DIsValid(fix.coordinate) {
            if !continuous && rejectMock && fix.sourceInformation?.isSimulatedBySoftware == true { continue }
            if continuous {
                let now = TrackingClock.elapsedMilliseconds()
                if deliveredAt == nil || now - deliveredAt! >= interval * 1000 { deliveredAt = now; callback(.success(fix)) }
            } else {
                if fix.timestamp < requestedAt.addingTimeInterval(-2) { continue }
                if collector.add(fix) { callback(.success(collector.best!)); stop(); return }
            }
        }
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if continuous && !stopped && (manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse) { manager.startUpdatingLocation() }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if (error as? CLError)?.code == .locationUnknown { return }
        if continuous {
            services.refresh { [weak self] in
                guard let self, self.services.enabled else { return }
                self.fail(error)
            }
        } else { fail(error) }
    }
    private func fail(_ error: Error) {
        guard !stopped else { return }
        callback(.failure(TrackingError((error as? CLError)?.code == .denied ? "PERMISSION_DENIED" : "UNAVAILABLE", "Core Location could not obtain a position")))
        stop()
    }
}
