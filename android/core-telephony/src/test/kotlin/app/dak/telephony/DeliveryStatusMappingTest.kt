package app.dak.telephony

import app.dak.core.model.DeliveryStatus
import app.dak.core.model.MessageBox
import app.dak.mms.pdu.MmsStatus
import app.dak.telephony.mms.MmsDeliveryReportCodec
import app.dak.telephony.provider.DeliveryStatusMapping
import app.dak.telephony.provider.SmsColumns
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeliveryStatusMappingTest {

    @Test
    fun smsProviderStatusConstants() {
        assertEquals(DeliveryStatus.NONE, DeliveryStatusMapping.sms(MessageBox.SENT, SmsColumns.STATUS_NONE))
        assertEquals(DeliveryStatus.DELIVERED, DeliveryStatusMapping.sms(MessageBox.SENT, SmsColumns.STATUS_COMPLETE))
        assertEquals(DeliveryStatus.PENDING, DeliveryStatusMapping.sms(MessageBox.SENT, SmsColumns.STATUS_PENDING))
        assertEquals(DeliveryStatus.FAILED, DeliveryStatusMapping.sms(MessageBox.SENT, SmsColumns.STATUS_FAILED))
        // Requested report while still in the outbox / queue / after a send failure.
        assertEquals(DeliveryStatus.PENDING, DeliveryStatusMapping.sms(MessageBox.OUTBOX, SmsColumns.STATUS_PENDING))
        assertEquals(DeliveryStatus.PENDING, DeliveryStatusMapping.sms(MessageBox.QUEUED, SmsColumns.STATUS_PENDING))
    }

    @Test
    fun smsRawTpStatusRanges() {
        // Apps that store the raw 3GPP TP-Status: 0x00-0x1F completed, 0x20-0x3F trying, 0x40+ failed.
        assertEquals(DeliveryStatus.DELIVERED, DeliveryStatusMapping.sms(MessageBox.SENT, 0x02))
        assertEquals(DeliveryStatus.PENDING, DeliveryStatusMapping.sms(MessageBox.SENT, 0x21))
        assertEquals(DeliveryStatus.FAILED, DeliveryStatusMapping.sms(MessageBox.SENT, 0x41))
        assertEquals(DeliveryStatus.FAILED, DeliveryStatusMapping.sms(MessageBox.SENT, 0x60))
    }

    @Test
    fun smsRaw3gpp2Status() {
        fun cdma(errorClass: Int, code: Int) = ((errorClass shl 8) or code) shl 16
        assertEquals(DeliveryStatus.DELIVERED, DeliveryStatusMapping.sms(MessageBox.SENT, cdma(0, 2)))
        assertEquals(DeliveryStatus.PENDING, DeliveryStatusMapping.sms(MessageBox.SENT, cdma(0, 0)))
        assertEquals(DeliveryStatus.PENDING, DeliveryStatusMapping.sms(MessageBox.SENT, cdma(2, 4)))
        assertEquals(DeliveryStatus.FAILED, DeliveryStatusMapping.sms(MessageBox.SENT, cdma(3, 4)))
    }

    @Test
    fun incomingIsNeverTicked() {
        assertEquals(DeliveryStatus.NONE, DeliveryStatusMapping.sms(MessageBox.INBOX, SmsColumns.STATUS_COMPLETE))
        assertEquals(DeliveryStatus.NONE, DeliveryStatusMapping.sms(MessageBox.DRAFT, SmsColumns.STATUS_PENDING))
        assertEquals(DeliveryStatus.NONE, DeliveryStatusMapping.mms(MessageBox.INBOX, MmsStatus.RETRIEVED, true))
    }

    @Test
    fun deliveredTimeOnlyWhenDelivered() {
        assertEquals(5_000L, DeliveryStatusMapping.smsDeliveredAt(DeliveryStatus.DELIVERED, 5_000L))
        assertNull(DeliveryStatusMapping.smsDeliveredAt(DeliveryStatus.DELIVERED, 0L))
        assertNull(DeliveryStatusMapping.smsDeliveredAt(DeliveryStatus.PENDING, 5_000L))
    }

    @Test
    fun mmsStatus() {
        assertEquals(DeliveryStatus.DELIVERED, DeliveryStatusMapping.mms(MessageBox.SENT, MmsStatus.RETRIEVED, true))
        assertEquals(DeliveryStatus.DELIVERED, DeliveryStatusMapping.mms(MessageBox.SENT, MmsStatus.FORWARDED, false))
        assertEquals(DeliveryStatus.FAILED, DeliveryStatusMapping.mms(MessageBox.SENT, MmsStatus.EXPIRED, true))
        assertEquals(DeliveryStatus.FAILED, DeliveryStatusMapping.mms(MessageBox.SENT, MmsStatus.REJECTED, true))
        assertEquals(DeliveryStatus.FAILED, DeliveryStatusMapping.mms(MessageBox.SENT, MmsStatus.UNREACHABLE, true))
        assertEquals(DeliveryStatus.FAILED, DeliveryStatusMapping.mms(MessageBox.SENT, MmsStatus.UNRECOGNISED, true))
        assertEquals(DeliveryStatus.PENDING, DeliveryStatusMapping.mms(MessageBox.SENT, MmsStatus.DEFERRED, true))
        assertEquals(DeliveryStatus.PENDING, DeliveryStatusMapping.mms(MessageBox.SENT, MmsStatus.INDETERMINATE, true))
        assertEquals(DeliveryStatus.PENDING, DeliveryStatusMapping.mms(MessageBox.SENT, 0, deliveryReportRequested = true))
        assertEquals(DeliveryStatus.NONE, DeliveryStatusMapping.mms(MessageBox.SENT, 0, deliveryReportRequested = false))
    }

    @Test
    fun groupMmsIsDeliveredOnlyWhenAllRecipientsAre() {
        val r = MmsStatus.RETRIEVED
        // Single recipient: kept as reported.
        assertEquals(r, DeliveryStatusMapping.aggregateMmsSt(1, mapOf("a" to r)))
        assertEquals(MmsStatus.EXPIRED, DeliveryStatusMapping.aggregateMmsSt(1, mapOf("a" to MmsStatus.EXPIRED)))
        // Group of three: pending until all three were delivered.
        assertEquals(MmsStatus.DEFERRED, DeliveryStatusMapping.aggregateMmsSt(3, mapOf("a" to r)))
        assertEquals(MmsStatus.DEFERRED, DeliveryStatusMapping.aggregateMmsSt(3, mapOf("a" to r, "b" to r)))
        assertEquals(r, DeliveryStatusMapping.aggregateMmsSt(3, mapOf("a" to r, "b" to r, "c" to r)))
        // One failure fails the message.
        assertEquals(
            MmsStatus.REJECTED,
            DeliveryStatusMapping.aggregateMmsSt(3, mapOf("a" to r, "b" to MmsStatus.REJECTED)),
        )
        // Unknown recipients share one slot, so a group never looks delivered from anonymous reports.
        assertEquals(MmsStatus.DEFERRED, DeliveryStatusMapping.aggregateMmsSt(2, mapOf("?" to r)))
        assertEquals(DeliveryStatus.DELIVERED, DeliveryStatusMapping.mms(MessageBox.SENT, DeliveryStatusMapping.aggregateMmsSt(2, mapOf("a" to r, "b" to r)), true))
    }

    @Test
    fun reportCodecRoundTrips() {
        val reports = mapOf("+15551234567" to MmsStatus.RETRIEVED, "a,b=c|d" to MmsStatus.DEFERRED)
        val decoded = MmsDeliveryReportCodec.decode(MmsDeliveryReportCodec.encode(42L, reports))!!
        assertEquals(42L, decoded.first)
        assertEquals(mapOf("+15551234567" to MmsStatus.RETRIEVED, "a_b_c_d" to MmsStatus.DEFERRED), decoded.second)
        assertNull(MmsDeliveryReportCodec.decode("garbage"))
        assertNull(MmsDeliveryReportCodec.decode(null))
    }
}
