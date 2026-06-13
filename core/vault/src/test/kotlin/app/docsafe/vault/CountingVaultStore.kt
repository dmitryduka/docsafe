package app.docsafe.vault

import app.docsafe.vault.store.VaultStore

/**
 * Test store that records read activity, so a test can prove that reading a single blob only
 * touches that blob's byte range — not the whole (potentially huge) file.
 */
class CountingVaultStore(private val delegate: VaultStore) : VaultStore {
    var bytesRead: Long = 0L
        private set
    val reads = mutableListOf<Pair<Long, Int>>()

    fun resetCounters() {
        bytesRead = 0L
        reads.clear()
    }

    override fun size(): Long = delegate.size()

    override fun read(offset: Long, length: Int): ByteArray {
        bytesRead += length
        reads.add(offset to length)
        return delegate.read(offset, length)
    }

    override fun append(bytes: ByteArray): Long = delegate.append(bytes)
    override fun writeAt(offset: Long, bytes: ByteArray) = delegate.writeAt(offset, bytes)
    override fun truncate(newSize: Long) = delegate.truncate(newSize)
    override fun close() = delegate.close()
}
