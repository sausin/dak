package app.dak.automation

import android.content.Context
import app.dak.R
import app.dak.automations.history.RunOutcome
import app.dak.automations.history.SkipReason
import app.dak.birthdays.BirthdaySendGate
import app.dak.broadcast.BroadcastSendGate
import app.dak.index.enrich.ConversationIds
import app.dak.index.repo.AuditLogRepository
import app.dak.index.repo.ScheduledSend
import app.dak.index.repo.ScheduledSendStatus
import app.dak.index.repo.ScheduledSendStore
import app.dak.safety.SendCostGuard
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.telephony.MessageSender
import app.dak.telephony.NumberNormalizer
import app.dak.telephony.OutgoingSms
import app.dak.telephony.SendResult
import app.dak.telephony.role.SmsRoleMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
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
 *
 * Unattended sends ([ScheduledSendOrigin.unattended]: auto-replies, held auto-forwards, automatic birthday wishes)
 * are re-checked when they fall due, because the app lock can be removed after they were queued: without one they
 * are cancelled ("no app lock") and logged in the automation history instead of going out. Automatic birthday
 * wishes also take one unattended send from [UnattendedSendLimits] when they go out.
 *
 * While Dak is not the default SMS app a due send is not handed to the sender at all: the row stays PENDING and is
 * looked at again after [ScheduledSendRoleGate.RECHECK_MILLIS] ([ScheduledSendRoleGate]). The sender would only keep
 * a held copy ([app.dak.telephony.send.HeldSendStore]) and report it as queued, which used to mark the row SENT
 * although nothing had gone out, and left the user no way to cancel or edit it.
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
    // Lazy: the guard builds the app lock manager and rule repository; only needed when an unattended send is due.
    private val outboundGuard: dagger.Lazy<OutboundAutomationGuard>,
    private val limits: UnattendedSendLimits,
    private val runLog: AutomationRunLog,
    private val audit: AuditLogRepository,
    private val role: SmsRoleMonitor,
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()

    /** Sends all due rows; returns how many were handed to the platform. */
    suspend fun runDue(nowMillis: Long = System.currentTimeMillis()): Int = mutex.withLock {
        var sent = 0
        // Re-read once per run (it re-checks the phone's screen lock), and only if an unattended send is due.
        var lockReady: Boolean? = null
        fun securityReady(): Boolean = lockReady
            ?: (runCatching { outboundGuard.get().securityReady() }.getOrDefault(false)).also { lockReady = it }
        for (due in store.due(nowMillis)) {
            val current = store.get(due.id) ?: continue
            if (current.status != ScheduledSendStatus.PENDING) continue
            if (current.addresses.isEmpty()) {
                store.markStatus(current.id, ScheduledSendStatus.FAILED, "no recipient")
                continue
            }
            val origin = ScheduledSendOrigin.of(current.ruleId)
            if (ScheduledSendGuard.cancelForNoLock(origin, lockReady = { securityReady() })) {
                cancelUnattended(current, origin)
                continue
            }
            // Checked before the gates and the throttle, so a send that waits for the role spends no rate-limit slot
            // and no daily allowance. Pushing the time forward (not only re-arming) keeps rearmPending from firing
            // the alarm again at once for a row that is already due.
            val retryAt = ScheduledSendRoleGate.waitUntil(isDefaultSmsApp = { role.isDefaultNow() }, nowMillis = nowMillis)
            if (retryAt != null) {
                scheduler.reschedule(current.id, retryAt)
                continue
            }
            if (!broadcastGate.beforeSend(current)) continue
            // Automatic birthday wishes without an app lock (or over the daily cap) become a prompt in the gate.
            if (!birthdayGate.beforeSend(current, nowMillis, securityReady = { securityReady() })) continue
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
            if (origin == ScheduledSendOrigin.BIRTHDAY_AUTO && !limits.tryConsume("birthday", nowMillis)) {
                birthdayGate.askInstead(current, nowMillis, BirthdaySendGate.REASON_DAILY_LIMIT)
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
            // A role lost between the check above and the send makes the sender keep a held copy (sent when the
            // role is back) and answer Queued: the row is then SENT, since leaving it PENDING would send it twice.
            when (result) {
                is SendResult.Queued -> {
                    store.markStatus(current.id, ScheduledSendRoleGate.statusAfter(result))
                    sent++
                    runCatching { birthdayGate.afterSent(current, nowMillis) }
                    runCatching { broadcastGate.afterSent(current, result.keys) }
                }
                is SendResult.Failed -> {
                    store.markStatus(current.id, ScheduledSendRoleGate.statusAfter(result), result.reason)
                    broadcastGate.afterFailed(current)
                }
            }
        }
        sent
    }

    /** Cancels a queued auto-reply / auto-forward that may no longer go out (no app lock), with a history row. */
    private suspend fun cancelUnattended(send: ScheduledSend, origin: ScheduledSendOrigin) {
        store.markStatus(send.id, ScheduledSendStatus.CANCELLED, NO_APP_LOCK)
        runCatching { audit.log("automation", "automation.skipped", detail = "${send.ruleId}: queued send cancelled, no app lock") }
        val label = context.getString(
            when (origin) {
                ScheduledSendOrigin.AUTO_FORWARD -> R.string.fw_queued_auto_forward
                ScheduledSendOrigin.AUTO_REPLY -> R.string.fw_queued_auto_reply
                else -> R.string.fw_queued_automation
            },
        )
        runLog.recordQueued(
            origin = origin,
            tag = send.ruleId ?: origin.name,
            label = label,
            address = send.addresses.firstOrNull(),
            body = send.body,
            outcome = RunOutcome.SKIPPED,
            reason = SkipReason.NO_APP_LOCK,
        )
    }

    private companion object {
        const val SLOT_GRACE_MILLIS = 5_000L
        const val PREMIUM_REFUSED = "premium-rate number not approved"
        const val NO_APP_LOCK = "no app lock"
    }
}

/**
 * The default-SMS-app check for a due scheduled send (pure, so it is unit-tested without Android).
 *
 * Without the role Dak cannot write the provider row a send needs, and the sender would only park a copy in
 * [app.dak.telephony.send.HeldSendStore] while answering [SendResult.Queued]. A scheduled send must not be reported as
 * sent in that case: the executor leaves the row PENDING and tries again later, so it stays visible, cancellable and
 * editable in the scheduled list, and goes out on the first run after the role is back.
 */
internal object ScheduledSendRoleGate {
    /** How long a due send waits before the role is checked again. */
    const val RECHECK_MILLIS: Long = 15 * 60 * 1000L

    /** Null when the send may go out now; otherwise when to look again (the row stays PENDING until then). */
    fun waitUntil(isDefaultSmsApp: () -> Boolean, nowMillis: Long): Long? =
        if (isDefaultSmsApp()) null else nowMillis + RECHECK_MILLIS

    /** The row's status once the sender has answered. */
    fun statusAfter(result: SendResult): ScheduledSendStatus = when (result) {
        is SendResult.Queued -> ScheduledSendStatus.SENT
        is SendResult.Failed -> ScheduledSendStatus.FAILED
    }
}
