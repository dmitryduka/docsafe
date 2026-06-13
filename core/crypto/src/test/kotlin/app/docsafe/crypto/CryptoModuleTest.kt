package app.docsafe.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Smoke test confirming the JVM unit-test toolchain works for the core modules. */
class CryptoModuleTest {
    @Test
    fun moduleMarkerIsPresent() {
        assertThat(CRYPTO_MODULE).isEqualTo("docsync-core-crypto")
    }
}
