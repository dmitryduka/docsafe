package app.docsafe.vault

import com.google.common.truth.Truth.assertThat
import app.docsafe.crypto.DecryptionException
import app.docsafe.crypto.KdfParams
import app.docsafe.vault.store.InMemoryVaultStore
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.function.ThrowingRunnable

class VaultFileTest {

    // Cheap KDF so tests stay fast; the format is identical regardless of cost params.
    private fun fastKdf() = KdfParams(memoryKib = 1024, iterations = 1, parallelism = 1, salt = ByteArray(16) { 9 })

    @Test
    fun createPutCommitAndReopenRoundTrips() {
        val store = InMemoryVaultStore()
        val photo = ByteArray(5000) { (it % 255).toByte() }

        val vault = VaultFile.create(store, "hunter2".toCharArray(), fastKdf())
        val blobId = vault.putBlob(photo)
        val doc = VaultTestFactory.document("d1", "Passport", blobId, photo.size.toLong())
        vault.commit(folders = emptyMap(), documents = mapOf(doc.id to doc))

        // Reopen from the same bytes with the password.
        val reopened = VaultFile.open(InMemoryVaultStore(store.toByteArray()), "hunter2".toCharArray())
        assertThat(reopened.snapshot().documents.keys).containsExactly("d1")
        assertThat(reopened.readBlob(blobId)).isEqualTo(photo)
    }

    @Test
    fun wrongPasswordFailsToOpen() {
        val store = InMemoryVaultStore()
        VaultFile.create(store, "correct".toCharArray(), fastKdf())
        assertThrows(DecryptionException::class.java, ThrowingRunnable {
            VaultFile.open(InMemoryVaultStore(store.toByteArray()), "incorrect".toCharArray())
        })
    }

    @Test
    fun opensWithDekWithoutPassword() {
        val store = InMemoryVaultStore()
        val vault = VaultFile.create(store, "pw".toCharArray(), fastKdf())
        val photo = ByteArray(1234) { it.toByte() }
        val blobId = vault.putBlob(photo)
        vault.commit(emptyMap(), emptyMap())
        val dek = vault.dataKey

        val viaDek = VaultFile.openWithDek(InMemoryVaultStore(store.toByteArray()), dek)
        assertThat(viaDek.readBlob(blobId)).isEqualTo(photo)
    }

    @Test
    fun identicalContentIsDeduplicated() {
        val store = InMemoryVaultStore()
        val vault = VaultFile.create(store, "pw".toCharArray(), fastKdf())
        val photo = ByteArray(2000) { (it % 7).toByte() }

        val id1 = vault.putBlob(photo)
        val sizeAfterFirst = store.size()
        val id2 = vault.putBlob(photo)
        assertThat(id2).isEqualTo(id1)
        // No new blob bytes were appended for identical content.
        assertThat(store.size()).isEqualTo(sizeAfterFirst)
    }

    @Test
    fun appendingDoesNotMoveExistingBlobs() {
        val store = InMemoryVaultStore()
        val vault = VaultFile.create(store, "pw".toCharArray(), fastKdf())

        val first = ByteArray(3000) { it.toByte() }
        val firstId = vault.putBlob(first)
        val firstOffset = vault.snapshot().blobs[firstId]!!.encOffset
        vault.commit(emptyMap(), emptyMap())

        // Add another blob + commit a new index. The first blob must not have moved.
        val second = ByteArray(4000) { (it * 2).toByte() }
        val secondId = vault.putBlob(second)
        vault.commit(emptyMap(), emptyMap())

        assertThat(vault.snapshot().blobs[firstId]!!.encOffset).isEqualTo(firstOffset)
        assertThat(vault.readBlob(firstId)).isEqualTo(first)
        assertThat(vault.readBlob(secondId)).isEqualTo(second)
    }
}
