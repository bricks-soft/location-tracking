import Foundation
import CoreLocation
import CoreMotion
import UserNotifications
import UIKit

final class Permissions: NSObject, CLLocationManagerDelegate {
    static let order = ["location", "notifications", "activityRecognition", "backgroundLocation"]
    let manager: CLLocationManager
    private let motion = CMMotionActivityManager()
    private var requests: [([String], (TrackingJSON) -> Void)] = []
    private var authorizationDone: (() -> Void)?
    private var awaitingAlways = false
    private var activeObserver: NSObjectProtocol?
    var providerChanged: ((TrackingJSON) -> Void)?
    init(manager: CLLocationManager = CLLocationManager()) {
        self.manager = manager
        super.init()
        manager.delegate = self
        LocationServices.shared.refresh()
        activeObserver = NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            LocationServices.shared.refresh()
            guard let self, self.awaitingAlways else { return }
            self.finishAuthorization()
        }
    }
    deinit { if let activeObserver { NotificationCenter.default.removeObserver(activeObserver) } }
    var provider: TrackingJSON {
        let status = manager.authorizationStatus
        let authorized = status == .authorizedAlways || status == .authorizedWhenInUse
        let enabled = LocationServices.shared.enabled
        return ["enabled": enabled, "gps": enabled, "network": enabled, "permission": status == .authorizedAlways ? "always" : authorized ? "when_in_use" : "denied", "accuracy": authorized ? (manager.accuracyAuthorization == .fullAccuracy ? "precise" : "approximate") : "none", "backend": "corelocation"]
    }
    func status(_ completion: @escaping (TrackingJSON) -> Void) {
        let location: String
        switch manager.authorizationStatus {
        case .notDetermined: location = "prompt"
        case .authorizedAlways, .authorizedWhenInUse: location = "granted"
        default: location = "denied"
        }
        let activity: String
        switch CMMotionActivityManager.authorizationStatus() {
        case .notDetermined: activity = "prompt"
        case .authorized: activity = "granted"
        default: activity = "denied"
        }
        let background = manager.authorizationStatus == .authorizedAlways ? "granted" : manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted ? "denied" : "prompt"
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let notification = settings.authorizationStatus == .notDetermined ? "prompt" : settings.authorizationStatus == .denied ? "denied" : "granted"
            DispatchQueue.main.async { completion(["location": location, "backgroundLocation": background, "activityRecognition": activity, "notifications": notification]) }
        }
    }
    func request(_ types: [String], completion: @escaping (TrackingJSON) -> Void) {
        requests.append((types, completion))
        if requests.count == 1 { advance(types) }
    }
    private func advance(_ types: [String]) {
        guard let first = types.first else {
            status { result in
                let callback = self.requests.removeFirst().1
                callback(result)
                if let next = self.requests.first { self.advance(next.0) }
            }
            return
        }
        let rest = Array(types.dropFirst())
        let next = { self.advance(rest) }
        switch first {
        case "location" where manager.authorizationStatus == .notDetermined:
            authorizationDone = next
            manager.requestWhenInUseAuthorization()
        case "backgroundLocation" where (manager.authorizationStatus == .notDetermined || manager.authorizationStatus == .authorizedWhenInUse) && !UserDefaults.standard.bool(forKey: "LT.alwaysAsked"):
            UserDefaults.standard.set(true, forKey: "LT.alwaysAsked")
            awaitingAlways = true
            authorizationDone = next
            manager.requestAlwaysAuthorization()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                if self.awaitingAlways && UIApplication.shared.applicationState == .active { self.finishAuthorization() }
            }
        case "activityRecognition" where CMMotionActivityManager.isActivityAvailable() && CMMotionActivityManager.authorizationStatus() == .notDetermined:
            motion.queryActivityStarting(from: Date().addingTimeInterval(-1), to: Date(), to: .main) { _, _ in next() }
        case "notifications":
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in DispatchQueue.main.async(execute: next) }
        default: next()
        }
    }
    private func finishAuthorization() {
        let done = authorizationDone
        authorizationDone = nil
        awaitingAlways = false
        done?()
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        LocationServices.shared.refresh { [weak self] in
            guard let self else { return }
            self.providerChanged?(self.provider)
        }
        if awaitingAlways {
            if manager.authorizationStatus == .authorizedAlways { finishAuthorization() }
        } else if manager.authorizationStatus != .notDetermined { finishAuthorization() }
    }
    func temporary(_ purpose: String, completion: @escaping (Result<TrackingJSON, Error>) -> Void) {
        guard (Bundle.main.object(forInfoDictionaryKey: "NSLocationTemporaryUsageDescriptionDictionary") as? [String: String])?[purpose] != nil else { completion(.failure(TrackingError("INVALID_ARGUMENT", "purposeKey must exist in NSLocationTemporaryUsageDescriptionDictionary"))); return }
        guard manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse else { completion(.failure(TrackingError("PERMISSION_DENIED", "Location permission is required"))); return }
        if manager.accuracyAuthorization == .fullAccuracy { completion(.success(provider)); return }
        manager.requestTemporaryFullAccuracyAuthorization(withPurposeKey: purpose) { error in
            DispatchQueue.main.async {
                completion(.success(self.provider))
            }
        }
    }
}
