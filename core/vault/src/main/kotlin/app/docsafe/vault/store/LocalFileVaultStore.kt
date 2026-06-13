package app.docsafe.vault.store

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

/**
 * A [VaultStore] backed by a local file, using `RandomAccessFile` for true random access.
 *
 * A single `RandomAccessFile` carries one shared file pointer, so every operation here is a
 * `seek` followed by a read/write. Those pairs MUST be atomic with respect to each other:
 * the UI reads many blobs concurrently (e.g. a grid of thumbnails decoding on `Dispatchers.IO`),
 * and without serialization two reads would interleave their seeks and return bytes from the
 * wrong offset — which then fails AES-GCM authentication and silently drops the blob. All access
 * is therefore guarded by a single lock.
 */
class LocalFileVaultStore(private val file: File) : VaultStore {

    private val raf = RandomAccessFile(file, "rw")
    private val lock = Any()

    override fun size(): Long = synchronized(lock) { raf.length() }

    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "offset must be >= 0" }
        require(length >= 0) { "length must be >= 0" }
        return synchronized(lock) {
            val out = ByteArray(length)
            raf.seek(offset)
            var read = 0
            while (read < length) {
                val n = raf.read(out, read, length - read)
                if (n < 0) throw EOFException("Requested $length bytes at $offset but file ended after $read")
                read += n
            }
            out
        }
    }

    override fun append(bytes: ByteArray): Long = synchronized(lock) {
        val offset = raf.length()
        raf.seek(offset)
        raf.write(bytes)
        offset
    }

    override fun writeAt(offset: Long, bytes: ByteArray) = synchronized(lock) {
        raf.seek(offset)
        raf.write(bytes)
    }

    override fun truncate(newSize: Long) = synchronized(lock) {
        raf.setLength(newSize)
    }

    override fun close() = synchronized(lock) {
        raf.close()
    }
}
