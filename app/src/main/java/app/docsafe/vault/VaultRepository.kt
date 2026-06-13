package app.docsafe.vault

import android.content.Context
import app.docsafe.R
import app.docsafe.security.SecurityRepository
import app.docsafe.vault.model.Attachment
import app.docsafe.vault.model.AttachmentKind
import app.docsafe.vault.model.DocField
import app.docsafe.vault.model.Document
import app.docsafe.vault.model.Folder
import app.docsafe.vault.model.VaultIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's view of the unlocked vault. Reads come from an in-memory [VaultIndex] snapshot
 * (exposed as a [StateFlow] the UI observes); writes mutate the open [VaultFile], which
 * appends to the encrypted file and rewrites only the small index, then refresh the snapshot.
 *
 * All file I/O runs on [Dispatchers.IO]. Mutations are cheap (AES-GCM on the index + an
 * append) — no password KDF is involved.
 */
@Singleton
class VaultRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val session: VaultSession,
    private val securityRepository: SecurityRepository,
    private val thumbnailGenerator: ThumbnailGenerator,
) {
    private val _index = MutableStateFlow(VaultIndex())
    val index: StateFlow<VaultIndex> = _index.asStateFlow()

    // Serializes all writes to the (non-thread-safe) open VaultFile.
    private val mutex = Mutex()

    /** Loads the current snapshot after unlock. Call when entering the document UI. */
    fun refresh() {
        session.current()?.let { _index.value = it.snapshot() }
    }

    fun foldersIn(parentId: String?): List<Folder> =
        _index.value.folders.values
            .filter { !it.deleted && it.parentId == parentId }
            .sortedBy { it.name.lowercase() }

    fun documentsIn(folderId: String?): List<Document> =
        _index.value.documents.values
            .filter { !it.deleted && it.folderId == folderId }
            .sortedBy { it.name.lowercase() }

    fun folder(id: String): Folder? = _index.value.folders[id]?.takeUnless { it.deleted }

    fun document(id: String): Document? = _index.value.documents[id]?.takeUnless { it.deleted }

    suspend fun createFolder(name: String, parentId: String?): String = mutate { vault, idx ->
        val folder = Folder(
            id = newId(),
            parentId = parentId,
            name = name.trim(),
            createdAt = now(),
            modifiedAt = now(),
            modifiedBy = me(),
        )
        vault.commit(idx.folders + (folder.id to folder), idx.documents)
        folder.id
    }

    suspend fun renameFolder(id: String, name: String) = mutateUnit { vault, idx ->
        val folder = idx.folders[id] ?: return@mutateUnit
        val updated = folder.copy(name = name.trim(), modifiedAt = now(), modifiedBy = me())
        vault.commit(idx.folders + (id to updated), idx.documents)
    }

    /** Tombstones a folder and everything beneath it (subfolders + their documents). */
    suspend fun deleteFolder(id: String) = mutateUnit { vault, idx ->
        val folderIds = collectSubtree(idx, id)
        val ts = now()
        val folders = idx.folders.mapValues { (fid, f) ->
            if (fid in folderIds) f.copy(deleted = true, modifiedAt = ts, modifiedBy = me()) else f
        }
        val documents = idx.documents.mapValues { (_, d) ->
            if (d.folderId in folderIds) d.copy(deleted = true, modifiedAt = ts, modifiedBy = me()) else d
        }
        vault.commit(folders, documents)
    }

    suspend fun createDocument(name: String, folderId: String?): String = mutate { vault, idx ->
        val doc = Document(
            id = newId(),
            folderId = folderId,
            name = name.trim(),
            attachments = emptyList(),
            createdAt = now(),
            modifiedAt = now(),
            modifiedBy = me(),
        )
        vault.commit(idx.folders, idx.documents + (doc.id to doc))
        doc.id
    }

    suspend fun renameDocument(id: String, name: String) = mutateUnit { vault, idx ->
        val doc = idx.documents[id] ?: return@mutateUnit
        val updated = doc.copy(name = name.trim(), modifiedAt = now(), modifiedBy = me())
        vault.commit(idx.folders, idx.documents + (id to updated))
    }

    suspend fun deleteDocument(id: String) = mutateUnit { vault, idx ->
        val doc = idx.documents[id] ?: return@mutateUnit
        val updated = doc.copy(deleted = true, modifiedAt = now(), modifiedBy = me())
        vault.commit(idx.folders, idx.documents + (id to updated))
    }

    /** Moves a document into [newFolderId] (null = vault root). */
    suspend fun moveDocument(id: String, newFolderId: String?) = mutateUnit { vault, idx ->
        val doc = idx.documents[id] ?: return@mutateUnit
        if (doc.folderId == newFolderId) return@mutateUnit
        val updated = doc.copy(folderId = newFolderId, modifiedAt = now(), modifiedBy = me())
        vault.commit(idx.folders, idx.documents + (id to updated))
    }

    /**
     * Re-parents a folder under [newParentId] (null = vault root). No-ops if the target is the
     * folder itself or one of its descendants (which would create a cycle / orphan the subtree).
     */
    suspend fun moveFolder(id: String, newParentId: String?) = mutateUnit { vault, idx ->
        val folder = idx.folders[id] ?: return@mutateUnit
        if (folder.parentId == newParentId) return@mutateUnit
        if (newParentId != null && newParentId in collectSubtree(idx, id)) return@mutateUnit
        val updated = folder.copy(parentId = newParentId, modifiedAt = now(), modifiedBy = me())
        vault.commit(idx.folders + (id to updated), idx.documents)
    }

    /** Adds an attachment to a document, storing [bytes] as a content-addressed blob. */
    suspend fun addAttachment(
        documentId: String,
        bytes: ByteArray,
        kind: AttachmentKind,
        fileName: String,
        mimeType: String? = null,
        pageCount: Int? = null,
    ) = mutateUnit { vault, _ ->
        val blobId = vault.putBlob(bytes)
        // Bake a small preview thumbnail into the vault as its own content-addressed blob, so
        // browsing only needs to read the tiny thumbnail rather than decrypt the full file.
        val thumbnailBlobId = thumbnailGenerator.generateJpeg(bytes, kind)?.let { vault.putBlob(it) }
        val idx = vault.snapshot() // now includes the new blob entries
        val doc = idx.documents[documentId] ?: return@mutateUnit
        val attachment = Attachment(
            id = newId(),
            kind = kind,
            blobId = blobId,
            fileName = fileName,
            sizeBytes = bytes.size.toLong(),
            mimeType = mimeType,
            pageCount = pageCount,
            thumbnailBlobId = thumbnailBlobId,
            addedAt = now(),
        )
        val updated = doc.copy(
            attachments = doc.attachments + attachment,
            modifiedAt = now(),
            modifiedBy = me(),
        )
        vault.commit(idx.folders, idx.documents + (documentId to updated))
    }

    suspend fun removeAttachment(documentId: String, attachmentId: String) = mutateUnit { vault, idx ->
        val doc = idx.documents[documentId] ?: return@mutateUnit
        val updated = doc.copy(
            attachments = doc.attachments.filterNot { it.id == attachmentId },
            modifiedAt = now(),
            modifiedBy = me(),
        )
        vault.commit(idx.folders, idx.documents + (documentId to updated))
    }

    /** Adds a key/value text field to a document. */
    suspend fun addField(documentId: String, key: String, value: String) {
        addFieldReturningId(documentId, key, value)
    }

    /** Adds a key/value text field and returns the new field's id (or null if the doc is gone). */
    suspend fun addFieldReturningId(documentId: String, key: String, value: String): String? {
        var newFieldId: String? = null
        mutateUnit { vault, idx ->
            val doc = idx.documents[documentId] ?: return@mutateUnit
            val field = DocField(id = newId(), key = key.trim(), value = value.trim())
            newFieldId = field.id
            val updated = doc.copy(fields = doc.fields + field, modifiedAt = now(), modifiedBy = me())
            vault.commit(idx.folders, idx.documents + (documentId to updated))
        }
        return newFieldId
    }

    suspend fun removeField(documentId: String, fieldId: String) = mutateUnit { vault, idx ->
        val doc = idx.documents[documentId] ?: return@mutateUnit
        val updated = doc.copy(
            fields = doc.fields.filterNot { it.id == fieldId },
            modifiedAt = now(),
            modifiedBy = me(),
        )
        vault.commit(idx.folders, idx.documents + (documentId to updated))
    }

    suspend fun addTag(documentId: String, tag: String) = mutateUnit { vault, idx ->
        val doc = idx.documents[documentId] ?: return@mutateUnit
        val clean = tag.trim()
        if (clean.isEmpty() || doc.tags.any { it.equals(clean, ignoreCase = true) }) return@mutateUnit
        val updated = doc.copy(tags = doc.tags + clean, modifiedAt = now(), modifiedBy = me())
        vault.commit(idx.folders, idx.documents + (documentId to updated))
    }

    suspend fun removeTag(documentId: String, tag: String) = mutateUnit { vault, idx ->
        val doc = idx.documents[documentId] ?: return@mutateUnit
        val updated = doc.copy(tags = doc.tags.filterNot { it == tag }, modifiedAt = now(), modifiedBy = me())
        vault.commit(idx.folders, idx.documents + (documentId to updated))
    }

    suspend fun setDocumentStarred(documentId: String, starred: Boolean) = mutateUnit { vault, idx ->
        val doc = idx.documents[documentId] ?: return@mutateUnit
        val updated = doc.copy(starred = starred, modifiedAt = now(), modifiedBy = me())
        vault.commit(idx.folders, idx.documents + (documentId to updated))
    }

    suspend fun setFolderStarred(folderId: String, starred: Boolean) = mutateUnit { vault, idx ->
        val folder = idx.folders[folderId] ?: return@mutateUnit
        val updated = folder.copy(starred = starred, modifiedAt = now(), modifiedBy = me())
        vault.commit(idx.folders + (folderId to updated), idx.documents)
    }

    /**
     * Returns thumbnail JPEG bytes for [attachment]. If the thumbnail is missing, it is
     * generated from the full blob and saved back into the vault so it persists.
     */
    suspend fun thumbnailBytes(attachment: Attachment): ByteArray? {
        attachment.thumbnailBlobId?.let { return readBlob(it) }
        val jpeg = thumbnailGenerator.generateJpeg(readBlob(attachment.blobId), attachment.kind) ?: return null
        mutate { vault, idx ->
            val thumbId = vault.putBlob(jpeg)
            val doc = idx.documents.values.firstOrNull { d -> d.attachments.any { it.id == attachment.id } }
            if (doc != null) {
                // Backfill the link without bumping the merge clock — it's a derived value.
                val atts = doc.attachments.map { if (it.id == attachment.id) it.copy(thumbnailBlobId = thumbId) else it }
                vault.commit(idx.folders, idx.documents + (doc.id to doc.copy(attachments = atts)))
            }
        }
        return jpeg
    }

    /** Decrypts a single blob (seeks to its range; never reads the whole file). */
    suspend fun readBlob(blobId: String): ByteArray = withContext(Dispatchers.IO) {
        session.require().readBlob(blobId)
    }

    /** Current size of the encrypted vault file on disk. */
    fun vaultSizeBytes(): Long = session.fileSizeBytes()

    /** Copies the encrypted vault file to [dest] for export/sharing. */
    suspend fun exportVaultCopy(dest: java.io.File) = withContext(Dispatchers.IO) {
        session.copyVaultTo(dest)
    }

    /** Reclaims space by compacting the vault (drops removed/garbage blobs). */
    suspend fun compact(): CompactionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val result = session.compact()
            _index.value = session.require().snapshot()
            result
        }
    }

    // --- Cross-vault copy / merge ------------------------------------------------------

    /** Snapshot of another (non-active) vault, e.g. to browse it as a copy destination. */
    suspend fun vaultIndexOf(vaultId: String): VaultIndex = withContext(Dispatchers.IO) {
        withVault(vaultId) { it.snapshot() }
    }

    /**
     * Copies [docIds] and [folderIds] (with their subtrees) from the **active** vault into vault
     * [destVaultId] under [destFolderId], re-encrypting every blob + thumbnail. Returns the number
     * of documents copied.
     */
    suspend fun copyToVault(
        destVaultId: String,
        docIds: Set<String>,
        folderIds: Set<String>,
        destFolderId: String?,
    ): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val source = session.require()
            val srcIndex = source.snapshot()
            // Items copied into a folder that already holds a same-named item are renamed
            // "<name> from <source vault>" so nothing is silently overwritten or duplicated.
            val sourceVaultName = securityRepository.activeVaultName().orEmpty()
            val rename = { name: String -> appContext.getString(R.string.copied_conflict_suffix, name, sourceVaultName) }
            withVault(destVaultId) { dest ->
                val result = VaultCopier.copy(
                    source = source,
                    sourceIndex = srcIndex,
                    dest = dest,
                    destFolders = dest.snapshot().folders,
                    destDocuments = dest.snapshot().documents,
                    folderIds = folderIds,
                    docIds = docIds,
                    destParentId = destFolderId,
                    newId = ::newId,
                    now = ::now,
                    by = me(),
                    thumbnailFor = { bytes, kind -> thumbnailGenerator.generateJpeg(bytes, kind) },
                    conflictRename = rename,
                )
                dest.commit(result.folders, result.documents)
                result.copiedDocuments
            }
        }
    }

    /** Merges the entire active vault into [destVaultId] (at [destFolderId], or its root if null). */
    suspend fun mergeActiveInto(destVaultId: String, destFolderId: String?): Int {
        val idx = session.require().snapshot()
        val rootFolders = idx.folders.values.filter { !it.deleted && it.parentId == null }.map { it.id }.toSet()
        val rootDocs = idx.documents.values.filter { !it.deleted && it.folderId == null }.map { it.id }.toSet()
        return copyToVault(destVaultId, rootDocs, rootFolders, destFolderId)
    }

    /** Opens a non-active vault transiently (via its master-key-unwrapped DEK), runs [block], closes it. */
    private inline fun <T> withVault(vaultId: String, block: (VaultFile) -> T): T {
        val fileName = securityRepository.vaults().firstOrNull { it.id == vaultId }?.fileName
            ?: error("Unknown vault $vaultId")
        val dek = securityRepository.dekFor(vaultId)
        val vault = session.openTransient(session.fileFor(fileName), dek)
        dek.fill(0)
        return try {
            block(vault)
        } finally {
            vault.close()
        }
    }

    // --- helpers -----------------------------------------------------------------------

    private suspend fun <T> mutate(block: (VaultFile, VaultIndex) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val vault = session.require()
            val result = block(vault, vault.snapshot())
            _index.value = vault.snapshot()
            result
        }
    }

    private suspend fun mutateUnit(block: (VaultFile, VaultIndex) -> Unit) {
        mutate { vault, idx -> block(vault, idx) }
    }

    private fun collectSubtree(idx: VaultIndex, rootId: String): Set<String> {
        val result = mutableSetOf(rootId)
        var added = true
        while (added) {
            added = false
            for (f in idx.folders.values) {
                if (f.parentId in result && f.id !in result) {
                    result += f.id
                    added = true
                }
            }
        }
        return result
    }

    private fun newId(): String = UUID.randomUUID().toString()
    private fun now(): Long = System.currentTimeMillis()
    private fun me(): String = securityRepository.deviceId()
}
