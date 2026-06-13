package app.docsafe.crypto

/**
 * Marker for the crypto module. Real implementations (Argon2idKdf, BlobCipher, envelope
 * key wrap/unwrap) land in Stage 2; this keeps the module compiling in the skeleton.
 */
internal const val CRYPTO_MODULE = "docsync-core-crypto"
