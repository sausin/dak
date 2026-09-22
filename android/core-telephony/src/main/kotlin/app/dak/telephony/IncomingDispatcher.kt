package app.dak.telephony

import android.util.Log
import app.dak.core.model.Message
import app.dak.telephony.internal.TAG
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs every contributed [IncomingMessageHandler] for a newly received message, lowest priority first. Each
 * handler is isolated: an exception or a slow handler (over [HANDLER_TIMEOUT_MILLIS]) is logged and skipped, so
 * one broken consumer can never stop the notification or the index from seeing the message.
 */
@Singleton
class IncomingDispatcher @Inject constructor(
    handlers: Set<@JvmSuppressWildcards IncomingMessageHandler>,
) {
    private val ordered: List<IncomingMessageHandler> = handlers.sortedBy { it.priority }

    suspend fun dispatch(message: Message) {
        for (handler in ordered) {
            try {
                val completed = withTimeoutOrNull(HANDLER_TIMEOUT_MILLIS) { handler.onIncoming(message) }
                if (completed == null) Log.w(TAG, "handler ${handler.javaClass.simpleName} timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "handler ${handler.javaClass.simpleName} failed", e)
            }
        }
    }

    companion object {
        /** Per-handler budget; receivers must finish well within the 10 s broadcast limit. */
        const val HANDLER_TIMEOUT_MILLIS: Long = 2_500L
    }
}
