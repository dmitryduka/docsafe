package app.docsafe.security

import kotlinx.serialization.Serializable

/** A vault known to this device. [fileName] is the `.dsvault` in app-private storage. */
@Serializable
data class VaultMeta(
    val id: String,
    val name: String,
    val fileName: String,
    val createdAt: Long,
)

/**
 * The set of vaults on this device, persisted (encrypted) in [SecureStore]. Each vault's DEK is
 * wrapped by the device **master key** (which is in turn wrapped by biometric / PIN) and stored
 * here as base64 in [wrappedDeks], keyed by vault id. One master key + one unlock therefore opens
 * any vault.
 */
@Serializable
data class VaultRegistry(
    val vaults: List<VaultMeta> = emptyList(),
    val activeVaultId: String? = null,
    /** vaultId -> base64(masterKey-wrapped DEK). */
    val wrappedDeks: Map<String, String> = emptyMap(),
) {
    val active: VaultMeta? get() = vaults.firstOrNull { it.id == activeVaultId } ?: vaults.firstOrNull()

    fun meta(id: String): VaultMeta? = vaults.firstOrNull { it.id == id }
}
