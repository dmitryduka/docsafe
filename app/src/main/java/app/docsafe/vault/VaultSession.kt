package app.docsafe.vault

import android.content.Context
import app.docsafe.crypto.DecryptionException
import app.docsafe.vault.store.LocalFileVaultStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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

    @Volatile
    private var activeFile: File? = null

    @Volatile
    private var vault: VaultFile? = null

    /** Resolves a vault file name to its absolute path in app-private storage. */
    fun fileFor(fileName: String): File = File(filesDir, fileName)

    /** Legacy single-vault file from versions before multi-vault. */
    fun legacyFile(): File = File(filesDir, LEGACY_VAULT_FILE_NAME)

    fun current(): VaultFile? = vault

    fun require(): VaultFile = vault ?: error("Vault is locked")

    val isOpen: Boolean get() = vault != null

    /** Creates a fresh vault in [file] protected by [password]; opens it active. Returns its DEK. */
    fun create(file: File, password: CharArray): ByteArray {
        close()
        val opened = VaultFile.create(LocalFileVaultStore(file), password)
        vault = opened
        activeFile = file
        return opened.dataKey
    }

    /** Opens [file] active with a previously-unwrapped [dek] (normal unlock / vault switch). */
    fun openWithDek(file: File, dek: ByteArray) {
        close()
        vault = VaultFile.openWithDek(LocalFileVaultStore(file), dek)
        activeFile = file
    }

    /**
     * Verifies [password] against the external [src] FIRST (so a wrong password is harmless),
     * then copies it to [dest], opens it active, and returns its DEK — or null if the password
     * is wrong. Used to import a `.dsvault` as a new vault.
     */
    fun importInto(dest: File, src: File, password: CharArray): ByteArray? {
        val dek = try {
            val probe = VaultFile.open(LocalFileVaultStore(src), password)
            val key = probe.dataKey
            probe.close()
            key
        } catch (e: DecryptionException) {
            return null
        }
        close()
        src.copyTo(dest, overwrite = true)
        openWithDek(dest, dek)
        return dek
    }

    /** Opens a non-active vault [file] with [dek] as a standalone handle; the caller must close it. */
    fun openTransient(file: File, dek: ByteArray): VaultFile =
        VaultFile.openWithDek(LocalFileVaultStore(file), dek)

    /** Current size of the active vault file on disk, or 0 if none. */
    fun fileSizeBytes(): Long = activeFile?.takeIf { it.exists() }?.length() ?: 0L

    /**
     * Rewrites the active vault, dropping unreferenced blobs to reclaim disk space, then re-opens
     * it with the same key. Must be called while unlocked.
     */
    fun compact(): CompactionResult {
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
            return CompactionResult(before, file.length())
        } finally {
            dek.fill(0)
            tempFile.delete()
        }
    }

    /** Copies the active vault file to [dest] (for export/sharing). */
    fun copyVaultTo(dest: File) {
        val file = requireNotNull(activeFile) { "No active vault to export" }
        require(file.exists()) { "No vault to export" }
        file.copyTo(dest, overwrite = true)
    }

    fun close() {
        vault?.close()
        vault = null
        activeFile = null
    }

    private companion object {
        const val LEGACY_VAULT_FILE_NAME = "vault.dsvault"
    }
}
