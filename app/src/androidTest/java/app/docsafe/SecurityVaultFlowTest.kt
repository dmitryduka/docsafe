package app.docsafe

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import app.docsafe.security.BiometricKeyManager
import app.docsafe.security.SecureStore
import app.docsafe.security.SecurityRepository
import app.docsafe.security.SecurityState
import app.docsafe.vault.VaultRepository
import app.docsafe.vault.VaultSession
import app.docsafe.vault.model.AttachmentKind
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the real device-side stack end-to-end with no UI: create a vault, set a PIN,
 * lock, unlock with the PIN, and exercise document/attachment operations + single-blob
 * read. This runs against the actual Keystore-backed [EncryptedSharedPreferences], which
 * cannot run in a host JVM test.
 */
@RunWith(AndroidJUnit4::class)
class SecurityVaultFlowTest {

    private lateinit var context: android.content.Context
    private lateinit var secureStore: SecureStore
    private lateinit var session: VaultSession
    private lateinit var security: SecurityRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Isolate from any prior run.
        File(context.filesDir, "vault.dsvault").delete()
        secureStore = SecureStore(context)
        secureStore.clearUnlockMethods()
        secureStore.vaultImported = false
        session = VaultSession(context)
        security = SecurityRepository(secureStore, BiometricKeyManager(), session)
    }

    @Test
    fun createSetPinLockUnlockAndStoreDocument() = runBlocking {
        assertThat(security.state.value).isEqualTo(SecurityState.Uninitialized)

        security.createVault("correct horse battery staple".toCharArray())
        assertThat(security.state.value).isEqualTo(SecurityState.NeedsUnlockMethod)

        security.enablePin("135790".toCharArray())
        security.completeOnboarding()
        assertThat(security.state.value).isEqualTo(SecurityState.Unlocked)

        // Add content while unlocked.
        val repo = VaultRepository(session, security)
        repo.refresh()
        val folderId = repo.createFolder("Taxes", null)
        val docId = repo.createDocument("2025 Return", folderId)
        val photo = ByteArray(50_000) { (it % 255).toByte() }
        repo.addAttachment(docId, photo, AttachmentKind.IMAGE, "scan.jpg")

        // Lock: DEK leaves memory, vault closes.
        security.lock()
        assertThat(security.state.value).isEqualTo(SecurityState.Locked)
        assertThat(session.isOpen).isFalse()

        // Wrong PIN is rejected.
        assertThat(security.unlockWithPin("000000".toCharArray())).isFalse()
        assertThat(security.state.value).isEqualTo(SecurityState.Locked)

        // Correct PIN unlocks without the master password.
        assertThat(security.unlockWithPin("135790".toCharArray())).isTrue()
        assertThat(security.state.value).isEqualTo(SecurityState.Unlocked)

        // The structure and the blob survived the lock/unlock round trip.
        val repo2 = VaultRepository(session, security)
        repo2.refresh()
        val reloadedDoc = repo2.documentsIn(folderId).single()
        assertThat(reloadedDoc.name).isEqualTo("2025 Return")
        val blobId = reloadedDoc.attachments.single().blobId
        assertThat(repo2.readBlob(blobId)).isEqualTo(photo)
    }

    @Test
    fun launchingExternalActivityDoesNotLockVault() = runBlocking {
        security.createVault("picker-test-password".toCharArray())
        security.enablePin("9090".toCharArray())
        security.completeOnboarding()
        assertThat(security.state.value).isEqualTo(SecurityState.Unlocked)

        // Simulate launching the system file picker and coming back with a result.
        security.notifyExternalActivityStarting()
        security.onAppBackgrounded()
        security.onAppForegrounded()

        // The vault must remain open so the picked file can actually be attached.
        assertThat(security.state.value).isEqualTo(SecurityState.Unlocked)
        assertThat(session.isOpen).isTrue()
    }

    @Test
    fun briefBackgroundWithinGraceKeepsVaultUnlocked() = runBlocking {
        security.createVault("grace-test-password".toCharArray())
        security.enablePin("1313".toCharArray())
        security.completeOnboarding()

        // Background then immediately foreground (elapsed well under the grace period).
        security.onAppBackgrounded()
        security.onAppForegrounded()

        assertThat(security.state.value).isEqualTo(SecurityState.Unlocked)
        assertThat(session.isOpen).isTrue()
    }

    @Test
    fun masterPasswordReopensVaultAfterReimport() = runBlocking {
        security.createVault("shared-team-password".toCharArray())
        security.enablePin("4242".toCharArray())
        security.completeOnboarding()
        val repo = VaultRepository(session, security)
        repo.refresh()
        repo.createFolder("Shared", null)
        security.lock()

        // Simulate a fresh device import using the master password.
        secureStore.clearUnlockMethods()
        secureStore.vaultImported = false
        val fresh = SecurityRepository(secureStore, BiometricKeyManager(), VaultSession(context))
        assertThat(fresh.importWithPassword("shared-team-password".toCharArray())).isTrue()
        assertThat(fresh.state.value).isEqualTo(SecurityState.NeedsUnlockMethod)
    }
}
