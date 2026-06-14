package app.docsafe.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.ProviderException
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
        // Prefer a StrongBox (secure-element) backed key where available; fall back to TEE/software
        // keymaster on devices without StrongBox rather than failing key creation.
        try {
            generate(strongBox = true)
        } catch (e: ProviderException) {
            // StrongBoxUnavailableException (a ProviderException) on devices without a secure
            // element — fall back to TEE/software keymaster. Caught by the always-present
            // superclass so this method verifies on API < 28 too.
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // Re-authenticate for each unwrap; valid for biometric AND device credential. We keep
            // the device-credential fallback deliberately (so users without enrolled biometrics can
            // still unlock), which means the key is not invalidated on new biometric enrollment.
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Defense in depth: the key is only usable while the device is unlocked.
            builder.setUnlockedDeviceRequired(true)
            if (strongBox) builder.setIsStrongBoxBacked(true)
        }
        generator.init(builder.build())
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
