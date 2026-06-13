package app.docsafe.vault

import app.docsafe.crypto.KdfParams
import app.docsafe.vault.model.Attachment
import app.docsafe.vault.model.AttachmentKind
import app.docsafe.vault.model.Document
import app.docsafe.vault.model.Folder
import app.docsafe.vault.store.InMemoryVaultStore
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VaultCopierTest {

    private fun fastKdf() = KdfParams(memoryKib = 1024, iterations = 1, parallelism = 1, salt = ByteArray(16) { 5 })

    /** A source vault: folder "Travel" with subfolder "Sub"; a doc (with image + thumbnail) in each. */
    private fun buildSource(): VaultFile {
        val v = VaultFile.create(InMemoryVaultStore(), "p".toCharArray(), fastKdf())
        val img = "IMAGE-BYTES".toByteArray()
        val thumb = "THUMB-BYTES".toByteArray()
        val blobId = v.putBlob(img)
        val thumbId = v.putBlob(thumb)
        val att = Attachment(
            id = "att1", kind = AttachmentKind.IMAGE, blobId = blobId, fileName = "scan.jpg",
            sizeBytes = img.size.toLong(), mimeType = "image/jpeg", thumbnailBlobId = thumbId, addedAt = 1L,
        )
        val travel = Folder("f1", null, "Travel", false, 1L, 1L, "src")
        val sub = Folder("f2", "f1", "Sub", false, 1L, 1L, "src")
        val docTop = Document("d1", "f1", "Boarding", attachments = listOf(att), tags = listOf("trip"), createdAt = 1L, modifiedAt = 1L, modifiedBy = "src")
        val docSub = Document("d2", "f2", "Hotel", createdAt = 1L, modifiedAt = 1L, modifiedBy = "src")
        v.commit(mapOf("f1" to travel, "f2" to sub), mapOf("d1" to docTop, "d2" to docSub))
        return v
    }

    @Test
    fun copiesFolderSubtreeWithBlobsAndThumbnails() {
        val source = buildSource()
        val dest = VaultFile.create(InMemoryVaultStore(), "q".toCharArray(), fastKdf())
        var seq = 0

        val result = VaultCopier.copy(
            source = source, sourceIndex = source.snapshot(), dest = dest,
            destFolders = dest.snapshot().folders, destDocuments = dest.snapshot().documents,
            folderIds = setOf("f1"), docIds = emptySet(), destParentId = null,
            newId = { "n${seq++}" }, now = { 100L }, by = "dev", thumbnailFor = { _, _ -> null },
        )
        dest.commit(result.folders, result.documents)

        // Two folders recreated with the right structure.
        assertThat(result.folders.values.map { it.name }).containsExactly("Travel", "Sub")
        val travel = result.folders.values.first { it.name == "Travel" }
        val sub = result.folders.values.first { it.name == "Sub" }
        assertThat(travel.parentId).isNull()
        assertThat(sub.parentId).isEqualTo(travel.id)
        assertThat(travel.id).isNotEqualTo("f1") // fresh ids

        // Both documents copied into the mapped folders.
        assertThat(result.copiedDocuments).isEqualTo(2)
        val boarding = result.documents.values.first { it.name == "Boarding" }
        val hotel = result.documents.values.first { it.name == "Hotel" }
        assertThat(boarding.folderId).isEqualTo(travel.id)
        assertThat(hotel.folderId).isEqualTo(sub.id)
        assertThat(boarding.tags).containsExactly("trip")

        // Attachment blob + thumbnail are readable from the destination (re-encrypted under dest's DEK).
        val att = boarding.attachments.single()
        assertThat(dest.readBlob(att.blobId)).isEqualTo("IMAGE-BYTES".toByteArray())
        assertThat(att.thumbnailBlobId).isNotNull()
        assertThat(dest.readBlob(att.thumbnailBlobId!!)).isEqualTo("THUMB-BYTES".toByteArray())

        // Source is unchanged.
        assertThat(source.snapshot().folders.keys).containsExactly("f1", "f2")
        assertThat(source.snapshot().documents.keys).containsExactly("d1", "d2")

        source.close(); dest.close()
    }

    @Test
    fun copiesSelectedDocumentIntoDestinationFolder() {
        val source = buildSource()
        val dest = VaultFile.create(InMemoryVaultStore(), "q".toCharArray(), fastKdf())
        // Pre-existing destination folder to copy into.
        val destFolder = Folder("df", null, "Inbox", false, 1L, 1L, "dev")
        dest.commit(mapOf("df" to destFolder), emptyMap())
        var seq = 0

        val result = VaultCopier.copy(
            source = source, sourceIndex = source.snapshot(), dest = dest,
            destFolders = dest.snapshot().folders, destDocuments = dest.snapshot().documents,
            folderIds = emptySet(), docIds = setOf("d1"), destParentId = "df",
            newId = { "m${seq++}" }, now = { 100L }, by = "dev", thumbnailFor = { _, _ -> null },
        )
        dest.commit(result.folders, result.documents)

        assertThat(result.copiedDocuments).isEqualTo(1)
        assertThat(result.folders.keys).containsExactly("df") // no new folders
        val copied = result.documents.values.first { it.name == "Boarding" }
        assertThat(copied.folderId).isEqualTo("df")
        assertThat(dest.readBlob(copied.attachments.single().blobId)).isEqualTo("IMAGE-BYTES".toByteArray())

        source.close(); dest.close()
    }
}
