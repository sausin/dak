package app.dak.index.repo

import androidx.room.withTransaction
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.dao.ChannelRow
import app.dak.index.db.entity.SenderAlias
import app.dak.index.db.entity.SenderFold
import app.dak.index.db.entity.SenderMergeGroup
import app.dak.index.enrich.ConversationIds
import app.dak.index.enrich.FoldCandidate
import app.dak.index.enrich.FoldProposal
import app.dak.index.enrich.FoldSuggester
import app.dak.index.enrich.SenderGrouping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** One sender channel inside a conversation (`HDFCBK` for `VM-HDFCBK` / `JD-HDFCBK`, or a number). */
data class FoldChannel(
    val channel: String,
    /** Raw addresses seen for the channel, most recent first (e.g. `VM-HDFCBK`, `JD-HDFCBK`). */
    val addresses: List<String>,
    val lastSeenMillis: Long,
    val subIds: Set<Int>,
    val messageCount: Int,
    /** True when the user folded or unfolded this channel by hand. */
    val userRule: Boolean,
)

/** A folded conversation: several sender channels shown as one thread. */
data class FoldGroup(
    val conversationId: String,
    val groupKey: String,
    /** User name, else brand, else the group key. */
    val title: String,
    val channels: List<FoldChannel>,
    val lastSeenMillis: Long,
    /** True for a group the user created by hand (rather than by the brand table). */
    val manual: Boolean,
)

/** A conversation that can be picked as a "Fold into…" target. */
data class FoldTarget(
    val conversationId: String,
    /** Group name, brand, or the newest raw address (the UI may swap in a contact name). */
    val title: String,
    val address: String,
    val lastSeenMillis: Long,
)

/** Undo token of a fold edit: each touched channel's previous rule (null = it had none). */
data class FoldReceipt(
    val previous: Map<String, String?>,
    /** Where the edited conversation lives now. */
    val conversationId: String,
)

/**
 * Sender groups ("folding"), display layer only — the provider keeps the underlying threads distinct:
 * - automatic folds: DLT prefixes (`VM-`/`JD-HDFCBK`) and the template bundle's brand table (`HDFCBK`, `HDFC` ->
 *   one "HDFC Bank" conversation);
 * - manual folds of any conversations ([foldTogether], [foldInto]), unfold of one channel ([unfoldChannel]),
 *   dissolving a whole group ([dissolve]), each undoable ([undo]);
 * - renaming a group, and the older per-address split/merge edits.
 *
 * Rules are stored per channel in the index and survive rebuilds; old conversation ids keep resolving through the
 * alias map (see [ConversationRepository.resolveConversationId]).
 */
