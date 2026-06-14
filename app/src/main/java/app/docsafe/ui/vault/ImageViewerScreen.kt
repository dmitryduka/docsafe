package app.docsafe.ui.vault

import app.docsafe.ui.copyTextToClipboard
import app.docsafe.ui.exportToShareCache
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.docsafe.R
import app.docsafe.ocr.FieldKind
import app.docsafe.ocr.OcrCandidate
import app.docsafe.vault.activeDocument
import app.docsafe.vault.model.Attachment
import app.docsafe.vault.model.AttachmentKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen in-app image viewer with swipe between a document's images and pinch-to-zoom.
 * Open-in-another-app and share actions are available in the top bar, plus an OCR mode that
 * auto-detects field-like numbers/codes as tappable boxes (and a manual select-area fallback).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    documentId: String,
    startAttachmentId: String,
    onBack: () -> Unit,
) {
    val viewModel: VaultViewModel = hiltViewModel()
    val index by viewModel.index.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val document = index.activeDocument(documentId)
    val images = document?.attachments?.filter { it.kind == AttachmentKind.IMAGE }.orEmpty()
    if (images.isEmpty()) {
        // No images left (document/attachment removed) — navigate back as a side effect.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val startIndex = images.indexOfFirst { it.id == startAttachmentId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startIndex) { images.size }
    val current = images.getOrNull(pagerState.currentPage) ?: images.first()

    var ocrMode by remember { mutableStateOf(false) }
    var regionMode by remember { mutableStateOf(false) }
    // A candidate the user picked (tap or region) that's pending save into a field.
    var pending by remember { mutableStateOf<OcrCandidate?>(null) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(current.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = Color.White)
                    }
                },
                actions = {
                    // Toggle OCR field-extraction mode.
                    IconButton(onClick = { ocrMode = !ocrMode; if (!ocrMode) regionMode = false }) {
                        Icon(
                            Icons.Filled.TextFields,
                            contentDescription = stringResource(R.string.ocr_extract),
                            tint = if (ocrMode) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                    if (ocrMode) {
                        // Toggle manual region selection (fallback for whatever auto-detect misses).
                        IconButton(onClick = { regionMode = !regionMode }) {
                            Icon(
                                Icons.Filled.Crop,
                                contentDescription = stringResource(R.string.ocr_select_area),
                                tint = if (regionMode) MaterialTheme.colorScheme.primary else Color.White,
                            )
                        }
                    } else {
                        IconButton(onClick = { openExternally(context, viewModel, scope, current) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.open_in_another_app), tint = Color.White)
                        }
                        IconButton(onClick = { shareExternally(context, viewModel, scope, current) }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share), tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    titleContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            if (ocrMode) {
                Surface(color = Color.Black.copy(alpha = 0.6f)) {
                    Text(
                        stringResource(if (regionMode) R.string.ocr_hint_region else R.string.ocr_hint_auto),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !regionMode,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black),
        ) { page ->
            if (ocrMode) {
                OcrImage(
                    viewModel = viewModel,
                    attachment = images[page],
                    regionMode = regionMode,
                    onPick = { pending = it },
                )
            } else {
                ZoomableImage(viewModel, images[page])
            }
        }
    }

    pending?.let { candidate ->
        AddFieldDialog(
            onDismiss = { pending = null },
            onConfirm = { key, value ->
                viewModel.addField(documentId, key, value)
                copyText(context, value)
                pending = null
            },
            initialKey = candidate.kind.presetKey,
            initialValue = candidate.text,
        )
    }
}

@Composable
private fun ZoomableImage(viewModel: VaultViewModel, attachment: Attachment) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = attachment.blobId) {
        value = viewModel.fullImage(attachment.blobId)
    }
    // Keyed by blob id so each page starts un-zoomed and zoom doesn't bleed across swipes.
    var scale by remember(attachment.blobId) { mutableFloatStateOf(1f) }
    var offset by remember(attachment.blobId) { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { boxSize = it },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Image(
                bitmap = image,
                contentDescription = attachment.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(attachment.blobId) {
                        // Only consume gestures while zooming or zoomed in; at 1x a one-finger
                        // horizontal drag is left unconsumed so the HorizontalPager can page.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (scale > 1f || zoom != 1f) {
                                    val newScale = (scale * zoom).coerceIn(1f, 6f)
                                    offset = if (newScale > 1f) {
                                        val maxX = boxSize.width * (newScale - 1f) / 2f
                                        val maxY = boxSize.height * (newScale - 1f) / 2f
                                        Offset(
                                            (offset.x + pan.x).coerceIn(-maxX, maxX),
                                            (offset.y + pan.y).coerceIn(-maxY, maxY),
                                        )
                                    } else {
                                        Offset.Zero
                                    }
                                    scale = newScale
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
        }
    }
}

/**
 * Shows the image fitted (no zoom) with an OCR overlay. In auto mode every detected field is
 * outlined and tappable; in region mode the user drags a box and we OCR just that crop.
 */
@Composable
private fun OcrImage(
    viewModel: VaultViewModel,
    attachment: Attachment,
    regionMode: Boolean,
    onPick: (OcrCandidate) -> Unit,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = attachment.blobId) {
        value = viewModel.decodeForOcr(attachment.blobId)
    }
    val candidates by produceState(initialValue = emptyList<OcrCandidate>(), key1 = bitmap, key2 = regionMode) {
        val bmp = bitmap
        value = if (bmp != null && !regionMode) viewModel.detectFields(bmp) else emptyList()
    }
    val scope = rememberCoroutineScope()
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val highlight = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier.fillMaxSize().onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator(color = Color.White)
            return@Box
        }
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = attachment.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        val map = remember(containerSize, bmp.width, bmp.height) {
            FitMapping(containerSize, bmp.width, bmp.height)
        }

        if (!regionMode) {
            // Auto-detected boxes: draw outlines and hit-test taps against them.
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(candidates, map) {
                        detectTapGestures { tap ->
                            candidates.firstOrNull { map.toScreen(it.box).contains(tap) }?.let(onPick)
                        }
                    },
            ) {
                candidates.forEach { c ->
                    val r = map.toScreen(c.box)
                    drawRect(color = highlight.copy(alpha = 0.18f), topLeft = r.topLeft, size = r.size)
                    drawRect(color = highlight, topLeft = r.topLeft, size = r.size, style = Stroke(width = 3f))
                }
            }
        } else {
            // Manual region selection: drag a box, OCR the crop on release.
            var start by remember { mutableStateOf<Offset?>(null) }
            var currentPos by remember { mutableStateOf<Offset?>(null) }
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(map) {
                        detectDragGestures(
                            onDragStart = { start = it; currentPos = it },
                            onDrag = { change, _ -> currentPos = change.position },
                            onDragEnd = {
                                val s = start
                                val e = currentPos
                                start = null
                                currentPos = null
                                if (s != null && e != null) {
                                    val region = map.toBitmap(s, e, bmp.width, bmp.height)
                                    if (region.width() > 3 && region.height() > 3) {
                                        scope.launch {
                                            viewModel.recognizeRegion(bmp, region)?.let(onPick)
                                        }
                                    }
                                }
                            },
                            onDragCancel = { start = null; currentPos = null },
                        )
                    },
            ) {
                val s = start
                val e = currentPos
                if (s != null && e != null) {
                    val left = min(s.x, e.x)
                    val top = min(s.y, e.y)
                    val w = kotlin.math.abs(e.x - s.x)
                    val h = kotlin.math.abs(e.y - s.y)
                    drawRect(color = highlight.copy(alpha = 0.18f), topLeft = Offset(left, top), size = Size(w, h))
                    drawRect(color = highlight, topLeft = Offset(left, top), size = Size(w, h), style = Stroke(width = 3f))
                }
            }
        }
    }
}

