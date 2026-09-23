package app.dak

import app.dak.core.model.Category
import app.dak.di.SettingsBinPolicy
import app.dak.di.SettingsOtpPolicy
import app.dak.index.BinPolicy
import app.dak.index.ConsumedOtpMode
import app.dak.premium.Feature
import app.dak.premium.FreeEntitlements
import app.dak.premium.StaticEntitlements
import app.dak.settings.AppearanceSettings
import app.dak.settings.DakSettings
import app.dak.settings.InMemorySettingsStore
import app.dak.ui.common.initialsOf
import app.dak.ui.settings.valueLabel
import app.dak.ui.theme.ContrastMode
import app.dak.ui.theme.ThemeMode
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellLogicTest {

    @Test fun initials() {
        assertEquals("AK", initialsOf("Asha Kumari"))
        assertEquals("AS", initialsOf("Asha R. Sen"))
        assertEquals("M", initialsOf("Mom"))
        assertNull(initialsOf("+91 98765 43210"))
        assertNull(initialsOf("  "))
        assertNull(initialsOf(null))
    }

    @Test fun appearanceDefaultsFollowSystem() {
        val prefs = AppearanceSettings.prefsFrom(InMemorySettingsStore())
        assertEquals(ThemeMode.SYSTEM, prefs.mode)
        assertEquals(ContrastMode.SYSTEM, prefs.contrast)
        assertFalse(prefs.amoled)
        assertTrue(prefs.dynamicColor)
    }

    @Test fun appearanceReadsStoredValues() {
        val store = InMemorySettingsStore(
            mapOf("appearance.themeMode" to "dark", "appearance.amoled" to "true", "appearance.contrast" to "high"),
        )
        val prefs = AppearanceSettings.prefsFrom(store)
        assertEquals(ThemeMode.DARK, prefs.mode)
        assertTrue(prefs.amoled)
        assertEquals(ContrastMode.HIGH, prefs.contrast)
    }

    @Test fun binPolicyKeepsFreeDefaultWhenLocked() = runTest {
        val store = InMemorySettingsStore(mapOf(DakSettings.otpBinRetention.key to "untilEmptied"))
        val free = SettingsBinPolicy(store, FreeEntitlements)
        assertEquals(BinPolicy.DEFAULT_OTP_RETENTION_MILLIS, free.retentionMillis(Category.OTP))
        val premium = SettingsBinPolicy(store, StaticEntitlements(setOf(Feature.ADJUSTABLE_OTP_BIN_RETENTION)))
        assertNull(premium.retentionMillis(Category.OTP))
        assertEquals(30L * 24 * 60 * 60_000L, free.retentionMillis(Category.PERSONAL))
    }

    @Test fun otpPolicyMapsSettings() = runTest {
        val store = InMemorySettingsStore(
            mapOf(
                DakSettings.otpAutoDelete.key to "off",
                DakSettings.consumedOtpHandling.key to "silentOnly",
                DakSettings.consumedOtpWindowMinutes.key to "5",
            ),
        )
        val policy = SettingsOtpPolicy(store)
        assertNull(policy.otpAutoDeleteAfterMillis())
        assertEquals(ConsumedOtpMode.SILENT_ONLY, policy.consumedOtpMode())
        assertEquals(5 * 60_000L, policy.consumedOtpDeleteAfterMillis())
    }

    @Test fun inlineValueLabels() {
        assertEquals("24 hours", valueLabel(DakSettings.otpAutoDelete, "24h"))
        assertEquals("On", valueLabel(DakSettings.quickActions, "true"))
        assertNull(valueLabel(DakSettings.selfTest, ""))
    }
}
