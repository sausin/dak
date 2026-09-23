package app.dak.finance.passbook

import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.InstrumentType
import app.dak.core.model.InvestmentAction
import app.dak.core.model.TransactionDirection
import app.dak.finance.ledger.Account
import app.dak.finance.ledger.AccountLedger
import app.dak.finance.ledger.AccountType
import app.dak.finance.ledger.BalanceState
import app.dak.finance.ledger.Ledger
import app.dak.finance.ledger.LedgerEntry
import app.dak.finance.ledger.LedgerInput
import app.dak.finance.money.Money
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spending / income / transfer arithmetic of the Passbook: what is summed, per currency, and what never is. */
class PassbookMathTest {

    private fun millis(y: Int, m: Int, d: Int): Long = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val bank = Account("HDFC_BANK:BANK_ACCOUNT:1234", "HDFC Bank", InstrumentType.BANK_ACCOUNT, "1234", "INR")

    private fun entry(
        key: String,
        date: Long,
        direction: TransactionDirection,
        money: Money,
        home: Money? = money,
        transfer: Boolean = false,
        action: InvestmentAction? = null,
        merchant: String? = null,
    ) = LedgerEntry(key, date, direction, money, home, transfer = transfer, investmentAction = action, merchant = merchant)

    @Test
    fun `transfers are totalled apart from spending and income`() {
        val d = millis(2026, 9, 10)
        val ledger = AccountLedger(
            bank,
            listOf(
                entry("spend", d, TransactionDirection.DEBIT, Money(1_000_00, "INR"), merchant = "SHOP"),
                entry("sip", d, TransactionDirection.DEBIT, Money(5_000_00, "INR"), transfer = true),
                entry("salary", d, TransactionDirection.CREDIT, Money(50_000_00, "INR")),
                entry("redemption", d, TransactionDirection.CREDIT, Money(2_000_00, "INR"), transfer = true),
            ),
        )
        val month = Passbook.monthlyTotals(ledger).single()
        assertEquals(Money(1_000_00, "INR"), month.debitsHome)
        assertEquals(Money(50_000_00, "INR"), month.creditsHome)
        assertEquals(Money(5_000_00, "INR"), month.transfersOutHome)
        assertEquals(Money(2_000_00, "INR"), month.transfersInHome)
        assertEquals(mapOf("INR" to Money(1_000_00, "INR")), month.debitsByCurrency)
        assertEquals(mapOf("INR" to Money(50_000_00, "INR")), month.creditsByCurrency)

        assertEquals(mapOf("SHOP" to Money(1_000_00, "INR")), Passbook.spendByMerchant(ledger))
        assertEquals(listOf(Money(1_000_00, "INR")), AccountGroups.spentSince(ledger.entries, 0))
    }

    @Test
    fun `valuations and switches move no money`() {
        val d = millis(2026, 9, 10)
        val fund = bank.copy(id = "F:MUTUAL_FUND:1234", instrument = InstrumentType.MUTUAL_FUND)
        val ledger = AccountLedger(
            fund,
            listOf(
                entry("buy", d, TransactionDirection.CREDIT, Money(5_000_00, "INR"), transfer = true, action = InvestmentAction.PURCHASE),
                entry("value", d, TransactionDirection.CREDIT, Money(99_999_00, "INR"), transfer = true, action = InvestmentAction.VALUATION),
                entry("switch", d, TransactionDirection.DEBIT, Money(1_000_00, "INR"), transfer = true, action = InvestmentAction.SWITCH),
                entry("dividend", d, TransactionDirection.CREDIT, Money(120_00, "INR"), action = InvestmentAction.DIVIDEND),
            ),
        )
        val month = Passbook.monthlyTotals(ledger).single()
        assertEquals(Money(5_000_00, "INR"), month.transfersInHome)
        assertEquals(Money(0, "INR"), month.transfersOutHome)
        assertEquals(Money(120_00, "INR"), month.creditsHome) // a dividend is income
        assertEquals(Money(0, "INR"), month.debitsHome)
        assertTrue(Passbook.spendByMerchant(ledger).isEmpty())
    }

