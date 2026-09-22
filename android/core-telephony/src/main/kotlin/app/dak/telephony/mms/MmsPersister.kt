package app.dak.telephony.mms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log
import app.dak.mms.pdu.ContentType
import app.dak.mms.pdu.MessageType
import app.dak.mms.pdu.MmsCharset
import app.dak.mms.pdu.MmsMessageBuilder
import app.dak.mms.pdu.NotificationInd
import app.dak.mms.pdu.PduPart
import app.dak.mms.pdu.RetrieveConf
import app.dak.mms.pdu.SendReq
import app.dak.telephony.SimRepository
import app.dak.telephony.internal.TAG
import app.dak.telephony.internal.insertTolerant
import app.dak.telephony.internal.int
import app.dak.telephony.internal.long
import app.dak.telephony.internal.safeQuery
import app.dak.telephony.internal.string
import app.dak.telephony.internal.toContentValues
import app.dak.telephony.internal.updateTolerant
import app.dak.telephony.number.TelephonyNumberNormalizer
import app.dak.telephony.provider.MmsAddrColumns
import app.dak.telephony.provider.MmsColumns
import app.dak.telephony.provider.MmsPartColumns
import app.dak.telephony.provider.ProviderUris
import app.dak.telephony.provider.TelephonyProviderWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Header fields of a stored outgoing MMS, for re-sends. */
private class OutgoingHeader(
    val transactionId: String?,
    val subject: String?,
    val subId: Int,
    val deliveryReport: Boolean,
    val readReport: Boolean,
)

/** What the download pipeline needs to know about a stored notification-ind row. */
data class MmsNotificationInfo(
    val id: Long,
    val messageType: Int,
    val contentLocation: String?,
    val transactionId: String?,
    val subId: Int,
    /** Absolute expiry in epoch seconds, 0 when unknown. */
    val expirySeconds: Long,
)

/**
 * Writes MMS into the Telephony provider (`pdu` row, then `part` rows under `content://mms/<id>/part`, then
 * `addr` rows under `content://mms/<id>/addr`) and reads back what re-sends and downloads need. A partially
 * written message is deleted rather than left half-formed.
 */
