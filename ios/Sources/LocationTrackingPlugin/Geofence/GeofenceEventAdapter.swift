import Foundation

enum MonitorState: Equatable { case unknown, satisfied, unsatisfied, unmonitored }

struct MonitorFailure: Equatable {
    var id: String
    var code: String
    var message: String
}

final class GeofenceEventAdapter {
    private var known: [String: MonitorState] = [:]
    private var initialTrigger: [String: Bool] = [:]
    private var reported: [String: Set<String>] = [:]
    private var ready: Set<String> = []
    private var expected: Set<String> = []
    private var held: [String: [(MonitorState, [(String, String)])]] = [:]

    func seed(_ id: String, _ state: MonitorState) {
        if known[id] == nil { known[id] = state }
    }

    func expect(_ id: String) {
        ready.remove(id); expected.insert(id)
    }

    func restored(_ id: String, initialTriggerEntry: Bool, state: MonitorState) -> ([OsGeofenceTransition], [MonitorFailure]) {
        initialTrigger[id] = initialTriggerEntry
        if known[id] == nil { known[id] = state }
        expected.remove(id); ready.insert(id)
        var transitions: [OsGeofenceTransition] = [], failures: [MonitorFailure] = []
        for (state, diagnostics) in held.removeValue(forKey: id) ?? [] {
            let result = process(id, state, diagnostics)
            transitions += result.0; failures += result.1
        }
        return (transitions, failures)
    }

    func registered(_ id: String, initialTriggerEntry: Bool) {
        known[id] = .unknown
        initialTrigger[id] = initialTriggerEntry
        reported[id] = nil; held[id] = nil
        expected.remove(id); ready.insert(id)
    }

    func removed(_ id: String) {
        known[id] = nil; initialTrigger[id] = nil; reported[id] = nil; held[id] = nil
        expected.remove(id); ready.remove(id)
    }

    func retain(_ ids: Set<String>) {
        known = known.filter { ids.contains($0.key) }
        initialTrigger = initialTrigger.filter { ids.contains($0.key) }
        reported = reported.filter { ids.contains($0.key) }
        held = held.filter { ids.contains($0.key) }
        expected.formIntersection(ids); ready.formIntersection(ids)
    }

    func handle(_ id: String, _ state: MonitorState, failures diagnostics: [(String, String)]) -> ([OsGeofenceTransition], [MonitorFailure]) {
        if !ready.contains(id), expected.contains(id) || known[id] != nil {
            held[id, default: []].append((state, diagnostics))
            return ([], [])
        }
        return process(id, state, diagnostics)
    }

    private func process(_ id: String, _ state: MonitorState, _ diagnostics: [(String, String)]) -> ([OsGeofenceTransition], [MonitorFailure]) {
        var failures: [MonitorFailure] = []
        for (code, message) in diagnostics where !(reported[id]?.contains(code) ?? false) {
            reported[id, default: []].insert(code)
            failures.append(MonitorFailure(id: id, code: code, message: "geofence '\(id)': " + message))
        }
        guard diagnostics.isEmpty, state == .satisfied || state == .unsatisfied, let previous = known[id] else { return ([], failures) }
        reported[id] = nil
        guard previous != state else { return ([], failures) }
        known[id] = state
        if previous == .unknown || previous == .unmonitored {
            if state == .satisfied { return (initialTrigger[id] ?? true ? [OsGeofenceTransition(id: id, action: .enter, location: nil)] : [], failures) }
            return ([OsGeofenceTransition(id: id, action: .exit, location: nil, initial: true)], failures)
        }
        return ([OsGeofenceTransition(id: id, action: state == .satisfied ? .enter : .exit, location: nil)], failures)
    }
}
