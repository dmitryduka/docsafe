package app.docsafe.crypto

import java.io.InputStream
import java.security.MessageDigest

/**
 * Content addressing for blobs: `blobId = SHA-256(plaintext)`. Content-addressed ids make
 * blobs immutable and de-duplicated, which is what lets the seekable format and the merge
 * engine treat blob bytes as stable while only references change.
 */
object Hashing {
    const val BLOB_ID_LEN = 32

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Streams [input] through a SHA-256 digest without buffering the whole content. */
    fun sha256(input: InputStream, bufferSize: Int = 64 * 1024): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSize)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest()
    }

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            ((hexDigit(hex[i * 2]) shl 4) or hexDigit(hex[i * 2 + 1])).toByte()
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("Invalid hex digit: $c")
    }
}
