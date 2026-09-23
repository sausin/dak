package app.dak.safety

import android.content.Context
import android.telephony.TelephonyManager
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.telephony.SimRepository
import app.dak.telephony.cost.CostKind
import app.dak.telephony.cost.CostPolicy
import app.dak.telephony.cost.CostVerdict
import app.dak.telephony.cost.DestinationCostClassifier
import app.dak.telephony.number.TelephonyNumberNormalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SMS cost checks for every send path, on top of :core-telephony's pure [DestinationCostClassifier]
 * (libphonenumber short-number tariffs, international and roaming detection):
 *
 * - Interactive sends (composer, forwards, send-later) call [toConfirm] and show a confirmation for what it returns.
 * - Unattended sends (automation forwards, rule-driven scheduled sends, notification quick replies) call
 *   [allowUnattended]: premium-rate destinations are refused unless the user approved that number on that SIM
 *   ("Don't ask again" in the composer's confirmation).
 *
 * Both are governed by the "Warn before costly SMS" setting ([DakSettings.costWarnings]); roaming prompts also
 * need [DakSettings.roamingWarnings]. Pass addresses as they will be sent (after E.164 normalisation).
 */
@Singleton
class SendCostGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sims: SimRepository,
    private val countries: TelephonyNumberNormalizer,
    private val settings: SettingsStore,
    private val approvals: CostApprovals,
) {
    private val classifier = DestinationCostClassifier()

    /** The cost verdict for sending to [address] from [subId]. */
    fun classify(address: String, subId: Int): CostVerdict {
        val home = runCatching { countries.homeCountry(subId) }.getOrNull()
        val network = networkCountry(subId)
        val roaming = runCatching { sims.isRoaming(subId) }.getOrDefault(false)
        return classifier.classify(address, home, network, roaming)
    }

    /** Destinations an interactive send to [addresses] should confirm first (loudest first); empty = send. */
    fun toConfirm(addresses: List<String>, subId: Int): List<CostVerdict> {
        if (!settings.get(DakSettings.costWarnings)) return emptyList()
        val verdicts = addresses.distinct().map { classify(it, subId) }
        return CostPolicy.toConfirm(verdicts, subId, approvals.keys(), warnRoaming = settings.get(DakSettings.roamingWarnings))
    }

    /** Whether an unattended send to [address] from [subId] may go out without asking. */
    fun allowUnattended(address: String, subId: Int): Boolean {
        if (!settings.get(DakSettings.costWarnings)) return true
        return CostPolicy.allowUnattended(classify(address, subId), subId, approvals.keys())
    }

    /** True when none of [addresses] is refused by [allowUnattended]. */
    fun allowUnattended(addresses: List<String>, subId: Int): Boolean = addresses.all { allowUnattended(it, subId) }

    /** Records "don't ask again" for these destinations on [subId] (also pre-approves them for automations). */
    fun approve(verdicts: List<CostVerdict>, subId: Int) {
        approvals.add(verdicts.map { CostPolicy.approvalKey(it.destination, subId) })
    }

    /** True if [verdicts] contain a premium-rate destination (strong warning). */
    fun hasPremium(verdicts: List<CostVerdict>): Boolean = verdicts.any { it.kind == CostKind.PREMIUM_RATE }

    private fun networkCountry(subId: Int): String? = try {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val forSub = if (tm != null && subId >= 0) tm.createForSubscriptionId(subId) else tm
        forSub?.networkCountryIso?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}

/** Persisted "don't ask again" approvals, keyed by [CostPolicy.approvalKey] (number + SIM). */
@Singleton
class CostApprovals @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun keys(): Set<String> = prefs.getStringSet(KEY, null)?.toSet().orEmpty()

    @Synchronized
    fun add(keys: Collection<String>) {
        if (keys.isEmpty()) return
        prefs.edit().putStringSet(KEY, HashSet(keys() + keys)).apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val PREFS = "dak_cost_approvals"
        const val KEY = "approved"
    }
}
