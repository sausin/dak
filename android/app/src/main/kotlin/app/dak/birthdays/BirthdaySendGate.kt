package app.dak.birthdays

import app.dak.automation.ScheduledSendScheduler
import app.dak.automations.birthdays.WishTag
import app.dak.index.repo.AuditLogRepository
import app.dak.index.repo.ScheduledSend
import app.dak.index.repo.ScheduledSendStatus
import app.dak.index.repo.ScheduledSendStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The scheduled-send executor's hook for birthday wishes (sends whose `ruleId` is a [WishTag]):
 * - never twice in a year: a send whose dedupe key (contact + occasion + year) is already recorded is dropped;
 * - a wish for a contact or feature that has since been switched off is dropped;
 * - "Ask me first" sends are not sent: the prompt notification is posted instead;
 * - automatic wishes are unattended sends, so like auto-replies and forwards they need an app lock *when they fall
 *   due* (it can be removed after the wish was queued) and count against [app.dak.automation.UnattendedSendLimits]
 *   (the executor takes the unit). Without a lock, or over the daily cap, the wish is not sent: the prompt is posted
 *   instead, so the user can still send it with one tap ([askInstead]). The executor's premium-rate guard applies as
 *   to every tagged send;
 * - after a wish went out (or was prompted), the next year's occurrence is scheduled.
 * Every other scheduled send passes straight through.
 */
@Singleton
class BirthdaySendGate @Inject constructor(
    private val store: BirthdayStore,
    private val sends: ScheduledSendStore,
    private val birthdays: BirthdayScheduler,
    private val notifications: BirthdayNotifications,
    private val scheduler: ScheduledSendScheduler,
    private val auditLog: AuditLogRepository,
) {

    /**
     * True when the executor should send [send] now; false when this gate handled (and closed) it. [securityReady]
     * says whether an app lock is set up right now; it is only asked for automatic wishes.
     */
    suspend fun beforeSend(send: ScheduledSend, nowMillis: Long, securityReady: () -> Boolean): Boolean {
        val tag = WishTag.decode(send.ruleId) ?: return true
        if (store.isWished(tag.dedupeKey)) {
            sends.markStatus(send.id, ScheduledSendStatus.CANCELLED, REASON_ALREADY_WISHED)
            return false
        }
        val settings = store.settings
        val config = store.config(tag.contactId, tag.kind)
        // Sends created from the prompt's "Send" action carry ask=false and are allowed while the feature is on.
        if (!settings.enabled || config == null || (!config.enabled && tag.ask)) {
            sends.markStatus(send.id, ScheduledSendStatus.CANCELLED, REASON_DISABLED)
            return false
        }
        if (tag.ask) {
            prompt(send, tag, config.name, nowMillis, REASON_ASKED)
            return false
        }
        if (tag.unattended && !securityReady()) {
            audit("automatic wish not sent, no app lock: asked instead")
            prompt(send, tag, config.name, nowMillis, REASON_NO_APP_LOCK)
            return false
        }
        return true
    }

    /**
     * Posts the "Ask me first" prompt for [send] instead of sending it (an automatic wish that may not go out
     * unattended right now) and closes the send with [reason].
     */
    suspend fun askInstead(send: ScheduledSend, nowMillis: Long, reason: String) {
        val tag = WishTag.decode(send.ruleId) ?: return
        val name = store.config(tag.contactId, tag.kind)?.name ?: send.addresses.firstOrNull().orEmpty()
        audit("automatic wish not sent ($reason): asked instead")
        prompt(send, tag, name, nowMillis, reason)
    }

    private suspend fun prompt(send: ScheduledSend, tag: WishTag, name: String, nowMillis: Long, reason: String) {
        val number = send.addresses.firstOrNull()
        if (number != null) notifications.prompt(tag, name, number, send.body, send.subId)
        sends.markStatus(send.id, ScheduledSendStatus.CANCELLED, reason)
        birthdays.reconcile(null, nowMillis)
    }

    private suspend fun audit(detail: String) {
        runCatching { auditLog.log("birthdays", "automation.skipped", detail = detail) }
    }

    /** Records a sent wish (dedupe) and schedules the next occurrence. */
    suspend fun afterSent(send: ScheduledSend, nowMillis: Long) {
        val tag = WishTag.decode(send.ruleId) ?: return
        store.markWished(tag.dedupeKey, birthdays.yearOf(nowMillis))
        birthdays.reconcile(null, nowMillis)
    }

    /** The prompt's "Send": queues the wish right away (still rate-limited and deduped). False if already wished. */
    suspend fun sendFromPrompt(tagText: String, number: String, body: String, subId: Int, nowMillis: Long): Boolean {
        val tag = WishTag.decode(tagText) ?: return false
        if (store.isWished(tag.dedupeKey)) return false
        // Confirmed by the user's tap: an attended send, not held to the app-lock and unattended-cap rules.
        scheduler.schedule(listOf(number), body, subId, nowMillis, ruleId = tag.copy(ask = false, confirmed = true).encode())
        return true
    }

    /** The prompt's "Skip": this year counts as handled; nothing more is sent until next year. */
    suspend fun skipFromPrompt(tagText: String, nowMillis: Long) {
        val tag = WishTag.decode(tagText) ?: return
        store.markWished(tag.dedupeKey, birthdays.yearOf(nowMillis))
        birthdays.reconcile(null, nowMillis)
    }

    companion object {
        const val REASON_ALREADY_WISHED = "already wished this year"
        const val REASON_DISABLED = "birthday wishes turned off"
        const val REASON_ASKED = "asked the user"
        const val REASON_NO_APP_LOCK = "no app lock: asked the user"
        const val REASON_DAILY_LIMIT = "daily unattended limit: asked the user"
    }
}
