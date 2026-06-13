package app.docsafe.crypto

import java.security.SecureRandom

private val secureRandom: SecureRandom by lazy { SecureRandom() }

/** Returns [n] cryptographically random bytes. */
fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

/** Constant-time comparison to avoid leaking equality timing. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var result = 0
    for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
    return result == 0
}
