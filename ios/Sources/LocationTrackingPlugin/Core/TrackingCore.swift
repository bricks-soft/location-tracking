import Foundation
import CoreLocation
import CoreMotion
import UIKit
import Network

final class TrackingCore {
    let queue: DispatchQueue
    let store: TrackingStore
    let config: TrackingConfig
    let log: TrackingLog
    let logExport = LogExport()
    let permissions: Permissions
    let positions: Positions
    let emit: (String, TrackingJSON) -> Void
    let record: (TrackingJSON) -> Void
    var ready = false
    private var odometer: Double = 0
    private var runtime: TrackingJSON
    private var network = "other"
    private var watchIDs = Set<String>()
    private let monitor = NWPathMonitor()
    lazy var syncer = Syncer(queue: queue, store: store, config: config, emit: emit)

    init(queue: DispatchQueue, permissions: Permissions, positions: Positions, emit: @escaping (String, TrackingJSON) -> Void, record: @escaping (TrackingJSON) -> Void, url: URL? = nil) throws {
        self.queue = queue; self.permissions = permissions; self.positions = positions; self.emit = emit; self.record = record
        let path = try url ?? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("LocationTracking/tracking.sqlite")
        store = try TrackingStore(url: path)
        log = TrackingLog(store: store)
        config = try TrackingConfig(store: store, secrets: SecretStore())
        log.configure(config.value.object("logger"))
        let persistence = config.value.object("persistence")
        try store.prune(days: Int(persistence.number("maxDaysToPersist", 7)), maximum: Int(persistence.number("maxRecordsToPersist", -1)))
        runtime = try store.load("runtime") ?? [:]
        odometer = runtime.number("odometer")
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.network = path.status != .satisfied ? "none" : path.usesInterfaceType(.cellular) ? "cellular" : path.usesInterfaceType(.wifi) ? "wifi" : path.usesInterfaceType(.wiredEthernet) ? "ethernet" : "other"
            self.syncer.connectivity(connected: path.status == .satisfied, cellular: path.usesInterfaceType(.cellular))
            self.emit("connectivitychange", ["connected": self.syncer.connected, "type": self.network])
        }
        monitor.start(queue: queue)
    }
    deinit { monitor.cancel() }
    static let allowed = Set(["ready", "getState", "checkPermissions", "requestPermissions", "requestTemporaryFullAccuracy", "getDeviceInfo", "getSensors", "log", "getLog", "getProviderState", "openBatteryOptimizationSettings", "openPowerManagerSettings", "openLocationSettings", "openAppSettings"])
    func state() -> TrackingJSON {
        ["enabled": false, "trackingMode": "location", "isMoving": false, "odometer": odometer, "backend": "corelocation", "lastRecordAt": runtime["lastRecordAt"] ?? NSNull(), "lastStopReason": runtime["lastStopReason"] ?? NSNull(), "lastStopAt": runtime["lastStopAt"] ?? NSNull(), "config": config.masked]
    }
    func call(_ method: String, _ options: TrackingJSON, native: Bool = false, callback: @escaping (Result<TrackingJSON, Error>) -> Void) {
        do {
            guard native || ready || Self.allowed.contains(method) else { throw TrackingError("NOT_READY", "Call ready() before using this method") }
            try Options.validate(method, options)
            switch method {
            case "ready", "reset", "setConfig":
                let patch = method == "setConfig" ? try options.requiringObject("config") : options.object("config")
                let resetting = method == "reset" || (method == "ready" && options.flag("reset", true))
                let existing = try store.load("config")
                let shouldApply = method != "ready" || resetting || existing == nil
                let warnings = shouldApply ? try config.apply(patch, reset: resetting) : []
                log.configure(config.value.object("logger"))
                if method == "ready" && !native { ready = true }
                var result = state(); result["warnings"] = warnings
                callback(.success(result)); syncer.automatic()
            case "getState": callback(.success(state()))
            case "stop": callback(.success(state()))
            case "getOdometer": callback(.success(["odometer": odometer]))
            case "setOdometer", "resetOdometer":
                let value = method == "resetOdometer" ? 0 : options.number("odometer", -1)
                guard value.isFinite && value >= 0 else { throw TrackingError("INVALID_ARGUMENT", "odometer must be a finite number >= 0") }
                var next = runtime; next["odometer"] = value
                try store.save("runtime", next); runtime = next; odometer = value
                callback(.success(["odometer": value]))
            case "getLocations": callback(.success(["locations": try store.locations(limit: max(0, Int(options.number("limit"))))]))
            case "getCount": callback(.success(["count": try store.count]))
            case "destroyLocations": callback(.success(["count": try store.clear()]))
            case "destroyLocation": callback(.success(["deleted": try store.delete(options.requiringString("uuid"))]))
            case "insertLocation": insert(try options.requiringObject("location"), callback: callback)
            case "sync": syncer.run { callback($0.map { ["locations": $0] }) }
            case "getCurrentPosition", "watchPosition":
                let watch = method == "watchPosition"
                let id = options.text("_watchId", UUID().uuidString)
                if watch { watchIDs.insert(id) }
                var request = options
                request["_rejectMock"] = config.value.object("geolocation").object("filter").flag("rejectMockLocations")
                if !watch && request["timeout"] == nil { request["timeout"] = config.value.object("geolocation").number("locationTimeout", 30000) }
                guard request.number("timeout", 30000) > 0, request.number("samples", 3) >= 1, request.number("maximumAge") >= 0, request.number("interval", 1000) >= 0 else { throw TrackingError("INVALID_ARGUMENT", "Invalid position options") }
                DispatchQueue.main.async {
                    self.positions.request(id: id, options: request, watch: watch) { result in
                        let provider = self.permissions.provider
                        self.queue.async {
                            if watch && !self.watchIDs.contains(id) { return }
                            do {
                                let fix = try result.get()
                                if watch && self.config.value.object("geolocation").object("filter").flag("rejectMockLocations") && fix.sourceInformation?.isSimulatedBySoftware == true { return }
                                let extras = self.config.value.object("persistence").object("extras").merging(options.object("extras")) { _, new in new }
                                let wire = RecordFactory.make(event: watch ? "watch_position" : "current_position", fix: fix, extras: extras, odometer: self.odometer, provider: provider, network: self.network)
                                if options.flag("persist", !watch) { self.deliver(wire) }
                                callback(.success(wire))
                            } catch { callback(.failure(error)) }
                        }
                    }
                }
            case "clearWatch":
                let id = try options.requiringString("id")
                watchIDs.remove(id)
                DispatchQueue.main.async { self.positions.clear(id) }
                callback(.success([:]))
            case "getProviderState": DispatchQueue.main.async { LocationServices.shared.whenReady { callback(.success(self.permissions.provider)) } }
            case "checkPermissions": DispatchQueue.main.async { self.permissions.status { callback(.success($0)) } }
            case "requestPermissions":
                let types = options["permissions"] as? [String] ?? Permissions.order
                guard types.allSatisfy({ Permissions.order.contains($0) }) else { throw TrackingError("INVALID_ARGUMENT", "Unknown permission") }
                let ordered = Permissions.order.filter { types.contains($0) }
                DispatchQueue.main.async { self.permissions.request(ordered) { callback(.success($0)) } }
            case "requestTemporaryFullAccuracy":
                let purpose = try options.requiringString("purposeKey")
                DispatchQueue.main.async { self.permissions.temporary(purpose, completion: callback) }
            case "isPowerSaveMode": callback(.success(["isPowerSaveMode": ProcessInfo.processInfo.isLowPowerModeEnabled]))
            case "getBatteryOptimizationStatus": callback(.success(["isIgnoringBatteryOptimizations": false, "canScheduleExactAlarms": false, "isDeviceIdleMode": false]))
            case "getPowerManagerInfo": callback(.success(["manufacturer": "Apple", "available": false]))
            case "openBatteryOptimizationSettings", "openPowerManagerSettings": callback(.success(["opened": false]))
            case "openAppSettings", "openLocationSettings":
                DispatchQueue.main.async { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) { callback(.success(["opened": $0])) } }
            case "getDeviceInfo":
                DispatchQueue.main.async {
                    callback(.success(["platform": "ios", "manufacturer": "Apple", "model": UIDevice.current.model, "brand": "Apple", "osVersion": UIDevice.current.systemVersion, "sdkInt": 0, "pluginVersion": "8.1.0", "gmsAvailable": false, "hmsAvailable": false, "backend": "corelocation", "packagedProviders": ["corelocation"], "backendFallbackReason": NSNull(), "gmsStatus": NSNull(), "hmsStatus": NSNull(), "hmsCoreOutdated": NSNull(), "locationAvailableHint": NSNull(), "cpuCores": ProcessInfo.processInfo.processorCount, "supportedAbis": []]))
                }
            case "getSensors":
                let motion = CMMotionManager()
                callback(.success(["accelerometer": motion.isAccelerometerAvailable, "gyroscope": motion.isGyroAvailable, "magnetometer": motion.isMagnetometerAvailable, "significantMotion": CMMotionActivityManager.isActivityAvailable(), "stepCounter": CMPedometer.isStepCountingAvailable(), "stepDetector": CMPedometer.isStepCountingAvailable(), "barometer": CMAltimeter.isRelativeAltitudeAvailable()]))
            case "log":
                guard let message = options["message"] as? String else { throw TrackingError("INVALID_ARGUMENT", "message must be a string") }
                log.write(try options.requiringString("level"), message); callback(.success([:]))
            case "getLog": callback(.success(["log": log.read(options)]))
            case "destroyLog": try log.clear(); callback(.success([:]))
            case "start", "changePace", "getHeartbeatStatus": throw TrackingError("UNAVAILABLE", "The route engine is delivered in iOS stage 2")
            case "startGeofences", "addGeofence", "addGeofences", "removeGeofence", "removeGeofences", "getGeofences", "getGeofence", "geofenceExists": throw TrackingError("UNAVAILABLE", "Geofencing is delivered in iOS stage 3 and requires iOS 17")
            case "uploadLog": try logExport.upload(log.read([:]), options: options, completion: callback)
            case "emailLog":
                let text = log.read([:])
                DispatchQueue.main.async { self.logExport.email(text, options: options, completion: callback) }
            default: throw TrackingError("UNIMPLEMENTED", "Unknown method: \(method)")
            }
        } catch { callback(.failure(error)) }
    }
    private func insert(_ input: TrackingJSON, callback: @escaping (Result<TrackingJSON, Error>) -> Void) {
        do {
            let coords = try input.requiringObject("coords")
            let lat = coords.number("latitude", .nan), lng = coords.number("longitude", .nan), accuracy = coords.number("accuracy", .nan)
            guard lat.isFinite, lng.isFinite, (-90...90).contains(lat), (-180...180).contains(lng), accuracy.isFinite, accuracy > 0 else { throw TrackingError("INVALID_ARGUMENT", "Invalid coordinates or accuracy") }
            let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            let timestamp: Date
            if let supplied = input["timestamp"] as? String {
                guard let parsed = formatter.date(from: supplied) else { throw TrackingError("INVALID_ARGUMENT", "timestamp must be ISO-8601") }
                timestamp = parsed
            } else { timestamp = Date() }
            let fix = CLLocation(coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lng), altitude: coords.number("altitude"), horizontalAccuracy: accuracy, verticalAccuracy: coords.number("altitude_accuracy", -1), course: coords.number("heading", -1), speed: coords.number("speed", -1), timestamp: timestamp)
            let event = input.text("event", "location")
            guard ["location", "motionchange", "current_position", "watch_position", "heartbeat", "geofence", "tracking_start", "tracking_stop", "providerchange"].contains(event) else { throw TrackingError("INVALID_ARGUMENT", "Invalid record event") }
            let extras = config.value.object("persistence").object("extras").merging(input.object("extras")) { _, new in new }
            let capturedOdometer = odometer
            let capturedNetwork = network
            DispatchQueue.main.async {
                let provider = self.permissions.provider
                var wire = RecordFactory.make(event: event, fix: fix, extras: extras, odometer: capturedOdometer, provider: provider, network: capturedNetwork)
                wire["is_moving"] = input.flag("is_moving")
                var normalized = wire.object("coords")
                for key in ["altitude", "altitude_accuracy", "speed", "speed_accuracy", "heading", "heading_accuracy"] { normalized[key] = coords[key] ?? NSNull() }
                wire["coords"] = normalized
                self.queue.async {
                    do { try self.persist(wire, updateRuntime: false); self.record(wire); self.syncer.automatic(); callback(.success(["uuid": wire.text("uuid")])) }
                    catch { callback(.failure(error)) }
                }
            }
        } catch { callback(.failure(error)) }
    }
    func deliver(_ wire: TrackingJSON) {
        var stored = false
        do { try persist(wire, updateRuntime: true); stored = true }
        catch {
            log.write("error", "Failed to persist position")
            if (error as? TrackingError)?.storageFull == true { emit("storagefull", ["uuid": wire.text("uuid"), "event": wire.text("event"), "diskFreeBytes": NSNull()]) }
            runtime["lastRecordAt"] = wire["recorded_at"]
        }
        record(wire)
        emit("location", wire)
        if stored { syncer.automatic() }
    }
    private func persist(_ wire: TrackingJSON, updateRuntime: Bool) throws {
        var next = runtime; next["lastRecordAt"] = wire["recorded_at"]
        let persistence = config.value.object("persistence")
        try store.transaction {
            try store.insert(wire)
            if updateRuntime { try store.save("runtime", next) }
            try store.enforceRetention(days: Int(persistence.number("maxDaysToPersist", 7)), maximum: Int(persistence.number("maxRecordsToPersist", -1)))
        }
        if updateRuntime { runtime = next }
    }
}
