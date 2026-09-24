package app.dak.ui.onboarding

import android.Manifest
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import androidx.core.content.ContextCompat
import app.dak.R

/** Default-SMS role checks and the request intent (RoleManager on Android 10+, ACTION_CHANGE_DEFAULT before). */
object SmsRole {
    fun isDefault(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            roles != null && roles.isRoleAvailable(RoleManager.ROLE_SMS) && roles.isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }

    /** Intent that shows the system "Set as default SMS app" dialog. Launch it for a result. */
    @Suppress("DEPRECATION")
    fun requestIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = requireNotNull(context.getSystemService(RoleManager::class.java))
            roles.createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        }
}

/** Runtime permissions asked right after Dak becomes the default SMS app. */
object RuntimePermissions {
    /** Notification permission (Android 13+ only). */
    val notifications: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    val contacts: List<String> = listOf(Manifest.permission.READ_CONTACTS)

    val phone: List<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(Manifest.permission.READ_PHONE_NUMBERS)
    }

    val all: List<String> get() = notifications + contacts + phone

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun allGranted(context: Context, permissions: List<String>): Boolean = permissions.all { granted(context, it) }

    /** True when notifications may be posted (always true below Android 13 unless the user blocked the app). */
    fun notificationsGranted(context: Context): Boolean = allGranted(context, notifications)
}

/** Battery-optimisation and background-restriction state; the biggest cause of missed OTPs on OEM ROMs. */
object BatteryOptimization {
    fun isIgnoring(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * True when battery optimisation cannot delay Dak: the user exempted it, or it is the default SMS app. The system
     * treats the default SMS app as a default active app (exempt from app standby and briefly allowlisted for every
     * incoming SMS / MMS), and Settings then shows its battery option as allowed and cannot be changed, while
     * [PowerManager.isIgnoringBatteryOptimizations] still reports false. Warning about it would be unfixable.
     */
    fun isExempt(context: Context): Boolean = exempt(isIgnoring(context), SmsRole.isDefault(context))

    /** The rule of [isExempt], pure for tests. */
    fun exempt(ignoringOptimizations: Boolean, defaultSmsApp: Boolean): Boolean = ignoringOptimizations || defaultSmsApp

    /**
     * The system list of apps and their battery-optimisation state, where the user finds Dak and chooses
     * "Don't optimise" / "Unrestricted" (the UI explains this first). No special permission needed.
     *
     * Dak deliberately does not use the direct `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt: it needs the
     * Play-restricted `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, and a default SMS app is already woken by the
     * system for every SMS_DELIVER / WAP_PUSH_DELIVER, so it does not qualify for that exemption. See
     * `docs/play-submission.md` ("Battery optimisation").
     */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** "Background usage restricted" (Settings → Battery → Restricted), Android 9+. */
    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.isBackgroundRestricted
    }
}

/** App details page (permissions, battery, notifications). */
fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))

/** Starts [intent] from any context; returns false when nothing handles it. */
fun Context.startSafely(intent: Intent): Boolean = try {
    if (this !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

/**
 * Manufacturer-specific background-killer guidance ("dontkillmyapp"-style). [settingsComponents] are tried in order;
 * the app details page is the fallback.
 */
data class OemGuidance(
    /** Brand name as printed on the phone (not translated). */
    val brand: String,
    /** Steps as `R.string` ids (strings_battery.xml): menu names follow the phone's own wording. */
    val steps: List<Int>,
    val settingsComponents: List<ComponentName>,
) {

    /** Opens the most specific settings page available. */
    fun open(context: Context): Boolean {
        for (component in settingsComponents) {
            if (context.startSafely(Intent().setComponent(component))) return true
        }
        return context.startSafely(appDetailsIntent(context))
    }

    companion object {
        /** Guidance for this device, or null for ROMs that behave (Pixel, Motorola, Nokia...). */
        fun forThisDevice(): OemGuidance? = forManufacturer(Build.MANUFACTURER.orEmpty())

        fun forManufacturer(manufacturer: String): OemGuidance? = when (manufacturer.lowercase()) { // lowercase(): Locale.ROOT
            "xiaomi", "redmi", "poco" -> OemGuidance(
                "Xiaomi",
                listOf(
                    R.string.oem_xiaomi_autostart,
                    R.string.oem_xiaomi_battery_saver,
                    R.string.oem_xiaomi_lock_recents,
                ),
                listOf(
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                ),
            )
            "oppo", "realme", "oneplus" -> OemGuidance(
                manufacturer.replaceFirstChar { it.uppercase() },
                listOf(
                    R.string.oem_oppo_battery_usage,
                    R.string.oem_oppo_optimise,
                    R.string.oem_oppo_lock_recents,
                ),
                listOf(
                    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
                ),
            )
            "vivo", "iqoo" -> OemGuidance(
                "Vivo",
                listOf(
                    R.string.oem_vivo_autostart,
                    R.string.oem_vivo_background_power,
                ),
                listOf(
                    ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                ),
            )
            "samsung" -> OemGuidance(
                "Samsung",
                listOf(
                    R.string.oem_samsung_sleeping_apps,
                    R.string.oem_samsung_never_sleeping,
                ),
                listOf(
                    ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                    ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                ),
            )
            "huawei", "honor" -> OemGuidance(
                manufacturer.replaceFirstChar { it.uppercase() },
                listOf(
                    R.string.oem_huawei_app_launch,
                ),
                listOf(
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ),
            )
            else -> null
        }
    }
}
