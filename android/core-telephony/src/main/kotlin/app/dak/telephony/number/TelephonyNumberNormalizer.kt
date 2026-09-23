package app.dak.telephony.number

import android.content.Context
import android.telephony.TelephonyManager
import app.dak.telephony.NumberNormalizer
import app.dak.telephony.SimRepository
import app.dak.telephony.TelephonySettings
import app.dak.telephony.internal.isUsableSubId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [NumberNormalizer] using the home country of the SIM in use, never the current network: the user's
 * per-SIM override, else `SubscriptionInfo.countryIso`, else `TelephonyManager.simCountryIso` for that
 * subscription, else the default subscription's SIM country, else the device locale. The rules themselves live in
 * [E164Normalizer] (pure, JVM-tested).
 */
@Singleton
class TelephonyNumberNormalizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sims: SimRepository,
    private val settings: TelephonySettings,
) : NumberNormalizer {

    private val core by lazy { E164Normalizer() }

    override fun normalize(address: String, subId: Int): String = core.normalize(address, homeCountry(subId))

    override fun matchKey(address: String, subId: Int): String = core.matchKey(address, homeCountry(subId))

    /** The ISO country used for [subId] (lower case), or null when nothing is known. */
    fun homeCountry(subId: Int): String? {
        settings.homeCountryOverride(subId)?.let { return it }
        sims.sim(subId)?.countryIso?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
        val tm = try {
            context.getSystemService(TelephonyManager::class.java)
        } catch (e: Exception) {
            null
        }
        if (tm != null) {
            val simCountry = try {
                val forSub = if (isUsableSubId(subId)) tm.createForSubscriptionId(subId) else tm
                forSub.simCountryIso?.takeIf { it.isNotBlank() } ?: tm.simCountryIso?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
            if (simCountry != null) return simCountry.lowercase()
        }
        return Locale.getDefault().country.takeIf { it.length == 2 }?.lowercase()
    }
}
