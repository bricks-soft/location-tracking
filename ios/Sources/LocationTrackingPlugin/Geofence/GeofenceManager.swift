import Foundation

protocol GeofenceBackend: AnyObject {
    func add(_ regions: [OsGeofence])
    func restore(_ regions: [OsGeofence])
    func remove(_ ids: [String])
    func retain(_ ids: Set<String>)
    func holdSession(_ always: Bool)
}

final class GeofenceManager {
    static let maxGeofences = 20
    static let initialTriggerWindow = 120000.0
    static let maxReorder = 120000.0
    static let consistentFixes = 2
    static let enteredBeforeMonitoring = 0.0
    static let maxIdentifierLength = 100
    static let minPolygonArea = 1.0
    static let reservedIdentifier = "__lt_stationary__"

    private final class Entry {
        let spec: GeofenceSpec
        var runtime: GeofenceRuntime
        var observed = false
        var initialUntil = -Double.infinity
        var initialLatched = false
        var lastFix: TrackedLocation?
        var candidate: Bool?
        var candidateCount = 0
        init(_ spec: GeofenceSpec, _ runtime: GeofenceRuntime) { self.spec = spec; self.runtime = runtime }
        func resetCandidate() { candidate = nil; candidateCount = 0 }
    }

    private let store: GeofenceStore
    private let backend: GeofenceBackend
    private let now: () -> Double
    private let initialTriggerEntry: () -> Bool
    private let enabled: () -> Bool
    private let storedLocation: () -> TrackedLocation?
    private let notify: (GeofenceSpec, GeofenceAction, TrackedLocation?) -> Void
    private let changed: ([GeofenceSpec], [String]) -> Void
    private let log: (String, String) -> Void
    private var entries: [String: Entry] = [:]
    private var order: [String] = []
    private var session: Bool?
    private var loaded = false
    private var wantsFixes = false
    private var lastFix: TrackedLocation?
    private var lastStreamFix: TrackedLocation?
    private(set) var needsContinuousLocation = false
    private(set) var suspended = false
    private var always = false
    var demandChanged: ((Bool) -> Void)?
    var blocked: (() -> Void)?
    private(set) var dwell: DwellTracker!

    init(store: GeofenceStore, backend: GeofenceBackend, queue: DispatchQueue, now: @escaping () -> Double, automaticTimers: Bool = true, initialTriggerEntry: @escaping () -> Bool, enabled: @escaping () -> Bool, storedLocation: @escaping () -> TrackedLocation?, notify: @escaping (GeofenceSpec, GeofenceAction, TrackedLocation?) -> Void, changed: @escaping ([GeofenceSpec], [String]) -> Void, log: @escaping (String, String) -> Void = { _, _ in }) {
        self.store = store; self.backend = backend; self.now = now; self.initialTriggerEntry = initialTriggerEntry; self.enabled = enabled
        self.storedLocation = storedLocation; self.notify = notify; self.changed = changed; self.log = log
        dwell = DwellTracker(queue: queue, now: now, automatic: automaticTimers) { [weak self] id, token in self?.fireDwell(id, token) }
    }

    func add(_ specs: [GeofenceSpec]) throws {
        guard !specs.isEmpty else { return }
        var incoming: [String: GeofenceSpec] = [:], ids: [String] = []
        for spec in specs {
            let valid = try Self.validated(spec)
            if incoming[valid.identifier] == nil { ids.append(valid.identifier) }
            incoming[valid.identifier] = valid
        }
        let list = ids.map { incoming[$0]! }
        try ensureLoaded()
        let newIds = ids.filter { entries[$0] == nil }
        let total = entries.count + newIds.count
        if total > Self.maxGeofences { throw TrackingError("TOO_MANY_GEOFENCES", "at most \(Self.maxGeofences) geofences are supported (this would make \(total))") }
        let replaced = ids.compactMap { entries[$0]?.spec }
        let register = enabled() && !suspended
        if register {
            let active = Set(list.filter(\.isActive).map(\.identifier))
            let deactivated = replaced.filter(\.isActive).map(\.identifier).filter { !active.contains($0) }
            if !deactivated.isEmpty { backend.remove(deactivated) }
            self.register(list)
        }
        do { try store.upsert(list) }
        catch {
            if register { backend.remove(newIds); self.register(replaced) }
            throw TrackingError("IO_ERROR", "failed to store geofences")
        }
        let time = now()
        for spec in list {
            let previous = entries[spec.identifier]
            dwell.cancel(spec.identifier)
            let entry = Entry(spec, .unknown)
            if previous == nil { order.append(spec.identifier) }
            entries[spec.identifier] = entry
            if let previous, previous.runtime != .unknown { persist(spec.identifier, .unknown) }
            if register { openInitialWindow(entry, time) }
        }
        refreshDerived()
        reconcileSession()
        if session == true && suspended && list.contains(where: \.isActive) { blocked?() }
        log("info", "added \(list.count) geofence(s): \(ids.joined(separator: ", "))")
        changed(list, [])
    }

