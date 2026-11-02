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
    var odometer: Double { runtime.number("odometer") }
    var runtime: TrackingJSON
    let clock: () -> TrackingInstant
    let automaticTimers: Bool
    var routeSource: RouteSource?
    lazy var engine = TrackingEngine(core: self)
    private var network = "other"
    private var watchIDs = Set<String>()
    private let monitor = NWPathMonitor()
    lazy var syncer = Syncer(queue: queue, store: store, config: config, emit: emit)

    init(queue: DispatchQueue, permissions: Permissions, positions: Positions, emit: @escaping (String, TrackingJSON) -> Void, record: @escaping (TrackingJSON) -> Void, url: URL? = nil, clock: @escaping () -> TrackingInstant = TrackingClock.now, automaticTimers: Bool = true, geofenceBackend: GeofenceBackend? = nil) throws {
        self.clock = clock; self.automaticTimers = automaticTimers
        self.queue = queue; self.permissions = permissions; self.positions = positions; self.emit = emit; self.record = record
        let path = try url ?? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("LocationTracking/tracking.sqlite")
        store = try TrackingStore(url: path)
        log = TrackingLog(store: store)
        config = try TrackingConfig(store: store, secrets: SecretStore())
        log.configure(config.value.object("logger"))
        let persistence = config.value.object("persistence")
        try store.prune(days: Int(persistence.number("maxDaysToPersist", 7)), maximum: Int(persistence.number("maxRecordsToPersist", -1)), now: Date(timeIntervalSince1970: clock().wall / 1000))
        runtime = try store.load("runtime") ?? [:]
        if !(TrackingInstant.from(runtime.object("clockCheckpoint")).map { clock().continues($0) } ?? false), var stored = TrackedLocation.from(runtime.object("lastLocation")), stored.elapsed != nil {
            stored.elapsed = nil; runtime["lastLocation"] = stored.json
        }
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.network = path.status != .satisfied ? "none" : path.usesInterfaceType(.cellular) ? "cellular" : path.usesInterfaceType(.wifi) ? "wifi" : path.usesInterfaceType(.wiredEthernet) ? "ethernet" : "other"
            self.syncer.connectivity(connected: path.status == .satisfied, cellular: path.usesInterfaceType(.cellular))
            self.emit("connectivitychange", ["connected": self.syncer.connected, "type": self.network])
        }
        monitor.start(queue: queue)
        if let geofenceBackend { attachGeofences(geofenceBackend) }
        else if #available(iOS 17.0, *) {
            let manager = permissions.manager
            attachGeofences(GeofenceMonitor.shared(queue: queue) { manager.authorizationStatus })
        }
    }
    var geofences: GeofenceManager? { engine.geofences }
    private func attachGeofences(_ backend: GeofenceBackend) {
        let manager = GeofenceManager(store: SQLiteGeofenceStore(store: store), backend: backend, queue: queue, now: { [unowned self] in self.clock().wall }, automaticTimers: automaticTimers, initialTriggerEntry: { [unowned self] in self.config.value.object("geofence").flag("initialTriggerEntry", true) }, enabled: { [unowned self] in self.runtime.flag("enabled") }, storedLocation: { [unowned self] in TrackedLocation.from(self.runtime.object("lastLocation")) }, notify: { [unowned self] in self.engine.recordGeofence($0, $1, $2) }, changed: { [unowned self] on, off in self.emit("geofenceschange", ["on": on.map(\.json), "off": off]) }, log: { [unowned self] level, message in self.log.write(level, "Geofences: " + message) })
        manager.demandChanged = { [unowned self] in self.engine.geofenceDemandChanged($0) }
        manager.blocked = { [unowned self] in self.engine.geofencesBlocked() }
        if let monitor = backend as? GeofenceMonitor {
            monitor.transitions = { [weak manager] in manager?.onTransitions($0) }
            monitor.failures = { [weak self] in self?.engine.geofenceFailures($0) }
        }
        engine.geofences = manager
    }
    private func geofenceManager() throws -> GeofenceManager {
        guard let geofences else { throw TrackingError("UNAVAILABLE", "Geofencing requires iOS 17") }
        return geofences
    }
    deinit { monitor.cancel() }
    static let allowed = Set(["ready", "getState", "checkPermissions", "requestPermissions", "requestTemporaryFullAccuracy", "getDeviceInfo", "getSensors", "log", "getLog", "getProviderState", "openBatteryOptimizationSettings", "openPowerManagerSettings", "openLocationSettings", "openAppSettings"])
    func state() -> TrackingJSON {
        ["enabled": runtime.flag("enabled"), "trackingMode": runtime.text("trackingMode", "location"), "isMoving": runtime.flag("isMoving"), "odometer": odometer, "backend": "corelocation", "lastRecordAt": runtime["lastRecordAt"] ?? NSNull(), "lastStopReason": runtime["lastStopReason"] ?? NSNull(), "lastStopAt": runtime["lastStopAt"] ?? NSNull(), "config": config.masked]
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
                if shouldApply { engine.configChanged() }
                if method == "ready" && !native { ready = true }
                var result = state(); result["warnings"] = warnings
                callback(.success(result)); syncer.automatic()
            case "getState": callback(.success(state()))
            case "start", "startGeofences":
                let mode = method == "start" ? "location" : "geofences"
                if mode == "geofences" { _ = try geofenceManager() }
                DispatchQueue.main.async {
                    let provider = self.permissions.provider
                    let foreground = UIApplication.shared.applicationState != .background
                    let lastLocation = self.routeSource?.lastLocation
                    self.queue.async {
                        do { self.engine.context(provider: provider, foreground: foreground); self.engine.offerLastLocation(lastLocation); try self.engine.start(mode: mode); callback(.success(self.state())) }
                        catch { callback(.failure(error)) }
                    }
                }
            case "stop": try engine.stop(); callback(.success(state()))
            case "changePace":
                guard let moving = options["isMoving"] as? Bool else { throw TrackingError("INVALID_ARGUMENT", "isMoving is required") }
                engine.changePace(moving); callback(.success([:]))
            case "getHeartbeatStatus": callback(.success(engine.heartbeatStatus()))
            case "getOdometer": callback(.success(["odometer": odometer]))
            case "setOdometer", "resetOdometer":
                let value = method == "resetOdometer" ? 0 : options.number("odometer", -1)
                guard value.isFinite && value >= 0 else { throw TrackingError("INVALID_ARGUMENT", "odometer must be a finite number >= 0") }
                try updateRuntime(["odometer": value])
                if method == "resetOdometer" { engine.odometer.reset(session: runtime["trackingStartedAt"] as? Double) }
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
                                var location = TrackedLocation(fix)
                                if self.runtime.flag("enabled") { location.session = self.runtime["trackingStartedAt"] as? Double }
                                let wire = self.makeRecord(event: watch ? "watch_position" : "current_position", fix: location, extras: extras, provider: provider)
                                if options.flag("persist", !watch) { self.deliver(wire, location: location) }
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
            case "addGeofence": try geofenceManager().add([GeofenceSpec.parse(options.requiringObject("geofence"))]); callback(.success([:]))
            case "addGeofences":
                let manager = try geofenceManager()
                guard let array = options["geofences"] as? [Any] else { throw TrackingError("INVALID_ARGUMENT", "geofences must be an array") }
                let specs = try array.enumerated().map { index, item -> GeofenceSpec in
                    guard let object = item as? TrackingJSON else { throw TrackingError("INVALID_ARGUMENT", "geofences[\(index)] must be an object") }
                    return try GeofenceSpec.parse(object)
                }
                if !specs.isEmpty { try manager.add(specs) }
                callback(.success([:]))
            case "removeGeofence": try geofenceManager().remove([options.requiringString("identifier")]); callback(.success([:]))
            case "removeGeofences":
                let manager = try geofenceManager()
                if options["identifiers"] == nil || options["identifiers"] is NSNull { try manager.removeAll() }
                else {
                    guard let array = options["identifiers"] as? [Any] else { throw TrackingError("INVALID_ARGUMENT", "identifiers must be an array") }
                    let ids = try array.enumerated().map { index, item -> String in
                        guard let id = item as? String, !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw TrackingError("INVALID_ARGUMENT", "identifiers[\(index)] must be a non-empty string") }
                        return id
                    }
                    if !ids.isEmpty { try manager.remove(ids) }
                }
                callback(.success([:]))
            case "getGeofences": callback(.success(["geofences": try geofenceManager().list().map(\.json)]))
            case "getGeofence": callback(.success(["geofence": try geofenceManager().get(options.requiringString("identifier"))?.json ?? NSNull()]))
            case "geofenceExists": callback(.success(["exists": try geofenceManager().get(options.requiringString("identifier")) != nil]))
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
            let now = clock()
            let parsed = try ExternalRecord(input, now: now.wall)
            let extras = config.value.object("persistence").object("extras").merging(parsed.extras) { _, new in new }
            let capturedOdometer = odometer
            let capturedNetwork = network
            let capturedMoving = runtime.flag("isMoving")
            let capturedActivity = runtime["activity"] ?? ["type": "unknown", "confidence": 0]
            DispatchQueue.main.async {
                let provider = self.permissions.provider
                var wire = RecordFactory.make(event: parsed.event, fix: parsed.location.location, extras: extras, odometer: capturedOdometer, provider: provider, network: capturedNetwork)
                wire["is_moving"] = parsed.moving ?? capturedMoving
                wire["activity"] = capturedActivity
                wire["coords"] = parsed.coordinates
                wire["recorded_at"] = iso(Date(timeIntervalSince1970: now.wall / 1000))
                wire["elapsed_realtime_ms"] = now.elapsed
                self.queue.async {
                    do { try self.persist(wire, updateRuntime: false); self.record(wire); self.syncer.automatic(); callback(.success(["uuid": wire.text("uuid")])) }
                    catch { callback(.failure(error)) }
                }
            }
        } catch { callback(.failure(error)) }
    }
    func updateRuntime(_ changes: TrackingJSON) throws {
        var next = runtime.merging(changes) { _, new in new }
        next["clockCheckpoint"] = clock().json
        try store.save("runtime", next)
        runtime = next
    }
    func makeRecord(event: String, fix: TrackedLocation?, extras: TrackingJSON? = nil, provider: TrackingJSON? = nil) -> TrackingJSON {
        var wire = RecordFactory.make(event: event, fix: fix?.location, extras: extras ?? config.value.object("persistence").object("extras"), odometer: odometer, provider: provider ?? engine.provider, network: network)
        wire["is_moving"] = runtime.flag("isMoving")
        wire["activity"] = runtime["activity"] ?? ["type": "unknown", "confidence": 0]
        wire["mock"] = fix?.isMock ?? false
        let now = clock()
        wire["recorded_at"] = iso(Date(timeIntervalSince1970: now.wall / 1000))
        wire["elapsed_realtime_ms"] = now.elapsed
        return wire
    }
    private func recordedRuntime(_ wire: TrackingJSON, location: TrackedLocation?) -> TrackingJSON {
        var next = runtime
        next["lastRecordAt"] = wire["recorded_at"]
        next["lastRecordElapsed"] = wire["elapsed_realtime_ms"] ?? clock().elapsed
        next["clockCheckpoint"] = clock().json
        if let location { next["lastLocation"] = location.json }
        if wire.text("event") == "heartbeat" { next["lastHeartbeatAt"] = wire["recorded_at"] }
        return next
    }
    func deliver(_ wire: TrackingJSON, location: TrackedLocation? = nil, onUpload: (() -> Void)? = nil) {
        var stored = false
        do { try persist(wire, updateRuntime: true, location: location); stored = true }
        catch {
            log.write("error", "Failed to persist position")
            if (error as? TrackingError)?.storageFull == true { emit("storagefull", ["uuid": wire.text("uuid"), "event": wire.text("event"), "diskFreeBytes": NSNull()]) }
            runtime = recordedRuntime(wire, location: location)
        }
        record(wire)
        switch wire.text("event") {
        case "location", "current_position", "watch_position": emit("location", wire)
        case "motionchange": emit("location", wire); emit("motionchange", ["isMoving": wire.flag("is_moving"), "location": wire])
        case "heartbeat": emit("heartbeat", ["location": wire])
        case "geofence":
            let hit = wire.object("geofence")
            var payload: TrackingJSON = ["identifier": hit["identifier"] ?? NSNull(), "action": hit["action"] ?? NSNull(), "location": wire]
            if let extras = hit["extras"] { payload["extras"] = extras }
            emit("geofence", payload)
        default: break
        }
        engine.recorded()
        if stored {
            if let onUpload { syncer.automatic { _ in onUpload() } }
            else { syncer.automatic() }
        } else { onUpload?() }
    }
    private func persist(_ wire: TrackingJSON, updateRuntime: Bool, location: TrackedLocation? = nil) throws {
        let next = recordedRuntime(wire, location: location)
        let persistence = config.value.object("persistence")
        try store.transaction {
            try store.insert(wire)
            if updateRuntime { try store.save("runtime", next) }
            try store.enforceRetention(days: Int(persistence.number("maxDaysToPersist", 7)), maximum: Int(persistence.number("maxRecordsToPersist", -1)), now: Date(timeIntervalSince1970: clock().wall / 1000))
        }
        if updateRuntime { runtime = next }
    }
}
