package app.dak.index.sync

import android.util.Log
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.entity.IndexedMessage
import app.dak.index.otp.OtpLifecycle
import app.dak.telephony.ProviderReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the index in step with the provider after the initial backfill:
 * - [incremental] (on coalesced `ProviderChanges` emissions): new messages via `messagesAfter(maxSmsId,
 *   maxMmsId)` (an indexed id lookup, near free when nothing is new), a refresh of the most recent messages
 *   (sent/failed/read state changes), a refresh of the send / delivery state of unsettled outgoing messages from the
 *   last [OUTGOING_WINDOW_MILLIS] (a delivery report can arrive minutes after sending: narrow provider query, only
 *   for rows still sending or awaiting a report), and - at most every [COUNT_CHECK_INTERVAL_MILLIS] - deletion detection when
 *   the provider's message count dropped (counting walks the whole table, and as the default SMS app nearly every
 *   deletion goes through our own recycle bin anyway);
 * - [full] (daily maintenance task): the same plus deletion detection, skipped when the provider and the index
 *   hold the same number of messages (the index is a superset of the provider once new messages are ingested).
 */
@Singleton
class ProviderReconciler @Inject constructor(
    db: DakIndexDatabase,
    private val reader: ProviderReader,
    private val ingestor: IndexIngestor,
    private val otpLifecycle: OtpLifecycle,
) {
    private val messageDao = db.messageDao()
    private val mutex = Mutex()

    @Volatile
    private var lastProviderCount = -1

    @Volatile
    private var lastCountCheckAt = 0L

    suspend fun incremental(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { reconcile(forceDeletionCheck = false) }
    }

    suspend fun full(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { reconcile(forceDeletionCheck = true) }
    }

    private suspend fun reconcile(forceDeletionCheck: Boolean) {
        // Before stage 1 has indexed anything, the backfill owns ingestion.
        if (messageDao.count() == 0) return
        ingestNew()
        val now = System.currentTimeMillis()
        ingestor.ingest(reader.recentMessages(now - RECENT_WINDOW_MILLIS, RECENT_REFRESH_COUNT))
        refreshOutgoingStates(now)
        if (!forceDeletionCheck && now - lastCountCheckAt < COUNT_CHECK_INTERVAL_MILLIS) return
        lastCountCheckAt = now
        val count = runCatching { reader.totalMessageCount() }.getOrDefault(-1)
        val dropped = lastProviderCount >= 0 && count in 0 until lastProviderCount
        if (count >= 0) lastProviderCount = count
        val inStep = count >= 0 && count == messageDao.count()
        if ((forceDeletionCheck && !inStep) || dropped) detectDeletions()
    }

    private suspend fun ingestNew() {
        var maxSms = messageDao.maxProviderId(MessageKind.SMS.name)
        var maxMms = messageDao.maxProviderId(MessageKind.MMS.name)
        while (true) {
            val batch = reader.messagesAfter(maxSms, maxMms, BATCH)
            if (batch.isEmpty()) break
            val rows = ingestor.ingest(batch)
            rows.filter { it.otpCode != null }.forEach { otpLifecycle.onIndexed(it) }
            maxSms = maxOf(maxSms, batch.filter { it.kind == MessageKind.SMS }.maxOfOrNull { it.providerId } ?: maxSms)
            maxMms = maxOf(maxMms, batch.filter { it.kind == MessageKind.MMS }.maxOfOrNull { it.providerId } ?: maxMms)
            if (batch.size < BATCH) break
        }
    }

    /**
     * Ticks: re-reads box + delivery status (no bodies) of outgoing rows that can still change and applies the
     * differences in place. Nothing to do (one indexed index query, no provider query) once every recent outgoing
     * message is settled.
     */
    private suspend fun refreshOutgoingStates(now: Long) {
        val unsettled = messageDao.unsettledOutgoing(now - OUTGOING_WINDOW_MILLIS, OUTGOING_REFRESH_LIMIT)
        if (unsettled.isEmpty()) return
        val keys = unsettled.map { MessageKey(it.kind, it.providerId) }
        val states = runCatching { reader.outgoingStates(keys) }.getOrDefault(emptyMap())
        if (states.isEmpty()) return
        val current = HashMap<MessageKey, IndexedMessage>()
        for ((kind, group) in keys.groupBy { it.kind }) {
            messageDao.getAll(kind.name, group.map { it.providerId }).forEach { current[MessageKey(kind, it.providerId)] = it }
        }
        for ((key, state) in states) {
            val row = current[key] ?: continue
            val code = state.deliveryStatus.code
            if (row.box == state.box && row.deliveryStatus == code && row.deliveredAtMillis == state.deliveredAtMillis) continue
            messageDao.setOutgoingState(key.kind.name, key.providerId, state.box.name, code, state.deliveredAtMillis)
        }
    }

    /** Drops index rows whose message no longer exists in the provider. */
    private suspend fun detectDeletions() {
        val providerKeys = runCatching { reader.allKeys() }.getOrNull()
        if (providerKeys.isNullOrEmpty()) {
            // An empty key set while we hold rows is far more likely a permission problem than a wiped inbox;
            // never empty the index on that signal.
            Log.w(TAG, "Provider returned no keys; skipping deletion detection")
            return
        }
        val gone = messageDao.allKeys()
            .map { MessageKey(it.kind, it.providerId) }
            .filter { it !in providerKeys }
        if (gone.isNotEmpty()) ingestor.remove(gone)
    }

    private companion object {
        const val TAG = "DakIndex"
        const val BATCH = 500
        const val RECENT_WINDOW_MILLIS = 10 * 60_000L
        const val RECENT_REFRESH_COUNT = 20
        const val COUNT_CHECK_INTERVAL_MILLIS = 30 * 60_000L
        const val OUTGOING_WINDOW_MILLIS = 7L * 24 * 60 * 60_000L
        const val OUTGOING_REFRESH_LIMIT = 200
    }
}
