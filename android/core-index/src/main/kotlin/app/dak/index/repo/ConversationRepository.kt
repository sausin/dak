package app.dak.index.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.dak.core.model.MessageKey
import app.dak.core.model.NO_SUB_ID
import app.dak.index.BackfillStage
import app.dak.index.ContactLookup
import app.dak.index.ConversationSummary
import app.dak.index.InboxTab
import app.dak.index.MessageItem
import app.dak.index.NoContactLookup
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.entity.ConversationPrefs
import app.dak.index.db.entity.MessageFlag
import app.dak.index.enrich.ConversationIds
import app.dak.index.sql.ConversationSqlBuilder
import app.dak.index.sql.SqlQuery
import app.dak.index.sql.Tables
import app.dak.telephony.ProviderReader
import app.dak.telephony.ProviderWriter
import app.dak.telephony.SimRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conversation list and thread contents for the UI, grouped by conversation id (merge groups collapse
 * `VM-HDFCBK` / `JD-HDFCBK` into one conversation). All functions are main-safe.
 *
 * While the backfill is still running, provider threads the index has not reached yet are appended to the
 * [InboxTab.ALL] list (and their messages paged straight from the provider), so nothing is ever hidden; they simply
 * lack category, SIM and finance enrichment until indexed ([ConversationSummary.enriched] = false).
 */
