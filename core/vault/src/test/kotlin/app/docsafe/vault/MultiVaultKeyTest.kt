package app.docsafe.vault

import app.docsafe.crypto.DecryptionException
import app.docsafe.crypto.KdfParams
import app.docsafe.crypto.KeyEnvelope
import app.docsafe.vault.store.InMemoryVaultStore
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.function.ThrowingRunnable

/** Crypto invariants behind 1.0.4 multi-vault: change-password, master-key wrapping, migration. */
class MultiVaultKeyTest {

    private fun fastKdf() = KdfParams(memoryKib = 1024, iterations = 1, parallelism = 1, salt = ByteArray(16) { 3 })

    @Test
    fun changePasswordReopensWithNewAndRejectsOld() {
        val store = InMemoryVaultStore()
        val vault = VaultFile.create(store, "old-pw".toCharArray(), fastKdf())
        val blobId = vault.putBlob("hello".toByteArray())
        vault.commit(emptyMap(), emptyMap()) // persist the index (incl. blob table)
        vault.changePassword("new-pw".toCharArray())
        vault.close()
        val bytes = store.toByteArray()

        val reopened = VaultFile.open(InMemoryVaultStore(bytes), "new-pw".toCharArray())
        assertThat(reopened.readBlob(blobId)).isEqualTo("hello".toByteArray())
        reopened.close()

        assertThrows(DecryptionException::class.java, ThrowingRunnable {
            VaultFile.open(InMemoryVaultStore(bytes), "old-pw".toCharArray())
        })
    }

    @Test
    fun oneMasterKeyWrapsManyIndependentVaultDeks() {
        val masterKey = KeyEnvelope.generateDek()

        val storeA = InMemoryVaultStore()
        val a = VaultFile.create(storeA, "pa".toCharArray(), fastKdf())
        val dekA = a.dataKey
        val idA = a.putBlob("alpha".toByteArray())
        a.commit(emptyMap(), emptyMap())
        val wrapA = KeyEnvelope.wrap(masterKey, dekA)
        a.close()

        val storeB = InMemoryVaultStore()
        val b = VaultFile.create(storeB, "pb".toCharArray(), fastKdf())
        val dekB = b.dataKey
        val idB = b.putBlob("beta".toByteArray())
        b.commit(emptyMap(), emptyMap())
        val wrapB = KeyEnvelope.wrap(masterKey, dekB)
        b.close()

        // The single master key unwraps each vault's own DEK, opening each vault.
        val ra = VaultFile.openWithDek(InMemoryVaultStore(storeA.toByteArray()), KeyEnvelope.unwrap(masterKey, wrapA))
        val rb = VaultFile.openWithDek(InMemoryVaultStore(storeB.toByteArray()), KeyEnvelope.unwrap(masterKey, wrapB))
        assertThat(ra.readBlob(idA)).isEqualTo("alpha".toByteArray())
        assertThat(rb.readBlob(idB)).isEqualTo("beta".toByteArray())
        // Vaults have independent DEKs.
        assertThat(KeyEnvelope.unwrap(masterKey, wrapA)).isNotEqualTo(KeyEnvelope.unwrap(masterKey, wrapB))
        ra.close(); rb.close()
    }

    @Test
    fun legacyMigrationAdoptsOldDekAsMasterKey() {
        // Migration treats the pre-1.0.4 DEK as the device master key; the vault's DEK (== the
        // master key) is wrapped under it. unwrap(mk, wrap(mk, mk)) must recover mk and open the vault.
        val store = InMemoryVaultStore()
        val vault = VaultFile.create(store, "pw".toCharArray(), fastKdf())
        val oldDek = vault.dataKey
        val id = vault.putBlob("doc".toByteArray())
        vault.commit(emptyMap(), emptyMap())
        vault.close()

        val masterKey = oldDek
        val wrappedDek = KeyEnvelope.wrap(masterKey, oldDek)
        val recovered = KeyEnvelope.unwrap(masterKey, wrappedDek)
        assertThat(recovered).isEqualTo(oldDek)

        val reopened = VaultFile.openWithDek(InMemoryVaultStore(store.toByteArray()), recovered)
        assertThat(reopened.readBlob(id)).isEqualTo("doc".toByteArray())
        reopened.close()
    }
}
