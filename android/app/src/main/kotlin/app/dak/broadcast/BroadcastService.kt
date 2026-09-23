package app.dak.broadcast

import app.dak.automation.EmergencyScheduleRefusedException
import app.dak.automation.ScheduledSendScheduler
import app.dak.automations.broadcast.BroadcastLimits
import app.dak.automations.broadcast.BroadcastList
import app.dak.automations.broadcast.BroadcastPlan
import app.dak.automations.broadcast.BroadcastPlanner
import app.dak.automations.broadcast.BroadcastQuota
import app.dak.automations.broadcast.BroadcastRecipient
import app.dak.automations.broadcast.BroadcastRecord
import app.dak.automations.broadcast.BroadcastRequest
import app.dak.automations.broadcast.BroadcastTag
import app.dak.automations.broadcast.RecipientStatus
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKey
import app.dak.core.model.NO_SUB_ID
import app.dak.index.repo.AuditLogRepository
import app.dak.index.repo.ScheduledSendStatus
import app.dak.index.repo.ScheduledSendStore
import app.dak.safety.SendCostGuard
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.telephony.BlockedNumbers
import app.dak.telephony.MessageSender
import app.dak.telephony.NumberNormalizer
import app.dak.telephony.ProviderReader
import app.dak.telephony.SendResult
import app.dak.telephony.SimRepository
import app.dak.telephony.SmsSegmentCounter
import app.dak.telephony.cost.CostKind
import app.dak.telephony.cost.CostVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the confirmation dialog shows before a broadcast goes out.
 *
 * @property subId the resolved sending SIM.
 * @property totalSegments SMS parts across all copies (each is billed as one SMS).
 * @property maxSegmentsPerCopy the longest copy's parts.
 * @property costVerdicts international / roaming destinations the cost guard wants confirmed (premium-rate and
 *   other special-tariff numbers are already excluded from the plan).
 * @property roaming the SIM is roaming.
 */
data class BroadcastPreview(
    val listId: String,
    val listName: String,
    val template: String,
    val plan: BroadcastPlan,
    val subId: Int,
    val scheduledAtMillis: Long?,
    val totalSegments: Int,
    val maxSegmentsPerCopy: Int,
    val costVerdicts: List<CostVerdict>,
    val roaming: Boolean,
    val remainingToday: Int,
)

/**
 * Plans and sends broadcasts. Each copy becomes its own scheduled send (tagged with [BroadcastTag]) so it goes to
 * the recipient's own 1:1 thread through the normal send path: rate-limited, retried, with delivery ticks. Copies
 * that share a send time share one alarm (battery rule 4). Only the user starts a broadcast: nothing in the
 * automation engine can reach this class.
 */
