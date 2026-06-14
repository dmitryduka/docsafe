package app.docsafe.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.docsafe.R
import app.docsafe.vault.breadcrumb
import app.docsafe.vault.childFolders
import app.docsafe.vault.descendantFolderIds
import app.docsafe.vault.model.VaultIndex

/**
 * A folder picker for moving documents/folders. The user navigates the folder tree, can create
 * a new subfolder inline, and confirms "Move here" to drop the items into the browsed folder.
 * Folders being moved (and their descendants) are excluded so you can't move a folder into itself.
 */
@Composable
fun MoveDestinationDialog(
    index: VaultIndex,
    movingFolderIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (destFolderId: String?) -> Unit,
    title: String = stringResource(R.string.move_to_title),
    confirmLabel: String = stringResource(R.string.move_here),
    onCreateFolder: ((name: String, parentId: String?) -> Unit)? = null,
) {
    var browseId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    val excluded = remember(index, movingFolderIds) {
        val set = HashSet<String>()
        movingFolderIds.forEach { set += index.descendantFolderIds(it) }
        set
    }
    val trail = index.breadcrumb(browseId)
    val subfolders = index.childFolders(browseId).filterNot { it.id in excluded }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // Current location breadcrumb (tap a crumb to jump there).
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.my_vault),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (browseId == null) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { browseId = null },
                    )
                    trail.forEach { f ->
                        Text("  ›  ", style = MaterialTheme.typography.labelLarge)
                        Text(
                            f.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (browseId == f.id) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable { browseId = f.id },
                        )
                    }
                }
                Spacer(Modifier.padding(4.dp))
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    if (subfolders.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.no_subfolders),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                    }
                    items(subfolders, key = { it.id }) { folder ->
                        ListItem(
                            leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(folder.name) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            modifier = Modifier.clickable { browseId = folder.id },
                        )
                    }
                }
                if (onCreateFolder != null) {
                    HorizontalDivider()
                    TextButton(onClick = { creating = true }, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                        Spacer(Modifier.padding(4.dp))
                        Text(stringResource(R.string.new_folder_here))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(browseId) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (creating && onCreateFolder != null) {
        NameInputDialog(
            title = stringResource(R.string.new_folder),
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { onCreateFolder(it, browseId); creating = false },
        )
    }
}

