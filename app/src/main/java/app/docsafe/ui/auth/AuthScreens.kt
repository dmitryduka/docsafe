package app.docsafe.ui.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.docsafe.R
import app.docsafe.security.BiometricAuthenticator
import app.docsafe.security.BiometricResult
import kotlinx.coroutines.launch

private const val MIN_PASSWORD_LEN = 8
private const val MIN_PIN_LEN = 4

/** Step 1 of onboarding: create a new vault protected by the shared master password. */
@Composable
fun CreateVaultScreen(viewModel: AuthViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val valid = password.length >= MIN_PASSWORD_LEN && password == confirm

    // Import an existing shared vault file (e.g. received via Telegram/email) instead.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.beginImportFromUri(uri)
    }

    AuthScaffold(
        icon = Icons.Filled.Lock,
        title = stringResource(R.string.create_vault_title),
        subtitle = stringResource(R.string.create_vault_subtitle),
    ) {
        PasswordWarningCard()
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.master_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text(stringResource(R.string.confirm_password)) },
            singleLine = true,
            isError = confirm.isNotEmpty() && confirm != password,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (password.isNotEmpty() && password.length < MIN_PASSWORD_LEN) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.min_chars, MIN_PASSWORD_LEN), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { viewModel.createVault(password.toCharArray()) },
            enabled = valid && !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.create_vault))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                viewModel.notifyExternalActivityStarting()
                importLauncher.launch(arrayOf("*/*"))
            },
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.import_shared_vault))
        }
        BusyAndError(ui)
    }
}

/** Shown when a `.dsvault` file has been opened/shared into the app: prompt for its password. */
@Composable
fun ImportVaultScreen(viewModel: AuthViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    val name = viewModel.pendingImportName ?: stringResource(R.string.import_vault_title)

    AuthScaffold(
        icon = Icons.Filled.Lock,
        title = stringResource(R.string.import_vault_title),
        subtitle = stringResource(R.string.import_vault_subtitle, name),
    ) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.master_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { viewModel.confirmImport(password.toCharArray()) },
            enabled = password.isNotEmpty() && !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.import_vault))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { viewModel.cancelImport() },
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_cancel))
        }
        BusyAndError(ui)
    }
}

/** Step 2 of onboarding: require at least one unlock method (biometric and/or PIN). */
@Composable
fun ChooseMethodsScreen(viewModel: AuthViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val scope = rememberCoroutineScope()
    val authenticator = remember { BiometricAuthenticator(activity) }

    var showPinDialog by remember { mutableStateOf(false) }

    // Observable so the screen reliably updates once a method is enabled.
    val biometricSet = ui.biometricEnabled
    val pinSet = ui.pinEnabled
    val biometricAvailable = authenticator.isAvailable()

    AuthScaffold(
        icon = Icons.Filled.Lock,
        title = stringResource(R.string.secure_vault_title),
        subtitle = stringResource(R.string.secure_vault_subtitle),
    ) {
        MethodCard(
            icon = Icons.Filled.Fingerprint,
            title = stringResource(R.string.biometrics_title),
            description = if (biometricAvailable) {
                stringResource(R.string.biometrics_desc)
            } else {
                stringResource(R.string.biometrics_unavailable)
            },
            enabled = biometricSet,
            actionEnabled = biometricAvailable && !biometricSet && !ui.busy,
            actionLabel = stringResource(R.string.action_enable),
            onAction = {
                scope.launch {
                    try {
                        val cipher = viewModel.biometricEncryptCipher()
                        when (val r = authenticator.authenticate("Enable biometric unlock", "Confirm it's you", cipher)) {
                            is BiometricResult.Success -> r.cipher?.let { viewModel.completeBiometricSetup(it) }
                            is BiometricResult.Error -> viewModel.showError(r.message)
                            BiometricResult.Failed -> viewModel.showError("Authentication failed.")
                        }
                    } catch (e: Exception) {
                        viewModel.showError(e.message ?: "Could not enable biometrics.")
                    }
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        MethodCard(
            icon = Icons.Filled.Lock,
            title = stringResource(R.string.pin_title),
            description = stringResource(R.string.pin_desc),
            enabled = pinSet,
            actionEnabled = !pinSet && !ui.busy,
            actionLabel = stringResource(R.string.set_pin),
            onAction = { showPinDialog = true },
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { viewModel.completeOnboarding() },
            enabled = (biometricSet || pinSet) && !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_continue))
        }
        BusyAndError(ui)
    }

    if (showPinDialog) {
        PinInputDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { entered ->
                viewModel.enablePin(entered.toCharArray())
                showPinDialog = false
            },
        )
    }
}

