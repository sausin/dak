package app.dak.telephony.provider

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.util.Log
import app.dak.core.model.Message
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import app.dak.mms.pdu.ContentType
import app.dak.mms.pdu.MessageType
import app.dak.mms.pdu.MmsCharset
import app.dak.mms.pdu.MmsLimits
import app.dak.mms.pdu.MmsSafety
import app.dak.mms.pdu.MmsStatus
import app.dak.telephony.OutgoingStatus
import app.dak.telephony.ProviderWriter
import app.dak.telephony.internal.TAG
import app.dak.telephony.internal.insertTolerant
import app.dak.telephony.internal.updateTolerant
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fields of an incoming SMS as delivered by the radio, written verbatim. */
data class IncomingSms(
    val address: String,
    val body: String,
    val dateSentMillis: Long,
    val dateMillis: Long,
    val subId: Int,
    val protocol: Int? = null,
    val serviceCenter: String? = null,
    val replyPathPresent: Boolean? = null,
    val subject: String? = null,
)

/**
 * [ProviderWriter] over the Telephony provider. Writes are verbatim and immediate; every write tolerates OEM
 * providers without a `sub_id` column (retrying without it) and never throws.
 */
@Singleton
class TelephonyProviderWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProviderWriter {

    private val resolver get() = context.contentResolver

    override suspend fun insertIncomingSms(
        address: String,
        body: String,
        dateSentMillis: Long,
        dateMillis: Long,
        subId: Int,
    ): MessageKey? = insertIncoming(IncomingSms(address, body, dateSentMillis, dateMillis, subId))

