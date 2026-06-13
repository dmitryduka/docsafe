package app.docsafe.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The category we guess for a recognized token, used to pre-select the field key in the
 * add-field dialog (Number/Date/Code) and to label candidates in batch mode.
 */
enum class FieldKind(val presetKey: String) {
    NUMBER("Number"),
    DATE("Date"),
    CODE("Code"),
    TEXT("Text"),
}

/**
 * A single extracted, "field-like" piece of text together with its location on the source
 * bitmap (in bitmap pixel coordinates). [box] is used to draw a tappable highlight over the
 * image; in batch mode only [text]/[kind] matter.
 */
data class OcrCandidate(
    val text: String,
    val box: Rect,
    val kind: FieldKind,
)

/**
 * On-device OCR over [ML Kit Text Recognition v2][TextRecognizer]. Everything runs locally;
 * no image or text leaves the device. We don't just return the raw recognized text — we walk
 * the recognizer's geometric hierarchy (block → line → element) and keep only tokens that look
 * like a *field* (a number, date, or code), so prose paragraphs don't get highlighted.
 */
@Singleton
class OcrEngine @Inject constructor() {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Runs recognition over the whole bitmap and returns the de-duplicated field candidates. */
    suspend fun detectFields(bitmap: Bitmap): List<OcrCandidate> {
        val text = recognize(bitmap)
        val out = ArrayList<OcrCandidate>()
        val seen = HashSet<String>()

        fun add(value: String, box: Rect?, kind: FieldKind) {
            if (box == null) return
            val key = normalize(value)
            if (key.isEmpty() || !seen.add(key)) return
            out.add(OcrCandidate(value.trim(), box, kind))
        }

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val elements = line.elements
                // Element-level: accurate boxes for single-token numbers/codes (e.g. "AB1234567").
                for (element in elements) {
                    classify(element.text)?.let { add(element.text, element.boundingBox, it) }
                }
                // Line-level: catch grouped numbers split across tokens ("1234 5678 9012").
                if (elements.size > 1) {
                    val joined = line.text
                    val digits = joined.count { it.isDigit() }
                    val onlyNumberish = joined.all { it.isDigit() || it == ' ' || it in "-/." }
                    if (onlyNumberish && digits >= 6) add(joined, line.boundingBox, FieldKind.NUMBER)
                }
            }
        }
        return out
    }

    /**
     * OCRs a sub-region of [bitmap] (manual box/circle selection) and returns the best field
     * candidate found there, or the raw recognized text as [FieldKind.TEXT] if nothing matches.
     */
    suspend fun recognizeRegion(bitmap: Bitmap, region: Rect): OcrCandidate? {
        val left = region.left.coerceIn(0, bitmap.width)
        val top = region.top.coerceIn(0, bitmap.height)
        val right = region.right.coerceIn(left, bitmap.width)
        val bottom = region.bottom.coerceIn(top, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w < 4 || h < 4) return null
        val crop = Bitmap.createBitmap(bitmap, left, top, w, h)
        val text = try {
            recognize(crop)
        } finally {
            if (crop != bitmap) crop.recycle()
        }
        val raw = text.text.trim()
        if (raw.isEmpty()) return null
        // Prefer a field-like token inside the crop; fall back to the whole recognized string.
        val best = text.textBlocks
            .flatMap { it.lines }
            .flatMap { listOf(it.text) + it.elements.map { e -> e.text } }
            .firstNotNullOfOrNull { t -> classify(t)?.let { OcrCandidate(t.trim(), region, it) } }
        return best ?: OcrCandidate(raw.replace('\n', ' '), region, FieldKind.TEXT)
    }

    private suspend fun recognize(bitmap: Bitmap): Text =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }

    private companion object {
        private val DATE_RE = Regex("""\d{1,4}[./-]\d{1,2}([./-]\d{1,4})?""")

        /** Returns the field kind for a token, or null if it doesn't look like a field. */
        fun classify(raw: String): FieldKind? {
            val t = raw.trim()
            if (t.length < 3) return null
            val digits = t.count { it.isDigit() }
            if (digits == 0) return null
            if (DATE_RE.matches(t)) return FieldKind.DATE
            val letters = t.count { it.isLetter() }
            // Code: alphanumeric mix (IDs, serials, plates), e.g. "X7B-22Q".
            if (letters > 0 && t.length >= 4 && t.all { it.isLetterOrDigit() || it in "-/" }) {
                return FieldKind.CODE
            }
            // Number: digits with common separators only, at least 3 digits.
            if (letters == 0 && digits >= 3 && t.all { it.isDigit() || it in " .,-/" }) {
                return FieldKind.NUMBER
            }
            return null
        }

        fun normalize(value: String): String =
            value.lowercase().filter { !it.isWhitespace() }
    }
}
