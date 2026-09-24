package app.dak.finance.ledger

import app.dak.core.model.InstrumentType
import app.dak.core.model.TransactionDirection
import app.dak.finance.money.Money
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the ledger's hot helpers to verbatim copies of their original implementations, so caching them (a hoisted
 * whitespace regex in [Account.idFor], a memoised date sort behind [AccountLedger.balanceState],
 * [AccountLedger.unitsHeld] and [AccountLedger.cardOutstanding]) cannot change a result. Randomized with fixed seeds.
 */
class LedgerHotPathEquivalenceTest {

    private val day = TimeUnit.DAYS.toMillis(1)

    // ---- Account.idFor ----

    /** The original `Account.idFor`, which built its regex on every call. */
    private fun referenceIdFor(institution: String?, instrument: InstrumentType, last4: String?): String {
        val inst = institution?.uppercase()?.replace(Regex("\\s+"), "_") ?: "UNKNOWN"
        val last = last4 ?: "0000"
        return "$inst:${instrument.name}:$last"
    }

    @Test
    fun `idFor equals the reference for every kind of whitespace and case`() {
        // ASCII whitespace the regex folds, and Unicode spaces it does not (NBSP, em space, zero-width space), plus
        // letters whose upper case changes length or depends on locale.
        val pool = listOf(
            "a", "B", "hdfc", "Bank", " ", "  ", "\t", "\n", "\r", "\u000B", "\u000C", " ", " ", "​",
            "ı", "İ", "ß", "é", "ﬁ", "_", "-", ".", "&", "1", "Σ", "ς", "हिंदी",
        )
        val rnd = Random(7)
        repeat(20_000) {
            val institution = if (rnd.nextInt(20) == 0) null else buildString { repeat(rnd.nextInt(8)) { append(pool[rnd.nextInt(pool.size)]) } }
            val instrument = InstrumentType.entries[rnd.nextInt(InstrumentType.entries.size)]
            val last4 = when (rnd.nextInt(4)) {
                0 -> null
                1 -> ""
                else -> rnd.nextInt(100_000_000).toString()
            }
            assertEquals(referenceIdFor(institution, instrument, last4), Account.idFor(institution, instrument, last4), "'$institution'")
        }
    }

    @Test
    fun `idFor timing`() {
        val names = listOf("HDFC Bank", "State Bank of India", "ICICI Bank", "AU Small Finance Bank", "Paytm Payments Bank")
        var sink = 0
        val n = 200_000
        val reference = measureNanoTime { repeat(n) { sink += referenceIdFor(names[it % names.size], InstrumentType.BANK_ACCOUNT, "1234").length } }
        val current = measureNanoTime { repeat(n) { sink += Account.idFor(names[it % names.size], InstrumentType.BANK_ACCOUNT, "1234").length } }
        println("IDFOR-BENCH calls=$n reference=${reference / 1_000_000} ms current=${current / 1_000_000} ms (sink $sink)")
    }

    // ---- AccountLedger derived values ----

    /** The original derivations, re-sorting the entries on every access. */
    private object ReferenceAccountLedger {
        fun sorted(l: AccountLedger) = l.entries.sortedBy { it.dateMillis }

        fun unitsHeld(l: AccountLedger): String? = sorted(l).lastOrNull { it.unitsHeld != null }?.unitsHeld

        fun balanceState(l: AccountLedger): BalanceState {
            val balanceEntries = sorted(l).filter { it.balanceAfter != null }
            val last = balanceEntries.maxByOrNull { it.dateMillis } ?: return BalanceState.NoInfo
            val known = BalanceState.Known(last.balanceAfter!!, last.dateMillis)
            val unsettledSince = sorted(l)
                .filter { !it.settled && it.dateMillis > last.dateMillis }
                .minOfOrNull { it.dateMillis }
            return if (unsettledSince != null) BalanceState.Unknown(sinceMillis = unsettledSince, lastKnown = known) else known
        }

        fun cardOutstanding(l: AccountLedger, asOfMillis: Long, billingCycle: BillingCycle): Money? {
            if (l.account.type != AccountType.CREDIT_CARD) return null
            val range = billingCycle.cycleRange(asOfMillis)
            var total = Money.zero(l.account.homeCurrency)
            for (entry in sorted(l)) {
                if (entry.dateMillis !in range) continue
                val value = entry.homeValue
                if (value.currencyUpper != total.currencyUpper) continue
                total = when (entry.direction) {
                    TransactionDirection.DEBIT -> total + value
                    TransactionDirection.CREDIT -> total - value
                }
            }
            return total
        }
    }