    @Test
    fun `a foreign entry without a home value is kept per currency but never guessed into the home total`() {
        val d = millis(2026, 9, 10)
        val ledger = AccountLedger(
            bank,
            listOf(
                entry("aed", d, TransactionDirection.DEBIT, Money(120_50, "AED"), home = null),
                entry("usd", d, TransactionDirection.DEBIT, Money(10_00, "USD"), home = Money(831_00, "INR")),
                entry("inr", d, TransactionDirection.DEBIT, Money(500_00, "INR")),
            ),
        )
        val month = Passbook.monthlyTotals(ledger).single()
        assertEquals(
            mapOf("AED" to Money(120_50, "AED"), "USD" to Money(10_00, "USD"), "INR" to Money(500_00, "INR")),
            month.debitsByCurrency,
        )
        assertEquals(Money(1_331_00, "INR"), month.debitsHome)
        assertEquals(
            listOf(Money(120_50, "AED"), Money(500_00, "INR"), Money(10_00, "USD")),
            AccountGroups.spentSince(ledger.entries, 0),
        )
        // The unconverted AED spend adds nothing to the merchant's home-currency total.
        assertEquals(Money(1_331_00, "INR"), Passbook.spendByMerchant(ledger).getValue("Unknown"))
    }

    @Test
    fun `months are UTC calendar months in chronological order`() {
        val lastMsOfAugust = millis(2026, 9, 1) - 1
        val ledger = AccountLedger(
            bank,
            listOf(
                entry("jan27", millis(2027, 1, 2), TransactionDirection.DEBIT, Money(3, "INR")),
                entry("sep", millis(2026, 9, 1), TransactionDirection.DEBIT, Money(2, "INR")),
                entry("aug", lastMsOfAugust, TransactionDirection.DEBIT, Money(1, "INR")),
            ),
        )
        assertEquals(listOf("2026-08", "2026-09", "2027-01"), Passbook.monthlyTotals(ledger).map { it.yearMonth })
    }

