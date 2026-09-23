package app.dak.telephony.send

import android.content.Context
import app.dak.core.model.MessageKey
import app.dak.telephony.sms.PartProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable multipart send / delivery progress per SMS id, shared between the sender and the status receiver
 * (which may run in a fresh process). Writes use `commit()`: callers are already off the main thread and the
 * state must survive the receiver finishing.
 */
@Singleton
class SendProgressStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("dak_send_progress", Context.MODE_PRIVATE)

    /** Starts (or restarts, for a retry) tracking of [id] with [partCount] parts for send [attempt]. */
    @Synchronized
    fun begin(id: Long, partCount: Int, nowMillis: Long, attempt: Int = 0) {
        pruneOlderThan(nowMillis - MAX_AGE_MILLIS)
        put(id, PartProgress(partCount = partCount, startedAtMillis = nowMillis, attempt = attempt))
    }

    /**
     * Records one part's sent result ([ok], else [resultCode]) of [attempt]. Returns the progress exactly once per
     * attempt, when the attempt has just settled (see [PartProgress.outcome]); null while parts are still in flight,
     * once the outcome was already handed out, and for a late report from an older attempt.
     */
    @Synchronized
    internal fun recordSendResult(id: Long, part: Int, partCount: Int, attempt: Int, ok: Boolean, resultCode: Int): PartProgress? {
        val current = get(id, partCount)
        if (current.attempt != 0 && attempt != 0 && current.attempt != attempt) return null
        val next = if (ok) current.withSent(part) else current.withFailedPart(part, resultCode)
        if (next.resolved || !next.isSettled) {
            put(id, next)
            return null
        }
        put(id, next.copy(resolved = true))
        return next
    }

    /** Records a delivered part; true once every part counts as delivered. */
    @Synchronized
    fun recordDelivered(id: Long, part: Int, partCount: Int): Boolean {
        val current = get(id, partCount)
        // A partly sent message stays failed: reports for the parts that did go out must not turn it "delivered".
        if (current.failedParts.isNotEmpty()) return false
        val next = current.withDelivered(part)
        put(id, next)
        return next.isFullyDelivered
    }

    @Synchronized
    fun clear(id: Long) {
        prefs.edit().remove(id.toString()).commit()
    }

    private fun get(id: Long, partCount: Int): PartProgress =
        PartProgress.decode(prefs.getString(id.toString(), null)) ?: PartProgress(partCount = partCount.coerceAtLeast(1))

    private fun put(id: Long, progress: PartProgress) {
        prefs.edit().putString(id.toString(), progress.encode()).commit()
    }

    private fun pruneOlderThan(cutoffMillis: Long) {
        val stale = prefs.all.filter { (_, v) ->
            val p = PartProgress.decode(v as? String)
            p == null || p.startedAtMillis in 1 until cutoffMillis
        }.keys
        if (stale.isEmpty()) return
        val edit = prefs.edit()
        stale.forEach { edit.remove(it) }
        edit.commit()
    }

    private companion object {
        /** Delivery reports older than this will not arrive any more. */
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

/** Last human-readable failure reason per message, for the "tap to retry" bubble. */
@Singleton
class SendFailureStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("dak_send_failures", Context.MODE_PRIVATE)

    fun get(key: MessageKey): String? = prefs.getString(key.toString(), null)

    fun set(key: MessageKey, reason: String) {
        prefs.edit().putString(key.toString(), reason).apply()
    }

    fun clear(key: MessageKey) {
        if (prefs.contains(key.toString())) prefs.edit().remove(key.toString()).apply()
    }
}
