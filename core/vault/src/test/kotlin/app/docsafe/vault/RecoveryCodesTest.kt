package app.docsafe.vault

import app.docsafe.crypto.KdfParams
import app.docsafe.vault.model.Folder
import app.docsafe.vault.store.InMemoryVaultStore
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecoveryCodesTest {

    private fun fastKdf() = KdfParams(memoryKib = 1024, iterations = 1, parallelism = 1, salt = ByteArray(16) { 9 })

    private fun newVaultWithContent(store: InMemoryVaultStore): VaultFile {
        val v = VaultFile.create(store, "master-pw".toCharArray(), fastKdf())
        v.commit(mapOf("f1" to Folder("f1", null, "Taxes", false, 1L, 1L, "dev")), emptyMap())
        return v
    }

    @Test
    fun generatesUniqueWellFormedCodes() {
        val codes = RecoveryCodes.generate(10)
        assertThat(codes).hasSize(10)
        assertThat(codes.toSet()).hasSize(10) // unique
        val allowed = ('0'..'9') + "ABCDEFGHJKMNPQRSTVWXYZ".toList()
        codes.forEach { code ->
            assertThat(code).matches("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}")
            code.filter { it != '-' }.forEach { assertThat(allowed).contains(it) }
        }
    }

    @Test
    fun recoveryCodeOpensVaultAndYieldsSameDek() {
        val store = InMemoryVaultStore()
        val v = newVaultWithContent(store)
        val dek = v.dataKey
        val codes = RecoveryCodes.generate(3)
        v.setRecoveryCodes(codes.map { it.toCharArray() }, fastKdf())
        assertThat(v.hasRecoveryCodes()).isTrue()

        val recovered = VaultFile.openWithRecoveryCode(store, codes[1].toCharArray())
        assertThat(recovered).isNotNull()
        assertThat(recovered!!.dataKey).isEqualTo(dek)
        assertThat(recovered.snapshot().folders.keys).containsExactly("f1") // content intact

        // Dashes/case are normalized: the same code without dashes / lower-case also works
        // (on an independent copy of the file, since recovery strikes the used code).
        val copy = InMemoryVaultStore(store.toByteArray())
        val again = VaultFile.openWithRecoveryCode(copy, codes[2].replace("-", "").lowercase().toCharArray())
        assertThat(again).isNotNull()
    }

    @Test
    fun wrongCodeReturnsNull() {
        val store = InMemoryVaultStore()
        val v = newVaultWithContent(store)
        v.setRecoveryCodes(RecoveryCodes.generate(3).map { it.toCharArray() }, fastKdf())
        assertThat(VaultFile.openWithRecoveryCode(store, "ZZZZ-ZZZZ-ZZZZ-ZZZZ".toCharArray())).isNull()
    }

    @Test
    fun codesAreSingleUse() {
        val store = InMemoryVaultStore()
        val v = newVaultWithContent(store)
        val codes = RecoveryCodes.generate(3)
        v.setRecoveryCodes(codes.map { it.toCharArray() }, fastKdf())

        // Use code[0] once — it is struck from the file.
        assertThat(VaultFile.openWithRecoveryCode(store, codes[0].toCharArray())).isNotNull()
        // Reusing the same code now fails…
        assertThat(VaultFile.openWithRecoveryCode(store, codes[0].toCharArray())).isNull()
        // …but the other codes still work.
        assertThat(VaultFile.openWithRecoveryCode(store, codes[1].toCharArray())).isNotNull()
    }

    @Test
    fun changingPasswordInvalidatesAllCodes() {
        val store = InMemoryVaultStore()
        val v = newVaultWithContent(store)
        val codes = RecoveryCodes.generate(3)
        v.setRecoveryCodes(codes.map { it.toCharArray() }, fastKdf())

        v.changePassword("new-master-pw".toCharArray())

        assertThat(v.hasRecoveryCodes()).isFalse()
        codes.forEach { assertThat(VaultFile.openWithRecoveryCode(store, it.toCharArray())).isNull() }
        // The new password still opens it.
        assertThat(VaultFile.open(store, "new-master-pw".toCharArray()).dataKey).isEqualTo(v.dataKey)
    }

    @Test
    fun vaultWithoutRecoveryCodesRejectsRecoveryOpen() {
        val store = InMemoryVaultStore()
        val v = newVaultWithContent(store)
        assertThat(v.hasRecoveryCodes()).isFalse()
        assertThat(VaultFile.openWithRecoveryCode(store, "ABCD-ABCD-ABCD-ABCD".toCharArray())).isNull()
    }
}