@Singleton
class MmsPersister @Inject constructor(
    @ApplicationContext private val context: Context,
    private val writer: TelephonyProviderWriter,
    private val sims: SimRepository,
    private val normalizer: TelephonyNumberNormalizer,
) {
    private val resolver get() = context.contentResolver

    /** An existing notification-ind for the same content location / transaction (duplicate WAP push). */
    suspend fun findNotification(contentLocation: String, transactionId: String?): Long? = withContext(Dispatchers.IO) {
        val selection = StringBuilder("${MmsColumns.MESSAGE_TYPE} = ${MessageType.NOTIFICATION_IND} AND ${MmsColumns.CONTENT_LOCATION} = ?")
        val args = ArrayList<String>().apply { add(contentLocation) }
        if (transactionId != null) {
            selection.append(" AND ${MmsColumns.TRANSACTION_ID} = ?")
            args.add(transactionId)
        }
        resolver.safeQuery(ProviderUris.MMS, arrayOf(MmsColumns.ID), selection.toString(), args.toTypedArray())?.use { c ->
            if (c.moveToFirst()) c.long(MmsColumns.ID) else null
        }
    }

    suspend fun insertNotification(n: NotificationInd, subId: Int): Long? {
        val threadId = writer.threadIdFor(setOf(n.from ?: UNKNOWN_SENDER))
        return withContext(Dispatchers.IO) {
            val row = MmsProviderMapping.notificationRow(n, subId, threadId, System.currentTimeMillis())
            val id = insertPdu(ProviderUris.MMS_INBOX, row) ?: return@withContext null
            MmsProviderMapping.notificationAddresses(n).forEach { insertAddr(id, it) }
            id
        }
    }

    suspend fun insertRetrieved(r: RetrieveConf, subId: Int): Long? {
        val recipients = MmsProviderMapping.threadRecipients(r.from, r.to, r.cc) { isOwnNumber(it, subId) }
        val threadId = writer.threadIdFor(recipients.ifEmpty { setOf(r.from ?: UNKNOWN_SENDER) })
        return withContext(Dispatchers.IO) {
            val row = MmsProviderMapping.retrievedRow(r, subId, threadId, System.currentTimeMillis())
            val id = insertPdu(ProviderUris.MMS_INBOX, row) ?: return@withContext null
            if (!insertParts(id, r.parts)) {
                deleteQuietly(id)
                return@withContext null
            }
            MmsProviderMapping.retrievedAddresses(r).forEach { insertAddr(id, it) }
            id
        }
    }

    suspend fun insertOutgoing(req: SendReq, subId: Int, threadId: Long, encodedSize: Int): Long? = withContext(Dispatchers.IO) {
        val row = MmsProviderMapping.outgoingRow(req, subId, threadId, System.currentTimeMillis(), encodedSize)
        val id = insertPdu(ProviderUris.MMS_OUTBOX, row) ?: return@withContext null
        if (!insertParts(id, req.parts)) {
            deleteQuietly(id)
            return@withContext null
        }
        MmsProviderMapping.outgoingAddresses(req).forEach { insertAddr(id, it) }
        id
    }

    suspend fun notificationInfo(id: Long): MmsNotificationInfo? = withContext(Dispatchers.IO) {
        resolver.safeQuery(ProviderUris.mms(id))?.use { c ->
            if (!c.moveToFirst()) return@use null
            MmsNotificationInfo(
                id = id,
                messageType = c.int(MmsColumns.MESSAGE_TYPE),
                contentLocation = c.string(MmsColumns.CONTENT_LOCATION),
                transactionId = c.string(MmsColumns.TRANSACTION_ID),
                subId = c.int(MmsColumns.SUBSCRIPTION_ID, -1),
                expirySeconds = c.long(MmsColumns.EXPIRY),
            )
        }
    }

    /** Every notification-ind still waiting in the inbox (for recovery after reboot). */
    suspend fun pendingNotifications(): List<MmsNotificationInfo> = withContext(Dispatchers.IO) {
        val out = ArrayList<MmsNotificationInfo>()
        resolver.safeQuery(ProviderUris.MMS, null, "${MmsColumns.MESSAGE_TYPE} = ${MessageType.NOTIFICATION_IND}")?.use { c ->
            while (c.moveToNext()) {
                out += MmsNotificationInfo(
                    id = c.long(MmsColumns.ID),
                    messageType = MessageType.NOTIFICATION_IND,
                    contentLocation = c.string(MmsColumns.CONTENT_LOCATION),
                    transactionId = c.string(MmsColumns.TRANSACTION_ID),
                    subId = c.int(MmsColumns.SUBSCRIPTION_ID, -1),
                    expirySeconds = c.long(MmsColumns.EXPIRY),
                )
            }
        }
        out
    }

    suspend fun delete(id: Long) {
        withContext(Dispatchers.IO) { deleteQuietly(id) }
    }

    suspend fun setBox(id: Long, msgBox: Int) {
        withContext(Dispatchers.IO) {
            resolver.updateTolerant(ProviderUris.mms(id), ContentValues().apply { put(MmsColumns.MESSAGE_BOX, msgBox) })
        }
    }

    suspend fun markSent(id: Long, messageId: String?, responseStatus: Int?) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MmsColumns.MESSAGE_BOX, MmsColumns.BOX_SENT)
                messageId?.let { put(MmsColumns.MESSAGE_ID, it) }
                responseStatus?.let { put(MmsColumns.RESPONSE_STATUS, it) }
            }
            resolver.updateTolerant(ProviderUris.mms(id), values)
        }
    }

    suspend fun markFailed(id: Long, responseStatus: Int?) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MmsColumns.MESSAGE_BOX, MmsColumns.BOX_FAILED)
                responseStatus?.let { put(MmsColumns.RESPONSE_STATUS, it) }
            }
            resolver.updateTolerant(ProviderUris.mms(id), values)
        }
    }

    /** m-delivery-ind: sets `st` on our sent message with that Message-ID. */
    suspend fun applyDeliveryReport(messageId: String, status: Int) {
        updateByMessageId(messageId, ContentValues().apply { put(MmsColumns.STATUS, status) })
    }

    /** m-read-orig-ind: sets `read_status` on our sent message with that Message-ID. */
    suspend fun applyReadReport(messageId: String, readStatus: Int) {
        updateByMessageId(messageId, ContentValues().apply { put(MmsColumns.READ_STATUS, readStatus) })
    }

    /**
     * Rebuilds the m-send-req of a stored outgoing message (for retries), with its sub id. Parts are read back
     * from the provider, so the original attachment files need not exist any more.
     */
    suspend fun loadOutgoing(id: Long): Pair<SendReq, Int>? = withContext(Dispatchers.IO) {
        val header = resolver.safeQuery(ProviderUris.mms(id))?.use { c ->
            if (!c.moveToFirst()) return@use null
            OutgoingHeader(
                transactionId = c.string(MmsColumns.TRANSACTION_ID),
                subject = c.string(MmsColumns.SUBJECT),
                subId = c.int(MmsColumns.SUBSCRIPTION_ID, -1),
                deliveryReport = c.int(MmsColumns.DELIVERY_REPORT) == YES,
                readReport = c.int(MmsColumns.READ_REPORT) == YES,
            )
        } ?: return@withContext null

        val to = ArrayList<String>()
        val cc = ArrayList<String>()
        val bcc = ArrayList<String>()
        resolver.safeQuery(ProviderUris.mmsAddresses(id))?.use { c ->
            while (c.moveToNext()) {
                val address = c.string(MmsAddrColumns.ADDRESS)?.trim().orEmpty()
                if (address.isEmpty() || address == MmsAddrColumns.INSERT_ADDRESS_TOKEN) continue
                when (c.int(MmsAddrColumns.TYPE)) {
                    MmsAddrColumns.TYPE_TO -> to += address
                    MmsAddrColumns.TYPE_CC -> cc += address
                    MmsAddrColumns.TYPE_BCC -> bcc += address
                }
            }
        }
        if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) return@withContext null

        val parts = ArrayList<Pair<Int, PduPart>>()
        resolver.safeQuery(ProviderUris.mmsParts(id))?.use { c ->
            while (c.moveToNext()) {
                val partId = c.long(MmsPartColumns.ID)
                val mime = c.string(MmsPartColumns.CONTENT_TYPE)?.lowercase() ?: continue
                val text = c.string(MmsPartColumns.TEXT)
                val charset = c.int(MmsPartColumns.CHARSET, 0).takeIf { it > 0 }
                val data = if (text != null) {
                    text.toByteArray(Charsets.UTF_8)
                } else {
                    readPart(partId) ?: continue
                }
                val name = c.string(MmsPartColumns.NAME)
                val part = PduPart(
                    contentType = ContentType(
                        mimeType = mime,
                        charset = if (text != null) MmsCharset.UTF_8 else charset,
                        name = name,
                    ),
                    data = data,
                    contentId = c.string(MmsPartColumns.CONTENT_ID),
                    contentLocation = c.string(MmsPartColumns.CONTENT_LOCATION),
                )
                parts += c.int(MmsPartColumns.SEQ) to part
            }
        }
        val ordered = parts.sortedBy { it.first }.map { it.second }
        val smil = ordered.firstOrNull { it.contentType.mimeType == ContentType.SMIL }
        val contentType = if (smil != null) {
            ContentType.multipartRelated(start = smil.contentId ?: MmsMessageBuilder.SMIL_CONTENT_ID)
        } else {
            ContentType(ContentType.MULTIPART_MIXED)
        }
        val req = SendReq(
            transactionId = header.transactionId ?: MmsMessageBuilder.newTransactionId(),
            to = to,
            contentType = contentType,
            parts = ordered,
            cc = cc,
            bcc = bcc,
            subject = header.subject?.takeIf { it.isNotEmpty() },
            dateSeconds = System.currentTimeMillis() / 1000,
            deliveryReport = header.deliveryReport,
            readReport = header.readReport,
        )
        req to header.subId
    }

    // --- Internals ------------------------------------------------------------------------------------------

    private fun insertPdu(uri: Uri, row: Map<String, Any>): Long? =
        resolver.insertTolerant(uri, row.toContentValues(), MmsColumns.SUBSCRIPTION_ID)?.let { ContentUris.parseId(it) }

    private fun insertParts(id: Long, parts: List<PduPart>): Boolean {
        for (row in MmsProviderMapping.partRows(parts)) {
            val values = row.values.toContentValues()
            values.put(MmsPartColumns.MSG_ID, id)
            row.text?.let { values.put(MmsPartColumns.TEXT, it) }
            val partUri = try {
                resolver.insert(ProviderUris.mmsParts(id), values)
            } catch (e: Exception) {
                Log.w(TAG, "part insert failed: ${e.javaClass.simpleName}")
                null
            } ?: return false
            val data = row.data ?: continue
            val written = try {
                resolver.openOutputStream(partUri)?.use { it.write(data) } != null
            } catch (e: Exception) {
                Log.w(TAG, "part data write failed: ${e.javaClass.simpleName}")
                false
            }
            if (!written) return false
        }
        return true
    }

    private fun insertAddr(id: Long, addr: AddrRow) {
        val values = ContentValues().apply {
            put(MmsAddrColumns.ADDRESS, addr.address)
            put(MmsAddrColumns.TYPE, addr.type)
            put(MmsAddrColumns.CHARSET, MmsCharset.UTF_8)
            put(MmsAddrColumns.MSG_ID, id)
        }
        try {
            resolver.insert(ProviderUris.mmsAddresses(id), values)
        } catch (e: Exception) {
            Log.w(TAG, "addr insert failed: ${e.javaClass.simpleName}")
        }
    }

    private fun readPart(partId: Long): ByteArray? = try {
        resolver.openInputStream(ProviderUris.mmsPart(partId))?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    private fun deleteQuietly(id: Long) {
        try {
            resolver.delete(ProviderUris.mms(id), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "mms delete failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun updateByMessageId(messageId: String, values: ContentValues) {
        withContext(Dispatchers.IO) {
            try {
                resolver.update(ProviderUris.MMS, values, "${MmsColumns.MESSAGE_ID} = ?", arrayOf(messageId))
            } catch (e: Exception) {
                Log.w(TAG, "report update failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** True when [address] is one of our own SIM numbers (compared by normalised match key). */
    private fun isOwnNumber(address: String, subId: Int): Boolean {
        val key = normalizer.matchKey(address, subId)
        return sims.sims.value.any { sim ->
            val number = sim.number?.takeIf { it.isNotBlank() } ?: return@any false
            normalizer.matchKey(number, sim.subId) == key
        }
    }

    private companion object {
        const val UNKNOWN_SENDER = "Unknown"
        const val YES = 0x80
    }
}