    /**
     * Bug: the month key was built with `"%04d-%02d".format(...)`, which uses the device locale's digits, so on a
     * phone set to Bengali, Marathi, Nepali, Arabic or Persian the key came out as "২০২৬-০৯" instead of "2026-09".
     */
    @Test
    fun `month keys are ASCII whatever the device locale`() {
        val ledger = AccountLedger(bank, listOf(entry("m", millis(2026, 9, 10), TransactionDirection.DEBIT, Money(1, "INR"))))
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("bn-IN", "mr-IN", "ne-NP", "ar-EG", "fa-IR", "hi-IN", "en-US")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals("2026-09", Passbook.monthlyTotals(ledger).single().yearMonth, tag)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `spend by merchant trims names, buckets blanks and orders by amount`() {
        val d = millis(2026, 9, 10)
        val ledger = AccountLedger(
            bank,
            listOf(
                entry("1", d, TransactionDirection.DEBIT, Money(100, "INR"), merchant = " SHOP "),
                entry("2", d, TransactionDirection.DEBIT, Money(200, "INR"), merchant = "SHOP"),
                entry("3", d, TransactionDirection.DEBIT, Money(500, "INR"), merchant = "  "),
                entry("4", d, TransactionDirection.DEBIT, Money(50, "INR"), merchant = "CAFE"),
            ),
        )
        val byMerchant = Passbook.spendByMerchant(ledger, unknownMerchantLabel = "?")
        assertEquals(listOf("?" to Money(500, "INR"), "SHOP" to Money(300, "INR"), "CAFE" to Money(50, "INR")), byMerchant.toList())
    }

    @Test
    fun `investment and loan groups total stated values and count the unknown`() {
        fun facts(type: InstrumentType, balance: BalanceState) =
            AccountFacts(bank.copy(id = type.name, instrument = type), balance)
        val investments = AccountGroups.totalsOf(
            AccountType.INVESTMENT,
            listOf(
                facts(InstrumentType.MUTUAL_FUND, BalanceState.Known(Money(1_00_000_00, "INR"), 1)),
                facts(InstrumentType.DEMAT, BalanceState.Known(Money(50_000_00, "INR"), 1)),
                facts(InstrumentType.MUTUAL_FUND, BalanceState.NoInfo),
            ),
        )
        assertEquals(TotalKind.CURRENT_VALUE, investments.kind)
        assertEquals(listOf(Money(1_50_000_00, "INR")), investments.amounts)
        assertEquals(1, investments.missingCount)
        assertEquals(TotalKind.BALANCE, AccountGroups.totalKindOf(AccountType.LOAN))
        assertEquals(TotalKind.SPENT_THIS_MONTH, AccountGroups.totalKindOf(AccountType.UPI))
        assertEquals(TotalKind.SPENT_THIS_MONTH, AccountGroups.totalKindOf(AccountType.UNKNOWN))
        assertEquals(AccountType.entries, AccountGroups.ORDER)
    }

    // --- Ledger.apply end to end

    private fun txn(
        direction: TransactionDirection,
        minor: Long,
        currency: String = "INR",
        instrument: InstrumentType = InstrumentType.BANK_ACCOUNT,
        masked: String = "XX1234",
        institution: String? = "HDFC Bank",
        balance: Long? = null,
        balanceCurrency: String? = balance?.let { currency },
        linked: String? = null,
        ownTransfer: Boolean = false,
        action: InvestmentAction? = null,
    ) = ExtractedTransaction(
        direction = direction,
        amountMinor = minor,
        currency = currency,
        instrument = instrument,
        last4 = masked.filter { it.isDigit() }.takeLast(4),
        institution = institution,
        balanceMinor = balance,
        balanceCurrency = balanceCurrency,
        maskedNumber = masked,
        linkedMaskedNumber = linked,
        ownTransfer = ownTransfer,
        investmentAction = action,
    )

    @Test
    fun `a debit card spend naming its account is posted to both, with the balance only on the account`() {
        val spend = txn(
            TransactionDirection.DEBIT, 1_000_00, instrument = InstrumentType.DEBIT_CARD, masked = "XX5678",
            linked = "XX1234", balance = 9_000_00,
        )
        val ledgers = Ledger.apply(listOf(LedgerInput("m1", 1L, spend))).associateBy { it.account.id }
        assertEquals(setOf("HDFC_BANK:DEBIT_CARD:5678", "HDFC_BANK:BANK_ACCOUNT:1234"), ledgers.keys)

        val card = ledgers.getValue("HDFC_BANK:DEBIT_CARD:5678")
        assertEquals("HDFC_BANK:BANK_ACCOUNT:1234", card.account.linkedAccountId)
        assertEquals(BalanceState.NoInfo, card.balanceState)
        assertEquals(listOf<String?>(null), card.entries.map { it.viaAccountId })

        val account = ledgers.getValue("HDFC_BANK:BANK_ACCOUNT:1234")
        assertEquals(BalanceState.Known(Money(9_000_00, "INR"), 1L), account.balanceState)
        assertEquals(listOf<String?>("HDFC_BANK:DEBIT_CARD:5678"), account.entries.map { it.viaAccountId })
        assertEquals(InstrumentType.BANK_ACCOUNT, account.account.instrument)
        assertEquals(listOf(Money(1_000_00, "INR")), account.entries.map { it.original })
    }

    @Test
    fun `home currency comes from a stated balance, then the institution, then the dominant currency`() {
        fun homeOf(vararg t: ExtractedTransaction, default: (String?) -> String? = Ledger::institutionHomeCurrency) =
            Ledger.apply(t.mapIndexed { i, x -> LedgerInput("m$i", i.toLong(), x) }, defaultHomeCurrency = default).single().account.homeCurrency

        // An Indian bank's account is in INR even when its first SMS is a foreign spend.
        assertEquals("INR", homeOf(txn(TransactionDirection.DEBIT, 100, currency = "USD")))
        // A stated balance currency wins over everything.
        assertEquals("AED", homeOf(txn(TransactionDirection.DEBIT, 100, currency = "USD", balance = 5, balanceCurrency = "AED")))
        // Unknown institution, no balance: the currency most of its SMS use (ties: first seen).
        val unknown = { _: String? -> null }
        assertEquals(
            "EUR",
            homeOf(
                txn(TransactionDirection.DEBIT, 1, currency = "USD", institution = "Revolut"),
                txn(TransactionDirection.DEBIT, 1, currency = "EUR", institution = "Revolut"),
                txn(TransactionDirection.DEBIT, 1, currency = "EUR", institution = "Revolut"),
                default = unknown,
            ),
        )
        assertEquals(
            "USD",
            homeOf(
                txn(TransactionDirection.DEBIT, 1, currency = "USD", institution = "Revolut"),
                txn(TransactionDirection.DEBIT, 1, currency = "EUR", institution = "Revolut"),
                default = unknown,
            ),
        )
        // A malformed default is ignored.
        assertEquals("USD", homeOf(txn(TransactionDirection.DEBIT, 1, currency = "USD", institution = "Revolut"), default = { "dollars" }))
        assertEquals("GBP", homeOf(txn(TransactionDirection.DEBIT, 1, currency = "USD", institution = "Revolut"), default = { " gbp " }))
    }

    @Test
    fun `every movement of an investment account except a dividend is a transfer`() {
        val fund = { action: InvestmentAction, dir: TransactionDirection ->
            txn(dir, 100, instrument = InstrumentType.MUTUAL_FUND, masked = "XX9999", action = action)
        }
        val ledger = Ledger.apply(
            listOf(
                LedgerInput("p", 1, fund(InvestmentAction.PURCHASE, TransactionDirection.CREDIT)),
                LedgerInput("r", 2, fund(InvestmentAction.REDEMPTION, TransactionDirection.DEBIT)),
                LedgerInput("d", 3, fund(InvestmentAction.DIVIDEND, TransactionDirection.CREDIT)),
            ),
        ).single()
        assertEquals(mapOf("p" to true, "r" to true, "d" to false), ledger.entries.associate { it.messageKey to it.transfer })
        // A bank-side SIP debit carries its own transfer flag.
        val sip = Ledger.apply(listOf(LedgerInput("s", 1, txn(TransactionDirection.DEBIT, 100, ownTransfer = true)))).single()
        assertTrue(sip.entries.single().transfer)
    }

    @Test
    fun `a user-set instrument changes the type but never the id`() {
        val ledger = Ledger.apply(
            listOf(LedgerInput("m", 1, txn(TransactionDirection.DEBIT, 100, instrument = InstrumentType.CREDIT_CARD))),
            instrumentOverride = { if (it == "HDFC_BANK:CREDIT_CARD:1234") InstrumentType.DEBIT_CARD else null },
        ).single()
        assertEquals("HDFC_BANK:CREDIT_CARD:1234", ledger.account.id)
        assertEquals(AccountType.DEBIT_CARD, ledger.account.type)
    }

    @Test
    fun `entries are posted oldest first whatever the input order`() {
        val ledger = Ledger.apply(
            listOf(
                LedgerInput("c", 30, txn(TransactionDirection.DEBIT, 3, balance = 30)),
                LedgerInput("a", 10, txn(TransactionDirection.DEBIT, 1, balance = 10)),
                LedgerInput("b", 20, txn(TransactionDirection.DEBIT, 2, balance = 20)),
            ),
        ).single()
        assertEquals(listOf("a", "b", "c"), ledger.entries.map { it.messageKey })
        assertEquals(BalanceState.Known(Money(30, "INR"), 30), ledger.balanceState)
    }
}
