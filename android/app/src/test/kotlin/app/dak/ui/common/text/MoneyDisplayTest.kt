package app.dak.ui.common.text

import app.dak.finance.money.Money
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class MoneyDisplayTest {
    private val enIn = Locale.forLanguageTag("en-IN")
    private val enUs = Locale.US

    @Test
    fun `indian grouping for INR and for viewers in India`() {
        assertEquals("₹1,23,456.78", MoneyDisplay.format(Money(12_345_678, "INR"), enIn))
        assertEquals("₹1,00,00,000.00", MoneyDisplay.format(Money(1_000_000_000, "INR"), enIn))
        // A foreign currency is still grouped the Indian way for a viewer in India, with its ISO code.
        assertEquals("USD 12,34,567.00", MoneyDisplay.format(Money(123_456_700, "USD"), enIn))
        // INR keeps lakh/crore grouping for a viewer elsewhere.
        assertEquals("INR 1,23,456.78", MoneyDisplay.format(Money(12_345_678, "INR"), enUs))
    }

    @Test
    fun `western grouping, symbols, minor units and markers`() {
        assertEquals("$1,234.50", MoneyDisplay.format(Money(123_450, "USD"), enUs))
        assertEquals("-$5.00", MoneyDisplay.format(Money(-500, "USD"), enUs))
        assertEquals("≈₹999.00", MoneyDisplay.formatIndicative(Money(99_900, "INR"), enIn))
        assertEquals("JPY 1,500", MoneyDisplay.format(Money(1_500, "JPY"), enUs))
        assertEquals("KWD 1.250", MoneyDisplay.format(Money(1_250, "KWD"), enUs))
        assertEquals("€1.234,50", MoneyDisplay.format(Money(123_450, "EUR"), Locale.GERMANY, homeCurrency = "EUR"))
    }

    @Test
    fun `digits follow the locale's numbering system, separators and all`() {
        // Arabic (Egypt) uses Arabic-Indic digits: every digit is localized, not only the separators.
        val arEg = Locale.forLanguageTag("ar-EG")
        val text = MoneyDisplay.format(Money(123_450, "EGP"), arEg, homeCurrency = "EGP")
        assertEquals("", text.filter { it in '0'..'9' }, text)
        assertEquals("١٢٣٤٥٠".toSet(), text.filter { Character.isDigit(it) }.toSet(), text)
        // Lakh grouping survives native digits (Hindi with Devanagari digits requested explicitly).
        val hiDeva = Locale.forLanguageTag("hi-IN-u-nu-deva")
        assertEquals("₹१,२३,४५६.७८", MoneyDisplay.format(Money(12_345_678, "INR"), hiDeva))
        // An explicit Latin numbering system keeps ASCII digits.
        val arLatn = Locale.forLanguageTag("ar-EG-u-nu-latn")
        assertEquals("123450".toSet(), MoneyDisplay.format(Money(123_450, "EGP"), arLatn, homeCurrency = "EGP").filter { Character.isDigit(it) }.toSet())
    }
}
