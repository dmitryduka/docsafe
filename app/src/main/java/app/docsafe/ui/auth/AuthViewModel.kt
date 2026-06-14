package app.docsafe.ui.auth

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.docsafe.R
import app.docsafe.security.SecurityRepository
import app.docsafe.security.SecurityState
import app.docsafe.share.PendingShareStore
import app.docsafe.share.SharedFileRef
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.crypto.Cipher
import javax.inject.Inject

/** Transient UI state layered on top of the durable [SecurityState]. */
data class AuthUiState(
    val busy: Boolean = false,
    val error: String? = null,
    val pinEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val securityRepository: SecurityRepository,
    pendingShareStore: PendingShareStore,
) : ViewModel() {

    val securityState: StateFlow<SecurityState> = securityRepository.state

    /** Non-empty when files were shared into the app and need a destination. */
    val pendingShare: StateFlow<List<SharedFileRef>> = pendingShareStore.files

    val pendingImportName: String? get() = securityRepository.pendingImportName

    fun notifyExternalActivityStarting() = securityRepository.notifyExternalActivityStarting()

    private val _ui = MutableStateFlow(
        AuthUiState(
            pinEnabled = securityRepository.pinEnabled,
            biometricEnabled = securityRepository.biometricEnabled,
        ),
    )
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    val biometricEnabled: Boolean get() = securityRepository.biometricEnabled
    val pinEnabled: Boolean get() = securityRepository.pinEnabled

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    fun completeOnboarding() {
        securityRepository.completeOnboarding()
    }

    fun createVault(password: CharArray) = runHeavy {
        // Argon2 derivation happens here; keep it off the main thread.
        securityRepository.createVault(password)
    }

    // --- Importing a shared .dsvault file ----------------------------------------------

    /** Reads a picked/opened `.dsvault` [uri] into a temp file and shows the import prompt. */
    fun beginImportFromUri(uri: Uri) = runHeavy {
        val name = displayName(uri)
        val temp = File(appContext.filesDir, "import_pending.dsvault")
        val ok = withContext(Dispatchers.IO) {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
                true
            } ?: false
        }
        if (ok) {
            securityRepository.beginImport(temp, name)
        } else {
            _ui.value = _ui.value.copy(error = "Couldn't read that file.")
        }
    }

    fun confirmImport(password: CharArray) = runHeavy {
        if (!securityRepository.confirmImport(password)) {
            _ui.value = _ui.value.copy(error = "Wrong password or not a valid DocSafe vault.")
        }
    }

    /** Imports the pending shared file using a recovery code instead of the password. */
    fun confirmImportWithRecoveryCode(code: CharArray) = runHeavy {
        if (!securityRepository.confirmImportWithRecoveryCode(code)) {
            _ui.value = _ui.value.copy(error = appContext.getString(R.string.recovery_code_invalid))
        }
    }

    fun cancelImport() {
        securityRepository.cancelImport()
    }

    private fun displayName(uri: Uri): String? {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    fun enablePin(pin: CharArray) = runHeavy {
        securityRepository.enablePin(pin)
        _ui.value = _ui.value.copy(pinEnabled = true)
    }

    fun unlockWithPin(pin: CharArray) = runHeavy {
        if (!securityRepository.unlockWithPin(pin)) {
            _ui.value = _ui.value.copy(error = "Incorrect PIN.")
        }
    }

    // --- Biometric: cipher is provided here, the prompt is driven by the composable. ---

    fun biometricEncryptCipher(): Cipher = securityRepository.biometricEncryptCipher()

    fun biometricDecryptCipher(): Cipher = securityRepository.biometricDecryptCipher()

    fun completeBiometricSetup(cipher: Cipher) = runHeavy {
        securityRepository.completeBiometricSetup(cipher)
        _ui.value = _ui.value.copy(biometricEnabled = true)
    }

    fun completeBiometricUnlock(cipher: Cipher) = runHeavy {
        securityRepository.completeBiometricUnlock(cipher)
    }

    fun showError(message: String) {
        _ui.value = _ui.value.copy(error = message)
    }

    private fun runHeavy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, error = null)
            try {
                withContext(Dispatchers.Default) { block() }
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = e.message ?: "Something went wrong.")
            } finally {
                _ui.value = _ui.value.copy(busy = false)
            }
        }
    }
}