    func remove(_ ids: [String]) throws {
        guard !ids.isEmpty else { return }
        try ensureLoaded()
        var requested: [String] = []
        for id in ids where !requested.contains(id) { requested.append(id) }
        let existing = requested.filter { entries[$0] != nil }
        guard !existing.isEmpty else { return }
        do { try store.remove(existing) } catch { throw TrackingError("IO_ERROR", "failed to remove geofences") }
        if enabled() { backend.remove(existing.filter { entries[$0]!.spec.isActive }) }
        for id in existing { dwell.cancel(id); entries.removeValue(forKey: id) }
        order.removeAll { existing.contains($0) }
        refreshDerived()
        reconcileSession()
        changed([], existing)
    }

    func removeAll() throws {
        try ensureLoaded()
        let ids = order, registered = registrableIds()
        do { try store.removeAll() } catch { throw TrackingError("IO_ERROR", "failed to remove geofences") }
        if enabled() { backend.remove(registered) }
        dwell.cancelAll(); entries.removeAll(); order.removeAll()
        refreshDerived()
        reconcileSession()
        guard !ids.isEmpty else { return }
        changed([], ids)
    }

    func list() throws -> [GeofenceSpec] { try store.all() }
    func get(_ id: String) throws -> GeofenceSpec? { try store.get(id) }

    func onTrackingStarted(always: Bool = false) {
        session = true
        self.always = always
        do { try ensureLoaded() } catch { log("error", "cannot load geofences; none registered"); return }
        reconcileSession()
        registerAll()
        refreshDwellTimers()
        refreshDerived()
        if suspended && !registrableIds().isEmpty { blocked?() }
    }

    private func reconcileSession() {
        guard session == true else { return }
        backend.holdSession(always && !registrableIds().isEmpty)
    }

    func onTrackingStopped() {
        session = false
        dwell.cancelAll()
        lastStreamFix = nil
        backend.holdSession(false)
        do { try ensureLoaded() } catch { refreshDerived(); return }
        backend.remove(registrableIds())
        backend.retain([])
        for entry in entries.values {
            entry.observed = false; entry.initialLatched = false; entry.lastFix = nil; entry.resetCandidate()
            if entry.runtime != .unknown { persist(entry, .unknown) }
        }
        refreshDerived()
    }

    func releaseStale() { backend.retain([]) }

    func setSuspended(_ value: Bool) {
        guard value != suspended else { return }
        suspended = value
        guard session == true else { return }
        if value {
            dwell.cancelAll()
            backend.remove(registrableIds())
            if !registrableIds().isEmpty { blocked?() }
        } else {
            registerAll()
            refreshDwellTimers()
        }
        refreshDerived()
    }

    func onLocation(_ location: TrackedLocation) {
        if lastFix == nil || !location.isOlder(than: lastFix!) { lastFix = location }
        if session == false || !enabled() || suspended { return }
        if loaded && !wantsFixes && !dwell.hasArmed { return }
        do { try handleLocation(location) } catch { log("error", "failed to process location for geofences") }
    }

    func onTransitions(_ transitions: [OsGeofenceTransition]) {
        guard !transitions.isEmpty else { return }
        if session == false || !enabled() || suspended { log("warn", "ignoring \(transitions.count) geofence transition(s): tracking is stopped"); return }
        do { try ensureLoaded() } catch { log("error", "cannot load geofences; dropping \(transitions.count) transition(s)"); return }
        for transition in transitions {
            guard let entry = entries[transition.id] else { log("warn", "ignoring \(transition.action.rawValue) for unknown geofence '\(transition.id)'"); continue }
            if transition.initial && !(transition.action == .exit && entry.runtime.insideCircle && (entry.spec.isPolygon || !entry.observed)) { continue }
            if entry.spec.isPolygon { onPolygonCircleTransition(entry, transition) } else { onCircleTransition(entry, transition) }
        }
        refreshDerived()
    }

    func fireDue() { dwell.fireDue() }

