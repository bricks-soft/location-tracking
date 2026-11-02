import Foundation
import UIKit
import BackgroundTasks

public protocol LocationTrackingListener: AnyObject {
    func onRecord(_ record: TrackingJSON)
    func onEvent(_ name: String, payload: TrackingJSON)
}
public extension LocationTrackingListener {
    func onRecord(_ record: TrackingJSON) {}
    func onEvent(_ name: String, payload: TrackingJSON) {}
}

public final class NativeSubscription {
    private let removeAction: () -> Void
    init(_ action: @escaping () -> Void) { removeAction = action }
    public func remove() { removeAction() }
}

public enum LocationTrackingNative {
    public static let refreshIdentifier = "com.brickssoft.locationtracking.refresh"
    static let queue = DispatchQueue(label: "LT.engine")
    private static let nativeQueue = DispatchQueue(label: "LT-native")
    private static let lock = NSLock()
    private static var listeners: [UUID: LocationTrackingListener] = [:]
    private static var result: Result<TrackingCore, Error>?
    private static var started = false
    private static var registered = false
    private static var backgroundObserver: NSObjectProtocol?

    public static func addListener(_ listener: LocationTrackingListener) -> NativeSubscription {
        let id = UUID()
        lock.lock(); listeners[id] = listener; lock.unlock()
        return NativeSubscription { lock.lock(); listeners.removeValue(forKey: id); lock.unlock() }
    }
    public static func bootstrap(registerBackgroundTasks: Bool = true) {
        if !Thread.isMainThread { DispatchQueue.main.async { bootstrap(registerBackgroundTasks: registerBackgroundTasks) }; return }
        if registerBackgroundTasks && !registered {
            registered = true
            let identifiers = Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String] ?? []
            if identifiers.contains(refreshIdentifier) {
                BGTaskScheduler.shared.register(forTaskWithIdentifier: refreshIdentifier, using: queue) { task in
                    guard case .success(let core) = result else { task.setTaskCompleted(success: false); return }
                    let upload = BackgroundUpload(syncer: core.syncer) { success in
                        task.expirationHandler = nil
                        task.setTaskCompleted(success: success)
                        scheduleRefresh()
                    }
                    task.expirationHandler = { queue.async { upload.expire() } }
                    upload.start()
                }
                backgroundObserver = NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { _ in scheduleRefresh() }
            }
        }
        guard !started else { return }
        started = true
        UIDevice.current.isBatteryMonitoringEnabled = true
        let permissions = Permissions()
        let positions = Positions()
        permissions.providerChanged = { payload in queue.async { emit("providerchange", payload: payload) } }
        queue.async {
            result = Result { try TrackingCore(queue: queue, permissions: permissions, positions: positions, emit: { emit($0, payload: $1) }, record: { record in
                let recipients = snapshot()
                nativeQueue.async { recipients.forEach { $0.onRecord(copy(record)) } }
            }) }
        }
    }
    private static func scheduleRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: refreshIdentifier)
        request.earliestBeginDate = Date().addingTimeInterval(900)
        do { try BGTaskScheduler.shared.submit(request) }
        catch { queue.async { if case .success(let core) = result { core.log.write("warn", "Background refresh could not be scheduled") } } }
    }
    static func emit(_ name: String, payload: TrackingJSON) {
        let recipients = snapshot()
        nativeQueue.async { recipients.forEach { $0.onEvent(name, payload: copy(payload)) } }
    }
    private static func copy(_ value: TrackingJSON) -> TrackingJSON { (try? jsonObject(jsonData(value))) ?? [:] }
    private static func snapshot() -> [LocationTrackingListener] {
        lock.lock(); defer { lock.unlock() }
        return Array(listeners.values)
    }
    public static func call(_ method: String, options: TrackingJSON = [:], completion: @escaping (Result<TrackingJSON, Error>) -> Void) {
        perform(method, options: options, native: true, completion: completion)
    }
    static func perform(_ method: String, options: TrackingJSON, native: Bool, completion: @escaping (Result<TrackingJSON, Error>) -> Void) {
        let copied: TrackingJSON
        do { copied = try jsonObject(jsonData(options)) }
        catch { nativeQueue.async { completion(.failure(TrackingError("INVALID_ARGUMENT", "Invalid JSON arguments"))) }; return }
        DispatchQueue.main.async {
            bootstrap(registerBackgroundTasks: false)
            queue.async {
                do {
                    guard let result else { throw TrackingError("INTERNAL", "Native bootstrap did not complete") }
                    let core = try result.get()
                    core.call(method, copied, native: native) { outcome in nativeQueue.async { completion(outcome) } }
                } catch { nativeQueue.async { completion(.failure(error)) } }
            }
        }
    }
    public static func ready(_ options: TrackingJSON = [:], completion: @escaping (Result<TrackingJSON, Error>) -> Void) { call("ready", options: options, completion: completion) }
    public static func setConfig(_ options: TrackingJSON = [:], completion: @escaping (Result<TrackingJSON, Error>) -> Void) { call("setConfig", options: options, completion: completion) }
    public static func start(_ options: TrackingJSON = [:], completion: @escaping (Result<TrackingJSON, Error>) -> Void) { call("start", options: options, completion: completion) }
    public static func startGeofences(_ options: TrackingJSON = [:], completion: @escaping (Result<TrackingJSON, Error>) -> Void) { call("startGeofences", options: options, completion: completion) }
    public static func stop(_ options: TrackingJSON = [:], completion: @escaping (Result<TrackingJSON, Error>) -> Void) { call("stop", options: options, completion: completion) }
    public static func changePace(_ options: TrackingJSON = [:], completion: @escaping (Result<TrackingJSON, Error>) -> Void) { call("changePace", options: options, completion: completion) }
    public static func getState(_ options: TrackingJSON = [:], completion: @escaping (Result<TrackingJSON, Error>) -> Void) { call("getState", options: options, completion: completion) }
    public static func getHeartbeatStatus(_ options: TrackingJSON = [:], completion: @escaping (Result<TrackingJSON, Error>) -> Void) { call("getHeartbeatStatus", options: options, completion: completion) }
    public static func sync(_ options: TrackingJSON = [:], completion: @escaping (Result<TrackingJSON, Error>) -> Void) { call("sync", options: options, completion: completion) }
    public static func insertLocation(_ options: TrackingJSON = [:], completion: @escaping (Result<TrackingJSON, Error>) -> Void) { call("insertLocation", options: options, completion: completion) }
    public static func addGeofence(_ options: TrackingJSON = [:], completion: @escaping (Result<TrackingJSON, Error>) -> Void) { call("addGeofence", options: options, completion: completion) }
    public static func removeGeofence(_ options: TrackingJSON = [:], completion: @escaping (Result<TrackingJSON, Error>) -> Void) { call("removeGeofence", options: options, completion: completion) }
    public static func getGeofences(_ options: TrackingJSON = [:], completion: @escaping (Result<TrackingJSON, Error>) -> Void) { call("getGeofences", options: options, completion: completion) }

}