    /** Inbox insert with the extra PDU fields the receiver has (protocol, service centre, reply path). */
    suspend fun insertIncoming(sms: IncomingSms): MessageKey? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(SmsColumns.ADDRESS, sms.address)
            put(SmsColumns.BODY, sms.body)
            put(SmsColumns.DATE, sms.dateMillis)
            put(SmsColumns.DATE_SENT, sms.dateSentMillis)
            put(SmsColumns.READ, 0)
            put(SmsColumns.SEEN, 0)
            put(SmsColumns.TYPE, SmsColumns.TYPE_INBOX)
            sms.protocol?.let { put(SmsColumns.PROTOCOL, it) }
            sms.serviceCenter?.let { put(SmsColumns.SERVICE_CENTER, it) }
            sms.replyPathPresent?.let { put(SmsColumns.REPLY_PATH_PRESENT, if (it) 1 else 0) }
            sms.subject?.let { put(SmsColumns.SUBJECT, it) }
            if (sms.subId >= 0) put(SmsColumns.SUBSCRIPTION_ID, sms.subId)
        }
        resolver.insertTolerant(ProviderUris.SMS_INBOX, values, SmsColumns.SUBSCRIPTION_ID)
            ?.let { MessageKey(MessageKind.SMS, ContentUris.parseId(it)) }
    }

    override suspend fun insertOutgoingSms(address: String, body: String, subId: Int, threadId: Long?): MessageKey? =
        insertOutgoing(address, body, subId, threadId, deliveryReportRequested = false)

    /** Outbox insert; `status` starts PENDING when a delivery report was requested, NONE otherwise. */
    suspend fun insertOutgoing(
        address: String,
        body: String,
        subId: Int,
        threadId: Long?,
        deliveryReportRequested: Boolean,
    ): MessageKey? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(SmsColumns.ADDRESS, address)
            put(SmsColumns.BODY, body)
            put(SmsColumns.DATE, System.currentTimeMillis())
            put(SmsColumns.READ, 1)
            put(SmsColumns.SEEN, 1)
            put(SmsColumns.TYPE, SmsColumns.TYPE_OUTBOX)
            put(SmsColumns.STATUS, if (deliveryReportRequested) SmsColumns.STATUS_PENDING else SmsColumns.STATUS_NONE)
            if (threadId != null && threadId > 0) put(SmsColumns.THREAD_ID, threadId)
            if (subId >= 0) put(SmsColumns.SUBSCRIPTION_ID, subId)
        }
        resolver.insertTolerant(ProviderUris.SMS_OUTBOX, values, SmsColumns.SUBSCRIPTION_ID)
            ?.let { MessageKey(MessageKind.SMS, ContentUris.parseId(it)) }
    }

    override suspend fun markSmsStatus(key: MessageKey, status: OutgoingStatus) {
        withContext(Dispatchers.IO) {
            when (key.kind) {
                MessageKind.SMS -> {
                    val values = ContentValues()
                    when (status) {
                        OutgoingStatus.QUEUED -> values.put(SmsColumns.TYPE, SmsColumns.TYPE_QUEUED)
                        OutgoingStatus.SENDING -> values.put(SmsColumns.TYPE, SmsColumns.TYPE_OUTBOX)
                        OutgoingStatus.SENT -> values.put(SmsColumns.TYPE, SmsColumns.TYPE_SENT)
                        OutgoingStatus.DELIVERED -> {
                            values.put(SmsColumns.TYPE, SmsColumns.TYPE_SENT)
                            values.put(SmsColumns.STATUS, SmsColumns.STATUS_COMPLETE)
                        }
                        OutgoingStatus.FAILED -> values.put(SmsColumns.TYPE, SmsColumns.TYPE_FAILED)
                    }
                    resolver.updateTolerant(ProviderUris.sms(key.providerId), values)
                }
                MessageKind.MMS -> {
                    val values = ContentValues()
                    when (status) {
                        OutgoingStatus.QUEUED, OutgoingStatus.SENDING -> values.put(MmsColumns.MESSAGE_BOX, MmsColumns.BOX_OUTBOX)
                        OutgoingStatus.SENT -> values.put(MmsColumns.MESSAGE_BOX, MmsColumns.BOX_SENT)
                        OutgoingStatus.DELIVERED -> {
                            values.put(MmsColumns.MESSAGE_BOX, MmsColumns.BOX_SENT)
                            values.put(MmsColumns.STATUS, MmsStatus.RETRIEVED)
                        }
                        OutgoingStatus.FAILED -> values.put(MmsColumns.MESSAGE_BOX, MmsColumns.BOX_FAILED)
                    }
                    resolver.updateTolerant(ProviderUris.mms(key.providerId), values)
                }
            }
        }
    }

    /** Marks an SMS as failed with the platform result code (kept in `error_code`). */
    suspend fun markSmsFailed(id: Long, errorCode: Int) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(SmsColumns.TYPE, SmsColumns.TYPE_FAILED)
                put(SmsColumns.ERROR_CODE, errorCode)
            }
            resolver.updateTolerant(ProviderUris.sms(id), values, optionalColumn = SmsColumns.ERROR_CODE)
        }
    }

    /** Sets the delivery `status` column (COMPLETE / PENDING / FAILED) without moving boxes. */
    suspend fun setSmsDeliveryStatus(id: Long, providerStatus: Int) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply { put(SmsColumns.STATUS, providerStatus) }
            resolver.updateTolerant(ProviderUris.sms(id), values)
        }
    }

    override suspend fun markThreadRead(threadId: Long) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(SmsColumns.READ, 1)
                put(SmsColumns.SEEN, 1)
            }
            val args = arrayOf(threadId.toString())
            try {
                resolver.update(ProviderUris.SMS, values, "${SmsColumns.THREAD_ID} = ? AND (${SmsColumns.READ} = 0 OR ${SmsColumns.SEEN} = 0)", args)
                resolver.update(ProviderUris.MMS, values, "${MmsColumns.THREAD_ID} = ? AND (${MmsColumns.READ} = 0 OR ${MmsColumns.SEEN} = 0)", args)
            } catch (e: Exception) {
                Log.w(TAG, "markThreadRead failed: ${e.javaClass.simpleName}")
            }
        }
    }

    override suspend fun markRead(key: MessageKey) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(SmsColumns.READ, 1)
                put(SmsColumns.SEEN, 1)
            }
            resolver.updateTolerant(uriFor(key), values)
        }
    }

    override suspend fun delete(key: MessageKey): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.delete(uriFor(key), null, null) > 0
        } catch (e: Exception) {
            Log.w(TAG, "delete failed: ${e.javaClass.simpleName}")
            false
        }
    }

    override suspend fun restore(message: Message): MessageKey? = when (message.kind) {
        MessageKind.SMS -> restoreSms(message)
        MessageKind.MMS -> restoreMms(message)
    }

    override suspend fun threadIdFor(addresses: Set<String>): Long = withContext(Dispatchers.IO) {
        val clean = addresses.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (clean.isEmpty()) return@withContext -1L
        try {
            Telephony.Threads.getOrCreateThreadId(context, clean)
        } catch (e: Exception) {
            Log.w(TAG, "getOrCreateThreadId failed: ${e.javaClass.simpleName}")
            -1L
        }
    }

    private fun uriFor(key: MessageKey) = when (key.kind) {
        MessageKind.SMS -> ProviderUris.sms(key.providerId)
        MessageKind.MMS -> ProviderUris.mms(key.providerId)
    }

    // --- Restore ----------------------------------------------------------------------------------------------

    /** Thread by address (the original thread id may no longer exist once its last message was deleted). */
    private suspend fun restoreSms(m: Message): MessageKey? {
        val threadId = threadIdFor(setOf(m.address)).takeIf { it > 0 }
        return withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(SmsColumns.ADDRESS, m.address)
                put(SmsColumns.BODY, m.body)
                put(SmsColumns.DATE, m.dateMillis)
                put(SmsColumns.DATE_SENT, m.dateMillis)
                put(SmsColumns.TYPE, BoxMapping.boxToSmsType(m.box))
                put(SmsColumns.READ, if (m.read) 1 else 0)
                put(SmsColumns.SEEN, if (m.seen) 1 else 0)
                threadId?.let { put(SmsColumns.THREAD_ID, it) }
                if (m.subId >= 0) put(SmsColumns.SUBSCRIPTION_ID, m.subId)
            }
            resolver.insertTolerant(ProviderUris.SMS, values, SmsColumns.SUBSCRIPTION_ID)
                ?.let { MessageKey(MessageKind.SMS, ContentUris.parseId(it)) }
        }
    }

    /**
     * Rebuilds an MMS: pdu row, text part, attachments (read from their URIs, which must still be readable, e.g.
     * a recycle-bin or backup file) and addresses (inbox: the sender; otherwise the space-joined recipients).
     */
    private suspend fun restoreMms(m: Message): MessageKey? {
        // Restored/imported files are untrusted: bound the recipient list like a received PDU.
        val parties = m.address.split(' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MmsLimits.MAX_ADDRESSES)
        val threadId = threadIdFor(parties.toSet())
        return withContext(Dispatchers.IO) {
            val inbox = m.box == MessageBox.INBOX
            val values = ContentValues().apply {
                put(MmsColumns.MESSAGE_TYPE, if (inbox) MessageType.RETRIEVE_CONF else MessageType.SEND_REQ)
                put(MmsColumns.MESSAGE_BOX, BoxMapping.boxToMmsBox(m.box))
                put(MmsColumns.DATE, m.dateMillis / 1000)
                put(MmsColumns.READ, if (m.read) 1 else 0)
                put(MmsColumns.SEEN, if (m.seen) 1 else 0)
                put(MmsColumns.CONTENT_TYPE, ContentType.MULTIPART_RELATED)
                if (threadId > 0) put(MmsColumns.THREAD_ID, threadId)
                if (m.subId >= 0) put(MmsColumns.SUBSCRIPTION_ID, m.subId)
            }
            val uri = resolver.insertTolerant(ProviderUris.MMS, values, MmsColumns.SUBSCRIPTION_ID) ?: return@withContext null
            val id = ContentUris.parseId(uri)
            var seq = 0
            if (m.body.isNotEmpty()) {
                val text = ContentValues().apply {
                    put(MmsPartColumns.SEQ, seq++)
                    put(MmsPartColumns.CONTENT_TYPE, ContentType.TEXT_PLAIN)
                    put(MmsPartColumns.CHARSET, MmsCharset.UTF_8)
                    put(MmsPartColumns.CONTENT_LOCATION, "text_0.txt")
                    put(MmsPartColumns.TEXT, m.body)
                }
                runCatching { resolver.insert(ProviderUris.mmsParts(id), text) }
            }
            for (attachment in m.attachments) {
                val bytes = runCatching {
                    resolver.openInputStream(android.net.Uri.parse(attachment.uri))?.use { it.readBytes() }
                }.getOrNull() ?: continue
                val part = ContentValues().apply {
                    put(MmsPartColumns.SEQ, seq++)
                    put(MmsPartColumns.CONTENT_TYPE, attachment.mimeType)
                    MmsSafety.safeFileName(attachment.name)?.let {
                        put(MmsPartColumns.NAME, it)
                        put(MmsPartColumns.CONTENT_LOCATION, it)
                    }
                }
                val partUri = runCatching { resolver.insert(ProviderUris.mmsParts(id), part) }.getOrNull() ?: continue
                runCatching { resolver.openOutputStream(partUri)?.use { it.write(bytes) } }
            }
            val addrType = if (inbox) MmsAddrColumns.TYPE_FROM else MmsAddrColumns.TYPE_TO
            if (!inbox) insertAddr(id, MmsAddrColumns.INSERT_ADDRESS_TOKEN, MmsAddrColumns.TYPE_FROM)
            parties.forEach { insertAddr(id, it, addrType) }
            MessageKey(MessageKind.MMS, id)
        }
    }

    private fun insertAddr(mmsId: Long, address: String, type: Int) {
        val values = ContentValues().apply {
            put(MmsAddrColumns.ADDRESS, address)
            put(MmsAddrColumns.TYPE, type)
            put(MmsAddrColumns.CHARSET, MmsCharset.UTF_8)
            put(MmsAddrColumns.MSG_ID, mmsId)
        }
        runCatching { resolver.insert(ProviderUris.mmsAddresses(mmsId), values) }
    }
}
