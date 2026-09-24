package app.dak.telephony

import android.util.Log
import app.dak.core.model.MessageKey
import app.dak.telephony.internal.TAG
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tells every contributed [OutgoingSentListener] that a message left the phone. Each listener is isolated like the
 * incoming handlers ([IncomingDispatcher]): a failure or a slow listener is logged and skipped, and can never undo
 * or delay the provider's "sent" status, which is written before this runs.
 */
@Singleton
class SentDispatcher @Inject constructor(
    // A Provider, so listeners may themselves depend on telephony classes without creating a Dagger cycle.
    private val listeners: Provider<Set<@JvmSuppressWildcards OutgoingSentListener>>,
) {
    suspend fun dispatch(key: MessageKey) {
        for (listener in listeners.get()) {
            try {
                val completed = withTimeoutOrNull(IncomingDispatcher.HANDLER_TIMEOUT_MILLIS) { listener.onSent(key) }
                if (completed == null) Log.w(TAG, "sent listener ${listener.javaClass.simpleName} timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "sent listener ${listener.javaClass.simpleName} failed", e)
            }
        }
    }
}
