package app.docsafe.security

import android.content.Context
import android.os.SystemClock
import app.docsafe.crypto.DecryptionException
import app.docsafe.crypto.KeyEnvelope
import app.docsafe.ui.wipeShareCaches
import app.docsafe.vault.VaultSession
import dagger.hilt.android.qualifiers.ApplicationContext
import app.docsafe.vault.format.KdfParamsDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
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

    /** Vaults exist with an unlock method, but the master key is not in memory — show unlock. */
    Locked,

    /** Master key in memory, active vault open and usable. */
    Unlocked,
}

/**
 * Coordinates the device-side security model. A random **device master key** is wrapped under a
 * Keystore key (biometric/device credential) and/or a PIN-derived key. Each vault's DEK is wrapped
 * by the master key. One unlock unwraps the master key (held in memory while unlocked), from which
 * any vault's DEK can be unwrapped — so the user can switch between, and copy across, vaults with
 * no further prompts. A vault's master password is only ever used to create/import/export it.
 */
@Singleton
class SecurityRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val secureStore: SecureStore,
    private val keyManager: BiometricKeyManager,
    private val session: VaultSession,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Device master key, present only while unlocked.
    private var masterKey: ByteArray? = null

    // Master key held between first-vault creation/import and choosing an unlock method.
    private var pendingMasterKey: ByteArray? = null

    private var registry: VaultRegistry = loadRegistry()

    // An external .dsvault staged for import (opened/shared into the app), awaiting its password.
    private var pendingImportFile: File? = null
    var pendingImportName: String? = null
        private set

    // Auto-lock is suppressed only briefly while we launch our own pickers/scanners, so a cancelled
    // launch can't leave the vault permanently un-lockable. Expressed as an expiry timestamp.
    private var suppressBackgroundLockUntilMs: Long = 0L
    private var backgroundedAtElapsedMs: Long? = null

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SecurityState> = _state.asStateFlow()

    // Reflect *any* available unlock method, including a pre-1.0.4 legacy wrap that has not yet
    // been migrated to the master-key layout. Migration only runs during unlock, so before it the
    // master-key wraps are absent — reading only those would leave the unlock screen with no
    // biometric prompt and no PIN button, locking upgrading users out.
    val biometricEnabled: Boolean get() = biometricWrappedBlob() != null
    val pinEnabled: Boolean get() = pinWrappedBlob() != null

    /** Stable per-install id used as `modifiedBy` in the vault's merge clock. */
    fun deviceId(): String =
        secureStore.deviceId ?: UUID.randomUUID().toString().also { secureStore.deviceId = it }

    // --- Vault registry (read API for the UI) ------------------------------------------

    fun vaults(): List<VaultMeta> = registry.vaults
    fun activeVaultId(): String? = registry.activeVaultId
    fun activeVaultName(): String? = registry.active?.name
    val isUnlocked: Boolean get() = masterKey != null

    private fun loadRegistry(): VaultRegistry =
        secureStore.vaultsJson?.let {
            runCatching { json.decodeFromString(VaultRegistry.serializer(), it) }.getOrNull()
        } ?: VaultRegistry()

    private fun persistRegistry() {
        secureStore.vaultsJson = json.encodeToString(VaultRegistry.serializer(), registry)
    }

    private fun needsMigration(): Boolean =
        !secureStore.anyMethodEnabled &&
            secureStore.legacyVaultImported &&
            (secureStore.legacyBiometricWrappedDek != null || secureStore.legacyPinWrappedDek != null) &&
            session.legacyFile().exists()

    private fun initialState(): SecurityState = when {
        secureStore.anyMethodEnabled && registry.active != null -> SecurityState.Locked
        needsMigration() -> SecurityState.Locked
        else -> SecurityState.Uninitialized
    }

    // --- Vault creation / import -------------------------------------------------------

    fun createVault(password: CharArray) = createVault(DEFAULT_VAULT_NAME, password)

    /** Creates a new vault. If unlocked, it is added and becomes active; otherwise it begins onboarding. */
    @Synchronized
    fun createVault(name: String, password: CharArray) {
        deviceId()
        val unlocked = masterKey != null
        val mk = masterKey ?: pendingMasterKey ?: KeyEnvelope.generateDek().also { pendingMasterKey = it }
        val id = UUID.randomUUID().toString()
        val fileName = vaultFileName(id)
        val dek = session.create(session.fileFor(fileName), password)
        try {
            registerVault(id, name, fileName, KeyEnvelope.wrap(mk, dek), unlocked)
        } finally {
            dek.fill(0)
        }
        if (!unlocked) _state.value = SecurityState.NeedsUnlockMethod
    }

    /** Imports an external [src] `.dsvault` as a new vault. Returns false on a wrong password. */
    @Synchronized
    fun importVault(name: String, src: File, password: CharArray): Boolean {
        deviceId()
        val unlocked = masterKey != null
        val mk = masterKey ?: pendingMasterKey ?: KeyEnvelope.generateDek()
        val id = UUID.randomUUID().toString()
        val fileName = vaultFileName(id)
        val dek = session.importInto(session.fileFor(fileName), src, password) ?: return false
        if (masterKey == null) pendingMasterKey = mk
        try {
            registerVault(id, name, fileName, KeyEnvelope.wrap(mk, dek), unlocked)
        } finally {
            dek.fill(0)
        }
        if (!unlocked) _state.value = SecurityState.NeedsUnlockMethod
        return true
    }

    private fun registerVault(id: String, name: String, fileName: String, wrappedDek: ByteArray, unlocked: Boolean) {
        val meta = VaultMeta(id, name.ifBlank { DEFAULT_VAULT_NAME }, fileName, now())
        registry = if (unlocked) {
            registry.copy(
                vaults = registry.vaults + meta,
                activeVaultId = id,
                wrappedDeks = registry.wrappedDeks + (id to b64(wrappedDek)),
            )
        } else {
            // First vault during onboarding — start a fresh registry.
            VaultRegistry(vaults = listOf(meta), activeVaultId = id, wrappedDeks = mapOf(id to b64(wrappedDek)))
        }
        persistRegistry()
    }

    // --- Importing a shared .dsvault file ----------------------------------------------

    /** True while a shared `.dsvault` is staged and waiting to be unlocked before import. */
    fun hasPendingImport(): Boolean = pendingImportFile != null

    @Synchronized
    fun beginImport(file: File, displayName: String?) {
        pendingImportFile = file
        pendingImportName = displayName
        // If vaults already exist but we're locked, the user must unlock first to add a vault.
        _state.value = if (registry.vaults.isNotEmpty() && masterKey == null) {
            SecurityState.Locked
        } else {
            SecurityState.ImportPending
        }
    }

    /** Once unlocked with a pending import present, move to the password prompt. */
    @Synchronized
    fun promotePendingImport() {
        if (pendingImportFile != null) _state.value = SecurityState.ImportPending
    }

    /** Confirms a pending shared-file import with its master [password]; adds it as a new vault. */
    @Synchronized
    fun confirmImport(password: CharArray): Boolean {
        val src = pendingImportFile ?: return false
        val name = pendingImportName?.removeSuffix(".dsvault")?.ifBlank { null } ?: DEFAULT_IMPORTED_NAME
        val ok = importVault(name, src, password)
        if (ok) {
            src.delete()
            pendingImportFile = null
            pendingImportName = null
        }
        return ok
    }

    @Synchronized
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
        val wrapped = requireNotNull(biometricWrappedBlob()) { "Biometric not set up" }
        return keyManager.decryptCipher(wrapped.copyOf(GCM_IV_LEN))
    }

    /** Completes biometric *setup*: wraps the (pending) master key with the authenticated cipher. */
    @Synchronized
    fun completeBiometricSetup(authenticatedCipher: Cipher) {
        val mk = currentMasterKey()
        val ciphertext = authenticatedCipher.doFinal(mk)
        secureStore.masterKeyBiometricWrapped = authenticatedCipher.iv + ciphertext
    }

    /** Completes biometric *unlock*: unwraps the master key, migrates if needed, opens active vault. */
    @Synchronized
    fun completeBiometricUnlock(authenticatedCipher: Cipher) {
        val wrapped = requireNotNull(biometricWrappedBlob())
        val mk = authenticatedCipher.doFinal(wrapped.copyOfRange(GCM_IV_LEN, wrapped.size))
        finishUnlock(mk)
    }

    private fun biometricWrappedBlob(): ByteArray? =
        secureStore.masterKeyBiometricWrapped ?: secureStore.legacyBiometricWrappedDek

    // --- PIN setup / unlock ------------------------------------------------------------

    @Synchronized
    fun enablePin(pin: CharArray) {
        val mk = currentMasterKey()
        val params = PinKdf.newParams()
        val key = PinKdf.deriveKey(pin, params)
        try {
            secureStore.masterKeyPinWrapped = KeyEnvelope.wrap(key, mk)
            secureStore.pinKdfParams = json.encodeToString(KdfParamsDto.serializer(), KdfParamsDto.from(params))
                .toByteArray(Charsets.UTF_8)
        } finally {
            key.fill(0)
        }
    }

    @Synchronized
    fun disablePin(): Boolean {
        if (!secureStore.biometricEnabled) return false
        secureStore.masterKeyPinWrapped = null
        secureStore.pinKdfParams = null
        return true
    }

    @Synchronized
    fun disableBiometric(): Boolean {
        if (!secureStore.pinEnabled) return false
        secureStore.masterKeyBiometricWrapped = null
        keyManager.deleteKey()
        return true
    }

    @Synchronized
    fun unlockWithPin(pin: CharArray): Boolean {
        val paramsBytes = secureStore.pinKdfParams ?: return false
        val wrapped = pinWrappedBlob() ?: return false
        val params = json.decodeFromString(KdfParamsDto.serializer(), String(paramsBytes, Charsets.UTF_8)).toKdfParams()
        val key = PinKdf.deriveKey(pin, params)
        val mk = try {
            KeyEnvelope.unwrap(key, wrapped)
        } catch (e: DecryptionException) {
            return false
        } finally {
            key.fill(0)
        }
        finishUnlock(mk)
        return true
    }

    private fun pinWrappedBlob(): ByteArray? =
        secureStore.masterKeyPinWrapped ?: secureStore.legacyPinWrappedDek

    /** Verifies [pin] without changing any state (step-up confirmation for sensitive actions). */
    fun verifyPin(pin: CharArray): Boolean {
        val paramsBytes = secureStore.pinKdfParams ?: return false
        val wrapped = pinWrappedBlob() ?: return false
        val params = json.decodeFromString(KdfParamsDto.serializer(), String(paramsBytes, Charsets.UTF_8)).toKdfParams()
        val key = PinKdf.deriveKey(pin, params)
        return try {
            KeyEnvelope.unwrap(key, wrapped).fill(0)
            true
        } catch (e: DecryptionException) {
            false
        } finally {
            key.fill(0)
        }
    }

    /** Common tail of every unlock: migrate legacy if needed, hold the master key, open active vault. */
    private fun finishUnlock(mk: ByteArray) {
        if (needsMigration()) migrateLegacyVault(mk)
        masterKey = mk
        openActiveVault()
        // A shared file may have been opened while locked; let the UI prompt for its password now.
        if (pendingImportFile != null) {
            _state.value = SecurityState.ImportPending
        } else {
            _state.value = SecurityState.Unlocked
        }
    }

    /**
     * One-time upgrade from the pre-1.0.4 single-vault layout. At this point [mk] is the old DEK
     * (the only thing the biometric/PIN keys wrapped). We adopt it as the device master key: the
     * existing biometric/PIN wraps now wrap the master key, the existing vault's DEK (== mk) is
     * wrapped under the master key, and the file is registered. Old keys are cleared last.
     */
    private fun migrateLegacyVault(mk: ByteArray) {
        val id = UUID.randomUUID().toString()
        val fileName = vaultFileName(id)
        val dest = session.fileFor(fileName)
        val legacy = session.legacyFile()
        if (!dest.exists()) {
            if (!legacy.renameTo(dest)) legacy.copyTo(dest, overwrite = true)
        }
        registry = VaultRegistry(
            vaults = listOf(VaultMeta(id, DEFAULT_VAULT_NAME, fileName, now())),
            activeVaultId = id,
            wrappedDeks = mapOf(id to b64(KeyEnvelope.wrap(mk, mk))),
        )
        persistRegistry()
        secureStore.legacyBiometricWrappedDek?.let { secureStore.masterKeyBiometricWrapped = it }
        secureStore.legacyPinWrappedDek?.let { secureStore.masterKeyPinWrapped = it }
        secureStore.clearLegacy()
        if (legacy.exists() && dest.exists() && legacy != dest) legacy.delete()
    }

    // --- Vault switching / removal / DEK access ----------------------------------------

    /** Opens the active vault using the in-memory master key. */
    private fun openActiveVault() {
        val mk = masterKey ?: error("Locked")
        val meta = registry.active ?: error("No active vault")
        val dek = unwrapDek(mk, meta.id)
        try {
            session.openWithDek(session.fileFor(meta.fileName), dek)
        } finally {
            dek.fill(0)
        }
    }

    /** Switches the active vault (seamless — uses the master key already in memory). */
    @Synchronized
    fun switchToVault(id: String) {
        val mk = masterKey ?: error("Locked")
        val meta = registry.meta(id) ?: return
        val dek = unwrapDek(mk, id)
        try {
            session.openWithDek(session.fileFor(meta.fileName), dek)
        } finally {
            dek.fill(0)
        }
        registry = registry.copy(activeVaultId = id)
        persistRegistry()
    }

    /** Removes a vault (deletes its file + keys). If it was the last one, returns to onboarding. */
    @Synchronized
    fun removeVault(id: String) {
        val meta = registry.meta(id) ?: return
        val wasActive = registry.activeVaultId == id
        session.fileFor(meta.fileName).delete()
        registry = registry.copy(
            vaults = registry.vaults.filterNot { it.id == id },
            wrappedDeks = registry.wrappedDeks - id,
            activeVaultId = if (wasActive) null else registry.activeVaultId,
        )
        if (registry.vaults.isEmpty()) {
            // Last vault gone — wipe everything and return to onboarding.
            session.close()
            masterKey?.fill(0); masterKey = null
            keyManager.deleteKey()
            secureStore.clearAll()
            registry = VaultRegistry()
            _state.value = SecurityState.Uninitialized
            return
        }
        if (wasActive) {
            val next = registry.vaults.first()
            registry = registry.copy(activeVaultId = next.id)
            if (masterKey != null) openActiveVault()
        }
        persistRegistry()
    }

    /** Unwraps a vault's DEK for cross-vault copy/merge (requires unlocked). Caller zeroes it. */
    @Synchronized
    fun dekFor(id: String): ByteArray {
        val mk = masterKey ?: error("Locked")
        return unwrapDek(mk, id)
    }

    private fun unwrapDek(mk: ByteArray, vaultId: String): ByteArray {
        val wrapped = requireNotNull(registry.wrappedDeks[vaultId]) { "No key for vault $vaultId" }
        return KeyEnvelope.unwrap(mk, Base64.getDecoder().decode(wrapped))
    }

    /** Changes the active vault's master password (re-wraps its DEK under a new password). */
    @Synchronized
    fun changeActiveVaultPassword(newPassword: CharArray) {
        session.require().changePassword(newPassword)
    }

    // --- Lock lifecycle ----------------------------------------------------------------

    @Synchronized
    fun notifyExternalActivityStarting() {
        suppressBackgroundLockUntilMs = SystemClock.elapsedRealtime() + EXTERNAL_LAUNCH_WINDOW_MS
    }

    private fun backgroundLockSuppressed(): Boolean =
        SystemClock.elapsedRealtime() < suppressBackgroundLockUntilMs

    @Synchronized
    fun onAppBackgrounded() {
        if (backgroundLockSuppressed()) return
        if (_state.value == SecurityState.Unlocked) {
            backgroundedAtElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    @Synchronized
    fun onAppForegrounded() {
        // Consume any pending suppression on return so it can't carry over to a later backgrounding.
        suppressBackgroundLockUntilMs = 0L
        val backgroundedAt = backgroundedAtElapsedMs
        backgroundedAtElapsedMs = null
        if (_state.value == SecurityState.Unlocked &&
            backgroundedAt != null &&
            SystemClock.elapsedRealtime() - backgroundedAt >= LOCK_GRACE_MS
        ) {
            lock()
        }
    }

    @Synchronized
    fun lock() {
        session.close()
        masterKey?.fill(0); masterKey = null
        pendingMasterKey?.fill(0); pendingMasterKey = null
        backgroundedAtElapsedMs = null
        // Drop any decrypted plaintext we wrote to the cache for sharing/opening so it does not
        // linger at rest once the vault is locked.
        wipeShareCaches(appContext)
        if (secureStore.anyMethodEnabled && registry.active != null) _state.value = SecurityState.Locked
    }

    private fun currentMasterKey(): ByteArray =
        masterKey ?: pendingMasterKey ?: error("No master key available for setup")

    /** Leaves onboarding once at least one unlock method is configured. */
    @Synchronized
    fun completeOnboarding() {
        if (!secureStore.anyMethodEnabled) return
        masterKey = pendingMasterKey
        pendingMasterKey = null
        _state.value = SecurityState.Unlocked
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun now(): Long = System.currentTimeMillis()
    private fun vaultFileName(id: String): String = "vault-$id.dsvault"

    private companion object {
        const val GCM_IV_LEN = 12
        const val LOCK_GRACE_MS = 30_000L
        const val EXTERNAL_LAUNCH_WINDOW_MS = 60_000L
        const val DEFAULT_VAULT_NAME = "My Vault"
        const val DEFAULT_IMPORTED_NAME = "Imported vault"
    }
}
