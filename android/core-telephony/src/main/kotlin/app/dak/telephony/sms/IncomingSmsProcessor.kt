package app.dak.telephony.sms

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import app.dak.core.model.Message
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import app.dak.telephony.IncomingDispatcher
import app.dak.telephony.UNPERSISTED_PROVIDER_ID
import app.dak.telephony.internal.SubscriptionExtras
import app.dak.telephony.internal.TAG
import app.dak.telephony.internal.long
import app.dak.telephony.internal.safeQuery
import app.dak.telephony.provider.IncomingSms
import app.dak.telephony.provider.ProviderUris
import app.dak.telephony.provider.SmsColumns
import app.dak.telephony.provider.TelephonyProviderReader
import app.dak.telephony.provider.TelephonyProviderWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Handles one SMS_DELIVER broadcast: journals the raw PDUs ([SmsJournal]), assembles the (possibly multipart)
 * message, writes it verbatim to the inbox, then dispatches the stored message to the [IncomingDispatcher].
 *
 * Durability: the platform deletes its raw copy as soon as the broadcast finishes, so the PDUs are fsync'd to the
 * journal first, the inbox insert runs [NonCancellable] (the receiver's `goAsync` budget cannot cut it short), and
 * the journal entry is removed only once the row exists. A failed insert is retried by [SmsJournalReplayWorker]
 * ([replayJournal]); replays are idempotent (an existing identical inbox row is reused, never duplicated).
 *
 * Blocked senders are not filtered here: since API 24 the platform drops blocked numbers before delivering to
 * the default SMS app.
 */
@Singleton
class IncomingSmsProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val writer: TelephonyProviderWriter,
    private val reader: TelephonyProviderReader,
    private val dispatcher: IncomingDispatcher,
) {
    private val journal: SmsJournal by lazy { journalFor(context) }

    /** Serialises work on one journal entry between the live receiver and a replay. */
    private val journalLock = Mutex()

    suspend fun process(intent: Intent) {
        val receivedAt = System.currentTimeMillis()
        val subId = SubscriptionExtras.subIdFrom(context, intent)
        val stored = withContext(NonCancellable + Dispatchers.IO) {
            journalLock.withLock {
                val written = rawPdus(intent)?.let { pdus ->
                    runCatching { journal.write(intent.getStringExtra(EXTRA_FORMAT), pdus, subId, receivedAt) }.getOrNull()
                }
                val parts: List<SmsMessage> = try {
                    Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
                } catch (e: Exception) {
                    Log.w(TAG, "unreadable SMS_DELIVER intent: ${e.javaClass.simpleName}")
                    emptyList()
                }
                if (parts.isEmpty()) {
                    // Nothing the platform itself can parse: keep the PDUs out of the replay queue, but visible.
                    written?.let {
                        journal.quarantine(it.key)
                        UnsavedSmsNotification.post(context, journal.quarantinedCount())
                    }
                    return@withLock null
                }
                // Message-waiting indications flagged "do not store" carry no user content (voicemail lamp control).
                if (parts.all { it.isMwiDontStore }) {
                    written?.let { journal.remove(it.key) }
                    return@withLock null
                }
                val sms = incomingOf(parts, subId, receivedAt)
                val existing = if (written?.alreadyPresent == true) existingInboxRow(sms, matchDateSent = true) else null
                val key = existing ?: writer.insertIncoming(sms)
                when {
                    written == null -> Unit
                    key != null -> journal.remove(written.key)
                    !journal.recordFailure(written.key) -> UnsavedSmsNotification.post(context, journal.quarantinedCount())
                }
                Stored(sms, key, isNew = existing == null)
            }
        } ?: return
        if (stored.key == null) {
            Log.e(TAG, "incoming SMS could not be written to the provider; queued for replay")
            SmsJournalReplayWorker.schedule(context)
        } else if (runCatching { journal.hasPending() }.getOrDefault(false)) {
            // Leftovers from an earlier broadcast whose receiver died: this delivery is a good moment to retry them.
            SmsJournalReplayWorker.schedule(context)
        }
        if (!stored.isNew) return // re-delivery of a message that is already in the inbox (and was announced)

        val message = stored.key?.let { reader.message(it) } ?: Message(
            providerId = stored.key?.providerId ?: UNPERSISTED_PROVIDER_ID,
            kind = MessageKind.SMS,
            threadId = -1L,
            address = stored.sms.address,
            body = stored.sms.body,
            dateMillis = receivedAt,
            subId = subId,
            box = MessageBox.INBOX,
        )
        dispatcher.dispatch(message)
    }

    /**
     * Retries every journaled SMS whose insert failed (or whose receiver died mid-way). Returns true when the journal
     * is empty afterwards, false when some entries should be retried later.
     */
    suspend fun replayJournal(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        for (entry in journal.pending()) {
            // A fresh entry without a recorded failure may still be in the live receiver's hands.
            if (entry.attempts == 0 && now - entry.receivedAtMillis in 0 until LIVE_GRACE_MILLIS) continue
            val newKey = withContext(NonCancellable) { journalLock.withLock { replayOne(entry) } }
            newKey?.let { key -> reader.message(key)?.let { dispatcher.dispatch(it) } }
        }
        !journal.hasPending()
    }

    /** Messages that could not be saved and were given up on after repeated failures. */
    fun unrecoverableCount(): Int = runCatching { journal.quarantinedCount() }.getOrDefault(0)

    /** Replays one entry; returns the key of a newly inserted row (to announce), or null. */
    private suspend fun replayOne(entry: SmsJournal.Entry): MessageKey? {
        val parts = entry.pdus.mapNotNull { pdu ->
            try {
                @Suppress("DEPRECATION")
                if (entry.format != null) SmsMessage.createFromPdu(pdu, entry.format) else SmsMessage.createFromPdu(pdu)
            } catch (e: Exception) {
                null
            }
        }
        if (parts.isEmpty()) {
            journal.quarantine(entry.key)
            Log.e(TAG, "journaled SMS cannot be parsed; quarantined")
            UnsavedSmsNotification.post(context, journal.quarantinedCount())
            return null
        }
        if (parts.all { it.isMwiDontStore }) {
            journal.remove(entry.key)
            return null
        }
        val sms = incomingOf(parts, entry.subId, entry.receivedAtMillis)
        existingInboxRow(sms, matchDateSent = true)?.let {
            journal.remove(entry.key)
            return null
        }
        val key = writer.insertIncoming(sms)
        if (key != null) {
            journal.remove(entry.key)
        } else if (!journal.recordFailure(entry.key)) {
            Log.e(TAG, "journaled SMS failed ${SmsJournal.DEFAULT_MAX_ATTEMPTS} times; quarantined")
            UnsavedSmsNotification.post(context, journal.quarantinedCount())
        }
        return key
    }

    /**
     * An inbox row already holding this message: same address and body, and the same receive time (a replay of our
     * own journal entry) or, for a platform re-delivery, the same service-centre timestamp.
     */
    private fun existingInboxRow(sms: IncomingSms, matchDateSent: Boolean): MessageKey? {
        val byDate = findInbox("${SmsColumns.DATE} = ?", sms.dateMillis, sms)
        if (byDate != null || !matchDateSent || sms.dateSentMillis <= 0) return byDate
        return findInbox("${SmsColumns.DATE_SENT} = ?", sms.dateSentMillis, sms)
    }

    private fun findInbox(timeClause: String, time: Long, sms: IncomingSms): MessageKey? =
        context.contentResolver.safeQuery(
            ProviderUris.SMS_INBOX,
            arrayOf(SmsColumns.ID),
            "$timeClause AND ${SmsColumns.ADDRESS} = ? AND ${SmsColumns.BODY} = ?",
            arrayOf(time.toString(), sms.address, sms.body),
        )?.use { c -> if (c.moveToFirst()) MessageKey(MessageKind.SMS, c.long(SmsColumns.ID)) else null }

    private class Stored(val sms: IncomingSms, val key: MessageKey?, val isNew: Boolean)

    internal companion object {
        private const val EXTRA_PDUS = "pdus"
        private const val EXTRA_FORMAT = "format"
        private const val JOURNAL_DIR = "dak_sms_journal"

        /** How long a fresh journal entry is left to the live receiver before a replay may touch it. */
        private const val LIVE_GRACE_MILLIS = 60_000L

        /** The journal lives in no-backup storage: it only ever holds messages until they reach the provider. */
        fun journalFor(context: Context): SmsJournal = SmsJournal(File(context.noBackupFilesDir, JOURNAL_DIR))

        @Suppress("DEPRECATION")
        private fun rawPdus(intent: Intent): List<ByteArray>? = try {
            (intent.getSerializableExtra(EXTRA_PDUS) as? Array<*>)?.mapNotNull { it as? ByteArray }?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }

        private fun incomingOf(parts: List<SmsMessage>, subId: Int, receivedAt: Long): IncomingSms {
            val first = parts[0]
            return IncomingSms(
                address = first.displayOriginatingAddress ?: first.originatingAddress ?: "",
                body = parts.joinToString(separator = "") { it.displayMessageBody ?: it.messageBody ?: "" },
                dateSentMillis = first.timestampMillis,
                dateMillis = receivedAt,
                subId = subId,
                protocol = first.protocolIdentifier,
                serviceCenter = first.serviceCenterAddress,
                replyPathPresent = first.isReplyPathPresent,
                subject = first.pseudoSubject?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
