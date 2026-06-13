package app.docsafe.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/**
 * Derives a 32-byte key-encryption key (KEK) from a password (or PIN) using Argon2id via
 * Bouncy Castle — a pure-Java implementation, so it runs identically in host JVM unit tests
 * and on Android.
 */
object Argon2idKdf {
    const val KEY_LEN = Aead.KEY_LEN

    /** Derives a KEK from a [password] using [params]. The password bytes are zeroed afterward. */
    fun deriveKey(password: CharArray, params: KdfParams): ByteArray {
        val passwordBytes = toUtf8Bytes(password)
        return try {
            deriveKey(passwordBytes, params)
        } finally {
            passwordBytes.fill(0)
        }
    }

    /** Derives a KEK from raw [passwordBytes]. Caller owns zeroing the input. */
    fun deriveKey(passwordBytes: ByteArray, params: KdfParams): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(params.iterations)
            .withMemoryAsKB(params.memoryKib)
            .withParallelism(params.parallelism)
            .withSalt(params.salt)
            .build()
        val generator = Argon2BytesGenerator().apply { init(parameters) }
        val out = ByteArray(KEY_LEN)
        generator.generateBytes(passwordBytes, out)
        return out
    }

    private fun toUtf8Bytes(chars: CharArray): ByteArray {
        val byteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(byteBuffer.remaining())
        byteBuffer.get(bytes)
        // Best-effort wipe of the temporary buffer.
        if (byteBuffer.hasArray()) byteBuffer.array().fill(0)
        clear(byteBuffer)
        return bytes
    }

    private fun clear(buffer: ByteBuffer) {
        buffer.clear()
        while (buffer.hasRemaining()) buffer.put(0)
    }
}
