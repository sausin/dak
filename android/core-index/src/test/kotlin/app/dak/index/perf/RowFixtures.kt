package app.dak.index.perf

import app.dak.core.model.Category
import app.dak.core.model.ClassifierSource
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import app.dak.index.db.entity.ConversationPrefs
import app.dak.index.db.entity.IndexedMessage
import app.dak.index.db.entity.SenderMergeGroup
import app.dak.index.sync.IndexRowMapper
import kotlin.random.Random

/** An index row with the derived columns (preview, FTS text) computed as the ingestor computes them. */
internal fun indexedRow(
    providerId: Long,
    threadId: Long,
    address: String,
    mergeKey: String,
    conversationId: String,
    dateMillis: Long,
    body: String,
    kind: MessageKind = MessageKind.SMS,
    subId: Int = 1,
    box: MessageBox = MessageBox.INBOX,
    read: Boolean = false,
    category: Category = Category.UNKNOWN,
    canonicalSender: String? = null,
    labels: Set<String> = emptySet(),
    otpCode: String? = null,
    hasLink: Boolean = false,
    hasAttachment: Boolean = false,
    amountMinor: Long? = null,
    starred: Boolean = false,
    archived: Boolean = false,
    repeatGroup: String? = null,
    deliveryStatus: Int = -1,
): IndexedMessage = IndexedMessage(
    kind = kind,
    providerId = providerId,
    threadId = threadId,
    subId = subId,
    address = address,
    mergeKey = mergeKey,
    conversationId = conversationId,
    dateMillis = dateMillis,
    box = box,
    read = read,
    seen = read,
    category = category,
    confidence = 0.9f,
    classifierSource = ClassifierSource.MODEL,
    canonicalSender = canonicalSender,
    labels = labels,
    otpCode = otpCode,
    otpConsumedBy = null,
    retrieverHash = null,
    webOtpDomain = null,
    hasLink = hasLink,
    hasAttachment = hasAttachment,
    amountMinor = amountMinor,
    currency = amountMinor?.let { "INR" },
    direction = null,
    instrumentLast4 = null,
    merchant = null,
    accountId = null,
    transactionJson = null,
    starred = starred,
    archived = archived,
    indexedAt = 0L,
    body = body,
    bodyPreview = IndexRowMapper.preview(body),
    attachmentsJson = "[]",
    templateVersion = 1,
    searchText = IndexRowMapper.searchText(body),
    searchSender = IndexRowMapper.searchSender(address, canonicalSender),
    repeatGroup = repeatGroup,
    deliveryStatus = deliveryStatus,
    deliveredAtMillis = null,
)

/** Rows, conversation prefs and merge-group names for the inbox tests. */
internal class InboxData(
    val messages: List<IndexedMessage>,
    val prefs: List<ConversationPrefs>,
    val groups: List<SenderMergeGroup>,
)

/**
 * A synthetic inbox: [rows] messages over [conversations] conversations (merge groups spanning two sender headers and
 * two threads, one-to-one threads with some MMS, a skewed size distribution), every category, two SIMs (some
 * conversations on both), unread and sent messages, individually starred / archived messages, repeat groups, delivery
 * states, and conversation prefs (pinned, muted, archived, starred, incognito). Every date is distinct: with a tie,
 * SQLite alone picks which newest message a conversation shows, so ties are pinned separately.
 */
