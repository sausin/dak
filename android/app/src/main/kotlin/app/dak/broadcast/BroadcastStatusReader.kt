package app.dak.broadcast

import app.dak.automations.broadcast.BroadcastRecipient
import app.dak.automations.broadcast.BroadcastRecord
import app.dak.automations.broadcast.PhoneKey
import app.dak.automations.broadcast.RecipientStatus
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.index.enrich.ConversationIds
import app.dak.index.repo.ConversationRepository
import app.dak.index.repo.ScheduledSendStatus
import app.dak.index.repo.ScheduledSendStore
import app.dak.telephony.ProviderReader
import app.dak.ui.conversation.DeliveryTicks
import app.dak.ui.conversation.TickState
import javax.inject.Inject
import javax.inject.Singleton

/** Live state of one recipient's copy. */
enum class CopyState { SCHEDULED, SENDING, SENT, DELIVERED, FAILED, CANCELLED }

/** One recipient row of the broadcast detail view. */
data class RecipientView(
    val index: Int,
    val recipient: BroadcastRecipient,
    val state: CopyState,
    /** The tick glyph, when the copy reached the provider. */
    val tick: TickState?,
    val threadId: Long?,
    val failureReason: String?,
) {
    val canRetry: Boolean get() = state == CopyState.FAILED
}

/** A reply from a member, received after their copy went out. */
data class ReplyView(
    val address: String,
    val displayName: String,
    val body: String,
    val dateMillis: Long,
    val threadId: Long,
)

/** Everything the broadcast detail view shows. */
data class BroadcastDetails(
    val record: BroadcastRecord,
    val recipients: List<RecipientView>,
    val replies: List<ReplyView>,
) {
    fun count(state: CopyState): Int = recipients.count { it.state == state }
}

/**
 * Reads the live state of a broadcast on demand (only while its screen is open): each copy's box and delivery report
 * from its own 1:1 provider message (the same source as the conversation's ticks), the scheduled-send row for copies
 * that have not gone out, and replies from members since their copy (newest [REPLY_SCAN] messages of each thread).
 */
@Singleton
class BroadcastStatusReader @Inject constructor(
    private val provider: ProviderReader,
    private val sends: ScheduledSendStore,
    private val conversations: ConversationRepository,
) {

    suspend fun details(record: BroadcastRecord): BroadcastDetails {
        val views = record.recipients.mapIndexed { index, r -> view(index, r) }
        val replies = ArrayList<ReplyView>()
        val sentAt = HashMap<Long, Long>()
        for (v in views) {
            val thread = v.threadId ?: continue
            sentAt[thread] = minOf(sentAt[thread] ?: Long.MAX_VALUE, v.recipient.sendAtMillis)
        }
        for ((thread, since) in sentAt) {
            val messages = runCatching { provider.messagesInThread(thread, limit = REPLY_SCAN) }.getOrDefault(emptyList())
            for (m in messages) {
                if (m.box != MessageBox.INBOX || m.dateMillis < since) continue
                val member = record.recipients.firstOrNull { PhoneKey.same(it.address, m.address) }
                replies += ReplyView(
                    address = m.address,
                    displayName = member?.displayName?.takeIf { it.isNotBlank() } ?: m.address,
                    body = m.body,
                    dateMillis = m.dateMillis,
                    threadId = thread,
                )
            }
        }
        return BroadcastDetails(record, views, replies.sortedByDescending { it.dateMillis })
    }

    /**
     * A cheap view from the stored record alone (no provider reads, no replies), for older broadcasts in the list;
     * open one to get [details].
     */
    fun quick(record: BroadcastRecord): BroadcastDetails {
        val views = record.recipients.mapIndexed { index, r ->
            val state = when (r.status) {
                RecipientStatus.SCHEDULED -> CopyState.SCHEDULED
                RecipientStatus.SENT -> CopyState.SENT
                RecipientStatus.FAILED -> CopyState.FAILED
                RecipientStatus.CANCELLED -> CopyState.CANCELLED
            }
            val tick = when (state) {
                CopyState.SENT -> TickState.SENT
                CopyState.FAILED -> TickState.FAILED
                else -> null
            }
            RecipientView(index, r, state, tick, null, null)
        }
        return BroadcastDetails(record, views, emptyList())
    }

    /** The conversation id to open for [threadId] (follows sender folds). */
    suspend fun conversationIdFor(threadId: Long): String {
        val id = ConversationIds.forThread(threadId)
        return runCatching { conversations.resolveConversationId(id) }.getOrDefault(id)
    }

    private suspend fun view(index: Int, r: BroadcastRecipient): RecipientView {
        val key = r.messageKey?.let { MessageKey.parse(it) }
        if (key != null) {
            val message = runCatching { provider.message(key) }.getOrNull()
                ?: return RecipientView(index, r, CopyState.SENT, TickState.SENT, null, null)
            val tick = DeliveryTicks.stateOf(message.box, message.deliveryStatus)
            val state = when (tick) {
                TickState.SENDING -> CopyState.SENDING
                TickState.DELIVERED -> CopyState.DELIVERED
                TickState.FAILED -> CopyState.FAILED
                TickState.SENT, null -> CopyState.SENT
            }
            return RecipientView(index, r, state, tick, message.threadId, null)
        }
        if (r.status == RecipientStatus.CANCELLED) return RecipientView(index, r, CopyState.CANCELLED, null, null, null)
        val send = r.scheduledSendId?.let { runCatching { sends.get(it) }.getOrNull() }
        return when (send?.status) {
            ScheduledSendStatus.FAILED -> RecipientView(index, r, CopyState.FAILED, TickState.FAILED, null, send.failureReason)
            ScheduledSendStatus.CANCELLED -> RecipientView(index, r, CopyState.CANCELLED, null, null, send.failureReason)
            ScheduledSendStatus.SENT -> RecipientView(index, r, CopyState.SENDING, TickState.SENDING, null, null)
            ScheduledSendStatus.PENDING, null ->
                if (r.status == RecipientStatus.FAILED) {
                    RecipientView(index, r, CopyState.FAILED, TickState.FAILED, null, null)
                } else {
                    RecipientView(index, r, CopyState.SCHEDULED, null, null, null)
                }
        }
    }

    private companion object {
        const val REPLY_SCAN = 40
    }
}
