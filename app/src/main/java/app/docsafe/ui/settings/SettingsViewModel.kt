package app.docsafe.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.docsafe.i18n.LocalePrefs
import app.docsafe.security.SecurityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.inject.Inject

data class SecurityFlags(val biometric: Boolean, val pin: Boolean)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val securityRepository: SecurityRepository,
) : ViewModel() {

    private val localePrefs = LocalePrefs(appContext)

    private val _flags = MutableStateFlow(SecurityFlags(securityRepository.biometricEnabled, securityRepository.pinEnabled))
    val flags: StateFlow<SecurityFlags> = _flags.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val languageTag: String? get() = localePrefs.languageTag

    private fun refresh() {
        _flags.value = SecurityFlags(securityRepository.biometricEnabled, securityRepository.pinEnabled)
    }

    fun setPin(pin: CharArray) = viewModelScope.launch {
        _busy.value = true
        withContext(Dispatchers.Default) { securityRepository.enablePin(pin) }
        refresh()
        _busy.value = false
    }

    /** Returns false if disabling would leave no unlock method. */
    fun disablePin(): Boolean = securityRepository.disablePin().also { if (it) refresh() }

    fun disableBiometric(): Boolean = securityRepository.disableBiometric().also { if (it) refresh() }

    fun biometricEncryptCipher(): Cipher = securityRepository.biometricEncryptCipher()

    fun completeBiometricSetup(cipher: Cipher) = viewModelScope.launch {
        _busy.value = true
        withContext(Dispatchers.Default) { securityRepository.completeBiometricSetup(cipher) }
        refresh()
        _busy.value = false
    }

    /** Persists the chosen UI language (null = system). The caller recreates the activity. */
    fun setLanguage(tag: String?) {
        localePrefs.languageTag = tag
    }
}
