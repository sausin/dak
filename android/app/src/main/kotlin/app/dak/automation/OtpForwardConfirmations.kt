package app.dak.automation

import android.content.Context
import app.dak.automations.forwarding.ForwardingPolicy
import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Rule
import app.dak.automations.rule.activeWindow
import app.dak.automations.rule.conditionsCanMatchOtp
import app.dak.automations.rule.isForwardingOrRelay
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which high-risk forwarding rules the user confirmed with a biometric check: rules that can forward OTPs,
 * and SMS forwards over a long or open-ended period ([ForwardingPolicy.hasLongSmsForward]). A confirmation is tied to
 * a fingerprint of the recipients and, for time-boxed rules, the end of the period and whether OTPs can match; changing
 * any of them (e.g. extending the period) means the rule stops forwarding until confirmed again. The runner skips
 * unconfirmed forwards and logs them.
 *
 * Migration: rules saved before the period check (long / open-ended ones, and every time-boxed OTP rule, whose
 * fingerprint now includes the period) have no matching confirmation, so they stay in place but forward nothing until
 * the user confirms them again from the Forwarding or Automations screen.
 */
@Singleton
class OtpForwardConfirmations @Inject constructor(
    @ApplicationContext context: Context,
    private val reminders: OutboundRuleReminders,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when [rule] forwards or relays and its conditions can match an OTP, or forwards SMS over a long period. */
    fun needsConfirmation(rule: Rule): Boolean =
        (rule.actions.any { it.isForwardingOrRelay() } && conditionsCanMatchOtp(rule.conditions)) ||
            ForwardingPolicy.hasLongSmsForward(rule)

    fun isConfirmed(rule: Rule): Boolean =
        !needsConfirmation(rule) || prefs.getString(rule.id, null) == fingerprint(rule)

    /**
     * Records a successful biometric confirmation of [rule] as it is now. Confirming (or extending) an enabled rule
     * starts it forwarding, so it also arms the "Was this you?" reminder ([OutboundRuleReminders]).
     */
    fun confirm(rule: Rule) {
        prefs.edit().putString(rule.id, fingerprint(rule)).apply()
        reminders.armIfOutbound(rule)
    }

    fun forget(ruleId: String) {
        prefs.edit().remove(ruleId).apply()
    }

    private fun fingerprint(rule: Rule): String {
        val recipients = rule.actions.mapNotNull { action ->
            when (action) {
                is ActionSpec.ForwardSms -> "sms:${action.to}"
                is ActionSpec.RelayRule -> "relay:${action.channel}:${action.recipient}"
                is ActionSpec.Webhook -> "webhook:${action.url}"
                is ActionSpec.RelayToWebClient -> "web:${action.pairingId}"
                else -> null
            }
        }.sorted().joinToString("|")
        // Time-boxed rules (auto-forwarding) also pin the end of the period and OTP-ness; window-less automation rules
        // keep the recipients-only fingerprint they were confirmed with.
        val window = rule.activeWindow()
            ?.let { "|end:${it.endMillis ?: "open"}|otp:${conditionsCanMatchOtp(rule.conditions)}" }
            .orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest((recipients + window).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFS = "dak_otp_forward_confirmations"
    }
}
