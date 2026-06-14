package app.docsafe.ui.vault

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Output
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.docsafe.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Context
import android.content.Intent
import app.docsafe.vault.breadcrumb
import app.docsafe.ui.formatDate
import app.docsafe.ui.rememberStepUp
import app.docsafe.vault.childDocuments
import app.docsafe.vault.childFolders
import app.docsafe.vault.model.Document
import app.docsafe.vault.model.Folder
import app.docsafe.vault.model.VaultIndex
import app.docsafe.vault.hasStarred
import app.docsafe.vault.searchDocuments
import app.docsafe.vault.searchFolders
import app.docsafe.vault.starredDocuments
import app.docsafe.vault.starredFolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowserScreen(
    folderId: String?,
    viewModel: VaultViewModel,
    onOpenFolder: (String) -> Unit,
    onOpenDocument: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBatchExtract: () -> Unit,
    onOpenVaults: () -> Unit,
    onNavigateUp: () -> Unit,
) {
    val index by viewModel.index.collectAsStateWithLifecycle()
    val vaults by viewModel.vaultList.collectAsStateWithLifecycle()
    val activeVaultId by viewModel.activeVaultId.collectAsStateWithLifecycle()
    val folders = index.childFolders(folderId)
    val documents = index.childDocuments(folderId)
    val trail = index.breadcrumb(folderId)
    // At the root the title/breadcrumb is the active vault's name (not a fixed "My Vault").
    val vaultName = vaults.firstOrNull { it.id == activeVaultId }?.name ?: stringResource(R.string.my_vault)
    val title = trail.lastOrNull()?.name ?: vaultName

    var fabMenuOpen by remember { mutableStateOf(false) }
    var topMenuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<NameDialogRequest?>(null) }
    var query by remember { mutableStateOf("") }
    var selectedDocs by remember { mutableStateOf(emptySet<String>()) }
    var selectedFolders by remember { mutableStateOf(emptySet<String>()) }
    var pendingMove by remember { mutableStateOf<MoveTargets?>(null) }
    var chooseCopyVault by remember { mutableStateOf(false) }
    var copyTarget by remember { mutableStateOf<app.docsafe.security.VaultMeta?>(null) }
    var copyTargetIndex by remember { mutableStateOf<app.docsafe.vault.model.VaultIndex?>(null) }
    var exportPasswordFor by remember { mutableStateOf<MoveTargets?>(null) }
    var pendingExport by remember { mutableStateOf<Pair<MoveTargets, CharArray>?>(null) }
    val selectionMode = selectedDocs.isNotEmpty() || selectedFolders.isNotEmpty()
    fun clearSelection() { selectedDocs = emptySet(); selectedFolders = emptySet() }
    fun toggleFolder(id: String) { selectedFolders = if (id in selectedFolders) selectedFolders - id else selectedFolders + id }
    fun toggleDoc(id: String) { selectedDocs = if (id in selectedDocs) selectedDocs - id else selectedDocs + id }
    val snackbarHostState = remember { SnackbarHostState() }
    val compacting by viewModel.compacting.collectAsStateWithLifecycle()
    val compactionMessage by viewModel.compactionMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Fresh confirmation gate before exporting/sharing the whole vault.
    val stepUp = rememberStepUp(
        biometricEnabled = viewModel.biometricEnabled,
        title = stringResource(R.string.confirm_identity),
        subtitle = stringResource(R.string.unlock_subtitle),
        verifyPin = viewModel::verifyPin,
    )

    // Save the encrypted vault to a user-chosen location on the device (Files/Downloads/etc.).
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) scope.launch { saveVaultToUri(context, viewModel, uri) }
    }

    // Export selected folders/docs into a brand-new vault file at a user-chosen location.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val req = pendingExport
        pendingExport = null
        if (req == null) return@rememberLauncherForActivityResult
        val (targets, password) = req
        if (uri == null) { password.fill(' '); return@rememberLauncherForActivityResult }
        scope.launch {
            val n = exportToNewVaultFile(context, viewModel, targets.folderIds, targets.docIds, password, uri)
            password.fill(' ')
            Toast.makeText(
                context,
                if (n >= 0) context.getString(R.string.exported_documents, n) else context.getString(R.string.err_cant_save_vault),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    LaunchedEffect(compactionMessage) {
        compactionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearCompactionMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selectedDocs.size + selectedFolders.size)) },
                    navigationIcon = {
                        IconButton(onClick = { clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    },
                    actions = {
                        IconButton(onClick = { pendingMove = MoveTargets(selectedFolders, selectedDocs) }) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.action_move))
                        }
                        IconButton(onClick = { chooseCopyVault = true }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy_to_vault))
                        }
                        IconButton(onClick = {
                            val targets = MoveTargets(selectedFolders, selectedDocs)
                            // Exporting copies data out — gate behind a fresh confirmation.
                            stepUp { exportPasswordFor = targets }
                        }) {
                            Icon(Icons.Filled.Output, contentDescription = stringResource(R.string.export_to_new_vault))
                        }
                        IconButton(onClick = {
                            selectedFolders.forEach { viewModel.deleteFolder(it) }
                            selectedDocs.forEach { viewModel.deleteDocument(it) }
                            clearSelection()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    },
                )
            } else {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (folderId != null) {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { topMenuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.save_vault_to_device)) },
                                leadingIcon = { Icon(Icons.Filled.Download, null) },
                                onClick = {
                                    topMenuOpen = false
                                    // Exporting the whole vault — require a fresh confirmation first.
                                    stepUp {
                                        viewModel.notifyExternalActivityStarting()
                                        saveLauncher.launch("DocSafe-vault.dsvault")
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share_vault)) },
                                leadingIcon = { Icon(Icons.Filled.IosShare, null) },
                                onClick = {
                                    topMenuOpen = false
                                    stepUp { scope.launch { exportAndShareVault(context, viewModel) } }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.batch_extract)) },
                                leadingIcon = { Icon(Icons.Filled.TextFields, null) },
                                onClick = {
                                    topMenuOpen = false
                                    onOpenBatchExtract()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.vaults_title)) },
                                leadingIcon = { Icon(Icons.Filled.Lock, null) },
                                onClick = {
                                    topMenuOpen = false
                                    onOpenVaults()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (compacting) stringResource(R.string.reclaiming) else stringResource(R.string.reclaim_storage)) },
                                leadingIcon = { Icon(Icons.Filled.Storage, null) },
                                enabled = !compacting,
                                onClick = {
                                    topMenuOpen = false
                                    viewModel.compactVault()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                onClick = {
                                    topMenuOpen = false
                                    onOpenSettings()
                                },
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
                FloatingActionButton(onClick = { fabMenuOpen = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add")
                }
                DropdownMenu(expanded = fabMenuOpen, onDismissRequest = { fabMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_folder)) },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                        onClick = {
                            fabMenuOpen = false
                            dialog = NameDialogRequest.NewFolder
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_document)) },
                        leadingIcon = { Icon(Icons.Filled.NoteAdd, null) },
                        onClick = {
                            fabMenuOpen = false
                            dialog = NameDialogRequest.NewDocument
                        },
                    )
                }
            }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )

            val searching = query.isNotBlank()
            val showStarred = !searching && folderId == null && index.hasStarred()

            if (trail.isNotEmpty() && !searching) {
                Breadcrumb(vaultName, trail.map { it.id to it.name }, onRoot = { /* up nav */ }, onCrumb = onOpenFolder)
            }

            val shownFolders = if (searching) index.searchFolders(query) else folders
            val shownDocuments = if (searching) index.searchDocuments(query) else documents

            if (!searching && !showStarred && shownFolders.isEmpty() && shownDocuments.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (showStarred) {
                        item { SectionHeader(stringResource(R.string.starred)) }
                        items(index.starredFolders(), key = { "sf-${it.id}" }) { folder ->
                            EntryRow(
                                icon = Icons.Filled.Folder,
                                title = folder.name,
                                subtitle = folderSubtitle(index, folder),
                                starred = true,
                                selectionMode = selectionMode,
                                selected = folder.id in selectedFolders,
                                onClick = { onOpenFolder(folder.id) },
                                onToggleSelect = { toggleFolder(folder.id) },
                                onLongPress = { toggleFolder(folder.id) },
                                onToggleStar = { viewModel.setFolderStarred(folder.id, false) },
                                onRename = { dialog = NameDialogRequest.RenameFolder(folder.id, folder.name) },
                                onMove = { pendingMove = MoveTargets(setOf(folder.id), emptySet()) },
                                onDelete = { viewModel.deleteFolder(folder.id) },
                            )
                        }
                        items(index.starredDocuments(), key = { "sd-${it.id}" }) { doc ->
                            EntryRow(
                                icon = Icons.Filled.Description,
                                title = doc.name,
                                subtitle = docSubtitle(doc),
                                starred = true,
                                selectionMode = selectionMode,
                                selected = doc.id in selectedDocs,
                                onClick = { onOpenDocument(doc.id) },
                                onToggleSelect = { toggleDoc(doc.id) },
                                onLongPress = { toggleDoc(doc.id) },
                                onToggleStar = { viewModel.setDocumentStarred(doc.id, false) },
                                onRename = { dialog = NameDialogRequest.RenameDocument(doc.id, doc.name) },
                                onMove = { pendingMove = MoveTargets(emptySet(), setOf(doc.id)) },
                                onDelete = { viewModel.deleteDocument(doc.id) },
                            )
                        }
                        item { SectionHeader(stringResource(R.string.section_all)) }
                    }
                    if (searching && shownFolders.isEmpty() && shownDocuments.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.no_matches),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                    items(shownFolders, key = { "f-${it.id}" }) { folder ->
                        EntryRow(
                            icon = Icons.Filled.Folder,
                            title = folder.name,
                            subtitle = if (searching) pathLabel(index, folder.parentId) else folderSubtitle(index, folder),
                            starred = folder.starred,
                            selectionMode = selectionMode,
                            selected = folder.id in selectedFolders,
                            onClick = { onOpenFolder(folder.id) },
                            onToggleSelect = { toggleFolder(folder.id) },
                            onLongPress = { toggleFolder(folder.id) },
                            onToggleStar = { viewModel.setFolderStarred(folder.id, !folder.starred) },
                            onRename = { dialog = NameDialogRequest.RenameFolder(folder.id, folder.name) },
                            onMove = { pendingMove = MoveTargets(setOf(folder.id), emptySet()) },
                            onDelete = { viewModel.deleteFolder(folder.id) },
                        )
                    }
                    items(shownDocuments, key = { "d-${it.id}" }) { doc ->
                        EntryRow(
                            icon = Icons.Filled.Description,
                            title = doc.name,
                            subtitle = if (searching) pathLabel(index, doc.folderId) else docSubtitle(doc),
                            starred = doc.starred,
                            selectionMode = selectionMode,
                            selected = doc.id in selectedDocs,
                            onClick = { onOpenDocument(doc.id) },
                            onToggleSelect = { toggleDoc(doc.id) },
                            onLongPress = { toggleDoc(doc.id) },
                            onToggleStar = { viewModel.setDocumentStarred(doc.id, !doc.starred) },
                            onRename = { dialog = NameDialogRequest.RenameDocument(doc.id, doc.name) },
                            onMove = { pendingMove = MoveTargets(emptySet(), setOf(doc.id)) },
                            onDelete = { viewModel.deleteDocument(doc.id) },
                        )
                    }
                }
            }
        }
    }

    dialog?.let { req ->
        NameInputDialog(
            title = stringResource(req.titleRes),
            initial = req.initial,
            onDismiss = { dialog = null },
            onConfirm = { name ->
                when (req) {
                    NameDialogRequest.NewFolder -> viewModel.createFolder(name, folderId)
                    NameDialogRequest.NewDocument -> viewModel.createDocument(name, folderId)
                    is NameDialogRequest.RenameFolder -> viewModel.renameFolder(req.id, name)
                    is NameDialogRequest.RenameDocument -> viewModel.renameDocument(req.id, name)
                }
                dialog = null
            },
        )
    }

    pendingMove?.let { targets ->
        MoveDestinationDialog(
            index = index,
            movingFolderIds = targets.folderIds,
            onDismiss = { pendingMove = null },
            onCreateFolder = { name, parent -> viewModel.createFolder(name, parent) },
            onConfirm = { dest ->
                targets.docIds.forEach { viewModel.moveDocument(it, dest) }
                targets.folderIds.forEach { viewModel.moveFolder(it, dest) }
                pendingMove = null
                selectedDocs = emptySet()
                selectedFolders = emptySet()
            },
        )
    }

    // Export selected items to a brand-new vault file: pick a password, then a save location.
    exportPasswordFor?.let { targets ->
        NewPasswordDialog(
            title = stringResource(R.string.export_to_new_vault),
            message = stringResource(R.string.export_new_vault_message),
            onDismiss = { exportPasswordFor = null },
            onConfirm = { password ->
                exportPasswordFor = null
                pendingExport = targets to password
                clearSelection()
                viewModel.notifyExternalActivityStarting()
                exportLauncher.launch("DocSafe-export.dsvault")
            },
        )
    }

    // Copy selected items into another vault: choose the target vault, then a destination folder.
    if (chooseCopyVault) {
        val allVaults by viewModel.vaultList.collectAsStateWithLifecycle()
        val active by viewModel.activeVaultId.collectAsStateWithLifecycle()
        VaultChooserDialog(
            vaults = allVaults.filter { it.id != active },
            onDismiss = { chooseCopyVault = false },
            onPick = { chooseCopyVault = false; copyTarget = it },
        )
    }
    copyTarget?.let { target ->
        LaunchedEffect(target.id) { copyTargetIndex = viewModel.vaultIndexOf(target.id) }
        copyTargetIndex?.let { targetIndex ->
            val docs = selectedDocs
            val folders = selectedFolders
            MoveDestinationDialog(
                index = targetIndex,
                movingFolderIds = emptySet(),
                title = stringResource(R.string.copy_to_title),
                confirmLabel = stringResource(R.string.copy_here),
                onDismiss = { copyTarget = null; copyTargetIndex = null },
                onConfirm = { dest ->
                    scope.launch {
                        val n = viewModel.copyToVault(target.id, docs, folders, dest)
                        Toast.makeText(context, context.getString(R.string.copied_documents, n), Toast.LENGTH_SHORT).show()
                    }
                    copyTarget = null; copyTargetIndex = null
                    selectedDocs = emptySet(); selectedFolders = emptySet()
                },
            )
        }
    }
}

