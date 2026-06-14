package app.docsafe.vault.model

import kotlinx.serialization.Serializable

/**
 * The broad category of an attachment's blob. [IMAGE] and [PDF] get rich previews; [OTHER]
 * covers any other file type (documents, archives, XML, …) shown with a generic file icon.
 */
@Serializable
enum class AttachmentKind {
    IMAGE, PDF, OTHER;

    companion object {
        /** Classifies an attachment from its MIME type. */
        fun fromMime(mime: String?): AttachmentKind = when {
            mime == "application/pdf" -> PDF
            mime?.startsWith("image/") == true -> IMAGE
            else -> OTHER
        }
    }
}

/**
 * A single file attached to a document. Blobs are content-addressed: [blobId] is the
 * lowercase hex SHA-256 of the plaintext bytes, so attachments referencing identical
 * content share one stored blob.
 */
@Serializable
data class Attachment(
    val id: String,
    val kind: AttachmentKind,
    val blobId: String,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val pageCount: Int? = null,
    /** Content id of a small preview thumbnail blob (also content-addressed), if generated. */
    val thumbnailBlobId: String? = null,
    val addedAt: Long,
)

/**
 * A typed key/value entry attached to a document (e.g. key "Number", value "12345"). [key] is
 * a free-form label chosen from suggestions (Number/Date/Code) or typed by the user.
 */
@Serializable
data class DocField(
    val id: String,
    val key: String,
    val value: String,
)

/**
 * A named document holding key/value [fields] and one or more file [attachments].
 * [modifiedAt]/[modifiedBy] form the logical clock used by the merge engine; [deleted] is the
 * tombstone flag.
 */
@Serializable
data class Document(
    val id: String,
    val folderId: String?,
    val name: String,
    val fields: List<DocField> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val tags: List<String> = emptyList(),
    val starred: Boolean = false,
    val createdAt: Long,
    val modifiedAt: Long,
    val modifiedBy: String,
    val deleted: Boolean = false,
)

/** A virtual folder. [parentId] == null means it lives at the vault root. */
@Serializable
data class Folder(
    val id: String,
    val parentId: String?,
    val name: String,
    val starred: Boolean = false,
    val createdAt: Long,
    val modifiedAt: Long,
    val modifiedBy: String,
    val deleted: Boolean = false,
)

/**
 * Physical location of an encrypted blob within a vault file. These coordinates are
 * file-specific (they describe where the bytes live in *this* file) and therefore are NOT
 * merged across devices the way logical entities are — the sync layer reconciles blob bytes
 * physically by copying any missing content-addressed blobs into the local file.
 */
@Serializable
data class BlobEntry(
    val blobId: String,
    val encOffset: Long,
    val encLength: Long,
    val chunkSize: Int,
    val plaintextSize: Long,
)

/**
 * The decrypted "pseudo filesystem" — the entire structure plus the blob location table.
 * This is what is serialized, encrypted under the DEK, and stored as the vault's index.
 */
@Serializable
data class VaultIndex(
    val schemaVersion: Int = SCHEMA_VERSION,
    val folders: Map<String, Folder> = emptyMap(),
    val documents: Map<String, Document> = emptyMap(),
    val blobs: Map<String, BlobEntry> = emptyMap(),
) {
    /** Blob ids (attachments + their thumbnails) referenced by any non-deleted document. */
    fun referencedBlobIds(): Set<String> =
        documents.values
            .filterNot { it.deleted }
            .flatMap { doc -> doc.attachments.flatMap { listOfNotNull(it.blobId, it.thumbnailBlobId) } }
            .toSet()

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