@Singleton
class BroadcastService @Inject constructor(
    private val store: BroadcastStore,
    private val scheduler: ScheduledSendScheduler,
    private val sends: ScheduledSendStore,
    private val blocked: BlockedNumbers,
    private val sims: SimRepository,
    private val costGuard: SendCostGuard,
    private val normalizer: NumberNormalizer,
    private val settings: SettingsStore,
    private val audit: AuditLogRepository,
    private val sender: MessageSender,
    private val provider: ProviderReader,
) {
    private val limits = BroadcastLimits()
    private val planner = BroadcastPlanner(limits)

    /** Resolves a null / unknown SIM choice to the default SMS SIM. */
    fun resolveSub(subId: Int?): Int = subId?.takeIf { it != NO_SUB_ID } ?: sims.defaultSmsSubId()

    /** Builds the plan and cost summary for sending [template] to [list] from [subId] now or at [scheduledAtMillis]. */
    suspend fun preview(list: BroadcastList, template: String, subId: Int?, scheduledAtMillis: Long?): BroadcastPreview =
        withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val sub = resolveSub(subId)
            val blockedSet = list.members.map { it.address.trim() }.filter { runCatching { blocked.isBlocked(it) }.getOrDefault(false) }.toSet()
            val own = sims.sims.value.mapNotNull { it.number?.takeIf { n -> n.isNotBlank() } }
            val remaining = BroadcastQuota.remaining(store.records.value, now, limits)
            val plan = planner.plan(
                BroadcastRequest(
                    members = list.members,
                    template = template,
                    nowMillis = now,
                    scheduledAtMillis = scheduledAtMillis,
                    ownNumbers = own,
                    isBlocked = { it in blockedSet },
                    isRefusedDestination = { address -> isRefused(address, sub) },
                    usedToday = limits.maxMessagesPerDay - remaining,
                ),
            )
            val segments = plan.copies.map { runCatching { SmsSegmentCounter.count(it.text).segments }.getOrDefault(1) }
            val outgoing = plan.copies.map { outgoingAddress(it.member.address, sub) }
            BroadcastPreview(
                listId = list.id,
                listName = list.name,
                template = template,
                plan = plan,
                subId = sub,
                scheduledAtMillis = scheduledAtMillis,
                totalSegments = segments.sum(),
                maxSegmentsPerCopy = segments.maxOrNull() ?: 0,
                costVerdicts = runCatching { costGuard.toConfirm(outgoing, sub) }.getOrDefault(emptyList()),
                roaming = runCatching { sims.isRoaming(sub) }.getOrDefault(false),
                remainingToday = remaining,
            )
        }

    /**
     * Queues every copy of a confirmed [preview]. Re-checks the daily quota first (another broadcast may have gone
     * out since the preview); returns the saved record, or null when the plan can no longer be sent.
     */
    suspend fun send(preview: BroadcastPreview): BroadcastRecord? {
        val plan = preview.plan
        if (!plan.canSend) return null
        val now = System.currentTimeMillis()
        if (BroadcastQuota.remaining(store.records.value, now, limits) < plan.copies.size) return null
        // The plan already leaves out emergency numbers (isRefused); copies after the first go straight into the store,
        // so the scheduler's own refusal is repeated here for all of them.
        if (plan.copies.any { scheduler.refusesEmergency(listOf(it.member.address), preview.subId) }) return null
        val recordId = UUID.randomUUID().toString()
        val record = BroadcastRecord(
            id = recordId,
            listId = preview.listId,
            listName = preview.listName,
            template = preview.template,
            subId = preview.subId,
            createdAt = now,
            scheduledAtMillis = preview.scheduledAtMillis,
            recipients = plan.copies.map { copy ->
                BroadcastRecipient(
                    address = copy.member.address,
                    displayName = copy.member.displayName,
                    contactId = copy.member.contactId,
                    text = copy.text,
                    sendAtMillis = copy.sendAtMillis,
                )
            },
        )
        // Save first: the executor may run as soon as the first copy is armed and looks the record up.
        store.putRecord(record)

        // Insert every copy, then arm one alarm per distinct send time (the executor sends all due rows per run).
        val ids = LongArray(plan.copies.size)
        plan.copies.forEachIndexed { i, copy ->
            if (i == 0) return@forEachIndexed
            ids[i] = sends.schedule(
                addresses = listOf(copy.member.address),
                body = copy.text,
                subId = preview.subId,
                sendAtMillis = copy.sendAtMillis,
                ruleId = BroadcastTag(recordId, i).encode(),
            )
            store.updateRecipient(recordId, i) { it.copy(scheduledSendId = ids[i]) }
        }
        val first = plan.copies.first()
        ids[0] = scheduler.schedule(
            addresses = listOf(first.member.address),
            body = first.text,
            subId = preview.subId,
            atMillis = first.sendAtMillis,
            ruleId = BroadcastTag(recordId, 0).encode(),
        )
        store.updateRecipient(recordId, 0) { it.copy(scheduledSendId = ids[0]) }
        plan.copies.indices
            .groupBy { plan.copies[it].sendAtMillis }
            .filterKeys { it != first.sendAtMillis }
            .forEach { (time, indices) -> scheduler.arm(ids[indices.first()], time) }

        runCatching {
            audit.log(
                actor = ACTOR,
                action = ACTION_SENT,
                target = preview.listName,
                detail = "${plan.copies.size} recipients" +
                    (preview.scheduledAtMillis?.let { ", scheduled for $it" } ?: "") +
                    ", risk ${plan.risk.level.name.lowercase()}",
            )
        }
        return store.record(recordId)
    }

    /** Re-sends one failed copy: retries the failed provider message, or queues a fresh copy when it never left. */
    suspend fun retry(recordId: String, index: Int): Boolean {
        val record = store.record(recordId) ?: return false
        val recipient = record.recipients.getOrNull(index) ?: return false
        val key = recipient.messageKey?.let { MessageKey.parse(it) }
        if (key != null) {
            val message = runCatching { provider.message(key) }.getOrNull()
            if (message != null && message.box == MessageBox.FAILED) {
                return when (val result = runCatching { sender.retry(key) }.getOrElse { SendResult.Failed(it.message ?: "") }) {
                    is SendResult.Queued -> {
                        store.updateRecipient(recordId, index) {
                            it.copy(status = RecipientStatus.SENT, messageKey = result.keys.firstOrNull()?.toString() ?: it.messageKey)
                        }
                        true
                    }
                    is SendResult.Failed -> false
                }
            }
        }
        val now = System.currentTimeMillis()
        val id = try {
            scheduler.schedule(
                addresses = listOf(recipient.address),
                body = recipient.text,
                subId = record.subId,
                atMillis = now,
                ruleId = BroadcastTag(recordId, index).encode(),
            )
        } catch (e: EmergencyScheduleRefusedException) {
            return false
        }
        store.updateRecipient(recordId, index) {
            it.copy(status = RecipientStatus.SCHEDULED, scheduledSendId = id, messageKey = null, sendAtMillis = now)
        }
        runCatching { audit.log(ACTOR, ACTION_RETRY, record.listName, recipient.address) }
        return true
    }

    /** Cancels every copy of [recordId] that has not gone out yet; returns how many were cancelled. */
    suspend fun cancelPending(recordId: String): Int {
        val record = store.record(recordId) ?: return 0
        var cancelled = 0
        record.recipients.forEachIndexed { i, r ->
            if (r.status != RecipientStatus.SCHEDULED) return@forEachIndexed
            val id = r.scheduledSendId
            val stillPending = id == null || sends.get(id)?.status == ScheduledSendStatus.PENDING
            if (!stillPending) return@forEachIndexed
            if (id != null) scheduler.cancel(id)
            store.updateRecipient(recordId, i) { it.copy(status = RecipientStatus.CANCELLED) }
            cancelled++
        }
        if (cancelled > 0) runCatching { audit.log(ACTOR, ACTION_CANCELLED, record.listName, "$cancelled copies") }
        return cancelled
    }

    /** Premium-rate, short-code, toll-free, emergency and alphanumeric destinations never get a broadcast. */
    private fun isRefused(address: String, subId: Int): Boolean {
        val kind = runCatching { costGuard.classify(outgoingAddress(address, subId), subId).kind }.getOrNull() ?: return false
        return kind !in ALLOWED_KINDS
    }

    private fun outgoingAddress(address: String, subId: Int): String =
        if (settings.get(DakSettings.numberNormalization)) runCatching { normalizer.normalize(address, subId) }.getOrDefault(address) else address

    private companion object {
        const val ACTOR = "user:broadcast"
        const val ACTION_SENT = "broadcast.sent"
        const val ACTION_RETRY = "broadcast.retry"
        const val ACTION_CANCELLED = "broadcast.cancelled"
        val ALLOWED_KINDS = setOf(CostKind.NORMAL, CostKind.INTERNATIONAL, CostKind.ROAMING)
    }
}
