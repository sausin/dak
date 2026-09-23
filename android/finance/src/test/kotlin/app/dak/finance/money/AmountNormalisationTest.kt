package app.dak.finance.money

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every way an Indian bank (or a person) writes five lakh rupees must parse to the same amount. */
class AmountNormalisationTest {

    private val fiveLakh = Money(50_000_000L, "INR")

    @Test
    fun `all spellings of five lakh rupees parse to the same money`() {
        val forms = listOf(
            "Rs 500,000. 00",
            "Rs 500,000 .00",
            "Rs 500,000 . 00",
            "Rs 5,00,000.00",
            "Rs 500,000.00",
            "Rs 500000.00",
            "Rs 500000",
            "Rs.5,00,000/-",
            "Rs. 5,00,000/-",
            "INR 500000",
            "INR 5,00,000.00",
            "₹5,00,000",
            "₹ 5,00,000.00",
            "500000 INR",
            "Rs 5 lakh",
            "Rs 5 Lakhs",
            "Rs 5 lac",
            "₹0.05 crore",
        )
        for (form in forms) assertEquals(fiveLakh, MoneyParser.parse(form), form)
    }

    @Test
    fun `spaced decimal keeps paise`() {
        assertEquals(Money(123_456L, "INR"), MoneyParser.parse("Rs 1,234. 56 debited"))
        assertEquals(Money(123_456L, "INR"), MoneyParser.parse("Rs 1,234 .56 debited"))
    }

    @Test
    fun `spaced decimal is not glued onto an amount that already has decimals`() {
        val all = MoneyParser.findAll("Rs 500.00. 12 items shipped")
        assertEquals(Money(50_000L, "INR"), all.single().money)
    }

    @Test
    fun `Cr after an amount means credited not crore`() {
        assertEquals(Money(50_000L, "INR"), MoneyParser.parse("INR 500.00 Cr to A/c XX1234"))
    }

    @Test
    fun `findAllForSearch returns currency and bare formatted amounts`() {
        val body = "Rs.5,00,000/- credited. Earlier 1,50,000.00 and 2500.50 pending. OTP 482913, call 9876543210 on 12.03.2024"
        val mentions = MoneyParser.findAllForSearch(body)
        assertEquals(listOf(50_000_000L, 15_000_000L, 250_050L), mentions.map { it.hundredths })
        assertEquals(listOf("INR", null, null), mentions.map { it.currency })
        assertEquals("Rs.5,00,000/-", body.substring(mentions[0].range))
        assertEquals("1,50,000.00", body.substring(mentions[1].range))
    }

    @Test
    fun `findAllForSearch ignores plain digit runs dates and ip-like numbers`() {
        val mentions = MoneyParser.findAllForSearch("Code 123456, phone 98765 43210, date 12.03.2024, host 192.168.1.1, year 2026")
        assertTrue(mentions.isEmpty(), mentions.toString())
    }

    @Test
    fun `findAllForSearch accepts spaced decimal in a bare amount`() {
        val m = MoneyParser.findAllForSearch("Total 500,000. 00 only").single()
        assertEquals(50_000_000L, m.hundredths)
        assertNull(m.currency)
    }

    @Test
    fun `hundredths is currency independent`() {
        assertEquals(50_000L, AmountMention(BigDecimal("500"), "JPY", 0..0).hundredths)
        assertEquals(123L, AmountMention(BigDecimal("1.234"), "KWD", 0..0).hundredths)
    }

    @Test
    fun `findAllForSearch stays fast on hostile input`() {
        val hostile = "1,".repeat(20_000) + "Rs " + "9".repeat(5_000) + " .".repeat(5_000)
        val start = System.nanoTime()
        MoneyParser.findAllForSearch(hostile)
        MoneyParser.findAll(hostile.take(AmountMentions.MAX_SCAN_CHARS))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 1_000, "took ${ms}ms")
    }
}
