import Foundation
import UserNotifications

protocol CheckInCenter {
    func authorized(_ completion: @escaping (Bool) -> Void)
    func add(_ request: UNNotificationRequest, completion: @escaping (Error?) -> Void)
    func cancel()
    func clearDelivered()
}

struct SystemCheckInCenter: CheckInCenter {
    private let center = UNUserNotificationCenter.current()
    func authorized(_ completion: @escaping (Bool) -> Void) {
        center.getNotificationSettings { completion([.authorized, .provisional, .ephemeral].contains($0.authorizationStatus)) }
    }
    func add(_ request: UNNotificationRequest, completion: @escaping (Error?) -> Void) { center.add(request, withCompletionHandler: completion) }
    func cancel() { center.removePendingNotificationRequests(withIdentifiers: [CheckInNotifier.identifier]) }
    func clearDelivered() { center.removeDeliveredNotifications(withIdentifiers: [CheckInNotifier.identifier]) }
}

final class CheckInNotifier {
    static let identifier = "com.brickssoft.locationtracking.check-in"
    private let queue: DispatchQueue
    private let clock: () -> TrackingInstant
    private let center: CheckInCenter
    private let warning: (String) -> Void
    private let appName: String
    private var generation = 0
    private var lastChecked: Double?
    private var enabled = false
    private var scheduling = false
    private var latest: (TrackingJSON, Double?)?
    init(queue: DispatchQueue, clock: @escaping () -> TrackingInstant, center: CheckInCenter = SystemCheckInCenter(), appName: String? = nil, warning: @escaping (String) -> Void) {
        self.queue = queue; self.clock = clock; self.center = center; self.warning = warning
        self.appName = appName ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleName") as? String ?? "Location tracking"
    }
    func start() { center.clearDelivered(); lastChecked = nil }
    func cancel() {
        generation += 1; enabled = false; latest = nil; lastChecked = nil
        center.cancel()
    }
    func touch(_ settings: TrackingJSON, deadlineElapsed: Double?, force: Bool = false) {
        guard settings.flag("enabled") else { cancel(); return }
        let deadlineChanged = latest.map { $0.1 != deadlineElapsed } ?? false
        enabled = true
        latest = (settings, deadlineElapsed)
        if force || deadlineChanged { generation += 1 }
        if force { lastChecked = nil }
        drain()
    }
    private func drain() {
        guard enabled, !scheduling, let (settings, deadline) = latest else { return }
        let now = clock()
        let delay = settings.number("delay", 600)
        guard deadline == nil || now.elapsed + delay * 1000 <= deadline! else { cancel(); return }
        if let lastChecked, now.elapsed - lastChecked < 60000 { return }
        let previous = lastChecked
        let mark = now.elapsed
        lastChecked = mark
        scheduling = true
        let captured = generation
        center.authorized { allowed in
            self.queue.async {
                guard captured == self.generation, self.enabled, let (settings, deadline) = self.latest else {
                    if self.lastChecked == mark { self.lastChecked = previous }
                    self.scheduling = false; self.drain(); return
                }
                let now = self.clock()
                let delay = settings.number("delay", 600)
                guard allowed, deadline == nil || now.elapsed + delay * 1000 <= deadline! else {
                    self.scheduling = false; self.center.cancel(); return
                }
                let content = UNMutableNotificationContent()
                let title = settings.text("title")
                content.title = title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? self.appName : title
                content.body = settings.text("text", "Location tracking is paused. Tap to resume.")
                content.sound = .default
                let request = UNNotificationRequest(identifier: Self.identifier, content: content, trigger: UNTimeIntervalNotificationTrigger(timeInterval: max(1, delay), repeats: false))
                self.center.add(request) { error in
                    self.queue.async {
                        self.scheduling = false
                        if captured == self.generation && self.enabled {
                            if error == nil { self.lastChecked = self.clock().elapsed }
                            else { self.warning("Check-in notification could not be scheduled") }
                        } else if !self.enabled { self.center.cancel() }
                        if captured != self.generation { self.drain() }
                    }
                }
            }
        }
    }
}
