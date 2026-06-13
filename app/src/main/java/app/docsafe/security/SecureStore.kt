package app.docsafe.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.Base64

/**
 * App-private encrypted key/value storage (backed by a Keystore master key). Holds the
 * locally-wrapped copies of the vault DEK and the unlock metadata — never the master
 * password and never the raw DEK.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "docsync_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var vaultImported: Boolean
        get() = prefs.getBoolean(KEY_VAULT_IMPORTED, false)
        set(value) = prefs.edit().putBoolean(KEY_VAULT_IMPORTED, value).apply()

    /** DEK wrapped by the biometric/device-credential Keystore key: `iv || ciphertext`. */
    var biometricWrappedDek: ByteArray?
        get() = getBytes(KEY_BIO_WRAPPED_DEK)
        set(value) = putBytes(KEY_BIO_WRAPPED_DEK, value)

    /** DEK wrapped by a PIN-derived key (`nonce || ciphertext || tag`). */
    var pinWrappedDek: ByteArray?
        get() = getBytes(KEY_PIN_WRAPPED_DEK)
        set(value) = putBytes(KEY_PIN_WRAPPED_DEK, value)

    /** Serialized Argon2id params (salt/cost) for the PIN KDF. */
    var pinKdfParams: ByteArray?
        get() = getBytes(KEY_PIN_KDF_PARAMS)
        set(value) = putBytes(KEY_PIN_KDF_PARAMS, value)

    val biometricEnabled: Boolean get() = biometricWrappedDek != null
    val pinEnabled: Boolean get() = pinWrappedDek != null
    val anyMethodEnabled: Boolean get() = biometricEnabled || pinEnabled

    fun clearUnlockMethods() {
        prefs.edit()
            .remove(KEY_BIO_WRAPPED_DEK)
            .remove(KEY_PIN_WRAPPED_DEK)
            .remove(KEY_PIN_KDF_PARAMS)
            .apply()
    }

    private fun getBytes(key: String): ByteArray? =
        prefs.getString(key, null)?.let { Base64.getDecoder().decode(it) }

    private fun putBytes(key: String, value: ByteArray?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, Base64.getEncoder().encodeToString(value))
        editor.apply()
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_VAULT_IMPORTED = "vault_imported"
        const val KEY_BIO_WRAPPED_DEK = "bio_wrapped_dek"
        const val KEY_PIN_WRAPPED_DEK = "pin_wrapped_dek"
        const val KEY_PIN_KDF_PARAMS = "pin_kdf_params"
    }
}
