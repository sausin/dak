package app.dak.telephony

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Telephony-level user settings, read synchronously by receivers and workers (so they live in
 * SharedPreferences, not DataStore). The settings UI in :app writes them through this class.
 */
@Singleton
class TelephonySettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Normalise outgoing numbers to E.164 using the sending SIM's home country (default on). */
    var normalizeOutgoingNumbers: Boolean
        get() = prefs.getBoolean(KEY_NORMALIZE, true)
        set(value) = prefs.edit().putBoolean(KEY_NORMALIZE, value).apply()

    /** Download MMS automatically when they arrive (default on). */
    var autoDownloadMms: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, value).apply()

    /** Also auto-download while roaming (default off: roaming data is billed; the bubble offers a tap). */
    var autoDownloadMmsWhenRoaming: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD_ROAMING, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_ROAMING, value).apply()

    /** Request SMS delivery reports by default (default on). */
    var requestSmsDeliveryReports: Boolean
        get() = prefs.getBoolean(KEY_SMS_DELIVERY_REPORTS, true)
        set(value) = prefs.edit().putBoolean(KEY_SMS_DELIVERY_REPORTS, value).apply()

    /** Request MMS delivery reports (default off; many MMSCs ignore them). */
    var requestMmsDeliveryReports: Boolean
        get() = prefs.getBoolean(KEY_MMS_DELIVERY_REPORTS, false)
        set(value) = prefs.edit().putBoolean(KEY_MMS_DELIVERY_REPORTS, value).apply()

    /**
     * Send m-notifyresp-ind to the MMSC after a successful download (default off). The platform's download API
     * does not send it; most MMSCs do not require it, a few re-send notifications without it.
     */
    var sendMmsNotifyResponse: Boolean
        get() = prefs.getBoolean(KEY_MMS_NOTIFY_RESPONSE, false)
        set(value) = prefs.edit().putBoolean(KEY_MMS_NOTIFY_RESPONSE, value).apply()

    /** User-corrected home country (ISO 3166-1 alpha-2) for a SIM, overriding what the SIM reports. */
    fun homeCountryOverride(subId: Int): String? = prefs.getString(KEY_COUNTRY_PREFIX + subId, null)

    fun setHomeCountryOverride(subId: Int, countryIso: String?) {
        val edit = prefs.edit()
        if (countryIso.isNullOrBlank()) edit.remove(KEY_COUNTRY_PREFIX + subId) else edit.putString(KEY_COUNTRY_PREFIX + subId, countryIso.trim().lowercase())
        edit.apply()
    }

    private companion object {
        const val PREFS = "dak_telephony_settings"
        const val KEY_NORMALIZE = "normalize_outgoing"
        const val KEY_AUTO_DOWNLOAD = "mms_auto_download"
        const val KEY_AUTO_DOWNLOAD_ROAMING = "mms_auto_download_roaming"
        const val KEY_SMS_DELIVERY_REPORTS = "sms_delivery_reports"
        const val KEY_MMS_DELIVERY_REPORTS = "mms_delivery_reports"
        const val KEY_MMS_NOTIFY_RESPONSE = "mms_notify_response"
        const val KEY_COUNTRY_PREFIX = "home_country_"
    }
}
