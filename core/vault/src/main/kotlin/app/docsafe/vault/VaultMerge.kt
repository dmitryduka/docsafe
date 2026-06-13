package app.docsafe.vault

import app.docsafe.vault.model.Document
import app.docsafe.vault.model.Folder
import app.docsafe.vault.model.VaultIndex

/**
 * Merges two vault structures so that several people sharing one vault through Drive can
 * add and remove documents concurrently and converge on the same result.
 *
 * Strategy: **per-entity Last-Writer-Wins with tombstones.** Every folder and document
 * carries `(modifiedAt, modifiedBy)`. For each id present on either side we keep the record
 * with the greater timestamp; ties break deterministically on `modifiedBy` (the device id)
 * so all devices reach the same winner regardless of merge order. A deletion is just a
 * record with `deleted = true` and a fresh `modifiedAt`, so "delete vs concurrent edit"
 * resolves by whichever happened later.
 *
 * The merge is commutative, associative, and idempotent over the `(modifiedAt, modifiedBy)`
 * order, which is what guarantees convergence.
 *
 * NOTE: merging is at document granularity. Two devices concurrently editing the *same*
 * document keep only the later write (an attachment added on the losing side is dropped).
 * That is acceptable for the add/remove-documents workflow; attachment-level merging is a
 * possible later refinement. Blob locations are intentionally left as [local]'s — physical
 * blob reconciliation is the sync layer's job.
 */
object VaultMerge {

    fun merge(local: VaultIndex, remote: VaultIndex): VaultIndex {
        val folders = mergeMaps(local.folders, remote.folders, { it.modifiedAt }, { it.modifiedBy })
        val documents = mergeMaps(local.documents, remote.documents, { it.modifiedAt }, { it.modifiedBy })
        return local.copy(
            folders = folders,
            documents = documents,
            // Blob byte locations are file-specific; keep local's. Sync copies in any
            // blobs that newly-merged documents reference but this file lacks.
            blobs = local.blobs,
        )
    }

    private fun <T> mergeMaps(
        a: Map<String, T>,
        b: Map<String, T>,
        timestamp: (T) -> Long,
        author: (T) -> String,
    ): Map<String, T> {
        val result = LinkedHashMap<String, T>(a)
        for ((id, bv) in b) {
            val av = result[id]
            result[id] = pickWinner(av, bv, timestamp, author)
        }
        return result
    }

    private fun <T> pickWinner(a: T?, b: T?, timestamp: (T) -> Long, author: (T) -> String): T {
        if (a == null) return b!!
        if (b == null) return a
        val ta = timestamp(a)
        val tb = timestamp(b)
        return when {
            ta > tb -> a
            tb > ta -> b
            // Deterministic tiebreak so all replicas agree.
            author(a) >= author(b) -> a
            else -> b
        }
    }

    // Exposed for documentation/testing intent; the generic path above handles both types.
    @Suppress("unused")
    private fun newerFolder(a: Folder, b: Folder): Folder = pickWinner(a, b, { it.modifiedAt }, { it.modifiedBy })

    @Suppress("unused")
    private fun newerDocument(a: Document, b: Document): Document = pickWinner(a, b, { it.modifiedAt }, { it.modifiedBy })
}
