package app.dak.index.scam

import app.dak.classify.scam.AccountHint
import app.dak.classify.scam.RecentMessage
import app.dak.core.model.Message
import app.dak.core.model.MessageKey

/**
 * What the index enricher needs, beyond the message itself, to run `app.dak.classify.scam.FakeCreditDetector`:
 * the user's known accounts, recent messages for follow-up detection, "Not a scam" overrides and the feature switch.
 * [None] (the default) supplies nothing, so the detector still runs on the message alone.
 */
interface ScamContextSource {
    /** False when the user turned "Warn about fake credit alerts" off: no labels are then stored. */
    fun enabled(): Boolean = true

    /** True when the user marked this message "Not a scam": it is never flagged again. */
    fun isDismissed(key: MessageKey): Boolean = false

    /** The user's genuine accounts (institution + visible digits). May be cached; may be empty. */
    suspend fun knownAccounts(): Set<AccountHint> = emptySet()

    /** Incoming messages of the last 48 h before [message]: same sender, plus already-flagged fake credits. */
    suspend fun recentMessages(message: Message): List<RecentMessage> = emptyList()

    /** Supplies nothing. */
    object None : ScamContextSource
}
