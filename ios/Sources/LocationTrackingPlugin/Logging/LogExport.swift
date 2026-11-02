import Foundation
import UIKit
import MessageUI
import zlib

final class LogExport: NSObject, MFMailComposeViewControllerDelegate {
    private let transport = HTTPTransport()
    private var mailer: MFMailComposeViewController?
    static func gzip(_ data: Data) throws -> Data {
        var stream = z_stream()
        guard deflateInit2_(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 31, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { throw TrackingError("IO_ERROR", "Cannot initialize log compression") }
        defer { deflateEnd(&stream) }
        var output = Data(count: Int(compressBound(uLong(data.count))) + 32)
        let status = data.withUnsafeBytes { input in
            output.withUnsafeMutableBytes { buffer in
                stream.next_in = UnsafeMutablePointer(mutating: input.baseAddress?.assumingMemoryBound(to: Bytef.self))
                stream.avail_in = uInt(data.count)
                stream.next_out = buffer.baseAddress?.assumingMemoryBound(to: Bytef.self)
                stream.avail_out = uInt(buffer.count)
                return deflate(&stream, Z_FINISH)
            }
        }
        guard status == Z_STREAM_END else { throw TrackingError("IO_ERROR", "Cannot compress logs") }
        output.count = Int(stream.total_out)
        return output
    }
    func upload(_ text: String, options: TrackingJSON, completion: @escaping (Result<TrackingJSON, Error>) -> Void) throws {
        let address = try options.requiringString("url")
        guard let url = URL(string: address), ["https", "http"].contains(url.scheme ?? ""), url.host != nil else { throw TrackingError("INVALID_ARGUMENT", "url must be HTTP or HTTPS") }
        let boundary = UUID().uuidString
        var body = Data()
        func append(_ text: String) { body.append(Data(text.utf8)) }
        append("--\(boundary)\r\nContent-Disposition: form-data; name=\"log\"; filename=\"location-tracking.log.gz\"\r\nContent-Type: application/gzip\r\n\r\n")
        body.append(try Self.gzip(Data(text.utf8)))
        append("\r\n")
        for (key, value) in options.object("params") {
            guard !key.contains("\r"), !key.contains("\n"), !key.contains("\"") else { throw TrackingError("INVALID_ARGUMENT", "Invalid multipart field name") }
            let text: String
            if let string = value as? String { text = string }
            else { text = String(decoding: try jsonData(value), as: UTF8.self) }
            append("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(key)\"\r\n\r\n\(text)\r\n")
        }
        append("--\(boundary)--\r\n")
        var headers = options["headers"] as? [String: String] ?? [:]
        headers["Content-Type"] = "multipart/form-data; boundary=\(boundary)"
        transport.send(url: url, method: "POST", headers: headers, body: body, timeout: 60000) { result in
            switch result {
            case .success(let response): completion(.success(["success": response.success, "status": response.status]))
            case .failure: completion(.success(["success": false, "status": 0]))
            }
        }
    }
    func email(_ text: String, options: TrackingJSON, completion: @escaping (Result<TrackingJSON, Error>) -> Void) {
        do {
            let email = try options.requiringString("email")
            guard MFMailComposeViewController.canSendMail() else { throw TrackingError("UNAVAILABLE", "Mail is not configured on this device") }
            guard mailer == nil else { throw TrackingError("UNAVAILABLE", "Log email is already open") }
            guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }), var presenter = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { throw TrackingError("NO_ACTIVITY", "No active app window") }
            while let presented = presenter.presentedViewController { presenter = presented }
            let controller = MFMailComposeViewController()
            controller.mailComposeDelegate = self
            controller.setToRecipients([email])
            controller.setSubject(options.text("subject", "Location tracking log"))
            controller.addAttachmentData(try Self.gzip(Data(text.utf8)), mimeType: "application/gzip", fileName: "location-tracking.log.gz")
            mailer = controller
            presenter.present(controller, animated: true) { completion(.success([:])) }
        } catch { completion(.failure(error)) }
    }
    func mailComposeController(_ controller: MFMailComposeViewController, didFinishWith result: MFMailComposeResult, error: Error?) {
        controller.dismiss(animated: true)
        mailer = nil
    }
}
