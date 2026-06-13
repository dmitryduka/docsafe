package app.docsafe.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.docsafe.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.docsafe.vault.activeFolder
import app.docsafe.vault.breadcrumb
import app.docsafe.vault.childDocuments
import app.docsafe.vault.childFolders

/**
 * Shown when files have been shared into the app: lets the user navigate to (or create) a
 * folder and pick (or create) a document to attach the shared files to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetScreen() {
    val viewModel: VaultViewModel = hiltViewModel()
    val index by viewModel.index.collectAsStateWithLifecycle()
    val pending by viewModel.pendingShare.collectAsStateWithLifecycle()
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    var fabMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var newDocument by remember { mutableStateOf(false) }

    val folders = index.childFolders(folderId)
    val documents = index.childDocuments(folderId)
    val trail = index.breadcrumb(folderId)
    val title = trail.lastOrNull()?.name ?: stringResource(R.string.add_items, pending.size)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (folderId == null) viewModel.clearPendingShare()
                        else folderId = index.activeFolder(folderId)?.parentId
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.clearPendingShare() }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { fabMenu = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_new))
                }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_folder)) },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                        onClick = { fabMenu = false; newFolder = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_document_add_here)) },
                        leadingIcon = { Icon(Icons.Filled.NoteAdd, null) },
                        onClick = { fabMenu = false; newDocument = true },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                stringResource(R.string.share_choose_destination),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(folders, key = { "f-${it.id}" }) { folder ->
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(folder.name) },
                        modifier = Modifier.clickable { folderId = folder.id },
                    )
                }
                items(documents, key = { "d-${it.id}" }) { doc ->
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(doc.name) },
                        supportingContent = { Text(stringResource(R.string.tap_to_add_here)) },
                        modifier = Modifier.clickable { viewModel.attachPendingShareTo(doc.id) },
                    )
                }
            }
        }
    }

    if (newFolder) {
        NameInputDialog(
            title = stringResource(R.string.new_folder),
            initial = "",
            onDismiss = { newFolder = false },
            onConfirm = { viewModel.createFolder(it, folderId); newFolder = false },
        )
    }
    if (newDocument) {
        NameInputDialog(
            title = stringResource(R.string.new_document),
            initial = "",
            onDismiss = { newDocument = false },
            onConfirm = { viewModel.createDocumentAndAttachShare(it, folderId); newDocument = false },
        )
    }
}
