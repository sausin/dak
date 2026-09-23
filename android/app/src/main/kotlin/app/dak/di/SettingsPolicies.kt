package app.dak.di

import app.dak.core.model.Category
import app.dak.index.BinPolicy
import app.dak.index.ConsumedOtpMode
import app.dak.index.OtpPolicy
import app.dak.index.otp.OtpTiming
import app.dak.premium.Entitlements
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton

private const val HOUR = 60 * 60_000L
private const val DAY = 24 * HOUR

/** [BinPolicy] backed by Settings → Backup and data. Adjusting OTP retention is premium; free keeps 1 day. */
@Singleton
class SettingsBinPolicy @Inject constructor(
    private val settings: SettingsStore,
    private val entitlements: Entitlements,
) : BinPolicy {
    override suspend fun retentionMillis(category: Category): Long? {
        if (category != Category.OTP) return settings.get(DakSettings.otherBinRetentionDays).coerceAtLeast(1) * DAY
        if (DakSettings.otpBinRetention.isLocked(entitlements)) return BinPolicy.DEFAULT_OTP_RETENTION_MILLIS
        return when (settings.get(DakSettings.otpBinRetention)) {
            "shorter" -> HOUR
            "longer" -> 7 * DAY
            "untilEmptied" -> null
            else -> BinPolicy.DEFAULT_OTP_RETENTION_MILLIS
        }
    }
}

/** [OtpPolicy] backed by Settings → Notifications (auto-delete timing and the consumed-OTP Advanced rows). */
@Singleton
class SettingsOtpPolicy @Inject constructor(private val settings: SettingsStore) : OtpPolicy {
    override suspend fun otpAutoDeleteAfterMillis(): Long? = when (settings.get(DakSettings.otpAutoDelete)) {
        "1h" -> HOUR
        "off" -> null
        else -> OtpTiming.DEFAULT_OTP_DELETE_MILLIS
    }

    override suspend fun consumedOtpMode(): ConsumedOtpMode = when (settings.get(DakSettings.consumedOtpHandling)) {
        "silentOnly" -> ConsumedOtpMode.SILENT_ONLY
        "normal" -> ConsumedOtpMode.NORMAL
        else -> ConsumedOtpMode.SILENT_AUTO_DELETE
    }

    override suspend fun consumedOtpDeleteAfterMillis(): Long =
        OtpTiming.clampConsumedDelay(settings.get(DakSettings.consumedOtpWindowMinutes) * 60_000L)
}
