package app.docsafe.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Argon2idKdfTest {

    // Low-cost params keep unit tests fast; production uses KdfParams defaults.
    private fun fastParams(salt: ByteArray = ByteArray(16) { 7 }) =
        KdfParams(memoryKib = 1024, iterations = 1, parallelism = 1, salt = salt)

    @Test
    fun derivesDeterministicKeyForSamePasswordAndParams() {
        val params = fastParams()
        val a = Argon2idKdf.deriveKey("correct horse battery".toCharArray(), params)
        val b = Argon2idKdf.deriveKey("correct horse battery".toCharArray(), params)
        assertThat(a).isEqualTo(b)
        assertThat(a).hasLength(Argon2idKdf.KEY_LEN)
    }

    @Test
    fun differentPasswordProducesDifferentKey() {
        val params = fastParams()
        val a = Argon2idKdf.deriveKey("password-a".toCharArray(), params)
        val b = Argon2idKdf.deriveKey("password-b".toCharArray(), params)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun differentSaltProducesDifferentKey() {
        val a = Argon2idKdf.deriveKey("same".toCharArray(), fastParams(ByteArray(16) { 1 }))
        val b = Argon2idKdf.deriveKey("same".toCharArray(), fastParams(ByteArray(16) { 2 }))
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun newRandomParamsHaveUniqueSalts() {
        assertThat(KdfParams.newRandom().salt).isNotEqualTo(KdfParams.newRandom().salt)
    }

    @Test
    fun rejectsAbsurdCostParamsFromAMaliciousHeader() {
        val salt = ByteArray(16) { 7 }
        // Memory / iterations / parallelism above the sane ceilings are refused before any
        // (OOM / unbounded-CPU) derivation is attempted.
        assertThrows { KdfParams(memoryKib = KdfParams.MAX_MEMORY_KIB + 1, iterations = 1, parallelism = 1, salt = salt) }
        assertThrows { KdfParams(memoryKib = 1024, iterations = KdfParams.MAX_ITERATIONS + 1, parallelism = 1, salt = salt) }
        assertThrows { KdfParams(memoryKib = 1024, iterations = 1, parallelism = KdfParams.MAX_PARALLELISM + 1, salt = salt) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected an exception but none was thrown")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
