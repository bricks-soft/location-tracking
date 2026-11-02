import XCTest
import CoreLocation
@testable import LocationTrackingPlugin

@MainActor
final class BridgeTests: XCTestCase {
    func testMethodTableCoversTypeScriptSurface() {
        let names = Set(LocationTrackingPlugin().pluginMethods.compactMap { $0.name })
        let expected = Set(["ready", "setConfig", "reset", "getState", "start", "startGeofences", "stop", "changePace", "getCurrentPosition", "watchPosition", "clearWatch", "getOdometer", "setOdometer", "resetOdometer", "getLocations", "getCount", "insertLocation", "destroyLocations", "destroyLocation", "sync", "addGeofence", "addGeofences", "removeGeofence", "removeGeofences", "getGeofences", "getGeofence", "geofenceExists", "getHeartbeatStatus", "getProviderState", "isPowerSaveMode", "getBatteryOptimizationStatus", "openBatteryOptimizationSettings", "getPowerManagerInfo", "openPowerManagerSettings", "openLocationSettings", "openAppSettings", "getDeviceInfo", "getSensors", "checkPermissions", "requestPermissions", "requestTemporaryFullAccuracy", "log", "getLog", "destroyLog", "uploadLog", "emailLog"])
        XCTAssertEqual(names, expected)
    }
    func testNotReadyAndNativeBypass() throws {
        let core = try TrackingCore(queue: .main, permissions: Permissions(), positions: Positions(), emit: { _, _ in }, record: { _ in }, url: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("test.sqlite"))
        var failure: TrackingError?
        core.call("getCount", [:]) { result in if case .failure(let error) = result { failure = error as? TrackingError } }
        XCTAssertEqual(failure?.code, "NOT_READY")
        var nativeCount: Double?
        core.call("getCount", [:], native: true) { result in nativeCount = (try? result.get())?.number("count") }
        XCTAssertEqual(nativeCount, 0)
        XCTAssertFalse(core.ready)
        core.call("ready", [:]) { result in if case .failure(let error) = result { XCTFail(error.localizedDescription) } }
        XCTAssertTrue(core.ready)
        core.call("getCount", [:]) { result in XCTAssertEqual((try? result.get())?.number("count"), 0) }
        core.syncer.close()
    }
    func testAllowlistMatchesAndroidWithPrecisionAddition() {
        XCTAssertEqual(TrackingCore.allowed, Set(["ready", "getState", "checkPermissions", "requestPermissions", "requestTemporaryFullAccuracy", "getDeviceInfo", "getSensors", "log", "getLog", "getProviderState", "openBatteryOptimizationSettings", "openPowerManagerSettings", "openLocationSettings", "openAppSettings"]))
    }
    func testRecordShapeOmitsUnavailableIDAndCoordinatesAreNullable() {
        let wire = RecordFactory.make(event: "current_position", fix: nil, extras: [:], odometer: 0, provider: [:], network: "none")
        XCTAssertTrue(wire["coords"] is NSNull)
        XCTAssertTrue(wire["timestamp"] is NSNull)
        XCTAssertEqual(wire.number("boot_count"), -1)
        XCTAssertEqual(wire.text("backend"), "corelocation")
        XCTAssertNoThrow(try jsonData(wire))
    }
    func testPermissionOrderMatchesAndroid() {
        XCTAssertEqual(Permissions.order, ["location", "notifications", "activityRecognition", "backgroundLocation"])
    }