@Singleton
class ConversationRepository @Inject constructor(
    private val db: DakIndexDatabase,
    private val reader: ProviderReader,
    private val writer: ProviderWriter,
    private val sims: SimRepository,
    contacts: Optional<ContactLookup>,
) {
    private val messageDao = db.messageDao()
    private val prefsDao = db.conversationPrefsDao()
    private val rawDao = db.rawQueryDao()
    private val stateDao = db.backfillStateDao()
    private val contacts: ContactLookup = contacts.orElse(NoContactLookup)
    private val prefsMutex = Mutex()

    /**
     * Conversations of [tab], pinned first then newest. [subId] restricts to conversations with messages on that
     * SIM (the fallback provider threads are then omitted, since their SIM is unknown).
     */
    fun conversations(tab: InboxTab, subId: Int? = null, pageSize: Int = 30): Flow<PagingData<ConversationSummary>> =
        Pager(PagingConfig(pageSize = pageSize, enablePlaceholders = false)) {
            ConversationPagingSource(tab, subId)
        }.flow

    /** Messages of a conversation, newest first (body included; it is stored encrypted in the index). */
    fun messages(conversationId: String, pageSize: Int = 50): Flow<PagingData<MessageItem>> =
        Pager(PagingConfig(pageSize = pageSize, enablePlaceholders = false)) {
            MessagePagingSource(conversationId)
        }.flow

    /** One message, live. Null while not indexed (or deleted). */
    fun message(key: MessageKey): Flow<MessageItem?> =
        messageDao.observe(key.kind.name, key.providerId).map { row -> row?.let { Mappers.messageItem(it) } }

    /** The conversation a message belongs to (after merge-group mapping), or null if not indexed. */
    suspend fun conversationIdOf(key: MessageKey): String? =
        messageDao.get(key.kind.name, key.providerId)?.conversationId

    fun prefs(conversationId: String): Flow<ConversationPrefs?> = prefsDao.observe(conversationId)

    /** Marks every message of the conversation read, in the index and in the provider. */
    suspend fun markRead(conversationId: String): Unit = withContext(Dispatchers.IO) {
        val threads = threadsOf(conversationId)
        messageDao.markConversationRead(conversationId)
        for (threadId in threads) {
            messageDao.markThreadRead(threadId)
            runCatching { writer.markThreadRead(threadId) }
        }
    }

    suspend fun markMessageRead(key: MessageKey): Unit = withContext(Dispatchers.IO) {
        messageDao.markRead(key.kind.name, key.providerId)
        runCatching { writer.markRead(key) }
        Unit
    }

    suspend fun setPinned(conversationId: String, pinned: Boolean) = updatePrefs(conversationId) { it.copy(pinned = pinned) }
    suspend fun setMuted(conversationId: String, muted: Boolean) = updatePrefs(conversationId) { it.copy(muted = muted) }
    suspend fun setArchived(conversationId: String, archived: Boolean) = updatePrefs(conversationId) { it.copy(archived = archived) }
    suspend fun setStarred(conversationId: String, starred: Boolean) = updatePrefs(conversationId) { it.copy(starred = starred) }
    suspend fun setBubbleColor(conversationId: String, argb: Int?) = updatePrefs(conversationId) { it.copy(bubbleColor = argb) }
    suspend fun setFontScale(conversationId: String, scale: Float?) = updatePrefs(conversationId) { it.copy(fontScale = scale) }
    suspend fun setAlwaysTranslate(conversationId: String, enabled: Boolean) =
        updatePrefs(conversationId) { it.copy(alwaysTranslate = enabled) }

    /** Sets the conversation's reply SIM; null returns to the default (SIM of the last incoming message). */
    suspend fun setReplySim(conversationId: String, subId: Int?) = updatePrefs(conversationId) { it.copy(replySubId = subId) }

    /**
     * The SIM to reply from: the user's per-conversation choice, else the SIM of the last incoming message, else
     * the system default SMS subscription. A SIM that is no longer present falls back to the default.
     */
    suspend fun replySimFor(conversationId: String): Int = withContext(Dispatchers.IO) {
        val chosen = prefsDao.get(conversationId)?.replySubId
        val lastIncoming = ConversationIds.threadIdOf(conversationId)?.let { messageDao.lastIncomingSubIdInThread(it) }
            ?: messageDao.lastIncomingSubId(conversationId)
        val candidate = listOfNotNull(chosen, lastIncoming).firstOrNull { it != NO_SUB_ID && sims.sim(it) != null }
        candidate ?: sims.defaultSmsSubId()
    }

    /**
     * Recipients to reply to: for a thread, the provider's recipient list (group MMS included); for a merge group,
     * the most recent sender address.
     */
    suspend fun addressesFor(conversationId: String): List<String> = withContext(Dispatchers.IO) {
        val threadId = ConversationIds.threadIdOf(conversationId)
        if (threadId != null) {
            val fromProvider = runCatching { reader.threads().firstOrNull { it.threadId == threadId }?.addresses }.getOrNull()
            if (!fromProvider.isNullOrEmpty()) return@withContext fromProvider
            return@withContext messageDao.addressesInThread(threadId).take(1).flatMap { Mappers.splitAddresses(it) }
        }
        messageDao.addressesOf(conversationId).take(1)
    }

    /** Stars / unstars one message (kept across index rebuilds). */
    suspend fun setMessageStarred(key: MessageKey, starred: Boolean): Unit = withContext(Dispatchers.IO) {
        db.withTransaction {
            val flag = messageDao.flag(key.kind.name, key.providerId)
            messageDao.upsertFlag(MessageFlag(key.kind, key.providerId, starred, flag?.archived ?: false))
            messageDao.setStarred(key.kind.name, key.providerId, starred)
        }
        Unit
    }

    /** Archives / unarchives one message (automation "archive" action). */
    suspend fun setMessageArchived(key: MessageKey, archived: Boolean): Unit = withContext(Dispatchers.IO) {
        db.withTransaction {
            val flag = messageDao.flag(key.kind.name, key.providerId)
            messageDao.upsertFlag(MessageFlag(key.kind, key.providerId, flag?.starred ?: false, archived))
            messageDao.setArchived(key.kind.name, key.providerId, archived)
        }
        Unit
    }

    private suspend fun threadsOf(conversationId: String): List<Long> =
        ConversationIds.threadIdOf(conversationId)?.let { listOf(it) } ?: messageDao.threadIdsOf(conversationId)

    private suspend fun updatePrefs(conversationId: String, change: (ConversationPrefs) -> ConversationPrefs) {
        withContext(Dispatchers.IO) {
            prefsMutex.withLock {
                val current = prefsDao.get(conversationId) ?: ConversationPrefs(conversationId)
                prefsDao.put(change(current).copy(updatedAt = System.currentTimeMillis()))
            }
        }
    }

    private suspend fun backfillComplete(): Boolean = stateDao.get()?.stage == BackfillStage.DONE.name

    private fun SqlQuery.toSupport(): SimpleSQLiteQuery = SimpleSQLiteQuery(sql, args.toTypedArray())

    /** Indexed conversations, then (while backfilling, ALL tab only) provider threads with no indexed rows. */
    private inner class ConversationPagingSource(
        private val tab: InboxTab,
        private val subId: Int?,
    ) : OffsetPagingSource<ConversationSummary>(db, arrayOf(Tables.MESSAGE, Tables.PREFS, Tables.MERGE_GROUP, Tables.BACKFILL_STATE)) {

        private var indexedTotal: Int? = null
        private var fallback: List<ConversationSummary>? = null

        override suspend fun loadRange(offset: Int, limit: Int): List<ConversationSummary> {
            val total = indexedTotal ?: (rawDao.count(ConversationSqlBuilder.count(tab, subId).toSupport())?.n ?: 0)
                .also { indexedTotal = it }
            val out = ArrayList<ConversationSummary>(limit)
            if (offset < total) {
                rawDao.conversations(ConversationSqlBuilder.page(tab, subId, limit, offset).toSupport())
                    .mapTo(out) { Mappers.conversationSummary(it, contacts) }
            }
            val remaining = limit - out.size
            if (remaining > 0) {
                val extra = fallbackThreads()
                val start = maxOf(0, offset + out.size - total)
                if (start < extra.size) out += extra.subList(start, minOf(extra.size, start + remaining))
            }
            return out
        }

        private suspend fun fallbackThreads(): List<ConversationSummary> {
            fallback?.let { return it }
            val list = if (tab != InboxTab.ALL || subId != null || backfillComplete()) {
                emptyList()
            } else {
                val indexed = messageDao.indexedThreadIds().toHashSet()
                val threads = runCatching { reader.threads() }.getOrDefault(emptyList())
                    .filter { it.threadId !in indexed && it.messageCount > 0 }
                    .sortedByDescending { it.dateMillis }
                val prefs = threads.map { ConversationIds.forThread(it.threadId) }.chunked(500)
                    .flatMap { prefsDao.getAll(it) }
                    .associateBy { it.conversationId }
                threads.map { Mappers.providerSummary(it, prefs[ConversationIds.forThread(it.threadId)], contacts) }
                    .filter { !it.archived }
            }
            fallback = list
            return list
        }
    }

    /** Indexed messages newest first, then (while backfilling, threads only) older ones from the provider. */
    private inner class MessagePagingSource(
        private val conversationId: String,
    ) : OffsetPagingSource<MessageItem>(db, arrayOf(Tables.MESSAGE, Tables.BACKFILL_STATE)) {

        private val threadId: Long? = ConversationIds.threadIdOf(conversationId)
        private var indexedCount: Int? = null

        override suspend fun loadRange(offset: Int, limit: Int): List<MessageItem> {
            val rows = if (threadId != null) {
                messageDao.pageByThread(threadId, limit, offset)
            } else {
                messageDao.pageByConversation(conversationId, limit, offset)
            }
            val out = rows.mapTo(ArrayList<MessageItem>(limit)) { Mappers.messageItem(it.message, it.otpRepeatedLater) }
            if (out.size < limit && threadId != null && !backfillComplete()) {
                // Indexed rows are the newest part of the thread (the backfill runs newest to oldest), so provider
                // positions line up with combined offsets; skip anything that is already indexed.
                val count = indexedCount ?: messageDao.countInThread(threadId).also { indexedCount = it }
                val providerOffset = maxOf(count, offset + out.size)
                val seen = out.mapTo(HashSet<MessageKey>()) { it.key }
                runCatching { reader.messagesInThread(threadId, limit - out.size, providerOffset) }
                    .getOrDefault(emptyList())
                    .filter { it.key !in seen && messageDao.get(it.kind.name, it.providerId) == null }
                    .mapTo(out) { Mappers.providerMessageItem(it) }
            }
            return out
        }
    }
}
