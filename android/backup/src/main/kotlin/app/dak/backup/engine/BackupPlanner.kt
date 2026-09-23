package app.dak.backup.engine

import app.dak.backup.format.MessageRecord

/** Result of comparing a previous backup's digest against the current message set. */
data class BackupPlan(
    val added: List<MessageRecord>,
    val changed: List<MessageRecord>,
    val deletedKeys: List<String>,
    val unchangedCount: Int,
) {
    val isEmpty: Boolean get() = added.isEmpty() && changed.isEmpty() && deletedKeys.isEmpty()

    /** Messages that need to be (re-)written into the next snapshot: added + changed. */
    fun toWrite(): List<MessageRecord> = added + changed
}

/**
 * Compares the message-key -> content-hash digest of a previous backup against the current live
 * message set to compute what an incremental snapshot needs to carry. Streams over [current] once.
 */
object BackupPlanner {
    /** [previousDigest] maps [MessageRecord.key] to the content hash recorded in the prior snapshot. */
    fun plan(previousDigest: Map<String, String>, current: Sequence<MessageRecord>): BackupPlan {
        val seenKeys = HashSet<String>(previousDigest.size)
        val added = mutableListOf<MessageRecord>()
        val changed = mutableListOf<MessageRecord>()
        var unchanged = 0

        for (record in current) {
            seenKeys += record.key
            val previousHash = previousDigest[record.key]
            when {
                previousHash == null -> added += record
                previousHash != record.contentHash() -> changed += record
                else -> unchanged++
            }
        }

        val deletedKeys = previousDigest.keys.filterNot { it in seenKeys }
        return BackupPlan(added, changed, deletedKeys, unchanged)
    }

    /** Builds the key -> content-hash digest of a message set, to persist alongside a snapshot for next time. */
    fun digestOf(messages: Sequence<MessageRecord>): Map<String, String> =
        messages.associate { it.key to it.contentHash() }
}
