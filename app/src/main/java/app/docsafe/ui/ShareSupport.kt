package app.docsafe.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import app.docsafe.ui.vault.VaultViewModel
import app.docsafe.vault.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared helpers for exporting decrypted attachments to other apps and for clipboard/date/toast.
 * Centralizes the security-sensitive "decrypt → write to private cache → hand out a FileProvider
 * URI" flow so the cache layout, the cache wipe, and the sensitivity flagging live in one place
 * (previously copy-pasted across the detail/viewer/browser screens, where cleanup had drifted).
 */

/** A decrypted attachment staged for sharing: a content:// URI plus its MIME type. */
data class ExportedBlob(val uri: Uri, val mime: String)

private const val SHARE_DIR = "shared"

/** Directories under cacheDir that may hold decrypted plaintext or staged vaults. Wiped on lock. */
private val PLAINTEXT_CACHE_DIRS = listOf("shared", "capture", "share_in", "import_tmp")

private fun fileProviderAuthority(context: Context): String = "${context.packageName}.fileprovider"

private fun shareDir(context: Context): File = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }

/**
 * Deletes every decrypted-plaintext file the app may have written to its private cache for
 * sharing/opening/capture/inbound-share. Called when the vault locks so plaintext does not
 * linger at rest after the user is done with it.
 */
fun wipeShareCaches(context: Context) {
    PLAINTEXT_CACHE_DIRS.forEach { name ->
        File(context.cacheDir, name).listFiles()?.forEach { it.deleteRecursively() }
    }
}

/** Decrypts [attachment] into the share cache and returns its FileProvider URI + MIME. */
suspend fun exportToShareCache(context: Context, viewModel: VaultViewModel, attachment: Attachment): ExportedBlob =
    withContext(Dispatchers.IO) {
        val bytes = viewModel.readBlob(attachment.blobId)
        val dir = shareDir(context).also { it.listFiles()?.forEach { f -> f.delete() } }
        val file = File(dir, safeFileName(attachment.fileName))
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
        ExportedBlob(uri, attachment.mimeType ?: mimeFromName(attachment.fileName) ?: "application/octet-stream")
    }

/** Decrypts several attachments into the share cache and returns their FileProvider URIs. */
suspend fun exportManyToShareCache(context: Context, viewModel: VaultViewModel, attachments: List<Attachment>): List<Uri> =
    withContext(Dispatchers.IO) {
        val dir = shareDir(context).also { it.listFiles()?.forEach { f -> f.delete() } }
        attachments.mapIndexedNotNull { i, att ->
            runCatching {
                val bytes = viewModel.readBlob(att.blobId)
                val file = File(dir, "${i}_" + safeFileName(att.fileName))
                file.writeBytes(bytes)
                FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
            }.getOrNull()
        }
    }

fun safeFileName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "file" }

fun mimeFromName(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return null
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
}

fun formatDate(epochMs: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMs))

fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/** Copies a content URI to the clipboard, marked sensitive so the OS redacts previews/history. */
fun copyUriToClipboard(context: Context, label: String, uri: Uri) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newUri(context.contentResolver, label, uri)
    markSensitive(clip)
    clipboard.setPrimaryClip(clip)
}

/** Copies plain text to the clipboard, marked sensitive (API 33+) to keep it out of history. */
fun copyTextToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, value)
    markSensitive(clip)
    clipboard.setPrimaryClip(clip)
}

private fun markSensitive(clip: ClipData) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
}
