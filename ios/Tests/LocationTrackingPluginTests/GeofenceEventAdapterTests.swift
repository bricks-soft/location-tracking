import XCTest
import CoreLocation
@testable import LocationTrackingPlugin

final class GeofenceEventAdapterTests: XCTestCase {
    let adapter = GeofenceEventAdapter()
    func actions(_ id: String, _ state: MonitorState, _ diagnostics: [(String, String)] = []) -> [String] {
        adapter.handle(id, state, failures: diagnostics).0.map { $0.action.rawValue + ($0.initial ? ":initial" : "") }
    }

    func testInitialTriggerEntryFalseKeepsAUserAlreadyInsideSilentAndReportsLaterTransitions() {
        adapter.registered("home", initialTriggerEntry: false)
        XCTAssertEqual(actions("home", .satisfied), [])
        XCTAssertEqual(actions("home", .satisfied), [])
        XCTAssertEqual(actions("home", .unsatisfied), ["EXIT"])
        XCTAssertEqual(actions("home", .satisfied), ["ENTER"])
    }
    func testInitialTriggerEntryTrueReportsTheInitialInsideState() {
        adapter.registered("home", initialTriggerEntry: true)
        XCTAssertEqual(actions("home", .satisfied), ["ENTER"])
        XCTAssertEqual(actions("home", .unsatisfied), ["EXIT"])
    }
    func testAnInitialOutsideDeterminationIsMarkedInitialAndARealEntryFollows() {
        adapter.registered("home", initialTriggerEntry: false)
        XCTAssertEqual(actions("home", .unsatisfied), ["EXIT:initial"])
        XCTAssertEqual(actions("home", .satisfied), ["ENTER"])
    }
    func testUnknownUnmonitoredAndDiagnosticEventsDoNotConsumeTheInitialDetermination() {
        adapter.registered("home", initialTriggerEntry: false)
        XCTAssertEqual(actions("home", .unknown), [])
        XCTAssertEqual(actions("home", .unmonitored), [])
        let (transitions, failures) = adapter.handle("home", .satisfied, failures: [("TOO_MANY_GEOFENCES", "limit")])
        XCTAssertTrue(transitions.isEmpty); XCTAssertEqual(failures, [MonitorFailure(id: "home", code: "TOO_MANY_GEOFENCES", message: "geofence 'home': limit")])
        XCTAssertEqual(actions("home", .satisfied), [], "the user already inside stays silent")
        XCTAssertEqual(actions("home", .unsatisfied), ["EXIT"])
    }
    func testAPersistedInsideStateReportsTheExitMissedWhileTheAppWasGone() {
        adapter.seed("home", .satisfied)
        adapter.seed("home", .unsatisfied)
        _ = adapter.restored("home", initialTriggerEntry: true, state: .unsatisfied)
        XCTAssertEqual(actions("home", .unsatisfied), ["EXIT"])
        XCTAssertEqual(actions("home", .unsatisfied), [])
    }
    func testEventsWaitForTheRestoreOrRegistrationOfTheirGeofence() {
        adapter.seed("home", .unknown); adapter.expect("home")
        XCTAssertEqual(actions("home", .satisfied), [])
        XCTAssertEqual(adapter.restored("home", initialTriggerEntry: false, state: .unknown).0, [], "the held first determination uses the restored initialTriggerEntry false")
        XCTAssertEqual(actions("home", .unsatisfied), ["EXIT"])
        adapter.seed("gym", .unsatisfied)
        XCTAssertEqual(actions("gym", .satisfied), [])
        XCTAssertEqual(adapter.restored("gym", initialTriggerEntry: false, state: .unsatisfied).0.map(\.action), [.enter])
        adapter.seed("work", .unknown); adapter.expect("work")
        XCTAssertEqual(actions("work", .satisfied), [])
        adapter.registered("work", initialTriggerEntry: true)
        XCTAssertEqual(actions("work", .unsatisfied), ["EXIT:initial"], "a fresh registration drops the events held for the condition it replaces")
    }
    func testFailuresAreReportedOncePerGeofenceAndCodeUntilAHealthyEvent() {
        adapter.registered("home", initialTriggerEntry: true)
        XCTAssertEqual(adapter.handle("home", .unknown, failures: [("TOO_MANY_GEOFENCES", "limit")]).1.count, 1)
        XCTAssertEqual(adapter.handle("home", .unknown, failures: [("TOO_MANY_GEOFENCES", "limit")]).1.count, 0)
        XCTAssertEqual(adapter.handle("home", .unknown, failures: [("PERMISSION_DENIED", "approximate")]).1.map(\.code), ["PERMISSION_DENIED"])
        XCTAssertEqual(actions("home", .satisfied), ["ENTER"])
        XCTAssertEqual(adapter.handle("home", .unknown, failures: [("TOO_MANY_GEOFENCES", "limit")]).1.count, 1)
    }
    func testRemovedAndUnknownIdentifiersAreIgnored() {
        adapter.registered("home", initialTriggerEntry: true)
        adapter.removed("home")
        XCTAssertEqual(actions("home", .satisfied), [])
        XCTAssertEqual(actions("ghost", .unsatisfied), [])
        adapter.registered("a", initialTriggerEntry: true); adapter.registered("b", initialTriggerEntry: true)
        adapter.retain(["b"])
        XCTAssertEqual(actions("a", .satisfied), []); XCTAssertEqual(actions("b", .satisfied), ["ENTER"])
    }

}

