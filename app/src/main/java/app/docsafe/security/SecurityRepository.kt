package app.docsafe.security

import android.os.SystemClock
import app.docsafe.crypto.DecryptionException
import app.docsafe.crypto.KeyEnvelope
import app.docsafe.vault.VaultSession
import app.docsafe.vault.format.KdfParamsDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/** Where the app is in the lock/setup lifecycle. */
enum class SecurityState {
    /** No vault on this device yet — onboarding must create or import one. */
    Uninitialized,

    /** A `.dsvault` file was opened/shared into the app and is awaiting its master password. */
    ImportPending,

    /** A vault is open (just created/imported) but no biometric/PIN unlock method is set. */
    NeedsUnlockMethod,

    /** A vault exists with an unlock method, but the DEK is not in memory — show unlock. */
    Locked,

    /** Vault open and usable. */
    Unlocked,
}

/**
 * Coordinates the device-side security model: wrapping the vault DEK under a Keystore key
 * (biometric/device credential) and/or a PIN-derived key, and unwrapping it on unlock. The
 * master password is only ever used to create or import a vault — after that, unlock uses
 * biometric or PIN.
 */
@Singleton
class SecurityRepository @Inject constructor(
    private val secureStore: SecureStore,
    private val keyManager: BiometricKeyManager,
    private val session: VaultSession,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // The DEK held transiently between vault creation/import and choosing an unlock method.
    private var pendingDek: ByteArray? = null

    // An external .dsvault staged for import (opened/shared into the app), awaiting its password.
    private var pendingImportFile: File? = null
    var pendingImportName: String? = null
        private set

    // Lock-on-background coordination. We must NOT lock when the app backgrounds only because
    // *we* launched another activity (file picker, scanner, share sheet) — otherwise the user
    // is forced to re-authenticate mid-task and the in-flight result (e.g. a picked file) is
    // lost. A short grace period also prevents a brief alt-tab from forcing a re-unlock.
    private var suppressNextBackgroundLock = false
    private var backgroundedAtElapsedMs: Long? = null

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SecurityState> = _state.asStateFlow()

    val biometricEnabled: Boolean get() = secureStore.biometricEnabled
    val pinEnabled: Boolean get() = secureStore.pinEnabled

    /** Stable per-install id used as `modifiedBy` in the vault's merge clock. */
    fun deviceId(): String =
        secureStore.deviceId ?: UUID.randomUUID().toString().also { secureStore.deviceId = it }

    private fun initialState(): SecurityState =
        if (!secureStore.vaultImported || !session.fileExists()) {
            SecurityState.Uninitialized
        } else {
            SecurityState.Locked
        }

    // --- Vault creation / import -------------------------------------------------------

    fun createVault(password: CharArray) {
        deviceId() // ensure assigned
        pendingDek = session.create(password)
        _state.value = SecurityState.NeedsUnlockMethod
    }

    /** Imports an existing vault file already placed at the session path. */
    fun importWithPassword(password: CharArray): Boolean = try {
        pendingDek = session.openWithPassword(password)
        _state.value = SecurityState.NeedsUnlockMethod
        true
    } catch (e: DecryptionException) {
        false
    }

    // --- Importing a shared .dsvault file ----------------------------------------------

    /** Stages an external `.dsvault` (opened/shared into the app) for import; shows the
     *  password prompt. [displayName] is shown to the user. */
    fun beginImport(file: File, displayName: String?) {
        pendingImportFile = file
        pendingImportName = displayName
        _state.value = SecurityState.ImportPending
    }

    /**
     * Confirms a pending import with the shared master [password]. Verifies it against the
     * imported file before replacing the local vault; on success the imported vault becomes
     * this device's vault and the user re-chooses an unlock method. Returns false on wrong
     * password (the existing vault is left untouched).
     */
    fun confirmImport(password: CharArray): Boolean {
        val src = pendingImportFile ?: return false
        val dek = session.importExternal(src, password) ?: return false
        // The imported vault has a different DEK, so prior unlock methods no longer apply.
        secureStore.clearUnlockMethods()
        secureStore.vaultImported = false
        pendingDek = dek
        src.delete()
        pendingImportFile = null
        pendingImportName = null
        _state.value = SecurityState.NeedsUnlockMethod
        return true
    }

    /** Abandons a pending import and returns to the prior state. */
    fun cancelImport() {
        pendingImportFile?.delete()
        pendingImportFile = null
        pendingImportName = null
        _state.value = initialState()
    }

    // --- Biometric setup / unlock ------------------------------------------------------

    fun biometricEncryptCipher(): Cipher {
        keyManager.ensureKey()
        return keyManager.encryptCipher()
    }

    fun biometricDecryptCipher(): Cipher {
        val wrapped = requireNotNull(secureStore.biometricWrappedDek) { "Biometric not set up" }
        return keyManager.decryptCipher(wrapped.copyOf(GCM_IV_LEN))
    }

    /** Completes biometric *setup*: wraps the pending DEK with the authenticated cipher. */
    fun completeBiometricSetup(authenticatedCipher: Cipher) {
        val dek = currentDek()
        val ciphertext = authenticatedCipher.doFinal(dek)
        secureStore.biometricWrappedDek = authenticatedCipher.iv + ciphertext
        markMethodAdded()
    }

    /** Completes biometric *unlock*: unwraps the DEK and opens the vault. */
    fun completeBiometricUnlock(authenticatedCipher: Cipher) {
        val wrapped = requireNotNull(secureStore.biometricWrappedDek)
        val dek = authenticatedCipher.doFinal(wrapped.copyOfRange(GCM_IV_LEN, wrapped.size))
        session.openWithDek(dek)
        _state.value = SecurityState.Unlocked
    }

    // --- PIN setup / unlock ------------------------------------------------------------

    fun enablePin(pin: CharArray) {
        val dek = currentDek()
        val params = PinKdf.newParams()
        val key = PinKdf.deriveKey(pin, params)
        try {
            secureStore.pinWrappedDek = KeyEnvelope.wrap(key, dek)
            secureStore.pinKdfParams = json.encodeToString(KdfParamsDto.serializer(), KdfParamsDto.from(params))
                .toByteArray(Charsets.UTF_8)
        } finally {
            key.fill(0)
        }
        markMethodAdded()
    }

    /** Removes the PIN method (only allowed if biometrics remain, so a method always stays). */
    fun disablePin(): Boolean {
        if (!secureStore.biometricEnabled) return false
        secureStore.pinWrappedDek = null
        secureStore.pinKdfParams = null
        return true
    }

    /** Removes the biometric method (only allowed if a PIN remains). */
    fun disableBiometric(): Boolean {
        if (!secureStore.pinEnabled) return false
        secureStore.biometricWrappedDek = null
        keyManager.deleteKey()
        return true
    }

    /** Attempts to unlock with [pin]; returns false on a wrong PIN. */
    fun unlockWithPin(pin: CharArray): Boolean {
        val paramsBytes = secureStore.pinKdfParams ?: return false
        val wrapped = secureStore.pinWrappedDek ?: return false
        val params = json.decodeFromString(KdfParamsDto.serializer(), String(paramsBytes, Charsets.UTF_8)).toKdfParams()
        val key = PinKdf.deriveKey(pin, params)
        val dek = try {
            KeyEnvelope.unwrap(key, wrapped)
        } catch (e: DecryptionException) {
            return false
        } finally {
            key.fill(0)
        }
        session.openWithDek(dek)
        _state.value = SecurityState.Unlocked
        return true
    }

    // --- Lock lifecycle ----------------------------------------------------------------

    /**
     * Call right before launching an external activity that keeps our process alive (system
     * file picker, document scanner, share sheet). The next background event will not lock.
     */
    fun notifyExternalActivityStarting() {
        suppressNextBackgroundLock = true
    }

    /** The whole app went to the background. */
    fun onAppBackgrounded() {
        if (suppressNextBackgroundLock) return // we launched a picker/scanner; stay unlocked
        if (_state.value == SecurityState.Unlocked) {
            backgroundedAtElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    /** The app returned to the foreground; re-lock if it was away longer than the grace period. */
    fun onAppForegrounded() {
        val suppressed = suppressNextBackgroundLock
        suppressNextBackgroundLock = false
        val backgroundedAt = backgroundedAtElapsedMs
        backgroundedAtElapsedMs = null
        if (suppressed) return
        if (_state.value == SecurityState.Unlocked &&
            backgroundedAt != null &&
            SystemClock.elapsedRealtime() - backgroundedAt >= LOCK_GRACE_MS
        ) {
            lock()
        }
    }

    /** Immediately lock the vault (clears the DEK from memory and closes the file). */
    fun lock() {
        session.close()
        pendingDek?.fill(0)
        pendingDek = null
        backgroundedAtElapsedMs = null
        if (secureStore.vaultImported) _state.value = SecurityState.Locked
    }

    private fun currentDek(): ByteArray =
        pendingDek ?: session.current()?.dataKey ?: error("No DEK available for setup")

    /** A method was wrapped; the vault is now recoverable on this device. Stay in setup so
     *  the user may add the second method before finishing. */
    private fun markMethodAdded() {
        secureStore.vaultImported = true
    }

    /** Leaves onboarding once at least one unlock method is configured. */
    fun completeOnboarding() {
        if (!secureStore.anyMethodEnabled) return
        pendingDek?.fill(0)
        pendingDek = null
        _state.value = SecurityState.Unlocked
    }

    private companion object {
        const val GCM_IV_LEN = 12

        // Re-lock only if the app was in the background at least this long. Brief task switches
        // (alt-tab, returning from a picker) within this window keep the vault open.
        const val LOCK_GRACE_MS = 30_000L
    }
}
