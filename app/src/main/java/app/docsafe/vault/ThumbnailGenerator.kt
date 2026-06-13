package app.docsafe.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import app.docsafe.vault.model.AttachmentKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Produces small preview bitmaps for attachments. Used both to bake a thumbnail blob into the
 * vault at add time and to regenerate one on the fly for legacy attachments that predate
 * stored thumbnails. EXIF orientation is applied so camera photos aren't rotated.
 */
@Singleton
class ThumbnailGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** A downscaled, correctly-oriented preview bitmap for an image/PDF, or null otherwise. */
    fun generateBitmap(bytes: ByteArray, kind: AttachmentKind): Bitmap? = when (kind) {
        AttachmentKind.IMAGE -> decodeImageOriented(bytes, IMAGE_TARGET_PX)
        AttachmentKind.PDF -> renderPdfFirstPage(bytes)
        AttachmentKind.OTHER -> null
    }

    /** Decodes a correctly-oriented image downscaled to at most [maxPx] on its longest side. */
    fun decodeOriented(bytes: ByteArray, maxPx: Int): Bitmap? = decodeImageOriented(bytes, maxPx)

    /** Encodes a thumbnail of [bytes] as JPEG, ready to store as a blob (null if not previewable). */
    fun generateJpeg(bytes: ByteArray, kind: AttachmentKind): ByteArray? {
        val bitmap = generateBitmap(bytes, kind) ?: return null
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    /** Decodes already-small (stored) thumbnail bytes for display. */
    fun decodeStored(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun decodeImageOriented(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return applyExifOrientation(bitmap, readExifOrientation(bytes))
    }

    private fun readExifOrientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    private fun renderPdfFirstPage(bytes: ByteArray): Bitmap? {
        val temp = File.createTempFile("thumb_", ".pdf", context.cacheDir)
        return try {
            temp.writeBytes(bytes)
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { page ->
                        // Render the page at high resolution so text in the preview stays crisp.
                        val scale = PDF_TARGET_PX.toFloat() / max(page.width, page.height)
                        val w = max(1, (page.width * scale).toInt())
                        val h = max(1, (page.height * scale).toInt())
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    private fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxPx && h / 2 >= maxPx) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private companion object {
        // Larger targets keep previews sharp at the bigger grid sizes; PDFs need extra
        // resolution for legible text.
        const val IMAGE_TARGET_PX = 512
        const val PDF_TARGET_PX = 1500
        const val JPEG_QUALITY = 90
    }
}