    private fun randomLedger(rnd: Random, instrument: InstrumentType): AccountLedger {
        val start = 1_767_225_600_000L // 2026-01-01T00:00Z
        val entries = List(rnd.nextInt(0, 80)) { i ->
            val date = start + if (rnd.nextInt(4) == 0) rnd.nextLong(60) * day else rnd.nextLong(60 * day)
            val currency = when (rnd.nextInt(8)) {
                0 -> "USD"
                1 -> "inr"
                else -> "INR"
            }
            val amount = Money(rnd.nextLong(1, 500_000), currency)
            val settled = currency != "USD" || rnd.nextInt(3) == 0
            LedgerEntry(
                messageKey = "sms:$i",
                dateMillis = date,
                direction = if (rnd.nextInt(4) == 0) TransactionDirection.CREDIT else TransactionDirection.DEBIT,
                original = amount,
                indicativeHome = when {
                    currency != "USD" -> amount
                    rnd.nextBoolean() -> Money(amount.amountMinor * 83, "INR")
                    else -> null
                },
                settled = settled,
                balanceAfter = if (rnd.nextInt(3) == 0) Money(rnd.nextLong(10_000_000), if (rnd.nextInt(6) == 0) "AED" else "INR") else null,
                unitsHeld = if (rnd.nextInt(5) == 0) "${rnd.nextInt(1000)}.${rnd.nextInt(1000)}" else null,
            )
        }.shuffled(rnd)
        val account = Account(
            id = "BANK:${instrument.name}:1234",
            institution = "Bank",
            instrument = instrument,
            last4 = "1234",
            homeCurrency = "INR",
        )
        return AccountLedger(account, entries)
    }

    @Test
    fun `balanceState, unitsHeld and cardOutstanding equal the reference`() {
        val instruments = listOf(InstrumentType.CREDIT_CARD, InstrumentType.BANK_ACCOUNT, InstrumentType.MUTUAL_FUND, InstrumentType.WALLET)
        for (seed in 0 until 600) {
            val rnd = Random(seed)
            val ledger = randomLedger(rnd, instruments[seed % instruments.size])
            assertEquals(ReferenceAccountLedger.balanceState(ledger), ledger.balanceState, "balanceState seed $seed")
            assertEquals(ReferenceAccountLedger.unitsHeld(ledger), ledger.unitsHeld, "unitsHeld seed $seed")
            // Repeated access must keep answering the same (a memoised sort must not be consumed or mutated).
            assertEquals(ReferenceAccountLedger.balanceState(ledger), ledger.balanceState, "balanceState again seed $seed")
            repeat(6) {
                val asOf = 1_767_225_600_000L + rnd.nextLong(90 * day)
                val cycle = BillingCycle(1 + rnd.nextInt(31))
                assertEquals(
                    ReferenceAccountLedger.cardOutstanding(ledger, asOf, cycle),
                    ledger.cardOutstanding(asOf, cycle),
                    "cardOutstanding seed $seed asOf $asOf day ${cycle.statementDay}",
                )
            }
        }
    }

    @Test
    fun `derived values are not part of equality and entries keep their order`() {
        val ledger = randomLedger(Random(5), InstrumentType.CREDIT_CARD)
        val copy = AccountLedger(ledger.account, ledger.entries.toList())
        ledger.balanceState // forces any memoised state on one side only
        ledger.cardOutstanding(1_767_225_600_000L + 10 * day, BillingCycle(15))
        assertEquals(copy, ledger)
        assertEquals(copy.hashCode(), ledger.hashCode())
        assertEquals(copy.entries, ledger.entries, "the constructor list is exposed as given, never sorted in place")
        assertNull(AccountLedger(ledger.account.copy(instrument = InstrumentType.BANK_ACCOUNT), ledger.entries).cardOutstanding(0L, BillingCycle(1)))
    }

    @Test
    fun `timing of repeated cardOutstanding on a large card`() {
        val rnd = Random(11)
        val start = 1_767_225_600_000L
        val entries = List(20_000) { i ->
            LedgerEntry("sms:$i", start + rnd.nextLong(365 * day), TransactionDirection.DEBIT, Money(rnd.nextLong(1, 100_000), "INR"), Money(rnd.nextLong(1, 100_000), "INR"))
        }
        val ledger = AccountLedger(Account("C:CREDIT_CARD:1", "C", InstrumentType.CREDIT_CARD, "1", "INR"), entries)
        val cycle = BillingCycle(10)
        val asOfs = List(24) { start + it * 15 * day }
        var check = 0L
        val reference = measureNanoTime { asOfs.forEach { check += ReferenceAccountLedger.cardOutstanding(ledger, it, cycle)!!.amountMinor } }
        var check2 = 0L
        val current = measureNanoTime { asOfs.forEach { check2 += ledger.cardOutstanding(it, cycle)!!.amountMinor } }
        assertEquals(check, check2)
        println("CARD-OUTSTANDING-BENCH entries=${entries.size} calls=${asOfs.size} reference=${reference / 1_000_000} ms current=${current / 1_000_000} ms")
    }
}
