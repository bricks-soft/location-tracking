import Foundation

struct HTTPResponse {
    let status: Int
    let body: Data
    let retryAfter: String?
    var success: Bool { (200..<300).contains(status) }
}

final class HTTPTransport: NSObject, URLSessionDataDelegate {
    private struct Pending {
        var data = Data()
        var response: HTTPURLResponse?
        var timeout: DispatchWorkItem?
        let responseLimit: Int?
        let completion: (Result<HTTPResponse, Error>) -> Void
    }
    private var pending: [Int: Pending] = [:]
    private let lock = NSLock()
    private var closed = false
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.httpCookieStorage = nil
        config.urlCredentialStorage = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.waitsForConnectivity = false
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()
    func send(url: URL, method: String, headers: [String: String], body: Data, timeout: Double, responseLimit: Int? = 16384, completion: @escaping (Result<HTTPResponse, Error>) -> Void) {
        var request = URLRequest(url: url, timeoutInterval: timeout / 1000)
        request.httpMethod = method
        request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        lock.lock()
        guard !closed else { lock.unlock(); completion(.failure(URLError(.cancelled))); return }
        let task = session.dataTask(with: request)
        let deadline = DispatchWorkItem { [weak self, weak task] in
            guard let self, let task else { return }
            self.lock.lock(); let item = self.pending.removeValue(forKey: task.taskIdentifier); self.lock.unlock()
            guard let item else { return }
            task.cancel()
            item.completion(.failure(URLError(.timedOut)))
        }
        pending[task.taskIdentifier] = Pending(timeout: deadline, responseLimit: responseLimit, completion: completion); lock.unlock()
        DispatchQueue.global().asyncAfter(deadline: .now() + timeout / 1000, execute: deadline)
        task.resume()
    }
    func cancel() {
        lock.lock()
        guard !closed else { lock.unlock(); return }
        let current = session
        lock.unlock()
        current.getAllTasks { $0.forEach { $0.cancel() } }
    }
    func close() {
        lock.lock()
        guard !closed else { lock.unlock(); return }
        closed = true
        let current = session
        lock.unlock()
        current.invalidateAndCancel()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        lock.lock(); pending[dataTask.taskIdentifier]?.response = response as? HTTPURLResponse; lock.unlock()
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        if var item = pending[dataTask.taskIdentifier] {
            if let limit = item.responseLimit { item.data.append(data.prefix(max(0, limit - item.data.count))) }
            else { item.data.append(data) }
            pending[dataTask.taskIdentifier] = item
        }
        lock.unlock()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock(); let item = pending.removeValue(forKey: task.taskIdentifier); lock.unlock()
        guard let item else { return }
        item.timeout?.cancel()
        if let error { item.completion(.failure(error)) }
        else if let response = item.response { item.completion(.success(HTTPResponse(status: response.statusCode, body: item.data, retryAfter: response.value(forHTTPHeaderField: "Retry-After")))) }
        else { item.completion(.failure(TrackingError("NETWORK_ERROR", "No HTTP response"))) }
    }
}
