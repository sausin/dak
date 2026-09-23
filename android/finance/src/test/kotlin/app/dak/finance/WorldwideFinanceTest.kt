package app.dak.finance

import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.Ledger
import app.dak.finance.ledger.LedgerInput
import app.dak.finance.money.CurrencyTable
import app.dak.finance.money.Money
import app.dak.finance.parser.InstitutionTable
import app.dak.finance.parser.TransactionParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** India-first, but nothing assumes India: "$" follows the region, home currency never defaults to INR blindly. */
class WorldwideFinanceTest {

    @Test
    fun `bare dollar follows the home currency`() {
        assertEquals("USD", CurrencyTable.symbolMapFor(null)["$"])
        assertEquals("USD", CurrencyTable.symbolMapFor("USD")["$"])
        assertEquals("CAD", CurrencyTable.symbolMapFor("cad")["$"])
        assertEquals("AUD", CurrencyTable.symbolMapFor("AUD")["$"])
        assertEquals("SGD", CurrencyTable.symbolMapFor("SGD")["$"])
        // No local dollar: USD; unambiguous symbols never change.
        assertEquals("USD", CurrencyTable.symbolMapFor("GBP")["$"])
        assertEquals("USD", CurrencyTable.symbolMapFor("INR")["$"])
        assertEquals("INR", CurrencyTable.symbolMapFor("CAD")["₹"])
        assertEquals("NPR", CurrencyTable.symbolMapFor("NPR")["₨"])
    }

    @Test
    fun `parser reads dollar amounts in the region's dollar`() {
        val body = "You spent \$42.10 at COFFEE CO with your card ending 1234."
        assertEquals("USD", TransactionParser.parse("12345", body)?.currency)
        assertEquals("CAD", TransactionParser.parse("12345", body, CurrencyTable.symbolMapFor("CAD"))?.currency)
        assertEquals("SGD", TransactionParser.parse("DBS", body, CurrencyTable.symbolMapFor("SGD"))?.currency)
        // An explicit code always wins.
        assertEquals("USD", TransactionParser.parse("12345", "You spent US\$42.10 at COFFEE CO", CurrencyTable.symbolMapFor("CAD"))?.currency)
    }

    private fun txn(currency: String, institution: String? = null, balanceCurrency: String? = null) = ExtractedTransaction(
        direction = TransactionDirection.DEBIT,
        amountMinor = 1000,
        currency = currency,
        last4 = "1234",
        institution = institution,
        balanceMinor = balanceCurrency?.let { 5000L },
        balanceCurrency = balanceCurrency,
    )

    @Test
    fun `home currency is never assumed to be INR`() {
        val inputs = listOf(
            LedgerInput("a", 1, txn("GBP", "Monzo")),
            LedgerInput("b", 2, txn("GBP", "Monzo")),
            LedgerInput("c", 3, txn("EUR", "Monzo")),
        )
        // Nothing known: the currency most transactions are in.
        assertEquals("GBP", Ledger.apply(inputs).single().account.homeCurrency)
        // Region default when given.
        assertEquals("CAD", Ledger.apply(inputs, defaultHomeCurrency = { "CAD" }).single().account.homeCurrency)
        // A stated balance currency always wins.
        val withBalance = inputs + LedgerInput("d", 4, txn("EUR", "Monzo", balanceCurrency = "EUR"))
        assertEquals("EUR", Ledger.apply(withBalance, defaultHomeCurrency = { "CAD" }).single().account.homeCurrency)
    }

    @Test
    fun `known Indian institutions are Indian wherever the user is`() {
        assertEquals("IN", InstitutionTable.countryOf("HDFC Bank"))
        assertEquals("IN", InstitutionTable.countryOf("state bank of india"))
        assertNull(InstitutionTable.countryOf("Monzo"))
        assertNull(InstitutionTable.countryOf(null))
    }

    @Test
    fun `lakh grouping only for rupees`() {
        assertEquals("₹1,23,456.78", Money(12345678, "INR").format())
        assertEquals("$123,456.78", Money(12345678, "USD").format())
        assertEquals("£1,234,567.00", Money(123456700, "GBP").format())
    }
}