internal object InboxCorpus {
    fun generate(rows: Int, conversations: Int, seed: Long = 1L): InboxData {
        val rnd = Random(seed)
        class Conv(
            val id: String,
            val mergeKey: String,
            val addresses: List<String>,
            val threads: List<Long>,
            val canonical: String?,
            val category: Category,
            val subIds: List<Int>,
        )
        val convs = (0 until conversations).map { c ->
            if (rnd.nextInt(10) < 4) {
                val key = "BRAND$c"
                Conv(
                    id = "m:$key",
                    mergeKey = key,
                    addresses = listOf("VM-$key", "JD-$key"),
                    threads = listOf(1_000L + 2 * c, 1_001L + 2 * c),
                    canonical = if (rnd.nextBoolean()) "Brand $c" else null,
                    category = listOf(Category.TRANSACTION, Category.OTP, Category.PROMOTION, Category.SPAM, Category.UNKNOWN)[rnd.nextInt(5)],
                    subIds = if (rnd.nextInt(10) < 3) listOf(1, 2) else listOf(1 + rnd.nextInt(2)),
                )
            } else {
                val thread = 50_000L + c
                val address = "+9198765" + (10_000 + c)
                Conv(
                    id = "t:$thread",
                    mergeKey = address,
                    addresses = listOf(address),
                    threads = listOf(thread),
                    canonical = null,
                    category = if (rnd.nextInt(10) < 7) Category.PERSONAL else listOf(Category.SPAM, Category.UNKNOWN, Category.TRANSACTION)[rnd.nextInt(3)],
                    subIds = if (rnd.nextInt(10) < 3) listOf(1, 2) else listOf(1 + rnd.nextInt(2)),
                )
            }
        }
        val dates = (0 until rows).map { START_MILLIS + it * 41_000L + rnd.nextLong(40_000L) }.shuffled(rnd)
        var smsId = 0L
        var mmsId = 0L
        var repeat = 0
        val out = ArrayList<IndexedMessage>(rows)
        for (i in 0 until rows) {
            val r = rnd.nextDouble()
            val conv = convs[(conversations * r * r).toInt().coerceAtMost(conversations - 1)]
            val a = rnd.nextInt(conv.addresses.size)
            val personal = conv.category == Category.PERSONAL
            val mms = personal && rnd.nextInt(20) == 0
            val sent = personal && rnd.nextInt(3) == 0
            val category = if (rnd.nextInt(5) == 0) Category.entries[rnd.nextInt(Category.entries.size)] else conv.category
            val body = "message $i for ${conv.id} ${listOf("hello", "payment", "otp", "offer", "parcel")[rnd.nextInt(5)]}"
            out += indexedRow(
                providerId = if (mms) ++mmsId else ++smsId,
                kind = if (mms) MessageKind.MMS else MessageKind.SMS,
                threadId = conv.threads[a.coerceAtMost(conv.threads.size - 1)],
                address = if (mms) "${conv.addresses[0]} +919999900000" else conv.addresses[a],
                mergeKey = conv.mergeKey,
                conversationId = conv.id,
                dateMillis = dates[i],
                body = body,
                subId = conv.subIds[rnd.nextInt(conv.subIds.size)],
                box = if (sent) MessageBox.SENT else MessageBox.INBOX,
                read = rnd.nextInt(10) < 6,
                category = category,
                canonicalSender = conv.canonical,
                hasAttachment = mms || rnd.nextInt(40) == 0,
                starred = rnd.nextInt(50) == 0,
                archived = rnd.nextInt(50) == 0,
                repeatGroup = if (rnd.nextInt(30) == 0) "rg${repeat++ / 2}" else null,
                deliveryStatus = if (sent) listOf(-1, 0, 32, 64)[rnd.nextInt(4)] else -1,
            )
        }
        val prefs = convs.filter { rnd.nextInt(10) < 3 }.map { c ->
            ConversationPrefs(
                conversationId = c.id,
                pinned = rnd.nextInt(4) == 0,
                muted = rnd.nextInt(5) == 0,
                archived = rnd.nextInt(5) == 0,
                starred = rnd.nextInt(5) == 0,
                incognitoSince = if (rnd.nextInt(6) == 0) START_MILLIS else null,
            )
        }
        val groups = convs.filter { it.id.startsWith("m:") && rnd.nextBoolean() }.map { c ->
            SenderMergeGroup(c.mergeKey, "Group ${c.mergeKey}", userEdited = false, previousDisplayName = null, previousUserEdited = false, updatedAt = 0L)
        }
        return InboxData(out, prefs, groups)
    }
}
