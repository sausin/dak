package app.dak.index.sync

import app.dak.core.model.Attachment
import app.dak.core.model.Category
import app.dak.core.model.Classification
import app.dak.core.model.ClassifierSource
import app.dak.core.model.DeliveryStatus
import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.InvestmentAction
import app.dak.core.model.Message
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import app.dak.core.model.OtpInfo
import app.dak.core.model.TransactionDirection
import app.dak.index.db.entity.IndexedMessage
import app.dak.index.db.entity.MessageFlag
import app.dak.index.enrich.Enrichment
import app.dak.index.enrich.GroupingRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [IndexRowMapper]: what an index row keeps of a provider message, and what a refresh may and may not touch. */
class IndexRowMapperTest {

    private val message = Message(
        providerId = 42,
        kind = MessageKind.SMS,
        threadId = 7,
        address = "VM-HDFCBK",
        body = "Rs.1,250.00 debited from a/c XX1234.\n\nAvl bal Rs 9,000. Details: https://hdfc.example/x",
        dateMillis = 1_000,
        subId = 2,
        box = MessageBox.INBOX,
        read = true,
        seen = true,
        attachments = listOf(Attachment("image/png", "content://mms/part/9", "a.png", 12)),
        deliveryStatus = DeliveryStatus.DELIVERED,
        deliveredAtMillis = 1_500,
    )

    private val debit = ExtractedTransaction(
        direction = TransactionDirection.DEBIT,
        amountMinor = 125_000,
        currency = "INR",
        last4 = "1234",
        merchant = "SWIGGY",
        institution = "HDFC",
    )

    private val classification = Classification(
        category = Category.TRANSACTION,
        confidence = 0.9f,
        source = ClassifierSource.TEMPLATE,
        otp = OtpInfo("123456", retrieverHash = "hash", webOtpDomain = "example.com"),
        canonicalSender = "HDFC Bank",
        labels = setOf("bank"),
    )

    private fun build(
        m: Message = message,
        txn: ExtractedTransaction? = debit,
        flag: MessageFlag? = null,
        repeatGroup: String? = null,
    ): IndexedMessage = IndexRowMapper.build(
        m, Enrichment(classification, txn), GroupingRules.NONE, flag, consumedBy = "com.bank.app", version = 9,
        nowMillis = 5_000, repeatGroup = repeatGroup,
    )

    @Test
    fun buildKeepsTheProviderFieldsAndTheEnrichment() {
        val row = build(repeatGroup = "sms:1")
        assertEquals(42L, row.providerId)
        assertEquals(MessageKind.SMS, row.kind)
        assertEquals(message.body, row.body, "the stored body is the raw body, never normalised")
        assertEquals("m:HDFCBK", row.conversationId)
        assertEquals("HDFCBK", row.mergeKey)
        assertEquals(Category.TRANSACTION, row.category)
        assertEquals("123456", row.otpCode)
        assertEquals("com.bank.app", row.otpConsumedBy)
        assertEquals("hash", row.retrieverHash)
        assertEquals("example.com", row.webOtpDomain)
        assertEquals(125_000L, row.amountMinor)
        assertEquals("INR", row.currency)
        assertEquals(TransactionDirection.DEBIT, row.direction)
        assertEquals("1234", row.instrumentLast4)
        assertNotNull(row.accountId)
        assertTrue(row.hasLink)
        assertTrue(row.hasAttachment)
        assertEquals(9, row.templateVersion)
        assertEquals(5_000L, row.indexedAt)
        assertEquals("sms:1", row.repeatGroup)
        assertEquals(DeliveryStatus.DELIVERED.code, row.deliveryStatus)
        assertEquals(1_500L, row.deliveredAtMillis)
        assertFalse(row.starred)
        assertFalse(row.archived)
        assertEquals(debit, IndexRowMapper.transaction(row))
    }

    @Test
    fun aValuationStatementPostsToTheLedgerButShowsNoAmount() {
        val valuation = debit.copy(investmentAction = InvestmentAction.VALUATION)
        val row = build(txn = valuation)
        assertNull(row.amountMinor)
        assertNull(row.currency)
        assertNull(row.direction)
        assertNull(row.merchant)
        // ...but the ledger still reads it for the account's value.
        assertNotNull(row.accountId)
        assertEquals(valuation, IndexRowMapper.transaction(row))
    }

    @Test
    fun noTransactionMeansNoMoneyFields() {
        val row = build(txn = null)
        assertNull(row.amountMinor)
        assertNull(row.accountId)
        assertNull(row.transactionJson)
        assertNull(IndexRowMapper.transaction(row))
    }

