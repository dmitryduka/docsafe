package app.docsafe.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.function.ThrowingRunnable

class BlobCipherTest {

    private val dek = ByteArray(32) { (it * 3).toByte() }
    private val cipher = BlobCipher(dek)

    private fun blobId(data: ByteArray) = Hashing.sha256(data)

    @Test
    fun roundTripsAcrossChunkBoundaries() {
        val chunk = 16
        // Sizes around the chunk boundary are the interesting edge cases.
        for (size in listOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 2 * chunk + 5, 5 * chunk)) {
            val plaintext = ByteArray(size) { (it % 251).toByte() }
            val id = blobId(plaintext)
            val ciphertext = cipher.encrypt(id, plaintext, chunkSize = chunk)
            val decrypted = cipher.decrypt(id, ciphertext, chunkSize = chunk)
            assertThat(decrypted).isEqualTo(plaintext)
            assertThat(ciphertext.size.toLong())
                .isEqualTo(cipher.cipherTextLength(size.toLong(), chunk))
        }
    }

    @Test
    fun decryptFailsWithWrongDek() {
        val plaintext = ByteArray(100) { it.toByte() }
        val id = blobId(plaintext)
        val ciphertext = cipher.encrypt(id, plaintext, chunkSize = 16)
        val otherCipher = BlobCipher(randomBytes(32))
        assertThrows(DecryptionException::class.java, ThrowingRunnable {
            otherCipher.decrypt(id, ciphertext, chunkSize = 16)
        })
    }

    @Test
    fun decryptFailsWhenCiphertextTampered() {
        val plaintext = ByteArray(50) { it.toByte() }
        val id = blobId(plaintext)
        val ciphertext = cipher.encrypt(id, plaintext, chunkSize = 16)
        ciphertext[ciphertext.size / 2] = (ciphertext[ciphertext.size / 2] + 1).toByte()
        assertThrows(DecryptionException::class.java, ThrowingRunnable {
            cipher.decrypt(id, ciphertext, chunkSize = 16)
        })
    }

    @Test
    fun decryptFailsWhenChunksReordered() {
        val chunk = 16
        // Two full chunks, then swap them — the chunk-index AAD must reject this.
        val plaintext = ByteArray(2 * chunk) { it.toByte() }
        val id = blobId(plaintext)
        val ciphertext = cipher.encrypt(id, plaintext, chunkSize = chunk)
        val encChunk = chunk + Aead.TAG_LEN
        val swapped = ByteArray(ciphertext.size)
        System.arraycopy(ciphertext, encChunk, swapped, 0, encChunk)
        System.arraycopy(ciphertext, 0, swapped, encChunk, encChunk)
        assertThrows(DecryptionException::class.java, ThrowingRunnable {
            cipher.decrypt(id, swapped, chunkSize = chunk)
        })
    }

    @Test
    fun identicalContentEncryptsDeterministically() {
        // Content addressing + deterministic per-blob key => dedup-friendly identical output.
        val plaintext = "same bytes".toByteArray()
        val id = blobId(plaintext)
        assertThat(cipher.encrypt(id, plaintext)).isEqualTo(cipher.encrypt(id, plaintext))
    }

    @Test
    fun hashingStreamMatchesArrayAndHexRoundTrips() {
        val data = ByteArray(200) { (it * 7).toByte() }
        assertThat(Hashing.sha256(data.inputStream(), bufferSize = 16)).isEqualTo(Hashing.sha256(data))
        val hex = Hashing.toHex(data)
        assertThat(Hashing.fromHex(hex)).isEqualTo(data)
    }
}
