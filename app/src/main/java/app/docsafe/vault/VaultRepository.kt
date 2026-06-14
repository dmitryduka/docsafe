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
import app.docsafe.vault.store.LocalFileVaultStore
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
        vault.commitFolder(idx, id) { it.copy(name = name.trim()) }
    }

    /** Tombstones a folder and everything beneath it (subfolders + their documents). */
    suspend fun deleteFolder(id: String) = mutateUnit { vault, idx ->
        val folderIds = idx.descendantFolderIds(id, includeDeleted = true)
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
        vault.commitDocument(idx, id) { it.copy(name = name.trim()) }
    }

    suspend fun deleteDocument(id: String) = mutateUnit { vault, idx ->
        vault.commitDocument(idx, id) { it.copy(deleted = true) }
    }

    /** Moves a document into [newFolderId] (null = vault root). */
    suspend fun moveDocument(id: String, newFolderId: String?) = mutateUnit { vault, idx ->
        val doc = idx.documents[id] ?: return@mutateUnit
        if (doc.folderId == newFolderId) return@mutateUnit
        vault.commitDocument(idx, id) { it.copy(folderId = newFolderId) }
    }

    /**
     * Re-parents a folder under [newParentId] (null = vault root). No-ops if the target is the
     * folder itself or one of its descendants (which would create a cycle / orphan the subtree).
     */
    suspend fun moveFolder(id: String, newParentId: String?) = mutateUnit { vault, idx ->
        val folder = idx.folders[id] ?: return@mutateUnit
        if (folder.parentId == newParentId) return@mutateUnit
        if (newParentId != null && newParentId in idx.descendantFolderIds(id, includeDeleted = true)) return@mutateUnit
        vault.commitFolder(idx, id) { it.copy(parentId = newParentId) }
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
        vault.commitDocument(idx, documentId) { it.copy(attachments = it.attachments.filterNot { a -> a.id == attachmentId }) }
    }

    /** Adds a key/value text field to a document. */
    suspend fun addField(documentId: String, key: String, value: String) {
        addFieldReturningId(documentId, key, value)
    }

    /** Adds a key/value text field and returns the new field's id (or null if the doc is gone). */
    suspend fun addFieldReturningId(documentId: String, key: String, value: String): String? {
        val field = DocField(id = newId(), key = key.trim(), value = value.trim())
        var added = false
        mutateUnit { vault, idx ->
            if (idx.documents[documentId] == null) return@mutateUnit
            added = true
            vault.commitDocument(idx, documentId) { it.copy(fields = it.fields + field) }
        }
        return if (added) field.id else null
    }

    suspend fun removeField(documentId: String, fieldId: String) = mutateUnit { vault, idx ->
        vault.commitDocument(idx, documentId) { it.copy(fields = it.fields.filterNot { f -> f.id == fieldId }) }
    }

    suspend fun addTag(documentId: String, tag: String) = mutateUnit { vault, idx ->
        val doc = idx.documents[documentId] ?: return@mutateUnit
        val clean = tag.trim()
        if (clean.isEmpty() || doc.tags.any { it.equals(clean, ignoreCase = true) }) return@mutateUnit
        vault.commitDocument(idx, documentId) { it.copy(tags = it.tags + clean) }
    }

    suspend fun removeTag(documentId: String, tag: String) = mutateUnit { vault, idx ->
        vault.commitDocument(idx, documentId) { it.copy(tags = it.tags.filterNot { t -> t == tag }) }
    }

    suspend fun setDocumentStarred(documentId: String, starred: Boolean) = mutateUnit { vault, idx ->
        vault.commitDocument(idx, documentId) { it.copy(starred = starred) }
    }

    suspend fun setFolderStarred(folderId: String, starred: Boolean) = mutateUnit { vault, idx ->
        vault.commitFolder(idx, folderId) { it.copy(starred = starred) }
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
        session.withVault { it.readBlob(blobId) }
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
    ): Int = transfer(destVaultId, destFolderId) { _ -> docIds to folderIds }

    /** Merges the entire active vault into [destVaultId] (at [destFolderId], or its root if null). */
    suspend fun mergeActiveInto(destVaultId: String, destFolderId: String?): Int =
        transfer(destVaultId, destFolderId) { srcIndex ->
            val rootFolders = srcIndex.folders.values.filter { !it.deleted && it.parentId == null }.map { it.id }.toSet()
            val rootDocs = srcIndex.documents.values.filter { !it.deleted && it.folderId == null }.map { it.id }.toSet()
            rootDocs to rootFolders
        }

    /**
     * Copies from the active vault into [destVaultId] under [destFolderId]. [select] chooses which
     * (docIds, folderIds) to copy from the source snapshot — taken **inside** the lock so it can't
     * race a concurrent mutation. Re-encrypts every blob + thumbnail; returns documents copied.
     */
    private suspend fun transfer(
        destVaultId: String,
        destFolderId: String?,
        select: (VaultIndex) -> Pair<Set<String>, Set<String>>,
    ): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val source = session.require()
            val srcIndex = source.snapshot()
            val (docIds, folderIds) = select(srcIndex)
            // Items copied into a folder that already holds a same-named item are renamed
            // "<name> from <source vault>" so nothing is silently overwritten or duplicated.
            val sourceVaultName = securityRepository.activeVaultName().orEmpty()
            val rename = { name: String -> appContext.getString(R.string.copied_conflict_suffix, name, sourceVaultName) }
            withVault(destVaultId) { dest ->
                val destIndex = dest.snapshot()
                val result = VaultCopier.copy(
                    source = source,
                    sourceIndex = srcIndex,
                    dest = dest,
                    destFolders = destIndex.folders,
                    destDocuments = destIndex.documents,
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

    /**
     * Exports [folderIds] (with subtrees) and [docIds] from the active vault into a brand-new
     * standalone vault file [dest] protected by [password]. The new vault gets a fresh random DEK;
     * every blob + thumbnail is decrypted from the source and re-encrypted under it. The source
     * vault is unchanged. Returns the number of documents exported.
     */
    suspend fun exportFoldersToNewVault(
        folderIds: Set<String>,
        docIds: Set<String>,
        password: CharArray,
        dest: java.io.File,
    ): Int = withContext(Dispatchers.Default) { // Argon2 for the new password
        mutex.withLock {
            val source = session.require()
            val srcIndex = source.snapshot()
            val newVault = VaultFile.create(LocalFileVaultStore(dest), password)
            try {
                val result = VaultCopier.copy(
                    source = source,
                    sourceIndex = srcIndex,
                    dest = newVault,
                    destFolders = emptyMap(),
                    destDocuments = emptyMap(),
                    folderIds = folderIds,
                    docIds = docIds,
                    destParentId = null,
                    newId = ::newId,
                    now = ::now,
                    by = me(),
                    thumbnailFor = { bytes, kind -> thumbnailGenerator.generateJpeg(bytes, kind) },
                )
                newVault.commit(result.folders, result.documents)
                result.copiedDocuments
            } finally {
                newVault.close()
            }
        }
    }

    /**
     * Generates and stores a fresh set of recovery codes for [vaultId], returning the plaintext
     * codes to show the user **once**. For the active vault the codes are set on the open handle
     * (so a later index write won't clobber the header); other vaults are opened transiently.
     * The heavy Argon2 derivations run off the main thread.
     */
    suspend fun generateRecoveryCodes(vaultId: String): List<String> = withContext(Dispatchers.Default) {
        val codes = RecoveryCodes.generate()
        mutex.withLock {
            val charCodes = codes.map { it.toCharArray() }
            if (vaultId == securityRepository.activeVaultId()) {
                session.require().setRecoveryCodes(charCodes)
            } else {
                withVault(vaultId) { it.setRecoveryCodes(charCodes) }
            }
        }
        codes
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

    /**
     * Looks up document [id], applies [transform], stamps the merge clock (modifiedAt/By), and
     * commits. No-ops if the document is gone. Centralizes the clock-stamp so single-document edits
     * can't forget it.
     */
    private fun VaultFile.commitDocument(idx: VaultIndex, id: String, transform: (Document) -> Document) {
        val doc = idx.documents[id] ?: return
        val updated = transform(doc).copy(modifiedAt = now(), modifiedBy = me())
        commit(idx.folders, idx.documents + (id to updated))
    }

    /** Folder counterpart of [commitDocument]. */
    private fun VaultFile.commitFolder(idx: VaultIndex, id: String, transform: (Folder) -> Folder) {
        val folder = idx.folders[id] ?: return
        val updated = transform(folder).copy(modifiedAt = now(), modifiedBy = me())
        commit(idx.folders + (id to updated), idx.documents)
    }

    private fun newId(): String = UUID.randomUUID().toString()
    private fun now(): Long = System.currentTimeMillis()
    private fun me(): String = securityRepository.deviceId()
}
