import Foundation

final class DwellTracker {
    private struct Pending { let deadline: Double; let token: Double; let sequence: Int }
    private let timers: TrackingTimers
    private let now: () -> Double
    private let onDue: (String, Double) -> Void
    private var pending: [String: Pending] = [:]
    private var sequence = 0
    init(queue: DispatchQueue, now: @escaping () -> Double, automatic: Bool = true, onDue: @escaping (String, Double) -> Void) {
        self.now = now; self.onDue = onDue
        timers = TrackingTimers(queue: queue, now: now, automatic: automatic)
    }
    func arm(_ id: String, deadline: Double, token: Double) {
        sequence += 1
        let current = sequence
        pending[id] = Pending(deadline: deadline, token: token, sequence: current)
        timers.schedule(id, after: deadline - now()) { [weak self] in
            guard let self, let entry = self.pending[id], entry.sequence == current else { return }
            self.pending.removeValue(forKey: id)
            self.onDue(id, entry.token)
        }
    }
    func cancel(_ id: String) { pending.removeValue(forKey: id); timers.cancel(id) }
    func cancelAll() { pending.removeAll(); timers.cancelAll() }
    func isArmed(_ id: String) -> Bool { pending[id] != nil }
    var hasArmed: Bool { !pending.isEmpty }
    func takeDue(_ at: Double? = nil) -> [(String, Double)] {
        let limit = at ?? now()
        let due = pending.filter { $0.value.deadline <= limit }.sorted { $0.value.sequence < $1.value.sequence }
        for (id, _) in due { pending.removeValue(forKey: id); timers.cancel(id) }
        return due.map { ($0.key, $0.value.token) }
    }
    func fireDue() { timers.fireDue() }
}
