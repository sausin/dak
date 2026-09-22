package app.dak.notifications

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.dak.ui.onboarding.BatteryOptimization
import app.dak.ui.onboarding.RuntimePermissions
import app.dak.ui.onboarding.SmsRole
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the user can do to fix a failed check. */
sealed interface ReliabilityFix {
    data object RequestSmsRole : ReliabilityFix
    data object RequestNotificationPermission : ReliabilityFix
    data object OpenAppNotificationSettings : ReliabilityFix
    data class OpenChannelSettings(val channelId: String) : ReliabilityFix
    data object RequestBatteryExemption : ReliabilityFix
    data object OpenAppDetails : ReliabilityFix
}

enum class ReliabilityCheckId { DEFAULT_SMS_APP, NOTIFICATION_PERMISSION, APP_NOTIFICATIONS, CHANNELS, BATTERY_OPTIMIZATION, BACKGROUND_RESTRICTED }

/** One check; [channelId] names the disabled channel for [ReliabilityCheckId.CHANNELS]. */
data class ReliabilityCheck(val id: ReliabilityCheckId, val ok: Boolean, val fix: ReliabilityFix?, val channelId: String? = null)

data class ReliabilityReport(val checks: List<ReliabilityCheck>) {
    /** True when anything could delay or drop a message notification. */
    val restricted: Boolean get() = checks.any { !it.ok }
    val failures: List<ReliabilityCheck> get() = checks.filter { !it.ok }
}

/**
 * Detects everything that silently breaks message notifications — the failure that sank SMS Organizer:
 * not being the default SMS app, POST_NOTIFICATIONS denied (13+), app or critical channels blocked,
 * battery optimisation on, and background usage restricted (9+).
 */
@Singleton
class ReliabilityChecker @Inject constructor(@ApplicationContext private val context: Context) {

    fun check(): ReliabilityReport {
        val manager = NotificationManagerCompat.from(context)
        val checks = buildList {
            add(ReliabilityCheck(ReliabilityCheckId.DEFAULT_SMS_APP, SmsRole.isDefault(context), ReliabilityFix.RequestSmsRole))
            val permission = RuntimePermissions.notificationsGranted(context)
            add(ReliabilityCheck(ReliabilityCheckId.NOTIFICATION_PERMISSION, permission, ReliabilityFix.RequestNotificationPermission))
            if (permission) {
                add(ReliabilityCheck(ReliabilityCheckId.APP_NOTIFICATIONS, manager.areNotificationsEnabled(), ReliabilityFix.OpenAppNotificationSettings))
            }
            val blocked = NotificationChannels.critical.firstOrNull { id ->
                val channel = manager.getNotificationChannelCompat(id)
                channel != null && channel.importance == NotificationManagerCompat.IMPORTANCE_NONE
            }
            add(
                ReliabilityCheck(
                    ReliabilityCheckId.CHANNELS,
                    ok = blocked == null,
                    fix = blocked?.let { ReliabilityFix.OpenChannelSettings(it) },
                    channelId = blocked,
                ),
            )
            add(ReliabilityCheck(ReliabilityCheckId.BATTERY_OPTIMIZATION, BatteryOptimization.isIgnoring(context), ReliabilityFix.RequestBatteryExemption))
            add(ReliabilityCheck(ReliabilityCheckId.BACKGROUND_RESTRICTED, !BatteryOptimization.isBackgroundRestricted(context), ReliabilityFix.OpenAppDetails))
        }
        return ReliabilityReport(checks)
    }
}
