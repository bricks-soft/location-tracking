package com.brickssoft.locationtracking.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import com.brickssoft.locationtracking.core.Logger
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The secret fields of `http.authorization` (recommendation 8): `accessToken`, `refreshToken`, `refreshPayload` (it can
 * hold a client secret) and `refreshHeaders`. They are kept out of the SharedPreferences file and stored by a
 * [SecretStore] instead.
 */
data class AuthorizationSecrets(
    val accessToken: String?,
    val refreshToken: String?,
    val refreshPayload: String,
    val refreshHeaders: Map<String, String>,
) {
    override fun toString(): String = "AuthorizationSecrets(redacted)"

    fun toJson(): JSONObject = JSONObject()
        .put("accessToken", accessToken ?: JSONObject.NULL)
        .put("refreshToken", refreshToken ?: JSONObject.NULL)
        .put("refreshPayload", refreshPayload)
        .put("refreshHeaders", JSONObject(refreshHeaders))

    companion object {
        val EMPTY = AuthorizationSecrets(null, null, AuthorizationConfig().refreshPayload, emptyMap())

        fun of(auth: AuthorizationConfig): AuthorizationSecrets =
            AuthorizationSecrets(auth.accessToken, auth.refreshToken, auth.refreshPayload, auth.refreshHeaders)

        fun fromJson(json: JSONObject): AuthorizationSecrets {
            val headers = LinkedHashMap<String, String>()
            json.optJSONObject("refreshHeaders")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
            return AuthorizationSecrets(
                accessToken = if (json.isNull("accessToken")) null else json.optString("accessToken"),
                refreshToken = if (json.isNull("refreshToken")) null else json.optString("refreshToken"),
                refreshPayload = json.optString("refreshPayload", AuthorizationConfig().refreshPayload),
                refreshHeaders = headers,
            )
        }
    }
}

/** Stores [AuthorizationSecrets]. Every method may throw; the config store decides what a failure means. */
interface SecretStore {
    /** The stored secrets, or null if none are stored. @throws Exception if they are stored but cannot be read. */
    fun read(): AuthorizationSecrets?

    fun write(secrets: AuthorizationSecrets)

    fun clear()

    companion object {
        /** In memory only (tests). */
        fun inMemory(): SecretStore = object : SecretStore {
            @Volatile
            private var value: AuthorizationSecrets? = null

            override fun read(): AuthorizationSecrets? = value

            override fun write(secrets: AuthorizationSecrets) {
                value = secrets
            }

            override fun clear() {
                value = null
            }
        }
    }
}

/** Encrypts and decrypts the secrets file. */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a key that never leaves the Android Keystore (alias [KEY_ALIAS], created on first use). The output
 * is `[IV length (4 bytes)][IV][ciphertext and tag]`, with [AAD] as associated data.
 *
 * This protects copies of the file (a backup, a forensic image, `adb backup`); code running inside the app process on
 * a rooted phone can still use the key.
 */
class KeystoreSecretCipher : SecretCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(plaintext)
        val iv = cipher.iv
        return ByteBuffer.allocate(Int.SIZE_BYTES + iv.size + encrypted.size).putInt(iv.size).put(iv).put(encrypted).array()
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(ciphertext)
        val ivSize = buffer.int
        require(ivSize in 12..32 && buffer.remaining() > ivSize) { "damaged secrets file" }
        val iv = ByteArray(ivSize).also { buffer.get(it) }
        val encrypted = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(encrypted)
    }

    private fun key(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(KEY_BITS)
                        .build(),
                )
            }
            .generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.brickssoft.locationtracking.secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        val AAD = "location_tracking_secrets_v1".toByteArray(Charsets.UTF_8)
        val lock = Any()
    }
}

/**
 * [SecretStore] in the file `noBackupFilesDir/`[FILE_NAME] (never in Auto Backup or device-to-device transfer),
 * encrypted by [cipher]. Writes go to a temporary file that is renamed over the old one.
 */
class FileSecretStore(private val file: File, private val cipher: SecretCipher) : SecretStore {
    override fun read(): AuthorizationSecrets? {
        if (!file.isFile) return null
        val plaintext = cipher.decrypt(file.readBytes())
        return AuthorizationSecrets.fromJson(JSONObject(String(plaintext, Charsets.UTF_8)))
    }

    override fun write(secrets: AuthorizationSecrets) {
        val encrypted = cipher.encrypt(secrets.toJson().toString().toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeBytes(encrypted)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("could not replace $file")
        }
    }

    override fun clear() {
        if (file.exists() && !file.delete()) Logger.w(TAG, "could not delete $file")
    }

    companion object {
        private const val TAG = "LT.Secrets"
        const val FILE_NAME = "location_tracking_secrets.bin"

        /** The store the plugin uses: [FILE_NAME] under `noBackupFilesDir`, Android Keystore encryption. */
        fun forContext(context: Context): SecretStore {
            testOverride?.let { return it }
            val app = context.applicationContext ?: context
            return FileSecretStore(File(app.noBackupFilesDir, FILE_NAME), KeystoreSecretCipher())
        }

        /** Test hook: the store [forContext] returns (Robolectric has no Android Keystore). */
        @VisibleForTesting
        @Volatile
        var testOverride: SecretStore? = null
    }
}
