package app.docsafe.crypto

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM authenticated encryption. The low-level building block for everything in the
 * vault: the encrypted index, DEK wrapping, and each blob chunk.
 */
object Aead {
    const val KEY_LEN = 32
    const val NONCE_LEN = 12
    const val TAG_LEN = 16
    private const val TAG_BITS = TAG_LEN * 8
    private const val TRANSFORM = "AES/GCM/NoPadding"

    /** Encrypts [plaintext] with an explicit [nonce]; returns ciphertext with the 16-byte tag appended. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == KEY_LEN) { "Key must be $KEY_LEN bytes" }
        require(nonce.size == NONCE_LEN) { "Nonce must be $NONCE_LEN bytes" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        aad?.let { cipher.updateAAD(it) }
        return cipher.doFinal(plaintext)
    }

    /** Decrypts and verifies [ciphertext]; throws [DecryptionException] on any auth failure. */
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == KEY_LEN) { "Key must be $KEY_LEN bytes" }
        require(nonce.size == NONCE_LEN) { "Nonce must be $NONCE_LEN bytes" }
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            aad?.let { cipher.updateAAD(it) }
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            // Covers a bad GCM tag and any other cipher failure (e.g. malformed/short ciphertext
            // from a tampered file) so decryption never leaks distinct failure modes.
            throw DecryptionException(cause = e)
        }
    }

    /** Convenience: generate a random nonce and return `nonce || ciphertext || tag`. */
    fun sealWithNonce(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val nonce = randomBytes(NONCE_LEN)
        return nonce + seal(key, nonce, plaintext, aad)
    }

    /** Inverse of [sealWithNonce]: reads the prefixed nonce, then decrypts. */
    fun openWithNonce(key: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray {
        if (sealed.size < NONCE_LEN + TAG_LEN) throw DecryptionException("Ciphertext too short")
        val nonce = sealed.copyOfRange(0, NONCE_LEN)
        val ciphertext = sealed.copyOfRange(NONCE_LEN, sealed.size)
        return open(key, nonce, ciphertext, aad)
    }
}
