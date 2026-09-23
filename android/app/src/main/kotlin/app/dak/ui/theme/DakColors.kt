package app.dak.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.dak.core.model.Category

/**
 * A container/content pair plus an accent for small marks (dots, icons, chart strokes) on the plain surface.
 * [content] on [container] meets WCAG AA (4.5:1); [accent] on the surface meets 3:1 for non-text marks.
 */
@Immutable
data class TonalColors(val container: Color, val content: Color, val accent: Color)

/**
 * Semantic colour tokens for everything Material's [ColorScheme] does not name. Screens read these via
 * `DakTheme.colors` and never hard-code a colour. Category, SIM, finance and warning tokens have separately
 * tuned light and dark values; bubble tokens derive from the active (possibly dynamic) scheme.
 */
@Immutable
data class DakColors(
    val isDark: Boolean,
    val bubbleIncoming: Color,
    val onBubbleIncoming: Color,
    val bubbleOutgoing: Color,
    val onBubbleOutgoing: Color,
    /** Failed outgoing bubble. */
    val bubbleFailed: Color,
    val onBubbleFailed: Color,
    /** Background behind an OTP code (bubble span, notification-like cards). */
    val otpHighlight: Color,
    val onOtpHighlight: Color,
    val personal: TonalColors,
    val transaction: TonalColors,
    val otp: TonalColors,
    val promotion: TonalColors,
    val spam: TonalColors,
    val unknownCategory: TonalColors,
    /** SIM colours by slot (index 0 = SIM 1). Use [sim] which wraps around. */
    val sims: List<TonalColors>,
    /** Avatar backgrounds; pick with [avatar]. */
    val avatars: List<TonalColors>,
    val financeCredit: Color,
    val financeDebit: Color,
    val warning: TonalColors,
    val success: TonalColors,
    /** Content colour of locked (premium) rows and chips. */
    val locked: Color,
    /** Flash behind a deep-linked settings row or a highlighted message. */
    val focusHighlight: Color,
) {
    /** Tokens for an inbox category. */
    fun category(category: Category): TonalColors = when (category) {
        Category.PERSONAL -> personal
        Category.TRANSACTION -> transaction
        Category.OTP -> otp
        Category.PROMOTION -> promotion
        Category.SPAM -> spam
        Category.UNKNOWN -> unknownCategory
    }

    /** Tokens for a SIM slot (0-based). Unknown slots (-1) get the neutral colour. */
    fun sim(slotIndex: Int): TonalColors =
        if (slotIndex < 0) unknownCategory else sims[slotIndex % sims.size]

    /** Stable avatar colour for a key (address / contact id). */
    fun avatar(key: String): TonalColors = avatars[Math.floorMod(key.hashCode(), avatars.size)]
}

private fun t(container: Long, content: Long, accent: Long) = TonalColors(Color(container), Color(content), Color(accent))

private val LightSemantic = Sem(
    personal = t(0xFFD3E4FF, 0xFF0B3A6E, 0xFF2B5EA7),
    transaction = t(0xFFC8F0D0, 0xFF0B4F24, 0xFF1E7A3E),
    otp = t(0xFFFFE08A, 0xFF4A3500, 0xFF8A6100),
    promotion = t(0xFFF3DBFF, 0xFF4C1C6B, 0xFF7B3FA8),
    spam = t(0xFFFFDAD6, 0xFF7A1410, 0xFFC0271E),
    unknown = t(0xFFE3E9E9, 0xFF3F4948, 0xFF6F7979),
    sims = listOf(
        t(0xFFD6E3FF, 0xFF0D3569, 0xFF1B5FAA),
        t(0xFFFFDCC3, 0xFF5A2600, 0xFFA04A00),
        t(0xFFCDEFCB, 0xFF12451A, 0xFF2E7D32),
        t(0xFFF0DBFF, 0xFF4A1F68, 0xFF7B3FA8),
    ),
    avatars = listOf(
        t(0xFFD3E4FF, 0xFF0B3A6E, 0xFF2B5EA7),
        t(0xFFFFDCC3, 0xFF5A2600, 0xFFA04A00),
        t(0xFFCDEFCB, 0xFF12451A, 0xFF2E7D32),
        t(0xFFF0DBFF, 0xFF4A1F68, 0xFF7B3FA8),
        t(0xFFFFD9E2, 0xFF5E1130, 0xFFA3395F),
        t(0xFFC9EEF0, 0xFF0A4A4E, 0xFF1C7479),
    ),
    credit = Color(0xFF1E7A3E),
    debit = Color(0xFFB3261E),
    warning = t(0xFFFFDDB3, 0xFF4E2600, 0xFF8B5000),
    success = t(0xFFC8F0D0, 0xFF0B4F24, 0xFF1E7A3E),
)

