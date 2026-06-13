package app.docsafe.security

import app.docsafe.crypto.Argon2idKdf
import app.docsafe.crypto.KdfParams
import app.docsafe.crypto.randomBytes

/**
 * Derives a wrapping key from a user PIN using Argon2id. A PIN has very low entropy, so we
 * lean on Argon2's memory-hardness and keep the cost reasonable for an interactive unlock.
 * The salt is generated once per device and stored alongside the PIN-wrapped DEK.
 */
object PinKdf {
    // OWASP-recommended interactive Argon2id (m=19 MiB, t=2, p=1): strong but fast enough
    // to type-and-go on each unlock. The PIN-wrapped DEK also lives inside Keystore-backed
    // EncryptedSharedPreferences, so this is defense-in-depth rather than the sole barrier.
    private const val MEMORY_KIB = 19_456
    private const val ITERATIONS = 2

    fun newParams(): KdfParams = KdfParams(
        memoryKib = MEMORY_KIB,
        iterations = ITERATIONS,
        parallelism = 1,
        salt = randomBytes(KdfParams.DEFAULT_SALT_LEN),
    )

    fun deriveKey(pin: CharArray, params: KdfParams): ByteArray = Argon2idKdf.deriveKey(pin, params)
}
