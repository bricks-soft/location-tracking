import Foundation
import CoreLocation

struct MonitoredCondition: Equatable {
    var state: MonitorState
    var latitude: Double
    var longitude: Double
    var radius: Double
}

struct MonitorEventValue {
    var id: String
    var state: MonitorState
    var diagnostics: [(String, String)]
}

protocol MonitorClient: AnyObject {
    func identifiers() async -> [String]
    func condition(_ id: String) async -> MonitoredCondition?
    func add(_ region: OsGeofence) async
    func remove(_ id: String) async
    func consume(_ handler: @escaping (MonitorEventValue) -> Void) async throws
}

final class GeofenceMonitor: GeofenceBackend, @unchecked Sendable {
    static let defaultName = "BricksSoftLocationTrackingGeofences"
    private static var instance: GeofenceMonitor?
    @available(iOS 17.0, *)
    static func shared(queue: DispatchQueue, authorization: @escaping () -> CLAuthorizationStatus) -> GeofenceMonitor {
        if let instance { return instance }
        let created = GeofenceMonitor(queue: queue, authorization: authorization) { await CoreLocationMonitorClient(name: defaultName) }
        instance = created
        return created
    }
    private let queue: DispatchQueue
    private let authorization: () -> CLAuthorizationStatus
    private let factory: () async -> MonitorClient
    private var client: Task<MonitorClient, Never>?
    private var tail: Task<Void, Never>?
    private var session: AnyObject?
    let adapter = GeofenceEventAdapter()
    var transitions: (([OsGeofenceTransition]) -> Void)?
    var failures: (([MonitorFailure]) -> Void)?
    var isOpen: Bool { client != nil }

    init(queue: DispatchQueue, authorization: @escaping () -> CLAuthorizationStatus, factory: @escaping () async -> MonitorClient) {
        self.queue = queue; self.authorization = authorization; self.factory = factory
    }

    private func onQueue(_ body: @escaping () -> Void) async {
        await withCheckedContinuation { continuation in queue.async { body(); continuation.resume() } }
    }

    private func open() -> Task<MonitorClient, Never>? {
        if let client { return client }
        guard authorization() != .notDetermined else { return nil }
        let factory = self.factory
        let opened = Task { await factory() }
        client = opened
        tail = Task { [weak self] in
            let client = await opened.value
            var persisted: [(String, MonitorState)] = []
            for id in await client.identifiers() { if let condition = await client.condition(id) { persisted.append((id, condition.state)) } }
            await self?.onQueue { persisted.forEach { self?.adapter.seed($0.0, $0.1) } }
            Task { [weak self] in
                do {
                    try await client.consume { value in self?.queue.async { self?.handle(value) } }
                } catch {
                    self?.queue.async { self?.failures?([MonitorFailure(id: "", code: "UNAVAILABLE", message: "geofence monitoring stopped: " + error.localizedDescription)]) }
                }
            }
        }
        return opened
    }

    private func handle(_ value: MonitorEventValue) {
        publish(adapter.handle(value.id, value.state, failures: value.diagnostics))
    }

    private func publish(_ result: ([OsGeofenceTransition], [MonitorFailure])) {
        if !result.1.isEmpty { failures?(result.1) }
        if !result.0.isEmpty { transitions?(result.0) }
    }

    private func enqueue(_ operation: @escaping (MonitorClient) async -> Void) {
        guard let opened = open() else { return }
        let previous = tail
        tail = Task {
            await previous?.value
            await operation(await opened.value)
        }
    }

    func whenIdle(_ completion: @escaping () -> Void) {
        guard client != nil else { completion(); return }
        enqueue { [weak self] _ in await self?.onQueue(completion) }
    }

    func add(_ regions: [OsGeofence]) {
        guard open() != nil else { return }
        for region in regions {
            adapter.expect(region.id)
            enqueue { [weak self] client in await self?.register(region, client) }
        }
    }

    func restore(_ regions: [OsGeofence]) {
        guard open() != nil else { return }
        for region in regions {
            adapter.expect(region.id)
            enqueue { [weak self] client in
                guard let self else { return }
                if let saved = await client.condition(region.id), Self.same(saved, region) {
                    await self.onQueue { self.publish(self.adapter.restored(region.id, initialTriggerEntry: region.initialTriggerEntry, state: saved.state)) }
                } else {
                    await self.register(region, client)
                }
            }
        }
    }

