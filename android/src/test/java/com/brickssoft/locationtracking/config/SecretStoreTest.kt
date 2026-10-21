package com.brickssoft.locationtracking.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** [FileSecretStore] (recommendation 8) with a test cipher; the Android Keystore cipher needs a real phone. */
@RunWith(RobolectricTestRunner::class)
class SecretStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Reverses and flips the bytes: enough to show that the file does not hold the plain text. */
    private val cipher = object : SecretCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.reversedArray().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray().reversedArray()
    }

    private val secrets = AuthorizationSecrets(
        accessToken = "access-token-value",
        refreshToken = "refresh-token-value",
        refreshPayload = """{"client_secret":"s3cret","refresh_token":"{refreshToken}"}""",
        refreshHeaders = mapOf("X-Client" to "abc"),
    )

    @Test
    fun `secrets round-trip through the encrypted file`() {
        val file = File(folder.root, "no_backup/${FileSecretStore.FILE_NAME}")
        val store = FileSecretStore(file, cipher)

        assertNull(store.read())
        store.write(secrets)

        assertEquals(secrets, FileSecretStore(file, cipher).read())
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("access-token-value"))
        assertFalse(raw.contains("s3cret"))
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun `null tokens stay null`() {
        val file = File(folder.root, FileSecretStore.FILE_NAME)
        FileSecretStore(file, cipher).write(AuthorizationSecrets.EMPTY.copy(refreshToken = "r"))

        val read = FileSecretStore(file, cipher).read()!!
        assertNull(read.accessToken)
        assertEquals("r", read.refreshToken)
    }

    @Test
    fun `clear deletes the file`() {
        val file = File(folder.root, FileSecretStore.FILE_NAME)
        val store = FileSecretStore(file, cipher)
        store.write(secrets)

        store.clear()

        assertFalse(file.exists())
        assertNull(store.read())
    }

    @Test
    fun `a file that cannot be decrypted throws instead of reading as empty`() {
        val file = File(folder.root, FileSecretStore.FILE_NAME)
        file.writeBytes(byteArrayOf(1, 2, 3))

        try {
            FileSecretStore(file, cipher).read()
            fail("expected an exception")
        } catch (e: Exception) {
            assertTrue(file.exists())
        }
    }

    @Test
    fun `toString never shows the values`() {
        assertFalse(secrets.toString().contains("access-token-value"))
    }
}
