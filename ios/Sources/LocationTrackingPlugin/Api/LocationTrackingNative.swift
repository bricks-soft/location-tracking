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
    private static var lifecycleObservers: [NSObjectProtocol] = []

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
                    DispatchQueue.main.async {
                        LocationServices.shared.whenReady {
                            queue.async {
                                guard case .success(let core) = result else { task.setTaskCompleted(success: false); return }
                                let upload = BackgroundUpload(syncer: core.syncer) { success in
                                    task.expirationHandler = nil
                                    task.setTaskCompleted(success: success)
                                    scheduleRefresh()
                                }
                                task.expirationHandler = { queue.async { upload.expire() } }
                                upload.start()
                            }
                        }
                    }
                }
                backgroundObserver = NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { _ in scheduleRefresh() }
            }
        }
        guard !started else { return }
        started = true
        DeviceReadings.shared.start()
        let permissions = Permissions()
        let positions = Positions()
        let route = RouteSource()
        permissions.providerChanged = { payload in queue.async { if case .success(let core) = result { core.engine.providerChanged(payload) } else { emit("providerchange", payload: payload) } } }
        route.locations = { fixes, session in queue.async { if case .success(let core) = result { core.engine.receive(fixes, session: session) } } }
        route.activity = { sample, session in queue.async { if case .success(let core) = result { core.engine.activity(sample, session: session) } } }
        route.error = { error, session in queue.async { if case .success(let core) = result { core.engine.registrationFailed(error, session: session) } } }
        route.authorizationChanged = { LocationServices.shared.refresh { permissions.providerChanged?(permissions.provider) } }
        LocationServices.shared.whenReady {
            let provider = permissions.provider
            let foreground = UIApplication.shared.applicationState == .active
            queue.async { initialize(permissions: permissions, positions: positions, route: route, provider: provider, foreground: foreground) }
        }
        lifecycleObservers.append(NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            LocationServices.shared.refresh {
                let provider = permissions.provider
                let lastLocation = route.lastLocation
                queue.async {
                    if case .success(let core) = result { core.engine.offerLastLocation(lastLocation); core.engine.active(provider: provider, foreground: true); core.syncer.automatic() }
                }
            }
        })
        lifecycleObservers.append(NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { _ in
            let provider = permissions.provider
            queue.async { if case .success(let core) = result { core.engine.context(provider: provider, foreground: false); try? core.updateRuntime([:]) } }
        })
        lifecycleObservers.append(NotificationCenter.default.addObserver(forName: UIApplication.significantTimeChangeNotification, object: nil, queue: .main) { _ in
            queue.async { if case .success(let core) = result { core.engine.clockChanged() } }
        })
        lifecycleObservers.append(NotificationCenter.default.addObserver(forName: .NSProcessInfoPowerStateDidChange, object: nil, queue: .main) { _ in
            let enabled = ProcessInfo.processInfo.isLowPowerModeEnabled
            queue.async { emit("powersavechange", payload: ["isPowerSaveMode": enabled]) }
        })
        lifecycleObservers.append(NotificationCenter.default.addObserver(forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main) { _ in
            LocationServices.shared.whenReady {
                let provider = permissions.provider
                let foreground = UIApplication.shared.applicationState == .active
                queue.async {
                    if case .failure = result { initialize(permissions: permissions, positions: positions, route: route, provider: provider, foreground: foreground) }
                }
            }
        })
        lifecycleObservers.append(NotificationCenter.default.addObserver(forName: UIApplication.willTerminateNotification, object: nil, queue: .main) { _ in
            let completed = DispatchSemaphore(value: 0)
            let stopped = TerminationResult()
            queue.async {
                if case .success(let core) = result {
                    stopped.value = core.config.value.object("app").flag("stopOnTerminate", true)
                    core.engine.terminate()
                }
                completed.signal()
            }
            if completed.wait(timeout: .now() + 2) == .success && stopped.value { route.apply(RouteProfile(enabled: false, moving: false, accuracy: "high", activityEnabled: false), force: true) }
        })
    }
    private final class TerminationResult { var value = false }
    private static func initialize(permissions: Permissions, positions: Positions, route: RouteSource, provider: TrackingJSON, foreground: Bool) {
        result = Result {
            let core = try TrackingCore(queue: queue, permissions: permissions, positions: positions, emit: { emit($0, payload: $1) }, record: { record in
                let recipients = snapshot()
                nativeQueue.async { recipients.forEach { $0.onRecord(copy(record)) } }
            })
            core.routeSource = route
            core.engine.context(provider: provider, foreground: foreground)
            do { try core.engine.restore() } catch { core.log.write("error", "Tracking restore failed") }
            return core
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
            LocationServices.shared.whenReady {
                queue.async {
                    do {
                        guard let result else { throw TrackingError("INTERNAL", "Native bootstrap did not complete") }
                        let core = try result.get()
                        core.call(method, copied, native: native) { outcome in nativeQueue.async { completion(outcome) } }
                    } catch { nativeQueue.async { completion(.failure(error)) } }
                }
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