final class FakeMonitorClient: MonitorClient {
    var saved: [String: MonitoredCondition] = [:]
    var added: [String] = []
    private let lock = NSLock()
    private var lookups = 0
    private var holdAfter: Int?
    private var waiting: [CheckedContinuation<Void, Never>] = []
    private let stream: AsyncStream<MonitorEventValue>
    private let continuation: AsyncStream<MonitorEventValue>.Continuation
    init() {
        var captured: AsyncStream<MonitorEventValue>.Continuation!
        stream = AsyncStream { captured = $0 }
        continuation = captured
    }
    func identifiers() async -> [String] { saved.keys.sorted() }
    func condition(_ id: String) async -> MonitoredCondition? {
        let hold: Bool = locked { lookups += 1; return holdAfter.map { lookups > $0 } ?? false }
        if hold { await withCheckedContinuation { park($0) } }
        return saved[id]
    }
    func holdLookups(after count: Int) { locked { holdAfter = count } }
    var heldLookups: Int { locked { waiting.count } }
    func releaseLookups() {
        let resumed: [CheckedContinuation<Void, Never>] = locked { holdAfter = nil; defer { waiting = [] }; return waiting }
        resumed.forEach { $0.resume() }
    }
    private func park(_ continuation: CheckedContinuation<Void, Never>) {
        let parked: Bool = locked { if holdAfter == nil { return false }; waiting.append(continuation); return true }
        if !parked { continuation.resume() }
    }
    private func locked<T>(_ body: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return body() }
    func add(_ region: OsGeofence) async { added.append(region.id); saved[region.id] = MonitoredCondition(state: .unknown, latitude: region.latitude, longitude: region.longitude, radius: region.radius) }
    func remove(_ id: String) async { saved[id] = nil }
    func consume(_ handler: @escaping (MonitorEventValue) -> Void) async throws { for await value in stream { handler(value) } }
    func emit(_ id: String, _ state: MonitorState) { continuation.yield(MonitorEventValue(id: id, state: state, diagnostics: [])) }
}

@MainActor
final class GeofenceMonitorTests: XCTestCase {
    let lat = GeofenceFixtures.lat, lng = GeofenceFixtures.lng
    var hits: [String] = []
    var initialTriggerEntry = false

