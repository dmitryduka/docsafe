package app.docsafe.sync

/**
 * Marker for the sync module. Real implementations (VaultStore, LocalFileVaultStore,
 * SyncBackend, DriveVaultStore stub) land in Stage 7; this keeps the module compiling.
 */
internal const val SYNC_MODULE = "docsync-core-sync"
