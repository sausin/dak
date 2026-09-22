package app.dak.telephony.provider

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.dak.telephony.ProviderChanges
import app.dak.telephony.di.TelephonyScope
import app.dak.telephony.internal.TAG
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/**
 * [ProviderChanges] backed by one ContentObserver on `content://mms-sms/` (plus `content://sms` and
 * `content://mms`, which some OEM providers notify separately), registered while anyone collects.
 *
 * Bursts are coalesced: the first change starts a [DEBOUNCE_MILLIS] window, changes inside it are absorbed and
 * one Unit is emitted at its end. Under a continuous stream this emits at most once per window, so collectors
 * are never starved.
 */
@Singleton
class TelephonyProviderChanges @Inject constructor(
    @ApplicationContext private val context: Context,
    @TelephonyScope scope: CoroutineScope,
) : ProviderChanges {

    private val manual = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val changes: Flow<Unit> = callbackFlow {
        val signals = Channel<Unit>(Channel.CONFLATED)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                signals.trySend(Unit)
            }
        }
        val resolver = context.contentResolver
        for (uri in listOf(ProviderUris.MMS_SMS, ProviderUris.SMS, ProviderUris.MMS)) {
            try {
                resolver.registerContentObserver(uri, true, observer)
            } catch (e: Exception) {
                Log.w(TAG, "cannot observe ${uri.authority}: ${e.javaClass.simpleName}")
            }
        }
        launch { manual.collect { signals.trySend(Unit) } }
        launch {
            for (signal in signals) {
                delay(DEBOUNCE_MILLIS)
                while (signals.tryReceive().isSuccess) {
                    // absorb changes that arrived during the window
                }
                send(Unit)
            }
        }
        awaitClose {
            resolver.unregisterContentObserver(observer)
            signals.close()
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), replay = 0)

    override fun requestReconcile() {
        manual.tryEmit(Unit)
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 300L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
