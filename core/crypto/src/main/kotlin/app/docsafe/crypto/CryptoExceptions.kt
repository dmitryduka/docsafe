package app.docsafe.crypto

/** Base type for all crypto failures surfaced by this module. */
sealed class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when authenticated decryption fails: a wrong password/key, a corrupted file, or
 * tampering. Deliberately does not distinguish these cases to avoid an oracle.
 */
class DecryptionException(message: String = "Decryption failed (wrong password or corrupt data)", cause: Throwable? = null) :
    CryptoException(message, cause)
