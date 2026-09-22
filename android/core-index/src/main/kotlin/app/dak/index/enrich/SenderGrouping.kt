package app.dak.index.enrich

import app.dak.classify.SenderId
import app.dak.classify.SenderKind

/**
 * Decides the merge key and conversation id of a message. Alphanumeric senders (DLT headers such as `VM-HDFCBK`,
 * other text sender ids) collapse into one `m:<mergeKey>` conversation per real-world sender; everything else
 * (people, groups, short codes) keeps its provider thread (`t:<threadId>`). A user alias (split / merge edit)
 * overrides the computed key and always yields a merge-group conversation.
 */
object SenderGrouping {

    /** Prefix of merge keys created by "split sender out", so they never collide with computed keys. */
    const val SPLIT_PREFIX = "="

    /** Normalized form of a raw address used as the alias key. */
    fun aliasKey(address: String): String = address.trim().uppercase()

    /** Merge key for a single-sender split out of its group. */
    fun splitKey(address: String): String = SPLIT_PREFIX + aliasKey(address)

    /** Computed merge key (see `SenderId.mergeKey`), unless [aliases] (alias key -> merge key) overrides it. */
    fun mergeKey(address: String, aliases: Map<String, String>): String =
        aliases[aliasKey(address)] ?: SenderId.mergeKey(address)

    /** True for a single alphanumeric sender id (never for group MMS address lists or numbers). */
    fun isMergeable(address: String): Boolean {
        val trimmed = address.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it == ',' || it == ';' }) return false
        return when (SenderId.classify(trimmed)) {
            SenderKind.DLT_HEADER, SenderKind.ALPHANUMERIC -> true
            SenderKind.SHORT_CODE, SenderKind.PHONE_NUMBER -> false
        }
    }

    fun conversationId(address: String, threadId: Long, aliases: Map<String, String>): String {
        val aliased = aliases.containsKey(aliasKey(address))
        return if (aliased || isMergeable(address)) {
            ConversationIds.forMergeGroup(mergeKey(address, aliases))
        } else {
            ConversationIds.forThread(threadId)
        }
    }
}
