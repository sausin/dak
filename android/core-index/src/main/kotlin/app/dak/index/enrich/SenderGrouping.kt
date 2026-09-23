package app.dak.index.enrich

import app.dak.classify.SenderId
import app.dak.classify.SenderKind

/**
 * Everything that decides which conversation a sender address belongs to, besides the address itself.
 *
 * @property aliases legacy per-address overrides (alias key -> merge key) from `SenderMergeRepository`
 *   split/merge edits; they win over everything else.
 * @property folds user fold rules per channel (channel -> group key; a self-mapping means "unfolded").
 * @property brandKey the template bundle's brand-level fold key of a channel (`HDFC` -> `HDFCBK`), or null.
 */
class GroupingRules(
    val aliases: Map<String, String> = emptyMap(),
    val folds: Map<String, String> = emptyMap(),
    val brandKey: (String) -> String? = { null },
) {
    companion object {
        val NONE = GroupingRules()
    }
}

/**
 * Decides the merge key and conversation id of a message.
 *
 * A **channel** is one real-world sender id: [SenderId.mergeKey] of the address, so `VM-HDFCBK` / `JD-HDFCBK` /
 * `AX-HDFCBK-S` are one channel `HDFCBK`, and `+919876543210` / `09876543210` are one channel `9876543210`.
 * Channels then **fold** into groups, in this order of precedence:
 * 1. a legacy per-address alias (split/merge edit) — always a merge-group conversation;
 * 2. a user fold rule for the channel ([GroupingRules.folds]): folded into a group (`m:<groupKey>`), or unfolded
 *    (the channel stands alone, ignoring its brand);
 * 3. the brand table: channels whose headers map to one brand fold into that brand's key (`HDFC`, `HDFCBK` ->
 *    `m:HDFCBK` "HDFC Bank");
 * 4. otherwise alphanumeric senders get `m:<channel>` and everything else (people, groups, short codes) keeps its
 *    provider thread (`t:<threadId>`).
 *
 * All of this is display-layer only; the provider keeps its threads.
 */
object SenderGrouping {

    /** Prefix of merge keys created by "split sender out", so they never collide with computed keys. */
    const val SPLIT_PREFIX = "="

    /** Prefix of group keys created by a manual fold of senders the brand table does not know. */
    const val MANUAL_PREFIX = "+"

    /** Normalized form of a raw address used as the alias key. */
    fun aliasKey(address: String): String = address.trim().uppercase()

    /** Merge key for a single-sender split out of its group. */
    fun splitKey(address: String): String = SPLIT_PREFIX + aliasKey(address)

    /** Key of a new manual group seeded by [channel]. */
    fun manualKey(channel: String): String = MANUAL_PREFIX + channel

    /** The channel of an address (see the class docs). */
    fun channelOf(address: String): String = SenderId.mergeKey(address)

    /** Computed merge key (see `SenderId.mergeKey`), unless [aliases] (alias key -> merge key) overrides it. */
    fun mergeKey(address: String, aliases: Map<String, String>): String =
        aliases[aliasKey(address)] ?: SenderId.mergeKey(address)

    /** True for a single alphanumeric sender id (never for group MMS address lists or numbers). */
    fun isMergeable(address: String): Boolean {
        val trimmed = address.trim()
        if (trimmed.isEmpty() || isAddressList(trimmed)) return false
        return when (SenderId.classify(trimmed)) {
            SenderKind.DLT_HEADER, SenderKind.ALPHANUMERIC -> true
            SenderKind.SHORT_CODE, SenderKind.PHONE_NUMBER -> false
        }
    }

    /** True for a group-MMS recipient list, which can never be folded. */
    fun isAddressList(address: String): Boolean = address.trim().any { it.isWhitespace() || it == ',' || it == ';' }

    fun conversationId(address: String, threadId: Long, aliases: Map<String, String>): String =
        resolve(address, threadId, GroupingRules(aliases = aliases)).conversationId

    /** Merge key (stored on the row) and conversation id of [address] in provider thread [threadId]. */
    data class Grouping(val mergeKey: String, val conversationId: String)

    fun resolve(address: String, threadId: Long, rules: GroupingRules): Grouping {
        rules.aliases[aliasKey(address)]?.let { return Grouping(it, ConversationIds.forMergeGroup(it)) }
        val channel = channelOf(address)
        if (isAddressList(address)) return Grouping(channel, ConversationIds.forThread(threadId))
        val natural = if (isMergeable(address)) {
            Grouping(channel, ConversationIds.forMergeGroup(channel))
        } else {
            Grouping(channel, ConversationIds.forThread(threadId))
        }
        val fold = rules.folds[channel]
        if (fold != null) {
            return if (fold == channel) natural else Grouping(fold, ConversationIds.forMergeGroup(fold))
        }
        val brand = rules.brandKey(channel)
        if (brand != null && brand != channel) return Grouping(brand, ConversationIds.forMergeGroup(brand))
        if (brand != null && !isMergeable(address)) {
            // A numeric long code the bundle lists as a brand header of its own.
            return Grouping(brand, ConversationIds.forMergeGroup(brand))
        }
        return natural
    }
}
