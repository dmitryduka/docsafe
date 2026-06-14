package app.docsafe.vault

import app.docsafe.vault.model.Document
import app.docsafe.vault.model.Folder
import app.docsafe.vault.model.VaultIndex

/** Pure, UI-friendly queries over a [VaultIndex] snapshot. */

fun VaultIndex.childFolders(parentId: String?): List<Folder> =
    folders.values
        .filter { !it.deleted && it.parentId == parentId }
        .sortedBy { it.name.lowercase() }

fun VaultIndex.childDocuments(folderId: String?): List<Document> =
    documents.values
        .filter { !it.deleted && it.folderId == folderId }
        .sortedBy { it.name.lowercase() }

fun VaultIndex.activeFolder(id: String?): Folder? = id?.let { folders[it] }?.takeUnless { it.deleted }

/**
 * [root] plus all of its descendant folder ids (breadth-first). By default tombstoned folders are
 * excluded; pass [includeDeleted] = true for structural checks (e.g. a move-cycle guard) that must
 * still account for not-yet-purged folders. O(n) via a parent index.
 */
fun VaultIndex.descendantFolderIds(root: String, includeDeleted: Boolean = false): Set<String> {
    val byParent = folders.values
        .filter { includeDeleted || !it.deleted }
        .groupBy { it.parentId }
    val result = LinkedHashSet<String>()
    val stack = ArrayDeque<String>().apply { add(root) }
    while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (result.add(id)) byParent[id]?.forEach { stack.add(it.id) }
    }
    return result
}

fun VaultIndex.activeDocument(id: String): Document? = documents[id]?.takeUnless { it.deleted }

fun VaultIndex.starredFolders(): List<Folder> =
    folders.values.filter { !it.deleted && it.starred }.sortedBy { it.name.lowercase() }

fun VaultIndex.starredDocuments(): List<Document> =
    documents.values.filter { !it.deleted && it.starred }.sortedBy { it.name.lowercase() }

fun VaultIndex.hasStarred(): Boolean =
    folders.values.any { !it.deleted && it.starred } || documents.values.any { !it.deleted && it.starred }

/** Searches folder/document names and document tags (case-insensitive). */
fun VaultIndex.searchFolders(query: String): List<Folder> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return folders.values
        .filter { !it.deleted && it.name.lowercase().contains(q) }
        .sortedBy { it.name.lowercase() }
}

fun VaultIndex.searchDocuments(query: String): List<Document> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return documents.values
        .filter { doc ->
            !doc.deleted && (
                doc.name.lowercase().contains(q) ||
                    doc.tags.any { it.lowercase().contains(q) }
                )
        }
        .sortedBy { it.name.lowercase() }
}

/** Folders from the root down to (and including) [folderId], for a breadcrumb trail. */
fun VaultIndex.breadcrumb(folderId: String?): List<Folder> {
    if (folderId == null) return emptyList()
    val trail = ArrayDeque<Folder>()
    var current = folders[folderId]?.takeUnless { it.deleted }
    val guard = HashSet<String>()
    while (current != null && guard.add(current.id)) {
        trail.addFirst(current)
        current = current.parentId?.let { folders[it] }?.takeUnless { it.deleted }
    }
    return trail.toList()
}
