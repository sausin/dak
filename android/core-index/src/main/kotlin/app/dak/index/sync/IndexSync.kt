package app.dak.index.sync

import android.util.Log
import app.dak.core.model.Message
import app.dak.index.maintenance.MaintenanceScheduler
import app.dak.index.otp.OtpLifecycle
import app.dak.index.signature.AppSignatureRegistry
import app.dak.telephony.IncomingMessageHandler
import app.dak.telephony.ProviderChanges
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    private val activity: BackgroundActivityLog,
) : IncomingMessageHandler {
    override val priority: Int get() = PRIORITY

    override suspend fun onIncoming(message: Message) {
        withContext(Dispatchers.IO) {
            val row = ingestor.get().ingest(listOf(message), allowCloud = true, refreshSignaturesOnMiss = true).firstOrNull()
            if (row != null) otpLifecycle.get().onIndexed(row)
            activity.record(BackgroundActivityLog.INCOMING)
        }
    }

    companion object {
        const val PRIORITY = 100
    }
}

/**
 * App-scoped index synchronisation. The app calls [start] once from `Application.onCreate`; it:
 * 1. makes sure the single daily maintenance job is scheduled (a no-op after the first run);
 * 2. builds the app-signature table the first time only (afterwards: daily maintenance + on an unknown hash);
 * 3. runs / resumes the backfill ([IndexMaintenance.ensureStarted]);
 * 4. collects `ProviderChanges` while the process lives and reconciles incrementally, coalescing bursts into one
 *    run per [COALESCE_MILLIS].
 *
 * Battery: a process start happens for nearly every incoming SMS, so [start] does no scans or scheduling beyond
 * cheap checks. The observer never wakes the device by itself; it only reacts while the process is alive anyway.
 */
@Singleton
class IndexSync @Inject constructor(
    // Lazy so that injecting IndexSync into the Application does not build the index graph on the main thread.
    private val maintenance: Lazy<IndexMaintenance>,
    private val reconciler: Lazy<ProviderReconciler>,
    private val signatures: Lazy<AppSignatureRegistry>,
    private val changes: ProviderChanges,
    private val maintenanceScheduler: MaintenanceScheduler,
    private val activity: BackgroundActivityLog,
) {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconcileSignals = Channel<Unit>(Channel.CONFLATED)

    /** Idempotent; safe to call from the main thread (all work is launched on IO). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            activity.record(BackgroundActivityLog.PROCESS_START)
            runCatching { maintenanceScheduler.ensureScheduled() }.onFailure { Log.w(TAG, "maintenance scheduling failed", it) }
            runCatching { maintenance.get().ensureStarted() }.onFailure { Log.w(TAG, "backfill start failed", it) }
        }
        scope.launch {
            runCatching { signatures.get().refreshIfNeverBuilt() }.onFailure { Log.w(TAG, "signature refresh failed", it) }
        }
        scope.launch {
            changes.changes.collect { reconcileSignals.trySend(Unit) }
        }
        scope.launch {
            for (signal in reconcileSignals) {
                // Coalesce: a receiver write, our own read-state writes and OEM duplicate notifications in the
                // next few seconds all go into this one run. Changes during the run trigger exactly one more.
                delay(COALESCE_MILLIS)
                reconcileSignals.tryReceive()
                activity.record(BackgroundActivityLog.RECONCILE)
                runCatching { reconciler.get().incremental() }.onFailure { Log.w(TAG, "incremental reconcile failed", it) }
            }
        }
    }

    /** Requests an incremental reconcile (e.g. when a thread screen resumes); coalesced with provider changes. */
    fun requestReconcile() {
        reconcileSignals.trySend(Unit)
    }

    private companion object {
        const val TAG = "DakIndex"
        const val COALESCE_MILLIS = 3_000L
    }
}
