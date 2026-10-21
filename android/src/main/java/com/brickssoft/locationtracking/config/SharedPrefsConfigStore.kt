package com.brickssoft.locationtracking.config

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.brickssoft.locationtracking.core.Clock
import com.brickssoft.locationtracking.core.Constants
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.http.ConfigChecks
import com.brickssoft.locationtracking.service.BootCountStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * [ConfigStore] backed by the SharedPreferences file [Constants.PREFS_NAME].
 *
 * Config and runtime state are loaded synchronously in the constructor. An unreadable stored value falls back to
 * its default; unreadable JSON text falls back to all defaults.
 *
 * Every write runs under one lock: it computes the new value, persists it and publishes it on the matching
 * [StateFlow] before returning. Writes use `apply()`, except a change of `runtime.enabled`, which uses `commit()`, so a
 * process killed a few milliseconds after `stop()` cannot bring tracking back (recommendation 16). Config writes go
 * through [ConfigJson.parse] and [ConfigValidator]. Only the `cfg_` keys of the shared prefs file are touched.
 *
 * **Tokens** (recommendation 8). `accessToken`, `refreshToken`, `refreshPayload` and `refreshHeaders` of
 * `http.authorization` are never written to the prefs file: they go to [secrets] (AES-GCM with an Android Keystore key,
 * under `noBackupFilesDir`), and the prefs file keeps the rest of the config. Plain-text tokens stored by an older
 * version are moved there on the first load. If the stored tokens cannot be decrypted (the key is gone, the file is
 * damaged, or the Keystore throws), they read as unset and [secretsUnavailable] is true: the uploader then sends
 * nothing that needs them and emits an `authorization` event with an error, until the app sets new tokens. If they
 * cannot be encrypted, they are kept in memory only (logged as an error).
 *
 * **Restored onto another installation** (recommendation 7). The app's `firstInstallTime` is stored; when it differs
 * from the stored one, the prefs file came from another installation (Auto Backup restore or device-to-device
 * transfer). Tracking is then never turned on by itself: `enabled` is cleared, the tokens are removed and the boot
 * counts are cleared.
 */
