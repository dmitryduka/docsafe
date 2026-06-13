package app.docsafe

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import android.content.Context
import app.docsafe.i18n.withAppLocale
import app.docsafe.security.SecurityRepository
import app.docsafe.share.PendingShareStore
import app.docsafe.share.SharedFileRef
import app.docsafe.ui.DocSafeApp
import app.docsafe.ui.theme.DocSafeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Single activity. Extends [FragmentActivity] because [androidx.biometric.BiometricPrompt]
 * requires it. Handles `.dsvault` files opened into the app (import) and files shared into
 * the app from other apps (attach to a document).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var securityRepository: SecurityRepository

    @Inject
    lateinit var pendingShareStore: PendingShareStore

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DocSafeTheme {
                DocSafeApp()
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { stageVaultImport(it) }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) handleSharedIn(listOf(uri))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) handleSharedIn(uris)
            }
        }
    }

    /** A single shared `.dsvault` is treated as a vault import; anything else is staged to attach. */
    private fun handleSharedIn(uris: List<Uri>) {
        if (uris.size == 1 && (queryName(uris[0])?.endsWith(".dsvault", ignoreCase = true) == true)) {
            stageVaultImport(uris[0])
            return
        }
        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "share_in").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                uris.mapNotNull { uri ->
                    val name = queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                    val mime = contentResolver.getType(uri)
                    val dest = File(dir, sanitize(name))
                    val copied = runCatching {
                        contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                        } != null
                    }.getOrDefault(false)
                    if (copied) SharedFileRef(dest, name, mime) else null
                }
            }
            if (staged.isNotEmpty()) pendingShareStore.set(staged)
        }
    }

    private fun stageVaultImport(uri: Uri) {
        lifecycleScope.launch {
            val temp = File(filesDir, "import_pending.dsvault")
            val name = queryName(uri)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { input.copyTo(it) }
                    } != null
                }.getOrDefault(false)
            }
            if (ok) securityRepository.beginImport(temp, name)
        }
    }

    private fun queryName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "file" }
}
