import XCTest
@testable import LocationTrackingPlugin

final class MotionTests: XCTestCase {
    let origin = EngineFixtures.location(accuracy: 5)
    func testStartsStationaryAndFirstFixAnchors() {
        let sm = MotionStateMachine()
        XCTAssertFalse(sm.isMoving); XCTAssertNil(sm.anchor)
        XCTAssertEqual(sm.onLocation(origin), []); XCTAssertEqual(sm.anchor, origin)
        XCTAssertEqual(sm.onLocation(EngineFixtures.moved(origin, 20)), []); XCTAssertEqual(sm.anchor, origin)
    }
    func testCertainlyOutsideRadiusAndAccuracyBoundaries() {
        for (distance, accuracy, moving) in [(40.0, 5.0, true), (60, 80, false), (40, 16, false), (40, 14, true), (2000, 101, false), (2000, 100, true)] {
            let sm = MotionStateMachine(); _ = sm.onLocation(origin)
            var far = EngineFixtures.moved(origin, distance); far.accuracy = accuracy
            let actions = sm.onLocation(far)
            XCTAssertEqual(sm.isMoving, moving)
            XCTAssertEqual(actions, moving ? [.enterMoving(far), .startStopTimer(300000)] : [])
            XCTAssertEqual(sm.isStopTimerArmed, moving)
        }
    }
    func testAnchorAccuracyDoesNotWidenExitRadius() {
        let sm = MotionStateMachine(); var coarse = origin; coarse.accuracy = 1500
        _ = sm.onLocation(coarse)
        var far = EngineFixtures.moved(coarse, 800); far.accuracy = 10
        XCTAssertEqual(sm.onLocation(far).first, .enterMoving(far))
    }
    func testBetterFixReplacesAnchorAndWorseKeepsIt() {
        let sm = MotionStateMachine(); var coarse = origin; coarse.accuracy = 20
        _ = sm.onLocation(coarse)
        var better = EngineFixtures.moved(coarse, 20); better.accuracy = 4
        var worse = EngineFixtures.moved(better, -10); worse.accuracy = 15
        XCTAssertEqual(sm.onLocation(better), []); XCTAssertEqual(sm.anchor, better)
        XCTAssertEqual(sm.onLocation(worse), []); XCTAssertEqual(sm.anchor, better)
        XCTAssertEqual(sm.onLocation(EngineFixtures.moved(better, 30)).first, .enterMoving(EngineFixtures.moved(better, 30)))
    }
    func testThresholdZeroAndSetAnchorOnlyWhileStationary() {
        let sm = MotionStateMachine(MotionSettings(trackingAccuracyThreshold: 0))
        _ = sm.onLocation(origin)
        let fresh = EngineFixtures.moved(origin, 300)
        sm.setAnchor(fresh); XCTAssertEqual(sm.anchor, fresh)
        var coarse = EngineFixtures.moved(fresh, 2000); coarse.accuracy = 500
        XCTAssertEqual(sm.onLocation(coarse).first, .enterMoving(coarse))
        sm.setAnchor(origin); XCTAssertNil(sm.anchor)
    }
    func testEveryMovingActivityAndBelowThresholdUnknownIgnored() {
        for type in MotionStateMachine.movingActivities {
            let sm = MotionStateMachine()
            XCTAssertEqual(sm.onActivity(ActivitySample(type: type, confidence: 90)), [.enterMoving(nil), .startStopTimer(300000)])
        }
        for sample in [ActivitySample(type: "walking", confidence: 74), ActivitySample(type: "unknown", confidence: 100), ActivitySample(type: "still", confidence: 100)] {
            let sm = MotionStateMachine(); XCTAssertEqual(sm.onActivity(sample), []); XCTAssertFalse(sm.isMoving)
        }
    }
    func testCoreMotionConfidenceThresholds33_66_67() {
        for threshold in [33, 66, 67, 75] {
            for confidence in [33, 66, 100] {
                let sm = MotionStateMachine(MotionSettings(minimumConfidence: threshold))
                _ = sm.onActivity(ActivitySample(type: "walking", confidence: confidence))
                XCTAssertEqual(sm.isMoving, confidence >= threshold)
            }
        }
    }
    func testActivityFlagPrecedence() {
        let expected = ["in_vehicle", "on_bicycle", "running", "walking", "still", "unknown"]
        for i in 0..<6 {
            let sample = ActivitySample.map(automotive: i == 0, cycling: i <= 1, running: i <= 2, walking: i <= 3, stationary: i <= 4, confidence: 66)
            XCTAssertEqual(sample.type, expected[i]); XCTAssertEqual(sample.confidence, 66)
        }
    }
    func testMotionTriggerMustBeSustainedAndStillCancels() {
        let sm = MotionStateMachine(MotionSettings(motionTriggerDelay: 30000))
        XCTAssertEqual(sm.onActivity(ActivitySample(type: "walking", confidence: 90)), [.startMotionTrigger(30000)])
        XCTAssertEqual(sm.onActivity(ActivitySample(type: "running", confidence: 90)), [])
        XCTAssertEqual(sm.onActivity(ActivitySample(type: "still", confidence: 90)), [.cancelMotionTrigger])
        XCTAssertEqual(sm.onMotionTriggerElapsed(), []); XCTAssertFalse(sm.isMoving)
        _ = sm.onActivity(ActivitySample(type: "walking", confidence: 90))
        XCTAssertEqual(sm.onMotionTriggerElapsed(), [.enterMoving(nil), .startStopTimer(300000)])
    }
    func testDistanceCancelsPendingMotionTrigger() {
        let sm = MotionStateMachine(MotionSettings(motionTriggerDelay: 30000)); _ = sm.onLocation(origin)
        _ = sm.onActivity(ActivitySample(type: "walking", confidence: 90))
        let far = EngineFixtures.moved(origin, 100)
        XCTAssertEqual(sm.onLocation(far), [.cancelMotionTrigger, .enterMoving(far), .startStopTimer(300000)])
    }
    func testMovingDisplacementAndActivityRestartStopTimer() {
        let sm = MotionStateMachine(); let start = EngineFixtures.moved(origin, 100)
        _ = sm.onLocation(origin); _ = sm.onLocation(start)
        let next = EngineFixtures.moved(start, 30)
        XCTAssertEqual(sm.onLocation(next), [.startStopTimer(300000)]); XCTAssertEqual(sm.motionAnchor, next)
        XCTAssertEqual(sm.onLocation(EngineFixtures.moved(next, 10)), []); XCTAssertEqual(sm.motionAnchor, next)
        XCTAssertEqual(sm.onActivity(ActivitySample(type: "on_foot", confidence: 80)), [.startStopTimer(300000)])
        XCTAssertEqual(sm.onActivity(ActivitySample(type: "still", confidence: 99)), [])
        XCTAssertEqual(sm.onStopTimeout(origin), [.enterStationary(origin, automatic: true)])
        XCTAssertEqual(sm.anchor, origin); XCTAssertFalse(sm.isStopTimerArmed)
        XCTAssertEqual(sm.onStopTimeout(origin), [])
    }
    func testMovingWithoutKnownLocationTakesFirstMotionReference() {
        let sm = MotionStateMachine()
        XCTAssertEqual(sm.force(true, nil), [.enterMoving(nil), .startStopTimer(300000)])
        XCTAssertNil(sm.motionAnchor); XCTAssertEqual(sm.onLocation(origin), []); XCTAssertEqual(sm.motionAnchor, origin)
    }
    func testDisableStopDetectionAndLiveSettings() {
        let sm = MotionStateMachine(); _ = sm.force(true, origin)
        XCTAssertEqual(sm.updateSettings(MotionSettings(disableStopDetection: true)), [.cancelStopTimer])
        XCTAssertEqual(sm.onLocation(EngineFixtures.moved(origin, 100)), [])
        XCTAssertEqual(sm.updateSettings(MotionSettings()), [.startStopTimer(300000)])
        XCTAssertEqual(sm.updateSettings(MotionSettings(stationaryRadius: 50)), [])
        XCTAssertEqual(sm.updateSettings(MotionSettings(stopTimeout: 60000)), [.startStopTimer(60000)])
    }
    func testTriggerSettingsChangeCancelsOnlyChangedDelay() {
        let sm = MotionStateMachine(MotionSettings(motionTriggerDelay: 600000)); _ = sm.onActivity(ActivitySample(type: "walking", confidence: 90))
        XCTAssertEqual(sm.updateSettings(MotionSettings(motionTriggerDelay: 600000, stopTimeout: 1)), [])
        XCTAssertEqual(sm.updateSettings(MotionSettings()), [.cancelMotionTrigger]); XCTAssertFalse(sm.isMotionTriggerArmed)
    }
    func testForceAndReset() {
        let sm = MotionStateMachine(MotionSettings(motionTriggerDelay: 30000)); _ = sm.onActivity(ActivitySample(type: "walking", confidence: 90))
        XCTAssertEqual(sm.force(true, origin), [.cancelMotionTrigger, .enterMoving(origin), .startStopTimer(300000)])
        XCTAssertEqual(sm.force(true, origin), [.startStopTimer(300000)])
        XCTAssertEqual(sm.force(false, origin), [.cancelStopTimer, .enterStationary(origin, automatic: false)])
        XCTAssertEqual(sm.force(false, origin), [])
        sm.reset(); XCTAssertFalse(sm.isMoving); XCTAssertNil(sm.anchor); XCTAssertNil(sm.motionAnchor)
        sm.offerAnchor(origin); sm.offerAnchor(EngineFixtures.moved(origin, 5)); XCTAssertEqual(sm.anchor, origin)
    }
    func testSettingsFromConfigAndMinimumStopTimeout() {
        let config: TrackingJSON = ["geolocation": ["stationaryRadius": 40, "stopTimeout": 3], "activity": ["minimumActivityRecognitionConfidence": 60, "motionTriggerDelay": 15000, "disableStopDetection": true]]
        XCTAssertEqual(MotionSettings.from(config), MotionSettings(stationaryRadius: 40, minimumConfidence: 60, motionTriggerDelay: 15000, stopTimeout: 180000, disableStopDetection: true))
        XCTAssertEqual(MotionSettings.from(["geolocation": ["stopTimeout": 0]]).stopTimeout, 60000)
    }
}
