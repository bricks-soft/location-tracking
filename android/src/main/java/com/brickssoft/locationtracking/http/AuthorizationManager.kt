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
 *   `expires|expires_at` (epoch; seconds if < 1e12) or `expires_in` (relative seconds), persists the new tokens with
 *   `configStore.update` and emits an `Authorization` event (also on failure).
 * - Refreshes are single-flight: callers that waited while another caller replaced their stale token reuse it.
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

    /** The access token to send ([value] null = no Authorization header) and whether a refresh was attempted. */
    data class Token(val value: String?, val refreshAttempted: Boolean)

    /** Tokens read from a refresh response; [expires] is epoch ms or -1 if unknown. */
    data class Tokens(val accessToken: String, val refreshToken: String?, val expires: Long)

    /** True if requests configured by [http] carry the bearer token (authorization set, no explicit header). */
    fun appliesTo(http: HttpConfig): Boolean =
        http.authorization != null && !HttpSupport.hasHeader(http.headers, HttpSupport.AUTHORIZATION)

    /** The token for the next request, refreshing first if it is missing or about to expire (and refreshable). */
    suspend fun tokenForRequest(client: OkHttpClient): Token {
        val auth = configStore.config.value.http.authorization ?: return Token(null, false)
        val current = auth.accessToken.nonBlank()
        if (!needsRefresh(auth) || !canRefresh(auth)) return Token(current, false)
        return Token(refresh(client, current) ?: current, true)
    }

    /**
     * Refreshes the token (single-flight) unless another caller already replaced [staleToken] with a token that
     * does not need a refresh. Returns the token to use, or null if there is none (refresh failed or impossible).
     */
    suspend fun refresh(client: OkHttpClient, staleToken: String?): String? = mutex.withLock {
        val auth = configStore.config.value.http.authorization ?: return@withLock null
        val current = auth.accessToken.nonBlank()
        if (current != null && current != staleToken && !needsRefresh(auth)) return@withLock current
        if (!canRefresh(auth)) return@withLock null
        performRefresh(client, auth)
    }

    private fun canRefresh(auth: AuthorizationConfig): Boolean = !auth.refreshUrl.isNullOrBlank()

    private fun needsRefresh(auth: AuthorizationConfig): Boolean {
        val token = auth.accessToken
        if (token.isNullOrBlank()) return true
        if (auth.expires <= 0) return false
        val now = clock.now()
        return now >= auth.expires || (now >= auth.expires - EXPIRY_MARGIN_MS && token != shortLivedToken)
    }

    private suspend fun performRefresh(client: OkHttpClient, auth: AuthorizationConfig): String? {
        val url = HttpSupport.parseUrl(auth.refreshUrl)
        if (url == null) {
            Logger.e(TAG, "authorization.refreshUrl is not a valid http(s) URL")
            emit(false, 0, "authorization.refreshUrl is not a valid http(s) URL", null)
            return null
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
            return null
        }
        val json = JsonUtil.parseObject(response.body)
        if (!response.isSuccessful) {
            Logger.w(TAG, "token refresh failed: HTTP ${response.status}")
            emit(false, response.status, "HTTP ${response.status}", json?.toString())
            return null
        }
        val tokens = parseTokens(json, clock.now())
        if (tokens == null) {
            Logger.w(TAG, "token refresh response (HTTP ${response.status}) has no accessToken")
            emit(false, response.status, "refresh response has no accessToken", json?.toString())
            return null
        }
        configStore.update { config ->
            // Do not resurrect an authorization config removed while the refresh was in flight.
            val current = config.http.authorization ?: return@update config
            val updated = current.copy(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken ?: current.refreshToken,
                expires = tokens.expires,
            )
            config.copy(http = config.http.copy(authorization = updated))
        }
        val shortLived = tokens.expires > 0 && tokens.expires - clock.now() <= EXPIRY_MARGIN_MS
        shortLivedToken = if (shortLived) tokens.accessToken else null
        if (shortLived) Logger.w(TAG, "refreshed access token expires within ${EXPIRY_MARGIN_MS / 1000} s")
        Logger.i(TAG, "access token refreshed (HTTP ${response.status})")
        emit(true, response.status, null, json?.toString())
        return tokens.accessToken
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
