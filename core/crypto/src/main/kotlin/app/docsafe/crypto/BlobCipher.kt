package app.docsafe.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Encrypts/decrypts a single attachment blob as a sequence of independent AES-256-GCM
 * chunks, so a hundreds-of-MB blob streams through a fixed buffer without ever being held
 * whole in memory.
 *
 * Each blob gets its own key derived from the DEK via HKDF keyed by the blob's content id
 * (`blobId = SHA-256(plaintext)`). Because the key is unique per distinct content, chunk
 * nonces can safely start at a zero counter. The chunk index is bound into the GCM AAD so
 * chunks cannot be reordered. The blob's total ciphertext length is recorded in the
 * (authenticated) vault index, which is what detects truncation.
 *
 * On-disk layout of one blob: `chunk0 || chunk1 || ...`, where each `chunkN` is
 * `ciphertext(plaintextChunk) || 16-byte tag`. Every chunk except the last carries exactly
 * [chunkSize] plaintext bytes.
 */
class BlobCipher(private val dek: ByteArray) {

    init {
        require(dek.size == Aead.KEY_LEN) { "DEK must be ${Aead.KEY_LEN} bytes" }
    }

    private val blobKeyInfo = "docsync:blob:v1".toByteArray(Charsets.US_ASCII)

    private fun blobKey(blobId: ByteArray): ByteArray =
        Hkdf.derive(ikm = dek, salt = blobId, info = blobKeyInfo, length = Aead.KEY_LEN)

    private fun chunkNonce(index: Long): ByteArray {
        // 12-byte nonce: 4 zero bytes + 8-byte big-endian chunk index.
        val nonce = ByteArray(Aead.NONCE_LEN)
        for (i in 0 until 8) {
            nonce[Aead.NONCE_LEN - 1 - i] = (index ushr (8 * i)).toByte()
        }
        return nonce
    }

    private fun chunkAad(blobId: ByteArray, index: Long): ByteArray {
        val aad = blobId.copyOf(blobId.size + 8)
        for (i in 0 until 8) {
            aad[aad.size - 1 - i] = (index ushr (8 * i)).toByte()
        }
        return aad
    }

    /** Streams plaintext from [input] to encrypted [output]; returns the ciphertext byte count. */
    fun encrypt(blobId: ByteArray, input: InputStream, output: OutputStream, chunkSize: Int = DEFAULT_CHUNK_SIZE): Long {
        require(chunkSize > 0) { "chunkSize must be positive" }
        val key = blobKey(blobId)
        val buffer = ByteArray(chunkSize)
        var index = 0L
        var written = 0L
        while (true) {
            val read = input.readNBytes(buffer, 0, chunkSize)
            if (read == 0) break
            val plaintext = if (read == chunkSize) buffer else buffer.copyOf(read)
            val ciphertext = Aead.seal(key, chunkNonce(index), plaintext, chunkAad(blobId, index))
            output.write(ciphertext)
            written += ciphertext.size
            index++
            if (read < chunkSize) break
        }
        return written
    }

    /**
     * Streams ciphertext from [input] (which MUST be bounded to exactly the blob's stored
     * length) to plaintext [output]. Throws [DecryptionException] on any tampering.
     */
    fun decrypt(blobId: ByteArray, input: InputStream, output: OutputStream, chunkSize: Int = DEFAULT_CHUNK_SIZE) {
        require(chunkSize > 0) { "chunkSize must be positive" }
        val key = blobKey(blobId)
        val encChunkSize = chunkSize + Aead.TAG_LEN
        val buffer = ByteArray(encChunkSize)
        var index = 0L
        while (true) {
            val read = input.readNBytes(buffer, 0, encChunkSize)
            if (read == 0) break
            val ciphertext = if (read == encChunkSize) buffer else buffer.copyOf(read)
            val plaintext = Aead.open(key, chunkNonce(index), ciphertext, chunkAad(blobId, index))
            output.write(plaintext)
            index++
            if (read < encChunkSize) break
        }
    }

    /** One-shot helper: encrypt [plaintext] fully in memory. */
    fun encrypt(blobId: ByteArray, plaintext: ByteArray, chunkSize: Int = DEFAULT_CHUNK_SIZE): ByteArray {
        val out = ByteArrayOutputStream()
        encrypt(blobId, ByteArrayInputStream(plaintext), out, chunkSize)
        return out.toByteArray()
    }

    /** One-shot helper: decrypt [ciphertext] fully in memory. */
    fun decrypt(blobId: ByteArray, ciphertext: ByteArray, chunkSize: Int = DEFAULT_CHUNK_SIZE): ByteArray {
        val out = ByteArrayOutputStream()
        decrypt(blobId, ByteArrayInputStream(ciphertext), out, chunkSize)
        return out.toByteArray()
    }

    /** Number of ciphertext bytes a plaintext of [plaintextSize] produces at [chunkSize]. */
    fun cipherTextLength(plaintextSize: Long, chunkSize: Int = DEFAULT_CHUNK_SIZE): Long {
        if (plaintextSize == 0L) return 0L
        val fullChunks = plaintextSize / chunkSize
        val remainder = plaintextSize % chunkSize
        val chunks = fullChunks + if (remainder == 0L) 0 else 1
        return plaintextSize + chunks * Aead.TAG_LEN
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
    }
}
