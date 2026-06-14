package app.docsafe.ui.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.docsafe.R
import app.docsafe.ocr.OcrCandidate
import app.docsafe.ocr.OcrEngine
import app.docsafe.security.SecurityRepository
import app.docsafe.share.PendingShareStore
import app.docsafe.share.SharedFileRef
import app.docsafe.vault.ThumbnailGenerator
import app.docsafe.vault.VaultRepository
import app.docsafe.vault.activeDocument
import app.docsafe.vault.descendantFolderIds
import app.docsafe.vault.model.Attachment
import app.docsafe.vault.model.AttachmentKind
import app.docsafe.vault.model.Document
import app.docsafe.vault.model.VaultIndex
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class VaultViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: VaultRepository,
    private val securityRepository: SecurityRepository,
    private val thumbnailLoader: ThumbnailLoader,
    private val thumbnailGenerator: ThumbnailGenerator,
    private val pendingShareStore: PendingShareStore,
    private val ocrEngine: OcrEngine,
) : ViewModel() {

    val index: StateFlow<VaultIndex> = repository.index

    /** Files shared into the app awaiting a destination document. */
    val pendingShare: StateFlow<List<SharedFileRef>> = pendingShareStore.files

    private val _compacting = MutableStateFlow(false)
    val compacting: StateFlow<Boolean> = _compacting.asStateFlow()

    // One-shot user-facing message after a compaction (shown in a snackbar, then cleared).
    private val _compactionMessage = MutableStateFlow<String?>(null)
    val compactionMessage: StateFlow<String?> = _compactionMessage.asStateFlow()

    init {
        repository.refresh()
    }

    fun vaultSizeBytes(): Long = repository.vaultSizeBytes()

    fun compactVault() = viewModelScope.launch {
        if (_compacting.value) return@launch
        _compacting.value = true
        _compactionMessage.value = try {
            val result = repository.compact()
            if (result.reclaimedBytes > 0) {
                appContext.getString(R.string.reclaimed_space, formatBytes(result.reclaimedBytes), formatBytes(result.afterBytes))
            } else {
                appContext.getString(R.string.already_compact, formatBytes(result.afterBytes))
            }
        } catch (e: Exception) {
            appContext.getString(R.string.reclaim_failed)
        }
        _compacting.value = false
    }

    fun clearCompactionMessage() {
        _compactionMessage.value = null
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    /**
     * Must be called immediately before launching an external activity (file picker, scanner)
     * so the vault doesn't auto-lock during the round trip and drop the in-flight result.
     */
    fun notifyExternalActivityStarting() {
        securityRepository.notifyExternalActivityStarting()
    }

    fun createFolder(name: String, parentId: String?) = viewModelScope.launch {
        repository.createFolder(name, parentId)
    }

    fun renameFolder(id: String, name: String) = viewModelScope.launch {
        repository.renameFolder(id, name)
    }

    fun deleteFolder(id: String) = viewModelScope.launch {
        repository.deleteFolder(id)
    }

    fun createDocument(name: String, folderId: String?) = viewModelScope.launch {
        repository.createDocument(name, folderId)
    }

    fun renameDocument(id: String, name: String) = viewModelScope.launch {
        repository.renameDocument(id, name)
    }

    fun deleteDocument(id: String) = viewModelScope.launch {
        repository.deleteDocument(id)
    }

    fun moveDocument(id: String, newFolderId: String?) = viewModelScope.launch {
        repository.moveDocument(id, newFolderId)
    }

    fun moveFolder(id: String, newParentId: String?) = viewModelScope.launch {
        repository.moveFolder(id, newParentId)
    }

    fun addAttachment(
        documentId: String,
        bytes: ByteArray,
        kind: AttachmentKind,
        fileName: String,
        mimeType: String? = null,
        pageCount: Int? = null,
    ) = viewModelScope.launch {
        repository.addAttachment(documentId, bytes, kind, fileName, mimeType, pageCount)
    }

    fun removeAttachment(documentId: String, attachmentId: String) = viewModelScope.launch {
        repository.removeAttachment(documentId, attachmentId)
    }

    // --- Files shared into the app -----------------------------------------------------

    fun clearPendingShare() = pendingShareStore.clear()

    /** Attaches the pending shared files to an existing document, then clears the share. */
    fun attachPendingShareTo(documentId: String) = viewModelScope.launch {
        attachPending(documentId)
    }

    /** Creates a document in [folderId], attaches the pending shared files, then clears. */
    fun createDocumentAndAttachShare(name: String, folderId: String?) = viewModelScope.launch {
        val id = repository.createDocument(name, folderId)
        attachPending(id)
    }

    private suspend fun attachPending(documentId: String) {
        val files = pendingShareStore.current()
        withContext(Dispatchers.IO) {
            files.forEach { ref ->
                val bytes = runCatching { ref.file.readBytes() }.getOrNull() ?: return@forEach
                repository.addAttachment(documentId, bytes, AttachmentKind.fromMime(ref.mime), ref.name, ref.mime)
            }
        }
        pendingShareStore.clear()
    }

    // --- Multiple vaults ---------------------------------------------------------------

    private val _vaults = MutableStateFlow(securityRepository.vaults())
    val vaultList: StateFlow<List<app.docsafe.security.VaultMeta>> = _vaults.asStateFlow()
    private val _activeVaultId = MutableStateFlow(securityRepository.activeVaultId())
    val activeVaultId: StateFlow<String?> = _activeVaultId.asStateFlow()

    private fun refreshVaults() {
        _vaults.value = securityRepository.vaults()
        _activeVaultId.value = securityRepository.activeVaultId()
    }

    fun activeVaultName(): String? = securityRepository.activeVaultName()

    /** Whether biometric unlock is configured — drives the step-up confirmation method. */
    val biometricEnabled: Boolean get() = securityRepository.biometricEnabled

    /** Verifies the app PIN for a step-up confirmation (Argon2 off the main thread). */
    suspend fun verifyPin(pin: CharArray): Boolean =
        withContext(Dispatchers.Default) { securityRepository.verifyPin(pin) }

    /** Seamless switch (no re-auth): uses the master key already in memory, then reloads the UI. */
    fun switchToVault(id: String) = viewModelScope.launch {
        withContext(Dispatchers.Default) { securityRepository.switchToVault(id) }
        repository.refresh()
        refreshVaults()
    }

    /** Creates and switches to a new vault (Argon2 runs off the main thread); suspends until ready. */
    suspend fun createVault(name: String, password: CharArray) {
        withContext(Dispatchers.Default) { securityRepository.createVault(name, password) }
        repository.refresh()
        refreshVaults()
    }

    /** Imports [file] as a new vault. Returns true on success (false = wrong password). */
    suspend fun importVault(name: String, file: java.io.File, password: CharArray): Boolean =
        withContext(Dispatchers.Default) {
            securityRepository.importVault(name, file, password).also {
                if (it) { repository.refresh(); refreshVaults() }
            }
        }

    fun removeVault(id: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { securityRepository.removeVault(id) }
        repository.refresh()
        refreshVaults()
    }

    fun changeVaultPassword(newPassword: CharArray) = viewModelScope.launch {
        withContext(Dispatchers.Default) { securityRepository.changeActiveVaultPassword(newPassword) }
    }

    /** Snapshot of another vault, to browse it as a copy/merge destination. */
    suspend fun vaultIndexOf(id: String): VaultIndex = repository.vaultIndexOf(id)

    suspend fun copyToVault(destVaultId: String, docIds: Set<String>, folderIds: Set<String>, destFolderId: String?): Int =
        repository.copyToVault(destVaultId, docIds, folderIds, destFolderId)

    suspend fun mergeActiveInto(destVaultId: String, destFolderId: String?): Int =
        repository.mergeActiveInto(destVaultId, destFolderId)

    fun addTag(documentId: String, tag: String) = viewModelScope.launch { repository.addTag(documentId, tag) }
    fun removeTag(documentId: String, tag: String) = viewModelScope.launch { repository.removeTag(documentId, tag) }
    fun setDocumentStarred(documentId: String, starred: Boolean) =
        viewModelScope.launch { repository.setDocumentStarred(documentId, starred) }
    fun setFolderStarred(folderId: String, starred: Boolean) =
        viewModelScope.launch { repository.setFolderStarred(folderId, starred) }

    fun addField(documentId: String, key: String, value: String) = viewModelScope.launch {
        repository.addField(documentId, key, value)
    }

    /** Adds a field and returns its id, so callers (batch mode) can offer an undo. */
    suspend fun addFieldReturningId(documentId: String, key: String, value: String): String? =
        repository.addFieldReturningId(documentId, key, value)

    fun removeField(documentId: String, fieldId: String) = viewModelScope.launch {
        repository.removeField(documentId, fieldId)
    }

    suspend fun removeFieldAwait(documentId: String, fieldId: String) =
        repository.removeField(documentId, fieldId)

    suspend fun readBlob(blobId: String): ByteArray = repository.readBlob(blobId)

    suspend fun exportVaultCopy(dest: java.io.File) = repository.exportVaultCopy(dest)

    /** Loads (and caches) a preview thumbnail for an attachment, or null if none applies. */
    suspend fun thumbnail(attachment: Attachment): ImageBitmap? = thumbnailLoader.load(attachment)

    /** Decrypts and decodes a full-resolution (oriented) image for the in-app viewer. */
    suspend fun fullImage(blobId: String): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching { thumbnailGenerator.decodeOriented(repository.readBlob(blobId), FULL_IMAGE_MAX_PX) }
            .getOrNull()
            ?.asImageBitmap()
    }

    // --- On-device OCR / field extraction ----------------------------------------------

    /** Decodes an oriented bitmap sized for OCR (kept large enough that small print is legible). */
    suspend fun decodeForOcr(blobId: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { thumbnailGenerator.decodeOriented(repository.readBlob(blobId), OCR_MAX_PX) }.getOrNull()
    }

    /** Auto-detects field-like numbers/codes across the whole bitmap. */
    suspend fun detectFields(bitmap: Bitmap): List<OcrCandidate> =
        runCatching { ocrEngine.detectFields(bitmap) }.getOrDefault(emptyList())

    /** OCRs a user-drawn region of the bitmap (manual selection fallback). */
    suspend fun recognizeRegion(bitmap: Bitmap, region: Rect): OcrCandidate? =
        runCatching { ocrEngine.recognizeRegion(bitmap, region) }.getOrNull()

    /** Image-bearing documents in [folderId] and all of its subfolders (root = whole vault). */
    fun batchDocuments(folderId: String?): List<Document> {
        val idx = index.value
        val scope: Set<String>? = folderId?.let { idx.descendantFolderIds(it) }
        return idx.documents.values
            .filter { doc ->
                !doc.deleted &&
                    doc.attachments.any { it.kind == AttachmentKind.IMAGE } &&
                    (scope == null || doc.folderId in scope)
            }
            .sortedBy { it.name.lowercase() }
    }

    /** Runs OCR over every image attachment of a document and merges de-duplicated candidates. */
    suspend fun detectFieldsForDocument(documentId: String): List<OcrCandidate> {
        val doc = index.value.activeDocument(documentId) ?: return emptyList()
        val out = ArrayList<OcrCandidate>()
        val seen = HashSet<String>()
        doc.attachments.filter { it.kind == AttachmentKind.IMAGE }.forEach { att ->
            val bitmap = decodeForOcr(att.blobId) ?: return@forEach
            try {
                detectFields(bitmap).forEach { c ->
                    if (seen.add(c.text.lowercase().filter { ch -> !ch.isWhitespace() })) out.add(c)
                }
            } finally {
                bitmap.recycle()
            }
        }
        return out
    }

    private companion object {
        const val FULL_IMAGE_MAX_PX = 2560
        const val OCR_MAX_PX = 2560
    }
}
