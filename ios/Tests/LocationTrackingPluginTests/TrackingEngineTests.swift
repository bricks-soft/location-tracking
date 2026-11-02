import XCTest
import CoreLocation
@testable import LocationTrackingPlugin

@MainActor
final class TrackingEngineTests: XCTestCase {
    var now = TrackingInstant(wall: 1790417730456, elapsed: 86400123)
    var core: TrackingCore!
    var records: [TrackingJSON] = []
    var events: [(String, TrackingJSON)] = []
    var url: URL!
    let provider: TrackingJSON = ["permission": "when_in_use", "enabled": true, "accuracy": "precise", "backend": "corelocation"]
    var origin: TrackedLocation { EngineFixtures.location(accuracy: 5, speed: 0, time: now.wall) }
    func prepare(_ patch: TrackingJSON = [:]) throws {
        url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("engine.sqlite")
        try reopen()
        try core.config.apply(["http": ["autoSync": false], "heartbeat": ["enabled": false], "geolocation": ["filter": ["maxImpliedSpeed": 0]]], reset: true)
        if !patch.isEmpty { try core.config.apply(patch, reset: false) }
        core.engine.context(provider: provider, foreground: true)
    }
    func reopen() throws {
        core = try TrackingCore(queue: .main, permissions: Permissions(), positions: Positions(), emit: { [weak self] in self?.events.append(($0, $1)) }, record: { [weak self] in self?.records.append($0) }, url: url, clock: { [unowned self] in self.now }, automaticTimers: false)
    }
    override func tearDown() { core?.engine.timers.cancelAll(); core?.syncer.close(); core = nil }
    func advance(_ milliseconds: Double, fire: Bool = true) { now.wall += milliseconds; now.elapsed += milliseconds; if fire { core.engine.timers.fireDue() } }
    func started() throws { try core.engine.start(); core.engine.initialFix(origin) }
    func sequence() -> [String] { records.map { $0.text("event") + ($0.text("reason").isEmpty ? ($0.text("event") == "motionchange" ? ":\($0.flag("is_moving"))" : "") : ":" + $0.text("reason")) } }
    func testStartMovingStationaryStopExactAndroidSequence() throws {
        try prepare(); let start = origin; try started()
        XCTAssertTrue(records[0]["coords"] is NSNull)
        let first = EngineFixtures.moved(start, 100); now.wall = first.time; now.elapsed += 1000
        core.engine.receive([first]); let second = EngineFixtures.moved(first, 50); now.wall = second.time; now.elapsed += 1000
        core.engine.receive([second]); let third = EngineFixtures.moved(second, 5); now.wall = third.time; now.elapsed += 1000
        core.engine.receive([third]); core.engine.activity(ActivitySample(type: "in_vehicle", confidence: 90))
        advance(300000 - 1); XCTAssertTrue(core.state().flag("isMoving"))
        advance(1); XCTAssertFalse(core.state().flag("isMoving"))
        try core.engine.stop()
        XCTAssertEqual(sequence(), ["tracking_start:start", "motionchange:false", "motionchange:true", "location", "motionchange:false", "tracking_stop:stop"])
        XCTAssertEqual(events.filter { $0.0 == "enabledchange" }.map { $0.1.flag("enabled") }, [true, false])
        XCTAssertEqual(events.filter { $0.0 == "motionchange" }.map { $0.1.flag("isMoving") }, [false, true, false])
        XCTAssertEqual(events.filter { $0.0 == "activitychange" }.first?.1.text("activity"), "in_vehicle")
        XCTAssertEqual(try core.store.count, 6)
        let count = records.count; core.engine.receive([EngineFixtures.moved(third, 500)]); advance(600000)
        XCTAssertEqual(records.count, count)
    }
    func testPermissionFailureIdempotencyAndChangePaceWhileStopped() throws {
        try prepare(); core.engine.context(provider: ["permission": "denied"], foreground: true)
        XCTAssertThrowsError(try core.engine.start()) { XCTAssertEqual(($0 as? TrackingError)?.code, "PERMISSION_DENIED") }
        XCTAssertTrue(records.isEmpty); XCTAssertFalse(core.state().flag("enabled"))
        core.engine.changePace(true); XCTAssertTrue(records.isEmpty)
        core.engine.context(provider: provider, foreground: true)
        try core.engine.start(); try core.engine.start(); XCTAssertEqual(sequence(), ["tracking_start:start"])
        core.engine.changePace(true); XCTAssertTrue(core.state().flag("isMoving"))
        core.engine.changePace(false); XCTAssertFalse(core.state().flag("isMoving"))
    }
    func testStationaryFixesAreNotRecordedOrCountedAndBadFixesAreIgnored() throws {
        try prepare(); let start = origin; try started()
        let near = EngineFixtures.moved(start, 20)
        core.engine.receive([near]); XCTAssertEqual(records.count, 2); XCTAssertEqual(core.odometer, 0)
        var bad = EngineFixtures.moved(start, 1000); bad.accuracy = 500
        core.engine.receive([bad]); XCTAssertEqual(records.count, 2); XCTAssertFalse(core.state().flag("isMoving"))
    }
    func testOverdueStopTimerMotionInBatchWins() throws {
        try prepare(); let start = origin; try started(); core.engine.changePace(true)
        advance(300001, fire: false)
        var moved = EngineFixtures.moved(start, 100); moved.time = now.wall
        core.engine.receive([start, moved])
        XCTAssertTrue(core.state().flag("isMoving"))
        advance(299999); XCTAssertTrue(core.state().flag("isMoving"))
        advance(1); XCTAssertFalse(core.state().flag("isMoving"))
    }
    func testDisableStopDetectionAndStopOnStationary() throws {
        try prepare(["activity": ["disableStopDetection": true]])
        try started(); core.engine.changePace(true); advance(600000)
        XCTAssertTrue(core.state().flag("isMoving"))
        try core.config.apply(["activity": ["disableStopDetection": false], "geolocation": ["stopOnStationary": true]], reset: false)
        core.engine.configChanged(); advance(300000)
        XCTAssertFalse(core.state().flag("enabled")); XCTAssertEqual(core.state().text("lastStopReason"), "stop_on_stationary")
    }
    func testLiveMotionTriggerAndActivityConfidence() throws {
        try prepare(["activity": ["motionTriggerDelay": 30000, "minimumActivityRecognitionConfidence": 67]])
        try started()
        core.engine.activity(ActivitySample(type: "walking", confidence: 66)); advance(30000); XCTAssertFalse(core.state().flag("isMoving"))
        core.engine.activity(ActivitySample(type: "walking", confidence: 100)); advance(29999); XCTAssertFalse(core.state().flag("isMoving"))
        advance(1); XCTAssertTrue(core.state().flag("isMoving"))
        XCTAssertEqual(core.runtime.object("activity").number("confidence"), 100)
    }
    func testRestoreBeforeReadyKeepsOriginalDeadlineAndStartsOnce() throws {
        try prepare(["geolocation": ["stopAfterElapsedMinutes": 60]])
        try started(); let startedAt = core.runtime.number("trackingStartedAt")
        advance(20 * 60000, fire: false); core.engine.timers.cancelAll(); core.syncer.close(); core = nil
        records = []; try reopen(); core.engine.context(provider: provider, foreground: true)
        try core.engine.restore(); try core.engine.restore()
        XCTAssertFalse(core.ready); XCTAssertEqual(sequence(), ["tracking_start:restore"])
        XCTAssertEqual(core.runtime.number("trackingStartedAt"), startedAt)
        advance(40 * 60000); XCTAssertEqual(sequence().last, "tracking_stop:stop_after_elapsed")
    }
    func testExpiredRestoreStartsNothingAndRecordsSingleStop() throws {
        try prepare(["geolocation": ["stopAfterElapsedMinutes": 60]])
        try started(); advance(61 * 60000, fire: false)
        core.engine.timers.cancelAll(); core.syncer.close(); core = nil; records = []
        try reopen(); core.engine.context(provider: provider, foreground: true)
        try core.engine.restore(); try core.engine.restore()
        XCTAssertEqual(sequence(), ["tracking_stop:stop_after_elapsed"]); XCTAssertFalse(core.engine.running)
    }
    func testRebootAndWallClockChangesUseDeadlineContract() throws {
        try prepare(["geolocation": ["stopAfterElapsedMinutes": 60]])
        try started(); now.wall -= 3 * 3600000
        advance(59 * 60000); XCTAssertTrue(core.state().flag("enabled"))
        advance(60000); XCTAssertEqual(core.state().text("lastStopReason"), "stop_after_elapsed")
        try core.engine.start(); advance(61 * 60000, fire: false)
        now.elapsed = 60000
        core.engine.timers.cancelAll(); core.syncer.close(); core = nil; records = []
        try reopen(); core.engine.context(provider: provider, foreground: true); try core.engine.restore()
        XCTAssertEqual(sequence(), ["tracking_stop:stop_after_elapsed"])
    }
    func testClockChangeCheckpointSurvivesColdRestore() throws {
        try prepare(["geolocation": ["stopAfterElapsedMinutes": 60]])
        try started(); now.wall -= 3 * 3600000; core.engine.clockChanged()
        advance(20 * 60000, fire: false); core.engine.timers.cancelAll(); core.syncer.close(); core = nil
        try reopen(); core.engine.context(provider: provider, foreground: true); try core.engine.restore()
        advance(40 * 60000); XCTAssertEqual(core.state().text("lastStopReason"), "stop_after_elapsed")
    }
    func testModeSwitchStartsNewWindowAndPreservesPublicGeofenceGate() throws {
        try prepare(["geolocation": ["stopAfterElapsedMinutes": 60]])
        try started(); core.engine.changePace(true); advance(20 * 60000, fire: false)
        try core.engine.start(mode: "geofences")
        XCTAssertEqual(sequence().suffix(2), ["motionchange:false", "tracking_start:start_geofences"])
        advance(40 * 60000); XCTAssertTrue(core.state().flag("enabled"))
        advance(20 * 60000); XCTAssertFalse(core.state().flag("enabled"))
        core.call("startGeofences", [:], native: true) { result in XCTAssertEqual((result.failure as? TrackingError)?.code, "UNAVAILABLE") }
    }
    func testWhenInUseBackgroundRestoreWaitsForActiveAndRevokedPermissionStops() throws {
        try prepare(); try started(); core.engine.timers.cancelAll(); core.syncer.close(); core = nil; records = []
        try reopen(); core.engine.context(provider: provider, foreground: false); try core.engine.restore()
        XCTAssertTrue(core.state().flag("enabled")); XCTAssertFalse(core.engine.running); XCTAssertTrue(records.isEmpty)
        core.engine.active(provider: provider, foreground: true)
        XCTAssertEqual(sequence(), ["tracking_start:restore"])
        core.engine.providerChanged(["enabled": true, "permission": "denied"])
        XCTAssertEqual(core.state().text("lastStopReason"), "permission_denied")
    }
    let servicesOff: TrackingJSON = ["enabled": false, "gps": false, "network": false, "permission": "denied", "accuracy": "none", "backend": "corelocation"]
    let revoked: TrackingJSON = ["enabled": true, "gps": true, "network": true, "permission": "denied", "accuracy": "none", "backend": "corelocation"]
    func coldLaunch(_ state: TrackingJSON) throws {
        core.engine.timers.cancelAll(); core.syncer.close(); core = nil; records = []
        try reopen(); core.engine.context(provider: state, foreground: true); try core.engine.restore()
    }
    func testColdRestoreWithLocationServicesOffKeepsIntentAndResumesWhenTheyReturn() throws {
        try prepare(); try started(); let session = core.runtime["trackingStartedAt"] as? Double
        advance(60000); try coldLaunch(servicesOff)
        XCTAssertTrue(core.state().flag("enabled")); XCTAssertFalse(core.engine.running); XCTAssertTrue(records.isEmpty)
        XCTAssertEqual(core.runtime["trackingStartedAt"] as? Double, session)
        core.engine.providerChanged(provider)
        XCTAssertTrue(core.engine.running); XCTAssertEqual(sequence(), ["tracking_start:restore"])
        XCTAssertEqual(core.runtime["trackingStartedAt"] as? Double, session)
    }
    func testLocationServicesOffRestoreStillEndsAtTheOriginalDeadline() throws {
        try prepare(["geolocation": ["stopAfterElapsedMinutes": 10]]); try started()
        advance(60000); try coldLaunch(servicesOff)
        advance(539999); XCTAssertTrue(core.state().flag("enabled"))
        advance(1); XCTAssertFalse(core.state().flag("enabled")); XCTAssertEqual(core.state().text("lastStopReason"), "stop_after_elapsed")
    }
    func testColdRestoreAfterAppPermissionRevocationStopsAndPendingRestoreHonorsLaterRevocation() throws {
        try prepare(); try started(); try coldLaunch(revoked)
        XCTAssertFalse(core.state().flag("enabled")); XCTAssertEqual(core.state().text("lastStopReason"), "permission_denied")
        XCTAssertEqual(sequence(), ["tracking_stop:permission_denied"])
        core.engine.context(provider: provider, foreground: true); try started(); try coldLaunch(servicesOff)
        XCTAssertTrue(core.state().flag("enabled"))
        core.engine.providerChanged(revoked)
        XCTAssertFalse(core.state().flag("enabled")); XCTAssertEqual(core.state().text("lastStopReason"), "permission_denied")
    }
    func testCoreLocationFixesSurviveABackwardClockCorrectionWithoutAcceptingStaleFixes() throws {
        try prepare(); let start = origin; try started(); core.engine.changePace(true)
        let manager = CLLocationManager()
        let route = RouteSource(manager: manager, relaunch: CLLocationManager(), clock: { [unowned self] in self.now })
        route.apply(RouteProfile(enabled: true, moving: true, accuracy: "high", activityEnabled: false))
        route.locations = { [unowned self] fixes, _ in self.core.engine.receive(fixes) }
        func deliver(_ north: Double, age: Double = 0) -> TrackedLocation {
            var fix = EngineFixtures.moved(start, north); fix.time = now.wall - age; fix.elapsed = nil
            route.locationManager(manager, didUpdateLocations: [fix.location])
            return fix
        }
        func locations() -> [TrackingJSON] { records.filter { $0.text("event") == "location" } }
        advance(1000); _ = deliver(100)
        XCTAssertEqual(locations().count, 1)
        now.wall -= 600000; now.elapsed += 1000
        let corrected = deliver(200)
        XCTAssertEqual(locations().count, 2)
        XCTAssertEqual(route.lastLocation?.latitude, corrected.latitude)
        now.wall += 1000; now.elapsed += 1000
        _ = deliver(400, age: 5000)
        XCTAssertEqual(locations().count, 2)
        XCTAssertEqual(route.lastLocation?.latitude, corrected.latitude)
        try core.engine.stop()
        XCTAssertEqual((records.last?["coords"] as? TrackingJSON)?["latitude"] as? Double ?? 0, corrected.latitude, accuracy: 0.0005)
    }
    func testBatchSpanningABackwardClockCorrectionKeepsTheNewerFix() throws {
        try prepare(); let start = origin; try started(); core.engine.changePace(true)
        let manager = CLLocationManager()
        let route = RouteSource(manager: manager, relaunch: CLLocationManager(), clock: { [unowned self] in self.now })
        route.apply(RouteProfile(enabled: true, moving: true, accuracy: "high", activityEnabled: false))
        route.locations = { [unowned self] fixes, _ in self.core.engine.receive(fixes) }
        advance(1000)
        var first = EngineFixtures.moved(start, 100); first.time = now.wall; first.elapsed = nil
        route.locationManager(manager, didUpdateLocations: [first.location])
        var cached = EngineFixtures.moved(start, 150); cached.time = now.wall + 500; cached.elapsed = nil
        now.wall -= 600000; now.elapsed += 1000
        var fresh = EngineFixtures.moved(start, 250); fresh.time = now.wall - 100; fresh.elapsed = nil
        route.locationManager(manager, didUpdateLocations: [cached.location, fresh.location])
        let locations = records.filter { $0.text("event") == "location" }.map { ($0["coords"] as? TrackingJSON)?["latitude"] as? Double ?? 0 }
        XCTAssertEqual(locations.count, 3)
        XCTAssertEqual(locations.last ?? 0, fresh.latitude, accuracy: 0.0005)
        XCTAssertEqual(route.lastLocation?.latitude, fresh.latitude)
    }
    func testOdometerKeepsTheFirstSegmentAfterACorrectionPersistAndReopen() throws {
        try prepare(); let start = origin; try started(); core.engine.changePace(true)
        advance(1000)
        var first = EngineFixtures.moved(start, 100); first.time = now.wall; first.elapsed = now.elapsed
        core.engine.receive([first])
        now.wall -= 600000; now.elapsed += 1000
        var corrected = EngineFixtures.moved(start, 200); corrected.time = now.wall; corrected.elapsed = now.elapsed
        core.engine.receive([corrected])
        XCTAssertLessThan(corrected.time, core.runtime["trackingStartedAt"] as? Double ?? 0)
        let before = core.odometer
        core.engine.timers.cancelAll(); core.syncer.close(); core = nil; records = []
        advance(1000, fire: false); try reopen(); core.engine.context(provider: provider, foreground: true); try core.engine.restore(); core.engine.changePace(true)
        var next = EngineFixtures.moved(start, 300); next.time = now.wall; next.elapsed = now.elapsed
        core.engine.receive([next])
        XCTAssertEqual(core.odometer, before + Geo.distance(corrected, next), accuracy: 1)
    }
    func testPersistedFixKeepsMonotonicTimeOnlyWhileTheClockCheckpointContinues() throws {
        try prepare(); let written = now.elapsed
        try core.updateRuntime(["lastLocation": EngineFixtures.location(time: now.wall, elapsed: written).json])
        core.engine.timers.cancelAll(); core.syncer.close(); core = nil
        advance(1000, fire: false); try reopen()
        XCTAssertEqual(TrackedLocation.from(core.runtime.object("lastLocation"))?.elapsed, written)
        core.engine.timers.cancelAll(); core.syncer.close(); core = nil
        now.elapsed = 5000; try reopen()
        XCTAssertNotNil(TrackedLocation.from(core.runtime.object("lastLocation")))
        XCTAssertNil(TrackedLocation.from(core.runtime.object("lastLocation"))?.elapsed)
    }
    func testTerminationPolicyAndOSKillWithoutCallback() throws {
        try prepare(["app": ["stopOnTerminate": false]])
        try started(); core.engine.terminate(); XCTAssertTrue(core.state().flag("enabled"))
        core.engine.timers.cancelAll(); core.syncer.close(); core = nil; records = []
        try reopen(); core.engine.context(provider: provider, foreground: true); try core.engine.restore()
        XCTAssertEqual(sequence(), ["tracking_start:restore"])
        try core.config.apply(["app": ["stopOnTerminate": true]], reset: false)
        core.engine.terminate(); XCTAssertEqual(sequence().last, "tracking_stop:terminate")
        XCTAssertFalse(try core.store.load("runtime")!.flag("enabled"))
    }
    func testHeartbeatNoBackfillMetadataAndLiveRecordsDelayIt() throws {
        try prepare(["heartbeat": ["enabled": true]])
        try started(); advance(5000, fire: false)
        core.deliver(core.makeRecord(event: "current_position", fix: origin), location: origin)
        advance(175000); XCTAssertFalse(records.contains { $0.text("event") == "heartbeat" })
        advance(5000); XCTAssertEqual(records.filter { $0.text("event") == "heartbeat" }.count, 1)
        let first = try XCTUnwrap(records.last)
        XCTAssertEqual(first.object("heartbeat").text("strategy"), "timer")
        XCTAssertEqual(first.object("heartbeat").text("next_at"), core.engine.heartbeatStatus().text("nextHeartbeatAt"))
        XCTAssertEqual(events.filter { $0.0 == "heartbeat" }.count, 1)
        advance(900000, fire: false); core.engine.heartbeat(); core.engine.heartbeat()
        XCTAssertEqual(records.filter { $0.text("event") == "heartbeat" }.count, 2)
        XCTAssertEqual(core.engine.heartbeatStatus().number("pendingHeartbeats"), 2)
        try core.engine.stop(); XCTAssertEqual(core.engine.heartbeatStatus().text("strategy"), "disabled")
    }
    func testDisablingActivityIgnoresQueuedSamplesButStillAllowsManualPace() throws {
        try prepare(); try started()
        try core.config.apply(["activity": ["disableMotionActivityUpdates": true]], reset: false)
        core.engine.configChanged()
        core.engine.activity(ActivitySample(type: "walking", confidence: 100), session: core.engine.sessionID)
        XCTAssertFalse(core.state().flag("isMoving")); XCTAssertFalse(events.contains { $0.0 == "activitychange" })
        core.engine.changePace(true); XCTAssertTrue(core.state().flag("isMoving"))
    }
    func testQueuedCallbacksFromPreviousSessionDoNotEnterTheNextSession() throws {
        try prepare(); try started(); let old = core.engine.sessionID
        try core.engine.stop(); advance(2000); try core.engine.start()
        let count = records.count
        core.engine.receive([EngineFixtures.moved(origin, 1000)], session: old)
        core.engine.activity(ActivitySample(type: "walking", confidence: 100), session: old)
        core.engine.registrationFailed(TrackingError("UNAVAILABLE", "old registration"), session: old)
        XCTAssertEqual(records.count, count); XCTAssertFalse(core.state().flag("isMoving"))
        XCTAssertFalse(events.contains { $0.0 == "locationerror" })
        core.engine.activity(ActivitySample(type: "walking", confidence: 100), session: core.engine.sessionID)
        XCTAssertTrue(core.state().flag("isMoving"))
    }
    func testCoarseBackendFallbackIsUsedOnlyWithoutAVettedFix() throws {
        try prepare()
        var coarse = EngineFixtures.moved(origin, 1000); coarse.accuracy = 500
        core.engine.offerLastLocation(coarse); try core.engine.start(); core.engine.initialFix(nil)
        XCTAssertEqual(records.first?.object("coords").number("accuracy"), 500)
        try core.engine.stop(); records = []; advance(2000)
        try core.engine.start(); let vetted = origin; core.engine.initialFix(vetted)
        coarse.time += 10000; core.engine.offerLastLocation(coarse)
        try core.engine.stop()
        XCTAssertEqual(records.last?.object("coords").number("latitude"), vetted.latitude)
    }
    func testRetryGrowsAndStopCancelsItAndOdometerResetKeepsSession() throws {
        try prepare(); try started()
        core.engine.registrationFailed(TrackingError("UNAVAILABLE", "test")); core.engine.registrationFailed(TrackingError("UNAVAILABLE", "test"))
        XCTAssertEqual(events.filter { $0.0 == "locationerror" }.map { $0.1.number("retryInMs") }, [10000, 20000])
        core.call("setOdometer", ["odometer": 250], native: true) { result in if case .failure(let error) = result { XCTFail(error.localizedDescription) } }
        XCTAssertEqual(core.odometer, 250)
        core.call("resetOdometer", [:], native: true) { result in if case .failure(let error) = result { XCTFail(error.localizedDescription) } }
        XCTAssertEqual(core.odometer, 0)
        try core.engine.stop(); let count = records.count; advance(60000); XCTAssertEqual(records.count, count)
    }
    func testRecordMetadataUsesAndroidFixtureAndReadsRuntimeAtCallTime() throws {
        try prepare(["persistence": ["extras": ["driver_id": 7]]])
        try core.updateRuntime(["isMoving": true, "odometer": 1532.4, "activity": ["type": "in_vehicle", "confidence": 92]])
        let wire = core.makeRecord(event: "location", fix: EngineFixtures.location())
        XCTAssertEqual(wire.text("timestamp"), "2026-09-26T10:15:30.123Z")
        XCTAssertEqual(wire.text("recorded_at"), "2026-09-26T10:15:30.456Z")
        XCTAssertEqual(wire.number("elapsed_realtime_ms"), 86400123)
        XCTAssertEqual(wire.number("boot_count"), -1); XCTAssertEqual(wire.text("backend"), "corelocation")
        XCTAssertTrue(wire.flag("is_moving")); XCTAssertEqual(wire.number("odometer"), 1532.4)
        XCTAssertEqual(wire.object("activity").text("type"), "in_vehicle"); XCTAssertEqual(wire.object("activity").number("confidence"), 92)
        XCTAssertEqual(wire.object("extras").number("driver_id"), 7)
        XCTAssertEqual(wire.object("coords").number("speed_accuracy"), 0.8)
        XCTAssertEqual(wire.object("coords").number("heading_accuracy"), 5)
        advance(5000); try core.updateRuntime(["isMoving": false, "odometer": 2000, "activity": ["type": "unknown", "confidence": 0]])
        let next = core.makeRecord(event: "heartbeat", fix: nil)
        XCTAssertFalse(next.flag("is_moving")); XCTAssertEqual(next.number("odometer"), 2000)
        XCTAssertEqual(next.number("elapsed_realtime_ms"), 86405123)
        XCTAssertTrue(next["timestamp"] is NSNull); XCTAssertTrue(next["coords"] is NSNull)
        XCTAssertFalse(next.object("device").text("thermal_status").isEmpty)
        let ids = Set((0..<200).map { _ in core.makeRecord(event: "location", fix: nil).text("uuid") })
        XCTAssertEqual(ids.count, 200)
        XCTAssertTrue(ids.allSatisfy { UUID(uuidString: $0) != nil })
    }
}

private extension Result {
    var failure: Error? { if case .failure(let error) = self { return error }; return nil }
}