class SharedPrefsConfigStore(
    context: Context,
    @Suppress("unused") private val clock: Clock,
    private val secrets: SecretStore = FileSecretStore.forContext(context),
) : ConfigStore {
    private val appContext: Context = context.applicationContext ?: context
    private val prefs: SharedPreferences = appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    /** The secrets [secrets] holds now (null: none). Guarded by [lock] after construction. */
    private var storedSecrets: AuthorizationSecrets? = null

    @Volatile
    private var secretsLost = false

    /** The plain-text tokens of an older version were moved to [secrets]: the prefs file must be rewritten. */
    private var migratedSecrets = false

    private val restored: Boolean = detectRestore()

    private val configFlow = MutableStateFlow(loadConfig())
    private val runtimeFlow = MutableStateFlow(loadRuntime())

    override val config: StateFlow<Config> = configFlow.asStateFlow()
    override val runtime: StateFlow<RuntimeState> = runtimeFlow.asStateFlow()

    override val secretsUnavailable: Boolean get() = secretsLost

    init {
        if (restored || migratedSecrets) {
            persist(configFlow.value, runtimeFlow.value.takeIf { restored }, sync = true)
        }
    }

    override fun ready(json: JSONObject?, reset: Boolean): Config = synchronized(lock) {
        val current = configFlow.value
        val next = when {
            reset -> build(json, Config())
            !runtimeFlow.value.didReady -> build(json, current)
            else -> {
                if (json != null && json.length() > 0) {
                    Logger.i(TAG, "ready(reset=false): keeping the persisted config; the given config is ignored")
                }
                current
            }
        }
        val runtime = runtimeFlow.value.copy(didReady = true)
        persist(next.takeIf { it != current }, runtime.takeIf { it != runtimeFlow.value })
        configFlow.value = next
        runtimeFlow.value = runtime
        next
    }

    override fun merge(json: JSONObject): Config = setConfig { build(json, it) }

    override fun reset(json: JSONObject?): Config = setConfig { build(json, Config()) }

    override fun update(transform: (Config) -> Config): Config = setConfig { ConfigValidator.validate(transform(it)) }

    override fun updateRuntime(transform: (RuntimeState) -> RuntimeState): RuntimeState = synchronized(lock) {
        val current = runtimeFlow.value
        val next = transform(current)
        if (next != current) {
            persist(null, next, sync = next.enabled != current.enabled)
            runtimeFlow.value = next
        }
        next
    }

    private inline fun setConfig(compute: (Config) -> Config): Config = synchronized(lock) {
        val current = configFlow.value
        val next = compute(current)
        if (next != current) {
            persist(next, null)
            configFlow.value = next
        }
        next
    }

    /**
     * Defaults-or-[base] plus [json], clamped, then checked ([ConfigChecks]). Throws INVALID_ARGUMENT (before anything
     * changes) on bad input; logs the warnings.
     */
    private fun build(json: JSONObject?, base: Config): Config {
        val config = ConfigValidator.validate(if (json == null) base else ConfigJson.parse(json, base))
        ConfigChecks.check(config).forEach { Logger.w(TAG, it) }
        return config
    }

    /**
     * Writes the non-null values in one `apply()` (`commit()` when [sync]). A value that cannot be serialized is logged
     * and skipped. The config's tokens go to [secrets] (see the class docs), never to the prefs file.
     */
    @SuppressLint("ApplySharedPref") // commit() only for `enabled` changes, which must be on disk at once
    private fun persist(config: Config?, runtime: RuntimeState?, sync: Boolean = false) {
        if (config == null && runtime == null) return
        val editor = prefs.edit()
        if (config != null) {
            storeSecrets(config, editor)
            serialize("config") { ConfigJson.toJson(withoutSecrets(config)) }?.let { editor.putString(KEY_CONFIG, it) }
        }
        if (runtime != null) {
            serialize("runtime state") { RuntimeStateJson.toJson(runtime) }?.let { editor.putString(KEY_RUNTIME, it) }
        }
        if (sync) editor.commit() else editor.apply()
    }

    /** Writes [config]'s tokens to [secrets] if they changed. */
    private fun storeSecrets(config: Config, editor: SharedPreferences.Editor) {
        val auth = config.http.authorization
        val wanted = auth?.let { AuthorizationSecrets.of(it) }?.takeUnless { it == AuthorizationSecrets.EMPTY }
        if (wanted == storedSecrets) return
        try {
            if (wanted == null) secrets.clear() else secrets.write(wanted)
            storedSecrets = wanted
            editor.putBoolean(KEY_SECRETS, wanted != null)
            // New tokens from the app (or no authorization at all) end the "cannot be decrypted" state.
            if (auth == null || wanted?.accessToken != null || wanted?.refreshToken != null) secretsLost = false
        } catch (e: Exception) {
            // GeneralSecurityException, and the RuntimeException (ProviderException) some Keystores throw. The old
            // tokens are removed so they cannot come back after a restart (for example after a user switch).
            Logger.e(TAG, "cannot store the authorization tokens encrypted; they are kept in memory only", e)
            secrets.runCatching { clear() }
            storedSecrets = null
            editor.putBoolean(KEY_SECRETS, false)
        }
    }

    private fun withoutSecrets(config: Config): Config {
        val auth = config.http.authorization ?: return config
        val stripped = auth.copy(
            accessToken = null,
            refreshToken = null,
            refreshPayload = AuthorizationSecrets.EMPTY.refreshPayload,
            refreshHeaders = emptyMap(),
        )
        return config.copy(http = config.http.copy(authorization = stripped))
    }

    private fun withSecrets(config: Config, values: AuthorizationSecrets): Config {
        val auth = config.http.authorization ?: return config
        val merged = auth.copy(
            accessToken = values.accessToken,
            refreshToken = values.refreshToken,
            refreshPayload = values.refreshPayload,
            refreshHeaders = values.refreshHeaders,
        )
        return config.copy(http = config.http.copy(authorization = merged))
    }

    /**
     * True when the prefs file came from another installation (see the class docs); stores the current
     * `firstInstallTime` either way. A missing stored value (first run, or an update from a version that did not store
     * it) is not a restore.
     */
    @SuppressLint("ApplySharedPref")
    private fun detectRestore(): Boolean {
        val installed = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).firstInstallTime
        } catch (e: Exception) {
            Logger.w(TAG, "firstInstallTime unavailable; restores from another installation are not detected", e)
            return false
        }
        val stored = prefs.getLong(KEY_FIRST_INSTALL, NO_INSTALL_TIME)
        if (stored == installed) return false
        prefs.edit().putLong(KEY_FIRST_INSTALL, installed).commit()
        if (stored == NO_INSTALL_TIME) return false
        Logger.w(
            TAG,
            "the stored state comes from another installation (backup restore or device transfer): tracking is not " +
                "turned on by itself; tokens and boot counts are cleared",
        )
        try {
            BootCountStore.clear(appContext)
        } catch (e: Exception) {
            Logger.w(TAG, "cannot clear the boot counts", e)
        }
        return true
    }

    private inline fun serialize(what: String, toJson: () -> JSONObject): String? = try {
        toJson().toString()
    } catch (e: Exception) {
        Logger.e(TAG, "cannot persist $what", e)
        null
    }

    private fun loadConfig(): Config {
        val text = prefs.getString(KEY_CONFIG, null) ?: return Config()
        val stored = try {
            ConfigValidator.validate(ConfigJson.parseStored(JSONObject(text)))
        } catch (e: Exception) {
            Logger.e(TAG, "persisted config is unreadable; using defaults", e)
            return Config()
        }
        if (restored) {
            secrets.runCatching { clear() }
            prefs.edit().remove(KEY_SECRETS).apply()
            return withoutSecrets(stored)
        }
        return withStoredSecrets(stored)
    }

    /** [stored] with the tokens from [secrets], or with the plain-text tokens of an older version moved there. */
    private fun withStoredSecrets(stored: Config): Config {
        val auth = stored.http.authorization ?: return stored
        val plain = AuthorizationSecrets.of(auth).takeUnless { it == AuthorizationSecrets.EMPTY }
        val values = try {
            secrets.read()
        } catch (e: Exception) {
            // GeneralSecurityException, a damaged file, or a Keystore ProviderException (a RuntimeException).
            Logger.e(TAG, "the stored tokens cannot be decrypted; uploads that need them wait for new tokens", e)
            secretsLost = true
            return withoutSecrets(stored)
        }
        if (values != null) {
            storedSecrets = values
            return withSecrets(stored, values)
        }
        if (plain != null) {
            // Written in plain text by an older version: move them now.
            try {
                secrets.write(plain)
                storedSecrets = plain
                migratedSecrets = true
                prefs.edit().putBoolean(KEY_SECRETS, true).apply()
                Logger.i(TAG, "authorization tokens moved to encrypted storage")
            } catch (e: Exception) {
                Logger.w(TAG, "cannot encrypt the stored tokens yet; they stay in the old place until the next start", e)
            }
            return stored
        }
        if (prefs.getBoolean(KEY_SECRETS, false)) {
            Logger.e(TAG, "the encrypted tokens are missing; uploads that need them wait for new tokens")
            secretsLost = true
        }
        return stored
    }

    private fun loadRuntime(): RuntimeState {
        val text = prefs.getString(KEY_RUNTIME, null) ?: return RuntimeState()
        val stored = try {
            RuntimeStateJson.fromJson(JSONObject(text))
        } catch (e: Exception) {
            Logger.e(TAG, "persisted runtime state is unreadable; using defaults", e)
            return RuntimeState()
        }
        return if (restored) stored.copy(enabled = false, isMoving = false) else stored
    }

    internal companion object {
        private const val TAG = "LT.ConfigStore"

        /** Full TS-shaped config JSON (`ConfigJson.toJson`). */
        const val KEY_CONFIG = "cfg_config"

        /** Runtime state JSON (`RuntimeStateJson`). */
        const val KEY_RUNTIME = "cfg_runtime"

        /** True while the encrypted tokens file holds tokens. */
        const val KEY_SECRETS = "cfg_secrets"

        /** `PackageInfo.firstInstallTime` of the installation that wrote this file. */
        const val KEY_FIRST_INSTALL = "cfg_first_install_time"
        private const val NO_INSTALL_TIME = -1L
    }
}
