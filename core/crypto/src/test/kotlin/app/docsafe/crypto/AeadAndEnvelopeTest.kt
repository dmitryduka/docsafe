package app.docsafe.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.Assert.assertThrows

class AeadAndEnvelopeTest {

    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun sealOpenRoundTripWithAad() {
        val nonce = randomBytes(Aead.NONCE_LEN)
        val plaintext = "top secret".toByteArray()
        val aad = "context".toByteArray()
        val sealed = Aead.seal(key, nonce, plaintext, aad)
        assertThat(Aead.open(key, nonce, sealed, aad)).isEqualTo(plaintext)
    }

    @Test
    fun openFailsWithWrongAad() {
        val nonce = randomBytes(Aead.NONCE_LEN)
        val sealed = Aead.seal(key, nonce, "data".toByteArray(), "aad1".toByteArray())
        assertThrows(DecryptionException::class.java, ThrowingRunnable {
            Aead.open(key, nonce, sealed, "aad2".toByteArray())
        })
    }

    @Test
    fun openFailsWhenCiphertextTampered() {
        val nonce = randomBytes(Aead.NONCE_LEN)
        val sealed = Aead.seal(key, nonce, "data".toByteArray())
        sealed[0] = (sealed[0] + 1).toByte()
        assertThrows(DecryptionException::class.java, ThrowingRunnable {
            Aead.open(key, nonce, sealed)
        })
    }

    @Test
    fun sealWithNonceRoundTrips() {
        val sealed = Aead.sealWithNonce(key, "hello".toByteArray())
        assertThat(Aead.openWithNonce(key, sealed)).isEqualTo("hello".toByteArray())
    }

    @Test
    fun dekWrapUnwrapRoundTrip() {
        val kek = randomBytes(32)
        val dek = KeyEnvelope.generateDek()
        val wrapped = KeyEnvelope.wrap(kek, dek)
        assertThat(KeyEnvelope.unwrap(kek, wrapped)).isEqualTo(dek)
    }

    @Test
    fun unwrapFailsWithWrongKek() {
        val dek = KeyEnvelope.generateDek()
        val wrapped = KeyEnvelope.wrap(randomBytes(32), dek)
        assertThrows(DecryptionException::class.java, ThrowingRunnable {
            KeyEnvelope.unwrap(randomBytes(32), wrapped)
        })
    }
}
