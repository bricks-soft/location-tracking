package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.AuthorizationConfig
import com.brickssoft.locationtracking.config.ConfigStore
import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.config.RefreshPayloadEncoding
import com.brickssoft.locationtracking.core.AppDispatchers
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.EventBus
import com.brickssoft.locationtracking.core.Iso8601
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingEvent
import com.brickssoft.locationtracking.model.JsonUtil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException

/**
 * JWT access-token handling for `http.authorization` (architecture §2).
 *
 * - The token is refreshed before a request when it is missing or `expires > 0 && now >= expires - 60 s`, and after
 *   a 401 (at most once per upload; the caller enforces that).
 * - A refresh POSTs `refreshPayload` (string values with `{refreshToken}` substituted, JSON or form encoded) with
 *   `refreshHeaders` to `refreshUrl`, reads `accessToken|access_token`, `refreshToken|refresh_token` and
 *   `expires|expires_at` (epoch; seconds if < 1e12) or `expires_in` (relative seconds), and emits an `Authorization`
 *   event (also on failure). The refresh answer is read in full: the new tokens are parsed from it.
 * - **One identity per upload** (recommendation 2). An upload reads the [Credentials] once, at its start, and every
 *   try of that upload uses only them or credentials refreshed from them. When the configured tokens differ from them
 *   (the app called `setConfig` with another user's token, or removed the authorization), [renew] and [prepare]
 *   answer [Outcome.CHANGED] and the upload stops, keeping its records.
 * - **Compare-and-set save.** A refresh saves its new tokens only if the configured access and refresh tokens are still
 *   the ones it started from; otherwise the new tokens are dropped and the answer is [Outcome.CHANGED]. A refresh that
 *   finishes after the app configured another user can therefore never overwrite that user's tokens.
 * - Refreshes are single-flight (one at a time).
 *
 * Token values are never logged.
 */