private val DarkSemantic = Sem(
    personal = t(0xFF1F3A5C, 0xFFD3E4FF, 0xFF9CC3FF),
    transaction = t(0xFF1D3F27, 0xFFB7EFC5, 0xFF7FD69A),
    otp = t(0xFF4A3A00, 0xFFFFE08A, 0xFFF5C542),
    promotion = t(0xFF41285A, 0xFFF0DBFF, 0xFFD7AEFF),
    spam = t(0xFF5C1A15, 0xFFFFDAD6, 0xFFFF8A80),
    unknown = t(0xFF2F3636, 0xFFBEC9C8, 0xFF889392),
    sims = listOf(
        t(0xFF173A63, 0xFFD6E3FF, 0xFF9EC8FF),
        t(0xFF5A2E0C, 0xFFFFDCC3, 0xFFFFB77C),
        t(0xFF1C4420, 0xFFCDEFCB, 0xFF8FD694),
        t(0xFF41285A, 0xFFF0DBFF, 0xFFD7AEFF),
    ),
    avatars = listOf(
        t(0xFF1F3A5C, 0xFFD3E4FF, 0xFF9CC3FF),
        t(0xFF5A2E0C, 0xFFFFDCC3, 0xFFFFB77C),
        t(0xFF1C4420, 0xFFCDEFCB, 0xFF8FD694),
        t(0xFF41285A, 0xFFF0DBFF, 0xFFD7AEFF),
        t(0xFF5B1D33, 0xFFFFD9E2, 0xFFFFB0C8),
        t(0xFF0F4346, 0xFFC9EEF0, 0xFF7FD3D8),
    ),
    credit = Color(0xFF7FD69A),
    debit = Color(0xFFFFB4AB),
    warning = t(0xFF5A3A00, 0xFFFFDDB3, 0xFFFFB95C),
    success = t(0xFF1D3F27, 0xFFB7EFC5, 0xFF7FD69A),
)

/** Builds the semantic tokens for the active [scheme]. */
internal fun dakColorsFor(scheme: ColorScheme, isDark: Boolean): DakColors {
    val s = if (isDark) DarkSemantic else LightSemantic
    return DakColors(
        isDark = isDark,
        bubbleIncoming = scheme.surfaceContainerHigh,
        onBubbleIncoming = scheme.onSurface,
        bubbleOutgoing = scheme.primaryContainer,
        onBubbleOutgoing = scheme.onPrimaryContainer,
        bubbleFailed = scheme.errorContainer,
        onBubbleFailed = scheme.onErrorContainer,
        otpHighlight = s.otp.container,
        onOtpHighlight = s.otp.content,
        personal = s.personal,
        transaction = s.transaction,
        otp = s.otp,
        promotion = s.promotion,
        spam = s.spam,
        unknownCategory = s.unknown,
        sims = s.sims,
        avatars = s.avatars,
        financeCredit = s.credit,
        financeDebit = s.debit,
        warning = s.warning,
        success = s.success,
        locked = scheme.onSurface.copy(alpha = 0.38f),
        focusHighlight = scheme.primary.copy(alpha = 0.16f),
    )
}

private class Sem(
    val personal: TonalColors,
    val transaction: TonalColors,
    val otp: TonalColors,
    val promotion: TonalColors,
    val spam: TonalColors,
    val unknown: TonalColors,
    val sims: List<TonalColors>,
    val avatars: List<TonalColors>,
    val credit: Color,
    val debit: Color,
    val warning: TonalColors,
    val success: TonalColors,
)
