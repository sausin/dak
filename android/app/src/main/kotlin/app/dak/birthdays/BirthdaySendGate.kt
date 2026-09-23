package app.dak.birthdays

import app.dak.automation.ScheduledSendScheduler
import app.dak.automations.birthdays.WishTag
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
) {

    /** True when the executor should send [send] now; false when this gate handled (and closed) it. */
    suspend fun beforeSend(send: ScheduledSend, nowMillis: Long): Boolean {
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
            val number = send.addresses.firstOrNull()
            if (number != null) notifications.prompt(tag, config.name, number, send.body, send.subId)
            sends.markStatus(send.id, ScheduledSendStatus.CANCELLED, REASON_ASKED)
            birthdays.reconcile(null, nowMillis)
            return false
        }
        return true
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
        scheduler.schedule(listOf(number), body, subId, nowMillis, ruleId = tag.copy(ask = false).encode())
        return true
    }

    /** The prompt's "Skip": this year counts as handled; nothing more is sent until next year. */
    suspend fun skipFromPrompt(tagText: String, nowMillis: Long) {
        val tag = WishTag.decode(tagText) ?: return
        store.markWished(tag.dedupeKey, birthdays.yearOf(nowMillis))
        birthdays.reconcile(null, nowMillis)
    }

    private companion object {
        const val REASON_ALREADY_WISHED = "already wished this year"
        const val REASON_DISABLED = "birthday wishes turned off"
        const val REASON_ASKED = "asked the user"
    }
}
