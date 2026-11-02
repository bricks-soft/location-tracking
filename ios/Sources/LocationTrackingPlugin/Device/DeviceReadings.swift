import Foundation
import UIKit

final class DeviceReadings {
    static let shared = DeviceReadings()
    private let lock = NSLock()
    private var identifier: String?
    private var model = "iPhone"
    private var version = ""
    private var level: Float = -1
    private var charging = false
    private var observers: [NSObjectProtocol] = []
    private init() {}
    func start() {
        precondition(Thread.isMainThread)
        UIDevice.current.isBatteryMonitoringEnabled = true
        refresh()
        guard observers.isEmpty else { return }
        for name in [UIDevice.batteryLevelDidChangeNotification, UIDevice.batteryStateDidChangeNotification, UIApplication.didBecomeActiveNotification, UIApplication.protectedDataDidBecomeAvailableNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in self?.refresh() })
        }
    }
    private func refresh() {
        let device = UIDevice.current
        let identifier = device.identifierForVendor?.uuidString, model = device.model, version = device.systemVersion
        let level = device.batteryLevel, state = device.batteryState
        lock.lock(); defer { lock.unlock() }
        self.identifier = identifier; self.model = model; self.version = version
        self.level = level; charging = state == .charging || state == .full
    }
    func snapshot(event: String) -> (device: TrackingJSON, battery: TrackingJSON) {
        lock.lock(); defer { lock.unlock() }
        var device: TrackingJSON = [:]
        if let identifier { device["device_id"] = identifier }
        if event == "tracking_start" { device.merge(["manufacturer": "Apple", "model": model, "os_version": version, "ram_total_bytes": ProcessInfo.processInfo.physicalMemory]) { _, new in new } }
        if event == "heartbeat" {
            let thermal: String
            switch ProcessInfo.processInfo.thermalState { case .nominal: thermal = "none"; case .fair: thermal = "light"; case .serious: thermal = "severe"; case .critical: thermal = "critical"; @unknown default: thermal = "unknown" }
            device["thermal_status"] = thermal
        }
        return (device, ["level": level, "is_charging": charging])
    }
}
