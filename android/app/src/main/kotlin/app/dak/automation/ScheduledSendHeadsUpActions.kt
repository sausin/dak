package app.dak.automation

import app.dak.automations.birthdays.WishTag
import app.dak.automations.broadcast.BroadcastTag
import app.dak.automations.broadcast.RecipientStatus
import app.dak.birthdays.BirthdaySendGate
import app.dak.broadcast.BroadcastService
import app.dak.broadcast.BroadcastStore
import app.dak.index.repo.ScheduledSend
import app.dak.index.repo.ScheduledSendStatus
import app.dak.index.repo.ScheduledSendStore
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the heads-up's actions (and the scheduled list's Change time / Cancel) do to a scheduled send.
 *
 * Every action re-reads the row and acts only while it exists and is PENDING ([HeadsUpActions.check]); otherwise the
 * stale heads-up is just cleared.
 * - **Send now** moves the send (a broadcast: all its pending copies, keeping their pacing) to now and runs the normal
 *   [ScheduledSendExecutor]: the same gates apply as when it falls due (app lock for unattended sends such as
 *   automatic birthday wishes and auto-replies, premium-rate guard, daily unattended cap, rate limit, and the hold while
 *   Dak is not the default SMS app). Nothing is sent around the executor.
 * - **Delay** (+1 hour, Tomorrow same time) and **Cancel** only make a send later or never, so they need no unlock.
 *   A cancelled birthday wish counts as handled for this year (like the prompt's Skip), otherwise the birthday
 *   scheduler would queue it again.
 */
@Singleton
class ScheduledSendHeadsUpActions @Inject constructor(
    private val store: ScheduledSendStore,
    private val scheduler: ScheduledSendScheduler,
    private val executor: dagger.Lazy<ScheduledSendExecutor>,
    private val headsUp: ScheduledSendHeadsUp,
    private val broadcastStore: BroadcastStore,
    private val broadcasts: dagger.Lazy<BroadcastService>,
    private val birthdayGate: dagger.Lazy<BirthdaySendGate>,
) {

    suspend fun perform(action: HeadsUpAction, sendId: Long, nowMillis: Long = System.currentTimeMillis()): HeadsUpActionCheck {
        val row = store.get(sendId)
        val check = HeadsUpActions.check(exists = row != null, pending = row?.status == ScheduledSendStatus.PENDING)
        if (check != HeadsUpActionCheck.OK || row == null) {
            headsUp.refresh(nowMillis)
            return check
        }
        val broadcastId = BroadcastTag.decode(row.ruleId)?.broadcastId
        when (action) {
            HeadsUpAction.DELAY_MENU -> headsUp.showDelayChoices(row.id)
            HeadsUpAction.CANCEL -> cancel(row, wholeBroadcast = true, nowMillis = nowMillis)
            HeadsUpAction.DELAY_HOUR, HeadsUpAction.DELAY_TOMORROW -> {
                val anchor = if (broadcastId != null) broadcastStart(broadcastId, row) else row.sendAtMillis
                val at = HeadsUpActions.delayedTo(action, anchor, nowMillis, ZoneId.systemDefault()) ?: return check
                if (broadcastId != null) moveBroadcast(broadcastId, at) else scheduler.reschedule(row.id, at)
            }
            HeadsUpAction.SEND_NOW -> {
                if (broadcastId != null) moveBroadcast(broadcastId, nowMillis) else scheduler.reschedule(row.id, nowMillis)
                try {
                    executor.get().runDue(nowMillis)
                } finally {
                    scheduler.rearmPending()
                }
            }
        }
        return HeadsUpActionCheck.OK
    }

    /** "Change time" in the scheduled list: moves this one send (a broadcast copy alone). False for a past time. */
    suspend fun moveTo(sendId: Long, atMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (atMillis <= nowMillis) return false
        val row = store.get(sendId) ?: return false
        if (HeadsUpActions.check(exists = true, pending = row.status == ScheduledSendStatus.PENDING) != HeadsUpActionCheck.OK) return false
        scheduler.reschedule(row.id, atMillis)
        return true
    }

    /**
     * Cancels [row]. From the heads-up a broadcast is cancelled as a whole ([wholeBroadcast]); from the list only
     * that copy. A birthday wish is also marked as handled for this year.
     */
    suspend fun cancel(row: ScheduledSend, wholeBroadcast: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        val broadcastId = BroadcastTag.decode(row.ruleId)?.broadcastId
        when {
            broadcastId != null && wholeBroadcast -> {
                broadcasts.get().cancelPending(broadcastId)
                headsUp.refresh(nowMillis)
            }
            WishTag.decode(row.ruleId) != null -> {
                scheduler.cancel(row.id)
                row.ruleId?.let { birthdayGate.get().skipFromPrompt(it, nowMillis) }
            }
            else -> scheduler.cancel(row.id)
        }
    }

    /** The broadcast's scheduled start (what its heads-up announced). */
    private fun broadcastStart(broadcastId: String, row: ScheduledSend): Long =
        broadcastStore.record(broadcastId)?.scheduledAtMillis ?: row.sendAtMillis

    /** Moves every pending copy of a broadcast so it starts at [newStartMillis], keeping the pacing between copies. */
    private suspend fun moveBroadcast(broadcastId: String, newStartMillis: Long) {
        val copies = store.pending().first().filter { BroadcastTag.decode(it.ruleId)?.broadcastId == broadcastId }
        if (copies.isEmpty()) return
        val oldStart = broadcastStore.record(broadcastId)?.scheduledAtMillis ?: copies.minOf { it.sendAtMillis }
        val times = HeadsUpActions.shifted(copies.map { it.sendAtMillis }, oldStart, newStartMillis)
        copies.zip(times).forEach { (copy, at) -> scheduler.reschedule(copy.id, at, refreshHeadsUps = false) }
        val delta = newStartMillis - oldStart
        broadcastStore.record(broadcastId)?.let { record ->
            broadcastStore.putRecord(
                record.copy(
                    scheduledAtMillis = newStartMillis,
                    recipients = record.recipients.map { r ->
                        if (r.status == RecipientStatus.SCHEDULED) r.copy(sendAtMillis = r.sendAtMillis + delta) else r
                    },
                ),
            )
        }
        headsUp.refresh()
    }
}