    private func onCircleTransition(_ entry: Entry, _ transition: OsGeofenceTransition) {
        let spec = entry.spec
        let synthesize = spec.notifyOnDwell
        let wasObserved = entry.observed
        entry.observed = true
        switch transition.action {
        case .enter:
            let tracksState = (spec.notifyOnEntry || synthesize) && (spec.notifyOnExit || synthesize)
            if tracksState && wasObserved && entry.runtime.insideCircle { return }
            let time = now()
            persist(entry, GeofenceRuntime(insideCircle: true, insidePolygon: false, enteredAt: time))
            if spec.notifyOnEntry { record(spec, .enter, transition.location) }
            if synthesize { dwell.arm(spec.identifier, deadline: time + spec.loiteringDelay, token: time) }
        case .exit:
            dwell.cancel(spec.identifier)
            persist(entry, .unknown)
            if spec.notifyOnExit { record(spec, .exit, transition.location) }
        case .dwell:
            dwell.cancel(spec.identifier)
            if !entry.runtime.insideCircle { persist(entry, GeofenceRuntime(insideCircle: true, insidePolygon: false, enteredAt: now())) }
            if spec.notifyOnDwell { record(spec, .dwell, transition.location) }
        }
    }

    private func reconcileCircle(_ entry: Entry, _ location: TrackedLocation) {
        let spec = entry.spec
        let accuracy = Self.accuracy(location), fromCenter = Self.distanceFromCenter(spec, location)
        if fromCenter - accuracy > spec.radius {
            entry.observed = true
            dwell.cancel(spec.identifier)
            persist(entry, .unknown)
            if spec.notifyOnExit { record(spec, .exit, location) }
        } else if fromCenter + accuracy <= spec.radius {
            entry.observed = true
        }
    }

    private func onPolygonCircleTransition(_ entry: Entry, _ transition: OsGeofenceTransition) {
        switch transition.action {
        case .enter:
            var runtime = entry.runtime
            runtime.insideCircle = true
            if runtime.enteredAt == nil {
                if now() <= entry.initialUntil { entry.initialLatched = true }
                else { runtime.insidePolygon = false; runtime.enteredAt = now() }
            }
            if runtime != entry.runtime { persist(entry, runtime) }
            guard let location = transition.location else { return }
            if lastStreamFix == nil || !location.isOlder(than: lastStreamFix!) { evaluatePolygon(entry, location) }
        case .exit:
            let wasInside = entry.runtime.insidePolygon
            let since = wasInside ? now() : (entry.runtime.enteredAt ?? now())
            dwell.cancel(entry.spec.identifier)
            entry.resetCandidate()
            entry.initialLatched = false
            persist(entry, GeofenceRuntime(insideCircle: false, insidePolygon: false, enteredAt: since))
            if wasInside && entry.spec.notifyOnExit { record(entry.spec, .exit, transition.location) }
        case .dwell:
            break
        }
    }

    private func handleLocation(_ location: TrackedLocation) throws {
        if session == false || !enabled() { return }
        try ensureLoaded()
        if let previous = lastStreamFix {
            let olderBy = -Geo.elapsed(previous, location)
            if (olderBy >= 1 && olderBy <= Self.maxReorder) || (olderBy == 0 && Self.sameFix(previous, location)) { return }
        }
        lastStreamFix = location
        for id in order {
            guard let entry = entries[id] else { continue }
            if entry.spec.isPolygon { evaluatePolygon(entry, location) }
            else if !entry.observed && entry.runtime.insideCircle && entry.spec.isActive { reconcileCircle(entry, location) }
        }
        for (id, token) in dwell.takeDue() { fireDwell(id, token) }
        refreshDerived()
    }

