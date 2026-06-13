package app.docsafe.vault.store

import java.io.Closeable

/**
 * Seekable backing store for a vault file. This is the single abstraction that makes the
 * vault format work identically on a local file (`RandomAccessFile`) now and over Google
 * Drive later (HTTP **Range** GETs + resumable uploads): the format only ever needs to read
 * a byte range, append bytes, or overwrite the fixed header region — never to read the whole
 * (potentially hundreds-of-MB) file.
 *
 * Implementations are blocking; callers that need async (Drive, UI) wrap calls on an IO
 * dispatcher.
 */
interface VaultStore : Closeable {
    /** Current total size of the store in bytes. */
    fun size(): Long

    /** Reads exactly [length] bytes starting at [offset]. Throws if fewer are available. */
    fun read(offset: Long, length: Int): ByteArray

    /** Appends [bytes] to the end of the store and returns the offset they were written at. */
    fun append(bytes: ByteArray): Long

    /** Overwrites [bytes] starting at [offset]. Used to rewrite the fixed-size header region. */
    fun writeAt(offset: Long, bytes: ByteArray)

    /** Truncates the store to [newSize] bytes (used during compaction). */
    fun truncate(newSize: Long)

    override fun close() {}
}