/** Maps a fitted (ContentScale.Fit, centered) bitmap between bitmap pixels and screen pixels. */
private class FitMapping(container: IntSize, val bw: Int, val bh: Int) {
    private val scale: Float =
        if (bw <= 0 || bh <= 0 || container.width == 0 || container.height == 0) 1f
        else min(container.width.toFloat() / bw, container.height.toFloat() / bh)
    private val offX: Float = (container.width - bw * scale) / 2f
    private val offY: Float = (container.height - bh * scale) / 2f

    fun toScreen(box: android.graphics.Rect): androidx.compose.ui.geometry.Rect =
        androidx.compose.ui.geometry.Rect(
            left = offX + box.left * scale,
            top = offY + box.top * scale,
            right = offX + box.right * scale,
            bottom = offY + box.bottom * scale,
        )

    fun toBitmap(a: Offset, b: Offset, bw: Int, bh: Int): android.graphics.Rect {
        fun bx(x: Float) = ((x - offX) / scale).roundToInt().coerceIn(0, bw)
        fun by(y: Float) = ((y - offY) / scale).roundToInt().coerceIn(0, bh)
        val left = min(bx(a.x), bx(b.x))
        val right = max(bx(a.x), bx(b.x))
        val top = min(by(a.y), by(b.y))
        val bottom = max(by(a.y), by(b.y))
        return android.graphics.Rect(left, top, right, bottom)
    }
}

private fun openExternally(context: Context, viewModel: VaultViewModel, scope: CoroutineScope, attachment: Attachment) {
    scope.launch {
        val e = runCatching { exportToShareCache(context, viewModel, attachment) }.getOrNull() ?: return@launch
        viewModel.notifyExternalActivityStarting()
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(e.uri, e.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_with))) }
            .onFailure { if (it is ActivityNotFoundException) Unit }
    }
}

private fun shareExternally(context: Context, viewModel: VaultViewModel, scope: CoroutineScope, attachment: Attachment) {
    scope.launch {
        val e = runCatching { exportToShareCache(context, viewModel, attachment) }.getOrNull() ?: return@launch
        viewModel.notifyExternalActivityStarting()
        val intent = Intent(Intent.ACTION_SEND).setType(e.mime).putExtra(Intent.EXTRA_STREAM, e.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_via)))
    }
}

/** Copies plain text to the clipboard (marked sensitive) with a short confirmation toast. */
internal fun copyText(context: Context, value: String) {
    copyTextToClipboard(context, "value", value)
    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}
