package app.dak.birthdays

import app.dak.automation.EmergencyScheduleRefusedException
import app.dak.automation.ScheduledSendScheduler
import app.dak.automations.birthdays.BirthdayDates
import app.dak.automations.birthdays.OccasionKind
import app.dak.automations.birthdays.WishTag
import app.dak.automations.birthdays.WishTemplates
import app.dak.automations.safety.Addresses
import app.dak.index.repo.ScheduledSendStatus
import app.dak.index.repo.ScheduledSendStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps exactly one pending scheduled send — the NEXT occurrence — per enabled contact occasion, through the
 * existing [ScheduledSendScheduler] (exact alarm when permitted, WorkManager fallback). Each send is tagged with a
 * [WishTag] in its `ruleId` so [BirthdaySendGate] can dedupe and honour "Ask me first".
 *
 * Called when the Birthdays screen changes something, when it opens (contacts re-scanned lazily), after a wish
 * fires, and once a day from the daily housekeeping. No contacts observer, no timer of its own.
 */
@Singleton
class BirthdayScheduler @Inject constructor(
    private val store: BirthdayStore,
    private val reader: ContactOccasionReader,
    private val sends: ScheduledSendStore,
    private val scheduler: ScheduledSendScheduler,
) {
    private val mutex = Mutex()

    /** Re-reads contacts (when permitted) and reconciles; returns the occasions read (empty without permission). */
    suspend fun syncFromContacts(nowMillis: Long = System.currentTimeMillis()): List<ContactOccasion> {
        if (!reader.hasPermission()) {
            reconcile(null, nowMillis)
            return emptyList()
        }
        val occasions = reader.read(includeAnniversaries = true, includeOtherDates = true)
        reconcile(occasions, nowMillis)
        return occasions
    }

    /**
     * Brings the pending sends in line with the settings and configs. With [occasions] (a fresh contacts read) the
     * contact snapshot in each config is refreshed first, and occasions that vanished from Contacts are unscheduled.
     */
    suspend fun reconcile(occasions: List<ContactOccasion>?, nowMillis: Long = System.currentTimeMillis()) = mutex.withLock {
        val settings = store.settings
        val byKey = occasions?.associateBy { OccasionConfigKey(it.contactId, it.kind) }
        for (config in store.state.value.configs.values) {
            var current = config
            var contactGone = false
            if (byKey != null) {
                val occasion = byKey[OccasionConfigKey(config.contactId, config.occasionKind)]
                if (occasion == null) {
                    contactGone = true
                } else {
                    val number = current.number?.takeIf { n -> occasion.numbers.any { Addresses.same(it.number, n) } }
                        ?: occasion.numbers.firstOrNull()?.number
                    current = current.copy(
                        name = occasion.name,
                        firstName = occasion.firstName,
                        month = occasion.date.month,
                        day = occasion.date.day,
                        year = occasion.date.year,
                        number = number,
                        label = occasion.label,
                    )
                }
            }
            val date = current.date
            val wanted = settings.enabled && current.enabled && !contactGone && date != null &&
                !current.number.isNullOrBlank() &&
                settings.includes(current.occasionKind)
            if (!wanted) {
                current = cancelPending(current)
                if (current != config) store.replaceConfig(current)
                continue
            }
            // A wish the user delayed from its heads-up (or moved in the scheduled list), or one the executor is
            // holding back, stays where it is: see BirthdayMovedSend.
            val pendingSend = current.scheduledSendId?.let { sends.get(it) }
            if (pendingSend != null && BirthdayMovedSend.keep(
                    pending = pendingSend.status == ScheduledSendStatus.PENDING,
                    sendTag = WishTag.decode(pendingSend.ruleId),
                    contactId = current.contactId,
                    kind = current.occasionKind,
                    configScheduledAtMillis = current.scheduledAtMillis,
                    sendAtMillis = pendingSend.sendAtMillis,
                )
            ) {
                if (current != config) store.replaceConfig(current)
                continue
            }
            val zone = ZoneId.systemDefault()
            val next = BirthdayDates.nextOccurrence(
                date = date,
                afterMillis = nowMillis,
                sendAt = LocalTime.of(settings.hour.coerceIn(0, 23), settings.minute.coerceIn(0, 59)),
                zone = zone,
                skipYears = store.wishedYears(current.contactId, current.occasionKind),
            )
            if (next == null) {
                current = cancelPending(current)
                if (current != config) store.replaceConfig(current)
                continue
            }
            val atMillis = next.toInstant().toEpochMilli()
            val tag = WishTag(settings.wishMode == WishMode.ASK, current.contactId, current.occasionKind, next.year).encode()
            val body = WishTemplates.render(
                template = current.template?.takeIf { it.isNotBlank() } ?: settings.templateFor(current.occasionKind),
                name = current.name,
                firstName = current.firstName,
                age = if (current.occasionKind == OccasionKind.BIRTHDAY) date.ageIn(next.year) else null,
                occasion = current.label,
            )
            val number = current.number
            val existing = current.scheduledSendId?.let { sends.get(it) }
            val upToDate = existing != null && existing.status == ScheduledSendStatus.PENDING &&
                existing.sendAtMillis == atMillis && existing.ruleId == tag && existing.body == body &&
                existing.addresses == listOf(number) && (settings.subId == null || existing.subId == settings.subId)
            if (!upToDate) {
                current = cancelPending(current)
                // A contact whose number is an emergency number never gets a scheduled wish (ScheduledEmergencyPolicy).
                val id = try {
                    scheduler.schedule(listOf(number), body, settings.subId, atMillis, ruleId = tag)
                } catch (e: EmergencyScheduleRefusedException) {
                    null
                }
                current = if (id != null) current.copy(scheduledSendId = id, scheduledAtMillis = atMillis) else current

            }
            if (current != config) store.replaceConfig(current)
        }
    }

    /** Cancels this config's pending send, if any, and clears the scheduled fields. */
    private suspend fun cancelPending(config: OccasionConfig): OccasionConfig {
        val id = config.scheduledSendId ?: return config
        val existing = sends.get(id)
        if (existing != null && existing.status == ScheduledSendStatus.PENDING) scheduler.cancel(id)
        return config.copy(scheduledSendId = null, scheduledAtMillis = null)
    }

    /** Year of [millis] in the device zone. */
    fun yearOf(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).year

    private data class OccasionConfigKey(val contactId: Long, val kind: OccasionKind)
}
