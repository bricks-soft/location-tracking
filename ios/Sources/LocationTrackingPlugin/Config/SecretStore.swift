import Foundation
import Security

final class SecretStore {
    private let service: String
    init(service: String = "com.brickssoft.locationtracking.authorization") { self.service = service }
    private var query: TrackingJSON {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "http"]
    }
    func load() throws -> TrackingJSON? {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw TrackingError("IO_ERROR", "Keychain authorization is unavailable (status \(status))") }
        return try jsonObject(data)
    }
    func save(_ value: TrackingJSON?) throws {
        if let value {
            let data = try jsonData(value)
            let attributes: TrackingJSON = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
            let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
            if status == errSecItemNotFound {
                var request = query
                attributes.forEach { request[$0.key] = $0.value }
                guard SecItemAdd(request as CFDictionary, nil) == errSecSuccess else { throw TrackingError("IO_ERROR", "Cannot save authorization in Keychain") }
            } else if status != errSecSuccess { throw TrackingError("IO_ERROR", "Cannot update authorization in Keychain") }
        } else {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw TrackingError("IO_ERROR", "Cannot clear authorization in Keychain") }
        }
    }
}