    private func register(_ region: OsGeofence, _ client: MonitorClient) async {
        await onQueue { self.adapter.registered(region.id, initialTriggerEntry: region.initialTriggerEntry) }
        await client.add(region)
    }

    private static func same(_ saved: MonitoredCondition, _ region: OsGeofence) -> Bool {
        abs(saved.latitude - region.latitude) < 1e-9 && abs(saved.longitude - region.longitude) < 1e-9 && abs(saved.radius - region.radius) < 1e-6
    }

    func remove(_ ids: [String]) {
        for id in ids {
            adapter.removed(id)
            enqueue { [weak self] client in
                await client.remove(id)
                await self?.onQueue { self?.adapter.removed(id) }
            }
        }
    }

    func retain(_ ids: Set<String>) {
        adapter.retain(ids)
        enqueue { [weak self] client in
            for id in await client.identifiers() where !ids.contains(id) { await client.remove(id) }
            await self?.onQueue { self?.adapter.retain(ids) }
        }
    }

    func holdSession(_ always: Bool) {
        guard #available(iOS 18.0, *) else { return }
        if always { if session == nil { session = CLServiceSession(authorization: .always) } }
        else { (session as? CLServiceSession)?.invalidate(); session = nil }
    }
}

@available(iOS 17.0, *)
final class CoreLocationMonitorClient: MonitorClient {
    private let monitor: CLMonitor
    init(name: String) async { monitor = await CLMonitor(name) }

    func identifiers() async -> [String] { await monitor.identifiers }

    func condition(_ id: String) async -> MonitoredCondition? {
        guard let record = await monitor.record(for: id), let circle = record.condition as? CLMonitor.CircularGeographicCondition else { return nil }
        return MonitoredCondition(state: Self.state(record.lastEvent.state), latitude: circle.center.latitude, longitude: circle.center.longitude, radius: circle.radius)
    }

    func add(_ region: OsGeofence) async {
        let condition = CLMonitor.CircularGeographicCondition(center: CLLocationCoordinate2D(latitude: region.latitude, longitude: region.longitude), radius: region.radius)
        await monitor.add(condition, identifier: region.id, assuming: .unknown)
    }

    func remove(_ id: String) async { await monitor.remove(id) }

    func consume(_ handler: @escaping (MonitorEventValue) -> Void) async throws {
        for try await event in await monitor.events {
            handler(MonitorEventValue(id: event.identifier, state: Self.state(event.state), diagnostics: Self.diagnostics(event)))
        }
    }

    private static func state(_ value: CLMonitor.Event.State) -> MonitorState {
        switch value {
        case .satisfied: return .satisfied
        case .unsatisfied: return .unsatisfied
        case .unknown: return .unknown
        default: return .unmonitored
        }
    }

    private static func diagnostics(_ event: CLMonitor.Event) -> [(String, String)] {
        guard #available(iOS 18.0, *) else { return [] }
        var result: [(String, String)] = []
        if event.conditionLimitExceeded { result.append(("TOO_MANY_GEOFENCES", "Core Location suspended monitoring because the app monitors too many conditions")) }
        if event.accuracyLimited { result.append(("PERMISSION_DENIED", "Core Location suspended monitoring while location accuracy is approximate")) }
        if event.authorizationDeniedGlobally { result.append(("LOCATION_DISABLED", "Core Location suspended monitoring while Location Services are off")) }
        else if event.authorizationDenied || event.authorizationRestricted { result.append(("PERMISSION_DENIED", "Core Location suspended monitoring because location authorization is denied")) }
        if event.conditionUnsupported { result.append(("UNAVAILABLE", "Core Location does not support monitoring this condition")) }
        if event.persistenceUnavailable { result.append(("IO_ERROR", "Core Location could not store the monitored condition")) }
        if event.serviceSessionRequired { result.append(("UNAVAILABLE", "Core Location requires a service session for this app")) }
        return result
    }
}
