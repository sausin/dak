package app.dak.telephony.provider

import app.dak.core.model.MessageBox
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RestoreBoxTest {

    /**
     * PoC: an SMS Backup & Restore XML row `<sms address="+1900…" body="YES" type="6" …/>` (type 6 = queued) used to be
     * restored as QUEUED, and boot recovery re-enqueues QUEUED rows, so importing a tampered backup made the phone text
     * that number at the next reboot with no confirmation. Restored outbox/queued messages are now failed ones.
     */
    @Test
    fun restoredMessagesNeverLandInTheSendQueue() {
        assertEquals(MessageBox.FAILED, BoxMapping.restoredBox(MessageBox.QUEUED))
        assertEquals(MessageBox.FAILED, BoxMapping.restoredBox(MessageBox.OUTBOX))
        for (box in listOf(MessageBox.INBOX, MessageBox.SENT, MessageBox.DRAFT, MessageBox.FAILED)) {
            assertEquals(box, BoxMapping.restoredBox(box))
        }
        // The provider type written for a restored queued SMS is FAILED (5), which boot recovery ignores.
        assertEquals(5, BoxMapping.boxToSmsType(BoxMapping.restoredBox(MessageBox.fromProviderType(6))))
    }

    @Test
    fun noBoxAndNoProviderTypeRestoresIntoTheSendQueue() {
        // Exhaustive, so a box added later cannot silently become sendable on restore.
        val sendable = setOf(MessageBox.OUTBOX, MessageBox.QUEUED)
        for (box in MessageBox.entries) assertFalse(BoxMapping.restoredBox(box) in sendable, "$box")
        // Every provider type a tampered file can carry, including unknown ones, lands in a non-sending box.
        for (type in -1..20) {
            val restored = BoxMapping.restoredBox(BoxMapping.smsTypeToBox(type))
            assertFalse(restored in sendable, "type $type")
            assertFalse(BoxMapping.boxToSmsType(restored) in setOf(4, 6), "type $type")
        }
        // An MMS msg_box read from a file goes the same way (outbox 4 -> failed).
        assertEquals(MmsColumns.BOX_FAILED, BoxMapping.boxToMmsBox(BoxMapping.restoredBox(BoxMapping.mmsBoxToBox(MmsColumns.BOX_OUTBOX))))
    }

    @Test
    fun smsAndMmsBoxesRoundTrip() {
        for (box in MessageBox.entries) assertEquals(box, BoxMapping.smsTypeToBox(BoxMapping.boxToSmsType(box)))
        for (box in MessageBox.entries - MessageBox.QUEUED) assertEquals(box, BoxMapping.mmsBoxToBox(BoxMapping.boxToMmsBox(box)))
        // MMS has no queued box: queued maps to the outbox. Unknown boxes read as inbox (never as sendable).
        assertEquals(MmsColumns.BOX_OUTBOX, BoxMapping.boxToMmsBox(MessageBox.QUEUED))
        assertEquals(MessageBox.INBOX, BoxMapping.mmsBoxToBox(0))
        assertEquals(MessageBox.INBOX, BoxMapping.mmsBoxToBox(99))
        assertEquals(MessageBox.INBOX, BoxMapping.smsTypeToBox(0))
    }
}
