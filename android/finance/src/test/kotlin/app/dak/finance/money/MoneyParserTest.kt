package app.dak.finance.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyParserTest {

    @Test
    fun `Rs dot with Indian grouping and decimal`() {
        assertEquals(Money(123450L, "INR"), MoneyParser.parse("Rs.1,234.50 debited"))
    }

    @Test
    fun `bare INR code with integer amount`() {
        assertEquals(Money(123400L, "INR"), MoneyParser.parse("INR 1234 credited"))
    }

    @Test
    fun `rupee symbol with full Indian grouping`() {
        assertEquals(Money(123456700L, "INR"), MoneyParser.parse("₹ 12,34,567 debited"))
    }

    @Test
    fun `Rs with trailing slash-dash suffix`() {
        assertEquals(Money(50000L, "INR"), MoneyParser.parse("withdrawn Rs 500/- at ATM"))
    }

    @Test
    fun `AED with decimal point`() {
        assertEquals(Money(12050L, "AED"), MoneyParser.parse("AED 120.50 spent"))
    }

    @Test
    fun `USD code with decimal`() {
        assertEquals(Money(4210L, "USD"), MoneyParser.parse("USD 42.10 spent"))
    }

    @Test
    fun `dollar symbol defaults to USD`() {
        assertEquals(Money(4210L, "USD"), MoneyParser.parse("\$42.10 charged"))
    }

    @Test
    fun `dollar symbol configurable to a different currency`() {
        val map = CurrencyTable.defaultSymbolToCurrency + ("\$" to "SGD")
        assertEquals(Money(4210L, "SGD"), MoneyParser.parse("\$42.10 charged", map))
    }

    @Test
    fun `euro symbol integer amount`() {
        assertEquals(Money(1000L, "EUR"), MoneyParser.parse("€10 paid"))
    }

    @Test
    fun `euro code with european decimal comma`() {
        assertEquals(Money(950L, "EUR"), MoneyParser.parse("EUR 9,50 spent"))
    }

    @Test
    fun `bare currency code with no amount yields null`() {
        assertNull(MoneyParser.parse("GBP"))
        assertNull(MoneyParser.parse("SGD"))
        assertNull(MoneyParser.parse("THB"))
    }

    @Test
    fun `plain number with no currency indicator is not money`() {
        assertNull(MoneyParser.parse("your OTP is 123456"))
    }

    @Test
    fun `thousands comma disambiguated from decimal comma by digit count`() {
        // "$1,234" - three digits after the comma for a 2-decimal currency => thousands separator.
        assertEquals(Money(123400L, "USD"), MoneyParser.parse("\$1,234 spent"))
    }

    @Test
    fun `findAll returns multiple occurrences with ranges`() {
        val body = "Rs.500 debited from A/c XX1234. Avl Bal Rs.12,345.67"
        val occurrences = MoneyParser.findAll(body)
        assertEquals(2, occurrences.size)
        assertEquals(Money(50000L, "INR"), occurrences[0].money)
        assertEquals(Money(1234567L, "INR"), occurrences[1].money)
    }
}
