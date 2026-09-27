package com.brickssoft.locationtracking.http

/**
 * Strict RFC 8259 validator. org.json's parser is lenient (it accepts unquoted strings, single quotes, `=`
 * separators, ...), so rendered templates are checked here before they are parsed.
 */
internal object StrictJson {
    private const val MAX_DEPTH = 256

    /** True if [text] is exactly one JSON value, optionally surrounded by whitespace. */
    fun isValid(text: String): Boolean {
        val parser = Parser(text)
        parser.skipWhitespace()
        if (!parser.value(0)) return false
        parser.skipWhitespace()
        return parser.atEnd
    }

    private class Parser(private val s: String) {
        private var i = 0

        val atEnd: Boolean get() = i == s.length

        fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(depth: Int): Boolean {
            if (depth > MAX_DEPTH || i >= s.length) return false
            return when (s[i]) {
                '{' -> obj(depth + 1)
                '[' -> array(depth + 1)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }

        private fun obj(depth: Int): Boolean {
            i++ // '{'
            skipWhitespace()
            if (consume('}')) return true
            while (true) {
                skipWhitespace()
                if (!peek('"') || !string()) return false
                skipWhitespace()
                if (!consume(':')) return false
                skipWhitespace()
                if (!value(depth)) return false
                skipWhitespace()
                if (consume('}')) return true
                if (!consume(',')) return false
            }
        }

        private fun array(depth: Int): Boolean {
            i++ // '['
            skipWhitespace()
            if (consume(']')) return true
            while (true) {
                skipWhitespace()
                if (!value(depth)) return false
                skipWhitespace()
                if (consume(']')) return true
                if (!consume(',')) return false
            }
        }

        private fun string(): Boolean {
            i++ // opening quote
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return true
                    c == '\\' -> if (!escape()) return false
                    c < ' ' -> return false
                }
            }
            return false
        }

        private fun escape(): Boolean {
            if (i >= s.length) return false
            return when (s[i++]) {
                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> true
                'u' -> {
                    if (i + 4 > s.length) return false
                    val hex = s.substring(i, i + 4)
                    i += 4
                    hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                }
                else -> false
            }
        }

        private fun literal(word: String): Boolean {
            if (!s.startsWith(word, i)) return false
            i += word.length
            return true
        }

        private fun number(): Boolean {
            consume('-')
            if (i >= s.length) return false
            when (s[i]) {
                '0' -> i++
                in '1'..'9' -> digits()
                else -> return false
            }
            if (consume('.') && !digits()) return false
            if (consume('e') || consume('E')) {
                if (!consume('+')) consume('-')
                if (!digits()) return false
            }
            return true
        }

        /** Consumes one or more ASCII digits; false if there is none. */
        private fun digits(): Boolean {
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            return i > start
        }

        private fun peek(c: Char): Boolean = i < s.length && s[i] == c

        private fun consume(c: Char): Boolean {
            if (!peek(c)) return false
            i++
            return true
        }
    }
}
