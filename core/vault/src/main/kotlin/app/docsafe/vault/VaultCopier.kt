package app.docsafe.vault

import app.docsafe.vault.model.AttachmentKind
import app.docsafe.vault.model.Document
import app.docsafe.vault.model.Folder
import app.docsafe.vault.model.VaultIndex

/**
 * Copies documents and whole folder subtrees from one open [VaultFile] into another. Vaults have
 * independent DEKs, so blobs cannot be copied as ciphertext — each blob (and its thumbnail) is
 * **decrypted from the source and re-encrypted into the destination** via [VaultFile.readBlob] /
 * [VaultFile.putBlob] (content-addressed, so identical content dedupes). Folders are recreated with
 * fresh ids under a destination parent; documents land in the mapped folder. Pure logic — the
 * caller [VaultFile.commit]s the returned maps and supplies id/time/thumbnail helpers.
 */
object VaultCopier {

    data class Result(
        val folders: Map<String, Folder>,
        val documents: Map<String, Document>,
        val copiedDocuments: Int,
    )

    /**
     * @param folderIds source folders to copy (with their entire subtree)
     * @param docIds individually selected source documents to copy
     * @param destParentId destination folder the copy is rooted at (null = destination root)
     * @param thumbnailFor regenerates a thumbnail when the source attachment has none (or returns null)
     */
    fun copy(
        source: VaultFile,
        sourceIndex: VaultIndex,
        dest: VaultFile,
        destFolders: Map<String, Folder>,
        destDocuments: Map<String, Document>,
        folderIds: Set<String>,
        docIds: Set<String>,
        destParentId: String?,
        newId: () -> String,
        now: () -> Long,
        by: String,
        thumbnailFor: (ByteArray, AttachmentKind) -> ByteArray?,
    ): Result {
        val folders = LinkedHashMap(destFolders)
        val documents = LinkedHashMap(destDocuments)
        val folderIdMap = HashMap<String, String>() // source folder id -> new dest folder id

        fun copyFolder(srcFolderId: String, parentId: String?) {
            val src = sourceIndex.folders[srcFolderId] ?: return
            if (src.deleted) return
            val id = newId()
            folderIdMap[srcFolderId] = id
            folders[id] = Folder(
                id = id,
                parentId = parentId,
                name = src.name,
                starred = src.starred,
                createdAt = now(),
                modifiedAt = now(),
                modifiedBy = by,
            )
            sourceIndex.folders.values
                .filter { !it.deleted && it.parentId == srcFolderId }
                .forEach { copyFolder(it.id, id) }
        }
        folderIds.forEach { copyFolder(it, destParentId) }

        // Documents: those explicitly selected, plus every document inside a copied folder.
        val docsToCopy = LinkedHashSet(docIds)
        sourceIndex.documents.values.forEach { doc ->
            if (!doc.deleted && doc.folderId != null && doc.folderId in folderIdMap) docsToCopy.add(doc.id)
        }

        var copied = 0
        for (docId in docsToCopy) {
            val srcDoc = sourceIndex.documents[docId] ?: continue
            if (srcDoc.deleted) continue
            val targetFolder = srcDoc.folderId?.let { folderIdMap[it] } ?: destParentId
            val attachments = srcDoc.attachments.map { att ->
                val bytes = source.readBlob(att.blobId)
                val newBlobId = dest.putBlob(bytes)
                val thumbId = att.thumbnailBlobId
                    ?.let { tid -> runCatching { source.readBlob(tid) }.getOrNull()?.let { dest.putBlob(it) } }
                    ?: thumbnailFor(bytes, att.kind)?.let { dest.putBlob(it) }
                att.copy(id = newId(), blobId = newBlobId, thumbnailBlobId = thumbId)
            }
            val id = newId()
            documents[id] = Document(
                id = id,
                folderId = targetFolder,
                name = srcDoc.name,
                fields = srcDoc.fields.map { it.copy(id = newId()) },
                attachments = attachments,
                tags = srcDoc.tags,
                starred = srcDoc.starred,
                createdAt = now(),
                modifiedAt = now(),
                modifiedBy = by,
            )
            copied++
        }
        return Result(folders, documents, copied)
    }
}
