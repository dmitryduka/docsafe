package app.docsafe.vault

import com.google.common.truth.Truth.assertThat
import app.docsafe.vault.model.Document
import app.docsafe.vault.model.Folder
import app.docsafe.vault.model.VaultIndex
import org.junit.Test

class VaultMergeTest {

    private fun doc(id: String, name: String, t: Long, by: String, deleted: Boolean = false) =
        Document(id = id, folderId = null, name = name, attachments = emptyList(), createdAt = t, modifiedAt = t, modifiedBy = by, deleted = deleted)

    private fun folder(id: String, name: String, t: Long, by: String, deleted: Boolean = false) =
        Folder(id = id, parentId = null, name = name, createdAt = t, modifiedAt = t, modifiedBy = by, deleted = deleted)

    private fun indexOf(vararg docs: Document) = VaultIndex(documents = docs.associateBy { it.id })

    @Test
    fun concurrentAddsAreUnioned() {
        val local = indexOf(doc("a", "A", 1, "dev1"))
        val remote = indexOf(doc("b", "B", 1, "dev2"))
        val merged = VaultMerge.merge(local, remote)
        assertThat(merged.documents.keys).containsExactly("a", "b")
    }

    @Test
    fun newerEditWinsOverOlder() {
        val local = indexOf(doc("a", "old", 1, "dev1"))
        val remote = indexOf(doc("a", "new", 5, "dev2"))
        assertThat(VaultMerge.merge(local, remote).documents["a"]!!.name).isEqualTo("new")
    }

    @Test
    fun deleteWinsWhenItIsNewer() {
        val local = indexOf(doc("a", "alive", 1, "dev1"))
        val remote = indexOf(doc("a", "gone", 5, "dev2", deleted = true))
        assertThat(VaultMerge.merge(local, remote).documents["a"]!!.deleted).isTrue()
    }

    @Test
    fun editWinsOverOlderDelete() {
        val local = indexOf(doc("a", "edited", 9, "dev1"))
        val remote = indexOf(doc("a", "deleted", 5, "dev2", deleted = true))
        val winner = VaultMerge.merge(local, remote).documents["a"]!!
        assertThat(winner.deleted).isFalse()
        assertThat(winner.name).isEqualTo("edited")
    }

    @Test
    fun equalTimestampsBreakTieDeterministically() {
        val local = indexOf(doc("a", "fromZ", 5, "devZ"))
        val remote = indexOf(doc("a", "fromA", 5, "devA"))
        // Higher modifiedBy wins, and the result is the same regardless of merge order.
        assertThat(VaultMerge.merge(local, remote).documents["a"]!!.name).isEqualTo("fromZ")
        assertThat(VaultMerge.merge(remote, local).documents["a"]!!.name).isEqualTo("fromZ")
    }

    @Test
    fun mergeIsIdempotent() {
        val index = indexOf(doc("a", "A", 1, "dev1"), doc("b", "B", 2, "dev2"))
        assertThat(VaultMerge.merge(index, index).documents).isEqualTo(index.documents)
    }

    @Test
    fun mergeIsCommutativeForStructure() {
        val local = VaultIndex(
            documents = mapOf("a" to doc("a", "A2", 3, "dev1"), "b" to doc("b", "B", 1, "dev1")),
            folders = mapOf("f" to folder("f", "Folder", 2, "dev1")),
        )
        val remote = VaultIndex(
            documents = mapOf("a" to doc("a", "A1", 1, "dev2"), "c" to doc("c", "C", 4, "dev2")),
            folders = mapOf("f" to folder("f", "Folder!", 5, "dev2")),
        )
        val ab = VaultMerge.merge(local, remote)
        val ba = VaultMerge.merge(remote, local)
        assertThat(ab.documents).isEqualTo(ba.documents)
        assertThat(ab.folders).isEqualTo(ba.folders)
        // Newer values won.
        assertThat(ab.documents["a"]!!.name).isEqualTo("A2")
        assertThat(ab.folders["f"]!!.name).isEqualTo("Folder!")
    }

    @Test
    fun foldersFollowSameLwwRules() {
        val local = VaultIndex(folders = mapOf("f" to folder("f", "Keep", 10, "dev1")))
        val remote = VaultIndex(folders = mapOf("f" to folder("f", "Remove", 20, "dev2", deleted = true)))
        assertThat(VaultMerge.merge(local, remote).folders["f"]!!.deleted).isTrue()
    }
}
