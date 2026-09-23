package app.dak.index.repo

import androidx.room.withTransaction
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.dao.AddressThreadRow
import app.dak.index.db.entity.ConversationAlias
import app.dak.index.enrich.ConversationIds
import app.dak.index.enrich.GroupingRules
import app.dak.index.enrich.MessageEnricher
import app.dak.index.enrich.SenderGrouping
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared machinery behind sender folding: the current [GroupingRules] (legacy aliases + user fold rules + the
 * template bundle's brand table), re-grouping indexed rows after a rule change, and the conversation-id alias map
 * that keeps old ids (in notifications, search results, saved deep links) resolving to the thread their messages
 * moved to. Callers run it off the main thread (every function suspends on Room).
 */
@Singleton
class FoldEngine @Inject constructor(
    private val db: DakIndexDatabase,
    private val enricher: MessageEnricher,
) {
    private val messageDao = db.messageDao()
    private val mergeDao = db.senderMergeDao()
    private val foldDao = db.senderFoldDao()
    private val aliasDao = db.conversationAliasDao()
    private val prefsDao = db.conversationPrefsDao()

    /** Rules as currently stored. */
    suspend fun rules(): GroupingRules = GroupingRules(
        aliases = mergeDao.aliases().associate { it.address to it.mergeKey },
        folds = foldDao.all().associate { it.channel to it.groupKey },
        brandKey = { channel -> enricher.brandFoldKey(channel) },
    )

    /**
     * The id to open for [conversationId]: itself while it has messages (or is an un-indexed provider thread),
     * else where its messages moved to (following the alias chain a few hops).
     */
    suspend fun resolve(conversationId: String): String {
        var current = conversationId
        repeat(MAX_HOPS) {
            if (messageDao.hasConversation(current)) return current
            val next = aliasDao.get(current)?.newId ?: return current
            if (next == current) return current
            current = next
        }
        return current
    }

    /**
     * Re-applies [rules] to the rows of every address whose channel is in [channels] (all rows when null), records
     * aliases for conversations that became empty, and carries their per-conversation prefs over. Returns the
     * moves made (old conversation id -> new id).
     */
    suspend fun regroup(channels: Set<String>?, rules: GroupingRules? = null): Map<String, String> {
        val effective = rules ?: rules()
        val rows = messageDao.addressThreads()
            .filter { channels == null || SenderGrouping.channelOf(it.address) in channels }
        return regroupRows(rows, effective)
    }

    /** [regroup] for the rows of the given conversations only. */
    suspend fun regroupConversations(conversationIds: Collection<String>, rules: GroupingRules? = null): Map<String, String> {
        if (conversationIds.isEmpty()) return emptyMap()
        val effective = rules ?: rules()
        val rows = conversationIds.distinct().chunked(CHUNK).flatMap { messageDao.addressThreadsIn(it) }
        return regroupRows(rows, effective)
    }

    private suspend fun regroupRows(rows: List<AddressThreadRow>, rules: GroupingRules): Map<String, String> {
        val moves = LinkedHashMap<String, String>()
        db.withTransaction {
            for (row in rows) {
                val grouping = SenderGrouping.resolve(row.address, row.threadId, rules)
                if (grouping.conversationId == row.conversationId) continue
                messageDao.regroupAddressThread(row.address, row.threadId, grouping.mergeKey, grouping.conversationId)
                moves[row.conversationId] = grouping.conversationId
            }
            recordMoves(moves)
        }
        return moves
    }

    /**
     * For every move whose old conversation is now empty: alias old -> new (and re-point older aliases), and copy
     * the old conversation's prefs (pin, mute, reply SIM...) to the new one if it has none yet.
     */
    suspend fun recordMoves(moves: Map<String, String>) {
        if (moves.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((oldId, newId) in moves) {
            if (oldId == newId) continue
            // The destination has messages now, so an alias starting there is stale.
            aliasDao.delete(newId)
            if (messageDao.hasConversation(oldId)) continue
            aliasDao.put(ConversationAlias(oldId = oldId, newId = newId, createdAt = now))
            aliasDao.repoint(oldId, newId)
            val oldPrefs = prefsDao.get(oldId)
            if (oldPrefs != null && prefsDao.get(newId) == null) {
                prefsDao.put(oldPrefs.copy(conversationId = newId, updatedAt = now))
            }
        }
    }

    /** Distinct channels of the messages in [conversationId]. */
    suspend fun channelsOf(conversationId: String): Set<String> {
        val threadId = ConversationIds.threadIdOf(conversationId)
        val rows = messageDao.addressThreadsIn(listOf(conversationId))
        val list = if (rows.isEmpty() && threadId != null) {
            messageDao.addressesInThread(threadId).map { AddressThreadRow(it, threadId, conversationId, 0) }
        } else {
            rows
        }
        return list.filter { !SenderGrouping.isAddressList(it.address) }
            .mapTo(LinkedHashSet()) { SenderGrouping.channelOf(it.address) }
    }

    private companion object {
        const val MAX_HOPS = 6
        const val CHUNK = 400
    }
}
