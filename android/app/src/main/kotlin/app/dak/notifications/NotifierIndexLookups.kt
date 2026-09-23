package app.dak.notifications

import android.util.Log
import app.dak.core.model.OtpInfo
import app.dak.index.db.DakIndexDatabase
import app.dak.index.enrich.ConversationIds
import app.dak.index.enrich.SenderGrouping
import app.dak.index.repo.FoldEngine
import app.dak.index.signature.AppSignatureRegistry
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two index lookups the notification path needs, kept small and bounded because they run inside the SMS
 * receiver's budget, before the message itself is indexed:
 * - [consumerOf]: the app that auto-reads an OTP, from the persisted app-hash table (no package scan; an unknown
 *   hash is refreshed later by the index handler, never here);
 * - [isMuted]: whether the conversation this message will land in is muted (its conversation id is computed with
 *   the same grouping rules the indexer uses, plus the provider thread id).
 *
 * The index is resolved lazily (receivers construct the notifier on the main thread) and every lookup runs on IO
 * with a timeout; on timeout or error the answer is "no consumer" / "not muted", so a notification is never lost.
 */
@Singleton
class NotifierIndexLookups @Inject constructor(
    private val signatures: Lazy<AppSignatureRegistry>,
    private val folds: Lazy<FoldEngine>,
    private val db: Lazy<DakIndexDatabase>,
) {
    /** Package that consumed [otp] (SMS Retriever hash or WebOTP), or null. */
    suspend fun consumerOf(otp: OtpInfo): String? {
        if (otp.retrieverHash == null && otp.webOtpDomain == null) return null
        return bounded("consumer lookup") { signatures.get().consumerOf(otp, refreshOnMiss = false) }
    }

    /** True when the conversation of a message from [address] in provider thread [threadId] is muted. */
    suspend fun isMuted(address: String, threadId: Long): Boolean =
        bounded("mute lookup") {
            val grouped = SenderGrouping.resolve(address, threadId, folds.get().rules()).conversationId
            val ids = listOf(grouped, ConversationIds.forThread(threadId)).distinct()
            db.get().conversationPrefsDao().getAll(ids).any { it.muted }
        } ?: false

    private suspend fun <T> bounded(what: String, block: suspend () -> T): T? = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(TIMEOUT_MILLIS) { block() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        const val TAG = "DakNotify"
        /** Leaves most of the handler's 2.5 s budget for building and posting (first DB open included). */
        const val TIMEOUT_MILLIS = 900L
    }
}
