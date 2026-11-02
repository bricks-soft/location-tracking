import UIKit
import UserNotifications
import SQLite3
import LocationTrackingPlugin

final class ScenarioHost: NSObject, LocationTrackingListener {
    static var current: ScenarioHost?
    static var modeURL: URL { try! FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("Stage2/enabled") }
    static var enabled: Bool { ProcessInfo.processInfo.arguments.contains("--ios-stage2-scenario") || FileManager.default.fileExists(atPath: modeURL.path) }
    private var subscription: NativeSubscription?
    private var timer: DispatchSourceTimer?
    private let commandQueue = DispatchQueue(label: "Scenario.commands")
    private var busy = false
    private var lastCommand = ""
    private(set) var audit: [TrackingJSON] = []
    private(set) var latest: TrackingJSON = [:]
    var changed: (() -> Void)?
    let directory: URL
    override init() {
        directory = try! FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("Stage2")
        super.init()
        try! FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try! Data("enabled".utf8).write(to: Self.modeURL, options: .atomic)
        subscription = LocationTrackingNative.addListener(self)
        let timer = DispatchSource.makeTimerSource(queue: commandQueue)
        timer.setEventHandler { [weak self] in self?.poll() }
        timer.schedule(deadline: .now(), repeating: .milliseconds(200))
        self.timer = timer; timer.resume()
        write(["ready": true, "pid": ProcessInfo.processInfo.processIdentifier], "ready.json")
    }
    static func install() { if enabled && current == nil { current = ScenarioHost() } }
    func onRecord(_ record: TrackingJSON) { DispatchQueue.main.async { self.audit.append(record); self.snapshot { _ in } } }
    func onEvent(_ name: String, payload: TrackingJSON) { if name == "enabledchange" { DispatchQueue.main.async { self.snapshot { _ in } } } }
    func invoke(_ method: String, _ options: TrackingJSON = [:], completion: @escaping (TrackingJSON) -> Void) {
        LocationTrackingNative.call(method, options: options) { result in
            DispatchQueue.main.async {
                switch result {
                case .success(let value): completion(["success": true, "value": value])
                case .failure(let error): completion(["success": false, "code": (error as? TrackingError)?.code ?? "INTERNAL", "error": error.localizedDescription])
                }
            }
        }
    }
    private func poll() {
        guard !busy, let data = try? Data(contentsOf: directory.appendingPathComponent("command.json")), let command = try? JSONSerialization.jsonObject(with: data) as? TrackingJSON, let id = command["id"] as? String, id != lastCommand, let method = command["method"] as? String else { return }
        lastCommand = id; busy = true
        let finish: (TrackingJSON) -> Void = { value in
            var response = value; response["id"] = id
            self.commandQueue.async { self.write(response, "response.json"); self.busy = false }
        }
        DispatchQueue.main.async {
            if method == "snapshot" { self.snapshot { finish(["success": true, "value": $0]) } }
            else if method == "willTerminate" { NotificationCenter.default.post(name: UIApplication.willTerminateNotification, object: UIApplication.shared); self.invoke("getState", completion: finish) }
            else { self.invoke(method, command["options"] as? TrackingJSON ?? [:], completion: finish) }
        }
    }
    func snapshot(_ completion: @escaping (TrackingJSON) -> Void) {
        invoke("getState") { state in
            self.invoke("getLocations") { locations in
                UNUserNotificationCenter.current().getPendingNotificationRequests { requests in
                    UNUserNotificationCenter.current().getDeliveredNotifications { delivered in
                        DispatchQueue.main.async {
                            let pending = requests.filter { $0.identifier == "com.brickssoft.locationtracking.check-in" }.map { request -> TrackingJSON in
                                ["identifier": request.identifier, "interval": (request.trigger as? UNTimeIntervalNotificationTrigger)?.timeInterval as Any? ?? NSNull(), "title": request.content.title]
                            }
                            self.latest = ["state": state["value"] ?? [:], "locations": (locations["value"] as? TrackingJSON)?["locations"] ?? [], "runtime": self.runtime(), "pending": pending, "delivered": delivered.filter { $0.request.identifier == "com.brickssoft.locationtracking.check-in" }.count, "audit": self.audit, "appState": UIApplication.shared.applicationState == .background ? "background" : "foreground"]
                            self.changed?(); completion(self.latest)
                        }
                    }
                }
            }
        }
    }
    private func runtime() -> TrackingJSON {
        let url = directory.deletingLastPathComponent().appendingPathComponent("LocationTracking/tracking.sqlite")
        var db: OpaquePointer?, query: OpaquePointer?
        guard sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else { sqlite3_close(db); return [:] }
        defer { sqlite3_finalize(query); sqlite3_close(db) }
        guard sqlite3_prepare_v2(db, "SELECT value FROM metadata WHERE key='runtime'", -1, &query, nil) == SQLITE_OK, sqlite3_step(query) == SQLITE_ROW, let bytes = sqlite3_column_text(query, 0), let data = String(cString: bytes).data(using: .utf8) else { return [:] }
        return (try? JSONSerialization.jsonObject(with: data) as? TrackingJSON) ?? [:]
    }
    private func write(_ value: TrackingJSON, _ file: String) {
        do { try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]).write(to: directory.appendingPathComponent(file), options: .atomic) }
        catch { assertionFailure("Could not write scenario checkpoint") }
    }
}

