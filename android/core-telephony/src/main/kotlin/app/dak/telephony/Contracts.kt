package app.dak.telephony

import app.dak.core.model.DeliveryStatus
import app.dak.core.model.Message
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.core.model.SimInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * Public contracts of :core-telephony. The Telephony provider is canonical and raw: this module is the only
 * code that reads or writes it. Everything else (index, UI, automations) goes through these interfaces.
 */

/** A conversation as the provider groups it (`Telephony.Threads`). */
data class ProviderThread(
    val threadId: Long,
    /** Raw recipient addresses in the thread (1 for 1:1, >1 for group MMS). */
    val addresses: List<String>,
    val snippet: String,
    val dateMillis: Long,
    val messageCount: Int,
    val unreadCount: Int,
)

/** Read side of the Telephony provider (SMS + MMS). All calls are main-safe (they switch to IO). */
interface ProviderReader {
    suspend fun threads(): List<ProviderThread>

    /** Messages in one provider thread, newest first. */
    suspend fun messagesInThread(threadId: Long, limit: Int = Int.MAX_VALUE, offset: Int = 0): List<Message>

    suspend fun message(key: MessageKey): Message?

    /** Newest-first messages with `date >= sinceMillis`, or at least [minCount] most recent, whichever is larger. */
    suspend fun recentMessages(sinceMillis: Long, minCount: Int): List<Message>

    /** Newest-first page across SMS and MMS, for backfill: messages strictly older than [beforeMillis]. */
    suspend fun messagesBefore(beforeMillis: Long, limit: Int): List<Message>

    /** Messages changed/added after the given provider ids (for incremental reconcile). */
    suspend fun messagesAfter(smsIdExclusive: Long, mmsIdExclusive: Long, limit: Int): List<Message>

    suspend fun totalMessageCount(): Int

    /** Highest current `_id` in each table, as (sms, mms). */
    suspend fun maxIds(): Pair<Long, Long>

    /** Every message key currently in the provider (used to detect deletions during reconcile). */
    suspend fun allKeys(): Set<MessageKey>

    /**
     * Send / delivery state of the given (outgoing) messages, read with a narrow projection (no bodies, parts or
     * addresses) so the reconcile can refresh ticks cheaply. Keys no longer in the provider are absent.
     */
    suspend fun outgoingStates(keys: Collection<MessageKey>): Map<MessageKey, OutgoingState> = emptyMap()
}

/** Volatile state of an outgoing message: its box (sending / sent / failed) and delivery report. */
data class OutgoingState(
    val box: MessageBox,
    val deliveryStatus: DeliveryStatus,
    /** When the delivery report arrived, if recorded. */
    val deliveredAtMillis: Long? = null,
)

/** Write side. Writes are verbatim: no dedupe, no reformatting, no delay. */
interface ProviderWriter {
    /** Inserts an incoming SMS into the inbox exactly as received. Returns the new key, or null on failure. */
    suspend fun insertIncomingSms(address: String, body: String, dateSentMillis: Long, dateMillis: Long, subId: Int): MessageKey?

    /** Inserts an outgoing SMS into the outbox (to be moved to SENT / FAILED by status updates). */
    suspend fun insertOutgoingSms(address: String, body: String, subId: Int, threadId: Long? = null): MessageKey?

    suspend fun markSmsStatus(key: MessageKey, status: OutgoingStatus)

    suspend fun markThreadRead(threadId: Long)
    suspend fun markRead(key: MessageKey)

    /** Deletes one message from the provider. The recycle bin copies it first (see :core-index). */
    suspend fun delete(key: MessageKey): Boolean

    /**
     * Re-inserts a previously deleted message (recycle-bin restore / backup restore) into its original thread,
     * preserving box, date, sub id and read state. Returns the new key.
     */
    suspend fun restore(message: Message): MessageKey?

    /** Resolves or creates the provider thread id for the given recipient set. */
    suspend fun threadIdFor(addresses: Set<String>): Long
}

enum class OutgoingStatus { QUEUED, SENDING, SENT, DELIVERED, FAILED }

/** Emits whenever the SMS or MMS provider content changes (ContentObserver), debounced. */
interface ProviderChanges {
    val changes: Flow<Unit>

    /**
     * Asks [changes] to emit once more (debounced like a real change), e.g. when the app returns to the
     * foreground or a periodic reconcile worker fires. Scheduling that worker is the index module's job.
     */
    fun requestReconcile() {}
}

