package app.dak.automation

import app.dak.automations.action.SmsForwarder
import app.dak.core.model.NO_SUB_ID
import app.dak.safety.SendCostGuard
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.telephony.MessageSender
import app.dak.telephony.NumberNormalizer
import app.dak.telephony.OutgoingSms
import app.dak.telephony.SendResult
import app.dak.telephony.SimRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SmsForwarder] over :core-telephony's [MessageSender]. The recipient is normalised to E.164 with the sending
 * SIM's home country (when the setting is on). A forward that would exceed the system send limit is scheduled for
 * the next free slot rather than dropped.
 */
@Singleton
class AndroidSmsForwarder @Inject constructor(
    private val sender: MessageSender,
    private val sims: SimRepository,
    private val normalizer: NumberNormalizer,
    private val settings: SettingsStore,
    private val throttle: SendThrottle,
    private val scheduler: ScheduledSendScheduler,
    private val costGuard: SendCostGuard,
    private val limits: UnattendedSendLimits,
) : SmsForwarder {

    override suspend fun forward(to: String, subId: Int?, text: String): Boolean {
        if (to.isBlank() || text.isEmpty()) return false
        val sub = resolveSub(subId) ?: return false
        val address = if (settings.get(DakSettings.numberNormalization)) normalizer.normalize(to, sub) else to
        // Unattended: never forward to a premium-rate number the user has not approved (see SendCostGuard).
        if (!costGuard.allowUnattended(address, sub)) return false
        if (!limits.tryConsume("forward")) return false
        val now = System.currentTimeMillis()
        val slot = throttle.reserve(now)
        if (slot > now + GRACE_MILLIS) {
            // Tagged as rule-driven so the executor re-checks the premium-rate guard when it finally sends.
            scheduler.schedule(listOf(address), text, sub, slot, ruleId = ScheduledSendScheduler.AUTO_FORWARD_TAG)
            return true
        }
        val result = runCatching { sender.sendSms(OutgoingSms(listOf(address), text, sub)) }.getOrNull()
        return result is SendResult.Queued
    }

    private fun resolveSub(requested: Int?): Int? {
        if (requested != null && requested != NO_SUB_ID && sims.sim(requested) != null) return requested
        val default = sims.defaultSmsSubId()
        if (default != NO_SUB_ID) return default
        return sims.sims.value.firstOrNull { it.isActive }?.subId
    }

    private companion object {
        const val GRACE_MILLIS = 5_000L
    }
}
