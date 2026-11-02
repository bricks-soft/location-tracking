import Foundation

struct ActivitySample: Equatable {
    var type: String
    var confidence: Int
    var json: TrackingJSON { ["type": type, "confidence": confidence] }
    static func map(automotive: Bool, cycling: Bool, running: Bool, walking: Bool, stationary: Bool, confidence: Int) -> ActivitySample {
        ActivitySample(type: automotive ? "in_vehicle" : cycling ? "on_bicycle" : running ? "running" : walking ? "walking" : stationary ? "still" : "unknown", confidence: confidence)
    }
}

struct MotionSettings: Equatable {
    var stationaryRadius = 25.0
    var minimumConfidence = 75
    var motionTriggerDelay = 0.0
    var stopTimeout = 300000.0
    var disableStopDetection = false
    var trackingAccuracyThreshold = 100.0
    static func from(_ config: TrackingJSON) -> MotionSettings {
        let geo = config.object("geolocation"), activity = config.object("activity")
        return MotionSettings(stationaryRadius: max(0, geo.number("stationaryRadius", 25)), minimumConfidence: Int(activity.number("minimumActivityRecognitionConfidence", 75)), motionTriggerDelay: max(0, activity.number("motionTriggerDelay")), stopTimeout: max(1, geo.number("stopTimeout", 5)) * 60000, disableStopDetection: activity.flag("disableStopDetection"), trackingAccuracyThreshold: geo.object("filter").number("trackingAccuracyThreshold", 100))
    }
}

enum MotionAction: Equatable {
    case enterMoving(TrackedLocation?)
    case enterStationary(TrackedLocation?, automatic: Bool)
    case startStopTimer(Double)
    case cancelStopTimer
    case startMotionTrigger(Double)
    case cancelMotionTrigger
}

final class MotionStateMachine {
    private(set) var settings: MotionSettings
    private(set) var isMoving = false
    private(set) var anchor: TrackedLocation?
    private(set) var motionAnchor: TrackedLocation?
    private(set) var isStopTimerArmed = false
    private(set) var isMotionTriggerArmed = false
    static let movingActivities: Set<String> = ["walking", "running", "on_foot", "on_bicycle", "in_vehicle"]
    init(_ settings: MotionSettings = MotionSettings()) { self.settings = settings }
    func reset(isMoving: Bool = false, anchor: TrackedLocation? = nil) {
        self.isMoving = isMoving
        self.anchor = isMoving ? nil : anchor
        motionAnchor = isMoving ? anchor : nil
        isStopTimerArmed = false; isMotionTriggerArmed = false
    }
    func offerAnchor(_ location: TrackedLocation?) { if !isMoving && anchor == nil { anchor = location } }
    func setAnchor(_ location: TrackedLocation) { if !isMoving { anchor = location } }
    func updateSettings(_ new: MotionSettings) -> [MotionAction] {
        let old = settings; settings = new
        if !isMoving {
            if isMotionTriggerArmed && new.motionTriggerDelay != old.motionTriggerDelay { isMotionTriggerArmed = false; return [.cancelMotionTrigger] }
            return []
        }
        if new.disableStopDetection { return cancelStopTimer() }
        if !isStopTimerArmed || new.stopTimeout != old.stopTimeout { return restartStopTimer() }
        return []
    }
    func onLocation(_ fix: TrackedLocation) -> [MotionAction] {
        if !isMoving {
            guard let current = anchor else { anchor = fix; return [] }
            if Geo.distance(current, fix) - fix.accuracyMeters > settings.stationaryRadius { return isAccurateEnough(fix) ? enterMoving(fix) : [] }
            if fix.accuracyMeters < current.accuracyMeters { anchor = fix }
            return []
        }
        guard let reference = motionAnchor else { motionAnchor = fix; return ensureStopTimer() }
        if Geo.distance(reference, fix) > max(settings.stationaryRadius, fix.accuracyMeters) { motionAnchor = fix; return restartStopTimer() }
        return ensureStopTimer()
    }
    func onActivity(_ sample: ActivitySample) -> [MotionAction] {
        guard sample.confidence >= settings.minimumConfidence else { return [] }
        let moving = Self.movingActivities.contains(sample.type)
        if isMoving {
            if moving { return restartStopTimer() }
            return sample.type == "still" ? ensureStopTimer() : []
        }
        if moving && settings.motionTriggerDelay <= 0 { return enterMoving(nil) }
        if moving && !isMotionTriggerArmed { isMotionTriggerArmed = true; return [.startMotionTrigger(settings.motionTriggerDelay)] }
        if sample.type == "still" && isMotionTriggerArmed { isMotionTriggerArmed = false; return [.cancelMotionTrigger] }
        return []
    }
    func onMotionTriggerElapsed() -> [MotionAction] {
        let armed = isMotionTriggerArmed; isMotionTriggerArmed = false
        return armed && !isMoving ? enterMoving(nil) : []
    }
    func onStopTimeout(_ location: TrackedLocation?) -> [MotionAction] {
        let armed = isStopTimerArmed; isStopTimerArmed = false
        return armed && isMoving ? enterStationary(location, automatic: true) : []
    }
    func force(_ moving: Bool, _ location: TrackedLocation?) -> [MotionAction] {
        if moving { return isMoving ? restartStopTimer() : enterMoving(location) }
        return isMoving ? enterStationary(location, automatic: false) : []
    }
    func isAccurateEnough(_ fix: TrackedLocation) -> Bool { settings.trackingAccuracyThreshold <= 0 || fix.accuracyMeters <= settings.trackingAccuracyThreshold }
    private func enterMoving(_ fix: TrackedLocation?) -> [MotionAction] {
        var actions: [MotionAction] = []
        if isMotionTriggerArmed { isMotionTriggerArmed = false; actions.append(.cancelMotionTrigger) }
        isMoving = true; anchor = nil; motionAnchor = fix
        actions.append(.enterMoving(fix)); actions += restartStopTimer()
        return actions
    }
    private func enterStationary(_ fix: TrackedLocation?, automatic: Bool) -> [MotionAction] {
        var actions = cancelStopTimer()
        if isMotionTriggerArmed { isMotionTriggerArmed = false; actions.append(.cancelMotionTrigger) }
        isMoving = false; anchor = fix; motionAnchor = nil
        actions.append(.enterStationary(fix, automatic: automatic))
        return actions
    }
    private func restartStopTimer() -> [MotionAction] {
        guard !settings.disableStopDetection else { return [] }
        isStopTimerArmed = true
        return [.startStopTimer(settings.stopTimeout)]
    }
    private func ensureStopTimer() -> [MotionAction] { isStopTimerArmed ? [] : restartStopTimer() }
    private func cancelStopTimer() -> [MotionAction] {
        guard isStopTimerArmed else { return [] }
        isStopTimerArmed = false
        return [.cancelStopTimer]
    }
}
