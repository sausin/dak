package app.dak.telephony.provider

import android.content.Context
import android.database.Cursor
import app.dak.core.model.Message
import app.dak.core.model.MessageKey
import app.dak.core.model.DeliveryStatus
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import app.dak.core.model.NO_SUB_ID
import app.dak.mms.pdu.MmsCharset
import app.dak.telephony.OutgoingState
import app.dak.telephony.ProviderReader
import app.dak.telephony.ProviderThread
import app.dak.telephony.SimRepository
import app.dak.telephony.internal.hasColumn
import app.dak.telephony.internal.int
import app.dak.telephony.internal.long
import app.dak.telephony.internal.safeQuery
import app.dak.telephony.internal.string
import app.dak.telephony.mms.MmsProviderMapping
import app.dak.telephony.mms.StoredPart
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ProviderReader] over `content://sms`, `content://mms` and `content://mms-sms`.
 *
 * Robust to OEM schemas: queries use a null projection and every column is looked up by name (a missing
 * `sub_id` falls back to OEM subscription or slot columns, else [NO_SUB_ID]). Provider errors (e.g. not the
 * default SMS app) yield empty results rather than exceptions. SMS and MMS are merged newest-first by walking
 * both cursors, so paging never needs SQL LIMIT support from the provider.
 */
