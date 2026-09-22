package app.dak.finance.rates

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RatesTableTest {

    @Test
    fun `rate to base currency is one`() {
        val table = RatesLoader.loadBundled()
        assertEquals(BigDecimal.ONE, table.rate("USD", "USD"))
    }

    @Test
    fun `rate from base currency matches the table entry`() {
        val table = RatesTable(base = "USD", date = "2026-09-21", rates = mapOf("INR" to "83.10"))
        assertEquals(0, BigDecimal("83.10").compareTo(table.rate("USD", "INR")))
    }

    @Test
    fun `rate between two non-base currencies triangulates through base`() {
        val table = RatesTable(base = "USD", date = "2026-09-21", rates = mapOf("INR" to "83.00", "AED" to "3.67"))
        // 1 AED = (83.00 / 3.67) INR
        val expected = BigDecimal("83.00").divide(BigDecimal("3.67"), 6, RoundingMode.HALF_UP)
        val actual = table.rate("AED", "INR")!!.setScale(6, RoundingMode.HALF_UP)
        assertEquals(expected, actual)
    }

    @Test
    fun `unknown currency yields no rate`() {
        val table = RatesTable(base = "USD", date = "2026-09-21", rates = mapOf("INR" to "83.10"))
        assertNull(table.rate("USD", "ZZZ"))
    }

    @Test
    fun `bundled sample loads and covers common currencies`() {
        val table = RatesLoader.loadBundled()
        assertEquals("USD", table.base)
        assert(table.rate("USD", "INR") != null)
        assert(table.rate("AED", "INR") != null)
    }
}
