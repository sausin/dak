package app.dak.finance.money

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rounding, grouping, sign, case and size edges of [Money] and [MoneyParser] that the happy-path tests do not reach. */
class MoneyEdgeCasesTest {

    // --- Money construction and arithmetic

    @Test
    fun `blank currency is rejected`() {
        assertFailsWith<IllegalArgumentException> { Money(1, "") }
        assertFailsWith<IllegalArgumentException> { Money(1, "   ") }
    }

    @Test
    fun `currency case is folded for arithmetic, comparison and formatting but equality is by raw fields`() {
        assertEquals(Money(300, "INR"), Money(100, "inr") + Money(200, "INR"))
        assertEquals(0, Money(100, "inr").compareTo(Money(100, "INR")))
        assertEquals("₹1.00", Money(100, "inr").format())
        assertEquals(Money(0, "USD"), Money.zero("usd"))
        // Data-class equality does not fold case: callers must build Money with upper-case codes (the parser does).
        assertTrue(Money(100, "inr") != Money(100, "INR"))
    }

    @Test
    fun `unary minus and abs keep the currency`() {
        assertEquals(Money(-500, "INR"), -Money(500, "INR"))
        assertEquals(Money(500, "INR"), Money(-500, "INR").abs())
        assertEquals(Money(500, "INR"), Money(500, "INR").abs())
        assertEquals(Money(0, "INR"), Money(0, "INR").abs())
    }

    @Test
    fun `comparison orders by amount within one currency`() {
        val sorted = listOf(Money(300, "INR"), Money(-5, "INR"), Money(100, "INR")).sorted()
        assertEquals(listOf(-5L, 100L, 300L), sorted.map { it.amountMinor })
    }

    @Test
    fun `ofMajor rounds half up to the currency's minor unit`() {
        assertEquals(Money(1, "INR"), Money.ofMajor(BigDecimal("0.005"), "INR"))
        assertEquals(Money(0, "INR"), Money.ofMajor(BigDecimal("0.0049"), "INR"))
        // HALF_UP rounds away from zero for negatives too.
        assertEquals(Money(-1, "INR"), Money.ofMajor(BigDecimal("-0.005"), "INR"))
        assertEquals(Money(2, "JPY"), Money.ofMajor(BigDecimal("1.5"), "JPY"))
        assertEquals(Money(1235, "KWD"), Money.ofMajor(BigDecimal("1.2345"), "kwd"))
    }

    @Test
    fun `ofMajor refuses values that do not fit a Long instead of wrapping`() {
        assertFailsWith<ArithmeticException> { Money.ofMajor(BigDecimal("1e30"), "INR") }
        // The largest representable INR amount round-trips exactly.
        val max = BigDecimal(Long.MAX_VALUE).movePointLeft(2)
        assertEquals(Money(Long.MAX_VALUE, "INR"), Money.ofMajor(max, "INR"))
        assertEquals(0, max.compareTo(Money(Long.MAX_VALUE, "INR").toBigDecimal()))
    }

    @Test
    fun `toBigDecimal uses the currency exponent`() {
        assertEquals(BigDecimal("12.34"), Money(1234, "INR").toBigDecimal())
        assertEquals(BigDecimal("1234"), Money(1234, "JPY").toBigDecimal())
        assertEquals(BigDecimal("1.234"), Money(1234, "BHD").toBigDecimal())
        assertEquals(BigDecimal("-0.05"), Money(-5, "USD").toBigDecimal())
    }

    @Test
    fun `convert rounds the converted amount half up`() {
        // $0.01 * 83.105 = Rs 0.83105 -> Rs 0.83
        assertEquals(Money(83, "INR"), Money.convert(Money(1, "USD"), "INR", BigDecimal("83.105")))
        // 1 JPY * 0.555 = Rs 0.555 -> Rs 0.56
        assertEquals(Money(56, "INR"), Money.convert(Money(1, "JPY"), "INR", BigDecimal("0.555")))
        // To a zero-decimal currency: Rs 100 * 1.795 = 179.5 JPY -> 180
        assertEquals(Money(180, "JPY"), Money.convert(Money(10000, "INR"), "jpy", BigDecimal("1.795")))
    }

