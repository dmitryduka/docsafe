package app.docsafe.vault

import android.content.Context
import app.docsafe.crypto.DecryptionException
import app.docsafe.vault.store.LocalFileVaultStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Bytes occupied by the vault file before and after a compaction. */
data class CompactionResult(val beforeBytes: Long, val afterBytes: Long) {
    val reclaimedBytes: Long get() = (beforeBytes - afterBytes).coerceAtLeast(0)
}

/**
 * Holds the single **active** decrypted [VaultFile] (the vault the user is currently viewing).
 * Each vault is a `.dsvault` in app-private storage; this opens one with a DEK (after unlock) or
 * a password (create/import) and closes it on lock. A second vault can be opened *transiently*
 * via [openTransient] for cross-vault copy/merge without disturbing the active session.
 */
@Singleton
class VaultSession @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val filesDir: File = context.filesDir

    // Guards the active [vault]/[activeFile] reference. Reads (readBlob, size, export) take the read
    // lock so they cannot observe the brief null window while a write swaps the open vault (most
    // importantly compaction, which closes and re-opens the file). The lock is reentrant.
    private val lock = ReentrantReadWriteLock()

    private var activeFile: File? = null

    private var vault: VaultFile? = null

    /** Resolves a vault file name to its absolute path in app-private storage. */
    fun fileFor(fileName: String): File = File(filesDir, fileName)

    /** Legacy single-vault file from versions before multi-vault. */
    fun legacyFile(): File = File(filesDir, LEGACY_VAULT_FILE_NAME)

    fun current(): VaultFile? = lock.read { vault }

    fun require(): VaultFile = lock.read { vault } ?: error("Vault is locked")

    val isOpen: Boolean get() = lock.read { vault != null }

    /** Runs [block] against the open vault while holding the read lock (safe vs a concurrent swap). */
    fun <T> withVault(block: (VaultFile) -> T): T = lock.read {
        block(vault ?: error("Vault is locked"))
    }

    /** Creates a fresh vault in [file] protected by [password]; opens it active. Returns its DEK. */
    fun create(file: File, password: CharArray): ByteArray = lock.write {
        close()
        val opened = VaultFile.create(LocalFileVaultStore(file), password)
        vault = opened
        activeFile = file
        opened.dataKey
    }

    /** Opens [file] active with a previously-unwrapped [dek] (normal unlock / vault switch). */
    fun openWithDek(file: File, dek: ByteArray) = lock.write {
        close()
        vault = VaultFile.openWithDek(LocalFileVaultStore(file), dek)
        activeFile = file
    }

    /**
     * Verifies [password] against the external [src] FIRST (so a wrong password is harmless),
     * then copies it to [dest], opens it active, and returns its DEK — or null if the password
     * is wrong. Used to import a `.dsvault` as a new vault.
     */
    fun importInto(dest: File, src: File, password: CharArray): ByteArray? = lock.write {
        val dek = try {
            val probe = VaultFile.open(LocalFileVaultStore(src), password)
            val key = probe.dataKey
            probe.close()
            key
        } catch (e: DecryptionException) {
            return@write null
        }
        close()
        src.copyTo(dest, overwrite = true)
        openWithDek(dest, dek)
        dek
    }

    /**
     * Imports [src] using a recovery [code] instead of the password. Verifies the code against
     * [src] FIRST (so a wrong code leaves the current session untouched), which also strikes the
     * used code from [src]; then copies the (now-struck) file to [dest] and opens it active.
     * Returns the DEK, or null if the code does not match.
     */
    fun importIntoWithRecovery(dest: File, src: File, code: CharArray): ByteArray? = lock.write {
        val probe = VaultFile.openWithRecoveryCode(LocalFileVaultStore(src), code) ?: return@write null
        val dek = probe.dataKey
        probe.close()
        close()
        src.copyTo(dest, overwrite = true)
        openWithDek(dest, dek)
        dek
    }

    /** Opens a non-active vault [file] with [dek] as a standalone handle; the caller must close it. */
    fun openTransient(file: File, dek: ByteArray): VaultFile =
        VaultFile.openWithDek(LocalFileVaultStore(file), dek)

    /** Current size of the active vault file on disk, or 0 if none. */
    fun fileSizeBytes(): Long = lock.read { activeFile?.takeIf { it.exists() }?.length() ?: 0L }

    /**
     * Rewrites the active vault, dropping unreferenced blobs to reclaim disk space, then re-opens
     * it with the same key. Must be called while unlocked.
     */
    fun compact(): CompactionResult = lock.write {
        val current = vault ?: error("Vault is locked")
        val file = activeFile ?: error("No active vault file")
        val before = file.length()
        val dek = current.dataKey
        val tempFile = File(file.parentFile, "${file.name}.compact")
        tempFile.delete()
        try {
            current.compactTo(LocalFileVaultStore(tempFile)).close()
            current.close()
            vault = null
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            vault = VaultFile.openWithDek(LocalFileVaultStore(file), dek)
            CompactionResult(before, file.length())
        } finally {
            dek.fill(0)
            tempFile.delete()
        }
    }

    /** Copies the active vault file to [dest] (for export/sharing). */
    fun copyVaultTo(dest: File) = lock.read {
        val file = requireNotNull(activeFile) { "No active vault to export" }
        require(file.exists()) { "No vault to export" }
        file.copyTo(dest, overwrite = true)
        Unit
    }

    fun close() = lock.write {
        vault?.close()
        vault = null
        activeFile = null
    }

    private companion object {
        const val LEGACY_VAULT_FILE_NAME = "vault.dsvault"
    }
}
