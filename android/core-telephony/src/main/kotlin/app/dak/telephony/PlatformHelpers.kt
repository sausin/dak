package app.dak.telephony

import android.app.AlarmManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsMessage

/**
 * Default-SMS-app role helpers. Android 10+: `RoleManager.ROLE_SMS`; below: `Telephony.Sms.getDefaultSmsPackage`
 * and `ACTION_CHANGE_DEFAULT`. Request SMS permissions only after the role is granted (Play policy).
 */
object DefaultSmsRole {
    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_SMS)) return roles.isRoleHeld(RoleManager.ROLE_SMS)
        }
        return Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }

    /**
     * Intent that asks the user to make this app the default SMS app; launch it with
     * `startActivityForResult` / an ActivityResultLauncher. Null when the role is unavailable on this device.
     */
    fun requestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java) ?: return null
            return if (roles.isRoleAvailable(RoleManager.ROLE_SMS)) roles.createRequestRoleIntent(RoleManager.ROLE_SMS) else null
        }
        return Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
    }
}

/** Segment count for the composer, from the platform's own encoder rules (`SmsMessage.calculateLength`). */
data class SmsSegments(
    val segments: Int,
    val codeUnitsUsed: Int,
    val codeUnitsRemaining: Int,
    /** True when the text needs UCS-2 (16-bit) encoding, which roughly halves the characters per segment. */
    val isUnicode: Boolean,
)

object SmsSegmentCounter {
    fun count(text: CharSequence): SmsSegments {
        val r = SmsMessage.calculateLength(text, false)
        return SmsSegments(
            segments = r.getOrElse(0) { 1 },
            codeUnitsUsed = r.getOrElse(1) { text.length },
            codeUnitsRemaining = r.getOrElse(2) { 0 },
            isUnicode = r.getOrElse(3) { SmsMessage.ENCODING_7BIT } == SmsMessage.ENCODING_16BIT,
        )
    }
}

/**
 * Exact-alarm permission helpers for scheduled sends. Android 12+ gates exact alarms behind
 * SCHEDULE_EXACT_ALARM (user-revocable, and denied by default on 14+ for new installs).
 */
object ExactAlarms {
    fun canSchedule(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarms.canScheduleExactAlarms()
    }

    /** Settings screen where the user grants "Alarms & reminders" for this app; null below Android 12. */
    fun settingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
    }
}