    // --- Formatting

    @Test
    fun `indian grouping at every length boundary`() {
        val expected = listOf(
            0L to "₹0.00",
            5L to "₹0.05",
            99_900L to "₹999.00",
            100_000L to "₹1,000.00",
            9_999_900L to "₹99,999.00",
            10_000_000L to "₹1,00,000.00",
            99_999_900L to "₹9,99,999.00",
            100_000_000L to "₹10,00,000.00",
            1_000_000_000L to "₹1,00,00,000.00",
            123_456_789_012L to "₹1,23,45,67,890.12",
        )
        for ((minor, text) in expected) assertEquals(text, Money(minor, "INR").format(), "$minor")
    }

    @Test
    fun `western grouping at every length boundary`() {
        val expected = listOf(
            99_900L to "$999.00",
            100_000L to "$1,000.00",
            99_999_900L to "$999,999.00",
            100_000_000L to "$1,000,000.00",
            123_456_789_012L to "$1,234,567,890.12",
        )
        for ((minor, text) in expected) assertEquals(text, Money(minor, "USD").format(), "$minor")
    }

    @Test
    fun `format options and fallback symbols`() {
        assertEquals("1,23,456.78", Money(12_345_678L, "INR").format(withSymbol = false))
        assertEquals("₹123456.78", Money(12_345_678L, "INR").format(grouping = false))
        assertEquals("-12345.67", Money(-1_234_567L, "EUR").format(withSymbol = false, grouping = false))
        // No native symbol: the ISO code is the prefix.
        assertEquals("AED1,234.50", Money(123_450L, "AED").format())
        assertEquals("-KWD1.005", Money(-1_005L, "KWD").format())
        assertEquals("¥0", Money(0L, "JPY").format())
        assertEquals("≈-\$1.00", Money(-100, "USD").formatIndicative())
    }

    @Test
    fun `toString is the formatted amount`() {
        assertEquals("₹5,00,000.00", Money(50_000_000L, "INR").toString())
    }

    // --- Parser: sizes, signs and ambiguity

    @Test
    fun `an amount too large for a Long is skipped, not crashed on and not wrapped`() {
        assertNull(MoneyParser.parse("Rs 99999999999999999999 credited"))
        // ...and does not hide the next, sane amount.
        val all = MoneyParser.findAll("Rs 99999999999999999999 and Rs 500")
        assertEquals(listOf(Money(50_000, "INR")), all.map { it.money })
    }

    @Test
    fun `a minus sign is never part of the amount`() {
        // Banks write debits as words, not signs; the parser never produces a negative amount.
        assertEquals(Money(50_000, "INR"), MoneyParser.parse("-Rs 500"))
        assertEquals(Money(50_000, "INR"), MoneyParser.parse("debited -500 INR"))
        // A sign between the currency and the digits detaches the currency: no amount rather than a wrong one.
        assertNull(MoneyParser.parse("Rs -500 debited"))
    }

    @Test
    fun `zero amounts parse as zero`() {
        assertEquals(Money(0, "INR"), MoneyParser.parse("Rs 0.00 debited"))
    }

    @Test
    fun `sub-unit precision beyond the currency exponent rounds half up`() {
        assertEquals(Money(12_346, "INR"), MoneyParser.parse("INR 123.4550"))
        assertEquals(Money(12_345, "INR"), MoneyParser.parse("INR 123.4549"))
        // Exactly three digits after a lone point is European grouping for a two-decimal currency, not a decimal.
        assertEquals(Money(12_345_500, "INR"), MoneyParser.parse("INR 123.455"))
        assertEquals(Money(2, "JPY"), MoneyParser.parse("JPY 1.5"))
    }

    @Test
    fun `lakh and crore multipliers keep paise`() {
        assertEquals(Money(25_000_000L, "INR"), MoneyParser.parse("Rs 2.5 lakh"))
        assertEquals(Money(1_234_500_000L, "INR"), MoneyParser.parse("₹1.2345 crore"))
        assertEquals(Money(10_000_000_000L, "INR"), MoneyParser.parse("INR 10 crores"))
        // "lakhs" must be a whole word: "Rs 5 lakhpati" is not five lakh.
        assertEquals(Money(500L, "INR"), MoneyParser.parse("Rs 5 lakhpati"))
    }

