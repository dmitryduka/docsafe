package app.docsafe.vault

import app.docsafe.crypto.Aead
import app.docsafe.crypto.Argon2idKdf
import app.docsafe.crypto.BlobCipher
import app.docsafe.crypto.DecryptionException
import app.docsafe.crypto.Hashing
import app.docsafe.crypto.KdfParams
import app.docsafe.crypto.KeyEnvelope
import app.docsafe.vault.format.KdfParamsDto
import app.docsafe.vault.format.RecoveryBlockDto
import app.docsafe.vault.format.VaultHeader
import app.docsafe.vault.format.VaultHeaderCodec
import app.docsafe.vault.model.BlobEntry
import app.docsafe.vault.model.Document
import app.docsafe.vault.model.Folder
import app.docsafe.vault.model.VaultIndex
import app.docsafe.vault.store.VaultStore
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.util.Base64

/**
 * The seekable vault. Opens a [VaultStore] and exposes the decrypted structure plus
 * random-access blob I/O:
 *
 *  - Opening reads only the fixed header region and the encrypted index — never the blob
 *    region — so a hundreds-of-MB vault opens with two small reads.
 *  - [readBlob] seeks directly to one blob's byte range and decrypts only that blob.
 *  - Mutations [putBlob]/[commit] **append** to the store and rewrite only the small index +
 *    header pointer, so adding a document never rewrites the existing blob region.
 *  - [compactTo] reclaims garbage (old indexes, unreferenced blobs) by copying referenced
 *    blob ciphertext (position-independent, so no re-encryption) into a fresh store.
 *
 * The instance holds the raw DEK in memory; call [close] when done.
 */