/** Active subscriptions; refreshes on SIM hot-swap / eSIM changes. */
interface SimRepository {
    val sims: StateFlow<List<SimInfo>>
    fun sim(subId: Int): SimInfo?
    /** System default SMS subscription, or [app.dak.core.model.NO_SUB_ID]. */
    fun defaultSmsSubId(): Int
    fun isRoaming(subId: Int): Boolean
    fun refresh()
}

data class OutgoingSms(
    val addresses: List<String>,
    val body: String,
    val subId: Int,
    /** Provider thread to file under, if known. */
    val threadId: Long? = null,
    val requestDeliveryReport: Boolean = true,
)

data class OutgoingMms(
    val addresses: List<String>,
    val text: String?,
    val subId: Int,
    val parts: List<OutgoingMmsPart>,
    val subject: String? = null,
    val threadId: Long? = null,
    /**
     * The user's "Delivery reports" choice. A report is requested only when this AND the carrier's
     * `enableMMSDeliveryReports` (off in AOSP's defaults: many MMSCs ignore them) are on, see
     * [app.dak.telephony.carrier.ReportPolicy].
     */
    val requestDeliveryReport: Boolean = true,
)

data class OutgoingMmsPart(val mimeType: String, val fileName: String, val bytes: ByteArray)

sealed interface SendResult {
    data class Queued(val keys: List<MessageKey>) : SendResult
    data class Failed(val reason: String) : SendResult
}

/**
 * Sends via `SmsManager.getSmsManagerForSubscriptionId(subId)` (multipart when needed), writes to the provider
 * outbox first, tracks sent/delivered intents, queues-and-retries on DSDS / radio failures, and spreads bulk sends
 * to respect the system limit of 30 messages per 30 minutes.
 */
interface MessageSender {
    suspend fun sendSms(sms: OutgoingSms): SendResult
    suspend fun sendMms(mms: OutgoingMms): SendResult
    /** Retries a FAILED message by key. */
    suspend fun retry(key: MessageKey): SendResult

    /** Human-readable reason for the last send failure of [key] (for the "tap to retry" bubble), if any. */
    fun failureReason(key: MessageKey): String? = null
}

/** MMS download state for a notification-indication awaiting retrieval. */
sealed interface MmsDownloadState {
    data object Pending : MmsDownloadState
    data object Downloading : MmsDownloadState
    data object Done : MmsDownloadState
    /** Visible "tap to retry" state carrying the failure reason. */
    data class Failed(val reason: String, val attempts: Int) : MmsDownloadState
}

interface MmsDownloads {
    fun state(key: MessageKey): Flow<MmsDownloadState>
    suspend fun retry(key: MessageKey)

    /**
     * After a successful download the notification-ind row is replaced by the retrieved message (a new provider
     * row); this returns the new key for the old one, if known.
     */
    fun replacementFor(key: MessageKey): MessageKey? = null
}

/**
 * [Message.providerId] of a message handed to [IncomingMessageHandler]s when writing it to the provider failed
 * (e.g. the role was revoked mid-delivery). The notification path must still show it; indexers should skip it.
 */
const val UNPERSISTED_PROVIDER_ID: Long = -1L

/**
 * Hook invoked by the receivers after an incoming message has been written to the provider.
 * Implementations are contributed via Hilt `@IntoSet` (e.g. the notification poster in :app and the indexer in
 * :core-index). Must return quickly; heavy work should be enqueued.
 */
interface IncomingMessageHandler {
    /** Lower runs first. Notifications use 0; indexing uses 100. */
    val priority: Int get() = 50
    suspend fun onIncoming(message: Message)
}

/**
 * Told when an outgoing message has left the phone (the radio / MMSC confirmed it: SMS all parts SENT, MMS send-conf
 * OK), after the provider row is marked sent. Contributed via Hilt `@IntoSet`; called at most once per successful
 * attempt, off the main thread, inside the sent-broadcast budget: must return quickly (enqueue heavy work).
 */
interface OutgoingSentListener {
    suspend fun onSent(key: MessageKey)
}

/** Shared system block list (`BlockedNumberContract`), writable only while we are the default SMS app. */
interface BlockedNumbers {
    suspend fun isBlocked(address: String): Boolean
    suspend fun block(address: String): Boolean
    suspend fun unblock(address: String): Boolean
    suspend fun list(): List<String>
}

/** E.164 normalisation using the SIM's home country, never the current network. */
interface NumberNormalizer {
    /** Returns the E.164 form, or the input unchanged for short codes (<7 digits) and alphanumeric sender ids. */
    fun normalize(address: String, subId: Int): String
    /** Key used for thread matching (normalised when possible). */
    fun matchKey(address: String, subId: Int): String
}
