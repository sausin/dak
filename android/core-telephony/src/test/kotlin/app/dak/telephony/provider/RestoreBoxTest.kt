package app.dak.telephony.provider

import app.dak.core.model.MessageBox
import org.junit.Test
import kotlin.test.assertEquals

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
}
