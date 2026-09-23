package app.dak.classify.entities

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntityExtractorTest {

    private fun extract(body: String, region: String? = "IN", hints: List<EntityHint> = emptyList(), otp: String? = null) =
        EntityExtractor.extract(body, region, hints, otp)

    private fun List<EntitySpan>.of(type: EntityType) = filter { it.type == type }

    @Test
    fun `otp is never a phone number`() {
        val spans = extract("482913 is your OTP for login to HDFC Bank NetBanking. Valid for 5 mins. Do not share with anyone.")
        assertEquals(listOf("482913"), spans.of(EntityType.OTP).map { it.value })
        assertTrue(spans.of(EntityType.PHONE).isEmpty())
    }

    @Test
    fun `known otp from the index is located`() {
        val body = "Use 7391 to verify your Swiggy account."
        val otp = extract(body, otp = "7391").single()
        assertEquals(EntityType.OTP, otp.type)
        assertEquals("7391", body.substring(otp.start, otp.end))
    }

    @Test
    fun `bank debit alert yields amount mask and reference but no phone`() {
        val body = "Rs.5,00,000.00 debited from A/c XX1234 on 23-09-26 to VPA rahul.s@okicici (UPI Ref No 426712345678). " +
            "Not you? Call 18002586161"
        val spans = extract(body)
        assertEquals(listOf("500000.00"), spans.of(EntityType.AMOUNT).map { it.value })
        assertEquals(listOf("1234"), spans.of(EntityType.MASKED_ACCOUNT).map { it.value })
        assertEquals(listOf("rahul.s@okicici"), spans.of(EntityType.UPI_ID).map { it.value })
        assertEquals(listOf("426712345678"), spans.of(EntityType.REFERENCE).map { it.value })
        // Only the helpline is a phone number: not the amount, the mask, the date or the reference.
        assertEquals(listOf("+9118002586161"), spans.of(EntityType.PHONE).map { it.value })
    }

    @Test
    fun `caller supplied amount hints replace the built in matcher`() {
        val body = "Paid USD 42.10 at AMAZON"
        val start = body.indexOf("USD")
        val spans = extract(body, hints = listOf(EntityHint(EntityType.AMOUNT, start, start + 9, "42.10")))
        val amount = spans.of(EntityType.AMOUNT).single()
        assertEquals("USD 42.10", amount.text)
        assertEquals("42.10", amount.value)
    }

    @Test
    fun `mobile numbers are phones in e164`() {
        val spans = extract("Hi, this is Priya. Call me on 98765 43210 or +91-99887-76655 tomorrow")
        assertEquals(listOf("+919876543210", "+919988776655"), spans.of(EntityType.PHONE).map { it.value })
    }

    @Test
    fun `unknown region only links plus prefixed numbers`() {
        val spans = extract("Call 9876543210 or +44 20 7946 0958", region = null)
        assertEquals(listOf("+442079460958"), spans.of(EntityType.PHONE).map { it.value })
    }

    @Test
    fun `generic region numbers`() {
        val spans = extract("Your appointment is confirmed. Questions? Call (415) 555-2671.", region = "US")
        assertEquals(listOf("+14155552671"), spans.of(EntityType.PHONE).map { it.value })
    }

    @Test
    fun `account and customer ids are not phones`() {
        val spans = extract("Customer ID 9876543210 and A/c no. 9123456789 updated. Ref no 9988776655.")
        assertTrue(spans.of(EntityType.PHONE).isEmpty(), spans.toString())
    }

    @Test
    fun `masks in many spellings`() {
        val body = "Card XXXX XXXX 5678 used; a/c **4321 debited; Account ending with 0042 credited; ac xx-9876"
        assertEquals(listOf("5678", "4321", "0042", "9876"), extract(body).of(EntityType.MASKED_ACCOUNT).map { it.value })
    }

    @Test
    fun `pnr and tracking with courier`() {
        val spans = extract(
            "PNR: 4512367890 Train 12951 CNF. Your parcel shipped via Blue Dart, AWB No. 77123456789. Track: https://bdt.in/x",
        )
        assertEquals(listOf("4512367890"), spans.of(EntityType.PNR).map { it.value })
        val tracking = spans.of(EntityType.TRACKING).single()
        assertEquals("77123456789", tracking.value)
        assertEquals("bluedart", tracking.courier)
        assertEquals(listOf("https://bdt.in/x"), spans.of(EntityType.URL).map { it.value })
        assertTrue(spans.of(EntityType.PHONE).isEmpty())
    }

    @Test
    fun `tracking without known courier`() {
        val t = extract("Your order has shipped. Tracking ID: FMPP1234567890").of(EntityType.TRACKING).single()
        assertEquals("FMPP1234567890", t.value)
        assertNull(t.courier)
    }

    @Test
    fun `order id with dashes is a reference`() {
        val spans = extract("Your Amazon order ID 402-1234567-7654321 has been delivered.")
        assertEquals(listOf("402-1234567-7654321"), spans.of(EntityType.REFERENCE).map { it.value })
        assertTrue(spans.of(EntityType.PHONE).isEmpty())
    }

    @Test
    fun `words after reference keywords are not references`() {
        val spans = extract("Order placed successfully. Refund initiated. Transaction successful.")
        assertTrue(spans.of(EntityType.REFERENCE).isEmpty(), spans.toString())
    }

    @Test
    fun `upi needs a known handle or upi context and email needs a dot`() {
        val spans = extract("Pay 9876543210@ybl or UPI ID: shop@newbank; mail care@hdfcbank.com; meet me@home")
        assertEquals(listOf("9876543210@ybl", "shop@newbank"), spans.of(EntityType.UPI_ID).map { it.value })
        assertEquals(listOf("care@hdfcbank.com"), spans.of(EntityType.EMAIL).map { it.value })
        assertTrue(spans.of(EntityType.PHONE).isEmpty())
    }

    @Test
    fun `digits inside urls are not phones`() {
        val spans = extract("Pay at https://pay.example.com/9876543210 now")
        assertEquals(listOf(EntityType.URL), spans.map { it.type })
    }

    @Test
    fun `dangerous schemes are not urls`() {
        assertTrue(extract("tap javascript:alert(1) or intent://x").of(EntityType.URL).isEmpty())
    }

    @Test
    fun `devanagari digits keep ranges in the original`() {
        val body = "कृपया ९८७६५४३२१० पर कॉल करें"
        val phone = extract(body).of(EntityType.PHONE).single()
        assertEquals("+919876543210", phone.value)
        assertEquals("९८७६५४३२१०", phone.text)
    }

    @Test
    fun `spans never overlap and are sorted`() {
        val body = "OTP 123456 for Rs 1,000 txn on card XX9876. Ref 998877665544. Call 9876543210. www.hdfcbank.com"
        val spans = extract(body)
        for (i in 1 until spans.size) assertTrue(spans[i - 1].end <= spans[i].start, spans.toString())
        assertEquals(
            listOf(EntityType.OTP, EntityType.AMOUNT, EntityType.MASKED_ACCOUNT, EntityType.REFERENCE, EntityType.PHONE, EntityType.URL),
            spans.map { it.type },
        )
    }

    @Test
    fun `input is bounded`() {
        val body = "x".repeat(EntityExtractor.MAX_CHARS) + " Call 9876543210"
        assertTrue(extract(body).isEmpty())
    }

    @Test
    fun `couriers are found by name`() {
        assertEquals("delhivery", Couriers.find("Shipped via Delhivery"))
        assertEquals("indiapost", Couriers.find("Booked with Speed Post"))
        assertNull(Couriers.find("Shipped via pigeon"))
    }
}
