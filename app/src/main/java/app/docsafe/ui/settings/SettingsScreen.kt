package app.docsafe.ui.settings

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.docsafe.R
import app.docsafe.i18n.AppLocales
import app.docsafe.security.BiometricAuthenticator
import app.docsafe.security.BiometricResult
import app.docsafe.ui.MIN_PIN_LEN
import app.docsafe.ui.rememberStepUp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateUp: () -> Unit) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val flags by viewModel.flags.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val scope = rememberCoroutineScope()
    val authenticator = remember { BiometricAuthenticator(activity) }

    var showPinDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }

    // Fresh biometric/PIN confirmation gate for sensitive actions (change password, disable an
    // unlock method, change PIN). Being already unlocked is not enough.
    val stepUp = rememberStepUp(
        biometricEnabled = flags.biometric,
        title = stringResource(R.string.confirm_identity),
        subtitle = stringResource(R.string.unlock_subtitle),
        verifyPin = viewModel::verifyPin,
    )

    fun enableBiometric() {
        scope.launch {
            try {
                val cipher = viewModel.biometricEncryptCipher()
                when (val r = authenticator.authenticate("DocSafe", "Confirm it's you", cipher)) {
                    is BiometricResult.Success -> r.cipher?.let { viewModel.completeBiometricSetup(it) }
                    is BiometricResult.Error -> Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
                    BiometricResult.Failed -> Unit
                }
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_close))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SectionTitle(stringResource(R.string.settings_security))

            val biometricAvailable = authenticator.isAvailable()
            ListItem(
                headlineContent = { Text(stringResource(R.string.biometric_unlock)) },
                supportingContent = {
                    Text(
                        when {
                            flags.biometric -> stringResource(R.string.enabled)
                            !biometricAvailable -> stringResource(R.string.biometrics_unavailable)
                            else -> stringResource(R.string.disabled)
                        },
                    )
                },
                trailingContent = {
                    Switch(
                        checked = flags.biometric,
                        enabled = !busy && (flags.biometric || biometricAvailable),
                        onCheckedChange = { on ->
                            if (on) enableBiometric()
                            else stepUp {
                                if (!viewModel.disableBiometric()) {
                                    Toast.makeText(context, context.getString(R.string.keep_one_method), Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.pin_title)) },
                supportingContent = { Text(if (flags.pin) stringResource(R.string.enabled) else stringResource(R.string.disabled)) },
                trailingContent = {
                    Switch(
                        checked = flags.pin,
                        enabled = !busy,
                        onCheckedChange = { on ->
                            if (on) showPinDialog = true
                            else stepUp {
                                if (!viewModel.disablePin()) {
                                    Toast.makeText(context, context.getString(R.string.keep_one_method), Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    )
                },
                modifier = Modifier.clickable(enabled = !busy && !flags.pin) { showPinDialog = true },
            )
            if (flags.pin) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.change_pin)) },
                    modifier = Modifier.clickable(enabled = !busy) { stepUp { showPinDialog = true } },
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.change_master_password)) },
                modifier = Modifier.clickable(enabled = !busy) { stepUp { showChangePassword = true } },
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_language))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_language)) },
                supportingContent = {
                    Text(AppLocales.displayName(viewModel.languageTag) ?: stringResource(R.string.language_system_default))
                },
                modifier = Modifier.clickable { showLanguageDialog = true },
            )
        }
    }

    if (showPinDialog) {
        PinDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { pin -> viewModel.setPin(pin.toCharArray()); showPinDialog = false },
        )
    }
    if (showChangePassword) {
        ChangePasswordDialog(
            onDismiss = { showChangePassword = false },
            onConfirm = { pw ->
                showChangePassword = false
                viewModel.changeMasterPassword(pw.toCharArray()) {
                    Toast.makeText(context, context.getString(R.string.password_changed), Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
    if (showLanguageDialog) {
        LanguageDialog(
            current = viewModel.languageTag,
            onDismiss = { showLanguageDialog = false },
            onSelect = { tag ->
                showLanguageDialog = false
                if (tag != viewModel.languageTag) {
                    viewModel.setLanguage(tag)
                    (context as? Activity)?.recreate()
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length >= MIN_PIN_LEN && pin == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_a_pin)) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.pin_hint, MIN_PIN_LEN)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.confirm_pin)) },
                    singleLine = true,
                    isError = confirm.isNotEmpty() && confirm != pin,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pin) }, enabled = valid) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val minLen = 8
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = password.length >= minLen && password == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_master_password)) },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.new_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(stringResource(R.string.confirm_new_password)) },
                    singleLine = true,
                    isError = confirm.isNotEmpty() && confirm != password,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.min_chars, minLen),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = valid) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun LanguageDialog(current: String?, onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LanguageRow(stringResource(R.string.language_system_default), current == null) { onSelect(null) }
                AppLocales.supported.forEach { (tag, name) ->
                    LanguageRow(name, current == tag) { onSelect(tag) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun LanguageRow(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}
