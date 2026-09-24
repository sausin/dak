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

    @Test
    fun `devanagari digits are normalised before parsing`() {
        // "Rs.१,२३४.५०" - Devanagari digits, same amount as "Rs.1,234.50".
        assertEquals(Money(123450L, "INR"), MoneyParser.parse("Rs.१,२३४.५० debited"))
    }

    @Test
    fun `arabic-indic digits are normalised before parsing`() {
        // Arabic-Indic (٠-٩) and Extended Arabic-Indic (۰-۹) digits.
        assertEquals(Money(50000L, "AED"), MoneyParser.parse("AED ٥٠٠.٠٠ spent"))
        assertEquals(Money(12000L, "SAR"), MoneyParser.parse("SAR ۱۲۰ spent"))
    }

    @Test
    fun `full-width digits are normalised before parsing`() {
        // JPY has zero decimal digits, so "１５００" (full-width "1500") is 1500 yen exactly.
        assertEquals(Money(1500L, "JPY"), MoneyParser.parse("¥１５００ spent"))
    }

    @Test
    fun `rupee symbol with narrow nbsp gap and indian grouping`() {
        assertEquals(Money(123456700L, "INR"), MoneyParser.parse("₹ 12,34,567 debited"))
    }

    @Test
    fun `indian grouping with nbsp thousands separator instead of comma`() {
        assertEquals(Money(123456700L, "INR"), MoneyParser.parse("₹ 12 34 567 debited"))
    }

    @Test
    fun `swiss apostrophe thousands separator`() {
        assertEquals(Money(123450L, "CHF"), MoneyParser.parse("CHF 1'234.50 charged"))
    }

    @Test
    fun `european grouping with dot thousands and comma decimal`() {
        assertEquals(Money(123456L, "EUR"), MoneyParser.parse("EUR 1.234,56 paid"))
    }

    @Test
    fun `bangladeshi taka symbol and word`() {
        assertEquals(Money(50000L, "BDT"), MoneyParser.parse("৳500 debited"))
        assertEquals(Money(120000L, "BDT"), MoneyParser.parse("Tk 1200 paid"))
    }

    @Test
    fun `ambiguous rupee sign defaults to pakistani rupee`() {
        assertEquals(Money(150000L, "PKR"), MoneyParser.parse("₨1,500 withdrawn"))
    }

    @Test
    fun `sri lankan rupee symbol`() {
        assertEquals(Money(250000L, "LKR"), MoneyParser.parse("රු2,500 spent"))
    }

    @Test
    fun `gulf currency symbols`() {
        assertEquals(Money(10000L, "AED"), MoneyParser.parse("د.إ 100 spent"))
        assertEquals(Money(20000L, "SAR"), MoneyParser.parse("﷼ 200 spent"))
    }

    @Test
    fun `three-decimal dinar-family currency`() {
        assertEquals(Money(1234500L, "BHD"), MoneyParser.parse("BHD 1234.500 spent"))
        assertEquals(Money(1500000L, "KWD"), MoneyParser.parse("KWD 1500.000 spent"))
    }

    @Test
    fun `east and southeast asian currency symbols`() {
        assertEquals(Money(100000L, "KRW"), MoneyParser.parse("₩100000 spent"))
        assertEquals(Money(50000L, "THB"), MoneyParser.parse("฿500 spent"))
        assertEquals(Money(10000L, "MYR"), MoneyParser.parse("RM100 spent"))
        assertEquals(Money(5000000L, "IDR"), MoneyParser.parse("Rp50000 spent"))
        assertEquals(Money(20000L, "PHP"), MoneyParser.parse("₱200 spent"))
        assertEquals(Money(1000L, "VND"), MoneyParser.parse("₫1000 spent")) // VND has zero decimals
        assertEquals(Money(10000L, "TRY"), MoneyParser.parse("₺100 spent"))
    }

    @Test
    fun `dollar variants disambiguated by their own symbol`() {
        assertEquals(Money(10000L, "CAD"), MoneyParser.parse("C\$100 charged"))
        assertEquals(Money(10000L, "AUD"), MoneyParser.parse("A\$100 charged"))
        assertEquals(Money(10000L, "SGD"), MoneyParser.parse("S\$100 charged"))
        assertEquals(Money(10000L, "HKD"), MoneyParser.parse("HK\$100 charged"))
        assertEquals(Money(10000L, "USD"), MoneyParser.parse("US\$100 charged"))
    }

    @Test
    fun `chinese yuan symbol and rmb word`() {
        assertEquals(Money(10000L, "CNY"), MoneyParser.parse("元100 spent"))
        assertEquals(Money(20000L, "CNY"), MoneyParser.parse("RMB 200 spent"))
    }

    @Test
    fun `a colon may separate the currency from the amount`() {
        assertEquals(Money(50000L, "INR"), MoneyParser.parse("debited for Rs:500.00 on 12-09"))
        assertEquals(Money(421000L, "INR"), MoneyParser.parse("Avl Bal Rs: 4,210.00"))
    }

    @Test
    fun `a suffix currency belongs to the next number, not to a reference, year or account tail`() {
        assertEquals(listOf(Money(50000L, "INR")), MoneyParser.findAll("UPI Ref 624812345678 INR 500.00 credited").map { it.money })
        assertEquals(listOf(Money(75000L, "INR")), MoneyParser.findAll("debited on 12-09-2026 INR 750.00").map { it.money })
        assertEquals(listOf(Money(50000L, "INR")), MoneyParser.findAll("Spent Card no. XX4321 INR 500 12-09-26").map { it.money })
        // A suffix currency with nothing after it still attaches.
        assertEquals(Money(50000L, "INR"), MoneyParser.parse("paid 500 INR."))
    }

    @Test
    fun `a number glued to a letter or mask is never an amount`() {
        assertNull(MoneyParser.parse("Card XX1234 INR"))
        assertNull(MoneyParser.parse("A/c *1234 INR"))
    }
}
