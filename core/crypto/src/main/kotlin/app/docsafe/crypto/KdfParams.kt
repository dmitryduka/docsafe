package app.docsafe.crypto

/**
 * Parameters for the password-based KDF (Argon2id). These are stored in the vault's
 * cleartext header so any device with the password can re-derive the key-encryption key.
 *
 * Defaults target interactive use on a phone: 64 MiB memory, 3 passes. Tune if needed; the
 * values used to encrypt a given vault are always read back from its header.
 */
data class KdfParams(
    val algorithm: String = ALGORITHM_ARGON2ID,
    val memoryKib: Int = DEFAULT_MEMORY_KIB,
    val iterations: Int = DEFAULT_ITERATIONS,
    val parallelism: Int = DEFAULT_PARALLELISM,
    val salt: ByteArray,
) {
    init {
        require(algorithm == ALGORITHM_ARGON2ID) { "Unsupported KDF algorithm: $algorithm" }
        require(salt.size >= MIN_SALT_LEN) { "Salt must be at least $MIN_SALT_LEN bytes" }
        require(memoryKib > 0 && iterations > 0 && parallelism > 0) { "KDF cost parameters must be positive" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KdfParams) return false
        return algorithm == other.algorithm &&
            memoryKib == other.memoryKib &&
            iterations == other.iterations &&
            parallelism == other.parallelism &&
            salt.contentEquals(other.salt)
    }

    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + memoryKib
        result = 31 * result + iterations
        result = 31 * result + parallelism
        result = 31 * result + salt.contentHashCode()
        return result
    }

    companion object {
        const val ALGORITHM_ARGON2ID = "argon2id"
        const val DEFAULT_MEMORY_KIB = 64 * 1024
        const val DEFAULT_ITERATIONS = 3
        const val DEFAULT_PARALLELISM = 1
        const val DEFAULT_SALT_LEN = 16
        const val MIN_SALT_LEN = 16

        /** Creates parameters with a fresh random salt and the interactive defaults. */
        fun newRandom(): KdfParams = KdfParams(salt = randomBytes(DEFAULT_SALT_LEN))
    }
}
