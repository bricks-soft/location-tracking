import XCTest
@testable import LocationTrackingPlugin

final class FakeGeofenceBackend: GeofenceBackend {
    var registered: [String: OsGeofence] = [:]
    var addCalls: [[OsGeofence]] = []
    var restoreCalls: [[OsGeofence]] = []
    var removeCalls: [[String]] = []
    var sessions: [Bool] = []
    func add(_ regions: [OsGeofence]) { addCalls.append(regions); regions.forEach { registered[$0.id] = $0 } }
    func restore(_ regions: [OsGeofence]) { restoreCalls.append(regions); regions.forEach { registered[$0.id] = $0 } }
    func remove(_ ids: [String]) { guard !ids.isEmpty else { return }; removeCalls.append(ids); ids.forEach { registered.removeValue(forKey: $0) } }
    func retain(_ ids: Set<String>) { registered = registered.filter { ids.contains($0.key) } }
    func holdSession(_ always: Bool) { sessions.append(always) }
}

final class FakeGeofenceStore: GeofenceStore {
    private var specs: [String: GeofenceSpec] = [:]
    private var states: [String: GeofenceRuntime] = [:]
    var failWrites = false
    var failReads = false
    var count: Int { specs.count }
    func upsert(_ list: [GeofenceSpec]) throws { if failWrites { throw TrackingError("IO_ERROR", "disk") }; list.forEach { specs[$0.identifier] = $0 } }
    func remove(_ ids: [String]) throws { if failWrites { throw TrackingError("IO_ERROR", "disk") }; ids.forEach { specs.removeValue(forKey: $0); states.removeValue(forKey: $0) } }
    func removeAll() throws { if failWrites { throw TrackingError("IO_ERROR", "disk") }; specs.removeAll(); states.removeAll() }
    func all() throws -> [GeofenceSpec] { if failReads { throw TrackingError("IO_ERROR", "disk") }; return specs.values.sorted { $0.identifier.utf8.lexicographicallyPrecedes($1.identifier.utf8) } }
    func get(_ id: String) throws -> GeofenceSpec? { specs[id] }
    func setRuntime(_ id: String, _ runtime: GeofenceRuntime) throws { if specs[id] != nil { states[id] = runtime } }
    func runtimes() throws -> [String: GeofenceRuntime] { if failReads { throw TrackingError("IO_ERROR", "disk") }; return states }
}

final class GeofenceHarness {
    static let defaultNow = 1790417730456.0
    var now = defaultNow
    var enabled: Bool
    var initialTriggerEntry: Bool
    var lastLocation: TrackedLocation?
    let backend = FakeGeofenceBackend()
    let store: FakeGeofenceStore
    var hits: [(String, GeofenceAction, TrackedLocation?)] = []
    var changes: [([GeofenceSpec], [String])] = []
    private var fixTime = 1790417730123.0
    lazy var manager = makeManager(store)
    init(enabled: Bool = true, initialTriggerEntry: Bool = true, store: FakeGeofenceStore = FakeGeofenceStore()) {
        self.enabled = enabled; self.initialTriggerEntry = initialTriggerEntry; self.store = store
    }
    func makeManager(_ store: GeofenceStore) -> GeofenceManager {
        GeofenceManager(store: store, backend: backend, queue: .main, now: { [unowned self] in self.now }, automaticTimers: false, initialTriggerEntry: { [unowned self] in self.initialTriggerEntry }, enabled: { [unowned self] in self.enabled }, storedLocation: { [unowned self] in self.lastLocation }, notify: { [unowned self] in self.hits.append(($0.identifier, $1, $2)) }, changed: { [unowned self] in self.changes.append(($0, $1)) })
    }
    func fix(_ north: Double, _ east: Double = 0, accuracy: Double = 5) -> TrackedLocation {
        fixTime += 1000
        return EngineFixtures.moved(EngineFixtures.location(accuracy: accuracy, time: fixTime), north, east: east, milliseconds: 0)
    }
    func transition(_ id: String, _ action: GeofenceAction, _ location: TrackedLocation? = nil) { manager.onTransitions([OsGeofenceTransition(id: id, action: action, location: location)]) }
    func advance(_ ms: Double) { now += ms; manager.fireDue() }
    var actions: [String] { hits.map { $0.0 + ":" + $0.1.rawValue } }
}

