package app.docsafe.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.fragment.app.FragmentActivity
import app.docsafe.R
import app.docsafe.security.BiometricAuthenticator
import app.docsafe.security.BiometricResult
import kotlinx.coroutines.launch

/** Minimum app PIN length (shared across set/verify dialogs). */
const val MIN_PIN_LEN = 4

/**
 * Returns a trigger that requires a **fresh** biometric/PIN confirmation before running a sensitive
 * action (e.g. exporting the whole vault, removing a vault, disabling an unlock method). This is the
 * step-up control: being already unlocked is not enough — the user must re-prove identity right now.
 *
 * If biometrics are enabled it shows the system prompt; otherwise it asks for the app PIN and
 * verifies it (Argon2 off the main thread via [verifyPin]). [onConfirmed] runs only on success.
 */
@Composable
fun rememberStepUp(
    biometricEnabled: Boolean,
    title: String,
    subtitle: String,
    verifyPin: suspend (CharArray) -> Boolean,
): (onConfirmed: () -> Unit) -> Unit {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val authenticator = remember { BiometricAuthenticator(activity) }
    val scope = rememberCoroutineScope()
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    if (pendingAction != null && !biometricEnabled) {
        StepUpPinDialog(
            title = title,
            onDismiss = { pendingAction = null },
            onConfirm = { pin ->
                scope.launch {
                    if (verifyPin(pin.toCharArray())) {
                        val action = pendingAction
                        pendingAction = null
                        action?.invoke()
                    } else {
                        toast(context, context.getString(R.string.incorrect_pin))
                    }
                }
            },
        )
    }

    return { onConfirmed ->
        if (biometricEnabled) {
            scope.launch {
                when (val r = authenticator.authenticate(title, subtitle)) {
                    is BiometricResult.Success -> onConfirmed()
                    is BiometricResult.Error -> toast(context, r.message)
                    BiometricResult.Failed -> Unit
                }
            }
        } else {
            pendingAction = onConfirmed
        }
    }
}

@Composable
private fun StepUpPinDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.pin)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }, enabled = pin.length >= MIN_PIN_LEN) {
                Text(stringResource(R.string.action_continue))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