/** A dialog (floats above the keyboard) for entering and confirming a numeric PIN. */
@Composable
private fun PinInputDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length >= MIN_PIN_LEN && pin == confirm

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_a_pin)) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.pin_hint, MIN_PIN_LEN)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.confirm_pin)) },
                    singleLine = true,
                    isError = confirm.isNotEmpty() && confirm != pin,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(pin) }, enabled = valid) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Shown on every cold start / return from background while a vault exists. */
@Composable
fun UnlockScreen(viewModel: AuthViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val scope = rememberCoroutineScope()
    val authenticator = remember { BiometricAuthenticator(activity) }
    var pin by remember { mutableStateOf("") }

    fun runBiometricUnlock() {
        scope.launch {
            try {
                val cipher = viewModel.biometricDecryptCipher()
                when (val r = authenticator.authenticate("Unlock DocSafe", "Confirm it's you", cipher)) {
                    is BiometricResult.Success -> r.cipher?.let { viewModel.completeBiometricUnlock(it) }
                    is BiometricResult.Error -> viewModel.showError(r.message)
                    BiometricResult.Failed -> viewModel.showError("Authentication failed.")
                }
            } catch (e: Exception) {
                viewModel.showError(e.message ?: "Could not unlock.")
            }
        }
    }

    // Present the fingerprint/device prompt immediately when the unlock screen appears,
    // so the user doesn't have to tap a button first. Runs once per appearance.
    if (viewModel.biometricEnabled) {
        LaunchedEffect(Unit) { runBiometricUnlock() }
    }

    AuthScaffold(
        icon = Icons.Filled.Lock,
        title = stringResource(R.string.unlock_title),
        subtitle = stringResource(R.string.unlock_subtitle),
    ) {
        if (viewModel.biometricEnabled) {
            Button(
                onClick = { runBiometricUnlock() },
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.use_biometrics))
            }
            Spacer(Modifier.height(16.dp))
        }
        if (viewModel.pinEnabled) {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.pin)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    viewModel.unlockWithPin(pin.toCharArray())
                    pin = ""
                },
                enabled = pin.length >= MIN_PIN_LEN && !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.unlock_with_pin))
            }
        }
        BusyAndError(ui)
    }
}

/** Cautions the user that the master password is unrecoverable, and names the cipher. */
@Composable
private fun PasswordWarningCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.password_warning), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// --- shared pieces ----------------------------------------------------------------------

@Composable
private fun AuthScaffold(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // imePadding() must come BEFORE verticalScroll so it shrinks the scroll viewport
            // (lifting content above the keyboard); applied after, it only pads the content.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Top-aligned (not centered) so form fields sit high and stay reachable above the
        // keyboard even on devices/emulators that under-report the IME inset.
        verticalArrangement = Arrangement.Top,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.height(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        content()
        // Extra scroll room so the bottom button can be scrolled clear of the keyboard even
        // when the IME inset is unavailable.
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun MethodCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    actionEnabled: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            if (enabled) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Enabled", tint = MaterialTheme.colorScheme.primary)
            } else {
                OutlinedButton(onClick = onAction, enabled = actionEnabled) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun BusyAndError(ui: AuthUiState) {
    if (ui.busy) {
        Spacer(Modifier.height(16.dp))
        CircularProgressIndicator()
    }
    ui.error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
}
