package app.dak.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Golden tests for everything in core-model that is persisted or crosses a process boundary (index rows, backups,
 * the transaction JSON column, WorkManager inputs). Enum names are stored, so a rename or removal must fail here;
 * the order is pinned too because [Category] order is the tab order and [InstrumentType]/[InvestmentAction] promise
 * append-only ordinals. If one of these fails, you are changing a persisted format: add a migration, don't edit
 * the golden.
 */
class PersistedFormatTest {

    private val json = Json

    @Test
    fun enumNamesAndOrderArePinned() {
        assertEquals(listOf("PERSONAL", "TRANSACTION", "OTP", "PROMOTION", "SPAM", "UNKNOWN"), Category.entries.map { it.name })
        assertEquals(listOf("TEMPLATE", "MODEL", "CLOUD", "USER", "NONE"), ClassifierSource.entries.map { it.name })
        assertEquals(listOf("NONE", "PENDING", "DELIVERED", "FAILED"), DeliveryStatus.entries.map { it.name })
        assertEquals(listOf("INBOX", "SENT", "DRAFT", "OUTBOX", "FAILED", "QUEUED"), MessageBox.entries.map { it.name })
        assertEquals(listOf("SMS", "MMS"), MessageKind.entries.map { it.name })
        assertEquals(listOf("DEBIT", "CREDIT"), TransactionDirection.entries.map { it.name })
        assertEquals(
            listOf(
                "BANK_ACCOUNT", "CREDIT_CARD", "WALLET", "UPI", "UNKNOWN", "DEBIT_CARD", "PREPAID_CARD", "LOAN",
                "MUTUAL_FUND", "DEMAT",
            ),
            InstrumentType.entries.map { it.name },
        )
        assertEquals(
            listOf("PURCHASE", "REDEMPTION", "SWITCH", "DIVIDEND", "BUY", "SELL", "VALUATION"),
            InvestmentAction.entries.map { it.name },
        )
    }

    @Test
    fun enumsSerializeByName() {
        assertEquals("\"TRANSACTION\"", json.encodeToString(Category.serializer(), Category.TRANSACTION))
        assertEquals("\"DEMAT\"", json.encodeToString(InstrumentType.serializer(), InstrumentType.DEMAT))
        assertEquals("\"DELIVERED\"", json.encodeToString(DeliveryStatus.serializer(), DeliveryStatus.DELIVERED))
        assertEquals(MessageBox.QUEUED, json.decodeFromString(MessageBox.serializer(), "\"QUEUED\""))
        // An unknown name is a hard error with the default Json, which is why names are append-only.
        assertFailsWith<SerializationException> { json.decodeFromString(Category.serializer(), "\"BILLS\"") }
    }

