package app.docsafe.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.docsafe.security.SecurityState
import app.docsafe.ui.auth.AuthViewModel
import app.docsafe.ui.auth.ChooseMethodsScreen
import app.docsafe.ui.auth.CreateVaultScreen
import app.docsafe.ui.auth.ImportVaultScreen
import app.docsafe.ui.auth.UnlockScreen
import app.docsafe.ui.vault.ShareTargetScreen
import app.docsafe.ui.vault.VaultNavHost

/**
 * Root of the app. The security state machine decides what is shown: onboarding when there
 * is no vault, the unlock gate when locked, and the document UI when unlocked.
 */
@Composable
fun DocSafeApp() {
    val authViewModel: AuthViewModel = hiltViewModel()
    val state by authViewModel.securityState.collectAsStateWithLifecycle()
    val pendingShare by authViewModel.pendingShare.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            SecurityState.Uninitialized -> CreateVaultScreen(authViewModel)
            SecurityState.ImportPending -> ImportVaultScreen(authViewModel)
            SecurityState.NeedsUnlockMethod -> ChooseMethodsScreen(authViewModel)
            SecurityState.Locked -> UnlockScreen(authViewModel)
            // Files shared into the app take precedence: pick a destination first.
            SecurityState.Unlocked -> if (pendingShare.isNotEmpty()) ShareTargetScreen() else VaultNavHost()
        }
    }
}
