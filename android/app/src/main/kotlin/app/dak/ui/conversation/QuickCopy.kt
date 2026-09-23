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

private const val QUOTE_MAX_CHARS = 80
private const val OTP_MASK = "••••"

/**
 * [draft] with a one-line quote of [item] on top (SMS has no native reply threading). An OTP in the quoted text
 * is masked, so replying to a code message can never send the code back out.
 */
internal fun withQuote(draft: String, item: MessageItem): String {
    var line = item.body.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    item.otp?.code?.takeIf { it.isNotBlank() }?.let { line = line.replace(it, OTP_MASK) }
    if (line.length > QUOTE_MAX_CHARS) line = line.take(QUOTE_MAX_CHARS).trimEnd() + "…"
    val quote = "> $line\n"
    return if (draft.startsWith(quote)) draft else quote + draft
}