@Singleton
class TelephonyProviderReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sims: SimRepository,
) : ProviderReader {

    private val resolver get() = context.contentResolver

    override suspend fun threads(): List<ProviderThread> = withContext(Dispatchers.IO) {
        val canonical = canonicalAddresses()
        val unread = unreadCounts()
        val out = ArrayList<ProviderThread>()
        resolver.safeQuery(ProviderUris.THREADS_SIMPLE, sortOrder = "date DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.long("_id", -1L)
                if (id < 0) continue
                val recipientIds = c.string("recipient_ids").orEmpty()
                    .split(' ')
                    .mapNotNull { it.trim().toLongOrNull() }
                out += ProviderThread(
                    threadId = id,
                    addresses = recipientIds.mapNotNull { canonical[it] },
                    snippet = c.string("snippet").orEmpty(),
                    dateMillis = c.long("date"),
                    messageCount = c.int("message_count"),
                    unreadCount = unread[id] ?: 0,
                )
            }
        }
        out
    }

    override suspend fun messagesInThread(threadId: Long, limit: Int, offset: Int): List<Message> =
        withContext(Dispatchers.IO) {
            if (limit <= 0) return@withContext emptyList()
            val args = arrayOf(threadId.toString())
            merged(
                smsSelection = "${SmsColumns.THREAD_ID} = ?",
                smsArgs = args,
                mmsSelection = "${MmsColumns.THREAD_ID} = ? AND ${MmsColumns.MESSAGE_TYPE_FILTER}",
                mmsArgs = args,
                offset = offset.coerceAtLeast(0),
            ) { taken, _ -> taken < limit }
        }

    override suspend fun message(key: MessageKey): Message? = withContext(Dispatchers.IO) {
        when (key.kind) {
            MessageKind.SMS -> resolver.safeQuery(ProviderUris.sms(key.providerId))?.use { c ->
                if (c.moveToFirst()) c.toSms() else null
            }
            MessageKind.MMS -> {
                val row = resolver.safeQuery(ProviderUris.mms(key.providerId))?.use { c ->
                    if (c.moveToFirst()) c.toMmsRow() else null
                }
                row?.let { resolveMms(listOf(it)).firstOrNull() }
            }
        }
    }

    override suspend fun recentMessages(sinceMillis: Long, minCount: Int): List<Message> = withContext(Dispatchers.IO) {
        merged(null, null, MmsColumns.MESSAGE_TYPE_FILTER, null, 0) { taken, date ->
            date >= sinceMillis || taken < minCount
        }
    }

    override suspend fun messagesBefore(beforeMillis: Long, limit: Int): List<Message> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        // MMS dates are seconds: s * 1000 < before  <=>  s < ceil(before / 1000).
        val mmsCutoff = Math.floorDiv(beforeMillis + 999, 1000L)
        merged(
            smsSelection = "${SmsColumns.DATE} < ?",
            smsArgs = arrayOf(beforeMillis.toString()),
            mmsSelection = "${MmsColumns.DATE} < ? AND ${MmsColumns.MESSAGE_TYPE_FILTER}",
            mmsArgs = arrayOf(mmsCutoff.toString()),
            offset = 0,
        ) { taken, _ -> taken < limit }
    }

    override suspend fun messagesAfter(smsIdExclusive: Long, mmsIdExclusive: Long, limit: Int): List<Message> =
        withContext(Dispatchers.IO) {
            if (limit <= 0) return@withContext emptyList()
            val out = ArrayList<Message>()
            resolver.safeQuery(
                ProviderUris.SMS, null, "${SmsColumns.ID} > ?", arrayOf(smsIdExclusive.toString()), "${SmsColumns.ID} ASC",
            )?.use { c ->
                while (out.size < limit && c.moveToNext()) out += c.toSms()
            }
            val remaining = limit - out.size
            if (remaining > 0) {
                val rows = ArrayList<MmsRow>()
                resolver.safeQuery(
                    ProviderUris.MMS, null, "${MmsColumns.ID} > ? AND ${MmsColumns.MESSAGE_TYPE_FILTER}",
                    arrayOf(mmsIdExclusive.toString()), "${MmsColumns.ID} ASC",
                )?.use { c ->
                    while (rows.size < remaining && c.moveToNext()) rows += c.toMmsRow()
                }
                out += resolveMms(rows)
            }
            out
        }

    override suspend fun totalMessageCount(): Int = withContext(Dispatchers.IO) {
        val sms = resolver.safeQuery(ProviderUris.SMS, arrayOf(SmsColumns.ID))?.use { it.count } ?: 0
        val mms = resolver.safeQuery(ProviderUris.MMS, arrayOf(MmsColumns.ID), MmsColumns.MESSAGE_TYPE_FILTER)?.use { it.count } ?: 0
        sms + mms
    }

    override suspend fun maxIds(): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val sms = resolver.safeQuery(ProviderUris.SMS, arrayOf(SmsColumns.ID), sortOrder = "${SmsColumns.ID} DESC")?.use { c ->
            if (c.moveToFirst()) c.long(SmsColumns.ID) else 0L
        } ?: 0L
        val mms = resolver.safeQuery(
            ProviderUris.MMS, arrayOf(MmsColumns.ID), MmsColumns.MESSAGE_TYPE_FILTER, null, "${MmsColumns.ID} DESC",
        )?.use { c -> if (c.moveToFirst()) c.long(MmsColumns.ID) else 0L } ?: 0L
        sms to mms
    }

    override suspend fun allKeys(): Set<MessageKey> = withContext(Dispatchers.IO) {
        val keys = HashSet<MessageKey>()
        resolver.safeQuery(ProviderUris.SMS, arrayOf(SmsColumns.ID))?.use { c ->
            while (c.moveToNext()) keys += MessageKey(MessageKind.SMS, c.long(SmsColumns.ID))
        }
        resolver.safeQuery(ProviderUris.MMS, arrayOf(MmsColumns.ID), MmsColumns.MESSAGE_TYPE_FILTER)?.use { c ->
            while (c.moveToNext()) keys += MessageKey(MessageKind.MMS, c.long(MmsColumns.ID))
        }
        keys
    }

    override suspend fun outgoingStates(keys: Collection<MessageKey>): Map<MessageKey, OutgoingState> =
        withContext(Dispatchers.IO) {
            val out = HashMap<MessageKey, OutgoingState>()
            val smsIds = keys.filter { it.kind == MessageKind.SMS }.map { it.providerId }.distinct()
            for (chunk in smsIds.chunked(PART_QUERY_CHUNK)) {
                resolver.safeQuery(
                    ProviderUris.SMS,
                    arrayOf(SmsColumns.ID, SmsColumns.TYPE, SmsColumns.STATUS, SmsColumns.DATE_SENT),
                    "${SmsColumns.ID} IN (${chunk.joinToString(",")})",
                    null,
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val box = BoxMapping.smsTypeToBox(c.int(SmsColumns.TYPE, SmsColumns.TYPE_INBOX))
                        val status = DeliveryStatusMapping.sms(box, c.int(SmsColumns.STATUS, SmsColumns.STATUS_NONE))
                        out[MessageKey(MessageKind.SMS, c.long(SmsColumns.ID))] = OutgoingState(
                            box = box,
                            deliveryStatus = status,
                            deliveredAtMillis = DeliveryStatusMapping.smsDeliveredAt(status, c.long(SmsColumns.DATE_SENT)),
                        )
                    }
                }
            }
            val mmsIds = keys.filter { it.kind == MessageKind.MMS }.map { it.providerId }.distinct()
            for (chunk in mmsIds.chunked(PART_QUERY_CHUNK)) {
                resolver.safeQuery(
                    ProviderUris.MMS,
                    arrayOf(MmsColumns.ID, MmsColumns.MESSAGE_BOX, MmsColumns.STATUS, MmsColumns.DELIVERY_REPORT),
                    "${MmsColumns.ID} IN (${chunk.joinToString(",")})",
                    null,
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val box = BoxMapping.mmsBoxToBox(c.int(MmsColumns.MESSAGE_BOX, MmsColumns.BOX_INBOX))
                        out[MessageKey(MessageKind.MMS, c.long(MmsColumns.ID))] = OutgoingState(
                            box = box,
                            deliveryStatus = DeliveryStatusMapping.mms(
                                box, c.int(MmsColumns.STATUS), c.int(MmsColumns.DELIVERY_REPORT) == MMS_YES,
                            ),
                            deliveredAtMillis = null,
                        )
                    }
                }
            }
            out
        }

    // --- Merge walk -----------------------------------------------------------------------------------------

    private sealed interface Pending {
        class Sms(val message: Message) : Pending
        class Mms(val row: MmsRow) : Pending
    }

    /**
     * Walks SMS and MMS cursors (both `date DESC`) in lockstep, newest first, skipping [offset] rows and taking
     * rows while [accept] (given the count taken so far and the row's date in millis) returns true.
     */
    private fun merged(
        smsSelection: String?,
        smsArgs: Array<String>?,
        mmsSelection: String?,
        mmsArgs: Array<String>?,
        offset: Int,
        accept: (taken: Int, dateMillis: Long) -> Boolean,
    ): List<Message> {
        val pending = ArrayList<Pending>()
        val sms = resolver.safeQuery(ProviderUris.SMS, null, smsSelection, smsArgs, "${SmsColumns.DATE} DESC")
        val mms = resolver.safeQuery(ProviderUris.MMS, null, mmsSelection, mmsArgs, "${MmsColumns.DATE} DESC")
        try {
            var smsHas = sms?.moveToFirst() == true
            var mmsHas = mms?.moveToFirst() == true
            var skipped = 0
            while (smsHas || mmsHas) {
                val smsDate = if (smsHas) sms!!.long(SmsColumns.DATE) else Long.MIN_VALUE
                val mmsDate = if (mmsHas) mms!!.long(MmsColumns.DATE) * 1000L else Long.MIN_VALUE
                val takeSms = smsHas && (!mmsHas || smsDate >= mmsDate)
                val date = if (takeSms) smsDate else mmsDate
                if (skipped >= offset && !accept(pending.size, date)) break
                if (skipped < offset) {
                    skipped++
                } else if (takeSms) {
                    pending += Pending.Sms(sms!!.toSms())
                } else {
                    pending += Pending.Mms(mms!!.toMmsRow())
                }
                if (takeSms) smsHas = sms!!.moveToNext() else mmsHas = mms!!.moveToNext()
            }
        } finally {
            sms?.close()
            mms?.close()
        }
        val mmsMessages = resolveMms(pending.filterIsInstance<Pending.Mms>().map { it.row }).associateBy { it.providerId }
        return pending.mapNotNull {
            when (it) {
                is Pending.Sms -> it.message
                is Pending.Mms -> mmsMessages[it.row.id]
            }
        }
    }

    // --- SMS ------------------------------------------------------------------------------------------------

    private fun Cursor.toSms(): Message {
        val box = BoxMapping.smsTypeToBox(int(SmsColumns.TYPE, SmsColumns.TYPE_INBOX))
        val delivery = DeliveryStatusMapping.sms(box, int(SmsColumns.STATUS, SmsColumns.STATUS_NONE))
        return toSms(box, delivery, DeliveryStatusMapping.smsDeliveredAt(delivery, long(SmsColumns.DATE_SENT)))
    }

    private fun Cursor.toSms(box: MessageBox, delivery: DeliveryStatus, deliveredAt: Long?): Message = Message(
        providerId = long(SmsColumns.ID),
        kind = MessageKind.SMS,
        threadId = long(SmsColumns.THREAD_ID),
        address = string(SmsColumns.ADDRESS).orEmpty(),
        body = string(SmsColumns.BODY).orEmpty(),
        dateMillis = long(SmsColumns.DATE),
        subId = subIdOf(this, SmsColumns.SUBSCRIPTION_ID),
        box = box,
        read = int(SmsColumns.READ) != 0,
        seen = int(SmsColumns.SEEN) != 0,
        deliveryStatus = delivery,
        deliveredAtMillis = deliveredAt,
    )

    /** `sub_id`, else an OEM subscription column, else an OEM slot column mapped through the SIM list. */
    private fun subIdOf(c: Cursor, primary: String): Int {
        if (c.hasColumn(primary)) return c.int(primary, NO_SUB_ID)
        for (column in SmsColumns.ALT_SUBSCRIPTION_COLUMNS) {
            if (c.hasColumn(column)) return c.int(column, NO_SUB_ID)
        }
        for (column in SmsColumns.SLOT_COLUMNS) {
            if (c.hasColumn(column)) {
                val slot = c.int(column, -1)
                return sims.sims.value.firstOrNull { it.slotIndex == slot && it.isActive }?.subId ?: NO_SUB_ID
            }
        }
        return NO_SUB_ID
    }

    // --- MMS ------------------------------------------------------------------------------------------------

    private class MmsRow(
        val id: Long,
        val threadId: Long,
        val dateMillis: Long,
        val msgBox: Int,
        val read: Boolean,
        val seen: Boolean,
        val subId: Int,
        val subject: String?,
        /** `st` (X-Mms-Status from m-delivery-ind for outgoing messages), 0 when unset. */
        val status: Int,
        val deliveryReportRequested: Boolean,
    )

    private fun Cursor.toMmsRow(): MmsRow = MmsRow(
        id = long(MmsColumns.ID),
        threadId = long(MmsColumns.THREAD_ID),
        dateMillis = long(MmsColumns.DATE) * 1000L,
        msgBox = int(MmsColumns.MESSAGE_BOX, MmsColumns.BOX_INBOX),
        read = int(MmsColumns.READ) != 0,
        seen = int(MmsColumns.SEEN) != 0,
        subId = subIdOf(this, MmsColumns.SUBSCRIPTION_ID),
        subject = string(MmsColumns.SUBJECT),
        status = int(MmsColumns.STATUS),
        deliveryReportRequested = int(MmsColumns.DELIVERY_REPORT) == MMS_YES,
    )

    private fun resolveMms(rows: List<MmsRow>): List<Message> {
        if (rows.isEmpty()) return emptyList()
        val parts = loadParts(rows.map { it.id })
        return rows.map { r ->
            val (text, attachments) = MmsProviderMapping.bodyAndAttachments(parts[r.id].orEmpty())
            val box = BoxMapping.mmsBoxToBox(r.msgBox)
            Message(
                providerId = r.id,
                kind = MessageKind.MMS,
                threadId = r.threadId,
                address = mmsAddress(r.id, r.msgBox),
                body = text.ifEmpty { r.subject.orEmpty() },
                dateMillis = r.dateMillis,
                subId = r.subId,
                box = box,
                read = r.read,
                seen = r.seen,
                attachments = attachments,
                deliveryStatus = DeliveryStatusMapping.mms(box, r.status, r.deliveryReportRequested),
            )
        }
    }

    /** Parts of many messages in one query per chunk (`mid IN (...)`), ordered by `seq`. */
    private fun loadParts(ids: List<Long>): Map<Long, List<StoredPart>> {
        val out = HashMap<Long, MutableList<Pair<Int, StoredPart>>>()
        for (chunk in ids.distinct().chunked(PART_QUERY_CHUNK)) {
            val selection = "${MmsPartColumns.MSG_ID} IN (${chunk.joinToString(",")})"
            resolver.safeQuery(ProviderUris.MMS_PART, null, selection, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val partId = c.long(MmsPartColumns.ID)
                    val ct = c.string(MmsPartColumns.CONTENT_TYPE)?.lowercase() ?: "application/octet-stream"
                    var text = c.string(MmsPartColumns.TEXT)
                    if (text == null && ct == "text/plain") text = readPartText(partId, c.int(MmsPartColumns.CHARSET, MmsCharset.UTF_8))
                    val part = StoredPart(
                        id = partId,
                        contentType = ct,
                        text = text,
                        name = c.string(MmsPartColumns.NAME),
                        fileName = c.string(MmsPartColumns.FILENAME),
                        contentLocation = c.string(MmsPartColumns.CONTENT_LOCATION),
                    )
                    out.getOrPut(c.long(MmsPartColumns.MSG_ID)) { ArrayList() } += c.int(MmsPartColumns.SEQ) to part
                }
            }
        }
        return out.mapValues { (_, list) -> list.sortedBy { it.first }.map { it.second } }
    }

    private fun readPartText(partId: Long, charset: Int): String? = try {
        resolver.openInputStream(ProviderUris.mmsPart(partId))?.use { MmsCharset.decode(it.readBytes(), charset) }
    } catch (e: Exception) {
        null
    }

    /** Inbox: the sender. Other boxes: the recipients, space-joined. */
    private fun mmsAddress(mmsId: Long, msgBox: Int): String {
        var from: String? = null
        val to = ArrayList<String>()
        resolver.safeQuery(ProviderUris.mmsAddresses(mmsId))?.use { c ->
            while (c.moveToNext()) {
                val address = c.string(MmsAddrColumns.ADDRESS)?.trim().orEmpty()
                if (address.isEmpty() || address == MmsAddrColumns.INSERT_ADDRESS_TOKEN) continue
                when (c.int(MmsAddrColumns.TYPE)) {
                    MmsAddrColumns.TYPE_FROM -> if (from == null) from = address
                    MmsAddrColumns.TYPE_TO, MmsAddrColumns.TYPE_CC, MmsAddrColumns.TYPE_BCC -> to += address
                }
            }
        }
        return if (msgBox == MmsColumns.BOX_INBOX) from.orEmpty() else to.distinct().joinToString(" ")
    }

    // --- Threads helpers ------------------------------------------------------------------------------------

    private fun canonicalAddresses(): Map<Long, String> {
        val map = HashMap<Long, String>()
        resolver.safeQuery(ProviderUris.CANONICAL_ADDRESSES)?.use { c ->
            while (c.moveToNext()) {
                val address = c.string("address") ?: continue
                map[c.long("_id")] = address
            }
        }
        return map
    }

    private fun unreadCounts(): Map<Long, Int> {
        val counts = HashMap<Long, Int>()
        fun count(c: Cursor?) {
            c?.use {
                while (it.moveToNext()) {
                    val thread = it.long(SmsColumns.THREAD_ID, -1L)
                    if (thread >= 0) counts[thread] = (counts[thread] ?: 0) + 1
                }
            }
        }
        count(resolver.safeQuery(ProviderUris.SMS, arrayOf(SmsColumns.THREAD_ID), "${SmsColumns.READ} = 0"))
        count(
            resolver.safeQuery(
                ProviderUris.MMS, arrayOf(MmsColumns.THREAD_ID), "${MmsColumns.READ} = 0 AND ${MmsColumns.MESSAGE_TYPE_FILTER}",
            ),
        )
        return counts
    }

    private companion object {
        const val PART_QUERY_CHUNK = 200

        /** PDU boolean "yes" (`d_rpt`). */
        const val MMS_YES = 128
    }
}
