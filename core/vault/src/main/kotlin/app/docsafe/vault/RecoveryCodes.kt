package app.docsafe.vault

import app.docsafe.crypto.randomBytes

/**
 * Generates and normalizes vault **recovery codes** — high-entropy, password-equivalent secrets
 * that wrap the vault DEK (see [VaultFile.setRecoveryCodes]). Each code is 80 bits of CSPRNG
 * output in Crockford base32 (no I/L/O/U, so it's unambiguous to write down), shown grouped as
 * `XXXX-XXXX-XXXX-XXXX`. 80 bits is far beyond offline brute-force, and the codes are still run
 * through Argon2id when deriving their wrapping key.
 */
object RecoveryCodes {

    const val DEFAULT_COUNT = 10

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford base32
    private const val ENTROPY_BYTES = 10 // 80 bits → exactly 16 base32 chars

    /** Returns [count] fresh display codes (grouped with dashes). */
    fun generate(count: Int = DEFAULT_COUNT): List<String> =
        List(count) { format(encode(randomBytes(ENTROPY_BYTES))) }

    /** Canonical form used for key derivation/matching: upper-case, no dashes or spaces. */
    fun normalize(input: String): String =
        input.uppercase().filter { it != '-' && !it.isWhitespace() }

    private fun format(code: String): String = code.chunked(4).joinToString("-")

    private fun encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buffer ushr bits) and 0x1F])
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return sb.toString()
    }
}