@Singleton
class SenderMergeRepository @Inject constructor(
    private val db: DakIndexDatabase,
    private val folds: FoldEngine,
) {
    private val mergeDao = db.senderMergeDao()
    private val foldDao = db.senderFoldDao()
    private val messageDao = db.messageDao()

    fun groups(): Flow<List<SenderMergeGroup>> = mergeDao.observeGroups()

    fun group(mergeKey: String): Flow<SenderMergeGroup?> = mergeDao.observeGroup(mergeKey)

    /** Folded conversations (two or more channels, or a user fold) with their channels, most recent first. */
    fun foldGroups(): Flow<List<FoldGroup>> =
        combine(messageDao.observeChannels(), foldDao.observeAll(), mergeDao.observeGroups()) { rows, rules, names ->
            val ruleOf = rules.associate { it.channel to it.groupKey }
            val nameOf = names.associate { it.mergeKey to it.displayName }
            rows.filter { ConversationIds.isMergeGroup(it.conversationId) }
                .groupBy { it.conversationId }
                .mapNotNull { (conversationId, convRows) ->
                    val channels = channelsFrom(convRows, ruleOf)
                    val key = ConversationIds.mergeKeyOf(conversationId) ?: return@mapNotNull null
                    val manual = key.startsWith(SenderGrouping.MANUAL_PREFIX)
                    if (channels.size < 2 && !manual) return@mapNotNull null
                    FoldGroup(
                        conversationId = conversationId,
                        groupKey = key,
                        title = nameOf[key] ?: convRows.maxByOrNull { it.lastSeen }?.canonicalSender ?: key.removePrefix(SenderGrouping.MANUAL_PREFIX),
                        channels = channels,
                        lastSeenMillis = channels.maxOfOrNull { it.lastSeenMillis } ?: 0L,
                        manual = manual,
                    )
                }
                .sortedByDescending { it.lastSeenMillis }
        }.flowOn(Dispatchers.Default)

    /** Channels of one conversation, most recent first (for bubble channel chips and the channel filter). */
    fun channelsOf(conversationId: String): Flow<List<FoldChannel>> =
        combine(messageDao.observeChannelsOf(conversationId), foldDao.observeAll()) { rows, rules ->
            channelsFrom(rows, rules.associate { it.channel to it.groupKey })
        }.flowOn(Dispatchers.Default)

    /**
     * Fold suggestions ("These 3 senders look like Amazon — fold?") from the brand table and similar headers.
     * [titleOf] supplies a display title per conversation id when there is no brand (contact name, address).
     */
    fun foldSuggestions(titleOf: (conversationId: String, address: String) -> String = { _, address -> address }): Flow<List<FoldProposal>> =
        combine(messageDao.observeChannels(), foldDao.observeAll()) { rows, rules ->
            val candidates = rows.groupBy { it.conversationId }.map { (conversationId, convRows) ->
                val newest = convRows.maxByOrNull { it.lastSeen }!!
                FoldCandidate(
                    conversationId = conversationId,
                    title = newest.canonicalSender ?: titleOf(conversationId, newest.address),
                    channels = convRows.filter { !SenderGrouping.isAddressList(it.address) }
                        .mapTo(LinkedHashSet()) { SenderGrouping.channelOf(it.address) },
                    canonicalSender = newest.canonicalSender,
                    lastSeenMillis = newest.lastSeen,
                )
            }
            FoldSuggester.suggest(candidates, rules.mapTo(HashSet()) { it.channel })
        }.flowOn(Dispatchers.Default)

    /** Conversations that can be folded (group MMS excluded), most recent first, at most [limit]. */
    fun foldTargets(limit: Int = 200): Flow<List<FoldTarget>> =
        combine(messageDao.observeChannels(), mergeDao.observeGroups()) { rows, names ->
            val nameOf = names.associate { it.mergeKey to it.displayName }
            rows.filter { !SenderGrouping.isAddressList(it.address) }
                .groupBy { it.conversationId }
                .map { (conversationId, convRows) ->
                    val newest = convRows.maxByOrNull { it.lastSeen }!!
                    val key = ConversationIds.mergeKeyOf(conversationId)
                    FoldTarget(
                        conversationId = conversationId,
                        title = key?.let { nameOf[it] } ?: newest.canonicalSender ?: newest.address,
                        address = newest.address,
                        lastSeenMillis = newest.lastSeen,
                    )
                }
                .sortedByDescending { it.lastSeenMillis }
                .take(limit)
        }.flowOn(Dispatchers.Default)

    /**
     * Folds [conversationIds] into one conversation. The first merge-group conversation among them is the target
     * (so "HDFC Bank" keeps its id); if there is none, a new manual group is created. [name] (optional) names it.
     * Group MMS conversations are skipped (they cannot be folded).
     */
    suspend fun foldTogether(conversationIds: List<String>, name: String? = null): FoldReceipt = withContext(Dispatchers.IO) {
        val ids = conversationIds.map { folds.resolve(it) }.distinct()
        val channelsById = ids.associateWith { folds.channelsOf(it) }.filterValues { it.isNotEmpty() }
        require(channelsById.isNotEmpty()) { "nothing to fold" }
        val targetId = channelsById.keys.firstOrNull { id ->
            ConversationIds.mergeKeyOf(id)?.startsWith(SenderGrouping.SPLIT_PREFIX) == false
        }
        val targetKey = targetId?.let { ConversationIds.mergeKeyOf(it) }
            ?: SenderGrouping.manualKey(channelsById.values.first().first())
        val channels = channelsById.filterKeys { it != targetId }.values.flatten().toMutableSet()
        if (targetId == null || targetKey.startsWith(SenderGrouping.MANUAL_PREFIX)) {
            // A manual group has no automatic members: every channel, the target's included, needs a rule.
            channelsById.values.flatten().toCollection(channels)
        }
        val receipt = setRules(channels.associateWith { targetKey }, ConversationIds.forMergeGroup(targetKey))
        val trimmed = name?.trim()
        if (!trimmed.isNullOrEmpty()) rename(targetKey, trimmed)
        receipt
    }

    /** Folds [conversationId] into [targetConversationId] (conversation menu "Fold into…"). */
    suspend fun foldInto(conversationId: String, targetConversationId: String): FoldReceipt =
        foldTogether(listOf(targetConversationId, conversationId))

    /** Splits one channel back out of its group; it stands alone even if the brand table would fold it. */
    suspend fun unfoldChannel(channel: String): FoldReceipt = withContext(Dispatchers.IO) {
        setRules(mapOf(channel to channel), null)
    }

    /** Unfolds every channel of a folded conversation. */
    suspend fun dissolve(conversationId: String): FoldReceipt = withContext(Dispatchers.IO) {
        val resolved = folds.resolve(conversationId)
        val channels = folds.channelsOf(resolved)
        setRules(channels.associateWith { it }, null)
    }

    /** Reverts a fold edit. Returns where the edited conversation lives now. */
    suspend fun undo(receipt: FoldReceipt): String = withContext(Dispatchers.IO) {
        db.withTransaction {
            val now = System.currentTimeMillis()
            for ((channel, previous) in receipt.previous) {
                if (previous == null) foldDao.delete(channel) else foldDao.put(SenderFold(channel, previous, now))
            }
            folds.regroup(receipt.previous.keys)
        }
        folds.resolve(receipt.conversationId)
    }

    /** "Not now" on a suggestion: its channels are marked as decided (standalone), so it is not suggested again. */
    suspend fun dismissSuggestion(proposal: FoldProposal): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val channels = proposal.conversationIds.flatMap { folds.channelsOf(it) }.distinct()
        db.withTransaction {
            for (channel in channels) {
                if (foldDao.get(channel) == null) foldDao.put(SenderFold(channel, channel, now))
            }
        }
    }

    private suspend fun setRules(rules: Map<String, String>, resultConversationId: String?): FoldReceipt {
        val now = System.currentTimeMillis()
        val previous = LinkedHashMap<String, String?>()
        val moves = db.withTransaction {
            for ((channel, groupKey) in rules) {
                previous[channel] = foldDao.get(channel)?.groupKey
                foldDao.put(SenderFold(channel, groupKey, now))
            }
            folds.regroup(rules.keys)
        }
        val result = resultConversationId ?: rules.keys.firstOrNull()?.let { conversationOfChannel(it) } ?: moves.values.firstOrNull().orEmpty()
        return FoldReceipt(previous, result)
    }

    private suspend fun conversationOfChannel(channel: String): String? {
        val rules = folds.rules()
        val row = messageDao.addressThreads().firstOrNull { SenderGrouping.channelOf(it.address) == channel } ?: return null
        return SenderGrouping.resolve(row.address, row.threadId, rules).conversationId
    }

    // ---- backup (fold rules and group names are user data; the rest of the index is rebuildable) ----

    /** Fold rules and group names as JSON, for the open backup export. */
    suspend fun exportRules(): String = withContext(Dispatchers.IO) {
        val names = mergeDao.allGroups().filter { it.userEdited }.map { FoldName(it.mergeKey, it.displayName) }
        val rules = foldDao.all().map { FoldRule(it.channel, it.groupKey) }
        backupJson.encodeToString(FoldBackup.serializer(), FoldBackup(rules = rules, names = names))
    }

    /** Restores [exportRules] output (additively: existing rules for the same channels are replaced). */
    suspend fun importRules(json: String): Boolean = withContext(Dispatchers.IO) {
        val backup = runCatching { backupJson.decodeFromString(FoldBackup.serializer(), json) }.getOrNull() ?: return@withContext false
        val now = System.currentTimeMillis()
        db.withTransaction {
            foldDao.putAll(backup.rules.filter { it.channel.isNotBlank() && it.groupKey.isNotBlank() }.map { SenderFold(it.channel, it.groupKey, now) })
            for (name in backup.names) {
                if (name.mergeKey.isBlank() || name.displayName.isBlank()) continue
                mergeDao.putGroup(SenderMergeGroup(name.mergeKey, name.displayName, true, null, false, now))
            }
            folds.regroup(backup.rules.mapTo(HashSet()) { it.channel })
        }
        true
    }

    // ---- names and legacy per-address edits ----

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
            regroupAddress(address)
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
            regroupAddress(address)
        }
    }

    /** Re-applies grouping to every indexed row of [address]; returns its conversation id. */
    private suspend fun regroupAddress(address: String): String {
        val aliasKey = SenderGrouping.aliasKey(address)
        val rules = folds.rules()
        val rows = messageDao.addressThreads().filter { SenderGrouping.aliasKey(it.address) == aliasKey }
        val moves = LinkedHashMap<String, String>()
        for (row in rows) {
            val grouping = SenderGrouping.resolve(row.address, row.threadId, rules)
            messageDao.regroupAddressThread(row.address, row.threadId, grouping.mergeKey, grouping.conversationId)
            if (grouping.conversationId != row.conversationId) moves[row.conversationId] = grouping.conversationId
        }
        folds.recordMoves(moves)
        val sample = rows.firstOrNull()
        return SenderGrouping.resolve(address, sample?.threadId ?: 0L, rules).conversationId.let { id ->
            if (sample == null && ConversationIds.threadIdOf(id) == 0L) ConversationIds.forMergeGroup(SenderGrouping.mergeKey(address, rules.aliases)) else id
        }
    }

    private fun channelsFrom(rows: List<ChannelRow>, ruleOf: Map<String, String>): List<FoldChannel> =
        rows.filter { !SenderGrouping.isAddressList(it.address) }
            .groupBy { SenderGrouping.channelOf(it.address) }
            .map { (channel, channelRows) ->
                val sorted = channelRows.sortedByDescending { it.lastSeen }
                FoldChannel(
                    channel = channel,
                    addresses = sorted.map { it.address }.distinct(),
                    lastSeenMillis = sorted.first().lastSeen,
                    subIds = channelRows.flatMapTo(HashSet()) { r -> r.subIds?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty() },
                    messageCount = channelRows.sumOf { it.n },
                    userRule = channel in ruleOf,
                )
            }
            .sortedByDescending { it.lastSeenMillis }

    @Serializable
    private data class FoldRule(val channel: String, val groupKey: String)

    @Serializable
    private data class FoldName(val mergeKey: String, val displayName: String)

    @Serializable
    private data class FoldBackup(val version: Int = 1, val rules: List<FoldRule> = emptyList(), val names: List<FoldName> = emptyList())

    private companion object {
        val backupJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
