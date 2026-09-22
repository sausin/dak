package app.dak.index.sync

import android.util.Log
import app.dak.core.model.Message
import app.dak.index.otp.OtpLifecycle
import app.dak.index.signature.AppSignatureRegistry
import app.dak.telephony.IncomingMessageHandler
import app.dak.telephony.ProviderChanges
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Indexes each new incoming message right after the receiver wrote it to the provider (priority 100, after the
 * notification handler), then schedules its OTP auto-delete if it is an OTP. Contributed to the
 * `Set<IncomingMessageHandler>` by `IndexModule`.
 */
class IncomingIndexer @Inject constructor(
    // Lazy: receivers inject the handler set on the main thread; the database is opened on first use (on IO).
    private val ingestor: Lazy<IndexIngestor>,
    private val otpLifecycle: Lazy<OtpLifecycle>,
) : IncomingMessageHandler {
    override val priority: Int get() = PRIORITY

    override suspend fun onIncoming(message: Message) {
        withContext(Dispatchers.IO) {
            val row = ingestor.get().ingest(listOf(message), allowCloud = true, refreshSignaturesOnMiss = true).firstOrNull()
            if (row != null) otpLifecycle.get().onIndexed(row)
        }
    }

    companion object {
        const val PRIORITY = 100
    }
}

/**
 * App-scoped index synchronisation. The app calls [start] once from `Application.onCreate`; it:
 * 1. schedules the periodic reconcile (6 h) and bin purge (daily) workers;
 * 2. refreshes the app-signature table (for consumed-OTP detection);
 * 3. runs / resumes the backfill ([IndexMaintenance.ensureStarted]);
 * 4. collects `ProviderChanges` for its lifetime and reconciles incrementally on each change.
 */
@Singleton
class IndexSync @Inject constructor(
    // Lazy so that injecting IndexSync into the Application does not open the database on the main thread.
    private val maintenance: Lazy<IndexMaintenance>,
    private val reconciler: Lazy<ProviderReconciler>,
    private val signatures: Lazy<AppSignatureRegistry>,
    private val changes: ProviderChanges,
    private val scheduler: BackfillScheduler,
) {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Idempotent; safe to call from the main thread (all work is launched on IO). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            runCatching { scheduler.schedulePeriodic() }.onFailure { Log.w(TAG, "periodic scheduling failed", it) }
            runCatching { maintenance.get().ensureStarted() }.onFailure { Log.w(TAG, "backfill start failed", it) }
        }
        scope.launch {
            runCatching { signatures.get().refresh() }.onFailure { Log.w(TAG, "signature refresh failed", it) }
        }
        scope.launch {
            changes.changes.conflate().collect {
                runCatching { reconciler.get().incremental() }.onFailure { Log.w(TAG, "incremental reconcile failed", it) }
            }
        }
    }

    /** Runs one incremental reconcile now (e.g. when a thread screen resumes). */
    fun requestReconcile() {
        scope.launch { runCatching { reconciler.get().incremental() } }
    }

    private companion object {
        const val TAG = "DakIndex"
    }
}