final class GeofenceManagerTests: XCTestCase {
    let lat = GeofenceFixtures.lat, lng = GeofenceFixtures.lng
    func circle(_ id: String = "home", latitude: Double? = nil, longitude: Double? = nil, radius: Double = 100, notifyOnEntry: Bool = true, notifyOnExit: Bool = true, notifyOnDwell: Bool = false, loiteringDelay: Double = 30000, extras: TrackingJSON? = nil) -> GeofenceSpec {
        GeofenceFixtures.circle(id, latitude: latitude ?? lat, longitude: longitude ?? lng, radius: radius, notifyOnEntry: notifyOnEntry, notifyOnExit: notifyOnExit, notifyOnDwell: notifyOnDwell, loiteringDelay: loiteringDelay, extras: extras)
    }
    func polygon(_ id: String = "zone", vertices: [LatLng]? = nil, notifyOnEntry: Bool = true, notifyOnDwell: Bool = false, loiteringDelay: Double = 30000, extras: TrackingJSON? = nil) -> GeofenceSpec {
        GeofenceFixtures.polygon(id, vertices: vertices ?? GeofenceFixtures.square(lat, lng, 100), notifyOnEntry: notifyOnEntry, notifyOnDwell: notifyOnDwell, loiteringDelay: loiteringDelay, extras: extras)
    }
    func rejected(_ code: String, _ block: () throws -> Void, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try block(), file: file, line: line) { XCTAssertEqual(($0 as? TrackingError)?.code, code, file: file, line: line) }
    }
    func normalized(_ spec: GeofenceSpec) -> GeofenceSpec { GeofenceManager.withEnclosingCircle(spec) }
    func restoredStore(_ spec: GeofenceSpec, _ runtime: GeofenceRuntime) -> FakeGeofenceStore {
        let store = FakeGeofenceStore()
        try? store.upsert([normalized(spec)]); try? store.setRuntime(spec.identifier, runtime)
        return store
    }

    func testAddRejectsInvalidGeofencesWithInvalidArgumentAndStoresNothing() {
        let h = GeofenceHarness()
        let invalid = [
            circle(""), circle("   "), circle(String(repeating: "x", count: 101)), circle(GeofenceManager.reservedIdentifier),
            circle(radius: 0), circle(radius: -5), circle(radius: .nan), circle(latitude: 90.5), circle(longitude: -180.5), circle(latitude: .nan), circle(loiteringDelay: -1),
            polygon(vertices: [LatLng(latitude: lat, longitude: lng), LatLng(latitude: lat + 0.001, longitude: lng)]),
            polygon(vertices: [LatLng(latitude: lat, longitude: lng), LatLng(latitude: lat + 0.001, longitude: lng), LatLng(latitude: 95, longitude: lng)]),
            polygon(vertices: [LatLng(latitude: lat, longitude: lng), LatLng(latitude: lat + 0.001, longitude: lng), LatLng(latitude: lat + 0.002, longitude: lng)]),
            polygon(vertices: [LatLng(latitude: lat, longitude: lng), LatLng(latitude: lat, longitude: lng), LatLng(latitude: lat + 0.001, longitude: lng + 0.001)]),
            polygon(vertices: [LatLng(latitude: 0, longitude: 179.9), LatLng(latitude: 0.1, longitude: -179.9), LatLng(latitude: -0.1, longitude: -179.9)]),
        ]
        for spec in invalid { rejected("INVALID_ARGUMENT") { try h.manager.add([circle("ok"), spec]) } }
        XCTAssertEqual(h.store.count, 0); XCTAssertTrue(h.backend.addCalls.isEmpty); XCTAssertTrue(h.changes.isEmpty)
    }
    func testMoreThan20GeofencesInTotalIsTooManyGeofences() throws {
        let h = GeofenceHarness()
        try h.manager.add((1...19).map { circle("g\($0)") })
        rejected("TOO_MANY_GEOFENCES") { try h.manager.add([circle("new1"), circle("new2")]) }
        XCTAssertEqual(h.store.count, 19)
        try h.manager.add([circle("g1", radius: 300), circle("new1")])
        XCTAssertEqual(h.store.count, 20); XCTAssertEqual(try h.store.get("g1")?.radius, 300)
        rejected("TOO_MANY_GEOFENCES") { try h.manager.add([circle("new2")]) }
        XCTAssertEqual(h.store.count, 20)
    }
    func testAddWhileTrackingIsDisabledStoresAndEmitsButDoesNotRegister() throws {
        let h = GeofenceHarness(enabled: false), home = circle("home", extras: ["a": 1])
        try h.manager.add([home])
        XCTAssertEqual(try h.store.all(), [home]); XCTAssertTrue(h.backend.addCalls.isEmpty)
        XCTAssertEqual(h.changes.count, 1); XCTAssertEqual(h.changes[0].0, [home]); XCTAssertEqual(h.changes[0].1, [])
        XCTAssertEqual(try h.manager.list(), [home]); XCTAssertEqual(try h.manager.get("home"), home); XCTAssertNil(try h.manager.get("nope"))
    }
    func testAddWhileTrackingRegistersCirclesWithTheOS() throws {
        let h = GeofenceHarness(initialTriggerEntry: false)
        try h.manager.add([circle("a", radius: 150, notifyOnDwell: true, loiteringDelay: 60000), circle("b", notifyOnEntry: false)])
        XCTAssertEqual(h.backend.registered["a"], OsGeofence(id: "a", latitude: lat, longitude: lng, radius: 150, onEntry: true, onExit: true, initialTriggerEntry: false))
        XCTAssertEqual(h.backend.registered["b"], OsGeofence(id: "b", latitude: lat, longitude: lng, radius: 100, onEntry: false, onExit: true, initialTriggerEntry: false))
    }
    func testPolygonGetsAPaddedEnclosingCircleAndIsRegisteredWithEnterAndExitOnly() throws {
        let h = GeofenceHarness(initialTriggerEntry: false), zone = polygon("zone", notifyOnDwell: true)
        try h.manager.add([zone])
        let stored = try XCTUnwrap(try h.store.get("zone"))
        XCTAssertEqual(stored.vertices, zone.vertices)
        XCTAssertEqual(PolygonMath.distanceMeters(lat, lng, stored.latitude, stored.longitude), 0, accuracy: 1)
        XCTAssertEqual(stored.radius, 100 * sqrt(2.0) * 1.1, accuracy: 1)
        for vertex in zone.vertices! { XCTAssertLessThan(PolygonMath.distanceMeters(stored.latitude, stored.longitude, vertex.latitude, vertex.longitude), stored.radius) }
        let os = try XCTUnwrap(h.backend.registered["zone"])
        XCTAssertEqual(os.radius, stored.radius); XCTAssertTrue(os.onEntry && os.onExit && os.initialTriggerEntry)
        XCTAssertEqual(h.changes.count, 1); XCTAssertEqual(h.changes[0].0, [stored])
    }
    func testGeofencesWithEveryNotificationOffAreStoredButNotRegistered() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("mute", notifyOnEntry: false, notifyOnExit: false)])
        XCTAssertEqual(h.store.count, 1); XCTAssertTrue(h.backend.registered.isEmpty)
    }
    func testFailedStoreWriteRejectsTheAddAndRollsBackTheRegistration() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("old")])
        h.store.failWrites = true
        rejected("IO_ERROR") { try h.manager.add([circle("new"), circle("old", radius: 500)]) }
        h.store.failWrites = false
        XCTAssertEqual(try h.store.all().map(\.identifier), ["old"]); XCTAssertEqual(try h.store.get("old")?.radius, 100)
        XCTAssertEqual(Set(h.backend.registered.keys), ["old"]); XCTAssertEqual(h.backend.registered["old"]?.radius, 100)
        XCTAssertEqual(h.changes.count, 1)
    }
    func testRemoveUnregistersDeletesAndEmitsTheRemovedIds() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("a"), circle("b"), circle("c")]); h.changes = []
        try h.manager.remove(["a", "c", "unknown"])
        XCTAssertEqual(try h.store.all().map(\.identifier), ["b"]); XCTAssertEqual(h.backend.removeCalls, [["a", "c"]]); XCTAssertEqual(Set(h.backend.registered.keys), ["b"])
        XCTAssertEqual(h.changes.count, 1); XCTAssertEqual(h.changes[0].0, []); XCTAssertEqual(h.changes[0].1, ["a", "c"])
        try h.manager.remove(["unknown"]); XCTAssertEqual(h.changes.count, 1)
    }
    func testRemoveAllClearsTheOSRegistrationsAndTheStore() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("a"), circle("b")]); h.changes = []
        try h.manager.removeAll()
        XCTAssertEqual(h.store.count, 0); XCTAssertTrue(h.backend.registered.isEmpty); XCTAssertEqual(h.backend.removeCalls, [["a", "b"]])
        XCTAssertEqual(h.changes.count, 1); XCTAssertEqual(h.changes[0].1, ["a", "b"])
    }
    func testRemoveWhileTrackingIsDisabledDoesNotTouchTheOS() throws {
        let h = GeofenceHarness(enabled: false)
        try h.manager.add([circle("a"), circle("b")])
        try h.manager.remove(["a"]); try h.manager.removeAll()
        XCTAssertTrue(h.backend.removeCalls.isEmpty); XCTAssertEqual(h.store.count, 0)
    }
    func testTrackingStartRegistersEveryStoredGeofenceAndStopUnregistersThem() throws {
        let h = GeofenceHarness(enabled: false)
        try h.manager.add((1...20).map { circle("g\($0)") })
        XCTAssertTrue(h.backend.addCalls.isEmpty)
        h.enabled = true; h.manager.onTrackingStarted()
        XCTAssertTrue(h.backend.addCalls.isEmpty); XCTAssertEqual(h.backend.restoreCalls.map(\.count), [20]); XCTAssertEqual(h.backend.registered.count, 20)
        h.manager.onTrackingStopped()
        XCTAssertTrue(h.backend.registered.isEmpty); XCTAssertEqual(h.store.count, 20); XCTAssertFalse(h.manager.needsContinuousLocation)
    }
    func testResumingTrackingRestoresStoredGeofencesWhileAddingOrReplacingOneRegistersItAfresh() throws {
        let h = GeofenceHarness(store: restoredStore(circle("home"), GeofenceRuntime(insideCircle: true, insidePolygon: false, enteredAt: GeofenceHarness.defaultNow - 60000)))
        h.manager.onTrackingStarted()
        XCTAssertEqual(h.backend.restoreCalls.map { $0.map(\.id) }, [["home"]]); XCTAssertTrue(h.backend.addCalls.isEmpty)
        try h.manager.add([circle("home")])
        XCTAssertEqual(h.backend.addCalls.map { $0.map(\.id) }, [["home"]]); XCTAssertEqual(h.backend.restoreCalls.count, 1)
    }
    func testCircleEnterAndExitBecomeGeofenceRecords() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("home", extras: ["k": "v"])]); h.manager.onTrackingStarted()
        let enterFix = h.fix(10), exitFix = h.fix(300)
        h.transition("home", .enter, enterFix); h.transition("home", .exit, exitFix)
        XCTAssertEqual(h.actions, ["home:ENTER", "home:EXIT"]); XCTAssertEqual(h.hits.map(\.2), [enterFix, exitFix])
    }
    func testTransitionWithoutAFixUsesTheLastKnownLocation() throws {
        let h = GeofenceHarness()
        let last = EngineFixtures.location(latitude: 1, longitude: 2)
        h.lastLocation = last
        try h.manager.add([circle("home")])
        h.transition("home", .enter)
        XCTAssertEqual(h.hits.single?.2, last)
    }
    func testNotifyOnFlagsAreRespected() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("noEnter", notifyOnEntry: false), circle("noExit", notifyOnExit: false), circle("noDwell", notifyOnDwell: false)])
        for id in ["noEnter", "noExit", "noDwell"] { h.transition(id, .enter, h.fix(0)); h.transition(id, .dwell, h.fix(0)); h.transition(id, .exit, h.fix(500)) }
        XCTAssertEqual(h.actions, ["noEnter:EXIT", "noExit:ENTER", "noDwell:ENTER", "noDwell:EXIT"])
    }
    func testUnknownIdsAndTransitionsWhileStoppedAreIgnored() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("home")])
        h.transition("ghost", .enter, h.fix(0)); h.enabled = false; h.transition("home", .enter, h.fix(0))
        XCTAssertTrue(h.hits.isEmpty)
    }
    func testTransitionsAfterOnTrackingStoppedAreIgnored() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("home")]); h.manager.onTrackingStarted(); h.manager.onTrackingStopped()
        h.transition("home", .enter, h.fix(0))
        XCTAssertTrue(h.hits.isEmpty)
    }
    func testARepeatedEnterWhileInsideIsADuplicate() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("home")])
        h.transition("home", .enter, h.fix(0)); h.transition("home", .enter, h.fix(0)); h.transition("home", .exit, h.fix(500)); h.transition("home", .enter, h.fix(0))
        XCTAssertEqual(h.hits.map(\.1), [.enter, .exit, .enter])
    }
    func testAnOSDwellTransitionIsPassedThroughAndEndsTheSynthesizedTimer() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("home", notifyOnDwell: true, loiteringDelay: 10000)]); h.manager.onTrackingStarted()
        h.transition("home", .enter, h.fix(0)); h.transition("home", .dwell, h.fix(0))
        h.advance(60000)
        XCTAssertEqual(h.hits.map(\.1), [.enter, .dwell])
    }
    func testDwellIsSynthesizedAfterLoiteringDelay() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("home", notifyOnEntry: false, notifyOnDwell: true, loiteringDelay: 30000)]); h.manager.onTrackingStarted()
        let os = try XCTUnwrap(h.backend.registered["home"])
        XCTAssertTrue(os.onEntry, "ENTER is needed to start the timer"); XCTAssertTrue(os.onExit)
        h.transition("home", .enter, h.fix(0))
        XCTAssertTrue(h.hits.isEmpty, "notifyOnEntry=false")
        h.advance(29999); XCTAssertTrue(h.hits.isEmpty)
        h.advance(1); XCTAssertEqual(h.actions, ["home:DWELL"])
        h.advance(600000); XCTAssertEqual(h.hits.count, 1)
    }
    func testExitBeforeLoiteringDelayCancelsTheSynthesizedDwell() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("home", notifyOnDwell: true, loiteringDelay: 30000)]); h.manager.onTrackingStarted()
        h.transition("home", .enter, h.fix(0)); h.advance(20000); h.transition("home", .exit, h.fix(500)); h.advance(60000)
        XCTAssertEqual(h.hits.map(\.1), [.enter, .exit])
    }
    func testOverdueSynthesizedDwellFiresOnTheNextFix() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("home", notifyOnEntry: false, notifyOnDwell: true, loiteringDelay: 30000)]); h.manager.onTrackingStarted()
        h.transition("home", .enter, h.fix(0))
        h.now += 31000
        h.manager.onLocation(h.fix(0))
        XCTAssertEqual(h.actions, ["home:DWELL"])
        h.advance(60000); XCTAssertEqual(h.hits.count, 1, "the timer was disarmed")
    }
    func testPolygonFlowCircleEnterHitTestEnterAndExitCircleExit() throws {
        let h = GeofenceHarness()
        try h.manager.add([polygon("zone", extras: ["z": 1])]); h.manager.onTrackingStarted()
        XCTAssertFalse(h.manager.needsContinuousLocation)
        h.transition("zone", .enter)
        XCTAssertTrue(h.manager.needsContinuousLocation); XCTAssertTrue(h.hits.isEmpty, "circle ENTER alone is not a polygon ENTER")
        XCTAssertEqual(try h.store.runtimes()["zone"], GeofenceRuntime(insideCircle: true, insidePolygon: false, enteredAt: nil))
        let inside = h.fix(0); h.manager.onLocation(inside)
        XCTAssertEqual(h.actions, ["zone:ENTER"]); XCTAssertEqual(h.hits.last?.2, inside)
        XCTAssertTrue(try h.store.runtimes()["zone"]!.insidePolygon)
        let outside = h.fix(130); h.manager.onLocation(outside)
        XCTAssertEqual(h.actions, ["zone:ENTER", "zone:EXIT"]); XCTAssertEqual(h.hits.last?.2, outside)
        XCTAssertTrue(h.manager.needsContinuousLocation, "still inside the circle")
        h.transition("zone", .exit)
        XCTAssertFalse(h.manager.needsContinuousLocation); XCTAssertEqual(h.hits.count, 2)
    }
    func testLeavingTheEnclosingCircleWhileInsideThePolygonIsAPolygonExit() throws {
        let h = GeofenceHarness()
        try h.manager.add([polygon("zone")]); h.manager.onTrackingStarted()
        h.transition("zone", .enter, h.fix(0))
        XCTAssertEqual(h.actions, ["zone:ENTER"])
        let exitFix = h.fix(400); h.transition("zone", .exit, exitFix)
        XCTAssertEqual(h.actions, ["zone:ENTER", "zone:EXIT"]); XCTAssertEqual(h.hits.last?.2, exitFix); XCTAssertFalse(h.manager.needsContinuousLocation)
    }
    func testConcavePolygonUsesTheRealShapeNotTheCircle() throws {
        let h = GeofenceHarness()
        func at(_ n: Double, _ e: Double) -> LatLng { let p = EngineFixtures.moved(EngineFixtures.location(), n, east: e); return LatLng(latitude: p.latitude, longitude: p.longitude) }
        let u = [at(0, 0), at(0, 300), at(300, 300), at(300, 200), at(100, 200), at(100, 100), at(300, 100), at(300, 0)]
        try h.manager.add([polygon("u", vertices: u)]); h.manager.onTrackingStarted()
        h.manager.onLocation(h.fix(250, 150)); XCTAssertTrue(h.hits.isEmpty)
        h.manager.onLocation(h.fix(250, 50)); XCTAssertEqual(h.actions, ["u:ENTER"])
    }
    func testFixesStraddlingTheBoundaryNeedTwoConsecutiveAgreeingFixes() throws {
        let h = GeofenceHarness()
        try h.manager.add([polygon("zone")]); h.manager.onTrackingStarted()
        h.manager.onLocation(h.fix(300))
        h.manager.onLocation(h.fix(95, accuracy: 20)); XCTAssertTrue(h.hits.isEmpty)
        h.manager.onLocation(h.fix(105, accuracy: 20)); h.manager.onLocation(h.fix(95, accuracy: 20)); XCTAssertTrue(h.hits.isEmpty)
        h.manager.onLocation(h.fix(90, accuracy: 20)); XCTAssertEqual(h.actions, ["zone:ENTER"])
        h.manager.onLocation(h.fix(300, accuracy: 5)); XCTAssertEqual(h.actions, ["zone:ENTER", "zone:EXIT"])
    }
    func testPolygonDwellIsSynthesizedAfterLoiteringDelayAndCancelledByExit() throws {
        let h = GeofenceHarness()
        try h.manager.add([polygon("zone", notifyOnEntry: false, notifyOnDwell: true, loiteringDelay: 60000)]); h.manager.onTrackingStarted()
        h.manager.onLocation(h.fix(0)); h.advance(60000)
        XCTAssertEqual(h.actions, ["zone:DWELL"])
        h.manager.onLocation(h.fix(500)); h.manager.onLocation(h.fix(0)); h.advance(30000); h.manager.onLocation(h.fix(500)); h.advance(120000)
        XCTAssertEqual(h.actions, ["zone:DWELL", "zone:EXIT", "zone:EXIT"])
    }
    func testInitialTriggerEntryFalseMakesTheFirstPolygonClassificationSilent() throws {
        let h = GeofenceHarness(initialTriggerEntry: false)
        try h.manager.add([polygon("zone", notifyOnDwell: true, loiteringDelay: 1000)]); h.manager.onTrackingStarted()
        h.manager.onLocation(h.fix(0)); h.advance(10000); XCTAssertTrue(h.hits.isEmpty)
        h.manager.onLocation(h.fix(500)); h.manager.onLocation(h.fix(0))
        XCTAssertEqual(h.actions, ["zone:EXIT", "zone:ENTER"])
    }
    func testInitialTriggerEntryTrueReportsTheFirstClassificationInside() throws {
        let h = GeofenceHarness(initialTriggerEntry: true)
        try h.manager.add([polygon("zone")]); h.manager.onTrackingStarted()
        h.manager.onLocation(h.fix(0))
        XCTAssertEqual(h.actions, ["zone:ENTER"])
    }
    func testLocationsAreIgnoredWhileTrackingIsDisabledAndStaleFixesAreSkipped() throws {
        let h = GeofenceHarness()
        try h.manager.add([polygon("zone")]); h.manager.onTrackingStarted()
        let older = h.fix(500), newer = h.fix(0)
        h.manager.onLocation(newer); h.manager.onLocation(older)
        XCTAssertEqual(h.actions, ["zone:ENTER"])
        h.enabled = false; h.manager.onLocation(h.fix(500)); XCTAssertEqual(h.hits.count, 1)
    }
    func testPolygonRuntimeIsPersistedAndRestoredByANewProcess() throws {
        let store = FakeGeofenceStore(), first = GeofenceHarness(store: store)
        try first.manager.add([polygon("zone")]); first.manager.onTrackingStarted()
        first.transition("zone", .enter, first.fix(0))
        XCTAssertEqual(first.actions, ["zone:ENTER"])
        let persisted = try XCTUnwrap(try store.runtimes()["zone"])
        XCTAssertTrue(persisted.insideCircle && persisted.insidePolygon && persisted.enteredAt != nil)
        let second = GeofenceHarness(store: store)
        second.manager.onTrackingStarted()
        XCTAssertTrue(second.manager.needsContinuousLocation)
        second.transition("zone", .enter); second.manager.onLocation(second.fix(0))
        XCTAssertTrue(second.hits.isEmpty)
        second.manager.onLocation(second.fix(500))
        XCTAssertEqual(second.actions, ["zone:EXIT"])
    }
    func testRestoredCircleReArmsTheRemainingSynthesizedDwell() {
        let store = restoredStore(circle("home", notifyOnDwell: true, loiteringDelay: 60000), GeofenceRuntime(insideCircle: true, insidePolygon: false, enteredAt: GeofenceHarness.defaultNow - 20000))
        let h = GeofenceHarness(store: store)
        h.manager.onTrackingStarted()
        h.advance(39999); XCTAssertTrue(h.hits.isEmpty)
        h.advance(1); XCTAssertEqual(h.actions, ["home:DWELL"])
    }
    func testRestoredDwellWhoseDeadlinePassedWhileTheProcessWasGoneIsNotReArmed() {
        let store = restoredStore(circle("home", notifyOnDwell: true, loiteringDelay: 60000), GeofenceRuntime(insideCircle: true, insidePolygon: false, enteredAt: GeofenceHarness.defaultNow - 61000))
        let h = GeofenceHarness(store: store)
        h.manager.onTrackingStarted(); h.advance(600000)
        XCTAssertTrue(h.hits.isEmpty)
    }
    func testRestoredInsideCircleReportsTheOSEnterAfterReRegistrationBecauseTheStateMayBeStale() {
        let store = restoredStore(circle("home"), GeofenceRuntime(insideCircle: true, insidePolygon: false, enteredAt: GeofenceHarness.defaultNow - 3600000))
        let h = GeofenceHarness(store: store)
        h.manager.onTrackingStarted()
        h.transition("home", .enter, h.fix(0)); h.transition("home", .enter, h.fix(0))
        XCTAssertEqual(h.actions, ["home:ENTER"], "the second one is a duplicate")
    }
    func testAFixCertainlyOutsideARestoredInsideCircleReportsTheExitMissedWhileTheProcessWasGone() throws {
        let store = restoredStore(circle("home", radius: 100), GeofenceRuntime(insideCircle: true, insidePolygon: false, enteredAt: GeofenceHarness.defaultNow - 3600000))
        let h = GeofenceHarness(store: store)
        h.manager.onTrackingStarted()
        h.manager.onLocation(h.fix(120, accuracy: 30)); XCTAssertTrue(h.hits.isEmpty)
        let far = h.fix(400); h.manager.onLocation(far); h.manager.onLocation(h.fix(500))
        XCTAssertEqual(h.actions, ["home:EXIT"]); XCTAssertEqual(h.hits.single?.2, far)
        XCTAssertEqual(try store.runtimes()["home"], GeofenceRuntime.unknown)
    }
    func testAFixConfirmingARestoredInsideCircleMakesTheLaterOSInitialEnterADuplicate() {
        let store = restoredStore(circle("home", radius: 100), GeofenceRuntime(insideCircle: true, insidePolygon: false, enteredAt: GeofenceHarness.defaultNow - 3600000))
        let h = GeofenceHarness(store: store)
        h.manager.onTrackingStarted()
        h.manager.onLocation(h.fix(10)); h.transition("home", .enter, h.fix(10))
        XCTAssertTrue(h.hits.isEmpty)
    }
    func testStaleRuntimeIsDiscardedWhenLoadedWhileTrackingIsDisabled() throws {
        let store = FakeGeofenceStore()
        try store.upsert([circle("home")]); try store.setRuntime("home", GeofenceRuntime(insideCircle: true, insidePolygon: false, enteredAt: 1))
        let h = GeofenceHarness(enabled: false, store: store)
        try h.manager.add([circle("other")])
        XCTAssertEqual(try store.runtimes()["home"], GeofenceRuntime.unknown)
    }
    func testTrackingStopResetsRuntimeAndCancelsPendingDwell() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("home", notifyOnDwell: true), polygon("zone")]); h.manager.onTrackingStarted()
        h.transition("home", .enter, h.fix(0)); h.transition("zone", .enter, h.fix(0))
        XCTAssertTrue(h.manager.needsContinuousLocation)
        h.manager.onTrackingStopped(); h.advance(120000)
        XCTAssertFalse(h.manager.needsContinuousLocation); XCTAssertEqual(h.hits.map(\.1), [.enter, .enter])
        XCTAssertEqual(try h.store.runtimes(), ["home": .unknown, "zone": .unknown])
    }
    func testReplacingAGeofenceResetsItsState() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("home")]); h.transition("home", .enter, h.fix(0))
        try h.manager.add([circle("home", radius: 250)]); h.transition("home", .enter, h.fix(0))
        XCTAssertEqual(h.hits.map(\.1), [.enter, .enter]); XCTAssertEqual(h.backend.registered["home"]?.radius, 250)
    }
    func testSilentInitialPolygonStateDoesNotReArmADwellAfterARestart() throws {
        let store = FakeGeofenceStore(), first = GeofenceHarness(initialTriggerEntry: false, store: store)
        try first.manager.add([polygon("zone", notifyOnDwell: true, loiteringDelay: 60000)]); first.manager.onTrackingStarted()
        first.manager.onLocation(first.fix(0))
        XCTAssertTrue(try store.runtimes()["zone"]!.insidePolygon)
        let second = GeofenceHarness(initialTriggerEntry: false, store: store)
        second.manager.onTrackingStarted(); second.advance(600000)
        XCTAssertTrue(first.hits.isEmpty); XCTAssertTrue(second.hits.isEmpty)
    }
    func testInitialTriggerEntryFalseStillReportsAPolygonEnteredAfterTheInitialWindow() throws {
        let h = GeofenceHarness(initialTriggerEntry: false)
        try h.manager.add([polygon("zone")]); h.manager.onTrackingStarted(); h.advance(3 * 60000)
        h.transition("zone", .enter, h.fix(0))
        XCTAssertEqual(h.actions, ["zone:ENTER"])
    }
    func testInitialTriggerEntryFalseAFirstFixAfterTheWindowWithoutAnInitialCircleEnterIsAnEnter() throws {
        let h = GeofenceHarness(initialTriggerEntry: false)
        try h.manager.add([polygon("zone")]); h.manager.onTrackingStarted(); h.advance(3 * 60000)
        h.manager.onLocation(h.fix(0))
        XCTAssertEqual(h.actions, ["zone:ENTER"])
    }
    func testTheOSInitialCircleEnterKeepsTheFirstClassificationSilentEvenIfTheFixIsLate() throws {
        let h = GeofenceHarness(initialTriggerEntry: false)
        try h.manager.add([polygon("zone")]); h.manager.onTrackingStarted(); h.advance(5000)
        h.transition("zone", .enter); h.advance(5 * 60000)
        h.manager.onLocation(h.fix(0)); XCTAssertTrue(h.hits.isEmpty)
        h.manager.onLocation(h.fix(130)); XCTAssertEqual(h.actions, ["zone:EXIT"])
    }
    func testAFixCertainlyOutsideTheEnclosingCircleClearsAStaleCircleState() throws {
        let store = restoredStore(polygon("zone"), GeofenceRuntime(insideCircle: true, insidePolygon: false, enteredAt: GeofenceHarness.defaultNow - 60000))
        let h = GeofenceHarness(store: store)
        h.manager.onTrackingStarted()
        XCTAssertTrue(h.manager.needsContinuousLocation)
        h.manager.onLocation(h.fix(5000))
        XCTAssertFalse(h.manager.needsContinuousLocation); XCTAssertFalse(try store.runtimes()["zone"]!.insideCircle); XCTAssertTrue(h.hits.isEmpty)
    }
    func testTheSameFixFromTheOSTransitionAndTheLocationStreamCountsOnce() throws {
        let h = GeofenceHarness()
        try h.manager.add([polygon("zone")]); h.manager.onTrackingStarted()
        h.manager.onLocation(h.fix(300))
        let straddling = h.fix(95, accuracy: 20)
        h.transition("zone", .enter, straddling); h.manager.onLocation(straddling)
        XCTAssertTrue(h.hits.isEmpty)
        h.manager.onLocation(h.fix(94, accuracy: 20))
        XCTAssertEqual(h.actions, ["zone:ENTER"])
    }
    func testAFixDeliveredWithOnePolygonsOSEnterIsStillUsedForTheOtherGeofences() throws {
        let h = GeofenceHarness()
        try h.manager.add([polygon("a"), polygon("b")]); h.manager.onTrackingStarted()
        h.manager.onLocation(h.fix(300))
        let inside = h.fix(0)
        h.transition("a", .enter, inside); h.manager.onLocation(inside)
        XCTAssertEqual(h.actions, ["a:ENTER", "b:ENTER"])
    }
    func testInitialTriggerEntryFalseAFreshFixOutsideAtRegistrationMakesAnEarlyEntryAnEnter() throws {
        let h = GeofenceHarness(initialTriggerEntry: false)
        h.lastLocation = EngineFixtures.moved(EngineFixtures.location(time: h.now - 10000), 2000)
        try h.manager.add([polygon("zone")]); h.manager.onTrackingStarted(); h.advance(20000)
        h.transition("zone", .enter, h.fix(0))
        XCTAssertEqual(h.actions, ["zone:ENTER"])
    }
    func testAStaleLastFixDoesNotSettleTheInitialState() throws {
        let h = GeofenceHarness(initialTriggerEntry: false)
        h.lastLocation = EngineFixtures.moved(EngineFixtures.location(time: h.now - 3600000), 2000)
        try h.manager.add([polygon("zone")]); h.manager.onTrackingStarted()
        h.transition("zone", .enter, h.fix(0))
        XCTAssertTrue(h.hits.isEmpty, "treated as the OS initial trigger")
    }
    func testAFixWithABogusFutureTimestampDoesNotBlockLaterFixes() throws {
        let h = GeofenceHarness()
        try h.manager.add([polygon("zone")]); h.manager.onTrackingStarted()
        var bogus = h.fix(500); bogus.time += 86400000
        h.manager.onLocation(bogus); h.manager.onLocation(h.fix(0))
        XCTAssertEqual(h.actions, ["zone:ENTER"])
    }
    func testTrackingStartAfterAFailedLoadStillProcessesLaterTransitions() throws {
        let h = GeofenceHarness()
        try h.store.upsert([circle("home")])
        h.store.failReads = true
        let manager = h.makeManager(h.store)
        manager.onTrackingStopped(); manager.onTrackingStarted()
        h.store.failReads = false
        manager.onTransitions([OsGeofenceTransition(id: "home", action: .enter, location: h.fix(0))])
        XCTAssertEqual(h.actions, ["home:ENTER"])
    }
    func testListAndGetReadTheStore() throws {
        let h = GeofenceHarness(), spec = circle("x")
        try h.store.upsert([spec])
        XCTAssertEqual(try h.manager.list(), [spec]); XCTAssertEqual(try h.manager.get("x"), spec)
    }
    func testApproximateAccuracyKeepsStoredGeofencesInactiveUntilPreciseReturns() throws {
        let h = GeofenceHarness()
        try h.manager.add([circle("home"), polygon("zone")]); h.manager.onTrackingStarted()
        XCTAssertEqual(Set(h.backend.registered.keys), ["home", "zone"])
        h.manager.setSuspended(true)
        XCTAssertTrue(h.backend.registered.isEmpty)
        h.transition("home", .enter, h.fix(0)); h.manager.onLocation(h.fix(0))
        XCTAssertTrue(h.hits.isEmpty)
        h.manager.setSuspended(false)
        XCTAssertEqual(Set(h.backend.registered.keys), ["home", "zone"])
        h.transition("home", .enter, h.fix(0))
        XCTAssertEqual(h.actions, ["home:ENTER"])
    }
    func testServiceSessionFollowsAddAndRemoveWhileTrackingWithAlways() throws {
        let h = GeofenceHarness()
        h.manager.onTrackingStarted(always: true)
        XCTAssertEqual(h.backend.sessions.last, false)
        try h.manager.add([circle("home")])
        XCTAssertEqual(h.backend.sessions.last, true)
        try h.manager.add([circle("mute", notifyOnEntry: false, notifyOnExit: false)])
        try h.manager.remove(["home"])
        XCTAssertEqual(h.backend.sessions.last, false, "only an inactive geofence is left")
        try h.manager.add([circle("home")]); XCTAssertEqual(h.backend.sessions.last, true)
        try h.manager.removeAll(); XCTAssertEqual(h.backend.sessions.last, false)
        try h.manager.add([circle("home")]); h.manager.onTrackingStarted(always: false)
        XCTAssertEqual(h.backend.sessions.last, false)
        h.manager.onTrackingStopped(); XCTAssertEqual(h.backend.sessions.last, false)
        let stopped = h.backend.sessions.count
        try h.manager.add([circle("away")])
        XCTAssertEqual(h.backend.sessions.count, stopped, "no session while tracking is stopped")
    }
    func testApproximateAccuracyNotifiesEveryBlockedAttempt() throws {
        let h = GeofenceHarness()
        var blocked = 0
        h.manager.blocked = { blocked += 1 }
        h.manager.setSuspended(true)
        h.manager.onTrackingStarted()
        XCTAssertEqual(blocked, 0, "nothing is blocked without geofences")
        try h.manager.add([circle("home")])
        XCTAssertEqual(blocked, 1); XCTAssertTrue(h.backend.registered.isEmpty)
        h.manager.onTrackingStopped(); h.manager.onTrackingStarted()
        XCTAssertEqual(blocked, 2)
        h.manager.setSuspended(false); XCTAssertEqual(Set(h.backend.registered.keys), ["home"])
        h.manager.setSuspended(true); XCTAssertEqual(blocked, 3)
    }
    func testAnInitialOutsideDeterminationReportsTheExitMissedByARestoredInsideGeofence() {
        let store = restoredStore(circle("home"), GeofenceRuntime(insideCircle: true, insidePolygon: false, enteredAt: GeofenceHarness.defaultNow - 3600000))
        try? store.upsert([normalized(polygon("zone"))]); try? store.setRuntime("zone", GeofenceRuntime(insideCircle: true, insidePolygon: true, enteredAt: GeofenceHarness.defaultNow - 3600000))
        let h = GeofenceHarness(store: store)
        h.manager.onTrackingStarted()
        h.manager.onTransitions([OsGeofenceTransition(id: "home", action: .exit, location: nil, initial: true), OsGeofenceTransition(id: "zone", action: .exit, location: nil, initial: true)])
        XCTAssertEqual(h.actions, ["home:EXIT", "zone:EXIT"])
        h.manager.onTransitions([OsGeofenceTransition(id: "home", action: .exit, location: nil, initial: true)])
        XCTAssertEqual(h.hits.count, 2, "an outside determination without a restored inside state is silent")
    }
}

private extension Array {
    var single: Element? { count == 1 ? first : nil }
}
