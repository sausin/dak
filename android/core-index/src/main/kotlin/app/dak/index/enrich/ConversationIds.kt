package app.dak.index.enrich

/**
 * Conversation ids are display-layer grouping keys:
 * - `t:<threadId>` for ordinary provider threads (people, groups, short codes);
 * - `m:<mergeKey>` for a sender merge group (alphanumeric senders such as `VM-HDFCBK` / `JD-HDFCBK`, which the
 *   provider keeps as separate threads but the UI shows as one "HDFC Bank" conversation).
 */
object ConversationIds {
    private const val THREAD_PREFIX = "t:"
    private const val MERGE_PREFIX = "m:"

    fun forThread(threadId: Long): String = THREAD_PREFIX + threadId

    fun forMergeGroup(mergeKey: String): String = MERGE_PREFIX + mergeKey

    /** The provider thread id for a `t:` id, else null. */
    fun threadIdOf(conversationId: String): Long? =
        if (conversationId.startsWith(THREAD_PREFIX)) conversationId.substring(THREAD_PREFIX.length).toLongOrNull() else null

    /** The merge key for an `m:` id, else null. */
    fun mergeKeyOf(conversationId: String): String? =
        if (conversationId.startsWith(MERGE_PREFIX)) conversationId.substring(MERGE_PREFIX.length).takeIf { it.isNotEmpty() } else null

    fun isMergeGroup(conversationId: String): Boolean = conversationId.startsWith(MERGE_PREFIX)
}
