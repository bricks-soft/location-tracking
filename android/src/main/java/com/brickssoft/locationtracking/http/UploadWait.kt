package com.brickssoft.locationtracking.http

import android.content.Context
import android.content.SharedPreferences
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import okhttp3.Headers.Companion.headersOf
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The stored "next try" time of automatic uploads (recommendations 4 and 11): after a failed automatic upload, normal
 * records are not sent again before [atWall] / [atElapsed]. Priority records (heartbeat and audit records) and
 * `sync()` do not wait.
 *
 * @property reason why uploads wait.
 * @property failures consecutive failed automatic uploads with reason [Reason.SERVER]; the wait doubles with each.
 * @property atElapsed elapsed-realtime ms of the next try, valid while the boot count is [bootCount].
 * @property atWall epoch ms of the next try, used after a reboot (elapsed realtime restarts at 0).
 * @property authKey for [Reason.AUTHORIZATION]: a hash of the access and refresh tokens that were rejected. The wait
 *   ends as soon as the configured tokens differ (the app set new ones).
 */
data class NextTry(
    val reason: Reason,
    val failures: Int,
    val atElapsed: Long,
    val bootCount: Int,
    val atWall: Long,
    val authKey: String? = null,
) {
    enum class Reason(val wire: String) {
        /** The server failed or rejected the upload, or did not answer. */
        SERVER("server"),

        /** HTTP 401 and the token could not be refreshed, or the stored tokens cannot be read. */
        AUTHORIZATION("authorization"),
        ;

        companion object {
            fun fromWire(value: String?): Reason? = entries.firstOrNull { it.wire == value }
        }
    }

    /** Milliseconds until the next try at the times of [clock]; 0 or less when it is due. */
    fun remainingMs(clock: Clock): Long {
        val boot = clock.bootCount()
        return if (bootCount >= 0 && boot == bootCount) atElapsed - clock.elapsedRealtime() else atWall - clock.now()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("reason", reason.wire)
        .put("failures", failures)
        .put("atElapsed", atElapsed)
        .put("bootCount", bootCount)
        .put("atWall", atWall)
        .put("authKey", authKey ?: JSONObject.NULL)

    companion object {
        /** Null if [json] is not a stored [NextTry]. */
        fun fromJson(json: JSONObject): NextTry? {
            val reason = Reason.fromWire(json.optString("reason")) ?: return null
            return NextTry(
                reason = reason,
                failures = json.optInt("failures", 1),
                atElapsed = json.optLong("atElapsed"),
                bootCount = json.optInt("bootCount", -1),
                atWall = json.optLong("atWall"),
                authKey = if (json.isNull("authKey")) null else json.optString("authKey"),
            )
        }
    }
}

/** Persists the [NextTry] across process restarts. */
interface NextTryStore {
    fun load(): NextTry?

    fun save(value: NextTry?)

    companion object {
        /** Keeps the value in memory only (tests, and callers without a Context). */
        fun inMemory(): NextTryStore = object : NextTryStore {
            @Volatile
            private var value: NextTry? = null

            override fun load(): NextTry? = value

            override fun save(value: NextTry?) {
                this.value = value
            }
        }
    }
}

/** [NextTryStore] in the plugin's SharedPreferences file, key [KEY]. */
class PrefsNextTryStore(context: Context) : NextTryStore {
    private val prefs: SharedPreferences by lazy {
        (context.applicationContext ?: context).getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun load(): NextTry? = try {
        prefs.getString(KEY, null)?.let { NextTry.fromJson(JSONObject(it)) }
    } catch (e: Exception) {
        Logger.w(TAG, "stored upload wait is unreadable; ignoring it", e)
        null
    }

    override fun save(value: NextTry?) {
        try {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY) else editor.putString(KEY, value.toJson().toString())
            editor.apply()
        } catch (e: Exception) {
            Logger.w(TAG, "cannot store the upload wait", e)
        }
    }

    private companion object {
        const val TAG = "LT.Http"
        const val KEY = "http_next_try"
    }
}

/**
 * Wait rules for failed uploads.
 *
 * - Inside one upload (owner decision R2-Q18): the retries after 2, 4 and 8 s, each moved by a random amount of up to
 *   20 % up or down ([retryDelayMs]), so many phones that failed at the same moment do not retry at the same moment.
 * - Between automatic uploads ([serverWaitMs]): 30 s after the first failure, doubling with each further failure up to
 *   15 minutes, each moved by up to 20 % up or down; never shorter than a `Retry-After` of a 429 or 503 answer (limited
 *   to `http.maxRetryAfter`) and, while `http.syncInterval` applies, never shorter than `syncInterval`.
 * - After an authorization failure: [AUTH_WAIT_MS] (15 minutes), or until the app sets new tokens.
 */
object UploadBackoff {
    /** Waits before the retries of one upload, before jitter. */
    val RETRY_DELAYS_MS = longArrayOf(2_000L, 4_000L, 8_000L)

    const val FIRST_WAIT_MS = 30_000L
    const val MAX_WAIT_MS = 15 * 60_000L
    const val AUTH_WAIT_MS = 15 * 60_000L

    /** Largest relative change of a wait by jitter (20 %). */
    const val JITTER = 0.2

    /** [base] moved by up to [JITTER] up or down; [random] is in [0, 1). */
    fun jittered(base: Long, random: Double): Long = (base * (1.0 - JITTER + 2 * JITTER * random)).toLong()

    /** The wait before retry number [index] (0-based) of one upload. */
    fun retryDelayMs(index: Int, random: Double): Long = jittered(RETRY_DELAYS_MS[index], random)

    /**
     * The wait after the [failures]-th consecutive failed automatic upload (1 = first failure), at least
     * [retryAfterMs] (already limited to `http.maxRetryAfter`) and [minimumMs].
     */
    fun serverWaitMs(failures: Int, random: Double, retryAfterMs: Long?, minimumMs: Long): Long {
        val doublings = (failures - 1).coerceIn(0, 30)
        val base = minOf(FIRST_WAIT_MS shl minOf(doublings, 20), MAX_WAIT_MS)
        return maxOf(jittered(base, random), retryAfterMs ?: 0L, minimumMs)
    }

    /**
     * The `Retry-After` value in ms (delta-seconds or an HTTP date relative to [now]), at most [maxSeconds] seconds;
     * null if absent or unreadable. A date in the past gives 0.
     */
    fun retryAfterMs(value: String?, now: Long, maxSeconds: Int): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val ms = text.toLongOrNull()?.let { if (it < 0) null else it * 1_000L }
            ?: try {
                headersOf(HttpSupport.RETRY_AFTER, text).getDate(HttpSupport.RETRY_AFTER)?.time?.let { maxOf(0L, it - now) }
            } catch (e: IllegalArgumentException) {
                null
            }
            ?: return null
        return minOf(ms, maxSeconds.coerceAtLeast(0) * 1_000L)
    }

    /** A short, non-reversible key of a token pair, so the stored wait never holds a token. */
    fun authKey(accessToken: String?, refreshToken: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${accessToken.orEmpty()}\u0000${refreshToken.orEmpty()}".toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
