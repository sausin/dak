package app.dak.index.repo

import androidx.room.withTransaction
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.entity.SenderAlias
import app.dak.index.db.entity.SenderMergeGroup
import app.dak.index.enrich.ConversationIds
import app.dak.index.enrich.SenderGrouping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User edits of sender merge groups (display layer only; the provider keeps the underlying threads distinct):
 * rename a group, split one raw sender out of its group, merge a sender into another group, and undo each.
 */
@Singleton
class SenderMergeRepository @Inject constructor(
    private val db: DakIndexDatabase,
) {
    private val mergeDao = db.senderMergeDao()
    private val messageDao = db.messageDao()

    fun groups(): Flow<List<SenderMergeGroup>> = mergeDao.observeGroups()

    fun group(mergeKey: String): Flow<SenderMergeGroup?> = mergeDao.observeGroup(mergeKey)

    /** Renames the group of a merge-group conversation (`m:<mergeKey>`); keeps the previous name for [undoRename]. */
    suspend fun rename(mergeKey: String, displayName: String): Unit = withContext(Dispatchers.IO) {
        val existing = mergeDao.group(mergeKey)
        mergeDao.putGroup(
            SenderMergeGroup(
                mergeKey = mergeKey,
                displayName = displayName.trim(),
                userEdited = true,
                previousDisplayName = existing?.displayName,
                previousUserEdited = existing?.userEdited ?: false,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Reverts the last [rename]. Returns false when there is nothing to undo. */
    suspend fun undoRename(mergeKey: String): Boolean = withContext(Dispatchers.IO) {
        val existing = mergeDao.group(mergeKey) ?: return@withContext false
        val previous = existing.previousDisplayName
        if (previous == null) {
            mergeDao.deleteGroup(mergeKey)
        } else {
            mergeDao.putGroup(
                existing.copy(
                    displayName = previous,
                    userEdited = existing.previousUserEdited,
                    previousDisplayName = null,
                    previousUserEdited = false,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        true
    }

    /** Splits one raw sender (e.g. `VK-AMAZON-P`) out of its merge group. Returns its new conversation id. */
    suspend fun splitSender(address: String): String = setAlias(address, SenderGrouping.splitKey(address))

    /** Moves one raw sender into another merge group. Returns the target conversation id. */
    suspend fun mergeSender(address: String, intoMergeKey: String): String = setAlias(address, intoMergeKey)

    /**
     * Reverts the last split/merge of [address]. Returns the conversation id it now belongs to, or null if there
     * was no edit.
     */
    suspend fun undoSenderEdit(address: String): String? = withContext(Dispatchers.IO) {
        val aliasKey = SenderGrouping.aliasKey(address)
        val alias = mergeDao.alias(aliasKey) ?: return@withContext null
        db.withTransaction {
            val previous = alias.previousMergeKey
            if (previous == null) {
                mergeDao.deleteAlias(aliasKey)
            } else {
                mergeDao.putAlias(alias.copy(mergeKey = previous, previousMergeKey = null, updatedAt = System.currentTimeMillis()))
            }
            regroup(address)
        }
    }

    private suspend fun setAlias(address: String, mergeKey: String): String = withContext(Dispatchers.IO) {
        val aliasKey = SenderGrouping.aliasKey(address)
        db.withTransaction {
            val current = mergeDao.alias(aliasKey)
            mergeDao.putAlias(
                SenderAlias(
                    address = aliasKey,
                    mergeKey = mergeKey,
                    previousMergeKey = current?.mergeKey,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            regroup(address)
        }
    }

    /** Re-applies grouping to every indexed row of [address]; returns its conversation id. */
    private suspend fun regroup(address: String): String {
        val aliasKey = SenderGrouping.aliasKey(address)
        val aliases = mergeDao.aliases().associate { it.address to it.mergeKey }
        val mergeKey = SenderGrouping.mergeKey(address, aliases)
        val sample = messageDao.anyFromAddress(aliasKey)
        val conversationId = SenderGrouping.conversationId(address, sample?.threadId ?: 0L, aliases)
        messageDao.regroupAddress(aliasKey, mergeKey, conversationId)
        return if (sample == null && ConversationIds.threadIdOf(conversationId) == 0L) {
            ConversationIds.forMergeGroup(mergeKey)
        } else {
            conversationId
        }
    }
}
