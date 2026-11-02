import Foundation

struct TrackingInstant: Equatable {
    var wall: Double
    var elapsed: Double
    var json: TrackingJSON { ["wall": wall, "elapsed": elapsed] }
    static func from(_ json: TrackingJSON) -> TrackingInstant? {
        guard let wall = json["wall"] as? Double, let elapsed = json["elapsed"] as? Double else { return nil }
        return TrackingInstant(wall: wall, elapsed: elapsed)
    }
    func continues(_ previous: TrackingInstant) -> Bool {
        elapsed >= previous.elapsed && abs((wall - elapsed) - (previous.wall - previous.elapsed)) < 2000
    }
}

struct SessionDeadline {
    var startedAt: Double
    var startedElapsed: Double
    init(startedAt: Double, startedElapsed: Double?, checkpoint: TrackingInstant?, now: TrackingInstant) {
        self.startedAt = startedAt
        if let startedElapsed, let checkpoint, now.continues(checkpoint) { self.startedElapsed = startedElapsed }
        else { self.startedElapsed = now.elapsed - (now.wall - startedAt) }
    }
    init(now: TrackingInstant) { startedAt = now.wall; startedElapsed = now.elapsed }
    func remaining(minutes: Double, now: TrackingInstant) -> Double? {
        minutes > 0 ? minutes * 60000 - (now.elapsed - startedElapsed) : nil
    }
    func elapsedDeadline(minutes: Double) -> Double? { minutes > 0 ? startedElapsed + minutes * 60000 : nil }
}
