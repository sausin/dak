package app.dak.index.repo

import app.dak.core.model.InvestmentAction
import app.dak.finance.ledger.Account
import app.dak.finance.ledger.AccountLedger
import app.dak.finance.ledger.BalanceState
import app.dak.finance.ledger.LedgerEntry
import app.dak.finance.money.Money
import app.dak.index.db.entity.AccountRow
import app.dak.index.db.entity.LedgerEntryRow
import java.math.BigDecimal

/**
 * Pure mapping between the ledger's model ([AccountLedger], [LedgerEntry]) and its index rows ([AccountRow],
 * [LedgerEntryRow]). Kept apart from [LedgerRepository] (Room, coroutines) so the round trip is JVM-tested: a field
 * dropped here is silently lost at the next recompute.
 */
internal object LedgerRowMapping {
    fun toAccount(row: AccountRow): Account = Account(
        id = row.id,
        institution = row.institution,
        instrument = row.instrument,
        last4 = row.last4,
        homeCurrency = row.homeCurrency,
        statementDay = row.statementDay,
        maskedNumber = row.maskedNumber,
        linkedAccountId = row.linkedAccountId,
    )

    fun balanceOf(row: AccountRow): BalanceState {
        val known = if (row.balanceMinor != null && row.balanceCurrency != null && row.balanceAsOfMillis != null) {
            BalanceState.Known(Money(row.balanceMinor, row.balanceCurrency), row.balanceAsOfMillis)
        } else {
            null
        }
        return when (row.balanceState) {
            STATE_KNOWN -> known ?: BalanceState.NoInfo
            STATE_UNKNOWN -> BalanceState.Unknown(row.unknownSinceMillis ?: 0L, known)
            else -> BalanceState.NoInfo
        }
    }

    fun toAccountRow(ledger: AccountLedger, statementDay: Int?, nowMillis: Long): AccountRow {
        val state = ledger.balanceState
        val known: BalanceState.Known? = when (state) {
            is BalanceState.Known -> state
            is BalanceState.Unknown -> state.lastKnown
            BalanceState.NoInfo -> null
        }
        return AccountRow(
            id = ledger.account.id,
            institution = ledger.account.institution,
            instrument = ledger.account.instrument,
            last4 = ledger.account.last4,
            homeCurrency = ledger.account.homeCurrency,
            statementDay = statementDay,
            balanceState = when (state) {
                is BalanceState.Known -> STATE_KNOWN
                is BalanceState.Unknown -> STATE_UNKNOWN
                BalanceState.NoInfo -> STATE_NO_INFO
            },
            balanceMinor = known?.balance?.amountMinor,
            balanceCurrency = known?.balance?.currencyUpper,
            balanceAsOfMillis = known?.asOfMillis,
            unknownSinceMillis = (state as? BalanceState.Unknown)?.sinceMillis,
            entryCount = ledger.entries.size,
            lastActivityMillis = ledger.entries.maxOfOrNull { it.dateMillis } ?: 0L,
            updatedAt = nowMillis,
            maskedNumber = ledger.account.maskedNumber,
            linkedAccountId = ledger.account.linkedAccountId,
            unitsHeld = ledger.unitsHeld,
        )
    }

    fun toEntryRow(accountId: String, e: LedgerEntry): LedgerEntryRow = LedgerEntryRow(
        messageKey = e.messageKey,
        accountId = accountId,
        dateMillis = e.dateMillis,
        direction = e.direction,
        originalMinor = e.original.amountMinor,
        originalCurrency = e.original.currencyUpper,
        indicativeMinor = e.indicativeHome?.amountMinor,
        indicativeCurrency = e.indicativeHome?.currencyUpper,
        rate = e.rate?.toPlainString(),
        rateDateMillis = e.rateDateMillis,
        settled = e.settled,
        markupPercent = e.effectiveMarkupPercent?.toPlainString(),
        balanceAfterMinor = e.balanceAfter?.amountMinor,
        balanceAfterCurrency = e.balanceAfter?.currencyUpper,
        merchant = e.merchant,
        reference = e.reference,
        viaAccountId = e.viaAccountId,
        transfer = e.transfer,
        investmentAction = e.investmentAction?.name,
        units = e.units,
        unitPrice = e.unitPrice,
    )

    fun toEntry(row: LedgerEntryRow): LedgerEntry = LedgerEntry(
        messageKey = row.messageKey,
        dateMillis = row.dateMillis,
        direction = row.direction,
        original = Money(row.originalMinor, row.originalCurrency),
        indicativeHome = if (row.indicativeMinor != null && row.indicativeCurrency != null) {
            Money(row.indicativeMinor, row.indicativeCurrency)
        } else {
            null
        },
        rate = row.rate?.let(::decimalOrNull),
        rateDateMillis = row.rateDateMillis,
        settled = row.settled,
        effectiveMarkupPercent = row.markupPercent?.let(::decimalOrNull),
        balanceAfter = if (row.balanceAfterMinor != null && row.balanceAfterCurrency != null) {
            Money(row.balanceAfterMinor, row.balanceAfterCurrency)
        } else {
            null
        },
        merchant = row.merchant,
        reference = row.reference,
        viaAccountId = row.viaAccountId,
        transfer = row.transfer,
        investmentAction = row.investmentAction?.let { name -> InvestmentAction.entries.firstOrNull { it.name == name } },
        units = row.units,
        unitPrice = row.unitPrice,
    )

    private fun decimalOrNull(s: String): BigDecimal? = runCatching { BigDecimal(s) }.getOrNull()

    const val STATE_KNOWN = "KNOWN"
    const val STATE_UNKNOWN = "UNKNOWN"
    const val STATE_NO_INFO = "NO_INFO"
}
