package app.dak.index.enrich

import app.dak.core.model.Category
import app.dak.core.model.Message
import app.dak.core.model.MessageKind
import app.dak.core.model.TransactionDirection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnrichmentHelpersTest {

    @Test
    fun conversationIdsRoundTrip() {
        assertEquals("t:42", ConversationIds.forThread(42))
        assertEquals(42L, ConversationIds.threadIdOf("t:42"))
        assertNull(ConversationIds.threadIdOf("m:HDFCBK"))
        assertEquals("HDFCBK", ConversationIds.mergeKeyOf("m:HDFCBK"))
        assertNull(ConversationIds.mergeKeyOf("m:"))
        assertTrue(ConversationIds.isMergeGroup("m:X"))
    }

    @Test
    fun dltHeadersFromDifferentPrefixesShareOneConversation() {
        val a = SenderGrouping.conversationId("VM-HDFCBK", 1, emptyMap())
        val b = SenderGrouping.conversationId("JD-HDFCBK", 2, emptyMap())
        val c = SenderGrouping.conversationId("AX-HDFCBK-S", 3, emptyMap())
        assertEquals("m:HDFCBK", a)
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun peopleAndShortCodesKeepTheirThread() {
        assertEquals("t:7", SenderGrouping.conversationId("+919876543210", 7, emptyMap()))
        assertEquals("t:8", SenderGrouping.conversationId("56070", 8, emptyMap()))
        assertEquals("t:9", SenderGrouping.conversationId("+911111111111 +912222222222", 9, emptyMap()))
        assertFalse(SenderGrouping.isMergeable(""))
    }

    @Test
    fun aliasOverridesGrouping() {
        val split = mapOf(SenderGrouping.aliasKey("vk-amazon-p") to SenderGrouping.splitKey("VK-AMAZON-P"))
        assertEquals("m:=VK-AMAZON-P", SenderGrouping.conversationId("VK-AMAZON-P", 1, split))
        assertEquals("m:AMAZON", SenderGrouping.conversationId("AX-AMAZON", 2, split))
        // A merged phone number joins the target group.
        val merged = mapOf("+919876543210" to "HDFCBK")
        assertEquals("m:HDFCBK", SenderGrouping.conversationId("+919876543210", 3, merged))
    }

    @Test
    fun linkDetection() {
        assertTrue(LinkDetector.containsLink("Track at https://amzn.in/d/abc"))
        assertTrue(LinkDetector.containsLink("visit www.example.org now"))
        assertTrue(LinkDetector.containsLink("go to bit.ly/xyz"))
        assertFalse(LinkDetector.containsLink("Rs.500.00 debited from a/c XX1234"))
        assertFalse(LinkDetector.containsLink("see you at 5.30"))
    }

    @Test
    fun transactionParsingGate() {
        assertTrue(DefaultMessageEnricher.shouldParseTransaction("VM-HDFCBK", Category.TRANSACTION))
        assertTrue(DefaultMessageEnricher.shouldParseTransaction("VM-HDFCBK", Category.UNKNOWN))
        assertFalse(DefaultMessageEnricher.shouldParseTransaction("+919876543210", Category.UNKNOWN))
        assertFalse(DefaultMessageEnricher.shouldParseTransaction("VM-HDFCBK", Category.OTP))
    }

    @Test
    fun cloudStageNeverSeesPeople() {
        assertTrue(DefaultMessageEnricher.cloudEligibleSender("VM-HDFCBK-T"))
        assertTrue(DefaultMessageEnricher.cloudEligibleSender("56070"))
        assertTrue(DefaultMessageEnricher.cloudEligibleSender("AMAZON"))
        assertFalse(DefaultMessageEnricher.cloudEligibleSender("+919876543210"))
        assertFalse(DefaultMessageEnricher.cloudEligibleSender("09876543210"))
    }

    @Test
    fun defaultEnricherClassifiesWithBundledTemplatesAndParsesTransactions() = runTest {
        val enricher = DefaultMessageEnricher(isContact = { false })
        assertTrue(enricher.version > 0)
        val debit = Message(
            providerId = 1,
            kind = MessageKind.SMS,
            threadId = 1,
            address = "VM-HDFCBK",
            body = "Rs.1,250.00 debited from a/c XX1234 on 01-09-26 to VPA swiggy@icici. Avl Bal Rs.10,000.00",
            dateMillis = 0,
        )
        val e = enricher.enrich(debit, allowCloud = false)
        if (e.classification.category == Category.TRANSACTION || e.classification.category == Category.UNKNOWN) {
            val txn = assertNotNull(e.transaction)
            assertEquals(TransactionDirection.DEBIT, txn.direction)
            assertEquals(125000L, txn.amountMinor)
            assertEquals("INR", txn.currency)
        }
    }
}