    func testReopenPrunesExpiredQueueBeforeAnyReadOrUpload() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("expired.sqlite")
        do {
            let store = try TrackingStore(url: url)
            try store.save("config", ["persistence": ["maxDaysToPersist": 1]])
            try store.insert(["uuid": "expired", "event": "location", "recorded_at": iso(Date().addingTimeInterval(-172800))])
            try store.insert(["uuid": "fresh", "event": "location", "recorded_at": iso()])
        }
        let core = try TrackingCore(queue: .main, permissions: Permissions(), positions: Positions(), emit: { _, _ in }, record: { _ in }, url: url)
        XCTAssertEqual(try core.store.locations().map { $0.text("uuid") }, ["fresh"])
        core.syncer.close()
    }
    func testLiveDeliverySurvivesSQLiteFull() throws {
        var events: [(String, TrackingJSON)] = []
        var native: [TrackingJSON] = []
        let core = try TrackingCore(queue: .main, permissions: Permissions(), positions: Positions(), emit: { events.append(($0, $1)) }, record: { native.append($0) }, url: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("full.sqlite"))
        try core.store.execute("PRAGMA max_page_count=1")
        let wire: TrackingJSON = ["uuid": "live", "event": "current_position", "recorded_at": iso(), "extras": ["large": String(repeating: "x", count: 1000000)]]
        core.deliver(wire)
        XCTAssertEqual(try core.store.count, 0)
        XCTAssertEqual(native.map { $0.text("uuid") }, ["live"])
        XCTAssertTrue(events.contains { $0.0 == "location" && $0.1.text("uuid") == "live" })
        let full = try XCTUnwrap(events.first { $0.0 == "storagefull" }?.1)
        XCTAssertTrue(full["diskFreeBytes"] is NSNull)
        XCTAssertEqual(full.text("uuid"), "live")
        XCTAssertEqual(core.state().text("lastRecordAt"), wire.text("recorded_at"))
        core.syncer.close()
    }
    func testInsertOnlyQueuesAndDeliversNativeAudit() async throws {
        var events: [String] = []
        var native: [TrackingJSON] = []
        let core = try TrackingCore(queue: .main, permissions: Permissions(), positions: Positions(), emit: { name, _ in events.append(name) }, record: { native.append($0) }, url: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("insert.sqlite"))
        let result: Result<TrackingJSON, Error> = await withCheckedContinuation { continuation in
            core.call("insertLocation", ["location": ["coords": ["latitude": 24, "longitude": 46, "accuracy": 5]]], native: true) { continuation.resume(returning: $0) }
        }
        XCTAssertFalse(try result.get().text("uuid").isEmpty)
        XCTAssertEqual(try core.store.count, 1)
        XCTAssertEqual(native.count, 1)
        XCTAssertFalse(events.contains("location"))
        XCTAssertTrue(core.state()["lastRecordAt"] is NSNull)
        try core.store.execute("PRAGMA query_only=ON")
        let failure: Result<TrackingJSON, Error> = await withCheckedContinuation { continuation in
            core.call("insertLocation", ["location": ["coords": ["latitude": 24, "longitude": 46, "accuracy": 5]]], native: true) { continuation.resume(returning: $0) }
        }
        XCTAssertThrowsError(try failure.get())
        XCTAssertEqual(native.count, 1)
        core.syncer.close()
    }
    func testServiceAvailabilityRefreshRunsOffMainThread() async {
        let services = LocationServices { XCTAssertFalse(Thread.isMainThread); return false }
        XCTAssertFalse(services.isReady)
        await withCheckedContinuation { continuation in services.whenReady { continuation.resume() } }
        XCTAssertTrue(services.isReady)
        XCTAssertFalse(services.enabled)
    }

    func testLiveDeliverySurvivesReadOnlyStoreWithoutStorageFull() throws {
        var events: [String] = []
        var native: [TrackingJSON] = []
        let core = try TrackingCore(queue: .main, permissions: Permissions(), positions: Positions(), emit: { name, _ in events.append(name) }, record: { native.append($0) }, url: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("readonly.sqlite"))
        try core.store.execute("PRAGMA query_only=ON")
        core.deliver(["uuid": "live", "event": "current_position", "recorded_at": iso()])
        XCTAssertEqual(native.count, 1)
        XCTAssertTrue(events.contains("location"))
        XCTAssertFalse(events.contains("storagefull"))
        core.syncer.close()
    }

    func testClearWatchCancelsPendingServiceStatusRead() async {
        let services = LocationServices { true }
        let positions = Positions(services: services)
        var delivered = false
        positions.request(id: "pending", options: [:], watch: true) { _ in delivered = true }
        positions.clear("pending")
        await withCheckedContinuation { continuation in services.whenReady { continuation.resume() } }
        try? await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertFalse(delivered)
    }

    func testWatchRechecksServicesBeforeClassifyingDeniedError() async {
        let services = LocationServices { false }
        var delivered = false
        let source = PositionSource(options: [:], continuous: true, services: services) { _ in delivered = true }
        defer { source.stop() }
        source.locationManager(CLLocationManager(), didFailWithError: NSError(domain: kCLErrorDomain, code: CLError.Code.denied.rawValue))
        await withCheckedContinuation { continuation in services.whenReady { continuation.resume() } }
        XCTAssertFalse(delivered)
    }
    func testWatchPermissionRevocationIsTerminalWhenServicesRemainEnabled() async {
        let services = LocationServices { true }
        var failure: TrackingError?
        let source = PositionSource(options: [:], continuous: true, services: services) { result in
            if case .failure(let error) = result { failure = error as? TrackingError }
        }
        defer { source.stop() }
        source.locationManager(CLLocationManager(), didFailWithError: NSError(domain: kCLErrorDomain, code: CLError.Code.denied.rawValue))
        await withCheckedContinuation { continuation in services.whenReady { continuation.resume() } }
        XCTAssertEqual(failure?.code, "PERMISSION_DENIED")
    }

}
