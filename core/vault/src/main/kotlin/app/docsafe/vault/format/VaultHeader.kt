package app.docsafe.vault.format

import app.docsafe.crypto.KdfParams
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * The cleartext header that lives at the very front of a vault file in a fixed-size reserved
 * region. It is readable without any key and tells a reader (a) how to derive the
 * key-encryption key from the password ([kdf]), (b) the password-wrapped DEK, and (c) where
 * the encrypted index lives ([indexOffset]/[indexLength]). Because the region is fixed-size,
 * the pointer can be rewritten in place after appends without shifting the blob region.
 */
@Serializable
data class VaultHeader(
    val kdf: KdfParamsDto,
    val wrappedDek: String,
    val chunkSize: Int,
    val indexOffset: Long,
    val indexLength: Int,
    /**
     * Optional recovery block: the DEK wrapped under each recovery-code-derived key, plus the
     * shared Argon2id params. Null when no recovery codes are set (the default for every
     * pre-1.0.8 vault, so old files decode unchanged).
     */
    val recovery: RecoveryBlockDto? = null,
)

/** DEK wrapped under one-or-more recovery codes (see [app.docsafe.vault.RecoveryCodes]). */
@Serializable
data class RecoveryBlockDto(
    val kdf: KdfParamsDto,
    /** Base64 of `KeyEnvelope.wrap(Argon2id(code), dek)`, one per still-valid code. */
    val wraps: List<String>,
)

@Serializable
data class KdfParamsDto(
    val algorithm: String,
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
    val salt: String,
) {
    fun toKdfParams(): KdfParams = KdfParams(
        algorithm = algorithm,
        memoryKib = memoryKib,
        iterations = iterations,
        parallelism = parallelism,
        salt = Base64.getDecoder().decode(salt),
    )

    companion object {
        fun from(params: KdfParams): KdfParamsDto = KdfParamsDto(
            algorithm = params.algorithm,
            memoryKib = params.memoryKib,
            iterations = params.iterations,
            parallelism = params.parallelism,
            salt = Base64.getEncoder().encodeToString(params.salt),
        )
    }
}

/** Reads/writes the fixed-size header region: `MAGIC | version | u32 len | json | padding`. */
object VaultHeaderCodec {
    val MAGIC = "DSVAULT".toByteArray(Charsets.US_ASCII)
    const val VERSION: Int = 1
    const val HEADER_RESERVED = 4096
    private const val PREFIX_LEN = 7 + 1 + 4 // magic + version + u32 length

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** Encodes [header] into a [HEADER_RESERVED]-byte region ready to write at offset 0. */
    fun encode(header: VaultHeader): ByteArray {
        val body = json.encodeToString(VaultHeader.serializer(), header).toByteArray(Charsets.UTF_8)
        require(PREFIX_LEN + body.size <= HEADER_RESERVED) {
            "Header too large: ${PREFIX_LEN + body.size} > $HEADER_RESERVED"
        }
        val region = ByteArray(HEADER_RESERVED)
        System.arraycopy(MAGIC, 0, region, 0, MAGIC.size)
        region[7] = VERSION.toByte()
        writeU32(region, 8, body.size)
        System.arraycopy(body, 0, region, PREFIX_LEN, body.size)
        return region
    }

    /** Decodes a header from the leading [HEADER_RESERVED] bytes of [region]. */
    fun decode(region: ByteArray): VaultHeader {
        require(region.size >= PREFIX_LEN) { "Header region too small" }
        for (i in MAGIC.indices) {
            require(region[i] == MAGIC[i]) { "Not a DocSafe vault file (bad magic)" }
        }
        val version = region[7].toInt() and 0xFF
        require(version == VERSION) { "Unsupported vault version: $version" }
        val len = readU32(region, 8)
        require(PREFIX_LEN + len <= region.size) { "Declared header length exceeds region" }
        val body = String(region, PREFIX_LEN, len, Charsets.UTF_8)
        return json.decodeFromString(VaultHeader.serializer(), body)
    }

    private fun writeU32(buf: ByteArray, off: Int, value: Int) {
        buf[off] = (value ushr 24).toByte()
        buf[off + 1] = (value ushr 16).toByte()
        buf[off + 2] = (value ushr 8).toByte()
        buf[off + 3] = value.toByte()
    }

    private fun readU32(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)
}
