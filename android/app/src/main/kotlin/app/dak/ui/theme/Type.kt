package app.dak.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit

/** Material 3 default type scale (system font, respects the user's font size). */
internal val DakMaterialTypography = Typography()

/**
 * Device-size text factor, keyed on the smallest screen width in dp (stable across rotation).
 *
 * Material's sp sizes read best on ~360–412dp phones (1.0; the emulator "Medium Phone" is 411dp). On
 * larger phones (e.g. 448dp Pixel Pro XL class) the same sp looks small, so text grows gently to 1.08;
 * foldables and tablets top out at 1.12, since they get a wider layout rather than bigger text. Small
 * phones (<360dp) shrink slightly, floored at 0.92. Linear between the anchors below.
 *
 * This multiplies on top of the system font scale (sp), which is always respected.
 */
internal fun deviceTextScale(smallestWidthDp: Int): Float {
    if (smallestWidthDp <= 0) return 1f // Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED
    val dp = smallestWidthDp.toFloat()
    val first = TEXT_SCALE_ANCHORS.first()
    if (dp <= first.first) return first.second
    for (i in 1 until TEXT_SCALE_ANCHORS.size) {
        val (x1, y1) = TEXT_SCALE_ANCHORS[i]
        if (dp <= x1) {
            val (x0, y0) = TEXT_SCALE_ANCHORS[i - 1]
            return y0 + (y1 - y0) * (dp - x0) / (x1 - x0)
        }
    }
    return TEXT_SCALE_ANCHORS.last().second
}

/** (smallest width dp, factor), ascending. Flat 1.0 across typical 360–412dp phones. */
private val TEXT_SCALE_ANCHORS = listOf(
    320f to 0.92f,
    360f to 1f,
    412f to 1f,
    448f to 1.08f,
    600f to 1.12f,
)

/** Every style's sp sizes (font size, line height, letter spacing) multiplied by [factor]. */
internal fun Typography.scaled(factor: Float): Typography {
    if (factor == 1f) return this
    return copy(
        displayLarge = displayLarge.scaled(factor),
        displayMedium = displayMedium.scaled(factor),
        displaySmall = displaySmall.scaled(factor),
        headlineLarge = headlineLarge.scaled(factor),
        headlineMedium = headlineMedium.scaled(factor),
        headlineSmall = headlineSmall.scaled(factor),
        titleLarge = titleLarge.scaled(factor),
        titleMedium = titleMedium.scaled(factor),
        titleSmall = titleSmall.scaled(factor),
        bodyLarge = bodyLarge.scaled(factor),
        bodyMedium = bodyMedium.scaled(factor),
        bodySmall = bodySmall.scaled(factor),
        labelLarge = labelLarge.scaled(factor),
        labelMedium = labelMedium.scaled(factor),
        labelSmall = labelSmall.scaled(factor),
    )
}

/** Scales only sp units; em values are already relative to the (scaled) font size, unspecified stays so. */
internal fun TextStyle.scaled(factor: Float): TextStyle {
    if (factor == 1f) return this
    return copy(
        fontSize = fontSize.scaledSp(factor),
        lineHeight = lineHeight.scaledSp(factor),
        letterSpacing = letterSpacing.scaledSp(factor),
    )
}

private fun TextUnit.scaledSp(factor: Float): TextUnit = if (isSp) this * factor else this