final class ScenarioViewController: UIViewController {
    private let status = UILabel(), start = UILabel(), elapsed = UILabel(), reason = UILabel(), pending = UILabel(), delivered = UILabel(), harness = UILabel()
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let begin = UIButton(type: .system); begin.setTitle("Start check-in", for: .normal); begin.addTarget(self, action: #selector(beginCheckIn), for: .touchUpInside)
        let stop = UIButton(type: .system); stop.setTitle("Stop tracking", for: .normal); stop.addTarget(self, action: #selector(stopTracking), for: .touchUpInside)
        harness.text = "Harness active"
        let stack = UIStackView(arrangedSubviews: [begin, stop, status, start, elapsed, reason, pending, delivered, harness])
        stack.axis = .vertical; stack.spacing = 16; stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20), stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 40)])
        ScenarioHost.current?.changed = { [weak self] in self?.update() }
        ScenarioHost.current?.snapshot { _ in }
    }
    private func update() {
        guard let latest = ScenarioHost.current?.latest else { return }
        let state = latest["state"] as? TrackingJSON ?? [:], runtime = latest["runtime"] as? TrackingJSON ?? [:]
        set(status, "State: \(state["enabled"] as? Bool == true ? "enabled" : "disabled")")
        set(start, "Session start: \(Int64((runtime["trackingStartedAt"] as? Double) ?? 0))")
        set(elapsed, "Elapsed start: \(Int64((runtime["trackingStartedElapsed"] as? Double) ?? 0))")
        let records = latest["locations"] as? [TrackingJSON] ?? []
        set(reason, "Last start reason: \(records.last { $0["event"] as? String == "tracking_start" }?["reason"] as? String ?? "none")")
        set(pending, "Check-in pending: \((latest["pending"] as? [TrackingJSON])?.isEmpty == false)")
        set(delivered, "Delivered: \(latest["delivered"] as? Int ?? -1)")
    }
    private func set(_ label: UILabel, _ text: String) { if label.text != text { label.text = text } }
    private func refreshPending(_ expected: Bool, until deadline: Date) {
        ScenarioHost.current?.snapshot { value in
            let scheduled = (value["pending"] as? [TrackingJSON])?.isEmpty == false
            if scheduled != expected && Date() < deadline {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in self?.refreshPending(expected, until: deadline) }
            }
        }
    }
    @objc private func beginCheckIn() {
        ScenarioHost.current?.invoke("requestPermissions", ["permissions": ["location", "notifications"]]) { permission in
            guard permission["success"] as? Bool == true else { return }
            ScenarioHost.current?.invoke("ready", ["config": ["http": ["autoSync": false], "geolocation": ["stopAfterElapsedMinutes": 10], "heartbeat": ["enabled": false, "minInterval": 60, "maxInterval": 60], "notification": ["resume": ["enabled": true, "delay": 120, "title": "Location tracking test check-in", "text": "Tap to resume the test route"]], "app": ["stopOnTerminate": false]]]) { _ in
                ScenarioHost.current?.invoke("start") { [weak self] _ in self?.refreshPending(true, until: Date().addingTimeInterval(15)) }
            }
        }
    }
    @objc private func stopTracking() {
        ScenarioHost.current?.invoke("stop") { [weak self] _ in
            do {
                if FileManager.default.fileExists(atPath: ScenarioHost.modeURL.path) { try FileManager.default.removeItem(at: ScenarioHost.modeURL) }
                self?.harness.text = "Harness cleared"
            } catch { self?.harness.text = "Harness cleanup failed" }
            self?.refreshPending(false, until: Date().addingTimeInterval(15))
        }
    }
}
