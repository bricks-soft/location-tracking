import Foundation

final class TrackingTimers {
    private struct Entry { let at: Double; let action: () -> Void }
    private let queue: DispatchQueue
    private let now: () -> Double
    private let automatic: Bool
    private var entries: [String: Entry] = [:]
    private var source: DispatchSourceTimer?
    init(queue: DispatchQueue, now: @escaping () -> Double, automatic: Bool = true) { self.queue = queue; self.now = now; self.automatic = automatic }
    deinit { source?.cancel() }
    func schedule(_ kind: String, after delay: Double, _ action: @escaping () -> Void) {
        entries[kind] = Entry(at: now() + max(0, delay), action: action)
        arm()
    }
    func cancel(_ kind: String) { entries.removeValue(forKey: kind); arm() }
    func cancelAll() { entries.removeAll(); source?.cancel(); source = nil }
    func fireDue() {
        while let due = entries.min(by: { $0.value.at < $1.value.at }), due.value.at <= now() {
            entries.removeValue(forKey: due.key)
            due.value.action()
        }
        arm()
    }
    private func arm() {
        guard automatic else { return }
        guard let first = entries.values.map(\.at).min() else { source?.cancel(); source = nil; return }
        if source == nil {
            let timer = DispatchSource.makeTimerSource(queue: queue)
            timer.setEventHandler { [weak self] in self?.fireDue() }
            source = timer; timer.resume()
        }
        source?.schedule(deadline: .now() + min(86400, max(0.001, (first - now()) / 1000)), leeway: .milliseconds(1000))
    }
}