    private func evaluatePolygon(_ entry: Entry, _ location: TrackedLocation) {
        let spec = entry.spec
        guard let vertices = spec.vertices, spec.isActive else { return }
        if let last = entry.lastFix, Self.sameFix(last, location) { return }
        entry.lastFix = location
        let accuracy = Self.accuracy(location), fromCenter = Self.distanceFromCenter(spec, location)
        let inside: Bool, certain: Bool
        if fromCenter - accuracy > spec.radius {
            inside = false; certain = true
            if entry.runtime.insideCircle { var runtime = entry.runtime; runtime.insideCircle = false; persist(entry, runtime) }
        } else {
            inside = PolygonMath.contains(vertices, location.latitude, location.longitude)
            certain = accuracy == 0 || PolygonMath.distanceToBoundary(vertices, location.latitude, location.longitude) >= accuracy
        }
        let known = entry.runtime.enteredAt != nil
        if known && inside == entry.runtime.insidePolygon { entry.resetCandidate(); return }
        if !certain {
            if entry.candidate == inside { entry.candidateCount += 1 } else { entry.candidate = inside; entry.candidateCount = 1 }
            if entry.candidateCount < Self.consistentFixes { return }
        }
        entry.resetCandidate()
        let time = now()
        let silent = !known && !initialTriggerEntry() && (entry.initialLatched || time <= entry.initialUntil)
        entry.initialLatched = false
        var runtime = entry.runtime
        if inside && silent {
            runtime.insidePolygon = true; runtime.enteredAt = Self.enteredBeforeMonitoring
            persist(entry, runtime)
        } else if inside {
            runtime.insidePolygon = true; runtime.enteredAt = time
            persist(entry, runtime)
            if spec.notifyOnEntry { record(spec, .enter, location) }
            if spec.notifyOnDwell { dwell.arm(spec.identifier, deadline: time + spec.loiteringDelay, token: time) }
        } else {
            dwell.cancel(spec.identifier)
            runtime.insidePolygon = false; runtime.enteredAt = time
            persist(entry, runtime)
            if known && spec.notifyOnExit { record(spec, .exit, location) }
        }
    }

    private func fireDwell(_ id: String, _ token: Double) {
        if session == false || !enabled() || suspended { return }
        guard let entry = entries[id] else { return }
        let inside = entry.spec.isPolygon ? entry.runtime.insidePolygon : entry.runtime.insideCircle
        guard inside, entry.runtime.enteredAt == token, entry.spec.notifyOnDwell else { return }
        record(entry.spec, .dwell, nil)
    }

    private func refreshDwellTimers() {
        let time = now()
        for id in order {
            guard let entry = entries[id] else { continue }
            let spec = entry.spec
            guard spec.notifyOnDwell else { dwell.cancel(id); continue }
            if dwell.isArmed(id) { continue }
            guard let enteredAt = entry.runtime.enteredAt else { continue }
            let inside = spec.isPolygon ? entry.runtime.insidePolygon : entry.runtime.insideCircle
            let deadline = enteredAt + spec.loiteringDelay
            if inside && enteredAt != Self.enteredBeforeMonitoring && deadline > time { dwell.arm(id, deadline: deadline, token: enteredAt) }
        }
    }

    private func record(_ spec: GeofenceSpec, _ action: GeofenceAction, _ location: TrackedLocation?) {
        log("info", "geofence \(action.rawValue) '\(spec.identifier)'")
        notify(spec, action, location ?? bestKnown())
    }

    private func bestKnown() -> TrackedLocation? {
        let stored = storedLocation()
        guard let fix = lastFix else { return stored }
        guard let stored else { return fix }
        return fix.isOlder(than: stored) ? stored : fix
    }

    private func registerAll() {
        let time = now()
        for id in order { if let entry = entries[id] { openInitialWindow(entry, time) } }
        backend.retain(Set(registrableIds()))
        guard !suspended else { return }
        register(order.compactMap { entries[$0]?.spec }, restoring: true)
    }

    private func openInitialWindow(_ entry: Entry, _ time: Double) {
        entry.initialUntil = time + Self.initialTriggerWindow
        entry.initialLatched = false
        let spec = entry.spec
        guard spec.isPolygon, entry.runtime.enteredAt == nil, let fix = bestKnown(), time - fix.time <= Self.initialTriggerWindow else { return }
        if Self.distanceFromCenter(spec, fix) - Self.accuracy(fix) > spec.radius { persist(entry, GeofenceRuntime(insideCircle: false, insidePolygon: false, enteredAt: time)) }
    }

    private func register(_ specs: [GeofenceSpec], restoring: Bool = false) {
        let trigger = initialTriggerEntry()
        let regions = specs.compactMap { Self.osGeofence($0, initialTriggerEntry: trigger) }
        guard !regions.isEmpty else { return }
        if restoring { backend.restore(regions) } else { backend.add(regions) }
    }

    private func registrableIds() -> [String] { order.filter { entries[$0]?.spec.isActive == true } }

    private func ensureLoaded() throws {
        if loaded { return }
        let specs = try store.all(), runtimes = try store.runtimes()
        let keep = enabled()
        entries.removeAll(); order.removeAll()
        for stored in specs {
            let spec = stored.isPolygon && !(stored.radius > 0) ? Self.withEnclosingCircle(stored) : stored
            let runtime = runtimes[spec.identifier]
            entries[spec.identifier] = Entry(spec, keep ? runtime ?? .unknown : .unknown)
            order.append(spec.identifier)
            if !keep, let runtime, runtime != .unknown { persist(spec.identifier, .unknown) }
        }
        loaded = true
        refreshDerived()
    }

