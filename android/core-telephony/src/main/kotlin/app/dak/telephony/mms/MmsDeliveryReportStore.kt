package app.dak.telephony.mms

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-recipient m-delivery-ind statuses of our group MMS, keyed by Message-ID (the provider's `st` column holds only
 * one value per message, so the aggregate is computed from these; see `DeliveryStatusMapping.aggregateMmsSt`).
 * Small SharedPreferences file; entries older than 30 days are pruned on write (reports never arrive that late).
 */
@Singleton
class MmsDeliveryReportStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("dak_mms_delivery_reports", Context.MODE_PRIVATE)

    /** Records [status] for [recipientKey] of [messageId] and returns every recipient's status so far. */
    @Synchronized
    fun record(messageId: String, recipientKey: String, status: Int, nowMillis: Long): Map<String, Int> {
        prune(nowMillis - MAX_AGE_MILLIS)
        val reports = MmsDeliveryReportCodec.decode(prefs.getString(messageId, null))?.second.orEmpty() +
            (MmsDeliveryReportCodec.cleanKey(recipientKey) to status)
        prefs.edit().putString(messageId, MmsDeliveryReportCodec.encode(nowMillis, reports)).commit()
        return reports
    }

    private fun prune(cutoffMillis: Long) {
        val stale = prefs.all.filter { (_, v) ->
            (MmsDeliveryReportCodec.decode(v as? String)?.first ?: 0L) < cutoffMillis
        }.keys
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach { remove(it) } }.commit()
    }

    private companion object {
        const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
