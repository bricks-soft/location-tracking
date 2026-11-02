import Foundation

final class BackgroundUpload {
    private let syncer: Syncer
    private let completion: (Bool) -> Void
    private var completed = false
    init(syncer: Syncer, completion: @escaping (Bool) -> Void) { self.syncer = syncer; self.completion = completion }
    func start() { syncer.automatic { [self] result in finish((try? result.get()) != nil) } }
    func expire() {
        guard !completed else { return }
        finish(false)
        syncer.cancel()
    }
    private func finish(_ success: Bool) {
        guard !completed else { return }
        completed = true
        completion(success)
    }
}