/** Documents and/or folders queued to be moved to a destination chosen in [MoveDestinationDialog]. */
private data class MoveTargets(val folderIds: Set<String>, val docIds: Set<String>)

@Composable
private fun docSubtitle(doc: Document): String =
    stringResource(R.string.files_count, doc.attachments.size) + " · " + formatDate(doc.createdAt)

/** Human-readable location path ("My Vault › Folder › Subfolder") for a search result. */
@Composable
private fun pathLabel(index: VaultIndex, folderId: String?): String {
    val root = stringResource(R.string.my_vault)
    val trail = index.breadcrumb(folderId)
    return if (trail.isEmpty()) root else root + "  ›  " + trail.joinToString("  ›  ") { it.name }
}

@Composable
private fun folderSubtitle(index: VaultIndex, folder: Folder): String {
    val folders = index.childFolders(folder.id).size
    val docs = index.childDocuments(folder.id).size
    val count = when {
        folders > 0 && docs > 0 -> stringResource(R.string.count_folders, folders) + " · " + stringResource(R.string.count_documents, docs)
        folders > 0 -> stringResource(R.string.count_folders, folders)
        docs > 0 -> stringResource(R.string.count_documents, docs)
        else -> stringResource(R.string.empty)
    }
    return count + " · " + formatDate(folder.createdAt)
}

