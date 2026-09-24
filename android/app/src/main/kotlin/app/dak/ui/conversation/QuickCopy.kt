package app.dak.ui.conversation

import app.dak.finance.money.CurrencyTable
import app.dak.index.MessageItem
import app.dak.index.TransactionItem
import java.math.BigDecimal

/**
 * How long after it arrived an OTP still offers one-tap copy in the thread (code chip, tap on the code, double-tap,
 * "Copy code"). Older codes are almost always expired, so they only keep the highlight; "Copy text" still works.
 */
internal const val OTP_COPY_WINDOW_MILLIS = 24 * 60 * 60_000L

/**
 * True when [item] carries an OTP that arrived less than [OTP_COPY_WINDOW_MILLIS] before [nowMillis]. A message
 * dated slightly in the future (clock skew) counts as fresh.
 */
internal fun isOtpCopyable(item: MessageItem, nowMillis: Long): Boolean =
    !item.otp?.code.isNullOrBlank() && nowMillis - item.dateMillis < OTP_COPY_WINDOW_MILLIS

/**
 * What a double-tap on a bubble copies: the OTP code while it is fresh ([isOtpCopyable]), else the transaction
 * amount, else nothing.
 */
sealed interface QuickCopy {
    val text: String

    data class Code(override val text: String) : QuickCopy
    data class Amount(override val text: String) : QuickCopy

    companion object {
        fun of(item: MessageItem, nowMillis: Long): QuickCopy? = of(item, otpCopyable = isOtpCopyable(item, nowMillis))

        /** As above, with the OTP freshness already decided by the caller ([BubbleDecor.otpCopyable]). */
        fun of(item: MessageItem, otpCopyable: Boolean): QuickCopy? {
            if (otpCopyable) item.otp?.code?.takeIf { it.isNotBlank() }?.let { return Code(it) }
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
