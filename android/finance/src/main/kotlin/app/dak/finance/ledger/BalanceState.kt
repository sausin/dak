package app.dak.finance.ledger

import app.dak.finance.money.Money

/**
 * An account's balance, as honestly as the SMS stream supports. We never invent a number: a
 * balance is only ever the value copied from the most recent balance-bearing SMS.
 */
sealed class BalanceState {
    /** No balance-bearing SMS has ever been seen for this account. */
    data object NoInfo : BalanceState()

    /** The last known balance, exactly as stated in a message, and when it was stated. */
    data class Known(val balance: Money, val asOfMillis: Long) : BalanceState()

    /**
     * A foreign or otherwise unsettled transaction happened after the last known balance, so the
     * true balance can no longer be stated with confidence. [sinceMillis] is that transaction's date.
     */
    data class Unknown(val sinceMillis: Long, val lastKnown: Known?) : BalanceState()
}
