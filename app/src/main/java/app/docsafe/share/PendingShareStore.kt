package app.docsafe.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A file shared into DocSafe from another app, already copied to app-private cache. */
data class SharedFileRef(val file: File, val name: String, val mime: String?)

/**
 * Holds files shared into the app (via ACTION_SEND) until the user picks a destination
 * document. They're staged in cache by [app.docsafe.MainActivity] so the content grant from
 * the sending app doesn't need to outlive the unlock + destination-picker steps.
 */
@Singleton
class PendingShareStore @Inject constructor() {
    private val _files = MutableStateFlow<List<SharedFileRef>>(emptyList())
    val files: StateFlow<List<SharedFileRef>> = _files.asStateFlow()

    fun set(files: List<SharedFileRef>) {
        clear()
        _files.value = files
    }

    fun current(): List<SharedFileRef> = _files.value

    fun clear() {
        _files.value.forEach { runCatching { it.file.delete() } }
        _files.value = emptyList()
    }
}
