package app.docsafe.ui.vault

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.docsafe.R
import app.docsafe.ocr.OcrCandidate
import app.docsafe.vault.activeDocument
import app.docsafe.vault.model.Attachment
import app.docsafe.vault.model.AttachmentKind
import app.docsafe.vault.model.Document
import kotlinx.coroutines.launch

/**
 * Batch field extraction: steps through every image-bearing document under a folder, runs OCR
 * on each, and lets the user quickly assign detected numbers/codes to keys without opening each
 * document. Detected values can be edited inline, keys can be custom (and are remembered for the
 * rest of the session), and an added field can be undone by tapping its checkmark.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchExtractScreen(
    folderId: String?,
    viewModel: VaultViewModel,
    onClose: () -> Unit,
) {
    val docs = remember(folderId) { viewModel.batchDocuments(folderId) }
    // Custom keys typed during this session, shared across all documents so they accumulate.
    // Held as an immutable list reassigned on each add, so every dropdown observes the change.
    var customKeys by remember { mutableStateOf(emptyList<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.batch_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        if (docs.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.batch_no_docs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            return@Scaffold
        }

        var pos by rememberSaveable(folderId) { mutableStateOf(0) }
        val clamped = pos.coerceIn(0, docs.size - 1)
        val doc = docs[clamped]

        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                stringResource(R.string.batch_progress, clamped + 1, docs.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // Per-document state (candidates, chosen keys, edits, added markers) resets via key(doc.id).
            key(doc.id) {
                DocumentExtraction(
                    doc = doc,
                    viewModel = viewModel,
                    customKeys = customKeys,
                    onRegisterCustomKey = { k ->
                        if (k !in FIELD_KEY_PRESETS && k !in customKeys) customKeys = customKeys + k
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { pos = clamped - 1 },
                    enabled = clamped > 0,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.batch_previous)) }
                Button(
                    onClick = { if (clamped >= docs.size - 1) onClose() else pos = clamped + 1 },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(if (clamped >= docs.size - 1) R.string.batch_finish else R.string.batch_next))
                }
            }
        }
    }
}

@Composable
private fun DocumentExtraction(
    doc: Document,
    viewModel: VaultViewModel,
    customKeys: List<String>,
    onRegisterCustomKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Re-read the live document so already-saved fields show even after adding.
    val index by viewModel.index.collectAsStateWithLifecycle()
    val live = index.activeDocument(doc.id) ?: doc

    val candidates by produceState<List<OcrCandidate>?>(initialValue = null, key1 = doc.id) {
        value = viewModel.detectFieldsForDocument(doc.id)
    }
    // Per-candidate (keyed by original detected text): chosen key, edited value, created field id.
    val chosenKey = remember(doc.id) { mutableStateMapOf<String, String>() }
    val editedValue = remember(doc.id) { mutableStateMapOf<String, String>() }
    val addedFieldIds = remember(doc.id) { mutableStateMapOf<String, String>() }

    fun pickKey(text: String, key: String) {
        val clean = key.trim()
        if (clean.isEmpty()) return
        chosenKey[text] = clean
        onRegisterCustomKey(clean)
    }

    Column(modifier.fillMaxWidth()) {
        Text(
            live.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        ThumbnailStrip(live.attachments.filter { it.kind == AttachmentKind.IMAGE }, viewModel)

        val list = candidates
        when {
            list == null -> Row(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.batch_scanning), style = MaterialTheme.typography.bodyMedium)
            }

            list.isEmpty() -> Text(
                stringResource(R.string.batch_no_candidates),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )

            else -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
            ) {
                items(list, key = { it.text }) { candidate ->
                    val added = candidate.text in addedFieldIds
                    CandidateCard(
                        value = editedValue[candidate.text] ?: candidate.text,
                        onValueChange = { editedValue[candidate.text] = it },
                        currentKey = chosenKey[candidate.text] ?: candidate.kind.presetKey,
                        customKeys = customKeys,
                        added = added,
                        onPickKey = { pickKey(candidate.text, it) },
                        onCopy = { copyText(context, editedValue[candidate.text] ?: candidate.text) },
                        onAdd = {
                            val key = chosenKey[candidate.text] ?: candidate.kind.presetKey
                            val value = editedValue[candidate.text] ?: candidate.text
                            scope.launch {
                                val id = viewModel.addFieldReturningId(doc.id, key, value)
                                if (id != null) addedFieldIds[candidate.text] = id
                            }
                        },
                        onRemove = {
                            addedFieldIds[candidate.text]?.let { id ->
                                scope.launch {
                                    viewModel.removeFieldAwait(doc.id, id)
                                    addedFieldIds.remove(candidate.text)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThumbnailStrip(images: List<Attachment>, viewModel: VaultViewModel) {
    if (images.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEach { att ->
            // null = still loading, present = ready (these are all images, so failure is rare).
            val thumb by produceState<ImageBitmap?>(initialValue = null, key1 = att.blobId) {
                value = runCatching { viewModel.thumbnail(att) }.getOrNull()
            }
            Box(
                Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = thumb
                if (bmp != null) {
                    Image(bitmap = bmp, contentDescription = att.fileName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CandidateCard(
    value: String,
    onValueChange: (String) -> Unit,
    currentKey: String,
    customKeys: List<String>,
    added: Boolean,
    onPickKey: (String) -> Unit,
    onCopy: () -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = !added,
                singleLine = true,
                label = { Text(stringResource(R.string.field_value)) },
                trailingIcon = {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy_to_clipboard), modifier = Modifier.size(18.dp))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeySelector(
                    currentKey = currentKey,
                    customKeys = customKeys,
                    enabled = !added,
                    onPick = onPickKey,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (added) {
                    // Tapping the checkmark undoes the add (removes the field again).
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.field_added_undo), tint = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    IconButton(onClick = onAdd, enabled = value.isNotBlank()) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add))
                    }
                }
            }
        }
    }
}

@Composable
private fun KeySelector(
    currentKey: String,
    customKeys: List<String>,
    enabled: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var customDialog by remember { mutableStateOf(false) }
    val options = (FIELD_KEY_PRESETS + customKeys).distinct()

    Box(modifier) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true },
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(currentKey, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onPick(option); open = false })
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.custom_option)) },
                onClick = { open = false; customDialog = true },
            )
        }
    }

    if (customDialog) {
        NameInputDialog(
            title = stringResource(R.string.custom_key),
            initial = "",
            onDismiss = { customDialog = false },
            onConfirm = { onPick(it); customDialog = false },
        )
    }
}
