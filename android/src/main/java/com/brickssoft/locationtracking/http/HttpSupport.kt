package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.core.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Status and body text of a completed HTTP exchange. [retryAfter] is the raw `Retry-After` header value, if the server
 * sent one.
 */
internal data class HttpResponse(val status: Int, val body: String, val retryAfter: String? = null) {
    val isSuccessful: Boolean get() = status in 200..299
}

/** Request plumbing shared by [OkHttpSyncer] and [AuthorizationManager]. */
internal object HttpSupport {
    private const val TAG = "LT.Http"

    const val CONTENT_TYPE = "Content-Type"
    const val AUTHORIZATION = "Authorization"
    const val RETRY_AFTER = "Retry-After"
    const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
    const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"

    /** Most bytes of an upload answer that are read and passed on in the `http` event (16 KB). */
    const val MAX_UPLOAD_RESPONSE_BYTES = 16_384L

    /**
     * The plugin's shared client (uploads, token refresh, `uploadLog`). It follows no redirects: OkHttp would send a
     * redirected POST again as a GET without a body, and a 2xx answer to that GET would delete records the server never
     * received. A 3xx is therefore an answer like any other non-2xx one. It also does not repeat a request on its own
     * after a connection failure; the uploader decides about retries.
     */
    fun sharedClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    /** Parses an http(s) URL (surrounding whitespace ignored); null if blank or invalid. */
    fun parseUrl(url: String?): HttpUrl? = url?.trim()?.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull()

    /**
     * A client derived from [base] (sharing its pool) whose call, read and write timeouts are [timeoutMs] (unless
     * [timeoutMs] <= 0). Whatever [base] is configured with, the derived client follows no redirects and does not
     * repeat a request after a connection failure (see [sharedClient]).
     */
    fun client(base: OkHttpClient, timeoutMs: Long): OkHttpClient {
        val builder = base.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
        if (timeoutMs > 0) {
            builder
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        }
        return builder.build()
    }

    /** True if [headers] contains [name], ignoring case. */
    fun hasHeader(headers: Map<String, String>, name: String): Boolean =
        headers.keys.any { it.equals(name, ignoreCase = true) }

    /**
     * Request headers in wire order: `Content-Type: [contentType]`, then [headers] (a header with the same name,
     * ignoring case, replaces the earlier value in place), then `Authorization: Bearer [bearerToken]` if a token is
     * given and [headers] has no Authorization header.
     */
    fun headers(contentType: String, headers: Map<String, String>, bearerToken: String?): List<Pair<String, String>> {
        val byName = LinkedHashMap<String, Pair<String, String>>()
        byName[CONTENT_TYPE.lowercase(Locale.ROOT)] = CONTENT_TYPE to contentType
        for ((name, value) in headers) byName[name.lowercase(Locale.ROOT)] = name to value
        val authKey = AUTHORIZATION.lowercase(Locale.ROOT)
        if (bearerToken != null && authKey !in byName) byName[authKey] = AUTHORIZATION to "Bearer $bearerToken"
        return byName.values.toList()
    }

    /**
     * Builds a request with exactly [headers] in order (invalid names or values are skipped with a warning).
     * The body carries no media type, so OkHttp keeps the explicit Content-Type header and its position.
     */
    fun request(url: HttpUrl, method: String, headers: List<Pair<String, String>>, body: String): Request {
        val builder = Headers.Builder()
        for ((name, value) in headers) {
            try {
                builder.add(name, value)
            } catch (e: IllegalArgumentException) {
                // Never log the value: it may be a credential.
                Logger.w(TAG, "skipping invalid header '$name'")
            }
        }
        return Request.Builder()
            .url(url)
            .headers(builder.build())
            .method(method, body.toByteArray(Charsets.UTF_8).toRequestBody(null))
            .build()
    }

    /**
     * Executes [request] on [io]. Cancelling the coroutine cancels the call (OkHttp does not support thread
     * interrupts). A body that fails to read after the status arrived yields "", because the server already answered.
     * With [maxBodyBytes], at most that many bytes of the body are read (`peekBody`); the rest is discarded.
     *
     * @throws IOException if the exchange fails before a status is received.
     */
    suspend fun execute(
        client: OkHttpClient,
        request: Request,
        io: CoroutineDispatcher,
        maxBodyBytes: Long? = null,
    ): HttpResponse =
        withContext(io) {
            val call = client.newCall(request)
            val finished = AtomicBoolean(false)
            val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) call.cancel()
                }
            }
            try {
                call.execute().use { response ->
                    val text = try {
                        if (maxBodyBytes != null) {
                            response.peekBody(maxBodyBytes).string()
                        } else {
                            response.body?.string().orEmpty()
                        }
                    } catch (e: IOException) {
                        Logger.w(TAG, "could not read the response body of HTTP ${response.code}: ${e.message}")
                        ""
                    }
                    HttpResponse(response.code, text, response.header(RETRY_AFTER))
                }
            } finally {
                finished.set(true)
                canceller.cancel()
            }
        }
}
