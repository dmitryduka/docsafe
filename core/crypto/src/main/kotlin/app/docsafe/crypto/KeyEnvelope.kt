package app.docsafe.crypto

/**
 * Two-layer key model. A random 32-byte **data-encryption key (DEK)** encrypts the vault
 * (its index and blobs). The DEK is itself wrapped (encrypted) by a **key-encryption key
 * (KEK)** derived from the password — and, on a device, also by an Android Keystore key
 * and/or a PIN-derived key. Unwrapping any wrap yields the same DEK, so the vault opens
 * without re-deriving from the password after the first import.
 */
object KeyEnvelope {
    private val DEK_AAD = "docsync:dek:v1".toByteArray(Charsets.US_ASCII)

    /** Generates a fresh random DEK. */
    fun generateDek(): ByteArray = randomBytes(Aead.KEY_LEN)

    /** Wraps [dek] under [kek]; returns `nonce || ciphertext || tag`. */
    fun wrap(kek: ByteArray, dek: ByteArray): ByteArray {
        require(dek.size == Aead.KEY_LEN) { "DEK must be ${Aead.KEY_LEN} bytes" }
        return Aead.sealWithNonce(kek, dek, DEK_AAD)
    }

    /** Unwraps a DEK previously produced by [wrap]; throws [DecryptionException] on a wrong KEK. */
    fun unwrap(kek: ByteArray, wrapped: ByteArray): ByteArray = Aead.openWithNonce(kek, wrapped, DEK_AAD)
}