class VaultFile private constructor(
    private val store: VaultStore,
    private val dek: ByteArray,
    private var header: VaultHeader,
    private var currentIndex: VaultIndex,
) : Closeable {

    private val chunkSize: Int = header.chunkSize
    private val blobCipher = BlobCipher(dek)

    /** Immutable snapshot of the current structure (folders, documents, blob table). */
    fun snapshot(): VaultIndex = currentIndex

    /** The DEK, so the app can re-wrap it under an Android Keystore / PIN-derived key. */
    val dataKey: ByteArray get() = dek.copyOf()

    /** The password-wrapped DEK from the header (for diagnostics / re-import). */
    val wrappedDek: ByteArray get() = Base64.getDecoder().decode(header.wrappedDek)

    /**
     * Stores [plaintext] as a blob and returns its content id. If a blob with identical
     * content already exists, it is reused (no new bytes written).
     */
    fun putBlob(plaintext: ByteArray): String {
        val id = Hashing.toHex(Hashing.sha256(plaintext))
        currentIndex.blobs[id]?.let { return id }
        val ciphertext = blobCipher.encrypt(Hashing.fromHex(id), plaintext, chunkSize)
        val offset = store.append(ciphertext)
        val entry = BlobEntry(
            blobId = id,
            encOffset = offset,
            encLength = ciphertext.size.toLong(),
            chunkSize = chunkSize,
            plaintextSize = plaintext.size.toLong(),
        )
        currentIndex = currentIndex.copy(blobs = currentIndex.blobs + (id to entry))
        return id
    }

    /** Copies an already-encrypted blob (ciphertext + its entry) in from another vault. */
    fun putBlobCiphertext(entry: BlobEntry, ciphertext: ByteArray): BlobEntry {
        currentIndex.blobs[entry.blobId]?.let { return it }
        val offset = store.append(ciphertext)
        val placed = entry.copy(encOffset = offset, encLength = ciphertext.size.toLong())
        currentIndex = currentIndex.copy(blobs = currentIndex.blobs + (placed.blobId to placed))
        return placed
    }

    /** Returns the raw ciphertext bytes of a blob (for copying between vaults during sync). */
    fun readBlobCiphertext(blobId: String): ByteArray {
        val entry = currentIndex.blobs[blobId] ?: error("Unknown blob $blobId")
        return store.read(entry.encOffset, intLength(entry.encLength))
    }

    /** Seeks to and decrypts a single blob by id. Reads only that blob's byte range. */
    fun readBlob(blobId: String): ByteArray {
        val entry = currentIndex.blobs[blobId] ?: error("Unknown blob $blobId")
        val ciphertext = store.read(entry.encOffset, intLength(entry.encLength))
        return blobCipher.decrypt(Hashing.fromHex(blobId), ciphertext, entry.chunkSize)
    }

    /**
     * Changes the vault's master password. Derives a fresh KEK from [newPassword] (new KDF
     * params + random salt) and re-wraps the **current DEK** under it, then rewrites the fixed
     * cleartext header. The DEK and all blobs are untouched — only the header's wrapped-DEK and
     * KDF params change — so this is cheap regardless of vault size. The vault must be open
     * (the DEK is already in memory); the old password is not required.
     */
    fun changePassword(newPassword: CharArray) {
        val params = KdfParams.newRandom()
        val kek = Argon2idKdf.deriveKey(newPassword, params)
        val wrapped = try {
            KeyEnvelope.wrap(kek, dek)
        } finally {
            kek.fill(0)
        }
        header = header.copy(
            kdf = KdfParamsDto.from(params),
            wrappedDek = Base64.getEncoder().encodeToString(wrapped),
            // Changing the password invalidates every recovery code (they wrap the DEK and were
            // handed out against the old credential set).
            recovery = null,
        )
        store.writeAt(0, VaultHeaderCodec.encode(header))
    }

    /** True if this vault currently has any valid recovery codes. */
    fun hasRecoveryCodes(): Boolean = header.recovery != null

    /**
     * (Re)places the recovery codes: derives a key from each code with fresh shared Argon2id
     * params and wraps the **current DEK** under it, then rewrites the header. Requires the vault
     * to be open (DEK in memory). Replaces any previous set.
     */
    fun setRecoveryCodes(codes: List<CharArray>, params: KdfParams = KdfParams.newRandom()) {
        require(codes.isNotEmpty()) { "No recovery codes provided" }
        val wraps = codes.map { code ->
            val key = Argon2idKdf.deriveKey(RecoveryCodes.normalize(String(code)).toCharArray(), params)
            try {
                Base64.getEncoder().encodeToString(KeyEnvelope.wrap(key, dek))
            } finally {
                key.fill(0)
            }
        }
        header = header.copy(recovery = RecoveryBlockDto(kdf = KdfParamsDto.from(params), wraps = wraps))
        store.writeAt(0, VaultHeaderCodec.encode(header))
    }

    /** Replaces folders + documents (keeping the blob table) and persists. */
    fun commit(folders: Map<String, Folder>, documents: Map<String, Document>) {
        currentIndex = currentIndex.copy(folders = folders, documents = documents)
        persistIndex()
    }

    /** Replaces the entire index (used by sync after a merge) and persists. */
    fun commit(index: VaultIndex) {
        currentIndex = index
        persistIndex()
    }

    private fun persistIndex() {
        val plain = JSON.encodeToString(VaultIndex.serializer(), currentIndex).toByteArray(Charsets.UTF_8)
        val sealed = Aead.sealWithNonce(dek, plain, INDEX_AAD)
        val offset = store.append(sealed)
        header = header.copy(indexOffset = offset, indexLength = sealed.size)
        store.writeAt(0, VaultHeaderCodec.encode(header))
    }

    /**
     * Writes a compacted copy of this vault into [target]: only blobs referenced by
     * non-deleted documents are carried over, dropping accumulated garbage. Blob ciphertext
     * is position-independent so it is copied verbatim (no re-encryption). Returns a
     * [VaultFile] open on [target].
     */
    fun compactTo(target: VaultStore): VaultFile {
        val placeholder = header.copy(indexOffset = VaultHeaderCodec.HEADER_RESERVED.toLong(), indexLength = 0)
        target.writeAt(0, VaultHeaderCodec.encode(placeholder))

        val referenced = currentIndex.referencedBlobIds()
        val newBlobs = LinkedHashMap<String, BlobEntry>()
        for (id in referenced) {
            val entry = currentIndex.blobs[id] ?: continue
            val ciphertext = store.read(entry.encOffset, intLength(entry.encLength))
            val offset = target.append(ciphertext)
            newBlobs[id] = entry.copy(encOffset = offset)
        }
        val newIndex = currentIndex.copy(blobs = newBlobs)
        val compacted = VaultFile(target, dek.copyOf(), placeholder, newIndex)
        compacted.persistIndex()
        return compacted
    }

    override fun close() {
        dek.fill(0)
        store.close()
    }

    /** Narrows a stored byte length to Int, rejecting values that would overflow (corrupt/crafted index). */
    private fun intLength(value: Long): Int {
        require(value in 0..Int.MAX_VALUE.toLong()) { "Blob length out of range: $value" }
        return value.toInt()
    }

    companion object {
        private val INDEX_AAD = "docsync:index:v1".toByteArray(Charsets.US_ASCII)
        private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        /** Creates a brand-new empty vault in [store], protected by [password]. */
        fun create(store: VaultStore, password: CharArray, kdfParams: KdfParams? = null, chunkSize: Int = BlobCipher.DEFAULT_CHUNK_SIZE): VaultFile {
            val params = kdfParams ?: KdfParams.newRandom()
            val kek = Argon2idKdf.deriveKey(password, params)
            val dek = KeyEnvelope.generateDek()
            val wrapped = try {
                KeyEnvelope.wrap(kek, dek)
            } finally {
                kek.fill(0)
            }
            val header = VaultHeader(
                kdf = KdfParamsDto.from(params),
                wrappedDek = Base64.getEncoder().encodeToString(wrapped),
                chunkSize = chunkSize,
                indexOffset = VaultHeaderCodec.HEADER_RESERVED.toLong(),
                indexLength = 0,
            )
            store.writeAt(0, VaultHeaderCodec.encode(header))
            val file = VaultFile(store, dek, header, VaultIndex())
            file.persistIndex()
            return file
        }

        /** Opens an existing vault with the master [password] (first import on a device). */
        fun open(store: VaultStore, password: CharArray): VaultFile {
            val header = readHeader(store)
            val kek = Argon2idKdf.deriveKey(password, header.kdf.toKdfParams())
            val dek = try {
                KeyEnvelope.unwrap(kek, Base64.getDecoder().decode(header.wrappedDek))
            } finally {
                kek.fill(0)
            }
            return openWith(store, header, dek)
        }

        /**
         * Opens a vault using a **recovery code** instead of the password. On success the matched
         * code is struck from the header (single-use) and the header is rewritten on [store], so
         * [store] must be the writable copy you intend to keep. Returns null if there are no
         * recovery codes or none matches [code].
         */
        fun openWithRecoveryCode(store: VaultStore, code: CharArray): VaultFile? {
            val header = readHeader(store)
            val recovery = header.recovery ?: return null
            val key = Argon2idKdf.deriveKey(RecoveryCodes.normalize(String(code)).toCharArray(), recovery.kdf.toKdfParams())
            try {
                recovery.wraps.forEachIndexed { i, wrapB64 ->
                    val dek = try {
                        KeyEnvelope.unwrap(key, Base64.getDecoder().decode(wrapB64))
                    } catch (e: DecryptionException) {
                        return@forEachIndexed
                    }
                    // Match. Strike this code (single-use) before opening.
                    val remaining = recovery.wraps.filterIndexed { j, _ -> j != i }
                    val updated = header.copy(recovery = if (remaining.isEmpty()) null else recovery.copy(wraps = remaining))
                    store.writeAt(0, VaultHeaderCodec.encode(updated))
                    return openWith(store, updated, dek)
                }
            } finally {
                key.fill(0)
            }
            return null
        }

        /** Opens an existing vault with an already-unwrapped DEK (local unlock, no password). */
        fun openWithDek(store: VaultStore, dek: ByteArray): VaultFile {
            val header = readHeader(store)
            return openWith(store, header, dek.copyOf())
        }

        private fun openWith(store: VaultStore, header: VaultHeader, dek: ByteArray): VaultFile {
            val index = readIndex(store, dek, header)
            return VaultFile(store, dek, header, index)
        }

        private fun readHeader(store: VaultStore): VaultHeader {
            val size = store.size()
            require(size >= VaultHeaderCodec.HEADER_RESERVED) { "File too small to be a vault" }
            return VaultHeaderCodec.decode(store.read(0, VaultHeaderCodec.HEADER_RESERVED))
        }

        private fun readIndex(store: VaultStore, dek: ByteArray, header: VaultHeader): VaultIndex {
            if (header.indexLength == 0) return VaultIndex()
            val bytes = store.read(header.indexOffset, header.indexLength)
            val plain = Aead.openWithNonce(dek, bytes, INDEX_AAD)
            return JSON.decodeFromString(VaultIndex.serializer(), String(plain, Charsets.UTF_8))
        }
    }
}
