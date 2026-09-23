package app.dak.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/** Light / dark choice. [SYSTEM] (the default) follows the system setting live. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** High-contrast choice. [SYSTEM] follows the system contrast setting on Android 14+ (standard below). */
enum class ContrastMode { SYSTEM, STANDARD, HIGH }

/** Everything the theme needs from Settings. Defaults are the product defaults. */
@Immutable
data class AppearancePrefs(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** True-black variant of dark. */
    val amoled: Boolean = false,
    val contrast: ContrastMode = ContrastMode.SYSTEM,
    /** Wallpaper-based colour on Android 12+. Ignored below 12 and in high contrast. */
    val dynamicColor: Boolean = true,
    /** OTP code size multiplier used in-app (the notification uses its own setting). */
    val otpScale: Float = 1f,
)

/** Resolved theme facts screens may need (e.g. to pick an illustration). */
@Immutable
data class DakThemeState(val isDark: Boolean, val isAmoled: Boolean, val isHighContrast: Boolean, val isDynamic: Boolean)

/** Extra type styles on top of [MaterialTheme.typography]. */
@Immutable
data class DakTypography(
    /** Large, bold, tabular OTP code (bubble chip, OTP card). */
    val otpCode: TextStyle,
    /** Monospace amounts in the passbook. */
    val amount: TextStyle,
)

private val LocalDakColors = staticCompositionLocalOf { dakColorsFor(DakLightScheme, isDark = false) }
private val LocalDakTypography = staticCompositionLocalOf { dakTypography(otpScale = 1f, textScale = 1f) }
private val LocalDakThemeState = staticCompositionLocalOf {
    DakThemeState(isDark = false, isAmoled = false, isHighContrast = false, isDynamic = false)
}

/** Accessors for Dak's tokens. Use `DakTheme.colors.otp.container`, never a literal colour. */
object DakTheme {
    val colors: DakColors
        @Composable @ReadOnlyComposable get() = LocalDakColors.current
    val typography: DakTypography
        @Composable @ReadOnlyComposable get() = LocalDakTypography.current
    val state: DakThemeState
        @Composable @ReadOnlyComposable get() = LocalDakThemeState.current
}

/**
 * The single app theme. Switches live when the system theme or [prefs] change (no activity restart):
 * `isSystemInDarkTheme()` recomposes on configuration change and MainActivity handles `uiMode` itself.
 * Text sizes are scaled for the device size ([deviceTextScale]) on top of the system font scale.
 */
@Composable
fun DakTheme(prefs: AppearancePrefs = AppearancePrefs(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (prefs.mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Re-read the system contrast whenever we come back to the foreground.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val systemHighContrast = remember(resumed, systemDark) { systemPrefersHighContrast(context) }
    val highContrast = when (prefs.contrast) {
        ContrastMode.HIGH -> true
        ContrastMode.STANDARD -> false
        ContrastMode.SYSTEM -> systemHighContrast
    }
    val dynamic = prefs.dynamicColor && !highContrast && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val base: ColorScheme = when {
        highContrast -> if (dark) DakDarkHighContrastScheme else DakLightHighContrastScheme
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> if (dark) DakDarkScheme else DakLightScheme
    }
    val scheme = if (dark && prefs.amoled) base.toAmoled() else base
    val dakColors = remember(scheme, dark) { dakColorsFor(scheme, dark) }
    val textScale = deviceTextScale(LocalConfiguration.current.smallestScreenWidthDp)
    val materialType = remember(textScale) { DakMaterialTypography.scaled(textScale) }
    val dakType = remember(prefs.otpScale, textScale) { dakTypography(prefs.otpScale, textScale) }
    val state = DakThemeState(isDark = dark, isAmoled = dark && prefs.amoled, isHighContrast = highContrast, isDynamic = dynamic)

    CompositionLocalProvider(
        LocalDakColors provides dakColors,
        LocalDakTypography provides dakType,
        LocalDakThemeState provides state,
    ) {
        MaterialTheme(colorScheme = scheme, typography = materialType, content = content)
    }
}

/** System contrast (Android 14+ "Contrast level"); anything at or above medium counts as high. */
internal fun systemPrefersHighContrast(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return false
    return uiModeManager.contrast >= 0.5f
}

private fun dakTypography(otpScale: Float, textScale: Float): DakTypography {
    val scale = otpScale.coerceIn(0.75f, 2f) * textScale
    return DakTypography(
        otpCode = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = (28 * scale).sp,
            letterSpacing = (3 * scale).sp,
        ),
        amount = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = (16 * textScale).sp),
    )
}
