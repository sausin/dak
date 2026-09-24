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

    /**
     * "Read receipts for MMS" (default **off**, for privacy). When on, and the SIM's carrier supports MMS read reports
     * (`enableMMSReadReports`, see [app.dak.telephony.carrier.ReportPolicy]), received MMS whose sender asked for a
     * read report get an m-read-rec-ind when first marked read, and outgoing MMS ask for one. Mirrored from the
     * `simsSending.mmsReadReceipts` setting by the app.
     */
    var sendMmsReadReceipts: Boolean
        get() = prefs.getBoolean(KEY_MMS_READ_RECEIPTS, false)
        set(value) = prefs.edit().putBoolean(KEY_MMS_READ_RECEIPTS, value).apply()

    /**
     * X-Mms-Report-Allowed on our m-notifyresp-ind / m-acknowledge-ind: whether the MMSC may tell a sender their MMS
     * was delivered to us (default on, as AOSP). Mirrored from the `simsSending.mmsDeliveryToSenders` setting.
     */
    var allowMmsDeliveryReportsToSenders: Boolean
        get() = prefs.getBoolean(KEY_MMS_REPORT_ALLOWED, true)
        set(value) = prefs.edit().putBoolean(KEY_MMS_REPORT_ALLOWED, value).apply()

    /**
     * Answer the MMSC as OMA MMS-CTR expects (default on, as AOSP does): m-notifyresp-ind Retrieved after a download,
     * Deferred when a download waits for a tap, m-acknowledge-ind after a deferred download, Unrecognised for an
     * unreadable notification. The platform's download API sends none of these; without them some MMSCs re-send
     * notifications or keep messages until they expire. Where they go (MMSC or the notification's URL) follows the
     * carrier's `enabledNotifyWapMMSC` config. Kept as a switch for carriers whose MMSC misbehaves with them.
     */
    var sendMmsNotifyResponse: Boolean
        get() = prefs.getBoolean(KEY_MMS_NOTIFY_RESPONSE, true)
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
        const val KEY_MMS_READ_RECEIPTS = "mms_read_receipts"
        const val KEY_MMS_REPORT_ALLOWED = "mms_report_allowed"
        const val KEY_MMS_NOTIFY_RESPONSE = "mms_notify_response"
        const val KEY_COUNTRY_PREFIX = "home_country_"
    }
}