    @Test
    fun providerTypeMappingIsPinned() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6), MessageBox.entries.map { it.providerType })
        for (b in MessageBox.entries) assertEquals(b, MessageBox.fromProviderType(b.providerType))
        // Unknown provider types (0 = MESSAGE_TYPE_ALL, vendor extensions) read as INBOX.
        assertEquals(MessageBox.INBOX, MessageBox.fromProviderType(0))
        assertEquals(MessageBox.INBOX, MessageBox.fromProviderType(99))
        assertEquals(MessageBox.INBOX, MessageBox.fromProviderType(-1))
    }

    @Test
    fun deliveryStatusAggregateCoversEveryPair() {
        // Exhaustive over all pairs: FAILED wins, all-DELIVERED is DELIVERED, any report → PENDING, else NONE.
        for (a in DeliveryStatus.entries) for (b in DeliveryStatus.entries) {
            val expected = when {
                a == DeliveryStatus.DELIVERED && b == DeliveryStatus.DELIVERED -> DeliveryStatus.DELIVERED
                a == DeliveryStatus.FAILED || b == DeliveryStatus.FAILED -> DeliveryStatus.FAILED
                a == DeliveryStatus.NONE && b == DeliveryStatus.NONE -> DeliveryStatus.NONE
                else -> DeliveryStatus.PENDING
            }
            assertEquals(expected, DeliveryStatus.aggregate(listOf(a, b)), "$a + $b")
            assertEquals(expected, DeliveryStatus.aggregate(setOf(a, b)), "set $a + $b")
        }
    }

    @Test
    fun messageKeyStringFormIsStable() {
        assertEquals("sms:42", MessageKey(MessageKind.SMS, 42).toString())
        assertEquals("mms:7", MessageKey(MessageKind.MMS, 7).toString())
        assertEquals("sms:-1", MessageKey(MessageKind.SMS, -1).toString())
        for (k in listOf(MessageKey(MessageKind.SMS, 0), MessageKey(MessageKind.MMS, Long.MAX_VALUE), MessageKey(MessageKind.SMS, -5))) {
            assertEquals(k, MessageKey.parse(k.toString()))
        }
        assertEquals(MessageKey(MessageKind.MMS, 3), MessageKey.parse("MMS:3"))
        for (bad in listOf("", "sms", "sms:", ":1", "rcs:1", "sms:1.5", "sms:abc", "sms:99999999999999999999", "sms 1")) {
            assertNull(MessageKey.parse(bad), bad)
        }
        // limit = 2: a second colon belongs to the id, which then fails to parse rather than being truncated.
        assertNull(MessageKey.parse("sms:1:2"))
    }

    @Test
    fun messageKeyDistinguishesOverlappingProviderIds() {
        val sms = Message(9, MessageKind.SMS, 1, "+1", "a", 0)
        val mms = Message(9, MessageKind.MMS, 1, "+1", "a", 0)
        assertEquals(MessageKey(MessageKind.SMS, 9), sms.key)
        assertEquals(false, sms.key == mms.key)
    }

    @Test
    fun messageGoldenJson() {
        val m = Message(
            providerId = 5, kind = MessageKind.MMS, threadId = 2, address = "+15551234567 +15557654321", body = "hi",
            dateMillis = 1_700_000_000_000, subId = 3, box = MessageBox.SENT, read = true, seen = true,
            attachments = listOf(Attachment("image/jpeg", "content://mms/part/1", "a.jpg", 1024)),
            deliveryStatus = DeliveryStatus.PENDING, deliveredAtMillis = 1_700_000_001_000,
        )
        val golden = """{"providerId":5,"kind":"MMS","threadId":2,"address":"+15551234567 +15557654321","body":"hi",""" +
            """"dateMillis":1700000000000,"subId":3,"box":"SENT","read":true,"seen":true,""" +
            """"attachments":[{"mimeType":"image/jpeg","uri":"content://mms/part/1","name":"a.jpg","sizeBytes":1024}],""" +
            """"deliveryStatus":"PENDING","deliveredAtMillis":1700000001000}"""
        assertGolden(Message.serializer(), m, golden)
        // Defaults are omitted, so a minimal message stays minimal on disk.
        assertGolden(
            Message.serializer(),
            Message(1, MessageKind.SMS, 1, "x", "", 0),
            """{"providerId":1,"kind":"SMS","threadId":1,"address":"x","body":"","dateMillis":0}""",
        )
        val minimal = json.decodeFromString(Message.serializer(), """{"providerId":1,"kind":"SMS","threadId":1,"address":"x","body":"","dateMillis":0}""")
        assertEquals(NO_SUB_ID, minimal.subId)
        assertEquals(MessageBox.INBOX, minimal.box)
        assertEquals(emptyList(), minimal.attachments)
    }

    @Test
    fun classificationGoldenJson() {
        val c = Classification(
            category = Category.OTP, confidence = 0.75f, source = ClassifierSource.TEMPLATE,
            otp = OtpInfo("123456", retrieverHash = "FA+9qCX9VSu", webOtpDomain = "example.com"),
            canonicalSender = "HDFC Bank", labels = setOf("bank", "otp"),
        )
        assertGolden(
            Classification.serializer(), c,
            """{"category":"OTP","confidence":0.75,"source":"TEMPLATE","otp":{"code":"123456","retrieverHash":"FA+9qCX9VSu",""" +
                """"webOtpDomain":"example.com"},"canonicalSender":"HDFC Bank","labels":["bank","otp"]}""",
        )
        assertGolden(Classification.serializer(), Classification.Unclassified, """{"category":"UNKNOWN","confidence":0.0,"source":"NONE"}""")
    }

    @Test
    fun transactionGoldenJson() {
        val t = ExtractedTransaction(
            direction = TransactionDirection.DEBIT, amountMinor = 123_456, currency = "INR",
            instrument = InstrumentType.MUTUAL_FUND, last4 = "1234", merchant = "Axis MF", reference = "REF1",
            balanceMinor = 99, balanceCurrency = "INR", institution = "Axis", maskedNumber = "XXXX1234",
            linkedMaskedNumber = "XX5678", investmentAction = InvestmentAction.PURCHASE, units = "45.678",
            unitPrice = "27.03", unitsHeld = "1234.567", isin = "INF846K01EW2", ownTransfer = true,
        )
        assertGolden(
            ExtractedTransaction.serializer(), t,
            """{"direction":"DEBIT","amountMinor":123456,"currency":"INR","instrument":"MUTUAL_FUND","last4":"1234",""" +
                """"merchant":"Axis MF","reference":"REF1","balanceMinor":99,"balanceCurrency":"INR","institution":"Axis",""" +
                """"maskedNumber":"XXXX1234","linkedMaskedNumber":"XX5678","investmentAction":"PURCHASE","units":"45.678",""" +
                """"unitPrice":"27.03","unitsHeld":"1234.567","isin":"INF846K01EW2","ownTransfer":true}""",
        )
        // A row written before the investment fields existed still decodes with safe defaults.
        val legacy = json.decodeFromString(
            ExtractedTransaction.serializer(),
            """{"direction":"CREDIT","amountMinor":5000,"currency":"AED"}""",
        )
        assertEquals(InstrumentType.UNKNOWN, legacy.instrument)
        assertNull(legacy.investmentAction)
        assertEquals(false, legacy.ownTransfer)
    }

    @Test
    fun simInfoGoldenJson() {
        assertGolden(
            SimInfo.serializer(),
            SimInfo(subId = 2, slotIndex = 1, displayName = "Work", carrierName = "Jio", countryIso = "in", colorArgb = -16711936, number = "+91", isEmbedded = true, isActive = false),
            """{"subId":2,"slotIndex":1,"displayName":"Work","carrierName":"Jio","countryIso":"in","colorArgb":-16711936,""" +
                """"number":"+91","isEmbedded":true,"isActive":false}""",
        )
        val min = json.decodeFromString(SimInfo.serializer(), """{"subId":1,"slotIndex":0,"displayName":"SIM 1"}""")
        assertEquals(true, min.isActive)
        assertEquals(false, min.isEmbedded)
    }

    private fun <T> assertGolden(serializer: KSerializer<T>, value: T, golden: String) {
        assertEquals(golden, json.encodeToString(serializer, value))
        assertEquals(value, json.decodeFromString(serializer, golden))
    }
}
