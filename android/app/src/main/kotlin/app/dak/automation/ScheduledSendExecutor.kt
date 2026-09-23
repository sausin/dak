package app.dak.automation

import app.dak.birthdays.BirthdaySendGate
import app.dak.broadcast.BroadcastSendGate
import app.dak.index.enrich.ConversationIds
import app.dak.index.repo.ScheduledSendStatus
import app.dak.index.repo.ScheduledSendStore
import app.dak.safety.SendCostGuard
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.telephony.MessageSender
import app.dak.telephony.NumberNormalizer
import app.dak.telephony.OutgoingSms
import app.dak.telephony.SendResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends every scheduled send that is due, once. Serialised by a mutex and idempotent (a row is only sent while
 * still PENDING), so the alarm receiver and the WorkManager fallback can both call [runDue] safely. Sends that
 * would exceed the 30-per-30-minutes limit are pushed to the next free slot instead of failing. Birthday wishes pass
 * through [BirthdaySendGate] (dedupe, "Ask me first", next-year rescheduling); broadcast copies through
 * [BroadcastSendGate] (dropped when their broadcast was cancelled; outcome recorded on the broadcast).
 */
@Singleton
class ScheduledSendExecutor @Inject constructor(
    private val store: ScheduledSendStore,
    private val sender: MessageSender,
    private val normalizer: NumberNormalizer,
    private val settings: SettingsStore,
    private val throttle: SendThrottle,
    private val scheduler: ScheduledSendScheduler,
    private val birthdayGate: BirthdaySendGate,
    private val costGuard: SendCostGuard,
    private val broadcastGate: BroadcastSendGate,
) {
    private val mutex = Mutex()

    /** Sends all due rows; returns how many were handed to the platform. */
    suspend fun runDue(nowMillis: Long = System.currentTimeMillis()): Int = mutex.withLock {
        var sent = 0
        for (due in store.due(nowMillis)) {
            val current = store.get(due.id) ?: continue
            if (current.status != ScheduledSendStatus.PENDING) continue
            if (current.addresses.isEmpty()) {
                store.markStatus(current.id, ScheduledSendStatus.FAILED, "no recipient")
                continue
            }
            if (!broadcastGate.beforeSend(current)) continue
            if (!birthdayGate.beforeSend(current, nowMillis)) continue
            val slot = throttle.reserve(nowMillis)
            if (slot > nowMillis + SLOT_GRACE_MILLIS) {
                scheduler.reschedule(current.id, slot)
                continue
            }
            val normalise = settings.get(DakSettings.numberNormalization)
            val addresses = current.addresses.map { if (normalise) normalizer.normalize(it, current.subId) else it }
            // Rule-driven sends are unattended: refuse unapproved premium-rate destinations. Sends the user scheduled
            // from the composer were confirmed there.
            if (current.ruleId != null && !costGuard.allowUnattended(addresses, current.subId)) {
                store.markStatus(current.id, ScheduledSendStatus.FAILED, PREMIUM_REFUSED)
                broadcastGate.afterFailed(current)
                continue
            }
            val result = runCatching {
                sender.sendSms(
                    OutgoingSms(
                        addresses = addresses,
                        body = current.body,
                        subId = current.subId,
                        threadId = current.conversationId?.let { ConversationIds.threadIdOf(it) },
                        requestDeliveryReport = settings.get(DakSettings.deliveryReports),
                    ),
                )
            }.getOrElse { SendResult.Failed(it.message ?: "send failed") }
            when (result) {
                is SendResult.Queued -> {
                    store.markStatus(current.id, ScheduledSendStatus.SENT)
                    sent++
                    runCatching { birthdayGate.afterSent(current, nowMillis) }
                    runCatching { broadcastGate.afterSent(current, result.keys) }
                }
                is SendResult.Failed -> {
                    store.markStatus(current.id, ScheduledSendStatus.FAILED, result.reason)
                    broadcastGate.afterFailed(current)
                }
            }
        }
        sent
    }

    private companion object {
        const val SLOT_GRACE_MILLIS = 5_000L
        const val PREMIUM_REFUSED = "premium-rate number not approved"
    }
}
