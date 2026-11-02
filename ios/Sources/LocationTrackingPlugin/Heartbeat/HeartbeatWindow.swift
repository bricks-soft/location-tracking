import Foundation

struct HeartbeatWindow: Equatable {
    var baseElapsed: Double
    var dueElapsed: Double
    var deadlineElapsed: Double
    var nextHeartbeatAt: Double?
    var minInterval: Double
    var enabled: Bool
    func shouldFire(_ elapsed: Double) -> Bool { elapsed >= dueElapsed - 1000 }
    func needsRearm(_ armed: HeartbeatWindow?) -> Bool {
        guard let armed, armed.enabled == enabled else { return true }
        let move = dueElapsed - armed.dueElapsed
        return move < 0 || move >= 30000
    }
    static func compute(lastRecordAt: Double?, lastRecordElapsed: Double?, sameBoot: Bool?, now: Double, nowElapsed: Double, minInterval: Double = 180, maxInterval: Double = 300, enabled: Bool = true, noRecordBaseElapsed: Double? = nil, lastAttemptElapsed: Double? = nil, trackingStartedAt: Double? = nil) -> HeartbeatWindow {
        let minimum = max(60, minInterval) * 1000
        let maximum = max(minimum, maxInterval * 1000)
        var base: Double
        if let at = lastRecordAt {
            if let elapsed = lastRecordElapsed, sameBoot ?? (elapsed <= nowElapsed) { base = elapsed }
            else { base = nowElapsed - (now - at) }
            if let started = trackingStartedAt, at < started, started <= now { base = max(base, nowElapsed - (now - started)) }
        } else { base = noRecordBaseElapsed ?? nowElapsed }
        base = min(base, nowElapsed)
        let due = max(base + minimum, lastAttemptElapsed.map { $0 + minimum } ?? -.infinity)
        return HeartbeatWindow(baseElapsed: base, dueElapsed: due, deadlineElapsed: base + maximum, nextHeartbeatAt: enabled ? now + max(0, due - nowElapsed) : nil, minInterval: minimum, enabled: enabled)
    }
}