    private func refreshDerived() {
        let polygons = order.compactMap { entries[$0] }.filter { $0.spec.isPolygon && $0.spec.isActive }
        wantsFixes = !polygons.isEmpty || entries.values.contains { !$0.spec.isPolygon && !$0.observed && $0.runtime.insideCircle && $0.spec.isActive }
        let needs = session == true && !suspended && polygons.contains { $0.runtime.insideCircle || $0.runtime.insidePolygon }
        if needs != needsContinuousLocation { needsContinuousLocation = needs; demandChanged?(needs) }
    }

    private func persist(_ entry: Entry, _ runtime: GeofenceRuntime) { entry.runtime = runtime; persist(entry.spec.identifier, runtime) }
    private func persist(_ id: String, _ runtime: GeofenceRuntime) {
        do { try store.setRuntime(id, runtime) } catch { log("warn", "failed to persist runtime of geofence '\(id)'") }
    }

    static func sameFix(_ a: TrackedLocation, _ b: TrackedLocation) -> Bool { a.time == b.time && a.latitude == b.latitude && a.longitude == b.longitude }
    static func distanceFromCenter(_ spec: GeofenceSpec, _ location: TrackedLocation) -> Double { PolygonMath.distanceMeters(spec.latitude, spec.longitude, location.latitude, location.longitude) }
    static func accuracy(_ location: TrackedLocation) -> Double { location.accuracy.isFinite && location.accuracy > 0 ? location.accuracy : 0 }

    static func osGeofence(_ spec: GeofenceSpec, initialTriggerEntry: Bool) -> OsGeofence? {
        guard spec.isActive else { return nil }
        if spec.isPolygon { return OsGeofence(id: spec.identifier, latitude: spec.latitude, longitude: spec.longitude, radius: spec.radius, onEntry: true, onExit: true, initialTriggerEntry: true) }
        return OsGeofence(id: spec.identifier, latitude: spec.latitude, longitude: spec.longitude, radius: spec.radius, onEntry: spec.notifyOnEntry || spec.notifyOnDwell, onExit: spec.notifyOnExit || spec.notifyOnDwell, initialTriggerEntry: initialTriggerEntry)
    }

    static func validated(_ spec: GeofenceSpec) throws -> GeofenceSpec {
        let id = spec.identifier
        if id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { throw invalid("geofence identifier is required") }
        if id == reservedIdentifier { throw invalid("geofence identifier '\(id)' is reserved") }
        if id.count > maxIdentifierLength { throw invalid("geofence identifier is longer than \(maxIdentifierLength) characters") }
        if spec.loiteringDelay < 0 { throw invalid("geofence '\(id)': loiteringDelay must be >= 0") }
        guard let vertices = spec.vertices else {
            if !PolygonMath.isValidLatLng(spec.latitude, spec.longitude) { throw invalid("geofence '\(id)': invalid latitude/longitude (\(spec.latitude), \(spec.longitude))") }
            if !(spec.radius.isFinite && spec.radius > 0) { throw invalid("geofence '\(id)': radius must be > 0") }
            return spec
        }
        if vertices.count < 3 { throw invalid("geofence '\(id)': a polygon needs at least 3 vertices") }
        for (index, vertex) in vertices.enumerated() where !PolygonMath.isValidLatLng(vertex.latitude, vertex.longitude) {
            throw invalid("geofence '\(id)': invalid vertices[\(index)] (\(vertex.latitude), \(vertex.longitude))")
        }
        let longitudes = vertices.map(\.longitude)
        if longitudes.max()! - longitudes.min()! > 180 { throw invalid("geofence '\(id)': polygons crossing the anti-meridian are not supported") }
        if Set(vertices).count < 3 || PolygonMath.areaSquareMeters(vertices) < minPolygonArea { throw invalid("geofence '\(id)': the polygon has no area") }
        return withEnclosingCircle(spec)
    }

    static func withEnclosingCircle(_ spec: GeofenceSpec) -> GeofenceSpec {
        guard let vertices = spec.vertices else { return spec }
        let circle = PolygonMath.enclosingCircle(vertices)
        var result = spec
        result.latitude = circle.latitude; result.longitude = circle.longitude; result.radius = Double(Float(circle.radius))
        return result
    }

    private static func invalid(_ message: String) -> TrackingError { TrackingError("INVALID_ARGUMENT", message) }
}
