package app.dak.index.otp

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.dak.classify.OtpExtractor
import app.dak.core.model.Message
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.index.ConsumedOtpMode
import app.dak.index.DefaultOtpPolicy
import app.dak.index.OtpPolicy
import app.dak.index.bin.DeletedBy
import app.dak.index.bin.RecycleBin
import app.dak.index.db.entity.IndexedMessage
import app.dak.index.signature.AppSignatureRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Optional
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OTP lifecycle: auto-delete of OTP messages into the bin a set time after arrival (default 24 h, or off), and the
 * shorter path for OTPs an app already consumed via SMS Retriever / WebOTP (default 10 min, never under 5, so a
 * retrying app can still read it). Deletion is a WorkManager one-shot per message, so it survives process death.
 */
@Singleton
class OtpLifecycle @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recycleBin: RecycleBin,
    private val signatures: AppSignatureRegistry,
    policy: Optional<OtpPolicy>,
) {
    private val policy: OtpPolicy = policy.orElse(DefaultOtpPolicy)

    /**
     * Schedules the auto-delete for a freshly indexed incoming OTP (no-op for other messages). Idempotent: an
     * already scheduled deletion for the same message is kept.
     */
    suspend fun onIndexed(row: IndexedMessage, nowMillis: Long = System.currentTimeMillis()) {
        if (row.otpCode == null || row.box != MessageBox.INBOX || row.starred) return
        val key = MessageKey(row.kind, row.providerId)
        val consumedBy = row.otpConsumedBy
        if (consumedBy != null) {
            when (policy.consumedOtpMode()) {
                ConsumedOtpMode.SILENT_AUTO_DELETE -> {
                    val lifetime = OtpTiming.clampConsumedDelay(policy.consumedOtpDeleteAfterMillis())
                    schedule(key, OtpTiming.remainingDelay(row.dateMillis, lifetime, nowMillis), DeletedBy.AutoConsumed(consumedBy))
                    return
                }
                ConsumedOtpMode.SILENT_ONLY, ConsumedOtpMode.NORMAL -> Unit
            }
        }
        val lifetime = policy.otpAutoDeleteAfterMillis() ?: return
        schedule(key, OtpTiming.remainingDelay(row.dateMillis, lifetime, nowMillis), DeletedBy.AutoOtp)
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
        cancel(key)
        return recycleBin.moveToBin(listOf(key), DeletedBy.Manual).binIds.isNotEmpty()
    }

    /** Cancels a pending auto-delete (e.g. the user starred or copied the message). */
    fun cancel(key: MessageKey) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(key))
    }

    private fun schedule(key: MessageKey, delayMillis: Long, deletedBy: DeletedBy) {
        val request = OneTimeWorkRequestBuilder<OtpDeleteWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(OtpDeleteWorker.KEY_MESSAGE to key.toString(), OtpDeleteWorker.KEY_DELETED_BY to deletedBy.encoded))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(key), ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val TAG = "dak-otp-delete"
        fun workName(key: MessageKey): String = "$TAG-$key"
    }
}
