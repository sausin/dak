package app.dak.index.enrich

import app.dak.core.model.Category
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.index.otp.OtpTiming

/**
 * Pure rules of the repeated-message collapse (generalising the old OTP-only collapse).
 *
 * Two incoming messages of one conversation are copies of each other when
 * - their bodies are exactly identical and they arrived within [EXACT_WINDOW_MILLIS] of each other, or
 * - they carry the same OTP code and arrived within [OTP_WINDOW_MILLIS] ("resend OTP" with new wording).
 *
 * Template look-alikes ("Your a/c XX1234 balance is Rs 5,000" / "... Rs 4,200") are deliberately NOT collapsed:
 * each carries its own data. Personal chats are never collapsed either: a friend sending "ok" twice in a day is two
 * messages in context, not a repeat. Copies share a group key: the [MessageKey] string of the oldest copy.
 */
object RepeatRules {

    const val EXACT_WINDOW_MILLIS: Long = 24 * 60 * 60_000L
    const val OTP_WINDOW_MILLIS: Long = OtpTiming.REPEAT_WINDOW_MILLIS

    /** Messages that may take part in a repeat group. */
    fun eligible(box: MessageBox, category: Category, body: String): Boolean =
        box == MessageBox.INBOX && category != Category.PERSONAL && body.isNotBlank()

    /** True when two messages are copies under the rules above (same conversation assumed). */
    fun isRepeat(
        bodyA: String,
        otpA: String?,
        dateA: Long,
        bodyB: String,
        otpB: String?,
        dateB: Long,
    ): Boolean {
        val delta = kotlin.math.abs(dateA - dateB)
        if (bodyA == bodyB && delta <= EXACT_WINDOW_MILLIS) return true
        return otpA != null && otpA == otpB && delta <= OTP_WINDOW_MILLIS
    }

    /** Group key for two copies with no group yet: the older one's key (ties: the smaller provider id). */
    fun groupKeyOf(a: MessageKey, dateA: Long, b: MessageKey, dateB: Long): String = when {
        dateA < dateB -> a.toString()
        dateB < dateA -> b.toString()
        a.providerId <= b.providerId -> a.toString()
        else -> b.toString()
    }
}
