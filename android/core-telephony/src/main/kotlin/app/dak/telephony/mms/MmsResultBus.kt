package app.dak.telephony.mms

import app.dak.core.model.MessageKey
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred

/** Result of one MMS download attempt, as seen by the waiting worker. */
sealed interface DownloadOutcome {
    /** Stored; [newKey] is the retrieved message (null if another attempt already stored it). */
    data class Success(val newKey: MessageKey?) : DownloadOutcome

    data class Failed(val reason: String, val retryable: Boolean) : DownloadOutcome
}

/**
 * Hands download results from [MmsDownloadedReceiver] to the [MmsDownloadWorker] waiting in the same process,
 * so WorkManager's backoff can drive retries. Results are persisted by the receiver regardless, so nothing is lost
 * when no worker is waiting (e.g. after process death).
 */
@Singleton
class MmsResultBus @Inject constructor() {
    private val waiters = ConcurrentHashMap<Long, CompletableDeferred<DownloadOutcome>>()

    fun register(id: Long): CompletableDeferred<DownloadOutcome> =
        CompletableDeferred<DownloadOutcome>().also { waiters[id] = it }

    fun unregister(id: Long, waiter: CompletableDeferred<DownloadOutcome>) {
        waiters.remove(id, waiter)
    }

    fun complete(id: Long, outcome: DownloadOutcome) {
        waiters.remove(id)?.complete(outcome)
    }
}
