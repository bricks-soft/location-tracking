import Foundation

final class TrackingEngine {
    private unowned let core: TrackingCore
    private(set) var running = false
    private(set) var provider: TrackingJSON = [:]
    private var foreground = false
    private var mode = "location"
    private(set) var sessionID = UUID().uuidString
    private var deadline: SessionDeadline?
    private var motion = MotionStateMachine()
    private let processor = LocationProcessor()
    let odometer = Odometer()
    private var lastFix: TrackedLocation?
    private var backendLocation: TrackedLocation?
    private var lastRecorded: TrackedLocation?
    private var motionRecorded = false
    private var lastLocationPersisted: Double?
    private var lastActivityPersisted: Double?
    private var lastActivityType: String?
    private var registrationFailures = 0
    private var armedHeartbeat: HeartbeatWindow?
    private var lastAttempt: Double?
    private var checkInConfig: Data?
    private var assertion: ExecutionAssertion?
    var geofences: GeofenceManager?
    private var geofenceDemand = false
    lazy var timers = TrackingTimers(queue: core.queue, now: { [unowned self] in self.core.clock().elapsed }, automatic: core.automaticTimers)
    lazy var notifier = CheckInNotifier(queue: core.queue, clock: core.clock) { [weak self] in self?.core.log.write("warn", $0) }
    init(core: TrackingCore) {
        self.core = core
        if let at = core.runtime["trackingStartedAt"] as? Double {
            deadline = SessionDeadline(startedAt: at, startedElapsed: core.runtime["trackingStartedElapsed"] as? Double, checkpoint: TrackingInstant.from(core.runtime.object("clockCheckpoint")), now: core.clock())
        }
    }
    private var config: TrackingJSON { core.config.value }
    private var geo: TrackingJSON { config.object("geolocation") }
    private var bestKnown: TrackedLocation? {
        let threshold = geo.object("filter").number("trackingAccuracyThreshold", 100)
        let vetted = backendLocation.flatMap { threshold <= 0 || $0.accuracyMeters <= threshold ? $0 : nil }
        return [lastFix, TrackedLocation.from(core.runtime.object("lastLocation")), vetted].compactMap { $0 }.max { $0.isOlder(than: $1) } ?? backendLocation
    }
    func offerLastLocation(_ location: TrackedLocation?) { backendLocation = location }
    func context(provider: TrackingJSON, foreground: Bool) { self.provider = provider; self.foreground = foreground }
    func restore() throws {
        guard core.runtime.flag("enabled") else { applyProfile(force: true); notifier.cancel(); geofences?.releaseStale(); return }
        guard !running else { return }
        if let remaining = deadline?.remaining(minutes: geo.number("stopAfterElapsedMinutes"), now: core.clock()), remaining <= 0 { try stop(reason: "stop_after_elapsed"); return }
        guard provider["enabled"] as? Bool != false else { scheduleElapsedStop(); return }
        guard provider.text("permission") == "always" || provider.text("permission") == "when_in_use" else { try stop(reason: "permission_denied"); return }
        if deadline == nil { deadline = SessionDeadline(now: core.clock()) }
        try saveSession(enabled: true, mode: core.runtime.text("trackingMode", "location"))
        guard foreground || provider.text("permission") == "always" else { scheduleElapsedStop(); return }
        activate(mode: core.runtime.text("trackingMode", "location"), reason: "restore")
    }
    func start(mode: String = "location") throws {
        if running && self.mode == mode { return }
        guard provider.text("permission") == "always" || provider.text("permission") == "when_in_use" else { throw TrackingError("PERMISSION_DENIED", "Location permission is required") }
        guard foreground || provider.text("permission") == "always" else { throw TrackingError("PERMISSION_DENIED", "Start When In Use tracking while the app is in the foreground") }
        let switching = running
        if switching && self.mode == "location" && motion.isMoving { recordMotion(false, bestKnown) }
        deadline = SessionDeadline(now: core.clock())
        try saveSession(enabled: true, mode: mode)
        if switching { configure(mode: mode); submit("tracking_start", bestKnown, reason: mode == "location" ? "start" : "start_geofences") }
        else {
            activate(mode: mode, reason: mode == "location" ? "start" : "start_geofences")
            core.emit("enabledchange", ["enabled": true, "reason": mode == "location" ? "start" : "start_geofences"])
        }
    }
    private func saveSession(enabled: Bool, mode: String) throws {
        guard let deadline else { return }
        try core.updateRuntime(["enabled": enabled, "trackingMode": mode, "isMoving": false, "trackingStartedAt": deadline.startedAt, "trackingStartedElapsed": deadline.startedElapsed])
    }
    private func activate(mode: String, reason: String) {
        running = true; core.syncer.tracking = true
        processor.reset(); lastAttempt = nil; armedHeartbeat = nil
        notifier.start()
        configure(mode: mode)
        submit("tracking_start", bestKnown, reason: reason)
    }
    private func configure(mode: String) {
        cancelInitialFix(); timers.cancelAll()
        sessionID = UUID().uuidString
        self.mode = mode
        motion = MotionStateMachine(MotionSettings.from(config))
        motionRecorded = false; lastRecorded = nil; lastActivityType = nil; lastActivityPersisted = nil; lastLocationPersisted = nil; registrationFailures = 0
        applyProfile()
        scheduleElapsedStop()
        if mode == "location" { initialFixRequest() }
        rescheduleHeartbeat(force: true)
        touchCheckIn(force: true)
        updateGeofenceAccess()
        geofences?.onTrackingStarted(always: provider.text("permission") == "always")
    }
    func stop(reason: String = "stop") throws {
        notifier.cancel()
        guard running || core.runtime.flag("enabled") else { return }
        running = false; timers.cancelAll(); armedHeartbeat = nil; lastAttempt = nil
        assertion?.finish(); assertion = nil
        geofences?.onTrackingStopped()
        cancelInitialFix()
        submit("tracking_stop", bestKnown, reason: reason)
        let stopped: TrackingJSON = ["enabled": false, "isMoving": false, "lastStopReason": reason, "lastStopAt": iso(Date(timeIntervalSince1970: core.clock().wall / 1000))]
        do { try core.updateRuntime(stopped) }
        catch {
            core.runtime.merge(stopped) { _, new in new }
            applyProfile(); core.syncer.tracking = false
            core.emit("enabledchange", ["enabled": false, "reason": reason])
            throw error
        }
        applyProfile(); core.syncer.tracking = false; core.syncer.automatic()
        core.emit("enabledchange", ["enabled": false, "reason": reason])
    }
    func terminate() { if config.object("app").flag("stopOnTerminate", true) { do { try stop(reason: "terminate") } catch { core.log.write("error", "Could not persist termination intent") } } }
    func active(provider: TrackingJSON, foreground: Bool) {
        context(provider: provider, foreground: foreground)
        do { try restore() } catch { core.log.write("error", "Tracking restore failed") }
        if running { applyProfile(force: true); timers.fireDue(); rescheduleHeartbeat(force: true); touchCheckIn() }
    }
    func clockChanged() {
        update([:]); scheduleElapsedStop(); rescheduleHeartbeat(force: true); timers.fireDue()
    }
    func configChanged() {
        if !config.object("notification").object("resume").flag("enabled") { notifier.cancel() }
        guard running else {
            if core.runtime.flag("enabled") { scheduleElapsedStop() }
            return
        }
        execute(motion.updateSettings(MotionSettings.from(config)))
        guard running else { return }
        applyProfile(); scheduleElapsedStop(); rescheduleHeartbeat(force: true); touchCheckIn()
        timers.fireDue()
    }
    func providerChanged(_ value: TrackingJSON) {
        let previous = provider
        guard !NSDictionary(dictionary: previous).isEqual(to: value) else { return }
        provider = value
        core.emit("providerchange", value)
        guard running else {
            if core.runtime.flag("enabled") { do { try restore() } catch { core.log.write("error", "Tracking restore failed") } }
            return
        }
        submit("providerchange", bestKnown)
        if value.flag("enabled") && value.text("permission") == "denied" { try? stop(reason: "permission_denied"); return }
        if value.flag("enabled") && (!previous.flag("enabled") || previous.text("permission") != value.text("permission")) { applyProfile(force: true) }
        if previous.text("accuracy") != value.text("accuracy") { updateGeofenceAccess() }
        if previous.text("permission") != value.text("permission") { geofences?.onTrackingStarted(always: value.text("permission") == "always") }
    }
    func changePace(_ moving: Bool) {
        guard running, mode == "location" else { return }
        execute(motion.force(moving, motion.isMoving == moving ? nil : bestKnown))
    }
    func receive(_ batch: [TrackedLocation], session: String? = nil) {
        guard running, session == nil || session == sessionID else { return }
        registrationFailures = 0
        if let newest = batch.max(by: { $0.isOlder(than: $1) }) { backendLocation = newest }
        if !batch.isEmpty { touchCheckIn() }
        for raw in batch {
            guard running else { return }
            guard case .accepted(var fix) = processor.process(raw, filter: geo.object("filter")) else { continue }
            fix.session = core.runtime["trackingStartedAt"] as? Double
            lastFix = fix
            geofences?.onLocation(fix)
            guard running else { return }
            if mode != "location" {
                let delta = odometer.onLocation(fix, runtime: core.runtime, threshold: geo.object("filter").number("odometerAccuracyThreshold", 20))
                if delta > 0 { update(["odometer": core.odometer + delta]) }
                rememberLocation(fix)
                continue
            }
            let wasMoving = motion.isMoving
            let oldAnchor = motion.anchor
            let actions = motion.onLocation(fix)
            if !wasMoving && !motion.isMoving {
                if let anchor = motion.anchor, anchor != oldAnchor { rememberLocation(anchor, force: true) }
                continue
            }
            let delta = odometer.onLocation(fix, runtime: core.runtime, threshold: geo.object("filter").number("odometerAccuracyThreshold", 20))
            if delta > 0 { update(["odometer": core.odometer + delta]) }
            execute(actions)
            guard running, wasMoving else { continue }
            if ElasticDistance.shouldRecord(lastRecorded, fix, geo) { submit("location", fix); lastRecorded = fix; lastLocationPersisted = core.clock().elapsed }
            else { rememberLocation(fix) }
        }
        timers.fireDue()
    }
    func initialFix(_ raw: TrackedLocation?) {
        guard running, mode == "location", !motionRecorded else { return }
        if let raw, case .accepted(var fix) = processor.process(raw, filter: geo.object("filter")) {
            fix.session = core.runtime["trackingStartedAt"] as? Double
            if lastFix == nil || !fix.isOlder(than: lastFix!) { lastFix = fix }
            motion.setAnchor(fix)
        }
        let known = bestKnown
        motion.offerAnchor(known)
        recordMotion(false, known)
    }
    func activity(_ sample: ActivitySample, session: String? = nil) {
        guard running, session == nil || session == sessionID, !config.object("activity").flag("disableMotionActivityUpdates"), sample.confidence >= Int(config.object("activity").number("minimumActivityRecognitionConfidence", 75)) else { return }
        let now = core.clock().elapsed
        let current = core.runtime.object("activity")
        if current.text("type") != sample.type || lastActivityPersisted == nil || now - lastActivityPersisted! >= 60000 {
            update(["activity": sample.json]); lastActivityPersisted = now
        }
        if lastActivityType != sample.type { lastActivityType = sample.type; core.emit("activitychange", ["activity": sample.type, "confidence": sample.confidence]) }
        if mode == "location" { execute(motion.onActivity(sample)) }
        timers.fireDue()
    }
    func registrationFailed(_ error: TrackingError, session: String? = nil) {
        guard running, session == nil || session == sessionID else { return }
        registrationFailures += 1
        let delay = min(300000, 10000 * pow(2, Double(min(10, registrationFailures - 1))))
        core.emit("locationerror", ["code": error.code, "message": error.message, "backend": "corelocation", "retryInMs": delay])
        timers.schedule("request_retry", after: delay) { [weak self] in guard let self, self.running else { return }; self.applyProfile(force: true) }
    }
    private func execute(_ actions: [MotionAction]) {
        for action in actions {
            guard running else { return }
            switch action {
            case .startStopTimer(let delay): timers.schedule("stop_timeout", after: delay) { [weak self] in guard let self, self.running else { return }; self.execute(self.motion.onStopTimeout(self.bestKnown)) }
            case .cancelStopTimer: timers.cancel("stop_timeout")
            case .startMotionTrigger(let delay): timers.schedule("motion_trigger", after: delay) { [weak self] in guard let self, self.running else { return }; self.execute(self.motion.onMotionTriggerElapsed()) }
            case .cancelMotionTrigger: timers.cancel("motion_trigger")
            case .enterMoving(let location): recordMotion(true, location ?? bestKnown); applyProfile()
            case .enterStationary(let location, let automatic):
                recordMotion(false, location)
                if automatic && geo.flag("stopOnStationary") { try? stop(reason: "stop_on_stationary") }
                else { applyProfile() }
            }
        }
    }
    private func recordMotion(_ moving: Bool, _ fix: TrackedLocation?) {
        update(["isMoving": moving]); submit("motionchange", fix)
        motionRecorded = true
        if let fix { lastRecorded = fix; lastLocationPersisted = core.clock().elapsed }
    }
    private func rememberLocation(_ fix: TrackedLocation, force: Bool = false) {
        let now = core.clock().elapsed
        guard force || lastLocationPersisted == nil || now - lastLocationPersisted! >= 10000 else { return }
        lastLocationPersisted = now; update(["lastLocation": fix.json])
    }
    private func update(_ values: TrackingJSON) {
        do { try core.updateRuntime(values) }
        catch { core.runtime.merge(values) { _, new in new }; core.log.write("error", "Tracking state could not be persisted") }
    }
    private func applyProfile(force: Bool = false) {
        guard let source = core.routeSource else { return }
        let profile = RouteProfile(enabled: running && (mode == "location" || geofenceDemand), moving: mode == "location" ? motion.isMoving : geofenceDemand, accuracy: geo.text("desiredAccuracy", "high"), activityEnabled: mode == "location" && !config.object("activity").flag("disableMotionActivityUpdates"), sessionID: sessionID)
        DispatchQueue.main.async { source.apply(profile, force: force) }
    }
    private func initialFixRequest() {
        guard core.routeSource != nil else { return }
        let id = sessionID
        let options: TrackingJSON = ["desiredAccuracy": geo.text("desiredAccuracy", "high"), "timeout": geo.number("locationTimeout", 30000), "samples": 3, "_rejectMock": geo.object("filter").flag("rejectMockLocations")]
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.core.positions.request(id: "route-" + id, options: options, watch: false) { [weak self] result in
                guard let self else { return }
                self.core.queue.async { [weak self] in guard let self, self.sessionID == id else { return }; self.initialFix((try? result.get()).map { TrackedLocation($0, receivedAt: self.core.clock()) }) }
            }
        }
    }
    private func cancelInitialFix() {
        let id = sessionID, positions = core.positions
        DispatchQueue.main.async { positions.clear("route-" + id) }
    }
    private func scheduleElapsedStop() {
        timers.cancel("elapsed_stop")
        guard core.runtime.flag("enabled"), let remaining = deadline?.remaining(minutes: geo.number("stopAfterElapsedMinutes"), now: core.clock()) else { return }
        timers.schedule("elapsed_stop", after: remaining) { [weak self] in try? self?.stop(reason: "stop_after_elapsed") }
    }
    private func touchCheckIn(force: Bool = false) {
        guard running else { return }
        let settings = config.object("notification").object("resume")
        let encoded = try? jsonData(settings)
        let changed = encoded != checkInConfig
        checkInConfig = encoded
        notifier.touch(settings, deadlineElapsed: deadline?.elapsedDeadline(minutes: geo.number("stopAfterElapsedMinutes")), force: force || changed)
    }
    private func window() -> HeartbeatWindow {
        let now = core.clock(), hb = config.object("heartbeat")
        return HeartbeatWindow.compute(lastRecordAt: timestamp(core.runtime["lastRecordAt"]), lastRecordElapsed: core.runtime["lastRecordElapsed"] as? Double, sameBoot: true, now: now.wall, nowElapsed: now.elapsed, minInterval: hb.number("minInterval", 180), maxInterval: hb.number("maxInterval", 300), enabled: running && hb.flag("enabled", true), noRecordBaseElapsed: deadline?.startedElapsed, lastAttemptElapsed: lastAttempt, trackingStartedAt: deadline?.startedAt)
    }
    func recorded() { if running { rescheduleHeartbeat(); touchCheckIn() } }
    private func rescheduleHeartbeat(force: Bool = false) {
        let next = window()
        guard next.enabled else { timers.cancel("heartbeat"); armedHeartbeat = nil; return }
        guard force || next.needsRearm(armedHeartbeat) else { return }
        armedHeartbeat = next
        timers.schedule("heartbeat", after: next.dueElapsed - core.clock().elapsed) { [weak self] in self?.heartbeat() }
    }
    func heartbeat() {
        guard running else { return }
        if let remaining = deadline?.remaining(minutes: geo.number("stopAfterElapsedMinutes"), now: core.clock()), remaining <= 0 { try? stop(reason: "stop_after_elapsed"); return }
        let current = window(), now = core.clock()
        guard current.enabled else { return }
        guard current.shouldFire(now.elapsed) else { rescheduleHeartbeat(force: true); return }
        if now.elapsed > current.deadlineElapsed + 1000 { core.log.write("warn", "Heartbeat arrived after heartbeat.maxInterval") }
        lastAttempt = now.elapsed
        var wire = core.makeRecord(event: "heartbeat", fix: bestKnown)
        let next = HeartbeatWindow.compute(lastRecordAt: now.wall, lastRecordElapsed: now.elapsed, sameBoot: true, now: now.wall, nowElapsed: now.elapsed, minInterval: config.object("heartbeat").number("minInterval", 180), maxInterval: config.object("heartbeat").number("maxInterval", 300))
        wire["heartbeat"] = ["strategy": "timer", "min_interval": config.object("heartbeat").number("minInterval", 180), "max_interval": config.object("heartbeat").number("maxInterval", 300), "next_at": next.nextHeartbeatAt.map { iso(Date(timeIntervalSince1970: $0 / 1000)) } as Any? ?? NSNull(), "battery_exempt": false, "device_idle": false]
        assertion?.finish()
        let upload = ExecutionAssertion(); assertion = upload
        upload.start(seconds: config.object("heartbeat").number("uploadKeepAwake", 10), queue: core.queue)
        core.deliver(wire, location: bestKnown) { upload.finish() }
        rescheduleHeartbeat(force: true)
    }
    func heartbeatStatus() -> TrackingJSON {
        let hb = config.object("heartbeat"), current = window()
        return ["enabled": hb.flag("enabled", true), "minInterval": hb.number("minInterval", 180), "maxInterval": hb.number("maxInterval", 300), "lastRecordAt": core.runtime["lastRecordAt"] ?? NSNull(), "lastHeartbeatAt": core.runtime["lastHeartbeatAt"] ?? NSNull(), "nextHeartbeatAt": current.nextHeartbeatAt.map { iso(Date(timeIntervalSince1970: $0 / 1000)) } as Any? ?? NSNull(), "strategy": current.enabled ? "timer" : "disabled", "canScheduleExactAlarms": false, "isIgnoringBatteryOptimizations": false, "isDeviceIdleMode": false, "isPowerSaveMode": ProcessInfo.processInfo.isLowPowerModeEnabled, "pendingHeartbeats": (try? core.store.count(event: "heartbeat")) ?? 0]
    }
    private func timestamp(_ value: Any?) -> Double? {
        guard let text = value as? String else { return nil }
        let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: text).map { $0.timeIntervalSince1970 * 1000 }
    }
    func geofenceDemandChanged(_ needs: Bool) {
        guard geofenceDemand != needs else { return }
        geofenceDemand = needs
        if running { applyProfile() }
    }
    func recordGeofence(_ spec: GeofenceSpec, _ action: GeofenceAction, _ location: TrackedLocation?) {
        var wire = core.makeRecord(event: "geofence", fix: location)
        var hit: TrackingJSON = ["identifier": spec.identifier, "action": action.rawValue]
        if let extras = spec.extras { hit["extras"] = extras }
        wire["geofence"] = hit
        core.deliver(wire, location: location)
    }
    private func updateGeofenceAccess() { geofences?.setSuspended(provider.text("accuracy") == "approximate") }
    func geofenceFailures(_ failures: [MonitorFailure]) {
        for failure in failures {
            core.log.write("error", failure.message + " (" + failure.code + ")")
            core.emit("locationerror", ["code": failure.code, "message": failure.message, "backend": "corelocation", "retryInMs": NSNull()])
        }
    }
    func geofencesBlocked() {
        core.emit("locationerror", ["code": "PERMISSION_DENIED", "message": "Geofences need precise location; stored geofences stay inactive while location accuracy is approximate", "backend": "corelocation", "retryInMs": NSNull()])
    }
    private func submit(_ event: String, _ location: TrackedLocation?, reason: String? = nil) {
        var wire = core.makeRecord(event: event, fix: location)
        if let reason { wire["reason"] = reason }
        core.deliver(wire, location: location)
    }
}
