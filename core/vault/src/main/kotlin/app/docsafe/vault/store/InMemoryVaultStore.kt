package app.docsafe.vault.store

import java.io.EOFException

/** In-memory [VaultStore] for tests and transient use. Grows as needed. */
class InMemoryVaultStore(initial: ByteArray = ByteArray(0)) : VaultStore {

    private var data: ByteArray = initial.copyOf()
    private var length: Int = initial.size

    override fun size(): Long = length.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0)
        if (offset + length > this.length) {
            throw EOFException("Requested $length bytes at $offset but size is ${this.length}")
        }
        return data.copyOfRange(offset.toInt(), (offset + length).toInt())
    }

    override fun append(bytes: ByteArray): Long {
        val offset = length.toLong()
        ensureCapacity(length + bytes.size)
        System.arraycopy(bytes, 0, data, length, bytes.size)
        length += bytes.size
        return offset
    }

    override fun writeAt(offset: Long, bytes: ByteArray) {
        ensureCapacity((offset + bytes.size).toInt())
        System.arraycopy(bytes, 0, data, offset.toInt(), bytes.size)
        if (offset + bytes.size > length) length = (offset + bytes.size).toInt()
    }

    override fun truncate(newSize: Long) {
        length = newSize.toInt()
    }

    /** Snapshot of the current bytes — handy for round-tripping a vault through bytes. */
    fun toByteArray(): ByteArray = data.copyOf(length)

    private fun ensureCapacity(min: Int) {
        if (data.size < min) {
            var newCap = if (data.isEmpty()) 1024 else data.size
            while (newCap < min) newCap *= 2
            data = data.copyOf(newCap)
        }
    }
}
