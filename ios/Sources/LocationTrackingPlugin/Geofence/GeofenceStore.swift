import Foundation

protocol GeofenceStore: AnyObject {
    func upsert(_ specs: [GeofenceSpec]) throws
    func remove(_ ids: [String]) throws
    func removeAll() throws
    func all() throws -> [GeofenceSpec]
    func get(_ id: String) throws -> GeofenceSpec?
    func setRuntime(_ id: String, _ runtime: GeofenceRuntime) throws
    func runtimes() throws -> [String: GeofenceRuntime]
}

final class SQLiteGeofenceStore: GeofenceStore {
    private let store: TrackingStore
    private let key = "geofences"
    init(store: TrackingStore) { self.store = store }
    private func load() throws -> TrackingJSON { try store.load(key) ?? [:] }
    private func save(_ rows: TrackingJSON) throws { try store.save(key, rows) }
    func upsert(_ specs: [GeofenceSpec]) throws {
        guard !specs.isEmpty else { return }
        var rows = try load()
        for spec in specs {
            var row = rows.object(spec.identifier)
            row["spec"] = spec.json
            rows[spec.identifier] = row
        }
        try save(rows)
    }
    func remove(_ ids: [String]) throws {
        guard !ids.isEmpty else { return }
        var rows = try load()
        ids.forEach { rows.removeValue(forKey: $0) }
        try save(rows)
    }
    func removeAll() throws { try save([:]) }
    func all() throws -> [GeofenceSpec] {
        try load().sorted { $0.key.utf8.lexicographicallyPrecedes($1.key.utf8) }.compactMap { GeofenceSpec.stored(($0.value as? TrackingJSON)?.object("spec") ?? [:]) }
    }
    func get(_ id: String) throws -> GeofenceSpec? { GeofenceSpec.stored(try load().object(id).object("spec")) }
    func setRuntime(_ id: String, _ runtime: GeofenceRuntime) throws {
        var rows = try load()
        guard var row = rows[id] as? TrackingJSON else { return }
        row["runtime"] = runtime.json
        rows[id] = row
        try save(rows)
    }
    func runtimes() throws -> [String: GeofenceRuntime] {
        var result: [String: GeofenceRuntime] = [:]
        for (id, value) in try load() { if let runtime = (value as? TrackingJSON)?["runtime"] as? TrackingJSON { result[id] = GeofenceRuntime.from(runtime) } }
        return result
    }
}