@Composable
private fun Breadcrumb(rootName: String, crumbs: List<Pair<String, String>>, onRoot: () -> Unit, onCrumb: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(rootName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { onRoot() })
        crumbs.forEach { (id, name) ->
            Text("  ›  ", style = MaterialTheme.typography.labelLarge)
            Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { onCrumb(id) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    starred: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onToggleStar: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            } else {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = if (selectionMode) null else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleStar) {
                        Icon(
                            if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Star",
                            tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menuOpen = false; onRename() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_move)) }, onClick = { menuOpen = false; onMove() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menuOpen = false; onDelete() })
                        }
                    }
                }
            }
        },
        modifier = Modifier.combinedClickable(
            onClick = { if (selectionMode) onToggleSelect() else onClick() },
            onLongClick = onLongPress,
        ),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        Text(stringResource(R.string.nothing_here), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.nothing_here_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Writes the encrypted vault to a user-chosen file location on the device. */
private suspend fun saveVaultToUri(context: Context, viewModel: VaultViewModel, uri: Uri) {
    val ok = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(context.cacheDir, "shared").apply { mkdirs() }.let { File(it, "DocSafe-vault.dsvault") }
            viewModel.exportVaultCopy(tmp)
            context.contentResolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            tmp.delete()
            true
        }.getOrDefault(false)
    }
    Toast.makeText(context, context.getString(if (ok) R.string.vault_saved else R.string.err_cant_save_vault), Toast.LENGTH_SHORT).show()
}