internal class AuthorizationManager(
    private val configStore: ConfigStore,
    private val events: EventBus,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
) {
    private val mutex = Mutex()

    /**
     * A refreshed token whose whole lifetime already fell inside the refresh margin. It is not refreshed again before
     * it really expires; otherwise every upload would first refresh it.
     */
    @Volatile
    private var shortLivedToken: String? = null

    /** The access and refresh tokens one upload is sent with (blank values are null). */
    data class Credentials(val accessToken: String?, val refreshToken: String?) {
        override fun toString(): String = "Credentials(redacted)"
    }

    /** What [prepare] or [renew] found. */
    enum class Outcome {
        /** The credentials are usable as they are (no refresh was needed, or [prepare] may not refresh). */
        UNCHANGED,

        /** The token was refreshed; use the returned credentials. */
        RENEWED,

        /** A refresh was needed but failed or is not possible (no `refreshUrl`). */
        FAILED,

        /** The configured tokens are no longer the upload's: stop the upload and keep its records. */
        CHANGED,
    }

    /** [credentials] to send with and what happened; [refreshAttempted] is true if a refresh request was made. */
    data class Result(val outcome: Outcome, val credentials: Credentials, val refreshAttempted: Boolean)

    /** Tokens read from a refresh response; [expires] is epoch ms or -1 if unknown. */
    data class Tokens(val accessToken: String, val refreshToken: String?, val expires: Long)

    /** True if requests configured by [http] carry the bearer token (authorization set, no explicit header). */
    fun appliesTo(http: HttpConfig): Boolean =
        http.authorization != null && !HttpSupport.hasHeader(http.headers, HttpSupport.AUTHORIZATION)

    /** The configured credentials, or null without `http.authorization`. */
    fun current(): Credentials? = configStore.config.value.http.authorization?.let(::credentialsOf)

    /** True while the configured credentials are still [credentials]. */
    fun isCurrent(credentials: Credentials): Boolean = current() == credentials

    /**
     * Before a request with [credentials]: refreshes first if the token is missing or about to expire and a refresh is
     * possible, unless [mayRefresh] is false. Never answers [Outcome.FAILED] for a token that is still usable; a failed
     * refresh of an expiring token keeps sending the old one ([Outcome.FAILED] with the old credentials).
     */
    suspend fun prepare(client: OkHttpClient, credentials: Credentials, mayRefresh: Boolean): Result {
        val auth = configStore.config.value.http.authorization
        if (auth == null || credentialsOf(auth) != credentials) return Result(Outcome.CHANGED, credentials, false)
        if (!mayRefresh || !needsRefresh(auth) || !canRefresh(auth)) return Result(Outcome.UNCHANGED, credentials, false)
        return renew(client, credentials)
    }

    /**
     * Refreshes [from] (single-flight). [Outcome.CHANGED] if the configured tokens are no longer [from] (before or after
     * the refresh request); [Outcome.FAILED] if the refresh failed or is not possible.
     */
    suspend fun renew(client: OkHttpClient, from: Credentials): Result = mutex.withLock {
        val auth = configStore.config.value.http.authorization
        if (auth == null || credentialsOf(auth) != from) return@withLock Result(Outcome.CHANGED, from, false)
        if (!canRefresh(auth)) return@withLock Result(Outcome.FAILED, from, false)
        performRefresh(client, auth, from)
    }

    private fun credentialsOf(auth: AuthorizationConfig) =
        Credentials(auth.accessToken.nonBlank(), auth.refreshToken.nonBlank())

    private fun canRefresh(auth: AuthorizationConfig): Boolean = !auth.refreshUrl.isNullOrBlank()

    private fun needsRefresh(auth: AuthorizationConfig): Boolean {
        val token = auth.accessToken
        if (token.isNullOrBlank()) return true
        if (auth.expires <= 0) return false
        val now = clock.now()
        return now >= auth.expires || (now >= auth.expires - EXPIRY_MARGIN_MS && token != shortLivedToken)
    }

    private suspend fun performRefresh(client: OkHttpClient, auth: AuthorizationConfig, from: Credentials): Result {
        val failed = Result(Outcome.FAILED, from, refreshAttempted = true)
        val url = HttpSupport.parseUrl(auth.refreshUrl)
        if (url == null) {
            Logger.e(TAG, "authorization.refreshUrl is not a valid http(s) URL")
            emit(false, 0, "authorization.refreshUrl is not a valid http(s) URL", null)
            return failed.copy(refreshAttempted = false)
        }
        val (contentType, body) = refreshBody(auth)
        val headers = HttpSupport.headers(contentType, auth.refreshHeaders, bearerToken = null)
        val request = HttpSupport.request(url, "POST", headers, body)
        val response = try {
            HttpSupport.execute(client, request, dispatchers.io)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            Logger.w(TAG, "token refresh failed: ${e.message}")
            emit(false, 0, e.message ?: e.javaClass.simpleName, null)
            return failed
        }
        val json = JsonUtil.parseObject(response.body)
        if (!response.isSuccessful) {
            Logger.w(TAG, "token refresh failed: HTTP ${response.status}")
            emit(false, response.status, "HTTP ${response.status}", json?.toString())
            return failed
        }
        val tokens = parseTokens(json, clock.now())
        if (tokens == null) {
            Logger.w(TAG, "token refresh response (HTTP ${response.status}) has no accessToken")
            emit(false, response.status, "refresh response has no accessToken", json?.toString())
            return failed
        }
        var saved: Credentials? = null
        configStore.update { config ->
            // Only onto the tokens the refresh started from: the app may have removed the authorization or configured
            // another user while the refresh was in flight.
            val current = config.http.authorization ?: return@update config
            if (credentialsOf(current) != from) return@update config
            val updated = current.copy(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken ?: current.refreshToken,
                expires = tokens.expires,
            )
            saved = credentialsOf(updated)
            config.copy(http = config.http.copy(authorization = updated))
        }
        val renewed = saved
        if (renewed == null) {
            Logger.w(TAG, "the authorization changed while its token was refreshed; the new tokens are not saved")
            return Result(Outcome.CHANGED, from, refreshAttempted = true)
        }
        val shortLived = tokens.expires > 0 && tokens.expires - clock.now() <= EXPIRY_MARGIN_MS
        shortLivedToken = if (shortLived) tokens.accessToken else null
        if (shortLived) Logger.w(TAG, "refreshed access token expires within ${EXPIRY_MARGIN_MS / 1000} s")
        Logger.i(TAG, "access token refreshed (HTTP ${response.status})")
        emit(true, response.status, null, json?.toString())
        return Result(Outcome.RENEWED, renewed, refreshAttempted = true)
    }

    /** Emits an `Authorization` event with [error] and no request (for example the stored tokens cannot be read). */
    fun reportUnusable(error: String) {
        emit(false, 0, error, null)
    }

    private fun emit(success: Boolean, status: Int, error: String?, responseJson: String?) {
        events.emit(TrackingEvent.Authorization(success, status, error, responseJson))
    }

    companion object {
        private const val TAG = "LT.Auth"

        /** Refresh this long before `expires`. */
        const val EXPIRY_MARGIN_MS = 60_000L

        private const val REFRESH_TOKEN_PLACEHOLDER = "{refreshToken}"

        /** Epoch values below this are seconds, not milliseconds. */
        private const val EPOCH_SECONDS_LIMIT = 1e12

        /** Content type and body text of the refresh request. */
        fun refreshBody(auth: AuthorizationConfig): Pair<String, String> {
            val payload = JsonUtil.parseObject(auth.refreshPayload) ?: JSONObject().also {
                if (auth.refreshPayload.isNotBlank()) Logger.w(TAG, "authorization.refreshPayload is not a JSON object")
            }
            val refreshToken = auth.refreshToken.orEmpty()
            val substituted = JSONObject()
            val keys = payload.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = payload.get(key)
                val resolved = if (value is String) value.replace(REFRESH_TOKEN_PLACEHOLDER, refreshToken) else value
                substituted.put(key, resolved)
            }
            return when (auth.refreshPayloadEncoding) {
                RefreshPayloadEncoding.JSON -> HttpSupport.JSON_CONTENT_TYPE to substituted.toString()
                RefreshPayloadEncoding.FORM -> HttpSupport.FORM_CONTENT_TYPE to formEncode(substituted)
            }
        }

        /** Reads the tokens of a refresh response; null if it has no access token. */
        fun parseTokens(json: JSONObject?, now: Long): Tokens? {
            if (json == null) return null
            val access = firstString(json, "accessToken", "access_token") ?: return null
            val expires = epochMs(json, "expires")
                ?: epochMs(json, "expires_at")
                ?: JsonUtil.optDouble(json, "expires_in")?.takeIf { it > 0 }?.let { now + (it * 1000).toLong() }
                ?: -1L
            return Tokens(access, firstString(json, "refreshToken", "refresh_token"), expires)
        }

        private fun firstString(json: JSONObject, vararg keys: String): String? =
            keys.firstNotNullOfOrNull { JsonUtil.optString(json, it).nonBlank() }

        /** An absolute expiry: epoch seconds (< 1e12), epoch ms, or an ISO-8601 string; null if absent/invalid. */
        private fun epochMs(json: JSONObject, key: String): Long? {
            val number = JsonUtil.optDouble(json, key)
                ?: return JsonUtil.optString(json, key)?.let { Iso8601.parse(it) }
            if (number <= 0) return null
            return if (number < EPOCH_SECONDS_LIMIT) (number * 1000).toLong() else number.toLong()
        }

        /** `application/x-www-form-urlencoded` text of [json]'s non-null values. */
        private fun formEncode(json: JSONObject): String {
            val form = FormBody.Builder()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!json.isNull(key)) form.add(key, json.get(key).toString())
            }
            val body = form.build()
            return (0 until body.size).joinToString("&") { "${body.encodedName(it)}=${body.encodedValue(it)}" }
        }

        private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }
    }
}
