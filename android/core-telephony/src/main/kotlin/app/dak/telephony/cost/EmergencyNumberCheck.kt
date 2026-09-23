package app.dak.telephony.cost

import android.content.Context
import android.telephony.TelephonyManager
import app.dak.telephony.internal.isUsableSubId
import app.dak.telephony.number.TelephonyNumberNormalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [EmergencyDestinations] for a real SIM: the SIM's home country and the country of the network it is on. Used by
 * the sender (emergency texts skip the rate limiter and are never held) and by the composer (a text to an emergency
 * number stays possible while Dak is not the default SMS app).
 */
@Singleton
class EmergencyNumberCheck @Inject constructor(
    @ApplicationContext private val context: Context,
    private val normalizer: TelephonyNumberNormalizer,
) {
    fun isEmergency(address: String, subId: Int): Boolean {
        val home = runCatching { normalizer.homeCountry(subId) }.getOrNull()
        val network = try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            (if (tm != null && isUsableSubId(subId)) tm.createForSubscriptionId(subId) else tm)?.networkCountryIso
        } catch (e: Exception) {
            null
        }
        return EmergencyDestinations.isEmergency(address, home, network?.takeIf { it.isNotBlank() })
    }

    /** True when there is at least one recipient and every one of them is an emergency number. */
    fun allEmergency(addresses: List<String>, subId: Int): Boolean =
        addresses.isNotEmpty() && addresses.all { isEmergency(it, subId) }
}
