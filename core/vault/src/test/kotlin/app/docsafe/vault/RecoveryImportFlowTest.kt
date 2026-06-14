package app.docsafe.vault

import app.docsafe.crypto.KdfParams
import app.docsafe.vault.format.KdfParamsDto
import app.docsafe.vault.format.RecoveryBlockDto
import app.docsafe.vault.format.VaultHeader
import app.docsafe.vault.format.VaultHeaderCodec
import app.docsafe.vault.model.Folder
import app.docsafe.vault.store.LocalFileVaultStore
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * Reproduces the on-device recovery-import path against real files (`LocalFileVaultStore`,
 * `RandomAccessFile`), mirroring `VaultSession.importIntoWithRecovery` step for step, since the
 * in-memory tests don't exercise file I/O, header rewrites on disk, or store open/close ordering.
 */
class RecoveryImportFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fastKdf() = KdfParams(memoryKib = 1024, iterations = 1, parallelism = 1, salt = ByteArray(16) { 3 })

    @Test
    fun fileBackedRecoveryImportReplicatesAppFlow() {
        val src = tmp.newFile("src.dsvault")
        val codes: List<String>
        // 1. Create a vault with content + recovery codes (mirrors generate, then export-as-file).
        LocalFileVaultStore(src).let { store ->
            val v = VaultFile.create(store, "master".toCharArray(), fastKdf())
            val blobId = v.putBlob("HELLO-WORLD".toByteArray())
            v.commit(mapOf("f1" to Folder("f1", null, "Docs", false, 1L, 1L, "dev")), emptyMap())
            codes = RecoveryCodes.generate(5)
            v.setRecoveryCodes(codes.map { it.toCharArray() }, fastKdf())
            v.close() // closes the store
            assertThat(blobId).isNotEmpty()
        }

        // 2. Import-with-recovery, exactly as VaultSession.importIntoWithRecovery does it.
        val dest = File(tmp.root, "vault-imported.dsvault")
        val probe = VaultFile.openWithRecoveryCode(LocalFileVaultStore(src), codes[2].toCharArray())
        assertThat(probe).isNotNull()
        val dek = probe!!.dataKey
        probe.close()
        src.copyTo(dest, overwrite = true)
        val reopened = VaultFile.openWithDek(LocalFileVaultStore(dest), dek)

        val idx = reopened.snapshot()
        assertThat(idx.folders.keys).containsExactly("f1")
        val onlyBlob = idx.blobs.keys.first()
        assertThat(reopened.readBlob(onlyBlob)).isEqualTo("HELLO-WORLD".toByteArray())
        reopened.close()

        // 3. Single-use: the used code is struck on both the source and the copied dest.
        assertThat(VaultFile.openWithRecoveryCode(LocalFileVaultStore(src), codes[2].toCharArray())).isNull()
        assertThat(VaultFile.openWithRecoveryCode(LocalFileVaultStore(dest), codes[2].toCharArray())).isNull()
        // A different, unused code still opens the dest copy.
        VaultFile.openWithRecoveryCode(LocalFileVaultStore(dest), codes[0].toCharArray()).let {
            assertThat(it).isNotNull(); it!!.close()
        }
    }

    @Test
    fun generateOnOpenVaultThenExportThenImportWithCode() {
        // End-to-end mirror of the app: unlock (openWithDek) → generate recovery codes on the open
        // handle → a normal edit (persistIndex) → export = byte copy of the active file → import
        // the export with a code. Proves the recovery block persists into an exported copy and
        // survives a later index write.
        val active = tmp.newFile("vault-active.dsvault")
        val dek: ByteArray
        run {
            val created = VaultFile.create(LocalFileVaultStore(active), "master".toCharArray(), fastKdf())
            created.commit(mapOf("f1" to Folder("f1", null, "Real Estate", false, 1L, 1L, "d")), emptyMap())
            dek = created.dataKey
            created.close()
        }
        val codes = RecoveryCodes.generate(10)
        VaultFile.openWithDek(LocalFileVaultStore(active), dek).let { open ->
            open.setRecoveryCodes(codes.map { it.toCharArray() }, fastKdf())
            // Intervening normal edit → persistIndex rewrites the header; recovery must survive.
            open.commit(
                mapOf(
                    "f1" to Folder("f1", null, "Real Estate", false, 1L, 2L, "d"),
                    "f2" to Folder("f2", null, "Taxes", false, 1L, 1L, "d"),
                ),
                emptyMap(),
            )
            open.close()
        }
        val exported = File(tmp.root, "exported.dsvault")
        active.copyTo(exported, overwrite = true)

        val imported = VaultFile.openWithRecoveryCode(LocalFileVaultStore(exported), codes[0].toCharArray())
        assertThat(imported).isNotNull()
        assertThat(imported!!.snapshot().folders.keys).containsExactly("f1", "f2")
        imported.close()
    }

    @Test
    fun headerWithTenRealisticRecoveryWrapsFitsTheReservedRegion() {
        // Guard the 4096-byte header budget at *production* code count (10) with realistic wrap
        // sizes, without paying for 10×64 MiB Argon2 derivations.
        val wrap = Base64.getEncoder().encodeToString(ByteArray(12 + 32 + 16) { 1 }) // nonce+ct+tag
        val header = VaultHeader(
            kdf = KdfParamsDto.from(KdfParams.newRandom()),
            wrappedDek = Base64.getEncoder().encodeToString(ByteArray(60) { 2 }),
            chunkSize = 65_536,
            indexOffset = 4096,
            indexLength = 0,
            recovery = RecoveryBlockDto(KdfParamsDto.from(KdfParams.newRandom()), List(10) { wrap }),
        )
        val encoded = VaultHeaderCodec.encode(header) // throws if it overflows HEADER_RESERVED
        assertThat(encoded.size).isEqualTo(VaultHeaderCodec.HEADER_RESERVED)
        // And it round-trips.
        assertThat(VaultHeaderCodec.decode(encoded).recovery!!.wraps).hasSize(10)
    }
}
