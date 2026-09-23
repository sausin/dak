package app.dak.finance.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {

    @Test
    fun `minor unit exponents`() {
        assertEquals(2, CurrencyTable.minorUnitExponent("INR"))
        assertEquals(0, CurrencyTable.minorUnitExponent("JPY"))
        assertEquals(3, CurrencyTable.minorUnitExponent("KWD"))
        assertEquals(3, CurrencyTable.minorUnitExponent("BHD"))
        assertEquals(3, CurrencyTable.minorUnitExponent("OMR"))
        assertEquals(2, CurrencyTable.minorUnitExponent("USD"))
    }

    @Test
    fun `formats INR with Indian lakh crore grouping`() {
        assertEquals("₹1,23,456.78", Money(12345678L, "INR").format())
        assertEquals("₹12,34,567.00", Money(123456700L, "INR").format())
        assertEquals("₹1,00,00,000.00", Money(1000000000L, "INR").format())
        assertEquals("₹500.00", Money(50000L, "INR").format())
    }

    @Test
    fun `formats USD with western grouping`() {
        assertEquals("\$1,234.50", Money(123450L, "USD").format())
        assertEquals("\$42.10", Money(4210L, "USD").format())
    }

    @Test
    fun `formats zero-decimal currency with no fraction`() {
        assertEquals("¥1,500", Money(1500L, "JPY").format())
    }

    @Test
    fun `formats three-decimal currency`() {
        assertEquals("KWD12.345", Money(12345L, "KWD").format())
    }

    @Test
    fun `formats negative amounts`() {
        assertEquals("-₹500.00", Money(-50000L, "INR").format())
    }

    @Test
    fun `formatIndicative prefixes approx symbol`() {
        assertEquals("≈₹500.00", Money(50000L, "INR").formatIndicative())
    }

    @Test
    fun `arithmetic requires matching currency`() {
        val a = Money(100L, "INR")
        val b = Money(100L, "USD")
        assertFailsWith<IllegalArgumentException> { a + b }
        assertFailsWith<IllegalArgumentException> { a - b }
        assertFailsWith<IllegalArgumentException> { a.compareTo(b) }
    }

    @Test
    fun `plus and minus`() {
        assertEquals(Money(300L, "INR"), Money(100L, "INR") + Money(200L, "INR"))
        assertEquals(Money(-100L, "INR"), Money(100L, "INR") - Money(200L, "INR"))
    }

    @Test
    fun `ofMajor rounds to currency scale`() {
        assertEquals(Money(123450L, "USD"), Money.ofMajor(java.math.BigDecimal("1234.50"), "USD"))
        assertEquals(Money(1500L, "JPY"), Money.ofMajor(java.math.BigDecimal("1500"), "JPY"))
    }

    @Test
    fun `convert applies a rate`() {
        val amount = Money(10000L, "USD") // $100
        val converted = Money.convert(amount, "INR", java.math.BigDecimal("83.10"))
        assertEquals(Money(831000L, "INR"), converted) // Rs 8,310.00
    }
}
