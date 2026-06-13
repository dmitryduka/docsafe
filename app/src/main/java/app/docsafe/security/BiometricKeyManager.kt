package app.docsafe.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

/**
 * Owns the hardware-backed AES key in the Android Keystore used to wrap the vault DEK for
 * biometric/device-credential unlock. The key requires user authentication for every use,
 * so the only way to obtain a working [Cipher] is through a successful [BiometricPrompt]
 * with a `CryptoObject` — that is what binds "decrypt the DEK" to "the user authenticated".
 */
class BiometricKeyManager {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun hasKey(): Boolean = keyStore.containsAlias(KEY_ALIAS)

    fun deleteKey() {
        if (hasKey()) keyStore.deleteEntry(KEY_ALIAS)
    }

    /** Creates the auth-bound key if it does not already exist. */
    fun ensureKey() {
        if (hasKey()) return
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // Re-authenticate for each unwrap; valid for biometric AND device credential.
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .build()
        generator.init(spec)
        generator.generateKey()
    }

    private fun secretKey(): SecretKey =
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: error("Keystore key missing")

    /** Cipher initialized for encryption; pair the resulting IV with the ciphertext when storing. */
    fun encryptCipher(): Cipher = Cipher.getInstance(TRANSFORM).apply {
        init(Cipher.ENCRYPT_MODE, secretKey())
    }

    /** Cipher initialized for decryption using the [iv] stored alongside the ciphertext. */
    fun decryptCipher(iv: ByteArray): Cipher = Cipher.getInstance(TRANSFORM).apply {
        init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "docsync_dek_wrapping_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
