package app.dak.index.enrich

/** One conversation as the fold suggester sees it. */
data class FoldCandidate(
    val conversationId: String,
    val title: String,
    /** Sender channels of the conversation (see [SenderGrouping.channelOf]). */
    val channels: Set<String>,
    /** Brand from the template bundle, if the classifier knew the sender. */
    val canonicalSender: String?,
    val lastSeenMillis: Long,
)

/** Why conversations are suggested for folding. */
enum class FoldSuggestionReason {
    /** The classifier names the same brand for all of them. */
    SAME_BRAND,

    /** Their alphanumeric sender headers share a stem (`AMAZON`, `AMAZONIN`, `AMAZONPAY`). */
    SIMILAR_HEADER,
}

/** "These 3 senders look like Amazon — fold?" */
data class FoldProposal(
    /** Display name for the folded conversation. */
    val title: String,
    /** Conversations to fold, most recently active first (the first one is the fold target). */
    val conversationIds: List<String>,
    val reason: FoldSuggestionReason,
) {
    /** Order-independent identity, for "not now" bookkeeping. */
    val key: String get() = conversationIds.sorted().joinToString("|")
}

/**
 * Suggests conversations the user may want to fold together. It never folds anything itself. Channels the user
 * already made a decision about (folded or unfolded by hand) are left out, so an unfold is never undone by a
 * suggestion. Only alphanumeric sender headers take part in stem matching; numbers are never guessed.
 */
object FoldSuggester {

    /** Leading characters two headers must share to count as similar. */
    const val STEM_LENGTH = 5

    fun suggest(candidates: List<FoldCandidate>, decidedChannels: Set<String>): List<FoldProposal> {
        val open = candidates.filter { c -> c.channels.isNotEmpty() && c.channels.none { it in decidedChannels } }
        val used = HashSet<String>()
        val out = ArrayList<FoldProposal>()

        open.filter { !it.canonicalSender.isNullOrBlank() }
            .groupBy { it.canonicalSender!!.trim().lowercase() }
            .values
            .filter { group -> group.size >= 2 }
            .forEach { group ->
                val sorted = group.sortedByDescending { it.lastSeenMillis }
                out += FoldProposal(sorted.first().canonicalSender!!.trim(), sorted.map { it.conversationId }, FoldSuggestionReason.SAME_BRAND)
                sorted.mapTo(used) { it.conversationId }
            }

        open.filter { it.conversationId !in used }
            .mapNotNull { c -> stemOf(c)?.let { it to c } }
            .groupBy({ it.first }, { it.second })
            .values
            .filter { group -> group.map { it.conversationId }.distinct().size >= 2 }
            .forEach { group ->
                val sorted = group.distinctBy { it.conversationId }.sortedByDescending { it.lastSeenMillis }
                val title = sorted.firstOrNull { !it.canonicalSender.isNullOrBlank() }?.canonicalSender ?: sorted.first().title
                out += FoldProposal(title, sorted.map { it.conversationId }, FoldSuggestionReason.SIMILAR_HEADER)
            }
        return out
    }

    /** Stem of a conversation made of alphanumeric headers only (all sharing it), else null. */
    private fun stemOf(candidate: FoldCandidate): String? {
        val stems = candidate.channels.map { channel ->
            if (channel.length < STEM_LENGTH || channel.none { it.isLetter() } || !channel.all { it.isLetterOrDigit() }) return null
            channel.take(STEM_LENGTH).uppercase()
        }.distinct()
        return stems.singleOrNull()
    }
}
