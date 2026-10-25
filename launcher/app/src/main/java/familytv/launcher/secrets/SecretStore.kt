package familytv.launcher.secrets

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The API keys the launcher knows about. [prefName] is the storage slot (never the value). */
enum class Secret(val prefName: String) {
    ANTHROPIC("anthropic_api_key"),
    TMDB("tmdb_api_key"),
}

/**
 * API keys entered once in Parents (docs/NOTES.md "Secrets and network security").
 * Values are never logged, never put in the APK, and never served by the local API.
 */
interface SecretStore {
    fun get(secret: Secret): String?
    /** Stores [value] trimmed; a blank value clears the slot. */
    fun set(secret: Secret, value: String)
    fun clear(secret: Secret)

    fun hasAnthropicKey(): Boolean = !get(Secret.ANTHROPIC).isNullOrBlank()
    fun hasTmdbKey(): Boolean = !get(Secret.TMDB).isNullOrBlank()

    companion object {
        /** The real store: AES-GCM with a non-exportable AndroidKeyStore key, ciphertext in SharedPreferences. */
        fun create(context: Context): SecretStore = KeystoreSecretStore(
            context.getSharedPreferences(KeystoreSecretStore.PREFS, Context.MODE_PRIVATE),
            KeystoreSecretStore::androidKeystoreKey,
        )
    }
}

/**
 * Encrypts each value with AES-256-GCM. The slot name is bound as associated data, so a ciphertext
 * copied into another slot fails to decrypt. Stored format: base64(iv) + ":" + base64(ciphertext+tag).
 *
 * @param key supplies the AES key; the AndroidKeyStore one in production, a software key in tests
 */
class KeystoreSecretStore internal constructor(
    private val prefs: SharedPreferences,
    private val key: () -> SecretKey,
) : SecretStore {

    @Synchronized
    override fun get(secret: Secret): String? {
        val stored = prefs.getString(secret.prefName, null) ?: return null
        return try {
            decrypt(stored, secret)
        } catch (e: GeneralSecurityException) {
            // Key invalidated or data tampered with. Drop it so Parents asks for the key again.
            Log.w(TAG, "Could not decrypt ${secret.name}; clearing it (${e.javaClass.simpleName})")
            clear(secret)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Malformed ${secret.name}; clearing it")
            clear(secret)
            null
        }
    }

    @Synchronized
    override fun set(secret: Secret, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return clear(secret)
        prefs.edit().putString(secret.prefName, encrypt(trimmed, secret)).apply()
    }

    @Synchronized
    override fun clear(secret: Secret) {
        prefs.edit().remove(secret.prefName).apply()
    }

    private fun encrypt(plain: String, secret: Secret): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // the provider picks a fresh random IV
        cipher.updateAAD(secret.prefName.toByteArray())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return b64.encodeToString(cipher.iv) + ":" + b64.encodeToString(sealed)
    }

    private fun decrypt(stored: String, secret: Secret): String {
        val parts = stored.split(':')
        require(parts.size == 2)
        val b64 = Base64.getDecoder()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, b64.decode(parts[0])))
        cipher.updateAAD(secret.prefName.toByteArray())
        return String(cipher.doFinal(b64.decode(parts[1])), Charsets.UTF_8)
    }

    companion object {
        internal const val PREFS = "secrets"
        private const val TAG = "SecretStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val KEY_ALIAS = "kidslauncher.secrets.v1"

        /** Loads, or creates on first use, the app's AES key. It never leaves the keystore. */
        internal fun androidKeystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
