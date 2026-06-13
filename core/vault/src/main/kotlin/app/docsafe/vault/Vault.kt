package app.docsafe.vault

/**
 * Marker for the vault module. Real implementations (domain model, seekable file format,
 * merge engine, VaultStore) land in Stage 3; this keeps the module compiling.
 */
internal const val VAULT_MODULE = "docsync-core-vault"
