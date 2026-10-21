package com.brickssoft.locationtracking.logging

/**
 * Removes credentials and addresses from text the app passes to the JS `log()` method (recommendation 16), before it
 * reaches logcat and the log files, which can be emailed or uploaded:
 * - values of credential fields (`accessToken`, `refresh_token`, `password`, `secret`, `apiKey`, `authorization`, …)
 *   written as `key: value`, `key=value` or JSON `"key": "value"`, in any letter case;
 * - `Bearer <token>`;
 * - JSON Web Tokens (`eyJ….….…`);
 * - URLs (`http(s)://…`, `ws(s)://…`), which can carry tokens in their query or user info.
 */
internal object LogRedaction {
    const val REDACTED = "[redacted]"

    private val SECRET_FIELD = Regex(
        "(?i)(\"?\\b(?:access[_-]?token|refresh[_-]?token|id[_-]?token|token|password|passwd|secret|client[_-]?secret|" +
            "api[_-]?key|authorization)\\b\"?\\s*[:=]\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|[^\\s,;&}]+)",
    )
    private val BEARER = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val JWT = Regex("\\beyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*")
    private val URL = Regex("(?i)\\b(?:https?|wss?)://[^\\s\"'<>]+")

    fun redact(text: String): String = text
        .replace(SECRET_FIELD) { match ->
            val value = match.groupValues[2]
            match.groupValues[1] + if (value.startsWith("\"")) "\"$REDACTED\"" else REDACTED
        }
        .replace(BEARER, "Bearer $REDACTED")
        .replace(JWT, "[jwt]")
        .replace(URL, "[url]")
}
