package app.dak.telephony.region

import android.content.Context
import android.os.SystemClock
import android.telephony.TelephonyManager
import app.dak.telephony.SimRepository
import app.dak.telephony.TelephonySettings
import app.dak.telephony.internal.isUsableSubId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RegionProvider] from the phone: the SIM's home country (user override → `SubscriptionInfo.countryIso` →
 * `TelephonyManager.simCountryIso`), then the registered network's country, then the device locale, via
 * [RegionResolver]. Results are cached per subscription for [CACHE_MILLIS] so per-message callers (the classifier
 * during a backfill) do not make a binder call per message; SIM swaps are picked up within that window.
 */
@Singleton
class TelephonyRegionProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sims: SimRepository,
    private val settings: TelephonySettings,
) : RegionProvider {

    private class Cached(val profile: RegionProfile, val atMillis: Long)

    private val cache = ConcurrentHashMap<Int, Cached>()

    override fun current(): RegionProfile = forSubId(sims.defaultSmsSubId())

    override fun forSubId(subId: Int): RegionProfile {
        val now = SystemClock.elapsedRealtime()
        cache[subId]?.takeIf { now - it.atMillis < CACHE_MILLIS }?.let { return it.profile }
        val profile = resolve(subId)
        cache[subId] = Cached(profile, now)
        return profile
    }

    private fun resolve(subId: Int): RegionProfile {
        val tm = try {
            context.getSystemService(TelephonyManager::class.java)
        } catch (e: Exception) {
            null
        }
        val forSub = try {
            if (tm != null && isUsableSubId(subId)) tm.createForSubscriptionId(subId) else tm
        } catch (e: Exception) {
            tm
        }
        val simCountry = settings.homeCountryOverride(subId)?.takeIf { it.isNotBlank() }
            ?: sims.sim(subId)?.countryIso?.takeIf { it.isNotBlank() }
            ?: safe { forSub?.simCountryIso }
            // No usable subscription (e.g. before READ_PHONE_STATE): any active SIM's country beats the locale.
            ?: sims.sims.value.firstOrNull { it.isActive && !it.countryIso.isNullOrBlank() }?.countryIso
        val networkCountry = safe { forSub?.networkCountryIso } ?: safe { tm?.networkCountryIso }
        return RegionResolver.resolve(simCountry, networkCountry, Locale.getDefault().country)
    }

    private inline fun safe(block: () -> String?): String? = try {
        block()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val CACHE_MILLIS = 60_000L
    }
}