    @Test
    fun `comma with three digits is grouping for two-decimal currencies but decimal for three-decimal ones`() {
        assertEquals(Money(123_400L, "USD"), MoneyParser.parse("USD 1,234"))
        assertEquals(Money(1_234L, "KWD"), MoneyParser.parse("KWD 1,234"))
        assertEquals(Money(1_234L, "JPY"), MoneyParser.parse("JPY 1,234"))
        assertEquals(Money(1_234L, "JPY"), MoneyParser.parse("JPY 1.234"))
    }

    @Test
    fun `one or two digits after a lone separator are decimals`() {
        assertEquals(Money(1_250L, "INR"), MoneyParser.parse("Rs 12,5"))
        assertEquals(Money(1_205L, "INR"), MoneyParser.parse("Rs 12,05"))
        assertEquals(Money(1_250L, "INR"), MoneyParser.parse("Rs 12.5"))
    }

    @Test
    fun `iso codes and symbols are case insensitive but never glued to letters`() {
        assertEquals(Money(50_000L, "INR"), MoneyParser.parse("inr 500"))
        assertEquals(Money(50_000L, "INR"), MoneyParser.parse("RS.500"))
        assertNull(MoneyParser.parse("LINR 500"))
        assertNull(MoneyParser.parse("PINR500"))
        // "USDT" (a crypto token) is not USD.
        assertNull(MoneyParser.parse("USDT 500"))
    }

    @Test
    fun `occurrence ranges point at the matched text`() {
        val body = "Paid ₹1,00,000.00 to X; fee Rs.18/- and GST INR 3.24."
        val all = MoneyParser.findAll(body)
        assertEquals(listOf(10_000_000L, 1_800L, 324L), all.map { it.money.amountMinor })
        for (o in all) assertEquals(o.rawText, body.substring(o.range))
        assertEquals("₹1,00,000.00", all[0].rawText)
        assertEquals("Rs.18/-", all[1].rawText)
    }

    @Test
    fun `symbolMapFor re-reads only ambiguous symbols`() {
        assertEquals("CAD", CurrencyTable.symbolMapFor("cad")["$"])
        assertEquals("NPR", CurrencyTable.symbolMapFor("NPR")["₨"])
        assertEquals("USD", CurrencyTable.symbolMapFor("INR")["$"])
        assertEquals("INR", CurrencyTable.symbolMapFor("CAD")["₹"])
        assertEquals(CurrencyTable.defaultSymbolToCurrency, CurrencyTable.symbolMapFor(null))
        assertEquals(CurrencyTable.defaultSymbolToCurrency, CurrencyTable.symbolMapFor("C"))
        assertEquals(Money(4_210, "CAD"), MoneyParser.parse("\$42.10", CurrencyTable.symbolMapFor(" cad ")))
    }

    @Test
    fun `minor unit scale matches the exponent`() {
        assertEquals(1L, CurrencyTable.minorUnitScale("JPY"))
        assertEquals(100L, CurrencyTable.minorUnitScale("inr"))
        assertEquals(1000L, CurrencyTable.minorUnitScale("OMR"))
        // Unknown codes use the ISO 4217 default of two decimals.
        assertEquals(2, CurrencyTable.minorUnitExponent("XYZ"))
        assertEquals("XYZ", CurrencyTable.symbolFor("xyz"))
    }

    @Test
    fun `non-latin digits in every Nd script normalise`() {
        // Bengali, Gujarati, Tamil, Telugu, Kannada, Malayalam, Gurmukhi and Odia "1,234.50".
        val scripts = listOf('০', '૦', '௦', '౦', '೦', '൦', '੦', '୦')
        for (zero in scripts) {
            val digits = "1,234.50".map { if (it.isDigit()) zero + (it - '0') else it }.joinToString("")
            assertEquals(Money(123_450L, "INR"), MoneyParser.parse("Rs $digits"), "script starting ${zero.code.toString(16)}")
        }
    }
}