    @Test
    fun rowRoundTripsToTheProviderMessage() {
        // Restore and backup rebuild the message from the row: nothing the provider owns may be lost.
        assertEquals(message, IndexRowMapper.toMessage(build()))
        val plain = message.copy(attachments = emptyList(), deliveryStatus = DeliveryStatus.NONE, deliveredAtMillis = null, subId = -1)
        assertEquals(plain, IndexRowMapper.toMessage(build(m = plain)))
    }

    @Test
    fun refreshUpdatesOnlyProviderOwnedFields() {
        val row = build().copy(starred = true, archived = true, repeatGroup = "sms:1")
        val moved = message.copy(
            threadId = 8, box = MessageBox.SENT, read = false, seen = false, subId = 3, attachments = emptyList(),
            deliveryStatus = DeliveryStatus.FAILED, deliveredAtMillis = null, dateMillis = 2_000,
        )
        val refreshed = IndexRowMapper.refresh(row, moved, GroupingRules.NONE, nowMillis = 9_000)
        assertEquals(8L, refreshed.threadId)
        assertEquals(MessageBox.SENT, refreshed.box)
        assertFalse(refreshed.read)
        assertEquals(3, refreshed.subId)
        assertFalse(refreshed.hasAttachment)
        assertEquals(DeliveryStatus.FAILED.code, refreshed.deliveryStatus)
        assertNull(refreshed.deliveredAtMillis)
        assertEquals(9_000L, refreshed.indexedAt)
        // User state and enrichment survive.
        assertTrue(refreshed.starred)
        assertTrue(refreshed.archived)
        assertEquals("sms:1", refreshed.repeatGroup)
        assertEquals(row.category, refreshed.category)
        assertEquals(row.otpCode, refreshed.otpCode)
        assertEquals(row.transactionJson, refreshed.transactionJson)
        assertEquals(row.searchText, refreshed.searchText)
    }

    @Test
    fun onlyAnUnchangedMessageOfTheSameVersionIsRefreshed() {
        val row = build()
        assertTrue(IndexRowMapper.canRefresh(row, message, 9))
        assertFalse(IndexRowMapper.canRefresh(row, message, 10), "a new enricher version re-enriches")
        assertFalse(IndexRowMapper.canRefresh(row, message.copy(body = message.body + "!"), 9))
        assertFalse(IndexRowMapper.canRefresh(row, message.copy(address = "AX-HDFCBK"), 9))
        assertTrue(IndexRowMapper.canRefresh(row, message.copy(read = false, box = MessageBox.SENT), 9))
    }

    @Test
    fun userFlagsAreCarriedIntoANewRow() {
        val row = build(flag = MessageFlag(MessageKind.SMS, 42, starred = true, archived = true))
        assertTrue(row.starred)
        assertTrue(row.archived)
    }

    @Test
    fun previewCollapsesWhitespaceAndIsBounded() {
        assertEquals("a b c", IndexRowMapper.preview("  a\n\n b\t\tc  "))
        assertEquals("", IndexRowMapper.preview(""))
        val long = "x".repeat(IndexedMessage.PREVIEW_LENGTH + 50)
        assertEquals(IndexedMessage.PREVIEW_LENGTH, IndexRowMapper.preview(long).length)
    }

    @Test
    fun corruptAttachmentOrTransactionJsonReadsAsEmptyNotAsACrash() {
        assertEquals(emptyList(), IndexRowMapper.attachments(""))
        assertEquals(emptyList(), IndexRowMapper.attachments("{not json"))
        assertEquals(emptyList(), IndexRowMapper.attachments("[{\"uri\":1}]"))
        assertNull(IndexRowMapper.transaction(build().copy(transactionJson = "[]")))
        // Unknown fields written by a newer version are ignored rather than failing the read.
        assertEquals(
            listOf(Attachment("image/png", "u")),
            IndexRowMapper.attachments("[{\"mimeType\":\"image/png\",\"uri\":\"u\",\"future\":true}]"),
        )
    }

    @Test
    fun searchTextFindsAmountsHoweverTheyAreWritten() {
        // Two spellings of the same amount index the same amount tokens.
        fun tokens(body: String) = IndexRowMapper.searchText(body).split(' ').filter { it.startsWith("amt") }.toSet()
        assertTrue(tokens("Rs.1,250.00 debited").isNotEmpty())
        assertEquals(tokens("Rs.1,250.00 debited"), tokens("INR 1250 debited"))
        assertTrue(tokens("hello there").isEmpty())
        // The sender part carries both the raw address and the canonical name.
        val sender = IndexRowMapper.searchSender("VM-HDFCBK", "HDFC Bank")
        assertTrue(sender.contains("hdfcbk"), sender)
        assertTrue(sender.contains("bank"), sender)
    }
}
