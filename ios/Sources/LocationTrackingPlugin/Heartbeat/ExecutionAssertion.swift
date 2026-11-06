import Foundation
import UIKit

final class ExecutionAssertion {
    private let lock = NSLock()
    private let begin: (@escaping () -> Void) -> Int
    private let end: (Int) -> Void
    private var identifier: Int?
    private var ended = false
    private var timeout: DispatchWorkItem?
    init(begin: @escaping (@escaping () -> Void) -> Int = { expiration in UIApplication.shared.beginBackgroundTask(withName: "Location tracking heartbeat", expirationHandler: expiration).rawValue }, end: @escaping (Int) -> Void = { UIApplication.shared.endBackgroundTask(UIBackgroundTaskIdentifier(rawValue: $0)) }) { self.begin = begin; self.end = end }
    func start(seconds: Double, queue: DispatchQueue) {
        guard seconds > 0 else { finish(); return }
        let id = begin { [weak self] in self?.finish() }
        lock.lock()
        if ended { lock.unlock(); if id != UIBackgroundTaskIdentifier.invalid.rawValue { end(id) }; return }
        identifier = id
        let item = DispatchWorkItem { [weak self] in self?.finish() }
        timeout = item
        lock.unlock()
        queue.asyncAfter(deadline: .now() + seconds, execute: item)
    }
    func finish() {
        lock.lock()
        guard !ended else { lock.unlock(); return }
        ended = true
        timeout?.cancel(); timeout = nil
        let id = identifier; identifier = nil
        lock.unlock()
        if let id, id != UIBackgroundTaskIdentifier.invalid.rawValue { end(id) }
    }
    deinit { finish() }
}
