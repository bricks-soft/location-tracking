import Foundation

struct StrictJSON {
    private let bytes: [UInt8]
    private var index = 0
    init(_ text: String) { bytes = Array(text.utf8) }
    static func isValid(_ text: String) -> Bool {
        var parser = StrictJSON(text)
        parser.whitespace()
        guard parser.value(0) else { return false }
        parser.whitespace()
        return parser.index == parser.bytes.count
    }
    private mutating func whitespace() { while index < bytes.count && [9, 10, 13, 32].contains(bytes[index]) { index += 1 } }
    private mutating func consume(_ byte: UInt8) -> Bool {
        guard index < bytes.count && bytes[index] == byte else { return false }
        index += 1; return true
    }
    private mutating func value(_ depth: Int) -> Bool {
        guard depth <= 256 && index < bytes.count else { return false }
        switch bytes[index] {
        case 123: return object(depth + 1)
        case 91: return array(depth + 1)
        case 34: return string()
        case 116: return literal("true")
        case 102: return literal("false")
        case 110: return literal("null")
        default: return number()
        }
    }
    private mutating func object(_ depth: Int) -> Bool {
        index += 1; whitespace()
        if consume(125) { return true }
        while true {
            whitespace()
            guard index < bytes.count && bytes[index] == 34 && string() else { return false }
            whitespace()
            guard consume(58) else { return false }
            whitespace()
            guard value(depth) else { return false }
            whitespace()
            if consume(125) { return true }
            guard consume(44) else { return false }
        }
    }
    private mutating func array(_ depth: Int) -> Bool {
        index += 1; whitespace()
        if consume(93) { return true }
        while true {
            whitespace()
            guard value(depth) else { return false }
            whitespace()
            if consume(93) { return true }
            guard consume(44) else { return false }
        }
    }
    private mutating func string() -> Bool {
        index += 1
        while index < bytes.count {
            let byte = bytes[index]; index += 1
            if byte == 34 { return true }
            if byte < 32 { return false }
            if byte == 92 {
                guard index < bytes.count else { return false }
                let escape = bytes[index]; index += 1
                if escape == 117 {
                    guard index + 4 <= bytes.count else { return false }
                    guard bytes[index..<index + 4].allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }) else { return false }
                    index += 4
                } else if ![34, 92, 47, 98, 102, 110, 114, 116].contains(escape) { return false }
            }
        }
        return false
    }
    private mutating func literal(_ text: String) -> Bool {
        let expected = Array(text.utf8)
        guard index + expected.count <= bytes.count && Array(bytes[index..<index + expected.count]) == expected else { return false }
        index += expected.count; return true
    }
    private mutating func number() -> Bool {
        _ = consume(45)
        guard index < bytes.count else { return false }
        if bytes[index] == 48 { index += 1 }
        else if (49...57).contains(bytes[index]) { _ = digits() }
        else { return false }
        if consume(46) && !digits() { return false }
        if consume(101) || consume(69) {
            if !consume(43) { _ = consume(45) }
            if !digits() { return false }
        }
        return true
    }
    private mutating func digits() -> Bool {
        let start = index
        while index < bytes.count && (48...57).contains(bytes[index]) { index += 1 }
        return index > start
    }
}
