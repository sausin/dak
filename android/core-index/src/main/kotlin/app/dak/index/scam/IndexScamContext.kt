package app.dak.index.scam

import app.dak.classify.scam.AccountHint
import app.dak.classify.scam.FakeCreditDetector
import app.dak.classify.scam.RecentMessage
import app.dak.classify.scam.ScamLabels
import app.dak.core.model.Message
import app.dak.core.model.MessageKey
import app.dak.index.db.DakIndexDatabase
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** [ScamContextSource] backed by the index (ledger accounts, recent rows) and [ScamOverrides]. */
@Singleton
class IndexScamContext @Inject constructor(
    private val db: DakIndexDatabase,
    private val overrides: ScamOverrides,
) : ScamContextSource {

    @Volatile
    private var accountsCache: Pair<Long, Set<AccountHint>>? = null

    override fun isDismissed(key: MessageKey): Boolean = overrides.isDismissed(key)

    override suspend fun knownAccounts(): Set<AccountHint> {
        val now = System.currentTimeMillis()
        accountsCache?.let { (at, value) -> if (now - at < ACCOUNTS_TTL_MILLIS) return value }
        val accounts = runCatching { db.ledgerDao().observeAccounts().first() }.getOrDefault(emptyList())
        val hints = accounts.mapNotNullTo(HashSet<AccountHint>()) { row ->
            val digits = (row.maskedNumber ?: row.last4)?.filter { it.isDigit() }
            if (digits.isNullOrEmpty() || row.institution.isBlank()) null else AccountHint(row.institution, digits)
        }
        accountsCache = now to hints
        return hints
    }

    override suspend fun recentMessages(message: Message): List<RecentMessage> {
        val rows = runCatching {
            db.messageDao().recentForScamCheck(
                address = message.address,
                fromMillis = message.dateMillis - FakeCreditDetector.FOLLOW_UP_WINDOW_MILLIS,
                toMillis = message.dateMillis,
                likely = ScamLabels.likePattern(ScamLabels.LIKELY),
                suspicious = ScamLabels.likePattern(ScamLabels.SUSPICIOUS),
                limit = RECENT_LIMIT,
            )
        }.getOrDefault(emptyList())
        return rows
            .filterNot { it.kind == message.kind && it.providerId == message.providerId }
            .map { RecentMessage(it.address, it.body, it.dateMillis, ScamLabels.isFlaggedCredit(it.labels)) }
    }

    private companion object {
        const val ACCOUNTS_TTL_MILLIS = 5 * 60_000L
        const val RECENT_LIMIT = 30
    }
}
