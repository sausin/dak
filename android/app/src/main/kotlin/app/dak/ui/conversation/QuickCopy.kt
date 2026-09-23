package app.dak.ui.conversation

import app.dak.finance.money.CurrencyTable
import app.dak.index.MessageItem
import app.dak.index.TransactionItem
import java.math.BigDecimal

/** What a double-tap on a bubble copies: the OTP code first, else the transaction amount, else nothing. */
sealed interface QuickCopy {
    val text: String

    data class Code(override val text: String) : QuickCopy
    data class Amount(override val text: String) : QuickCopy

    companion object {
        fun of(item: MessageItem): QuickCopy? {
            item.otp?.code?.takeIf { it.isNotBlank() }?.let { return Code(it) }
            item.transaction?.let { return Amount(plainAmount(it)) }
            return null
        }
    }
}

/**
 * The amount as a plain number ("1250.50", no grouping, no symbol) so it pastes cleanly into a UPI or banking
 * app's amount field. Uses the currency's own minor-unit exponent, like the rest of the finance display.
 */
internal fun plainAmount(transaction: TransactionItem): String {
    val exponent = runCatching { CurrencyTable.minorUnitExponent(transaction.currency) }.getOrDefault(2).coerceAtLeast(0)
    return BigDecimal.valueOf(transaction.amountMinor, exponent).abs().toPlainString()
}
