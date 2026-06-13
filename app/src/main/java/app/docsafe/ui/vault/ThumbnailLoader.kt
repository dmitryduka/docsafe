package app.docsafe.ui.vault

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.docsafe.vault.ThumbnailGenerator
import app.docsafe.vault.VaultRepository
import app.docsafe.vault.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies preview thumbnails to the UI, cached in memory keyed by the thumbnail's blob id.
 *
 * Thumbnails are stored inside the vault as their own small blobs (see
 * [VaultRepository.addAttachment]); this just decrypts the tiny thumbnail blob — no need to
 * read the full image. For attachments created before stored thumbnails existed
 * ([Attachment.thumbnailBlobId] == null), it falls back to generating one from the full blob.
 */
@Singleton
class ThumbnailLoader @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val thumbnailGenerator: ThumbnailGenerator,
) {
    private val cache = LruCache<String, ImageBitmap>(CACHE_ENTRIES)

    suspend fun load(attachment: Attachment): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = attachment.thumbnailBlobId ?: attachment.blobId
        cache.get(key)?.let { return@withContext it }
        // Reads the stored thumbnail blob, or generates + persists one if missing.
        val bytes = vaultRepository.thumbnailBytes(attachment) ?: return@withContext null
        thumbnailGenerator.decodeStored(bytes)?.asImageBitmap()?.also { cache.put(key, it) }
    }

    private companion object {
        const val CACHE_ENTRIES = 128
    }
}
