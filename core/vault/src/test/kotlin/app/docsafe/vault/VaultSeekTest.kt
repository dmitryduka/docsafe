package app.docsafe.vault

import com.google.common.truth.Truth.assertThat
import app.docsafe.crypto.KdfParams
import app.docsafe.vault.store.InMemoryVaultStore
import org.junit.Test

/**
 * The defining property of the format: opening reads only the header + index, and reading
 * one photo reads only that photo's byte range — never the whole (large) file.
 */
class VaultSeekTest {

    private fun fastKdf() = KdfParams(memoryKib = 1024, iterations = 1, parallelism = 1, salt = ByteArray(16) { 3 })

    @Test
    fun readingOneBlobReadsOnlyThatBlobsRange() {
        val backing = InMemoryVaultStore()
        // Build a multi-MB vault: 8 blobs of ~250 KB each.
        val blobSize = 250 * 1024
        val blobIds = mutableListOf<String>()
        run {
            val vault = VaultFile.create(backing, "pw".toCharArray(), fastKdf())
            val docs = LinkedHashMap<String, app.docsafe.vault.model.Document>()
            for (i in 0 until 8) {
                val bytes = ByteArray(blobSize) { ((it + i * 31) % 251).toByte() }
                val id = vault.putBlob(bytes)
                blobIds += id
                val doc = VaultTestFactory.document("d$i", "Doc$i", id, blobSize.toLong())
                docs[doc.id] = doc
            }
            vault.commit(emptyMap(), docs)
        }

        val totalSize = backing.size()
        assertThat(totalSize).isGreaterThan(1_900_000L) // genuinely large (~2 MB)

        // Open via a counting store and prove the open is cheap.
        val counting = CountingVaultStore(InMemoryVaultStore(backing.toByteArray()))
        val vault = VaultFile.open(counting, "pw".toCharArray())
        val bytesToOpen = counting.bytesRead
        // Opening reads the 4 KB header region + the small encrypted index, nowhere near the full file.
        assertThat(bytesToOpen).isLessThan(totalSize / 4)

        // Now read exactly one blob and confirm only its range is touched.
        counting.resetCounters()
        val target = blobIds[5]
        val entry = vault.snapshot().blobs[target]!!
        val data = vault.readBlob(target)

        assertThat(data.size).isEqualTo(blobSize)
        assertThat(counting.bytesRead).isEqualTo(entry.encLength)
        assertThat(counting.reads).containsExactly(entry.encOffset to entry.encLength.toInt())
        // The single read is a small fraction of the whole file.
        assertThat(counting.bytesRead).isLessThan(totalSize / 4)
    }
}
