package app.dak.index.otp

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.dak.classify.OtpExtractor
import app.dak.core.model.Message
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.index.ConsumedOtpMode
import app.dak.index.DefaultOtpPolicy
import app.dak.index.OtpPolicy
import app.dak.index.bin.DeletedBy
import app.dak.index.bin.RecycleBin
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.entity.IndexedMessage
import app.dak.index.signature.AppSignatureRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Optional
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OTP lifecycle: auto-delete of OTP messages into the bin a set time after arrival (default 24 h, or off), and the
 * shorter path for OTPs an app already consumed via SMS Retriever / WebOTP (default 10 min, never under 5, so a
 * retrying app can still read it).
 *
 * Battery: there is **one** job for all pending deletes, not one per message. Pending deletes live in
 * [OtpDeleteQueue]; a single unique WorkManager job ([SWEEP_WORK], no exact alarm) is armed for the most urgent
 * deadline and deletes everything due in one batch ([OtpSweepPlan] tolerance windows), then re-arms. Survives
 * process death and reboots (WorkManager persists it).
 */
@Singleton
class OtpLifecycle @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recycleBin: RecycleBin,
    private val signatures: AppSignatureRegistry,
    private val queue: OtpDeleteQueue,
    private val db: DakIndexDatabase,
    policy: Optional<OtpPolicy>,
) {
    private val policy: OtpPolicy = policy.orElse(DefaultOtpPolicy)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sweepLock = Mutex()

    /**
     * Queues the auto-delete for a freshly indexed incoming OTP (no-op for other messages). Idempotent: an already
     * queued deletion for the same message is kept.
     */
    suspend fun onIndexed(row: IndexedMessage, nowMillis: Long = System.currentTimeMillis()) {
        if (row.otpCode == null || row.box != MessageBox.INBOX || row.starred) return
        val key = MessageKey(row.kind, row.providerId)
        val consumedBy = row.otpConsumedBy
        if (consumedBy != null) {
            when (policy.consumedOtpMode()) {
                ConsumedOtpMode.SILENT_AUTO_DELETE -> {
                    val lifetime = OtpTiming.clampConsumedDelay(policy.consumedOtpDeleteAfterMillis())
                    queueIfLive(key, row.dateMillis, lifetime, nowMillis, DeletedBy.AutoConsumed(consumedBy))
                    return
                }
                ConsumedOtpMode.SILENT_ONLY, ConsumedOtpMode.NORMAL -> Unit
            }
        }
        val lifetime = policy.otpAutoDeleteAfterMillis() ?: return
        queueIfLive(key, row.dateMillis, lifetime, nowMillis, DeletedBy.AutoOtp)
    }

    /**
     * Never deletes retroactively: an OTP discovered after its lifetime already passed (e.g. re-inserted by a
     * backup restore) is left alone.
     */
    private suspend fun queueIfLive(key: MessageKey, arrivedAt: Long, lifetime: Long, nowMillis: Long, deletedBy: DeletedBy) {
        if (arrivedAt + lifetime <= nowMillis) return
        val deleteAt = nowMillis + OtpTiming.remainingDelay(arrivedAt, lifetime, nowMillis)
        withContext(Dispatchers.IO) {
            if (queue.add(OtpDeleteEntry(key.toString(), deleteAt, deletedBy.encoded))) rearm(nowMillis)
        }
    }

    /**
     * Whether the notification for [message] should be silent (no heads-up, no sound): true for an OTP that an
     * installed app or browser consumed, unless the user chose "treat as normal". Callable before indexing (the
     * notification handler runs first).
     */
    suspend fun shouldNotifySilently(message: Message): Boolean {
        if (message.box != MessageBox.INBOX) return false
        val otp = OtpExtractor.extract(message.body) ?: return false
        if (otp.retrieverHash == null && otp.webOtpDomain == null) return false
        if (policy.consumedOtpMode() == ConsumedOtpMode.NORMAL) return false
        return signatures.consumerOf(otp, refreshOnMiss = true) != null
    }

    /** The "delete now" quick action on the notification / bubble. */
    suspend fun deleteNow(key: MessageKey): Boolean {
        withContext(Dispatchers.IO) { queue.remove(listOf(key.toString())) }
        return recycleBin.moveToBin(listOf(key), DeletedBy.Manual).binIds.isNotEmpty()
    }

    /** Cancels a pending auto-delete (e.g. the user starred or copied the message). Main-safe; returns at once. */
    fun cancel(key: MessageKey) {
        scope.launch { queue.remove(listOf(key.toString())) }
    }

    /**
     * Deletes every due OTP in one batch and re-arms the job for the next deadline. Called by [OtpSweepWorker].
     * Returns the number of messages moved to the bin. Each batch runs to completion even if the job is cancelled
     * meanwhile (a copied-but-not-deleted message would otherwise be possible).
     */
    suspend fun sweep(nowMillis: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        sweepLock.withLock {
            val due = OtpSweepPlan.due(queue.all(), nowMillis)
            var binned = 0
            if (due.isNotEmpty()) {
                val autoOn = policy.otpAutoDeleteAfterMillis() != null
                val consumedOn = policy.consumedOtpMode() == ConsumedOtpMode.SILENT_AUTO_DELETE
                withContext(NonCancellable) {
                    for ((deletedBy, entries) in due.groupBy { it.deletedBy }) {
                        // A policy switched off since queuing wins: the message simply stays.
                        val allowed = if (entries.first().consumed) consumedOn else autoOn
                        val keys = if (allowed) entries.mapNotNull { MessageKey.parse(it.key) }.filter { deletable(it) } else emptyList()
                        // A failed provider delete (e.g. no longer the default SMS app) is not retried.
                        if (keys.isNotEmpty()) binned += recycleBin.moveToBin(keys, DeletedBy.decode(deletedBy)).binIds.size
                        queue.remove(entries.map { it.key })
                    }
                }
            }
            queue.armedAtMillis = 0L
            rearm(nowMillis)
            binned
        }
    }

    /** Still indexed and not starred (a starred message is never auto-deleted). */
    private suspend fun deletable(key: MessageKey): Boolean {
        val row = db.messageDao().get(key.kind.name, key.providerId) ?: return false
        return !row.starred
    }

    /**
     * Arms the single sweep job for the most urgent pending deadline, unless an armed run already covers it.
     * `REPLACE` from inside a running sweep only cancels that (already finished) run.
     */
    private fun rearm(nowMillis: Long) {
        val next = OtpSweepPlan.nextRunAt(queue.all())
        val wm = runCatching { WorkManager.getInstance(context) }.getOrElse {
            Log.w(TAG, "WorkManager unavailable", it)
            return
        }
        if (next == null) {
            if (queue.armedAtMillis != 0L) {
                wm.cancelUniqueWork(SWEEP_WORK)
                queue.armedAtMillis = 0L
            }
            return
        }
        if (OtpSweepPlan.armedCovers(queue.armedAtMillis, next, nowMillis)) return
        val request = OneTimeWorkRequestBuilder<OtpSweepWorker>()
            .setInitialDelay((next - nowMillis).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(SWEEP_WORK)
            .build()
        wm.enqueueUniqueWork(SWEEP_WORK, ExistingWorkPolicy.REPLACE, request)
        queue.armedAtMillis = next
    }

    companion object {
        /** Unique work name of the single OTP sweep job. */
        const val SWEEP_WORK = "dak-otp-sweep"
        private const val TAG = "DakIndex"
    }
}
