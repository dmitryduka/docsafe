package app.docsafe.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.Base64

/**
 * App-private encrypted key/value storage (backed by a Keystore master key). Holds the locally
 * wrapped **device master key** (under biometric and/or PIN), the vault registry (which carries
 * each vault's master-key-wrapped DEK), and unlock metadata — never a vault master password and
 * never a raw key.
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

    /** Device master key wrapped by the biometric/device-credential Keystore key: `iv || ct`. */
    var masterKeyBiometricWrapped: ByteArray?
        get() = getBytes(KEY_MK_BIO)
        set(value) = putBytes(KEY_MK_BIO, value)

    /** Device master key wrapped by a PIN-derived key (`nonce || ciphertext || tag`). */
    var masterKeyPinWrapped: ByteArray?
        get() = getBytes(KEY_MK_PIN)
        set(value) = putBytes(KEY_MK_PIN, value)

    /** Serialized Argon2id params (salt/cost) for the PIN KDF. */
    var pinKdfParams: ByteArray?
        get() = getBytes(KEY_PIN_KDF_PARAMS)
        set(value) = putBytes(KEY_PIN_KDF_PARAMS, value)

    /** Serialized [VaultRegistry] (vault list + active id + per-vault wrapped DEKs). */
    var vaultsJson: String?
        get() = prefs.getString(KEY_VAULTS_JSON, null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_VAULTS_JSON) else editor.putString(KEY_VAULTS_JSON, value)
            editor.apply()
        }

    val biometricEnabled: Boolean get() = masterKeyBiometricWrapped != null
    val pinEnabled: Boolean get() = masterKeyPinWrapped != null
    val anyMethodEnabled: Boolean get() = biometricEnabled || pinEnabled

    /** Removes unlock methods + the registry (used when the last vault is removed). */
    fun clearAll() {
        prefs.edit()
            .remove(KEY_MK_BIO)
            .remove(KEY_MK_PIN)
            .remove(KEY_PIN_KDF_PARAMS)
            .remove(KEY_VAULTS_JSON)
            .apply()
    }

    // --- Legacy (pre-multi-vault) fields, read-only for one-time migration ----------------

    /** True if a single pre-1.0.4 vault was set up on this device. */
    val legacyVaultImported: Boolean get() = prefs.getBoolean(KEY_LEGACY_VAULT_IMPORTED, false)
    val legacyBiometricWrappedDek: ByteArray? get() = getBytes(KEY_LEGACY_BIO_WRAPPED_DEK)
    val legacyPinWrappedDek: ByteArray? get() = getBytes(KEY_LEGACY_PIN_WRAPPED_DEK)

    /** Removes the legacy single-vault keys after a successful migration. */
    fun clearLegacy() {
        prefs.edit()
            .remove(KEY_LEGACY_VAULT_IMPORTED)
            .remove(KEY_LEGACY_BIO_WRAPPED_DEK)
            .remove(KEY_LEGACY_PIN_WRAPPED_DEK)
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
        const val KEY_MK_BIO = "mk_bio_wrapped"
        const val KEY_MK_PIN = "mk_pin_wrapped"
        const val KEY_PIN_KDF_PARAMS = "pin_kdf_params"
        const val KEY_VAULTS_JSON = "vaults_json"

        // Pre-1.0.4 single-vault keys (migrated then cleared).
        const val KEY_LEGACY_VAULT_IMPORTED = "vault_imported"
        const val KEY_LEGACY_BIO_WRAPPED_DEK = "bio_wrapped_dek"
        const val KEY_LEGACY_PIN_WRAPPED_DEK = "pin_wrapped_dek"
    }
}
