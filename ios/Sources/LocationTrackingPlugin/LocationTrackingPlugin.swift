import Foundation
import Capacitor

@objc(LocationTrackingPlugin)
public final class LocationTrackingPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "LocationTrackingPlugin"
    public let jsName = "LocationTracking"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "ready", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setConfig", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reset", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startGeofences", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "changePace", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getCurrentPosition", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearWatch", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getOdometer", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setOdometer", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resetOdometer", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLocations", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getCount", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "insertLocation", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "destroyLocations", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "destroyLocation", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "sync", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "addGeofence", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "addGeofences", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeGeofence", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeGeofences", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getGeofences", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getGeofence", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "geofenceExists", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getHeartbeatStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getProviderState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isPowerSaveMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getBatteryOptimizationStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openBatteryOptimizationSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPowerManagerInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openPowerManagerSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openLocationSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openAppSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDeviceInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSensors", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestTemporaryFullAccuracy", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "log", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLog", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "destroyLog", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "uploadLog", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "emailLog", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "watchPosition", returnType: CAPPluginReturnCallback),
    ]
    private var subscription: NativeSubscription?
    private var watches: [String: CAPPluginCall] = [:]
    public override func load() {
        subscription = LocationTrackingNative.addListener(PluginEventForwarder(self))
        LocationTrackingNative.bootstrap(registerBackgroundTasks: false)
    }
    deinit {
        subscription?.remove()
        for id in watches.keys { LocationTrackingNative.call("clearWatch", options: ["id": id]) { _ in } }
    }
    public func onEvent(_ name: String, payload: TrackingJSON) {
        DispatchQueue.main.async { [weak self] in self?.notifyListeners(name, data: payload) }
    }
    private func invoke(_ method: String, _ call: CAPPluginCall) {
        LocationTrackingNative.perform(method, options: call.options as? TrackingJSON ?? [:], native: false) { result in
            DispatchQueue.main.async {
                switch result {
                case .success(let value): call.resolve(value)
                case .failure(let error): call.reject(error.localizedDescription, (error as? TrackingError)?.code ?? "INTERNAL")
                }
            }
        }
    }
    @objc public func watchPosition(_ call: CAPPluginCall) {
        call.keepAlive = true
        DispatchQueue.main.async { self.beginWatch(call) }
    }
    private func beginWatch(_ call: CAPPluginCall) {
        guard let id = call.callbackId else { call.reject("Missing callback id", "INTERNAL"); return }
        watches[id] = call
        var options: TrackingJSON = call.options as? TrackingJSON ?? [:]
        options["_watchId"] = id
        LocationTrackingNative.perform("watchPosition", options: options, native: false) { [weak self] result in
            DispatchQueue.main.async {
                guard let self, self.watches[id] != nil else { return }
                switch result {
                case .success(let wire): call.resolve(wire)
                case .failure(let error): call.reject(error.localizedDescription, (error as? TrackingError)?.code ?? "INTERNAL")
                }
            }
        }
    }
    @objc public func ready(_ call: CAPPluginCall) { invoke("ready", call) }
    @objc public func setConfig(_ call: CAPPluginCall) { invoke("setConfig", call) }
    @objc public func reset(_ call: CAPPluginCall) { invoke("reset", call) }
    @objc public func getState(_ call: CAPPluginCall) { invoke("getState", call) }
    @objc public func start(_ call: CAPPluginCall) { invoke("start", call) }
    @objc public func startGeofences(_ call: CAPPluginCall) { invoke("startGeofences", call) }
    @objc public func stop(_ call: CAPPluginCall) { invoke("stop", call) }
    @objc public func changePace(_ call: CAPPluginCall) { invoke("changePace", call) }
    @objc public func getCurrentPosition(_ call: CAPPluginCall) { invoke("getCurrentPosition", call) }
    @objc public func clearWatch(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if let id = call.getString("id"), let watch = self.watches.removeValue(forKey: id) {
                watch.keepAlive = false
                self.bridge?.releaseCall(watch)
            }
            self.invoke("clearWatch", call)
        }
    }
    @objc public func getOdometer(_ call: CAPPluginCall) { invoke("getOdometer", call) }
    @objc public func setOdometer(_ call: CAPPluginCall) { invoke("setOdometer", call) }
    @objc public func resetOdometer(_ call: CAPPluginCall) { invoke("resetOdometer", call) }
    @objc public func getLocations(_ call: CAPPluginCall) { invoke("getLocations", call) }
    @objc public func getCount(_ call: CAPPluginCall) { invoke("getCount", call) }
    @objc public func insertLocation(_ call: CAPPluginCall) { invoke("insertLocation", call) }
    @objc public func destroyLocations(_ call: CAPPluginCall) { invoke("destroyLocations", call) }
    @objc public func destroyLocation(_ call: CAPPluginCall) { invoke("destroyLocation", call) }
    @objc public func sync(_ call: CAPPluginCall) { invoke("sync", call) }
    @objc public func addGeofence(_ call: CAPPluginCall) { invoke("addGeofence", call) }
    @objc public func addGeofences(_ call: CAPPluginCall) { invoke("addGeofences", call) }
    @objc public func removeGeofence(_ call: CAPPluginCall) { invoke("removeGeofence", call) }
    @objc public func removeGeofences(_ call: CAPPluginCall) { invoke("removeGeofences", call) }
    @objc public func getGeofences(_ call: CAPPluginCall) { invoke("getGeofences", call) }
    @objc public func getGeofence(_ call: CAPPluginCall) { invoke("getGeofence", call) }
    @objc public func geofenceExists(_ call: CAPPluginCall) { invoke("geofenceExists", call) }
    @objc public func getHeartbeatStatus(_ call: CAPPluginCall) { invoke("getHeartbeatStatus", call) }
    @objc public func getProviderState(_ call: CAPPluginCall) { invoke("getProviderState", call) }
    @objc public func isPowerSaveMode(_ call: CAPPluginCall) { invoke("isPowerSaveMode", call) }
    @objc public func getBatteryOptimizationStatus(_ call: CAPPluginCall) { invoke("getBatteryOptimizationStatus", call) }
    @objc public func openBatteryOptimizationSettings(_ call: CAPPluginCall) { invoke("openBatteryOptimizationSettings", call) }
    @objc public func getPowerManagerInfo(_ call: CAPPluginCall) { invoke("getPowerManagerInfo", call) }
    @objc public func openPowerManagerSettings(_ call: CAPPluginCall) { invoke("openPowerManagerSettings", call) }
    @objc public func openLocationSettings(_ call: CAPPluginCall) { invoke("openLocationSettings", call) }
    @objc public func openAppSettings(_ call: CAPPluginCall) { invoke("openAppSettings", call) }
    @objc public func getDeviceInfo(_ call: CAPPluginCall) { invoke("getDeviceInfo", call) }
    @objc public func getSensors(_ call: CAPPluginCall) { invoke("getSensors", call) }
    @objc public override func checkPermissions(_ call: CAPPluginCall) { invoke("checkPermissions", call) }
    @objc public override func requestPermissions(_ call: CAPPluginCall) { invoke("requestPermissions", call) }
    @objc public func requestTemporaryFullAccuracy(_ call: CAPPluginCall) { invoke("requestTemporaryFullAccuracy", call) }
    @objc public func log(_ call: CAPPluginCall) { invoke("log", call) }
    @objc public func getLog(_ call: CAPPluginCall) { invoke("getLog", call) }
    @objc public func destroyLog(_ call: CAPPluginCall) { invoke("destroyLog", call) }
    @objc public func uploadLog(_ call: CAPPluginCall) { invoke("uploadLog", call) }
    @objc public func emailLog(_ call: CAPPluginCall) { invoke("emailLog", call) }
}

private final class PluginEventForwarder: LocationTrackingListener {
    weak var plugin: LocationTrackingPlugin?
    init(_ plugin: LocationTrackingPlugin) { self.plugin = plugin }
    func onEvent(_ name: String, payload: TrackingJSON) { plugin?.onEvent(name, payload: payload) }
}
