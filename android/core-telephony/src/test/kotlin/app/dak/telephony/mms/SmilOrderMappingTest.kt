package app.dak.telephony.mms

import app.dak.mms.pdu.ReadStatus
import app.dak.telephony.provider.DeliveryStatusMapping
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Receive-side SMIL ordering of stored parts, and read reports implying delivery. */
class SmilOrderMappingTest {

    private val smil = "<smil><head><layout><root-layout/></layout></head><body>" +
        "<par dur=\"5s\"><img src=\"cid:img2\"/><text src=\"t2.txt\"/></par>" +
        "<par dur=\"5s\"><img src=\"one.jpg\"/><text src=\"t1.txt\"/></par>" +
        "</body></smil>"

    private fun parts(smilText: String?) = listOf(
        StoredPart(1, "application/smil", smilText, null, null, "smil.xml", contentId = "<smil>"),
        StoredPart(2, "image/jpeg", null, null, null, "one.jpg"),
        StoredPart(3, "text/plain", "first caption", null, null, "t1.txt"),
        StoredPart(4, "image/jpeg", null, null, null, "two.jpg", contentId = "<img2>"),
        StoredPart(5, "text/plain", "second caption", null, null, "t2.txt"),
        StoredPart(6, "text/x-vcard", null, null, "card.vcf", null),
    )

    @Test
    fun attachmentsAndTextFollowTheSmilSlides() {
        val (body, attachments) = MmsProviderMapping.bodyAndAttachments(parts(smil))
        assertEquals("second caption\nfirst caption", body)
        // Slide 1 image, slide 2 image, then the unreferenced vCard in part order.
        assertEquals(
            listOf("content://mms/part/4", "content://mms/part/2", "content://mms/part/6"),
            attachments.map { it.uri },
        )
    }

    @Test
    fun missingOrBrokenSmilKeepsPartOrder() {
        for (s in listOf(null, "", "<smil><body><par><img src=\"two.jpg\"/>", "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///x\">]><smil/>")) {
            val (body, attachments) = MmsProviderMapping.bodyAndAttachments(parts(s))
            assertEquals("first caption\nsecond caption", body, "smil=$s")
            assertEquals(listOf("content://mms/part/2", "content://mms/part/4", "content://mms/part/6"), attachments.map { it.uri })
        }
    }

    @Test
    fun readReportsImplyDelivery() {
        assertTrue(DeliveryStatusMapping.readReportImpliesDelivery(ReadStatus.READ))
        assertTrue(DeliveryStatusMapping.readReportImpliesDelivery(ReadStatus.DELETED_WITHOUT_BEING_READ))
        assertFalse(DeliveryStatusMapping.readReportImpliesDelivery(0x99))
    }
}
