package app.docsafe.ui.vault

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.docsafe.R
import app.docsafe.security.VaultMeta
import app.docsafe.vault.model.VaultIndex
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultsScreen(
    viewModel: VaultViewModel,
    onNavigateUp: () -> Unit,
    onVaultSwitched: () -> Unit,
) {
    val vaults by viewModel.vaultList.collectAsStateWithLifecycle()
    val activeId by viewModel.activeVaultId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showCreate by remember { mutableStateOf(false) }
    var importFile by remember { mutableStateOf<File?>(null) }
    var removeTarget by remember { mutableStateOf<VaultMeta?>(null) }
    var mergeTarget by remember { mutableStateOf<VaultMeta?>(null) }
    var mergeIndex by remember { mutableStateOf<VaultIndex?>(null) }

    val pickVault = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val staged = stageImport(context, uri)
                if (staged != null) importFile = staged else toast(context, context.getString(R.string.err_cant_open))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vaults_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(vaults, key = { it.id }) { vault ->
                    VaultRow(
                        vault = vault,
                        isActive = vault.id == activeId,
                        onSwitch = {
                            viewModel.switchToVault(vault.id)
                            onVaultSwitched()
                        },
                        onMerge = {
                            mergeTarget = vault
                        },
                        onRemove = { removeTarget = vault },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = { pickVault.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.import_new_vault))
                }
                Button(onClick = { showCreate = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.create_new_vault))
                }
            }
        }
    }

    if (showCreate) {
        NameAndPasswordDialog(
            title = stringResource(R.string.create_new_vault),
            confirmLabel = stringResource(R.string.create_vault),
            requireConfirmPassword = true,
            onDismiss = { showCreate = false },
            onConfirm = { name, password ->
                viewModel.createVault(name, password)
                toast(context, context.getString(R.string.vault_created))
                showCreate = false
            },
        )
    }

    importFile?.let { file ->
        NameAndPasswordDialog(
            title = stringResource(R.string.import_new_vault),
            confirmLabel = stringResource(R.string.import_vault),
            requireConfirmPassword = false,
            initialName = file.nameWithoutExtension,
            onDismiss = { file.delete(); importFile = null },
            onConfirm = { name, password ->
                scope.launch {
                    val ok = viewModel.importVault(name, file, password)
                    if (ok) {
                        toast(context, context.getString(R.string.vault_created))
                        file.delete(); importFile = null
                    } else {
                        toast(context, context.getString(R.string.import_failed))
                    }
                }
            },
        )
    }

    removeTarget?.let { vault ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            icon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.remove_vault)) },
            text = { Text(stringResource(R.string.remove_vault_warning, vault.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeVault(vault.id)
                    toast(context, context.getString(R.string.vault_removed))
                    removeTarget = null
                }) {
                    Text(stringResource(R.string.remove_vault_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    // Merge: load the target vault's tree, then pick a destination folder (root or subfolder).
    mergeTarget?.let { vault ->
        LaunchedEffect(vault.id) { mergeIndex = viewModel.vaultIndexOf(vault.id) }
        val idx = mergeIndex
        if (idx != null) {
            MoveDestinationDialog(
                index = idx,
                movingFolderIds = emptySet(),
                title = stringResource(R.string.merge_into_vault),
                confirmLabel = stringResource(R.string.merge_here),
                onDismiss = { mergeTarget = null; mergeIndex = null },
                onConfirm = { destFolderId ->
                    scope.launch {
                        val n = viewModel.mergeActiveInto(vault.id, destFolderId)
                        toast(context, context.getString(R.string.copied_documents, n))
                    }
                    mergeTarget = null; mergeIndex = null
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultRow(
    vault: VaultMeta,
    isActive: Boolean,
    onSwitch: () -> Unit,
    onMerge: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        ListItem(
            colors = if (isActive) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
            leadingContent = { Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(vault.name, fontWeight = FontWeight.Medium) },
            supportingContent = { Text(rowDate(vault.createdAt)) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isActive) {
                        AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(R.string.active_label)) })
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.settings))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (!isActive) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.switch_vault)) },
                                    leadingIcon = { Icon(Icons.Filled.Check, null) },
                                    onClick = { menuOpen = false; onSwitch() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.merge_into_vault)) },
                                    leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                                    onClick = { menuOpen = false; onMerge() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.remove_vault), color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; onRemove() },
                            )
                        }
                    }
                }
            },
            modifier = if (isActive) Modifier else Modifier.clickable(onClick = onSwitch),
        )
    }
}

/** Dialog asking for a vault name + password (with optional confirm field for new vaults). */
@Composable
internal fun NameAndPasswordDialog(
    title: String,
    confirmLabel: String,
    requireConfirmPassword: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, password: CharArray) -> Unit,
    initialName: String = "",
) {
    var name by remember { mutableStateOf(initialName) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val minLen = 8
    val valid = name.isNotBlank() && password.length >= minLen && (!requireConfirmPassword || password == confirm)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.vault_name)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text(stringResource(R.string.master_password)) },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (requireConfirmPassword) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = confirm, onValueChange = { confirm = it }, singleLine = true,
                        label = { Text(stringResource(R.string.confirm_password)) },
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.min_chars, minLen), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), password.toCharArray()) }, enabled = valid) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Lists vaults to pick a copy/merge target. */
@Composable
internal fun VaultChooserDialog(
    vaults: List<VaultMeta>,
    onDismiss: () -> Unit,
    onPick: (VaultMeta) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_target_vault)) },
        text = {
            if (vaults.isEmpty()) {
                Text(stringResource(R.string.create_new_vault))
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(vaults, key = { it.id }) { v ->
                        ListItem(
                            leadingContent = { Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(v.name) },
                            modifier = Modifier.clickable { onPick(v) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun rowDate(epochMs: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMs))

private fun toast(context: android.content.Context, message: String) =
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

/** Copies a picked `.dsvault` content [uri] into a temp file for import. */
private suspend fun stageImport(context: android.content.Context, uri: Uri): File? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val temp = File(context.cacheDir, "import_${System.nanoTime()}.dsvault")
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: return@runCatching null
            temp
        }.getOrNull()
    }