    func saved(_ state: MonitorState, radius: Double = 100) -> MonitoredCondition { MonitoredCondition(state: state, latitude: lat, longitude: lng, radius: radius) }
    func idle(_ monitor: GeofenceMonitor) async { await withCheckedContinuation { continuation in monitor.whenIdle { continuation.resume() } } }
    func waitForHits(_ count: Int) async {
        var attempts = 0
        while attempts < 300, hits.count < count {
            attempts += 1
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
    }
    func settle(_ client: FakeMonitorClient) async {
        var attempts = 0
        while attempts < 300, client.heldLookups == 0 {
            attempts += 1
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
    }
    func store(_ runtimes: [String: GeofenceRuntime]) -> FakeGeofenceStore {
        let store = FakeGeofenceStore()
        for (id, runtime) in runtimes.sorted(by: { $0.key < $1.key }) {
            try? store.upsert([GeofenceFixtures.circle(id, latitude: lat, longitude: lng, radius: 100)]); try? store.setRuntime(id, runtime)
        }
        return store
    }
    func relaunch(_ client: FakeMonitorClient, _ store: FakeGeofenceStore, status: CLAuthorizationStatus = .authorizedAlways) -> (GeofenceMonitor, GeofenceManager) {
        let monitor = GeofenceMonitor(queue: .main, authorization: { status }) { client }
        let manager = GeofenceManager(store: store, backend: monitor, queue: .main, now: { GeofenceHarness.defaultNow }, automaticTimers: false, initialTriggerEntry: { [unowned self] in self.initialTriggerEntry }, enabled: { true }, storedLocation: { nil }, notify: { [unowned self] spec, action, _ in self.hits.append(spec.identifier + ":" + action.rawValue) }, changed: { _, _ in })
        monitor.transitions = { [weak manager] in manager?.onTransitions($0) }
        return (monitor, manager)
    }
    let inside = GeofenceRuntime(insideCircle: true, insidePolygon: false, enteredAt: GeofenceHarness.defaultNow - 60000)

    func testAPersistedOutsideCircleEnteredWhileTheAppWasGoneReportsEnterWithInitialTriggerDisabled() async {
        let client = FakeMonitorClient()
        client.saved = ["home": saved(.unsatisfied)]
        let (monitor, manager) = relaunch(client, store(["home": .unknown]))
        manager.onTrackingStarted()
        await idle(monitor)
        XCTAssertEqual(client.added, [], "an unchanged saved condition is restored, not registered again")
        client.emit("home", .satisfied)
        await waitForHits(1)
        XCTAssertEqual(hits, ["home:ENTER"])
    }
    func testAPersistedInsideCircleLeftWhileTheAppWasGoneReportsExit() async {
        let client = FakeMonitorClient()
        client.saved = ["home": saved(.satisfied)]
        let (monitor, manager) = relaunch(client, store(["home": inside]))
        manager.onTrackingStarted()
        await idle(monitor)
        client.emit("home", .unsatisfied)
        await waitForHits(1)
        XCTAssertEqual(hits, ["home:EXIT"])
        client.emit("home", .unsatisfied)
        client.emit("home", .satisfied)
        await waitForHits(2)
        XCTAssertEqual(hits, ["home:EXIT", "home:ENTER"])
    }
    func testEventsPendingWhenTheMonitorOpensAreComparedWithTheSavedState() async {
        let client = FakeMonitorClient()
        client.saved = ["home": saved(.unsatisfied), "work": saved(.satisfied)]
        client.emit("home", .satisfied); client.emit("work", .unsatisfied)
        let (monitor, manager) = relaunch(client, store(["home": .unknown, "work": inside]))
        manager.onTrackingStarted()
        await idle(monitor)
        await waitForHits(2)
        XCTAssertEqual(hits, ["home:ENTER", "work:EXIT"])
    }
    func testAPendingFirstDeterminationWaitsForTheRestoredInitialTriggerSetting() async {
        let client = FakeMonitorClient()
        client.saved = ["home": saved(.unknown)]
        client.holdLookups(after: 1)
        let (monitor, manager) = relaunch(client, store(["home": .unknown]))
        manager.onTrackingStarted()
        await settle(client)
        XCTAssertEqual(client.heldLookups, 1, "the restore is delayed after the saved records were loaded")
        client.emit("home", .satisfied)
        try? await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(hits, [], "the pending determination is not handled before initialTriggerEntry false is restored")
        client.releaseLookups()
        await idle(monitor)
        XCTAssertEqual(hits, []); XCTAssertEqual(client.added, [])
        client.emit("home", .unsatisfied)
        await waitForHits(1)
        XCTAssertEqual(hits, ["home:EXIT"])
    }
    func testAPendingEventOfAChangedConditionIsDroppedByItsFreshRegistration() async {
        let client = FakeMonitorClient()
        client.saved = ["home": saved(.unknown, radius: 50)]
        client.holdLookups(after: 1)
        let (monitor, manager) = relaunch(client, store(["home": .unknown]))
        manager.onTrackingStarted()
        await settle(client)
        XCTAssertEqual(client.heldLookups, 1, "the registration is delayed after the saved records were loaded")
        client.emit("home", .satisfied)
        try? await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(hits, [], "the replaced condition's pending event is not handled before the fresh registration")
        client.releaseLookups()
        await idle(monitor)
        XCTAssertEqual(hits, []); XCTAssertEqual(client.added, ["home"])
        client.emit("home", .satisfied); client.emit("home", .unsatisfied)
        await waitForHits(1)
        XCTAssertEqual(hits, ["home:EXIT"], "the new condition's initial inside determination stays silent")
    }
    func testARestoredStateThatDidNotChangeStaysSilentUntilARealTransition() async {
        initialTriggerEntry = true
        let client = FakeMonitorClient()
        client.saved = ["home": saved(.satisfied), "work": saved(.unsatisfied)]
        let (monitor, manager) = relaunch(client, store(["home": inside, "work": .unknown]))
        manager.onTrackingStarted()
        await idle(monitor)
        client.emit("home", .satisfied); client.emit("work", .unsatisfied); client.emit("work", .satisfied)
        await waitForHits(1)
        await idle(monitor)
        XCTAssertEqual(hits, ["work:ENTER"])
    }
    func testAChangedSavedConditionIsRegisteredAgainWithItsInitialDetermination() async {
        let client = FakeMonitorClient()
        client.saved = ["home": saved(.unsatisfied, radius: 50)]
        let (monitor, manager) = relaunch(client, store(["home": .unknown]))
        manager.onTrackingStarted()
        await idle(monitor)
        XCTAssertEqual(client.added, ["home"])
        client.emit("home", .satisfied); client.emit("home", .unsatisfied)
        await waitForHits(1)
        XCTAssertEqual(hits, ["home:EXIT"], "the initial inside determination stays silent with initialTriggerEntry false")
    }
    func testReplacingAGeofenceRegistersItAfreshEvenWhenTheConditionIsUnchanged() async throws {
        initialTriggerEntry = true
        let client = FakeMonitorClient()
        client.saved = ["home": saved(.satisfied)]
        let (monitor, manager) = relaunch(client, store(["home": inside]))
        manager.onTrackingStarted()
        await idle(monitor)
        XCTAssertEqual(client.added, [])
        try manager.add([GeofenceFixtures.circle("home", latitude: lat, longitude: lng, radius: 100)])
        await idle(monitor)
        XCTAssertEqual(client.added, ["home"])
        client.emit("home", .satisfied)
        await waitForHits(1)
        XCTAssertEqual(hits, ["home:ENTER"])
    }
    func testRemovedAndStaleConditionsAreNotRevivedBySavedRecords() async {
        let client = FakeMonitorClient()
        client.saved = ["home": saved(.unsatisfied), "stale": saved(.unsatisfied)]
        let (monitor, manager) = relaunch(client, store(["home": .unknown]))
        manager.onTrackingStarted()
        await idle(monitor)
        XCTAssertEqual(client.saved.keys.sorted(), ["home"])
        client.emit("stale", .satisfied)
        try? manager.remove(["home"])
        await idle(monitor)
        client.emit("home", .satisfied)
        await idle(monitor)
        XCTAssertEqual(hits, [])
        XCTAssertTrue(client.saved.isEmpty)
    }
    func testTheMonitorDoesNotOpenWhileLocationAuthorizationIsUndetermined() async {
        var status = CLAuthorizationStatus.notDetermined
        var opened = 0
        let client = FakeMonitorClient()
        let monitor = GeofenceMonitor(queue: .main, authorization: { status }) { opened += 1; return client }
        let region = OsGeofence(id: "home", latitude: lat, longitude: lng, radius: 100, onEntry: true, onExit: true, initialTriggerEntry: false)
        monitor.add([region]); monitor.restore([region]); monitor.remove(["home"]); monitor.retain([]); monitor.holdSession(false)
        await idle(monitor)
        XCTAssertFalse(monitor.isOpen); XCTAssertEqual(opened, 0)
        status = .denied
        monitor.retain([])
        await idle(monitor)
        XCTAssertTrue(monitor.isOpen); XCTAssertEqual(opened, 1)
    }
}
