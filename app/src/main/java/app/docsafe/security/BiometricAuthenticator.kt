package app.docsafe.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Result of a biometric/credential authentication that unlocks a [Cipher]. */
sealed interface BiometricResult {
    data class Success(val cipher: Cipher?) : BiometricResult
    data class Error(val code: Int, val message: String) : BiometricResult
    data object Failed : BiometricResult
}

/**
 * Coroutine-friendly wrapper over [BiometricPrompt]. We allow both strong biometrics and the
 * device credential (PIN/pattern/password) so a device without enrolled biometrics can still
 * use this path; an app-managed PIN is the separate fallback.
 */
class BiometricAuthenticator(private val activity: FragmentActivity) {

    fun status(): Int =
        BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)

    fun isAvailable(): Boolean = status() == BiometricManager.BIOMETRIC_SUCCESS

    suspend fun authenticate(title: String, subtitle: String, cryptoCipher: Cipher): BiometricResult =
        suspendCancellableCoroutine { cont ->
            val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(BiometricResult.Success(result.cryptoObject?.cipher))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(BiometricResult.Error(errorCode, errString.toString()))
                    }

                    override fun onAuthenticationFailed() {
                        // A single rejected attempt; the prompt stays open for retries.
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()
            try {
                prompt.authenticate(info, BiometricPrompt.CryptoObject(cryptoCipher))
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
}
