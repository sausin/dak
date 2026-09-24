package app.dak.i18n

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import app.dak.DakApplication
import app.dak.R
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * Per-app language ("app language"), docs/i18n.md.
 *
 * - Android 13+ (API 33): the framework owns the choice. [set] writes [LocaleManager.setApplicationLocales]; the
 *   system persists it, recreates activities and also offers it in Settings → Apps → Dak → Language, listing the
 *   languages in `res/xml/locales_config.xml` (`android:localeConfig` in the manifest).
 * - Android 8–12: no framework support, and Dak's activities are not AppCompat activities (MainActivity is a
 *   FragmentActivity for androidx.biometric), so AppCompat's `setApplicationLocales` + `autoStoreLocales` backport
 *   would store the choice but never apply it. Dak therefore stores the tag itself ([PREFS]) and applies it the way
 *   AppCompat does internally: an override configuration on every activity ([applyTo], called from
 *   `attachBaseContext`) and the application's own resources ([applyToApplication], for notifications and other
 *   text built outside an activity). [Locale.setDefault] is deliberately left alone: region logic (e.g. the phone
 *   number country fallback) reads the default locale's country, which must stay the phone's.
 * - Upgrading a phone from 12 to 13+ carries the stored choice over to the framework once ([migrateToFramework]).
 *
 * The language list comes from locales_config.xml, so shipping a translation is: add `values-xx/`, add
 * `<locale android:name="xx"/>`, done.
 */
object AppLocales {
    private const val TAG = "DakLocales"
    private const val PREFS = "dak_app_locale"
    private const val KEY_TAG = "tag"
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** BCP 47 tags declared in res/xml/locales_config.xml, in file order (debug builds add pseudo-locales). */
    fun supported(context: Context): List<String> = try {
        val parser = context.resources.getXml(R.xml.locales_config)
        try {
            buildList {
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                        parser.getAttributeValue(ANDROID_NS, "name")?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
        } finally {
            parser.close()
        }
    } catch (e: Exception) {
        // A malformed config must never break Settings: offer only "System default".
        Log.w(TAG, "locales_config unreadable: ${e.javaClass.simpleName}")
        emptyList()
    }

    /** The chosen app language tag, or null for "System default". */
    fun current(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            frameworkLocales(context)?.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
        } else {
            storedTag(context)
        }

    /**
     * Sets the app language ([tag] null = follow the system). On 13+ the system recreates activities itself; below,
     * [activity] (the caller) is recreated and the application's resources are updated in place.
     */
    fun set(context: Context, tag: String?, activity: Activity? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setFramework(context, tag)
            return
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (tag == null) remove(KEY_TAG) else putString(KEY_TAG, tag)
        }.commit() // synchronous: the recreated activity reads it in attachBaseContext
        (context.applicationContext as? Application)?.let(::applyToApplication)
        // No configuration change reaches the application for this: rename the notification channels here.
        (context.applicationContext as? DakApplication)?.notificationChannels?.onLocaleMaybeChanged()
        activity?.recreate()
    }

    /**
     * For `attachBaseContext` of every activity (below Android 13): applies the stored language as an override
     * configuration. Survives configuration changes the activity handles itself (e.g. `uiMode`), like AppCompat.
     */
    fun applyTo(activity: Activity, base: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val tag = storedTag(base) ?: return
        val override = Configuration().apply { setLocales(LocaleList.forLanguageTags(tag)) }
        try {
            activity.applyOverrideConfiguration(override)
        } catch (e: IllegalStateException) {
            // Resources were already created (should not happen from attachBaseContext): keep the system language.
            Log.w(TAG, "could not apply app language")
        }
    }

    /**
     * Below Android 13: points the application's resources at the stored language (or back at the system's), so
     * notifications and other text built from the application context follow the app language. Call from
     * `Application.onCreate` and `onConfigurationChanged` (a system configuration change resets them).
     */
    @Suppress("DEPRECATION") // Resources.updateConfiguration: the only way to retarget application resources < 33.
    fun applyToApplication(app: Application) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val res = app.resources
        val wanted = storedTag(app)?.let { LocaleList.forLanguageTags(it) } ?: Resources.getSystem().configuration.locales
        if (res.configuration.locales == wanted) return
        val config = Configuration(res.configuration).apply { setLocales(wanted) }
        res.updateConfiguration(config, res.displayMetrics)
    }

    /** Once on Android 13+: hands a choice stored by an older Android version to the framework, then forgets it. */
    fun migrateToFramework(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val stored = storedTag(context) ?: return
        if (frameworkLocales(context)?.isEmpty != false) setFramework(context, stored)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TAG).apply()
    }

    /** Label of the current choice in the current language: "System default" or the language's own name. */
    fun currentLabel(context: Context): String =
        current(context)?.let { displayName(it) } ?: context.getString(R.string.language_system_default)

    /**
     * A language's name in that language ("हिन्दी", "English"), which is how people find their language in a list
     * they may not be able to read.
     */
    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /**
     * [id] in every shipped language ([supported]) and the current one, distinct. For text other phones may send
     * back to this one in their own language, e.g. the auto-forward marker the loop guard must recognise.
     */
    fun stringInEveryLanguage(context: Context, @StringRes id: Int): List<String> {
        val base = context.resources.configuration
        val locales = supported(context).map(Locale::forLanguageTag) + primary(context.resources)
        return locales.mapNotNull { locale ->
            runCatching {
                context.createConfigurationContext(Configuration(base).apply { setLocale(locale) }).getString(id)
            }.getOrNull()
        }.distinct()
    }

    /** The resources' primary locale (app language when set, else the system's). */
    fun primary(resources: Resources): Locale = resources.configuration.locales[0] ?: Locale.getDefault()

    private fun storedTag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)?.takeIf { it.isNotBlank() }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun frameworkLocales(context: Context): LocaleList? =
        context.getSystemService(LocaleManager::class.java)?.applicationLocales

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setFramework(context: Context, tag: String?) {
        context.getSystemService(LocaleManager::class.java)?.applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }
}
