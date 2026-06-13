package app.docsafe.ui.vault

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.docsafe.R
import app.docsafe.vault.activeDocument
import app.docsafe.vault.model.Attachment
import app.docsafe.vault.model.AttachmentKind
import app.docsafe.vault.model.DocField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ThumbSize(val cell: Dp, val label: String) {
    SMALL(96.dp, "S"),
    MEDIUM(140.dp, "M"),
    LARGE(190.dp, "L"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun DocumentDetailScreen(
    documentId: String,
    viewModel: VaultViewModel,
    onNavigateUp: () -> Unit,
    onOpenImage: (attachmentId: String) -> Unit,
) {
    val index by viewModel.index.collectAsStateWithLifecycle()
    val document = index.activeDocument(documentId)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var showFieldDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var thumbSize by remember { mutableStateOf(ThumbSize.MEDIUM) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    if (document == null) {
        onNavigateUp()
        return
    }
    val selectionMode = selected.isNotEmpty()

    fun openExternally(attachment: Attachment) {
        scope.launch {
            val result = runCatching { exportToCache(context, viewModel, attachment) }.getOrNull()
                ?: return@launch toast(context, context.getString(R.string.err_cant_open))
            viewModel.notifyExternalActivityStarting()
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(result.uri, result.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_with)))
            } catch (e: ActivityNotFoundException) {
                toast(context, context.getString(R.string.err_no_app))
            }
        }
    }

    fun shareOne(attachment: Attachment) {
        scope.launch {
            val result = runCatching { exportToCache(context, viewModel, attachment) }.getOrNull()
                ?: return@launch toast(context, context.getString(R.string.err_cant_share))
            viewModel.notifyExternalActivityStarting()
            val intent = Intent(Intent.ACTION_SEND)
                .setType(result.mime)
                .putExtra(Intent.EXTRA_STREAM, result.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_via)))
        }
    }

    fun shareSelected() {
        val attachments = document.attachments.filter { it.id in selected }
        if (attachments.isEmpty()) return
        scope.launch {
            val uris = withContext(Dispatchers.IO) { exportMany(context, viewModel, attachments) }
            if (uris.isEmpty()) return@launch
            viewModel.notifyExternalActivityStarting()
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_via)))
            selected = emptySet()
        }
    }

    fun openTap(attachment: Attachment) {
        when {
            selectionMode -> selected = if (attachment.id in selected) selected - attachment.id else selected + attachment.id
            attachment.kind == AttachmentKind.IMAGE -> onOpenImage(attachment.id)
            else -> openExternally(attachment)
        }
    }

    // "Save to device": pick a destination via SAF, then write the decrypted blob there.
    var pendingSave by remember { mutableStateOf<Attachment?>(null) }
    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val att = pendingSave
        pendingSave = null
        if (uri != null && att != null) {
            scope.launch {
                val ok = saveAttachmentToUri(context, viewModel, att, uri)
                toast(context, context.getString(if (ok) R.string.file_saved else R.string.err_cant_save_file))
            }
        }
    }
    fun saveToDevice(attachment: Attachment) {
        pendingSave = attachment
        viewModel.notifyExternalActivityStarting()
        saveFileLauncher.launch(safeFileName(attachment.fileName))
    }

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        importFrom(context, viewModel, documentId, scope, uris)
    }
    val pickAnyFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importFrom(context, viewModel, documentId, scope, uris)
    }
    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val scan = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
            .fromActivityResultIntent(result.data) ?: return@rememberLauncherForActivityResult
        scan.pages?.forEachIndexed { i, page ->
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(page.imageUri)?.use { it.readBytes() }
                } ?: return@launch
                viewModel.addAttachment(documentId, bytes, AttachmentKind.IMAGE, "Scan ${i + 1}.jpg", "image/jpeg")
            }
        }
    }
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = pendingCaptureFile
        pendingCaptureFile = null
        if (saved && file != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { file.readBytes().also { file.delete() } }
                viewModel.addAttachment(documentId, bytes, AttachmentKind.IMAGE, "Photo.jpg", "image/jpeg")
            }
        }
    }

    fun launchScanner() {
        val activity = context as? Activity ?: return
        val options = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .build()
        viewModel.notifyExternalActivityStarting()
        com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { sender -> scannerLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { toast(context, context.getString(R.string.err_scanner)) }
    }

    fun launchCamera() {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "capture_${System.nanoTime()}.jpg")
        pendingCaptureFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        viewModel.notifyExternalActivityStarting()
        try {
            cameraLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            toast(context, context.getString(R.string.err_no_camera))
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selected.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    },
                    actions = {
                        IconButton(onClick = { shareSelected() }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share_selected))
                        }
                        IconButton(onClick = {
                            selected.forEach { viewModel.removeAttachment(documentId, it) }
                            selected = emptySet()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remove_selected))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(document.name) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.setDocumentStarred(documentId, !document.starred) }) {
                            Icon(
                                if (document.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = "Star",
                                tint = if (document.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menuOpen = false; renaming = true })
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_document)) },
                                    onClick = { menuOpen = false; viewModel.deleteDocument(documentId); onNavigateUp() },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { addMenuOpen = true },
                        icon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                        text = { Text(stringResource(R.string.action_add)) },
                    )
                    DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_text_field)) },
                            leadingIcon = { Icon(Icons.Filled.TextFields, null) },
                            onClick = { addMenuOpen = false; showFieldDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_tag)) },
                            leadingIcon = { Icon(Icons.Filled.Description, null) },
                            onClick = { addMenuOpen = false; showTagDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.scan_document)) },
                            leadingIcon = { Icon(Icons.Filled.DocumentScanner, null) },
                            onClick = { addMenuOpen = false; launchScanner() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.take_photo)) },
                            leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) },
                            onClick = { addMenuOpen = false; launchCamera() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.photo_library)) },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) },
                            onClick = {
                                addMenuOpen = false
                                viewModel.notifyExternalActivityStarting()
                                pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browse_files)) },
                            leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                            onClick = {
                                addMenuOpen = false
                                viewModel.notifyExternalActivityStarting()
                                pickAnyFiles.launch(arrayOf("*/*"))
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val attachments = document.attachments
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = thumbSize.cell),
            contentPadding = PaddingValues(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(
                        stringResource(R.string.added_on, formatDate(document.createdAt)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    TagsRow(
                        tags = document.tags,
                        onRemove = { viewModel.removeTag(documentId, it) },
                        onAdd = { showTagDialog = true },
                    )
                    if (document.fields.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FieldsTable(
                            fields = document.fields,
                            onCopyValue = { copyText(context, it) },
                            onRemove = { viewModel.removeField(documentId, it) },
                        )
                    }
                }
            }
            if (attachments.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.thumbnail_size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        ThumbSize.entries.forEach { size ->
                            FilterChip(
                                selected = thumbSize == size,
                                onClick = { thumbSize = size },
                                label = { Text(size.label) },
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
                items(attachments, key = { it.id }) { attachment ->
                    AttachmentCell(
                        attachment = attachment,
                        viewModel = viewModel,
                        selected = attachment.id in selected,
                        selectionMode = selectionMode,
                        onTap = { openTap(attachment) },
                        onLongPress = { selected = selected + attachment.id },
                        onOpen = { openExternally(attachment) },
                        onShare = { shareOne(attachment) },
                        onCopy = { copyToClipboard(context, viewModel, scope, attachment) },
                        onSave = { saveToDevice(attachment) },
                        onRemove = { viewModel.removeAttachment(documentId, attachment.id) },
                    )
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.no_files_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }

    if (renaming) {
        NameInputDialog(
            title = stringResource(R.string.rename_document),
            initial = document.name,
            onDismiss = { renaming = false },
            onConfirm = { viewModel.renameDocument(documentId, it); renaming = false },
        )
    }
    if (showFieldDialog) {
        AddFieldDialog(
            onDismiss = { showFieldDialog = false },
            onConfirm = { key, value -> viewModel.addField(documentId, key, value); showFieldDialog = false },
        )
    }
    if (showTagDialog) {
        NameInputDialog(
            title = stringResource(R.string.add_tag),
            initial = "",
            onDismiss = { showTagDialog = false },
            onConfirm = { viewModel.addTag(documentId, it); showTagDialog = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsRow(tags: List<String>, onRemove: (String) -> Unit, onAdd: () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tags.forEach { tag ->
            InputChip(
                selected = false,
                onClick = { onRemove(tag) },
                label = { Text(tag) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_remove), modifier = Modifier.size(16.dp)) },
            )
        }
        AssistChip(onClick = onAdd, label = { Text(stringResource(R.string.add_tag_chip)) })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentCell(
    attachment: Attachment,
    viewModel: VaultViewModel,
    selected: Boolean,
    selectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(modifier = Modifier.padding(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(BorderStroke(if (selected) 3.dp else 1.dp, borderColor), RoundedCornerShape(10.dp))
                .combinedClickable(onClick = onTap, onLongClick = { if (!selectionMode) menuOpen = true else onLongPress() }),
            contentAlignment = Alignment.Center,
        ) {
            if (attachment.kind == AttachmentKind.OTHER) {
                Icon(iconFor(attachment.kind), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val state by produceState<ThumbState>(initialValue = ThumbState.Loading, key1 = attachment.blobId) {
                    value = runCatching { viewModel.thumbnail(attachment) }.fold(
                        onSuccess = { if (it != null) ThumbState.Ready(it) else ThumbState.None },
                        onFailure = { ThumbState.None },
                    )
                }
                when (val s = state) {
                    is ThumbState.Ready ->
                        Image(bitmap = s.bitmap, contentDescription = attachment.fileName, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    ThumbState.Loading ->
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    ThumbState.None ->
                        Icon(iconFor(attachment.kind), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.open_in_another_app)) }, onClick = { menuOpen = false; onOpen() })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = { menuOpen = false; onShare() })
                DropdownMenuItem(text = { Text(stringResource(R.string.copy_to_clipboard)) }, onClick = { menuOpen = false; onCopy() })
                DropdownMenuItem(text = { Text(stringResource(R.string.save_to_device)) }, onClick = { menuOpen = false; onSave() })
                DropdownMenuItem(text = { Text(stringResource(R.string.select)) }, onClick = { menuOpen = false; onLongPress() })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_remove)) }, onClick = { menuOpen = false; onRemove() })
            }
        }
        Text(
            attachment.fileName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** Display state for an attachment preview tile, so we can show a spinner vs. a fallback icon. */
private sealed interface ThumbState {
    data object Loading : ThumbState
    data class Ready(val bitmap: ImageBitmap) : ThumbState
    data object None : ThumbState
}

private fun iconFor(kind: AttachmentKind): ImageVector = when (kind) {
    AttachmentKind.PDF -> Icons.Filled.PictureAsPdf
    AttachmentKind.IMAGE -> Icons.Filled.Image
    AttachmentKind.OTHER -> Icons.Filled.Description
}

@Composable
private fun FieldsTable(fields: List<DocField>, onCopyValue: (String) -> Unit, onRemove: (String) -> Unit) {
    // The whole table sits in one SelectionContainer: a long-press selects & copies part of any
    // value (or key); a plain tap on a value still copies the whole value. Wrapping the parent
    // (rather than each value) keeps the key / value / delete Row layout intact.
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(stringResource(R.string.details), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 4.dp))
            fields.forEach { field ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(field.key, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.34f))
                    Text(
                        field.value,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(0.66f).clickable { onCopyValue(field.value) }.padding(vertical = 6.dp),
                    )
                    // Exclude the delete button from text selection so it stays tappable.
                    DisableSelection {
                        IconButton(onClick = { onRemove(field.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_remove), modifier = Modifier.size(18.dp))
                        }
                    }
                }
                HorizontalDivider()
            }
            Text(stringResource(R.string.tap_value_copy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

internal val FIELD_KEY_PRESETS = listOf("Number", "Date", "Code")

/**
 * Add-field dialog, shared by the document detail screen, the OCR viewer, and batch mode.
 * [initialKey] pre-selects a preset (or seeds a custom key if it isn't one); [initialValue]
 * pre-fills the value (e.g. an OCR-extracted number).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddFieldDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
    initialKey: String? = null,
    initialValue: String = "",
) {
    val startCustom = initialKey != null && initialKey !in FIELD_KEY_PRESETS
    var expanded by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf(initialKey?.takeIf { it in FIELD_KEY_PRESETS } ?: FIELD_KEY_PRESETS.first()) }
    var custom by remember { mutableStateOf(startCustom) }
    var customKey by remember { mutableStateOf(if (startCustom) initialKey!! else "") }
    var value by remember { mutableStateOf(initialValue) }

    val customLabel = stringResource(R.string.custom_option)
    val options = FIELD_KEY_PRESETS + customLabel
    val effectiveKey = if (custom) customKey.trim() else selectedPreset
    val valid = value.isNotBlank() && effectiveKey.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_field)) },
        text = {
            Column {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = if (custom) customLabel else selectedPreset,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.field_key)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    if (option == customLabel) custom = true else { custom = false; selectedPreset = option }
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                if (custom) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = customKey, onValueChange = { customKey = it }, label = { Text(stringResource(R.string.custom_key)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(stringResource(R.string.field_value)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(effectiveKey, value) }, enabled = valid) { Text(stringResource(R.string.action_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private data class ExportedBlob(val uri: Uri, val mime: String)

private suspend fun exportToCache(context: Context, viewModel: VaultViewModel, attachment: Attachment): ExportedBlob =
    withContext(Dispatchers.IO) {
        val bytes = viewModel.readBlob(attachment.blobId)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, safeFileName(attachment.fileName))
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        ExportedBlob(uri, attachment.mimeType ?: mimeFromName(attachment.fileName) ?: "application/octet-stream")
    }

/** Decrypts an attachment and writes it to a user-chosen location (SAF). Returns success. */
private suspend fun saveAttachmentToUri(context: Context, viewModel: VaultViewModel, attachment: Attachment, dest: Uri): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = viewModel.readBlob(attachment.blobId)
            context.contentResolver.openOutputStream(dest)?.use { it.write(bytes) }
                ?: error("no output stream")
            true
        }.getOrDefault(false)
    }

/** Exports several attachments to cache and returns their FileProvider uris (for multi-share). */
private suspend fun exportMany(context: Context, viewModel: VaultViewModel, attachments: List<Attachment>): List<Uri> {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val out = ArrayList<Uri>()
    attachments.forEachIndexed { i, att ->
        runCatching {
            val bytes = viewModel.readBlob(att.blobId)
            val file = File(dir, "${i}_" + safeFileName(att.fileName))
            file.writeBytes(bytes)
            out.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
        }
    }
    return out
}

private fun copyToClipboard(context: Context, viewModel: VaultViewModel, scope: CoroutineScope, attachment: Attachment) {
    scope.launch {
        val result = runCatching { exportToCache(context, viewModel, attachment) }.getOrNull()
            ?: return@launch toast(context, "Couldn't copy this file")
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, attachment.fileName, result.uri))
        toast(context, "Copied to clipboard")
    }
}

private fun importFrom(context: Context, viewModel: VaultViewModel, documentId: String, scope: CoroutineScope, uris: List<Uri>) {
    uris.forEach { uri ->
        scope.launch {
            val imported = withContext(Dispatchers.IO) { readImported(context, uri) } ?: return@launch
            viewModel.addAttachment(documentId, imported.bytes, imported.kind, imported.fileName, imported.mimeType)
        }
    }
}

private data class ImportedFile(val bytes: ByteArray, val fileName: String, val kind: AttachmentKind, val mimeType: String?)

private fun readImported(context: Context, uri: Uri): ImportedFile? {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri)
    val kind = when {
        mime == "application/pdf" -> AttachmentKind.PDF
        mime?.startsWith("image/") == true -> AttachmentKind.IMAGE
        else -> AttachmentKind.OTHER
    }
    val name = queryDisplayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: defaultName(mime)
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return ImportedFile(bytes, name, kind, mime)
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return cursor.getString(idx)
        }
    }
    return null
}

private fun defaultName(mime: String?): String {
    val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
    return if (ext != null) "file.$ext" else "file"
}

private fun mimeFromName(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return null
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
}

private fun safeFileName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "file" }

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMs))

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
