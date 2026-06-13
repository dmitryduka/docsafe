package app.docsafe.vault

import com.google.common.truth.Truth.assertThat
import app.docsafe.crypto.KdfParams
import app.docsafe.vault.store.InMemoryVaultStore
import org.junit.Test

class VaultCompactionTest {

    private fun fastKdf() = KdfParams(memoryKib = 1024, iterations = 1, parallelism = 1, salt = ByteArray(16) { 5 })

    @Test
    fun compactionDropsUnreferencedBlobsButKeepsReferencedOnes() {
        val store = InMemoryVaultStore()
        val vault = VaultFile.create(store, "pw".toCharArray(), fastKdf())

        val keepBytes = ByteArray(40_000) { it.toByte() }
        val dropBytes = ByteArray(60_000) { (it * 3).toByte() }
        val keepId = vault.putBlob(keepBytes)
        val dropId = vault.putBlob(dropBytes)

        val keepDoc = VaultTestFactory.document("keep", "Keep", keepId, keepBytes.size.toLong())
        // The dropped doc is marked deleted, so its blob becomes unreferenced garbage.
        val dropDoc = VaultTestFactory.document("drop", "Drop", dropId, dropBytes.size.toLong(), deleted = true)
        vault.commit(emptyMap(), mapOf(keepDoc.id to keepDoc, dropDoc.id to dropDoc))

        val sizeBefore = store.size()

        val target = InMemoryVaultStore()
        val compacted = vault.compactTo(target)

        // Unreferenced blob is gone; referenced blob survives and still decrypts.
        assertThat(compacted.snapshot().blobs.keys).containsExactly(keepId)
        assertThat(compacted.readBlob(keepId)).isEqualTo(keepBytes)
        // Tombstone for the deleted document is retained (needed for convergent merges).
        assertThat(compacted.snapshot().documents.keys).containsExactly("keep", "drop")
        // Compacted file is smaller (dropped ~60 KB of garbage).
        assertThat(target.size()).isLessThan(sizeBefore)

        // The compacted vault opens cleanly with the original password.
        val reopened = VaultFile.open(InMemoryVaultStore(target.toByteArray()), "pw".toCharArray())
        assertThat(reopened.readBlob(keepId)).isEqualTo(keepBytes)
    }
}
