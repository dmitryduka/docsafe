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
}
