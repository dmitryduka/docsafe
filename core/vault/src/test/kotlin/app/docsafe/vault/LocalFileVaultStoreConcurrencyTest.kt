package app.docsafe.vault

import app.docsafe.crypto.KdfParams
import app.docsafe.vault.store.LocalFileVaultStore
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression test for concurrent blob reads. The UI decrypts many blobs at once (a grid of
 * thumbnails), and the file store shares one `RandomAccessFile` pointer; without serialization
 * the seek/read pairs interleave and return bytes from the wrong offset, failing GCM auth and
 * dropping thumbnails. This hammers the store from many threads and asserts every read is exact.
 */
class LocalFileVaultStoreConcurrencyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fastKdf() = KdfParams(memoryKib = 1024, iterations = 1, parallelism = 1, salt = ByteArray(16) { 7 })

    @Test
    fun concurrentBlobReadsAllSucceed() {
        val file = tmp.newFile("vault.dsvault")
        val blobs = (0 until 24).map { i ->
            // Multi-chunk, distinct content so a misaligned read corrupts the plaintext.
            ByteArray(50_000) { ((i * 31 + it) % 251).toByte() }
        }

        val ids: List<String>
        LocalFileVaultStore(file).use { store ->
            val vault = VaultFile.create(store, "pw".toCharArray(), fastKdf())
            ids = blobs.map { vault.putBlob(it) }
            vault.commit(emptyMap(), emptyMap())
        }

        LocalFileVaultStore(file).use { store ->
            val vault = VaultFile.open(store, "pw".toCharArray())
            val pool = Executors.newFixedThreadPool(16)
            val failures = AtomicInteger(0)
            repeat(400) { iter ->
                val idx = iter % ids.size
                pool.submit {
                    val got = runCatching { vault.readBlob(ids[idx]) }.getOrNull()
                    if (got == null || !got.contentEquals(blobs[idx])) failures.incrementAndGet()
                }
            }
            pool.shutdown()
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue()
            assertThat(failures.get()).isEqualTo(0)
        }
    }
}
