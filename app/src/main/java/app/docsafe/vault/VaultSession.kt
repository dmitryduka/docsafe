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
 * Holds the single decrypted-while-unlocked [VaultFile] for the app. The portable encrypted
 * `.dsvault` lives in app-private storage; this opens it with a DEK (after unlock) or a
 * password (on import), and closes it when the app locks.
 */
@Singleton
class VaultSession @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val file: File = File(context.filesDir, VAULT_FILE_NAME)

    @Volatile
    private var vault: VaultFile? = null

    fun fileExists(): Boolean = file.exists()

    fun current(): VaultFile? = vault

    fun require(): VaultFile = vault ?: error("Vault is locked")

    val isOpen: Boolean get() = vault != null

    /** Creates a fresh vault protected by [password]; returns its DEK (kept by the caller). */
    fun create(password: CharArray): ByteArray {
        close()
        val opened = VaultFile.create(LocalFileVaultStore(file), password)
        vault = opened
        return opened.dataKey
    }

    /** Opens the existing vault with a previously-unwrapped [dek] (normal unlock). */
    fun openWithDek(dek: ByteArray) {
        close()
        vault = VaultFile.openWithDek(LocalFileVaultStore(file), dek)
    }

    /** Opens the existing vault with the master [password] (import / recovery); returns its DEK. */
    fun openWithPassword(password: CharArray): ByteArray {
        close()
        val opened = VaultFile.open(LocalFileVaultStore(file), password)
        vault = opened
        return opened.dataKey
    }

    /** Current size of the vault file on disk, or 0 if it doesn't exist. */
    fun fileSizeBytes(): Long = if (file.exists()) file.length() else 0L

    /**
     * Rewrites the vault, dropping unreferenced blobs (garbage left by removed attachments
     * and superseded indexes) to reclaim disk space. The open vault is replaced in place and
     * re-opened with the same key. Must be called while unlocked.
     */
    fun compact(): CompactionResult {
        val current = vault ?: error("Vault is locked")
        val before = file.length()
        val dek = current.dataKey
        val tempFile = File(file.parentFile, "$VAULT_FILE_NAME.compact")
        tempFile.delete()
        try {
            // Read referenced blobs from the live file into a fresh one (ciphertext copied
            // verbatim — position-independent — so no re-encryption), then swap it in.
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

    /** Copies the current vault file to [dest] (for export/sharing). */
    fun copyVaultTo(dest: File) {
        require(file.exists()) { "No vault to export" }
        file.copyTo(dest, overwrite = true)
    }

    /** Replaces the local vault file with [src] (an imported `.dsvault`). Closes any open vault. */
    fun replaceWith(src: File) {
        close()
        src.copyTo(file, overwrite = true)
    }

    /**
     * Imports an external `.dsvault` [src]: verifies [password] against it FIRST (so a wrong
     * password can't destroy the existing local vault), then adopts it as the device vault and
     * opens it. Returns the DEK on success, or null if the password is wrong.
     */
    fun importExternal(src: File, password: CharArray): ByteArray? {
        val dek = try {
            val probe = VaultFile.open(LocalFileVaultStore(src), password)
            val key = probe.dataKey
            probe.close()
            key
        } catch (e: DecryptionException) {
            return null
        }
        replaceWith(src)
        openWithDek(dek)
        return dek
    }

    fun close() {
        vault?.close()
        vault = null
    }

    private companion object {
        const val VAULT_FILE_NAME = "vault.dsvault"
    }
}
