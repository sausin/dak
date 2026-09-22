package app.dak.automation

import android.content.Context
import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Rule
import app.dak.automations.rule.conditionsCanMatchOtp
import app.dak.automations.rule.isForwardingOrRelay
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which OTP-capable forwarding rules the user confirmed with a biometric check, per recipient set.
 * Changing a rule's recipients changes its fingerprint, so the rule stops forwarding until confirmed again; the
 * runner skips unconfirmed forwards and logs them.
 */
@Singleton
class OtpForwardConfirmations @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when [rule] forwards or relays and its conditions can match an OTP. */
    fun needsConfirmation(rule: Rule): Boolean =
        rule.actions.any { it.isForwardingOrRelay() } && conditionsCanMatchOtp(rule.conditions)

    fun isConfirmed(rule: Rule): Boolean =
        !needsConfirmation(rule) || prefs.getString(rule.id, null) == fingerprint(rule)

    /** Records a successful biometric confirmation of [rule] as it is now. */
    fun confirm(rule: Rule) {
        prefs.edit().putString(rule.id, fingerprint(rule)).apply()
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
        val digest = MessageDigest.getInstance("SHA-256").digest(recipients.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFS = "dak_otp_forward_confirmations"
    }
}
