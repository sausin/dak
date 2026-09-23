package app.dak.ui.conversation

import app.dak.core.model.DeliveryStatus
import app.dak.core.model.MessageBox

/** What the status glyph of an outgoing message shows (WhatsApp-style ticks). */
enum class TickState {
    /** Clock: in the outbox / queued for a retry or a rate-limit slot. */
    SENDING,

    /** Single check: sent; no delivery report yet (or reports are off / unsupported). */
    SENT,

    /** Double check: the delivery report arrived (group MMS: from every recipient). */
    DELIVERED,

    /** Red error: the send failed, or the network reported the message undeliverable. Tap to retry. */
    FAILED,
}

/** Pure mapping from a message's box and delivery report to its [TickState]. */
object DeliveryTicks {
    /** The glyph for an outgoing message, or null for incoming messages and drafts. */
    fun stateOf(box: MessageBox, delivery: DeliveryStatus): TickState? = when (box) {
        MessageBox.OUTBOX, MessageBox.QUEUED -> TickState.SENDING
        MessageBox.FAILED -> TickState.FAILED
        MessageBox.SENT -> when (delivery) {
            DeliveryStatus.DELIVERED -> TickState.DELIVERED
            DeliveryStatus.FAILED -> TickState.FAILED
            DeliveryStatus.PENDING, DeliveryStatus.NONE -> TickState.SENT
        }
        MessageBox.INBOX, MessageBox.DRAFT -> null
    }
}
