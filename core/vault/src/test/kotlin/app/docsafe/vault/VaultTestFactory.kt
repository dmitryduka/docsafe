package app.docsafe.vault

import app.docsafe.vault.model.Attachment
import app.docsafe.vault.model.AttachmentKind
import app.docsafe.vault.model.Document

/** Shared builders for vault tests. */
object VaultTestFactory {

    fun document(
        id: String,
        name: String,
        blobId: String,
        plaintextSize: Long,
        t: Long = 1L,
        by: String = "test-device",
        deleted: Boolean = false,
    ): Document = Document(
        id = id,
        folderId = null,
        name = name,
        attachments = listOf(
            Attachment(
                id = "att-$id",
                kind = AttachmentKind.IMAGE,
                blobId = blobId,
                fileName = "$name.jpg",
                sizeBytes = plaintextSize,
                addedAt = t,
            ),
        ),
        createdAt = t,
        modifiedAt = t,
        modifiedBy = by,
        deleted = deleted,
    )
}
