package app.dak.index.scam

import app.dak.classify.scam.AccountHint
import app.dak.classify.scam.RecentMessage
import app.dak.core.model.Message
import app.dak.core.model.MessageKey

/**
 * What the index enricher needs, beyond the message itself, to run `app.dak.classify.scam.FakeCreditDetector`:
 * the user's known accounts, recent messages for follow-up detection and "Not a scam" overrides.
 * [None] (the default) supplies nothing, so the detector still runs on the message alone.
 */
interface ScamContextSource {
    /** True when the user marked this message "Not a scam": it is never flagged again. */
    fun isDismissed(key: MessageKey): Boolean = false

    /** The user's genuine accounts (institution + visible digits). May be cached; may be empty. */
    suspend fun knownAccounts(): Set<AccountHint> = emptySet()

    /** Incoming messages of the last 48 h before [message]: same sender, plus already-flagged fake credits. */
    suspend fun recentMessages(message: Message): List<RecentMessage> = emptyList()

    /** Supplies nothing. */
    object None : ScamContextSource
}
