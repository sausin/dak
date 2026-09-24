package app.dak.telephony.mms

import android.content.Context
import android.util.Log
import app.dak.mms.pdu.MessageType
import app.dak.mms.pdu.MmsClientTransactions
import app.dak.telephony.TelephonySettings
import app.dak.telephony.carrier.CarrierConfigRepository
import app.dak.telephony.carrier.ReportPolicy
import app.dak.telephony.internal.TAG
import app.dak.telephony.internal.int
import app.dak.telephony.internal.long
import app.dak.telephony.internal.safeQuery
import app.dak.telephony.internal.string
import app.dak.telephony.provider.MmsAddrColumns
import app.dak.telephony.provider.MmsColumns
import app.dak.telephony.provider.ProviderUris
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sends m-read-rec-ind (OMA MMS-CTR read report) for received MMS whose sender asked for one (X-Mms-Read-Report =
 * Yes, stored as `rr`), when they are first marked read. Off unless the user turned on "Read receipts for MMS"
 * ([TelephonySettings.sendMmsReadReceipts]) and the SIM's carrier supports MMS read reports ([ReportPolicy]); the
 * per-message rules (a Message-ID, a personal sender, not an advertisement) are
 * [MmsClientTransactions.readReceipt].
 *
 * Used by the provider writer around every "mark read" (conversation opened, notification action, "mark all
 * read"): [pending] is called *before* the rows are marked read (only unread rows qualify, so each message is
 * reported once), [send] after. Best effort: a failure is logged, never retried, and never blocks marking read.
 */
@Singleton
class MmsReadReceipts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: TelephonySettings,
    private val carrierConfig: CarrierConfigRepository,
    private val sendManager: MmsSendManager,
) {
    /** One received message owed a read report. */
    class Pending internal constructor(
        val id: Long,
        internal val messageId: String?,
        internal val messageClass: String?,
        internal val subId: Int,
    )

    private val resolver get() = context.contentResolver
    private val mutex = Mutex()

    /** Provider ids already reported in this process (guards against two concurrent "mark read" calls). */
    private val reported = LinkedHashSet<Long>()

    /** Unread received MMS in [threadId] that ask for a read report; empty unless read receipts are on. */
    suspend fun pendingInThread(threadId: Long): List<Pending> =
        if (!settings.sendMmsReadReceipts) emptyList() else query("${MmsColumns.THREAD_ID} = ?", arrayOf(threadId.toString()))

    /** The unread received MMS [id] if it asks for a read report; empty unless read receipts are on. */
    suspend fun pendingFor(id: Long): List<Pending> =
        if (!settings.sendMmsReadReceipts) emptyList() else query("${MmsColumns.ID} = ?", arrayOf(id.toString()))

    /** Sends the read reports for [pending] (now marked read). */
    suspend fun send(pending: List<Pending>) {
        if (pending.isEmpty() || !settings.sendMmsReadReceipts) return
        val nowSeconds = System.currentTimeMillis() / 1000
        for (p in pending) {
            val fresh = mutex.withLock {
                if (p.id in reported) return@withLock false
                reported += p.id
                while (reported.size > MAX_REMEMBERED) reported.remove(reported.first())
                true
            }
            if (!fresh) continue
            if (!ReportPolicy.sendMmsReadReport(settings.sendMmsReadReceipts, carrierConfig.forSubscription(p.subId))) continue
            val originator = withContext(Dispatchers.IO) { sender(p.id) }
            val pdu = MmsClientTransactions.readReceipt(
                messageId = p.messageId,
                originator = originator,
                readReportRequested = true,
                messageClass = p.messageClass,
                nowSeconds = nowSeconds,
            ) ?: continue
            Log.i(TAG, "sending MMS read report")
            sendManager.sendClientPdu(pdu, p.subId)
        }
    }

    private suspend fun query(selection: String, args: Array<String>): List<Pending> = withContext(Dispatchers.IO) {
        val out = ArrayList<Pending>()
        // All columns: few rows match, and OEM providers without `sub_id` must not fail the query.
        val where = "$selection AND ${MmsColumns.READ} = 0 AND ${MmsColumns.MESSAGE_BOX} = ${MmsColumns.BOX_INBOX}" +
            " AND ${MmsColumns.MESSAGE_TYPE} = ${MessageType.RETRIEVE_CONF} AND ${MmsColumns.READ_REPORT} = $YES"
        try {
            resolver.safeQuery(ProviderUris.MMS, null, where, args)?.use { c ->
                while (c.moveToNext() && out.size < MAX_PER_CALL) {
                    out += Pending(
                        id = c.long(MmsColumns.ID),
                        messageId = c.string(MmsColumns.MESSAGE_ID),
                        messageClass = c.string(MmsColumns.MESSAGE_CLASS),
                        subId = c.int(MmsColumns.SUBSCRIPTION_ID, -1),
                    )
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "read report query failed: ${e.javaClass.simpleName}")
        }
        out
    }

    /** The From address of a stored message, or null (hidden sender / no row). */
    private fun sender(id: Long): String? =
        resolver.safeQuery(ProviderUris.mmsAddresses(id))?.use { c ->
            while (c.moveToNext()) {
                if (c.int(MmsAddrColumns.TYPE) != MmsAddrColumns.TYPE_FROM) continue
                val address = c.string(MmsAddrColumns.ADDRESS)?.trim().orEmpty()
                if (address.isNotEmpty() && address != MmsAddrColumns.INSERT_ADDRESS_TOKEN) return@use address
            }
            null
        }

    private companion object {
        const val YES = 0x80

        /** A burst of reports is capped: marking a huge old thread read must not send hundreds of MMS PDUs. */
        const val MAX_PER_CALL = 20
        const val MAX_REMEMBERED = 512
    }
}