/** Asks for a new password (with confirmation) for the exported vault; returns it as a CharArray. */
@Composable
private fun NewPasswordDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    val minLen = 8
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = password.length >= minLen && password == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text(stringResource(R.string.master_password)) },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it }, singleLine = true,
                    label = { Text(stringResource(R.string.confirm_password)) },
                    isError = confirm.isNotEmpty() && confirm != password,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.min_chars, minLen),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password.toCharArray()) }, enabled = valid) { Text(stringResource(R.string.export_to_new_vault)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Builds a new vault from the selected items into a temp file, copies it to [dest], wipes the temp.
 * Returns the number of documents exported, or -1 on failure.
 */
private suspend fun exportToNewVaultFile(
    context: Context,
    viewModel: VaultViewModel,
    folderIds: Set<String>,
    docIds: Set<String>,
    password: CharArray,
    dest: Uri,
): Int = withContext(Dispatchers.IO) {
    val tmp = File(File(context.cacheDir, "shared").apply { mkdirs() }, "export_${System.nanoTime()}.dsvault")
    try {
        val count = viewModel.exportToNewVault(folderIds, docIds, password, tmp)
        context.contentResolver.openOutputStream(dest)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            ?: error("no output stream")
        count
    } catch (e: Exception) {
        -1
    } finally {
        tmp.delete()
    }
}

/** Exports the encrypted vault to cache and opens the system share sheet to send it anywhere. */
private suspend fun exportAndShareVault(context: Context, viewModel: VaultViewModel) {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val dest = File(dir, "DocSafe-vault.dsvault")
    withContext(Dispatchers.IO) { viewModel.exportVaultCopy(dest) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
    viewModel.notifyExternalActivityStarting()
    val intent = Intent(Intent.ACTION_SEND)
        .setType("application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_vault)))
}

private sealed interface NameDialogRequest {
    val titleRes: Int
    val initial: String

    data object NewFolder : NameDialogRequest {
        override val titleRes = R.string.new_folder
        override val initial = ""
    }

    data object NewDocument : NameDialogRequest {
        override val titleRes = R.string.new_document
        override val initial = ""
    }

    data class RenameFolder(val id: String, override val initial: String) : NameDialogRequest {
        override val titleRes = R.string.rename_folder
    }

    data class RenameDocument(val id: String, override val initial: String) : NameDialogRequest {
        override val titleRes = R.string.rename_document
    }
}
