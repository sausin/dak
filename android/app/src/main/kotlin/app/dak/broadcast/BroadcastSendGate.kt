package app.dak.broadcast

import app.dak.automations.broadcast.BroadcastTag
import app.dak.automations.broadcast.RecipientStatus
import app.dak.core.model.MessageKey
import app.dak.index.repo.ScheduledSend
import app.dak.index.repo.ScheduledSendStatus
import app.dak.index.repo.ScheduledSendStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The scheduled-send executor's hook for broadcast copies (sends whose `ruleId` is a [BroadcastTag]): a copy whose
 * broadcast was deleted or cancelled is dropped, and the outcome of each copy (the provider message key, or the
 * failure) is written back to its [app.dak.automations.broadcast.BroadcastRecord]. Other sends pass straight through.
 */
@Singleton
class BroadcastSendGate @Inject constructor(
    private val store: BroadcastStore,
    private val sends: ScheduledSendStore,
) {

    /** True when the executor should send [send]; false when the copy's broadcast is gone or cancelled. */
    suspend fun beforeSend(send: ScheduledSend): Boolean {
        val tag = BroadcastTag.decode(send.ruleId) ?: return true
        val recipient = store.record(tag.broadcastId)?.recipients?.getOrNull(tag.recipientIndex)
        if (recipient == null || recipient.status == RecipientStatus.CANCELLED) {
            // No reason: the copy's state ("Cancelled") says it all, in the app language.
            sends.markStatus(send.id, ScheduledSendStatus.CANCELLED)
            return false
        }
        return true
    }

    /** Records the provider key of a copy that was handed to the platform. */
    fun afterSent(send: ScheduledSend, keys: List<MessageKey>) {
        val tag = BroadcastTag.decode(send.ruleId) ?: return
        store.updateRecipient(tag.broadcastId, tag.recipientIndex) {
            it.copy(status = RecipientStatus.SENT, messageKey = keys.firstOrNull()?.toString() ?: it.messageKey)
        }
    }

    /** Records a copy that could not be handed to the platform. */
    fun afterFailed(send: ScheduledSend) {
        val tag = BroadcastTag.decode(send.ruleId) ?: return
        store.updateRecipient(tag.broadcastId, tag.recipientIndex) { it.copy(status = RecipientStatus.FAILED) }
    }

    internal companion object {
        /** The English reason older versions stored with a dropped copy; hidden when shown (see BroadcastStatusReader). */
        const val LEGACY_REASON_REMOVED = "broadcast cancelled"
    }
}
