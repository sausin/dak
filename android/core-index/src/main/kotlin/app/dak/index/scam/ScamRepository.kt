package app.dak.index.scam

import app.dak.classify.scam.ScamLabels
import app.dak.core.model.MessageKey
import app.dak.index.db.DakIndexDatabase
import app.dak.index.sync.IndexIngestor
import app.dak.index.sync.IndexRowMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-facing API of fake-credit warnings stored in the index (labels from [ScamLabels]):
 * the conversations to mark in the inbox, the user's "Not a scam" override and the feature switch.
 */
@Singleton
class ScamRepository @Inject constructor(
    private val db: DakIndexDatabase,
    private val ingestor: IndexIngestor,
    private val overrides: ScamOverrides,
) {
    /** Ids of conversations with a flagged (likely or suspicious, not dismissed) message in the last 30 days. */
    fun flaggedConversations(): Flow<Set<String>> =
        db.messageDao()
            .observeScamFlaggedConversations(
                sinceMillis = System.currentTimeMillis() - FLAGGED_WINDOW_MILLIS,
                likely = ScamLabels.likePattern(ScamLabels.LIKELY),
                suspicious = ScamLabels.likePattern(ScamLabels.SUSPICIOUS),
            )
            .map { it.toSet() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    /**
     * "Not a scam": remembers the decision (it survives re-indexing) and re-enriches the message, which replaces its
     * warning labels with [ScamLabels.DISMISSED] and lets a genuine credit back into the ledger.
     */
    suspend fun dismiss(key: MessageKey): Unit = withContext(Dispatchers.IO) {
        overrides.dismiss(key)
        val row = db.messageDao().get(key.kind.name, key.providerId) ?: return@withContext
        ingestor.ingest(listOf(IndexRowMapper.toMessage(row)), force = true)
    }

    /** Mirrors the "Warn about fake credit alerts" setting for the index enricher (call when it changes). */
    fun setEnabled(enabled: Boolean) = overrides.setEnabled(enabled)

    private companion object {
        const val FLAGGED_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
